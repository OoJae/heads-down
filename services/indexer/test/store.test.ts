import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { encodeBase58 } from "../src/codec/base58.ts";
import { decodeRig, encodeRig, type RigAccount } from "../src/codec/accounts.ts";
import { extractTransaction } from "../src/codec/tx.ts";
import { findProgramAddress, seed, addrBytes } from "../src/codec/pda.ts";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import { buildDigTx, buildEventTx } from "../src/sim/txbuilder.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { DatasetMismatchError, Store } from "../src/store/store.ts";

const HD = HEADS_DOWN_PROGRAM_ID;
const OPTS = { programId: HD, executorPda: EXECUTOR_PDA };
const addr = (n: number) => encodeBase58(Uint8Array.from({ length: 32 }, (_, i) => (i * 7 + n) & 0xff));
const sig = (n: number) => encodeBase58(Uint8Array.from({ length: 64 }, (_, i) => (i * 13 + n + 1) & 0xff));

let db: Db;
beforeAll(async () => {
  db = await openDb("pglite://memory");
  expect(await migrate(db)).toEqual(["001_init.sql"]);
  expect(await migrate(db)).toEqual([]); // idempotent
});
afterAll(async () => {
  await db.close();
});

function digTx(n: number, failed = false) {
  return buildDigTx({
    signature: sig(n),
    slot: 1000 + n,
    blockTime: 1_790_800_000 + n,
    cranker: addr(9),
    programId: HD,
    configPda: CONFIG_PDA,
    executorPda: EXECUTOR_PDA,
    roundAccount: addr(10),
    roundId: 422_700n + BigInt(n),
    failed,
    rigs: [
      { rig: addr(1), authority: addr(11), automation: addr(12), miner: addr(13), outcome: { kind: "dug", perTile: 66_666n, mask: 0x7fff, emaEv: 540_000_000n } },
      { rig: addr(2), authority: addr(21), automation: addr(22), miner: addr(23), outcome: { kind: "skipped", error: 1 } },
    ],
  });
}

