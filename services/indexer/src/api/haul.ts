/**
 * Assembles one rig's morning haul (contract B) from the store: picks the shift, gathers its
 * activity and the ORE outcome of every round it spans, replays the streak, and asks the market
 * source for a quote. The arithmetic itself is in metrics/haul.ts.
 */
import { decodeOreRound } from "../codec/round.ts";
import { findProgramAddress, seed, addrBytes, u64le } from "../codec/pda.ts";
import { computeHaul, lastListedRound, type HaulDiagnostics, type HaulSummary } from "../metrics/haul.ts";
import { outcomeFromReset, outcomeFromRoundAccount, type RoundOutcome } from "../metrics/oremath.ts";
import { replayStreaks, type EndedShift } from "../metrics/streak.ts";
import type { Dataset } from "../model.ts";
import type { MarketPrice } from "../sources/market.ts";
import type { RigShifts, Store } from "../store/store.ts";

export type HaulResponse =
  | { status: 200; haul: HaulSummary; diagnostics: HaulDiagnostics }
  | { status: 404; error: string; retryAfterS?: number };

export interface HaulDeps {
  store: Store;
  dataset: Dataset;
  simulated: boolean;
  programId: string;
  market: MarketPrice | null;
  link: (kind: "tx" | "account", id: string) => string | null;
  /** Unix seconds (the simulated dataset passes its frozen asOf). */
  now?: () => number;
}

/** How long after end_shift the haul waits for the shift's last round to reset (display-only data). */
export const END_ROUND_WAIT_S = 1800;

/** The `X-HeadsDown-Haul-Checks` header: how the haul's own cross-checks came out. */
export function haulChecks(d: HaulDiagnostics): string {
  const parts = [
    `dark=${d.darkMatches ? "match" : `mismatch(${d.reconstructedDark})`}`,
    `sol-returned=${d.solReturnedExact ? "exact" : "approx"}`,
    `deploys=${d.deploysMatchDigs ? "match" : "mismatch"}`,
  ];
  if (BigInt(d.roundsListed) < d.roundsInShift) parts.push(`rounds=first ${d.roundsListed} of ${d.roundsInShift}`);
  return parts.join("; ");
}

export function shiftLogAddress(rig: string, shiftId: bigint, programId: string): string {
  return findProgramAddress([seed("shift"), addrBytes(rig), u64le(shiftId)], programId).address;
}

type End = RigShifts["ends"][number];

/** Order key on chain: slot, then the event's position (registrations sort before shifts in a slot). */
const order = (slot: number, idx: number) => BigInt(slot) * 1_000_000n + BigInt(idx);

/** Ended shifts, one per (registration epoch, shift_id); tag 10 preferred (the extractor already drops a paired tag 4). */
function endedShifts(rs: RigShifts): (End & { epoch: number })[] {
  const regSlots = rs.registered.map((r) => r.slot);
  const epochOf = (slot: number) => regSlots.filter((s) => s <= slot).length;
  const byKey = new Map<string, End & { epoch: number }>();
  for (const e of rs.ends) {
    const k = `${epochOf(e.slot)}:${e.shiftId}`;
    const prev = byKey.get(k);
    if (!prev || (prev.tag === 4 && e.tag === 10)) byKey.set(k, { ...e, epoch: epochOf(e.slot) });
  }
  return [...byKey.values()].sort((a, b) => a.slot - b.slot || a.idx - b.idx);
}

