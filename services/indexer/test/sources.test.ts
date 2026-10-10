import { readFileSync } from "node:fs";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { encodeRig, encodeSeekerSeat, type RigAccount } from "../src/codec/accounts.ts";
import { findProgramAddress, seed, addrBytes } from "../src/codec/pda.ts";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import { newSnapshotState, pollRpcOnce, type IngestContext } from "../src/ingest.ts";
import { checkWebhookAuth, handleHeliusPayload } from "../src/sources/helius.ts";
import { parseJsonBig, parseResetItem, pollOreRounds } from "../src/sources/oreApi.ts";
import { RpcClient, RpcError, scrubRpcText, urlSecrets } from "../src/sources/rpc.ts";
import { buildDigTx, buildEventTx } from "../src/sim/txbuilder.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { Store } from "../src/store/store.ts";
import { addTx, addr, fakeChain, fakeRpcFetch, sig, type FakeChain } from "./helpers.ts";

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

/** The Rig account of authority addr(11), at its canonical PDA. */
const RIG_PDA = findProgramAddress([seed("rig"), addrBytes(addr(11))], HD);
function rigAccount(lifetimeRoundsDug = 3n): RigAccount {
  return {
    kind: "Rig", bump: RIG_PDA.bump, authority: addr(11), p256Pubkey: "03" + "22".repeat(32), attestationLevel: 1, tier: 0, state: 2, sgtMint: null,
    attestationExpirySlot: 0n, capWeek: 0n, capShift: 0n, capRound: 0n, capMaxCost: 0n, capsExpiryTs: 0n, planMaxEvCost: 0n, planDigLamports: 0n,
    planSplitTiles: 15, planSoloTiles: 0, planLeaseRounds: 3, planFlags: 0, planWindowStartTs: 0n, planWindowEndTs: 0n, shiftId: 1n, hbCounter: 0n,
    leaseFromRound: 0n, leaseToRound: 0n, gapCount: 0, spentShift: 0n, spentWeek: 0n, weekStartTs: 0n, lastDugRound: 0n, shiftStartRound: 0n,
    shiftDarkRounds: 0n, shiftRoundsDug: 0n, lifetimeDarkRounds: 0n, lifetimeRoundsDug, lifetimeLamportsDeployed: 2_999_970n, streak: 0, freezesLeft: 2, lastShiftDay: 0n,
  };
}

