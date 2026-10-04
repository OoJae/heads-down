/**
 * ORE round resolver against a fake RPC: Round accounts are snapshotted only after their reset,
 * closed rounds are marked missing (and never asked for again), and a dug round without a
 * ResetEvent gets it from the transaction that created the next round's PDA (here the REAL mainnet
 * reset transaction of round 422,680, a v1 transaction).
 */
import { readFileSync } from "node:fs";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { ByteWriter } from "../src/codec/bytes.ts";
import { encodeOreRound, oreRoundPda, type OreRoundAccount } from "../src/codec/round.ts";
import type { RawTransaction } from "../src/codec/tx.ts";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID, ONE_ORE, ORE_BOARD, ORE_PROGRAM_ID, ORE_SPLIT_ADDRESS } from "../src/constants.ts";
import { extractTransaction } from "../src/codec/tx.ts";
import { resolveRounds } from "../src/sources/rounds.ts";
import { RpcClient } from "../src/sources/rpc.ts";
import { buildDigTx } from "../src/sim/txbuilder.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { Store } from "../src/store/store.ts";
import { addTx, addr, fakeChain, fakeRpcFetch, sig } from "./helpers.ts";

const HD = HEADS_DOWN_PROGRAM_ID;
let db: Db;
beforeAll(async () => {
  db = await openDb("pglite://memory");
  await migrate(db);
});
afterAll(async () => db.close());

const board = (roundId: bigint) => new ByteWriter(40).u64(105n).u64(roundId).u64(1n).u64(2n).u64(900_000_000n).finish();
function roundAccount(id: bigint, reset: boolean): OreRoundAccount {
  const deployed = Array.from({ length: 25 }, (_, i) => 1_000_000n + BigInt(i));
  return {
    id,
    deployed,
    slotHash: reset ? Uint8Array.from({ length: 32 }, (_, i) => i + 1) : new Uint8Array(32),
    expiresAt: 999n,
    motherlode: 0n,
    rentPayer: addr(3),
    rewards: [ONE_ORE, ...Array.from({ length: 24 }, () => 0n)],
    totalVaulted: 1n,
    totalReturnedSol: 2n,
    totalMiners: 7n,
    topMiner: ORE_SPLIT_ADDRESS,
  };
}
const dig = (n: number, roundId: bigint) =>
  buildDigTx({
    signature: sig(n), slot: 100 + n, blockTime: 1_790_800_000 + n, cranker: addr(9), programId: HD, configPda: CONFIG_PDA, executorPda: EXECUTOR_PDA,
    roundAccount: oreRoundPda(roundId), roundId,
    rigs: [{ rig: addr(1), authority: addr(11), automation: addr(12), miner: addr(13), outcome: { kind: "dug", perTile: 10n, mask: 0b11, emaEv: 1n } }],
  });

