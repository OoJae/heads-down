import { readFileSync } from "node:fs";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { encodeRig, encodeSeekerSeat, type RigAccount } from "../src/codec/accounts.ts";
import { findProgramAddress, seed, addrBytes } from "../src/codec/pda.ts";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import { pollRpcOnce, type IngestContext } from "../src/ingest.ts";
import { checkWebhookAuth, handleHeliusPayload } from "../src/sources/helius.ts";
import { parseJsonBig, parseResetItem, pollOreRounds } from "../src/sources/oreApi.ts";
import { RpcClient, RpcError } from "../src/sources/rpc.ts";
import { buildDigTx, buildEventTx } from "../src/sim/txbuilder.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { Store } from "../src/store/store.ts";
import { addTx, addr, fakeChain, fakeRpcFetch, sig } from "./helpers.ts";

const HD = HEADS_DOWN_PROGRAM_ID;
const noSleep = async () => undefined;

let db: Db;
beforeAll(async () => {
  db = await openDb("pglite://memory");
  await migrate(db);
});
afterAll(async () => db.close());

async function ctxFor(name: "mainnet" | "devnet" | "localnet"): Promise<IngestContext> {
  const store = await Store.bind(db, { name, programId: HD, executorPda: EXECUTOR_PDA });
  return { store, programId: HD, executorPda: EXECUTOR_PDA };
}

function dig(n: number, rig = addr(1), authority = addr(11)) {
  return buildDigTx({
    signature: sig(n), slot: 5000 + n, blockTime: 1_790_800_000 + n * 80, cranker: addr(9), programId: HD, configPda: CONFIG_PDA,
    executorPda: EXECUTOR_PDA, roundAccount: addr(10), roundId: 422_700n + BigInt(n),
    rigs: [{ rig, authority, automation: addr(12), miner: addr(13), outcome: { kind: "dug", perTile: 66_666n, mask: 0x7fff, emaEv: 1n } }],
  });
}

describe("RpcClient", () => {
  it("retries HTTP 429 with backoff, then succeeds", async () => {
    const chain = fakeChain();
    chain.throttleNext = 2;
    const rpc = new RpcClient("https://rpc.example/?api-key=SECRET", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep });
    expect(await rpc.getTransaction(sig(1))).toBeNull();
    expect(chain.calls).toHaveLength(3);
  });

  it("does not retry JSON-RPC errors and never leaks the URL's API key", async () => {
    const chain = fakeChain();
    const rpc = new RpcClient("https://rpc.example/?api-key=SECRET", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep });
    const err = (await rpc.call("nope", []).catch((e: unknown) => e)) as RpcError;
    expect(err).toBeInstanceOf(RpcError);
    expect(chain.calls).toHaveLength(1);
    expect(String(err.message)).not.toContain("SECRET");
    chain.throttleNext = 99;
    const err2 = (await rpc.getTransaction(sig(1)).catch((e: unknown) => e)) as RpcError;
    expect(String(err2.message)).toContain("rpc.example");
    expect(String(err2.message)).not.toContain("SECRET");
  });

  it("rejects non-http URLs", () => {
    expect(() => new RpcClient("file:///etc/passwd")).toThrow();
  });
});