/** A provider that answers every call with a JSON-RPC error carrying `message`. */
const errorFetch = (message: string) =>
  (async () => new Response(JSON.stringify({ jsonrpc: "2.0", id: 1, error: { code: -32000, message } }), { status: 200 })) as typeof fetch;

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

  it("scrubs a provider's error text of the URL, of any api-key value and of the key itself", async () => {
    const key = "not-a-real-key-0f1e2d3c-4b5a-6978";
    const url = `https://mainnet.helius-rpc.com/?api-key=${key}`;
    const told = `Unauthorized: ${url} is not allowed (key ${key}, API-KEY=${key.toUpperCase()}&x=1) apikey=other-key`;
    const err = (await new RpcClient(url, { fetchImpl: errorFetch(told) }).call("getSlot", []).catch((e: unknown) => e)) as RpcError;
    expect(err).toBeInstanceOf(RpcError);
    expect(err.code).toBe(-32000);
    expect(err.message).toBe(
      "getSlot: Unauthorized: https://mainnet.helius-rpc.com/<redacted> is not allowed (key <redacted>, API-KEY=<redacted>&x=1) apikey=<redacted>",
    );
  });

  it("scrubs a key that sits in the URL's path", async () => {
    const key = "notarealtoken0f1e2d3c4b5a69788796";
    const url = `https://example.solana-mainnet.quiknode.pro/${key}/`;
    const told = `token ${key} is over its limit; endpoint /${key}/ and ${url}`;
    const err = (await new RpcClient(url, { fetchImpl: errorFetch(told) }).call("getSlot", []).catch((e: unknown) => e)) as RpcError;
    expect(err.message).toBe("getSlot: token <redacted> is over its limit; endpoint /<redacted>/ and https://example.solana-mainnet.quiknode.pro/<redacted>");
  });

  it("scrubs before it cuts the text, so a key at the cut does not leak in part", async () => {
    const key = "not-a-real-key-0f1e2d3c-4b5a-6978";
    const told = "x".repeat(195) + key;
    const err = (await new RpcClient(`https://rpc.example/?api-key=${key}`, { fetchImpl: errorFetch(told) }).call("getSlot", []).catch((e: unknown) => e)) as RpcError;
    expect(err.message).toBe(`getSlot: ${"x".repeat(195)}<reda`);
  });

  it("leaves ordinary error text alone, including on a URL with no key", async () => {
    const told = "Transaction version (0) is not supported by the requesting client";
    for (const url of ["http://127.0.0.1:8899", "https://api.mainnet-beta.solana.com/", "https://rpc.ankr.com/solana/0123456789abcdef"]) {
      const err = (await new RpcClient(url, { fetchImpl: errorFetch(`${told} at ${url}`) }).call("getSlot", []).catch((e: unknown) => e)) as RpcError;
      expect(err.message.startsWith(`getSlot: ${told} at `)).toBe(true);
      expect(err.message).not.toContain("0123456789abcdef");
    }
    // A keyless URL is not a secret: it stays readable.
    expect(scrubRpcText("cannot reach http://127.0.0.1:8899", "http://127.0.0.1:8899", [])).toBe("cannot reach http://127.0.0.1:8899");
    expect(scrubRpcText("ends with api-key=", "http://127.0.0.1:8899", [])).toBe("ends with api-key=<redacted>");
  });

  it("finds the parts of a URL that may be a key: query values, path segments and userinfo", () => {
    expect(urlSecrets("https://mainnet.helius-rpc.com/?api-key=abcdef12-3456")).toEqual(["abcdef12-3456"]);
    // Short path words ("solana", "v2") are not keys; replacing them would mangle ordinary text.
    expect(urlSecrets("https://rpc.ankr.com/solana/0123456789abcdef")).toEqual(["0123456789abcdef"]);
    expect(urlSecrets("https://solana-mainnet.g.alchemy.com/v2/AbCdEfGh12345678")).toEqual(["AbCdEfGh12345678"]);
    expect(urlSecrets("https://lb.drpc.org/ogrpc?network=solana&dkey=Abcdefgh12345678")).toEqual(["Abcdefgh12345678"]);
    expect(urlSecrets("https://user:long-password-1@host.example/ws")).toEqual(["long-password-1"]);
    // Percent-encoded and decoded, longest first.
    expect(urlSecrets("https://h.example/?token=ab%2Fcd%2Fef12")).toEqual(["ab%2Fcd%2Fef12", "ab/cd/ef12"]);
    expect(urlSecrets("http://127.0.0.1:8899")).toEqual([]);
    expect(urlSecrets("https://api.mainnet-beta.solana.com/")).toEqual([]);
  });
});