describe("ORE round resolver", () => {
  it("snapshots reset rounds, marks closed ones missing, waits on the live round, and finds a ResetEvent on chain", async () => {
    const store = await Store.bind(db, { name: "localnet", programId: HD, executorPda: EXECUTOR_PDA });
    const ctx = { store, programId: HD, executorPda: EXECUTOR_PDA };
    // Heads Down dug rounds 422,680 (account since closed), 422,690 (reset) and 422,700 (still live).
    await store.ingestTxs([dig(1, 422_680n), dig(2, 422_690n), dig(3, 422_700n)].map((t) => extractTransaction(t, ctx)), "test");
    const chain = fakeChain();
    chain.accounts.push({ address: ORE_BOARD, owner: ORE_PROGRAM_ID, data: board(422_700n) });
    chain.accounts.push({ address: oreRoundPda(422_690n), owner: ORE_PROGRAM_ID, data: encodeOreRound(roundAccount(422_690n, true)) });
    chain.accounts.push({ address: oreRoundPda(422_700n), owner: ORE_PROGRAM_ID, data: encodeOreRound(roundAccount(422_700n, false)) });
    // The real reset of round 422,680 created the Round PDA of 422,681: its oldest signature.
    const reset = JSON.parse(readFileSync(new URL("./fixtures/ore-reset-mainnet-v1.json", import.meta.url), "utf8")) as RawTransaction;
    addTx(chain, reset, [oreRoundPda(422_681n)]);
    const rpc = new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: async () => {} });

    const r = await resolveRounds(ctx, rpc, { maxRounds: 50, withShiftRounds: true, resetLookups: 5 });
    expect(r).toEqual({ board: "422700", snapshots: 1, missing: 1, pending: 1, resets: 1 });
    const { resets, states } = await store.roundOutcomeRows(422_680n, 422_700n);
    expect(states.map((s) => s.roundId)).toEqual([422_690n]);
    expect(resets).toHaveLength(1);
    expect(resets[0]).toMatchObject({ roundId: 422_680n, winningSquare: 22, totalMiners: 170n, resetSignature: reset.transaction.signatures[0] });

    // The second pass reads only the Board: the live round is not asked for until the Board moves past it.
    chain.calls.length = 0;
    const r2 = await resolveRounds(ctx, rpc, { maxRounds: 50, withShiftRounds: true, resetLookups: 5 });
    expect(r2).toMatchObject({ snapshots: 0, missing: 0, pending: 1 });
    const asked = chain.calls.filter((c) => c.method === "getMultipleAccounts").flatMap((c) => c.params[0] as string[]);
    expect(asked).toEqual([ORE_BOARD]);
    // Once it is reset, it is snapshotted.
    chain.accounts[0] = { address: ORE_BOARD, owner: ORE_PROGRAM_ID, data: board(422_701n) };
    chain.accounts[2] = { address: oreRoundPda(422_700n), owner: ORE_PROGRAM_ID, data: encodeOreRound(roundAccount(422_700n, true)) };
    expect(await resolveRounds(ctx, rpc, { maxRounds: 50, withShiftRounds: true, resetLookups: 0 })).toMatchObject({ snapshots: 1, pending: 0 });
    expect((await store.health()).problems).toEqual([]);
  });

  it("asks the RPC nothing while no round is waiting for its outcome", async () => {
    const store = await Store.bind(db, { name: "mainnet", programId: HD, executorPda: EXECUTOR_PDA });
    const ctx = { store, programId: HD, executorPda: EXECUTOR_PDA };
    const chain = fakeChain();
    chain.accounts.push({ address: ORE_BOARD, owner: ORE_PROGRAM_ID, data: board(600n) });
    const rpc = new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: async () => {} });
    // Nothing was dug: not even the Board is read.
    expect(await resolveRounds(ctx, rpc, { maxRounds: 50, withShiftRounds: true, resetLookups: 5 })).toEqual({ board: null, snapshots: 0, missing: 0, pending: 0, resets: 0 });
    expect(chain.calls).toEqual([]);
    // A dig in round 599, which has been reset: the Board and that Round account are read.
    await store.ingestTxs([extractTransaction(dig(20, 599n), ctx)], "test");
    chain.accounts.push({ address: oreRoundPda(599n), owner: ORE_PROGRAM_ID, data: encodeOreRound(roundAccount(599n, true)) });
    expect(await resolveRounds(ctx, rpc, { maxRounds: 50, withShiftRounds: true, resetLookups: 0 })).toMatchObject({ board: "600", snapshots: 1 });
    expect(chain.calls.map((c) => c.method)).toEqual(["getMultipleAccounts", "getMultipleAccounts"]);
    // Its outcome is stored: the next pass is free again.
    chain.calls.length = 0;
    expect((await resolveRounds(ctx, rpc, { maxRounds: 50, withShiftRounds: true, resetLookups: 0 })).board).toBeNull();
    expect(chain.calls).toEqual([]);
  });

  it("rejects a Round account not owned by ORE", async () => {
    const store = await Store.bind(db, { name: "devnet", programId: HD, executorPda: EXECUTOR_PDA });
    const ctx = { store, programId: HD, executorPda: EXECUTOR_PDA };
    await store.ingestTxs([extractTransaction(dig(9, 500n), ctx)], "test");
    const chain = fakeChain();
    chain.accounts.push({ address: ORE_BOARD, owner: ORE_PROGRAM_ID, data: board(501n) });
    chain.accounts.push({ address: oreRoundPda(500n), owner: addr(66), data: encodeOreRound(roundAccount(500n, true)) });
    const rpc = new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: async () => {} });
    const r = await resolveRounds(ctx, rpc, { maxRounds: 50, withShiftRounds: false, resetLookups: 0 });
    expect(r.snapshots).toBe(0);
    expect((await store.health()).problems).toEqual([{ code: "NOT_ORE_OWNED", count: 1 }]);
  });
});
