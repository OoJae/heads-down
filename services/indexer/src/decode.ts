/**
 * `decode <signature>`: one transaction, explained in words, for demo captions and debugging.
 * Every heads_down instruction is decoded (fields and account roles) even when the transaction
 * failed; events are shown only for a successful transaction (a failed one changed nothing).
 */
import { breakReasonName, hdErrorName, hdErrorRange, skipLabel, SHIFT_MODE_NAMES, type HdEvent } from "./codec/events.ts";
import { hdAccountRoles, NO_HEARTBEAT } from "./codec/ix.ts";
import { extractTransaction, type RawTransaction } from "./codec/tx.ts";
import { ORE_SPLIT_ADDRESS } from "./constants.ts";

export interface Described {
  signature: string;
  slot: number;
  blockTime: number | null;
  status: "success" | "failed";
  failure: string | null;
  feePayer: string;
  instructions: { path: string; name: string; fields: Record<string, string>; accounts: { role: string; address: string }[]; heartbeats: string[] }[];
  events: { kind: string; text: string; fields: Record<string, string> }[];
  ore: string[];
  problems: string[];
}

const str = (v: unknown) => (typeof v === "bigint" ? v.toString() : String(v));
const short = (a: string) => `${a.slice(0, 4)}…${a.slice(-4)}`;

/** Plain-language line for one heads_down event. */
export function eventText(e: HdEvent): string {
  switch (e.kind) {
    case "RigDug":
      return `RigDug: rig ${short(e.rig)} dug ORE round ${e.roundId}: ${e.lamports} lamports on ${popcount(e.mask)} squares (gate cost ${e.emaEv} lamports/ORE)`;
    case "RigSkipped":
      return `RigSkipped: rig ${short(e.rig)} in round ${e.roundId}: ${hdErrorName(e.error)} = ${skipLabel(e.error)}`;
    case "ShiftArmed":
      return `ShiftArmed: rig ${short(e.rig)} armed shift ${e.shiftId}`;
    case "ShiftEnded":
      return `ShiftEnded: rig ${short(e.rig)} shift ${e.shiftId}: ${e.darkRounds} dark rounds, ${e.roundsDug} dug, ${e.lamports} lamports spent, reason ${breakReasonName(e.reason)}`;
    case "ShiftEndedV2":
      return `ShiftEndedV2: rig ${short(e.rig)} shift ${e.shiftId} (rounds ${e.startRound}..${e.endRound}, ${SHIFT_MODE_NAMES[e.mode] ?? `mode ${e.mode}`}): ${e.darkRounds} dark rounds, ${e.roundsDug} dug, ${e.lamports} lamports spent, reason ${breakReasonName(e.reason)}`;
    case "SeekerVerified":
      return `SeekerVerified: rig ${short(e.rig)} holds Seeker Genesis Token ${short(e.sgtMint)} (member #${e.memberNumber})`;
    case "RigRegistered":
      return `RigRegistered: rig ${short(e.rig)} for wallet ${short(e.authority)} (tier ${e.tier === 1 ? "seeker" : "guest"}, attestation level ${e.attestationLevel})`;
    case "RigClosed":
      return `RigClosed: rig ${short(e.rig)}`;
    case "HeartbeatsRecorded":
      return `HeartbeatsRecorded: rig ${short(e.rig)} in round ${e.roundId}: +${e.darkRoundsAdded} dark rounds`;
    case "ShiftBroken":
      return `ShiftBroken: rig ${short(e.rig)} shift ${e.shiftId}: ${breakReasonName(e.reason)}`;
  }
}

function popcount(mask: number): number {
  let m = mask >>> 0;
  let n = 0;
  while (m) {
    m &= m - 1;
    n++;
  }
  return n;
}

const FAILED_LINE = /^Program ([1-9A-HJ-NP-Za-km-z]{32,44}) failed: custom program error: 0x([0-9a-fA-F]{1,8})$/;

/**
 * Names a failed transaction's error. A custom code only means something to the program that raised
 * it, and a CPI's error surfaces through every caller with the same code, so the raiser is read from
 * the logs (the first `Program <id> failed` line), else taken to be the failing top-level
 * instruction's program. Only heads_down's own codes get heads_down names.
 */
function failureText(tx: RawTransaction, programId: string): string {
  const err = tx.meta?.err;
  const ie = (err as { InstructionError?: [number, unknown] } | null)?.InstructionError;
  if (!Array.isArray(ie)) return JSON.stringify(err);
  const [i, e] = ie;
  const custom = (e as { Custom?: number } | null)?.Custom;
  if (typeof custom !== "number") return `instruction ${i} failed: ${typeof e === "string" ? e : JSON.stringify(e)}`;
  const logs = Array.isArray(tx.meta?.logMessages) ? tx.meta.logMessages : [];
  const first = logs.map((l) => (typeof l === "string" ? FAILED_LINE.exec(l) : null)).find((m) => m !== null && m !== undefined);
  const top = (tx.transaction.message.instructions?.[i] as { programIdIndex?: unknown } | undefined)?.programIdIndex;
  const key = typeof top === "number" ? tx.transaction.message.accountKeys[top] : undefined;
  const topProgram = typeof key === "string" ? key : (key?.pubkey ?? null);
  const raisedBy = first ? first[1]! : topProgram;
  if (raisedBy === programId) return `instruction ${i} failed: ${hdErrorName(custom)} (${custom}, ${hdErrorRange(custom)})`;
  return `instruction ${i} failed: custom error ${custom} (0x${custom.toString(16)}) raised by ${raisedBy ?? "an unknown program"}, not by heads_down`;
}