describe("RPC polling source", () => {
  it("backfills, advances the cursor, is idempotent, and snapshots accounts", async () => {
    const ctx = await ctxFor("devnet");
    const chain = fakeChain();
    const rigPda = RIG_PDA.address;
    addTx(chain, buildEventTx({ signature: sig(50), slot: 4000, blockTime: 1_790_790_000, signer: addr(11), programId: HD, events: [{ kind: "ShiftArmed", rig: rigPda, shiftId: 1n }] }), [HD]);
    for (let n = 1; n <= 3; n++) addTx(chain, dig(n, rigPda), [HD, EXECUTOR_PDA]);
    const rig = rigAccount();
    chain.accounts.push({ address: rigPda, owner: HD, data: encodeRig(rig) });
    // A forged "rig" at a non-PDA address and one owned by another program are rejected.
    chain.accounts.push({ address: addr(66), owner: HD, data: encodeRig(rig) });
    // A SeekerSeat whose address does not match its mint.
    chain.accounts.push({ address: addr(67), owner: HD, data: encodeSeekerSeat({ kind: "SeekerSeat", bump: 255, sgtMint: addr(70), rig: rigPda, authority: addr(11), memberNumber: 1n, verifiedSlot: 1n }) });

    const rpc = new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep });
    const opts = { addresses: [HD, EXECUTOR_PDA], maxBackfill: 1000, concurrency: 2 };
    const first = await pollRpcOnce(ctx, rpc, opts);
    expect(first).toEqual({ ingested: 4, accounts: 1 });
    expect(await ctx.store.getCursor("rpc-signatures", HD)).toBe(`5003:${sig(3)}`);
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
    expect(await ctx.store.getCursor("rpc-signatures", HD)).toBe(`5004:${sig(4)}`);
    chain.unavailable.clear();
    expect((await pollRpcOnce(ctx, rpc, opts)).ingested).toBe(1);
    expect((await ctx.store.loadMetricsInput()).digs).toHaveLength(5);
  });

  it("keeps polling after a short-history node forgets the cursor transaction", async () => {
    const ctx = await ctxFor("localnet");
    const chain = fakeChain();
    const { address: rigPda } = findProgramAddress([seed("rig"), addrBytes(addr(11))], HD);
    for (let n = 1; n <= 2; n++) addTx(chain, dig(n, rigPda), [HD]);
    const rpc = new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep });
    const opts = { addresses: [HD], maxBackfill: 1000, concurrency: 1 };
    expect((await pollRpcOnce(ctx, rpc, opts)).ingested).toBe(2);
    // solana-test-validator keeps ~10,000 shreds: the cursor transaction is purged...
    chain.pruned.add(sig(2));
    addTx(chain, dig(3, rigPda), [HD]);
    // ...and `until` now fails with "not found": the poll falls back to the cursor's slot.
    expect((await pollRpcOnce(ctx, rpc, opts)).ingested).toBe(1);
    expect(await ctx.store.getCursor("rpc-signatures", HD)).toBe(`5003:${sig(3)}`);
    expect((await ctx.store.loadMetricsInput()).digs).toHaveLength(3);
    expect((await ctx.store.health()).problems).toEqual([]);
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

