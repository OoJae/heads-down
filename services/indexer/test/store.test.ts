import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { encodeBase58 } from "../src/codec/base58.ts";
import { decodeRig, encodeRig, type RigAccount } from "../src/codec/accounts.ts";
import { extractTransaction } from "../src/codec/tx.ts";
import { findProgramAddress, seed, addrBytes } from "../src/codec/pda.ts";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import { buildDigTx, buildEventTx } from "../src/sim/txbuilder.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { DatasetMismatchError, Store } from "../src/store/store.ts";
import { computeSkrSummary } from "../src/metrics/skr.ts";
import { GOLDEN_VECTORS, goldenTx } from "./helpers.ts";

const HD = HEADS_DOWN_PROGRAM_ID;
const OPTS = { programId: HD, executorPda: EXECUTOR_PDA };
const addr = (n: number) => encodeBase58(Uint8Array.from({ length: 32 }, (_, i) => (i * 7 + n) & 0xff));
const sig = (n: number) => encodeBase58(Uint8Array.from({ length: 64 }, (_, i) => (i * 13 + n + 1) & 0xff));

let db: Db;
beforeAll(async () => {
  db = await openDb("pglite://memory");
  expect(await migrate(db)).toEqual(["001_init.sql", "002_v1_1.sql", "003_v1_3.sql"]);
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

  it("keeps the time and outcome of the last ingest pass per dataset", async () => {
    const devnet = await Store.bind(db, { name: "devnet", programId: HD, executorPda: EXECUTOR_PDA });
    const none = { lastPollAt: null, lastPollOk: null, lastOkPollAt: null };
    expect(await devnet.health()).toMatchObject(none);
    await devnet.recordPoll(1_790_800_000, false);
    expect(await devnet.health()).toMatchObject({ lastPollAt: 1_790_800_000, lastPollOk: false, lastOkPollAt: null });
    await devnet.recordPoll(1_790_800_030, true);
    await devnet.recordPoll(1_790_800_060, false);
    expect(await devnet.health()).toMatchObject({ lastPollAt: 1_790_800_060, lastPollOk: false, lastOkPollAt: 1_790_800_030 });
    // Another dataset in the same database has its own.
    const mainnet = await Store.bind(db, { name: "mainnet", programId: HD, executorPda: EXECUTOR_PDA });
    expect(await mainnet.health()).toMatchObject(none);
    // A value this code did not write reads as "no pass", not as a number.
    await devnet.setCursor("ingest", "last-poll", "soon");
    expect(await devnet.health()).toMatchObject({ lastPollAt: null, lastPollOk: null, lastOkPollAt: 1_790_800_030 });
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
      // v1.1 §3.3 fields (the former reserved bytes) decode as zero when unset.
      expect(decodeRig(data)).toEqual({ ...account, shiftOpen: 0, breakReason: 0, oreAutomationBump: 0, oreMinerBump: 0, shiftStartTs: 0n });
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

  it("orders ORE rounds by the numeric id, not by its text (99,999 comes before 100,000)", async () => {
    const store = await Store.bind(db, { name: "devnet", programId: HD, executorPda: EXECUTOR_PDA });
    const dig = (n: number, roundId: bigint) =>
      buildDigTx({
        signature: sig(n), slot: 2000 + n, blockTime: 1_790_900_000 + n, cranker: addr(9), programId: HD, configPda: CONFIG_PDA, executorPda: EXECUTOR_PDA,
        roundAccount: addr(10), roundId,
        rigs: [{ rig: addr(1), authority: addr(11), automation: addr(12), miner: addr(13), outcome: { kind: "dug", perTile: 1_000n, mask: 1, emaEv: 1n } }],
      });
    expect(await store.ingestTxs([dig(40, 99_999n), dig(41, 100_000n), dig(42, 9n)].map((t) => extractTransaction(t, OPTS)), "test")).toBe(3);
    expect(await store.dugRoundsWithoutReset(10)).toEqual([100_000n, 99_999n, 9n]);
    expect(await store.roundsToResolve(10, false)).toEqual([100_000n, 99_999n, 9n]);
    expect(await store.roundsToResolve(2, true)).toEqual([100_000n, 99_999n]);
    const reset = (roundId: bigint) => ({
      resetSignature: sig(200 + Number(roundId % 100n)),
      event: {
        kind: "OreReset" as const, roundId, startSlot: 1n, endSlot: 2n, winningSquare: 0, topMiner: addr(77), totalMiners: 5n, motherlode: 0n, totalDeployed: 10n,
        totalVaulted: 1n, totalWinnings: 1n, totalMinted: 100_000_000_000n, ts: 1_790_900_000n + roundId, rng: 0n, deployedWinningSquare: 1n,
      },
    });
    await store.upsertRounds([reset(100_000n), reset(9n), reset(99_999n)], "test");
    expect((await store.loadMetricsInput()).rounds.map((r) => r.roundId)).toEqual([9n, 99_999n, 100_000n]);
    expect((await store.roundOutcomeRows(0n, 200_000n)).resets.map((r) => r.roundId)).toEqual([9n, 99_999n, 100_000n]);
    expect((await store.roundOutcomeRows(50n, 60n, [100_000n, 9n])).resets.map((r) => r.roundId)).toEqual([9n, 100_000n]);
    expect(await store.dugRoundsWithoutReset(10)).toEqual([]);
  });

  it("cursors round-trip", async () => {
    const store = await Store.bind(db, { name: "devnet", programId: HD, executorPda: EXECUTOR_PDA });
    expect(await store.getCursor("rpc", "x")).toBeNull();
    await store.setCursor("rpc", "x", "abc");
    await store.setCursor("rpc", "x", "def");
    expect(await store.getCursor("rpc", "x")).toBe("def");
  });
});

describe("Store: SKR and v1.3 events (ev_ext)", () => {
  it("stores every golden SKR / v1.3 event with its fields, and the summary re-adds them", async () => {
    const store = await Store.bind(db, { name: "devnet", programId: HD, executorPda: EXECUTOR_PDA });
    const vectors = GOLDEN_VECTORS.instructions.filter((v) => v.tag >= 15).sort((a, b) => a.step - b.step);
    const txs = vectors.map((v) => extractTransaction(goldenTx(v.name, sig(100 + v.step), 5_000 + v.step), OPTS));
    expect(await store.ingestTxs(txs, "test")).toBe(vectors.length);
    expect(await store.ingestTxs(txs, "test")).toBe(0);

    const golden = vectors.flatMap((v) => (v.litesvm.events ?? []).filter((e) => Number(e.fields.tag) >= 11));
    const rows = await db.query<{ name: string; tag: number; rig: string | null; fields: string }>(
      "SELECT name, tag, rig, fields::text AS fields FROM ev_ext WHERE dataset = 'devnet' AND slot >= 5000 ORDER BY slot, idx",
    );
    expect(rows.map((r) => r.name)).toEqual(golden.map((e) => e.event));
    rows.forEach((r, i) => {
      const want = golden[i]!.fields;
      const got = JSON.parse(r.fields) as Record<string, string | number>;
      for (const [k, v] of Object.entries(want)) if (k !== "tag") expect(String(got[k]), `${r.name}.${k}`).toBe(String(v));
      expect(r.rig).toBe(typeof want.rig === "string" ? want.rig : null);
    });
    // The heartbeats a stack_checkin applied are stored like record_heartbeats entries.
    const hb = await db.query<{ kind: string; applied: boolean; fresh: boolean }>(
      "SELECT kind, applied, fresh FROM hd_heartbeats WHERE dataset = 'devnet' AND slot >= 5000 ORDER BY slot, entry_idx",
    );
    expect(hb.map((h) => [h.kind, h.fresh, h.applied])).toEqual([...Array(3).fill(["record", true, true]), ...Array(3).fill(["record", false, false])]);

    // The summary is nothing but counts and sums of those events.
    const total = (name: string, field: string, pick: (f: Record<string, string | number>) => boolean = () => true) =>
      golden.filter((e) => e.event === name && pick(e.fields)).reduce((n, e) => n + BigInt(e.fields[field]!), 0n).toString();
    const n = (name: string, pick: (f: Record<string, string | number>) => boolean = () => true) => golden.filter((e) => e.event === name && pick(e.fields)).length;
    const sum = computeSkrSummary(await store.skrEventGroups(), await store.buryState());
    expect(sum.stack).toMatchObject({
      tablesOpened: 1,
      seatsJoined: n("StackJoined"),
      skrBonded: total("StackJoined", "bond"),
      tablesSettled: 1,
      skrToBury: total("StackSettled", "bury_amount"),
      checkinsCounted: n("StackCheckin", (f) => f.result === 0),
      checkinsRefused: n("StackCheckin", (f) => f.result !== 0),
      payouts: n("StackClaimed", (f) => f.kind === 0),
      skrPaidOut: total("StackClaimed", "amount", (f) => f.kind === 0),
    });
    expect(sum.stack.checkinsCounted).toBe(5);
    expect(sum.stack.checkinsRefused).toBe(1);
    expect(BigInt(sum.stack.skrToFinishers)).toBe(BigInt(total("StackSettled", "payouts_total")) - BigInt(total("StackSettled", "finisher_bonds")));
    expect(sum.focusBond).toEqual({
      locked: 1, skrLocked: total("FocusBondLocked", "amount"),
      released: 1, skrReleased: total("FocusBondReleased", "amount"),
      forfeited: 1, skrForfeited: total("FocusBondForfeited", "amount"),
    });
    expect(sum.gift).toEqual({
      created: 2, lamportsCreated: total("GiftCreated", "lamports"), createdForSeeker: 1,
      claimed: 2, lamportsClaimed: total("GiftClaimed", "lamports"), claimedBySeeker: 1,
      refunded: 1, lamportsRefunded: total("GiftRefunded", "lamports"),
    });
    const sold = golden.find((e) => e.event === "BuryAuctionSold")!.fields;
    const lastLot = [...golden].reverse().find((e) => e.event === "BuryLotAdded")!.fields;
    expect(sum.bury).toEqual({
      lots: 2, skrIn: total("BuryLotAdded", "amount"),
      skrFromStack: total("BuryLotAdded", "amount", (f) => f.source_kind === 1),
      skrFromBonds: total("BuryLotAdded", "amount", (f) => f.source_kind === 2),
      sales: 1, skrSold: String(sold.skr_amount), orePaid: String(sold.ore_paid), oreBurned: String(sold.ore_burned), oreToStakers: String(sold.ore_shared),
      lotSkr: String(sold.lot_remaining), lastPrice: String(sold.price),
      startPrice: String(lastLot.start_price), startSlot: String(lastLot.start_slot),
    });
    // 90% burned, 10% to ORE's stake program: never "100% burned".
    expect(BigInt(sum.bury.oreBurned) + BigInt(sum.bury.oreToStakers)).toBe(BigInt(sum.bury.orePaid));
    // Governance and ShiftLogClosed are stored but are not SKR totals.
    expect(rows.filter((r) => r.tag >= 24).map((r) => r.name)).toEqual(["ShiftLogClosed", "ShiftLogClosed", "GovernanceProposed", "GovernanceCancelled", "GovernanceAccepted"]);
  });

  it("is empty, not an error, before any SKR event", async () => {
    const store = await Store.bind(db, { name: "mainnet", programId: HD, executorPda: EXECUTOR_PDA });
    const sum = computeSkrSummary(await store.skrEventGroups(), await store.buryState());
    expect(sum.stack.tablesOpened).toBe(0);
    expect(sum.bury).toMatchObject({ lots: 0, skrIn: "0", lotSkr: null, lastPrice: null });
  });
});