export function describeTransaction(tx: RawTransaction, opts: { programId: string; executorPda: string }): Described {
  const failed = tx.meta?.err !== null && tx.meta?.err !== undefined;
  // Decode the instructions from a successful copy so a failed transaction still shows what it tried.
  const asSuccess: RawTransaction = failed ? { ...tx, meta: { ...tx.meta!, err: null, logMessages: [] } } : tx;
  const x = extractTransaction(asSuccess, opts);
  const out: Described = {
    signature: x.signature,
    slot: x.slot,
    blockTime: x.blockTime,
    status: failed ? "failed" : "success",
    failure: failed ? failureText(tx, opts.programId) : null,
    feePayer: x.feePayer,
    instructions: x.hdInstructions.map((h) => {
      const roles = hdAccountRoles(h.ix, h.accounts.length);
      const hbs = x.heartbeats.filter((b) => b.ixIndex === h.index);
      return {
        path: h.path,
        name: h.ix.name,
        fields: Object.fromEntries(h.ix.fields.filter((f) => f.name !== "tag" && !f.name.endsWith("_pad")).map((f) => [f.name, str(f.value)])),
        accounts: h.accounts.map((a, i) => ({ role: roles[i] ?? `extra[${i}]`, address: a })),
        heartbeats: hbs.map((b) =>
          b.fresh
            ? `rig ${short(b.rig)}: heartbeat #${b.counter} signed for round ${b.hbRound}, lease ${b.leaseRounds}` +
              (failed ? "" : b.applied === true ? " -> applied (lease granted)" : b.applied === false ? " -> refused" : " -> outcome unknown")
            : `rig ${short(b.rig)}: reuses its current lease (no heartbeat, hb_ix 0x${NO_HEARTBEAT.toString(16)})`,
        ),
      };
    }),
    events: failed
      ? []
      : x.hdEvents.map(({ event }) => ({
          kind: event.kind,
          text: eventText(event),
          fields: Object.fromEntries(Object.entries(event).filter(([k]) => k !== "kind").map(([k, v]) => [k, str(v)])),
        })),
    ore: failed
      ? []
      : [
          ...x.oreDeploys.map(({ event: d }) => `ORE DeployEvent (signer = Executor PDA): wallet ${short(d.authority)} placed ${d.amount} lamports on each of ${d.totalSquares} squares in round ${d.roundId}`),
          ...x.oreResets.map(
            ({ event: r }) =>
              `ORE ResetEvent: round ${r.roundId} winning square ${r.winningSquare ?? "none (refund)"} (${r.topMiner === ORE_SPLIT_ADDRESS ? "split" : `solo, top miner ${short(r.topMiner)}`}), ${r.totalMiners} miners${r.motherlode > 0n ? `, Motherlode ${r.motherlode} atoms` : ""}`,
          ),
        ],
    problems: x.problems.filter((p) => !failed || !p.code.startsWith("IX_LOG")).map((p) => `${p.code} at ${p.location}: ${p.message}`),
  };
  return out;
}

export function formatDescribed(d: Described, link: (sig: string) => string | null): string {
  const lines: string[] = [];
  lines.push(`${d.signature}`);
  lines.push(`  slot ${d.slot}${d.blockTime !== null ? `, ${new Date(d.blockTime * 1000).toISOString()}` : ""}, fee payer ${d.feePayer}`);
  lines.push(`  status: ${d.status}${d.failure ? ` (${d.failure})` : ""}`);
  const url = link(d.signature);
  if (url) lines.push(`  explorer: ${url}`);
  for (const ix of d.instructions) {
    lines.push(`  heads_down ${ix.name} [ix ${ix.path}]`);
    for (const [k, v] of Object.entries(ix.fields)) lines.push(`    ${k} = ${v}`);
    for (const a of ix.accounts) lines.push(`    ${a.role.padEnd(20)} ${a.address}`);
    for (const h of ix.heartbeats) lines.push(`    - ${h}`);
  }
  if (d.events.length) lines.push("  events:");
  for (const e of d.events) lines.push(`    ${e.text}`);
  if (d.ore.length) lines.push("  ORE:");
  for (const o of d.ore) lines.push(`    ${o}`);
  for (const p of d.problems) lines.push(`  problem: ${p}`);
  if (d.instructions.length === 0 && d.events.length === 0) lines.push("  (no heads_down instruction in this transaction)");
  return lines.join("\n");
}