describe("account snapshot schedule", () => {
  // A database of its own per test: these tests count calls from an empty cursor.
  let own: Db;
  let ctx: IngestContext;
  beforeEach(async () => {
    own = await openDb("pglite://memory");
    await migrate(own);
    ctx = { store: await Store.bind(own, { name: "devnet", programId: HD, executorPda: EXECUTOR_PDA }), programId: HD, executorPda: EXECUTOR_PDA };
  });
  afterEach(async () => own.close());

  const opts = { addresses: [HD, EXECUTOR_PDA], maxBackfill: 1000, concurrency: 2, snapshotEvery: 4 };
  const scans = (chain: FakeChain) => chain.calls.filter((c) => c.method === "getProgramAccounts").length;
  const rigsDug = async () => (await ctx.store.loadMetricsInput()).rigs.map((r) => r.lifetimeRoundsDug);
  /** A chain whose scans answer from slot 9000, past every transaction of these tests (slots 5001 and up). */
  function chainWithRig(): { chain: FakeChain; rpc: RpcClient } {
    const chain = fakeChain();
    chain.slot = 9000;
    chain.accounts.push({ address: RIG_PDA.address, owner: HD, data: encodeRig(rigAccount(1n)) });
    addTx(chain, dig(1, RIG_PDA.address), [HD, EXECUTOR_PDA]);
    return { chain, rpc: new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep }) };
  }

  it("is taken on the first poll, on a poll that sees a new signature, and every Nth poll otherwise", async () => {
    const { chain, rpc } = chainWithRig();
    const state = newSnapshotState();
    expect(await pollRpcOnce(ctx, rpc, opts, state)).toEqual({ ingested: 1, accounts: 1 });
    expect(scans(chain)).toBe(4);

    // Idle polls cost the two signature calls and nothing else.
    chain.calls.length = 0;
    for (let i = 0; i < 3; i++) expect(await pollRpcOnce(ctx, rpc, opts, state)).toEqual({ ingested: 0, accounts: null });
    expect(chain.calls.map((c) => c.method)).toEqual(Array.from({ length: 6 }, () => "getSignaturesForAddress"));
    // The 4th poll since the last snapshot is the safety net.
    expect(await pollRpcOnce(ctx, rpc, opts, state)).toEqual({ ingested: 0, accounts: 1 });
    expect(scans(chain)).toBe(4);

    // A new signature: the snapshot is taken in the same poll and shows what the transaction changed.
    chain.calls.length = 0;
    chain.accounts[0] = { address: RIG_PDA.address, owner: HD, data: encodeRig(rigAccount(2n)) };
    addTx(chain, dig(2, RIG_PDA.address), [HD, EXECUTOR_PDA]);
    expect(await rigsDug()).toEqual([1n]);
    expect(await pollRpcOnce(ctx, rpc, opts, state)).toEqual({ ingested: 1, accounts: 1 });
    expect(scans(chain)).toBe(4);
    expect(await rigsDug()).toEqual([2n]);
    // ... and the count towards the safety net starts again.
    chain.calls.length = 0;
    for (let i = 0; i < 3; i++) expect((await pollRpcOnce(ctx, rpc, opts, state)).accounts).toBeNull();
    expect(scans(chain)).toBe(0);
    expect((await pollRpcOnce(ctx, rpc, opts, state)).accounts).toBe(1);
  });

  it("is taken on every poll when no state is kept, or when the interval is 1", async () => {
    const { chain, rpc } = chainWithRig();
    expect((await pollRpcOnce(ctx, rpc, opts)).accounts).toBe(1);
    expect((await pollRpcOnce(ctx, rpc, opts)).accounts).toBe(1);
    expect(scans(chain)).toBe(8);
    const state = newSnapshotState();
    for (let i = 0; i < 3; i++) expect((await pollRpcOnce(ctx, rpc, { ...opts, snapshotEvery: 1 }, state)).accounts).toBe(1);
    expect(scans(chain)).toBe(20);
  });

  it("is not asked for by a failed transaction, which changed no account data", async () => {
    const { chain, rpc } = chainWithRig();
    const state = newSnapshotState();
    await pollRpcOnce(ctx, rpc, opts, state);
    chain.calls.length = 0;
    const failed = buildDigTx({ signature: sig(77), slot: 5077, blockTime: 1, cranker: addr(9), programId: HD, configPda: CONFIG_PDA, executorPda: EXECUTOR_PDA, roundAccount: addr(10), roundId: 1n, rigs: [], failed: true });
    addTx(chain, failed, [HD, EXECUTOR_PDA]);
    expect(await pollRpcOnce(ctx, rpc, opts, state)).toEqual({ ingested: 0, accounts: null });
    expect(chain.calls.map((c) => c.method)).toEqual(["getSignaturesForAddress", "getSignaturesForAddress"]);
    expect(await ctx.store.getCursor("rpc-signatures", HD)).toBe(`5077:${sig(77)}`);
  });

  it("is taken again when the scan was answered from a slot before the new signature", async () => {
    const { chain, rpc } = chainWithRig();
    const state = newSnapshotState();
    chain.slot = 5001; // the slot of the first dig: its scan has seen it
    await pollRpcOnce(ctx, rpc, opts, state);
    expect(state.due).toBe(false);
    // The node behind getProgramAccounts lags: it still answers from slot 5001, before the dig in slot 5002.
    addTx(chain, dig(2, RIG_PDA.address), [HD, EXECUTOR_PDA]);
    expect((await pollRpcOnce(ctx, rpc, opts, state)).accounts).toBe(1);
    expect(state.due).toBe(true);
    chain.calls.length = 0;
    expect((await pollRpcOnce(ctx, rpc, opts, state)).accounts).toBe(1); // no new signature, scanned all the same
    expect(scans(chain)).toBe(4);
    expect(await rigsDug()).toEqual([1n]);
    // It catches up: this scan has seen the dig, and the idle polls after it scan nothing.
    chain.slot = 5002;
    chain.accounts[0] = { address: RIG_PDA.address, owner: HD, data: encodeRig(rigAccount(2n)) };
    expect((await pollRpcOnce(ctx, rpc, opts, state)).accounts).toBe(1);
    expect(await rigsDug()).toEqual([2n]);
    expect((await pollRpcOnce(ctx, rpc, opts, state)).accounts).toBeNull();
  });

  it("stays owed when the scans fail after the transactions were stored", async () => {
    const { chain, rpc } = chainWithRig();
    const state = newSnapshotState();
    chain.broken.add("getProgramAccounts");
    await expect(pollRpcOnce(ctx, rpc, opts, state)).rejects.toThrow(/getProgramAccounts: HTTP 500 from rpc\.example/);
    expect((await ctx.store.loadMetricsInput()).digs).toHaveLength(1); // the cursor has moved: the next poll sees no new signature
    expect(await rigsDug()).toEqual([]);
    chain.broken.clear();
    expect(await pollRpcOnce(ctx, rpc, opts, state)).toEqual({ ingested: 0, accounts: 1 });
    expect(await rigsDug()).toEqual([1n]);
    expect((await pollRpcOnce(ctx, rpc, opts, state)).accounts).toBeNull();
  });

  it("stays owed while a new transaction is not served yet", async () => {
    const { chain, rpc } = chainWithRig();
    const state = newSnapshotState();
    chain.unavailable.add(sig(1));
    expect(await pollRpcOnce(ctx, rpc, opts, state)).toEqual({ ingested: 0, accounts: null });
    expect(scans(chain)).toBe(0);
    chain.unavailable.clear();
    expect(await pollRpcOnce(ctx, rpc, opts, state)).toEqual({ ingested: 1, accounts: 1 });
  });

  // The two tests above start with a snapshot already owed (a first poll). These start with nothing
  // owed, so the snapshot they end with can only come from the new signature of the poll that failed.
  it("stays owed when a later poll's scans fail, although no poll sees that signature again", async () => {
    const { chain, rpc } = chainWithRig();
    const state = newSnapshotState();
    await pollRpcOnce(ctx, rpc, opts, state);
    expect(state.due).toBe(false);
    chain.accounts[0] = { address: RIG_PDA.address, owner: HD, data: encodeRig(rigAccount(2n)) };
    addTx(chain, dig(2, RIG_PDA.address), [HD, EXECUTOR_PDA]);
    chain.broken.add("getProgramAccounts");
    await expect(pollRpcOnce(ctx, rpc, opts, state)).rejects.toThrow(/getProgramAccounts: HTTP 500 from rpc\.example/);
    expect((await ctx.store.loadMetricsInput()).digs).toHaveLength(2); // stored, and both cursors have moved
    expect(await rigsDug()).toEqual([1n]);
    chain.broken.clear();
    chain.calls.length = 0;
    expect(await pollRpcOnce(ctx, rpc, opts, state)).toEqual({ ingested: 0, accounts: 1 });
    expect(chain.calls.filter((c) => c.method === "getTransaction")).toHaveLength(0);
    expect(await rigsDug()).toEqual([2n]);
    expect((await pollRpcOnce(ctx, rpc, opts, state)).accounts).toBeNull();
  });

  it("stays owed when a later poll cannot read the second address after the first one's cursor moved", async () => {
    const chain = fakeChain();
    chain.slot = 9000;
    chain.accounts.push({ address: RIG_PDA.address, owner: HD, data: encodeRig(rigAccount(1n)) });
    addTx(chain, dig(1, RIG_PDA.address), [HD, EXECUTOR_PDA]);
    // The program id's signatures are read first; the Executor PDA's list can be made to fail on its own.
    let executorListDown = false;
    const answer = fakeRpcFetch(chain);
    const fetchImpl = (async (url: string | URL | Request, init?: RequestInit) => {
      const req = JSON.parse(String(init?.body)) as { method: string; params: unknown[] };
      if (executorListDown && req.method === "getSignaturesForAddress" && req.params[0] === EXECUTOR_PDA) return new Response("upstream error", { status: 500 });
      return answer(url, init);
    }) as typeof fetch;
    const rpc = new RpcClient("https://rpc.example", { fetchImpl, sleep: noSleep });
    const state = newSnapshotState();
    await pollRpcOnce(ctx, rpc, opts, state);
    expect(state.due).toBe(false);

    // A transaction that names only the program id (as arm_shift does) changes the rig.
    chain.accounts[0] = { address: RIG_PDA.address, owner: HD, data: encodeRig(rigAccount(2n)) };
    addTx(chain, buildEventTx({ signature: sig(60), slot: 5060, blockTime: 1_790_805_000, signer: addr(11), programId: HD, events: [{ kind: "ShiftArmed", rig: RIG_PDA.address, shiftId: 2n }] }), [HD]);
    executorListDown = true;
    await expect(pollRpcOnce(ctx, rpc, opts, state)).rejects.toThrow(/getSignaturesForAddress: HTTP 500 from rpc\.example/);
    expect(await ctx.store.getCursor("rpc-signatures", HD)).toBe(`5060:${sig(60)}`); // stored, and this cursor has moved
    expect(await rigsDug()).toEqual([1n]);
    // The next poll finds no new signature at either address, and takes the snapshot it owes.
    executorListDown = false;
    chain.calls.length = 0;
    expect(await pollRpcOnce(ctx, rpc, opts, state)).toEqual({ ingested: 0, accounts: 1 });
    expect(chain.calls.filter((c) => c.method === "getTransaction")).toHaveLength(0);
    expect(await rigsDug()).toEqual([2n]);
    expect((await pollRpcOnce(ctx, rpc, opts, state)).accounts).toBeNull();
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
    const res = await pollOreRounds(ctx, { since: 0, maxPages: 3, verifySample: 5, fetchImpl: apiFetch(page), sleep: noSleep }, rpc);
    // The recorded page's two rounds, and page 1, which is empty: the whole list.
    expect(res).toEqual({ stored: 2, verified: 1, mismatches: 0, pages: 2, newest: "422680", backTo: "2026-09-29T18:56:07.000Z", backfill: "done", listEnd: "422679" });
    const rounds = (await ctx.store.loadMetricsInput()).rounds;
    expect(rounds.map((r) => r.roundId)).toContain(422_680n);
    // Second poll: nothing it has not read.
    expect(await pollOreRounds(ctx, { since: 0, maxPages: 3, verifySample: 5, fetchImpl: apiFetch(page), sleep: noSleep }, rpc)).toMatchObject({ stored: 0, verified: 0, pages: 1 });
  });

  it("chain wins when api.ore.com disagrees", async () => {
    const ctx = await ctxFor("devnet");
    const chain = fakeChain();
    const resetTx = JSON.parse(readFileSync(new URL("./fixtures/ore-reset-mainnet-v1.json", import.meta.url), "utf8"));
    chain.txs.set(resetTx.transaction.signatures[0], resetTx);
    const tampered = page.replace(/"num_winners":\s*170\b/, '"num_winners": 17');
    expect(tampered).not.toBe(page);
    const rpc = new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep });
    const res = await pollOreRounds(ctx, { since: 0, maxPages: 1, verifySample: 5, fetchImpl: apiFetch(tampered), sleep: noSleep }, rpc);
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