describe("RPC polling source", () => {
  it("backfills, advances the cursor, is idempotent, and snapshots accounts", async () => {
    const ctx = await ctxFor("devnet");
    const chain = fakeChain();
    const { address: rigPda, bump } = findProgramAddress([seed("rig"), addrBytes(addr(11))], HD);
    addTx(chain, buildEventTx({ signature: sig(50), slot: 4000, blockTime: 1_790_790_000, signer: addr(11), programId: HD, events: [{ kind: "ShiftArmed", rig: rigPda, shiftId: 1n }] }), [HD]);
    for (let n = 1; n <= 3; n++) addTx(chain, dig(n, rigPda), [HD, EXECUTOR_PDA]);
    const rig: RigAccount = {
      kind: "Rig", bump, authority: addr(11), p256Pubkey: "03" + "22".repeat(32), attestationLevel: 1, tier: 0, state: 2, sgtMint: null,
      attestationExpirySlot: 0n, capWeek: 0n, capShift: 0n, capRound: 0n, capMaxCost: 0n, capsExpiryTs: 0n, planMaxEvCost: 0n, planDigLamports: 0n,
      planSplitTiles: 15, planSoloTiles: 0, planLeaseRounds: 3, planFlags: 0, planWindowStartTs: 0n, planWindowEndTs: 0n, shiftId: 1n, hbCounter: 0n,
      leaseFromRound: 0n, leaseToRound: 0n, gapCount: 0, spentShift: 0n, spentWeek: 0n, weekStartTs: 0n, lastDugRound: 0n, shiftStartRound: 0n,
      shiftDarkRounds: 0n, shiftRoundsDug: 0n, lifetimeDarkRounds: 0n, lifetimeRoundsDug: 3n, lifetimeLamportsDeployed: 2_999_970n, streak: 0, freezesLeft: 2, lastShiftDay: 0n,
    };
    chain.accounts.push({ address: rigPda, owner: HD, data: encodeRig(rig) });
    // A forged "rig" at a non-PDA address and one owned by another program are rejected.
    chain.accounts.push({ address: addr(66), owner: HD, data: encodeRig(rig) });
    // A SeekerSeat whose address does not match its mint.
    chain.accounts.push({ address: addr(67), owner: HD, data: encodeSeekerSeat({ kind: "SeekerSeat", bump: 255, sgtMint: addr(70), rig: rigPda, authority: addr(11), memberNumber: 1n, verifiedSlot: 1n }) });

    const rpc = new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep });
    const opts = { addresses: [HD, EXECUTOR_PDA], maxBackfill: 1000, concurrency: 2 };
    const first = await pollRpcOnce(ctx, rpc, opts);
    expect(first).toEqual({ ingested: 4, accounts: 1 });
    expect(await ctx.store.getCursor("rpc-signatures", HD)).toBe(sig(3));
    const m = await ctx.store.loadMetricsInput();
    expect(m.digs).toHaveLength(3);
    expect(m.arms).toHaveLength(1);
    expect(m.rigs.map((r) => r.address)).toEqual([rigPda]);
    const health = await ctx.store.health();
    expect(health.problems).toEqual([{ code: "BAD_FIELD", count: 2 }]);

    // Nothing new: no transactions fetched on the next poll.
    chain.calls.length = 0;
    expect((await pollRpcOnce(ctx, rpc, opts)).ingested).toBe(0);
    expect(chain.calls.filter((c) => c.method === "getTransaction")).toHaveLength(0);

    // New tx arrives; one not yet served stops the cursor before it.
    addTx(chain, dig(4, rigPda), [HD, EXECUTOR_PDA]);
    addTx(chain, dig(5, rigPda), [HD, EXECUTOR_PDA]);
    chain.unavailable.add(sig(5));
    expect((await pollRpcOnce(ctx, rpc, opts)).ingested).toBe(1);
    expect(await ctx.store.getCursor("rpc-signatures", HD)).toBe(sig(4));
    chain.unavailable.clear();
    expect((await pollRpcOnce(ctx, rpc, opts)).ingested).toBe(1);
    expect((await ctx.store.loadMetricsInput()).digs).toHaveLength(5);
  });

  it("skips failed signatures without fetching them", async () => {
    const ctx = await ctxFor("localnet");
    const chain = fakeChain();
    const failed = buildDigTx({ ...{ signature: sig(77), slot: 1, blockTime: 1, cranker: addr(9), programId: HD, configPda: CONFIG_PDA, executorPda: EXECUTOR_PDA, roundAccount: addr(10), roundId: 1n, rigs: [] }, failed: true });
    addTx(chain, failed, [HD]);
    const rpc = new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep });
    await pollRpcOnce(ctx, rpc, { addresses: [HD], maxBackfill: 10, concurrency: 1 });
    expect(chain.calls.filter((c) => c.method === "getTransaction")).toHaveLength(0);
  });
});

