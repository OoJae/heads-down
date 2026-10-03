/**
 * ORE round resolver: makes every round a haul needs final, from chain data only.
 *
 *  1. Round accounts. For each round with a Heads Down deploy (and, where no ResetEvent source
 *     exists, every round a haul lists for a recently ended shift) the ORE `Round` account is read once its reset has
 *     run (slot hash set). It carries each square's total, which ORE's checkpoint divides by, so
 *     SOL returned becomes exact. ORE closes Round accounts about a day after the round; a round
 *     that is gone is marked missing and never asked for again.
 *  2. ResetEvents. For a dug round with no ResetEvent (localnet and devnet have no api.ore.com;
 *     on mainnet the API can lag), the reset transaction is found on chain: ORE's `reset` of round
 *     r creates the Round PDA of r + 1, so the OLDEST signature of that address is the reset of r.
 *     Its Board-signed ORE Log carries the ResetEvent (and the reset transaction's signature, the
 *     verifiable link).
 *
 * Only rounds below the Board's current round are resolved: their reset has already happened.
 */
import { DecodeError } from "../codec/errors.ts";
import { decodeOreBoard, decodeOreRound, isRoundReset, oreRoundPda, type OreRoundAccount } from "../codec/round.ts";
import { extractTransaction } from "../codec/tx.ts";
import { ORE_BOARD, ORE_PROGRAM_ID } from "../constants.ts";
import { HAUL_MAX_ROUNDS } from "../metrics/haul.ts";
import type { IngestContext } from "../ingest.ts";
import type { RpcClient, SignatureInfo } from "./rpc.ts";

export interface ResolveOptions {
  /** Round accounts to request per pass. */
  maxRounds: number;
  /** Also resolve every round of ended shifts that has neither a snapshot nor a ResetEvent. */
  withShiftRounds: boolean;
  /** Reset transactions to look up per pass. */
  resetLookups: number;
}

export interface ResolveResult {
  board: string | null;
  snapshots: number;
  missing: number;
  pending: number;
  resets: number;
}

export async function resolveRounds(ctx: IngestContext, rpc: RpcClient, opts: ResolveOptions): Promise<ResolveResult> {
  const out: ResolveResult = { board: null, snapshots: 0, missing: 0, pending: 0, resets: 0 };
  const ids = await ctx.store.roundsToResolve(opts.maxRounds, opts.withShiftRounds, HAUL_MAX_ROUNDS);
  const board = await rpc.getMultipleAccounts([ORE_BOARD]);
  const b = board.accounts[0];
  if (!b || b.owner !== ORE_PROGRAM_ID) {
    await ctx.store.recordProblem(ORE_BOARD, "round resolver", "ORE_BOARD", "ORE Board missing or not owned by ORE");
    return out;
  }
  let boardRound: bigint;
  try {
    boardRound = decodeOreBoard(b.data).roundId;
  } catch (e) {
    if (!(e instanceof DecodeError)) throw e;
    await ctx.store.recordProblem(ORE_BOARD, "round resolver", e.code, e.message);
    return out;
  }
  out.board = boardRound.toString();

  const finished = ids.filter((id) => id < boardRound);
  out.pending += ids.length - finished.length;
  for (let i = 0; i < finished.length; i += 100) {
    const chunk = finished.slice(i, i + 100);
    const res = await rpc.getMultipleAccounts(chunk.map(oreRoundPda));
    const snaps: { address: string; account: OreRoundAccount; data: Uint8Array; contextSlot: number }[] = [];
    const gone: bigint[] = [];
    for (let k = 0; k < chunk.length; k++) {
      const id = chunk[k]!;
      const a = res.accounts[k];
      if (a === null || a === undefined) {
        gone.push(id);
        continue;
      }
      if (a.owner !== ORE_PROGRAM_ID) {
        await ctx.store.recordProblem(a.address, "round resolver", "NOT_ORE_OWNED", `Round ${id} is not owned by ORE`);
        continue;
      }
      try {
        const acc = decodeOreRound(a.data);
        if (acc.id !== id) throw new DecodeError("BAD_FIELD", `Round PDA of ${id} holds round ${acc.id}`);
        if (!isRoundReset(acc)) {
          out.pending++;
          continue;
        }
        snaps.push({ address: a.address, account: acc, data: a.data, contextSlot: res.slot });
      } catch (e) {
        if (!(e instanceof DecodeError)) throw e;
        await ctx.store.recordProblem(a.address, "round resolver", e.code, e.message);
      }
    }
    if (snaps.length) await ctx.store.upsertRoundStates(snaps, "round-account");
    if (gone.length) await ctx.store.markRoundsMissing(gone);
    out.snapshots += snaps.length;
    out.missing += gone.length;
  }

  if (opts.resetLookups > 0) {
    const need = (await ctx.store.dugRoundsWithoutReset(opts.resetLookups * 2)).filter((id) => id < boardRound).slice(0, opts.resetLookups);
    const found: { event: import("../codec/ore.ts").OreResetEvent; resetSignature: string }[] = [];
    for (const id of need) {
      const r = await findResetEvent(ctx, rpc, id);
      if (r) found.push(r);
    }
    if (found.length) await ctx.store.upsertRounds(found, "chain");
    out.resets = found.length;
  }
  return out;
}

/** The ResetEvent of round `id`, from the transaction that created the Round PDA of `id + 1`. */
export async function findResetEvent(ctx: IngestContext, rpc: RpcClient, id: bigint): Promise<{ event: import("../codec/ore.ts").OreResetEvent; resetSignature: string } | null> {
  const next = oreRoundPda(id + 1n);
  let last: SignatureInfo[] = [];
  let before: string | undefined;
  for (let page = 0; page < 5; page++) {
    const sigs = await rpc.getSignaturesForAddress(next, { before, limit: 1000 });
    if (!Array.isArray(sigs) || sigs.length === 0) break;
    last = sigs;
    if (sigs.length < 1000) break;
    before = sigs[sigs.length - 1]!.signature;
  }
  // Oldest first; failed transactions (e.g. a reset attempt that lost the race) changed nothing.
  const candidates = [...last].reverse().filter((s) => s.err === null || s.err === undefined).slice(0, 3);
  for (const c of candidates) {
    const tx = await rpc.getTransaction(c.signature);
    if (!tx) continue;
    const x = extractTransaction(tx, { programId: ctx.programId, executorPda: ctx.executorPda });
    const reset = x.oreResets.find((e) => e.event.roundId === id);
    if (reset) return { event: reset.event, resetSignature: x.signature };
  }
  return null;
}