describe("Store", () => {
  it("ingests a dig idempotently and reads it back as bigint rows", async () => {
    const store = await Store.bind(db, { name: "localnet", ...{ programId: HD, executorPda: EXECUTOR_PDA } });
    const x = extractTransaction(digTx(1), OPTS);
    expect(await store.ingestTxs([x], "test")).toBe(1);
    expect(await store.ingestTxs([x, x], "test")).toBe(0);
    const m = await store.loadMetricsInput();
    expect(m.digs).toHaveLength(1);
    expect(m.digs[0]).toMatchObject({ rig: addr(1), roundId: 422_701n, lamports: 999_990n, mask: 0x7fff, emaEv: 540_000_000n });
    expect(m.skips[0]).toMatchObject({ rig: addr(2), errorCode: 1 });
    expect(m.deploys[0]).toMatchObject({ authority: addr(11), amount: 66_666n, totalSquares: 15, roundId: 422_701n });
  });

  it("stores a failed tx without events", async () => {
    const store = await Store.bind(db, { name: "localnet", programId: HD, executorPda: EXECUTOR_PDA });
    expect(await store.ingestTxs([extractTransaction(digTx(2, true), OPTS)], "test")).toBe(1);
    const m = await store.loadMetricsInput();
    expect(m.digs.map((d) => d.signature)).not.toContain(sig(2));
    const h = await store.health();
    expect(h.failedTxs).toBe(1);
  });

  it("isolates datasets: simulated rows never appear in another dataset", async () => {
    const sim = await Store.bind(db, { name: "simulated", programId: HD, executorPda: EXECUTOR_PDA, simSeed: "t1" });
    const tx = buildEventTx({ signature: sig(3), slot: 5, blockTime: 5, signer: addr(9), programId: HD, events: [{ kind: "ShiftArmed", rig: addr(5), shiftId: 1n }] });
    await sim.ingestTxs([extractTransaction(tx, OPTS)], "sim");
    const mainnet = await Store.bind(db, { name: "mainnet", programId: HD, executorPda: EXECUTOR_PDA });
    expect((await mainnet.loadMetricsInput()).arms).toEqual([]);
    expect((await sim.loadMetricsInput()).arms).toHaveLength(1);
    expect((await sim.info()).simulated).toBe(true);
    expect((await mainnet.info()).simulated).toBe(false);
    // The same signature may exist in two datasets without colliding.
    await mainnet.ingestTxs([extractTransaction(tx, OPTS)], "test");
    expect((await mainnet.loadMetricsInput()).arms).toHaveLength(1);
  });

  it("refuses to bind a dataset to a different program or executor", async () => {
    await expect(Store.bind(db, { name: "mainnet", programId: HD, executorPda: addr(99) })).rejects.toThrow(DatasetMismatchError);
  });

  it("requires a seed for (and only for) the simulated dataset", async () => {
    await expect(Store.bind(db, { name: "simulated", programId: HD, executorPda: EXECUTOR_PDA })).rejects.toThrow(DatasetMismatchError);
    await expect(Store.bind(db, { name: "devnet", programId: HD, executorPda: EXECUTOR_PDA, simSeed: "x" })).rejects.toThrow(DatasetMismatchError);
  });

  it("enforces u64 and address domains in the database itself", async () => {
    await expect(
      db.query("INSERT INTO ore_rounds (dataset, round_id, ts, start_slot, end_slot, top_miner, total_miners, motherlode, total_deployed, total_vaulted, total_winnings, total_minted, rng, deployed_winning_square, source) VALUES ('mainnet', 18446744073709551616, 0,0,0,'11111111111111111111111111111111',0,0,0,0,0,0,0,0,'x')"),
    ).rejects.toThrow();
    await expect(
      db.query("INSERT INTO ore_rounds (dataset, round_id, ts, start_slot, end_slot, top_miner, total_miners, motherlode, total_deployed, total_vaulted, total_winnings, total_minted, rng, deployed_winning_square, source) VALUES ('mainnet', 1, 0,0,0,'not base58 0OIl',0,0,0,0,0,0,0,0,'x')"),
    ).rejects.toThrow();
  });

  it("marks accounts missing from a newer full scan as closed", async () => {
    const store = await Store.bind(db, { name: "devnet", programId: HD, executorPda: EXECUTOR_PDA });
    const mk = (auth: string): { address: string; account: RigAccount; data: Uint8Array } => {
      const { address, bump } = findProgramAddress([seed("rig"), addrBytes(auth)], HD);
      const account: RigAccount = {
        kind: "Rig", bump, authority: auth, p256Pubkey: "02" + "11".repeat(32), attestationLevel: 1, tier: 0, state: 0, sgtMint: null,
        attestationExpirySlot: 0n, capWeek: 0n, capShift: 0n, capRound: 0n, capMaxCost: 0n, capsExpiryTs: 0n, planMaxEvCost: 0n,
        planDigLamports: 0n, planSplitTiles: 15, planSoloTiles: 0, planLeaseRounds: 3, planFlags: 0, planWindowStartTs: 0n,
        planWindowEndTs: 0n, shiftId: 1n, hbCounter: 0n, leaseFromRound: 0n, leaseToRound: 0n, gapCount: 0, spentShift: 0n,
        spentWeek: 0n, weekStartTs: 0n, lastDugRound: 0n, shiftStartRound: 0n, shiftDarkRounds: 0n, shiftRoundsDug: 0n,
        lifetimeDarkRounds: 10n, lifetimeRoundsDug: 2n, lifetimeLamportsDeployed: 2_000_000n, streak: 1, freezesLeft: 2, lastShiftDay: 0n,
      };
      const data = encodeRig(account);
      expect(decodeRig(data)).toEqual(account);
      return { address, account, data };
    };
    const a = mk(addr(31));
    const b = mk(addr(32));
    await store.replaceAccounts({ rigs: [a, b], shiftLogs: [], seats: [], config: null }, 100);
    expect((await store.loadMetricsInput()).rigs.filter((r) => !r.closed)).toHaveLength(2);
    await store.replaceAccounts({ rigs: [a], shiftLogs: [], seats: [], config: null }, 200);
    const rigs = (await store.loadMetricsInput()).rigs;
    expect(rigs.find((r) => r.address === b.address)?.closed).toBe(true);
    expect(rigs.find((r) => r.address === a.address)?.closed).toBe(false);
    // An older scan cannot resurrect or overwrite newer state.
    await store.replaceAccounts({ rigs: [b], shiftLogs: [], seats: [], config: null }, 150);
    expect((await store.loadMetricsInput()).rigs.find((r) => r.address === b.address)?.closed).toBe(true);
  });

  it("cursors round-trip", async () => {
    const store = await Store.bind(db, { name: "devnet", programId: HD, executorPda: EXECUTOR_PDA });
    expect(await store.getCursor("rpc", "x")).toBeNull();
    await store.setCursor("rpc", "x", "abc");
    await store.setCursor("rpc", "x", "def");
    expect(await store.getCursor("rpc", "x")).toBe("def");
  });
});