describe("api.ore.com rounds", () => {
  const page = readFileSync(new URL("./fixtures/api-ore-events-reset-page.json", import.meta.url), "utf8");

  it("parses u64 fields exactly (rng > 2^53) and the reset signature", () => {
    const items = parseJsonBig(page) as unknown[];
    const r = parseResetItem(items.find((it) => (it as [unknown, { round_id: bigint }])[1].round_id === 422680n));
    expect(r.resetSignature).toBe("4jjLVs2Q1fEVqpdpcjS9iWCBHPYRPUAyADCPUHmMxZARCoZPpnAVvRhpD72VkBmcBLMav2ofVKS9XFpFisSXW3jp");
    expect(r.event.rng).toBe(1_057_593_117_031_599_697n);
    expect(r.event.totalMiners).toBe(170n);
  });

  it("rejects malformed items", () => {
    expect(() => parseResetItem([[1, 2], {}])).toThrow(/signature/);
    const items = parseJsonBig(page) as [unknown[], Record<string, unknown>][];
    const bad = [items[0]![0], { ...items[0]![1], total_deployed: -1n }];
    expect(() => parseResetItem(bad)).toThrow(/total_deployed/);
    const bad2 = [items[0]![0], { ...items[0]![1], top_miner: [1, 2, 3] }];
    expect(() => parseResetItem(bad2)).toThrow(/top_miner/);
  });

  function apiFetch(body: string): typeof fetch {
    return (async (url: string | URL | Request) => {
      const u = new URL(String(url));
      return new Response(u.searchParams.get("page") === "0" ? body : "[]", { status: 200 });
    }) as typeof fetch;
  }

  it("stores rounds and verifies a sample against the real on-chain ResetEvent", async () => {
    const ctx = await ctxFor("mainnet");
    const chain = fakeChain();
    const resetTx = JSON.parse(readFileSync(new URL("./fixtures/ore-reset-mainnet-v1.json", import.meta.url), "utf8"));
    chain.txs.set(resetTx.transaction.signatures[0], resetTx);
    const rpc = new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep });
    const res = await pollOreRounds(ctx, { since: 0, maxPages: 3, verifySample: 5, fetchImpl: apiFetch(page) }, rpc);
    expect(res).toEqual({ stored: 2, verified: 1, mismatches: 0 });
    const rounds = (await ctx.store.loadMetricsInput()).rounds;
    expect(rounds.map((r) => r.roundId)).toContain(422_680n);
    // Second poll: nothing newer than the cursor.
    expect((await pollOreRounds(ctx, { since: 0, maxPages: 3, verifySample: 5, fetchImpl: apiFetch(page) }, rpc)).stored).toBe(0);
  });

  it("chain wins when api.ore.com disagrees", async () => {
    const ctx = await ctxFor("devnet");
    const chain = fakeChain();
    const resetTx = JSON.parse(readFileSync(new URL("./fixtures/ore-reset-mainnet-v1.json", import.meta.url), "utf8"));
    chain.txs.set(resetTx.transaction.signatures[0], resetTx);
    const tampered = page.replace(/"num_winners":\s*170\b/, '"num_winners": 17');
    expect(tampered).not.toBe(page);
    const rpc = new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep });
    const res = await pollOreRounds(ctx, { since: 0, maxPages: 1, verifySample: 5, fetchImpl: apiFetch(tampered) }, rpc);
    expect(res.mismatches).toBe(1);
    const r = (await ctx.store.loadMetricsInput()).rounds.find((x) => x.roundId === 422_680n);
    expect(r?.totalMiners).toBe(170n);
    expect((await ctx.store.health()).problems).toContainEqual({ code: "ORE_API_MISMATCH", count: 1 });
  });
});

describe("Helius webhook", () => {
  it("authenticates in constant time", () => {
    expect(checkWebhookAuth("s3cret", "s3cret")).toBe(true);
    expect(checkWebhookAuth("s3cre", "s3cret")).toBe(false);
    expect(checkWebhookAuth(undefined, "s3cret")).toBe(false);
    expect(checkWebhookAuth("x", "")).toBe(false);
  });

  it("treats the payload as a hint: forged content is replaced by what the RPC returns", async () => {
    const ctx = await ctxFor("localnet");
    const chain = fakeChain();
    const real = dig(40);
    chain.txs.set(sig(40), real);
    const forged = dig(40);
    // Attacker rewrites the payload's logs to claim a 1,000 SOL dig; it must not be believed.
    forged.meta!.logMessages = forged.meta!.logMessages!.map((l) => (l.startsWith("Program data:") ? "Program data: AQ==" : l));
    const ghost = dig(41); // not on chain at all
    const rpc = new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep });
    const res = await handleHeliusPayload(ctx, [forged, ghost, { junk: true }], { rpc, trustPayload: false });
    expect(res).toEqual({ received: 3, ingested: 1, rejected: 2 });
    const m = await ctx.store.loadMetricsInput();
    const d = m.digs.find((x) => x.signature === sig(40));
    expect(d?.lamports).toBe(999_990n);
    expect(m.digs.find((x) => x.signature === sig(41))).toBeUndefined();
  });

  it("refuses to verify without an RPC unless explicitly trusted", async () => {
    const ctx = await ctxFor("localnet");
    await expect(handleHeliusPayload(ctx, [], { rpc: null, trustPayload: false })).rejects.toThrow(/RPC_URL/);
    await expect(handleHeliusPayload(ctx, {}, { rpc: null, trustPayload: true })).rejects.toThrow(/array/);
  });
});