export async function loadHaul(deps: HaulDeps, rig: string, which: "latest" | bigint): Promise<HaulResponse> {
  const rs = await deps.store.rigShifts(rig);
  const ends = endedShifts(rs);
  if (ends.length === 0) return { status: 404, error: "no finished shift for this rig" };
  const end =
    which === "latest"
      ? ends[ends.length - 1]!
      : (ends.filter((e) => e.shiftId === which).sort((a, b) => b.epoch - a.epoch || b.slot - a.slot)[0] ?? null);
  // Shift ids restart when a rig is closed and registered again; the latest epoch wins.
  if (!end) return { status: 404, error: `no finished shift ${which} for this rig` };

  const log = rs.shiftLogs.find((l) => l.shiftId === end.shiftId && !l.closed) ?? null;
  const startRound = end.startRound ?? log?.startRound ?? null;
  const endRound = end.endRound ?? log?.endRound ?? null;
  const mode = end.mode ?? log?.mode ?? null;
  if (startRound === null || endRound === null || mode === null) {
    return { status: 404, error: `shift ${end.shiftId} has neither a ShiftEndedV2 event nor a ShiftLog account (pre-v1.1 program)` };
  }
  const arm = rs.arms.filter((a) => a.shiftId === end.shiftId && a.slot <= end.slot).sort((a, b) => b.slot - a.slot)[0] ?? null;
  const startTs = log?.startTs ?? (arm?.blockTime != null ? BigInt(arm.blockTime) : null);
  const endTs = log?.endTs ?? (end.blockTime !== null ? BigInt(end.blockTime) : null);
  if (startTs === null || endTs === null) {
    return { status: 404, error: `shift ${end.shiftId}: its arm_shift transaction is not indexed (and no ShiftLog snapshot)` };
  }

  // Outcomes are loaded for the rounds the haul lists (and for any dug round beyond them).
  const listEnd = lastListedRound(startRound, endRound);
  const act = await deps.store.shiftActivity(rig, {
    fromSlot: arm?.slot ?? 0,
    toSlot: end.slot,
    startRound,
    endRound: listEnd,
    armSignature: arm?.signature ?? null,
  });
  const outcomes = new Map<bigint, RoundOutcome>();
  for (const r of act.resets) outcomes.set(r.roundId, outcomeFromReset(r));
  for (const st of act.states) {
    const o = outcomeFromRoundAccount(decodeOreRound(st.data), outcomes.get(st.roundId)?.resetSignature ?? null);
    if (o) outcomes.set(st.roundId, o);
  }

  // Streak: replay every ended shift of the rig (end time from its ShiftLog, else its block time).
  const replay: EndedShift[] = ends.map((e) => {
    const l = rs.shiftLogs.find((x) => x.shiftId === e.shiftId && !x.closed);
    const t = e === end ? endTs : (l?.endTs ?? BigInt(e.blockTime ?? 0));
    return { shiftId: BigInt(e.epoch) * (1n << 64n) + e.shiftId, endTs: t, reason: e.reason, darkRounds: e.darkRounds, order: order(e.slot, e.idx) };
  });
  const streaks = replayStreaks(replay, rs.registered.map((r) => order(r.slot, -1)));
  const streak = streaks.get(BigInt(end.epoch) * (1n << 64n) + end.shiftId) ?? { before: 0, after: 0 };

  const authority = rs.registered[rs.registered.length - 1]?.authority ?? rs.account?.authority ?? act.heartbeats.find((h) => h.authority)?.authority ?? null;
  const market = deps.market ? await deps.market.get() : null;
  const inShift = (r: bigint) => r >= startRound && r <= endRound;
  const result = computeHaul({
    rig,
    authority,
    shiftId: end.shiftId,
    startRound,
    endRound,
    mode,
    reason: end.reason,
    darkRounds: end.darkRounds,
    roundsDug: end.roundsDug,
    lamports: end.lamports,
    startTs,
    endTs,
    planLease: act.plan?.lease ?? null,
    heartbeats: act.heartbeats.filter((h) => h.applied === true).map((h) => ({ counter: h.counter, hbRound: h.hbRound, leaseRounds: h.leaseRounds })),
    digs: act.digs.filter((d) => inShift(d.roundId)),
    deploys: act.deploys.filter((d) => (authority === null || d.authority === authority) && inShift(d.roundId)),
    outcomes,
    streak,
    market: market ? { lamportsPerOre: market.lamportsPerOre, source: market.source } : null,
    simulated: deps.simulated,
    explorer: { shiftLog: deps.link("account", shiftLogAddress(rig, end.shiftId, deps.programId)), digUrl: (s) => deps.link("tx", s) },
    // The end round was live at end_shift: wait for its reset while the shift is fresh, so the rounds of a served haul do not change later.
    alsoRequired: listEnd === endRound && (deps.now ? deps.now() : Math.floor(Date.now() / 1000)) - Number(endTs) < END_ROUND_WAIT_S ? [endRound] : [],
  });
  if (!result.final) {
    return {
      status: 404,
      error: `shift ${end.shiftId} ended but its haul is not final yet: waiting for ORE round(s) ${result.pendingRounds.join(", ")} to reset`,
      retryAfterS: 30,
    };
  }
  return { status: 200, haul: result.haul, diagnostics: result.diagnostics };
}

/** Ended shifts of a rig for the dashboard's shift picker (newest first). */
export async function listRigShifts(store: Store, rig: string): Promise<{ shiftId: string; endedAt: number | null; darkRounds: string; roundsDug: string; reason: number; mode: number | null; signature: string }[]> {
  const rs = await store.rigShifts(rig);
  return endedShifts(rs)
    .reverse()
    .map((e) => ({
      shiftId: e.shiftId.toString(),
      endedAt: e.blockTime,
      darkRounds: e.darkRounds.toString(),
      roundsDug: e.roundsDug.toString(),
      reason: e.reason,
      mode: e.mode,
      signature: e.signature,
    }));
}
