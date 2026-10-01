/**
 * ORE mined and SOL returned per miner, against REAL mainnet data (test/fixtures/ore-rounds-mainnet.json):
 * three rounds read on 2026-10-01 — a split round with a 485.8 ORE Motherlode (423,310), a solo round
 * with a 39.6 ORE Motherlode (423,508) and a plain split round (423,800) — with each round's
 * ResetEvent (Board-signed ORE Log bytes), its Round account (952 bytes, read after the reset), the
 * miners' DeployEvents, and each miner's own `checkpoint` log lines: the rewards ORE itself
 * computed. The expectations below are hand-computed with integer floor division and equal ORE's
 * logged values.
 */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { decodeOreLogInstruction, type OreDeployEvent, type OreResetEvent } from "../src/codec/ore.ts";
import { decodeOreRound, encodeOreRound, oreRoundPda, roundRng } from "../src/codec/round.ts";
import { ONE_ORE, ORE_SPLIT_ADDRESS } from "../src/constants.ts";
import { oreMined, outcomeFromReset, outcomeFromRoundAccount, perSquare, solReturned, squareReturn } from "../src/metrics/oremath.ts";
import type { RoundRow } from "../src/model.ts";

interface FixtureRound {
  round_id: string;
  reset: { signature: string; slot: number; block_time: number; log_ix_hex: string };
  round_account: { address: string; owner: string; context_slot: number; data_base64: string };
  miners: { authority: string; deploys: { signature: string; log_ix_hex: string }[]; checkpoint: { signature: string; logs: string[] } }[];
}
const fixture = JSON.parse(readFileSync(new URL("./fixtures/ore-rounds-mainnet.json", import.meta.url), "utf8")) as { rounds: FixtureRound[] };

const decode = (hex: string) => decodeOreLogInstruction(Uint8Array.from(Buffer.from(hex, "hex")));
const round = (id: string) => fixture.rounds.find((r) => r.round_id === id)!;
const resetOf = (r: FixtureRound) => decode(r.reset.log_ix_hex) as OreResetEvent;
const accountOf = (r: FixtureRound) => decodeOreRound(Uint8Array.from(Buffer.from(r.round_account.data_base64, "base64")));
const rowOf = (e: OreResetEvent, sig: string): RoundRow => ({
  roundId: e.roundId, ts: Number(e.ts), winningSquare: e.winningSquare, topMiner: e.topMiner, totalMiners: e.totalMiners, motherlode: e.motherlode,
  totalDeployed: e.totalDeployed, totalMinted: e.totalMinted, deployedWinningSquare: e.deployedWinningSquare, resetSignature: sig,
});
const minerOf = (r: FixtureRound, prefix: string) => r.miners.find((m) => m.authority.startsWith(prefix))!;
const deploysOf = (m: FixtureRound["miners"][number]) => m.deploys.map((d) => decode(d.log_ix_hex) as OreDeployEvent);

/** ORE's own numbers from its checkpoint logs (amount_to_ui_amount / lamports_to_sol print exact f64s). */
function logged(logs: string[]): { base: bigint; motherlode: bigint; sol: bigint | null } {
  const out = { base: 0n, motherlode: 0n, sol: null as bigint | null };
  for (const l of logs) {
    let m;
    if ((m = /^(?:Split|Top miner) rewards: (\S+) ORE$/.exec(l))) out.base = BigInt(Math.round(Number(m[1]) * 1e11));
    if ((m = /^Motherlode rewards: (\S+) ORE$/.exec(l))) out.motherlode = BigInt(Math.round(Number(m[1]) * 1e11));
    if ((m = /^Sending (\S+) SOL to /.exec(l))) out.sol = BigInt(Math.round(Number(m[1]) * 1e9));
  }
  return out;
}

describe("ORE Round account (real mainnet bytes)", () => {
  it("decodes all three rounds and agrees with each ResetEvent field for field", () => {
    for (const r of fixture.rounds) {
      const acc = accountOf(r);
      const reset = resetOf(r);
      expect(oreRoundPda(acc.id)).toBe(r.round_account.address);
      expect(acc.id).toBe(BigInt(r.round_id));
      expect(reset.roundId).toBe(acc.id);
      const rng = roundRng(acc.slotHash);
      expect(rng).toBe(reset.rng);
      expect(Number(rng! % 25n)).toBe(reset.winningSquare);
      expect(acc.deployed[reset.winningSquare!]).toBe(reset.deployedWinningSquare);
      expect(acc.deployed.reduce((a, b) => a + b, 0n)).toBe(reset.totalDeployed);
      expect(acc.topMiner).toBe(reset.topMiner);
      expect(acc.motherlode).toBe(reset.motherlode);
      expect(acc.totalMiners).toBe(reset.totalMiners);
      expect(acc.totalVaulted).toBe(reset.totalVaulted);
      expect(acc.totalReturnedSol).toBe(reset.totalWinnings);
      // top_miner_reward() = sum(rewards) = the +1 ORE mint = min(total_minted, 1 ORE)
      expect(acc.rewards.reduce((a, b) => a + b, 0n)).toBe(reset.totalMinted < ONE_ORE ? reset.totalMinted : ONE_ORE);
      expect(encodeOreRound(acc).length).toBe(952);
      expect(decodeOreRound(encodeOreRound(acc))).toEqual({ ...acc });
    }
  });

  it("rejects a wrong length or discriminator", () => {
    const bytes = Uint8Array.from(Buffer.from(fixture.rounds[0]!.round_account.data_base64, "base64"));
    expect(() => decodeOreRound(bytes.subarray(0, 951))).toThrow(/BAD_LENGTH/);
    const bad = bytes.slice();
    bad[0] = 105;
    expect(() => decodeOreRound(bad)).toThrow(/BAD_TAG/);
  });
});

describe("ORE mined per miner: hand-computed and equal to ORE's own checkpoint logs", () => {
  // floor(1e11 × d / T) and floor(motherlode × d / T), integer arithmetic.
  const cases: { round: string; miner: string; base: bigint; motherlode: bigint; why: string }[] = [
    { round: "423310", miner: "9ABZDJbz", base: 429_023_806n, motherlode: 208_419_765_183n, why: "split: 1e11 × 1,927,710 / 449,324,716; ML 485.8 ORE pro rata" },
    { round: "423310", miner: "BKQqoatc", base: 89_022_478n, motherlode: 43_247_120_196n, why: "split, 400,000 on the winning square" },
    { round: "423310", miner: "32iBb14g", base: 13_388_090n, motherlode: 6_503_934_406n, why: "split, 60,156 on the winning square" },
    { round: "423508", miner: "98YeM6PZ", base: ONE_ORE, motherlode: 565_133_289_864n, why: "solo winner (ResetEvent.top_miner): all of the +1 ORE; ML 39.6 × 39,385,500 / 275,981,937" },
    { round: "423508", miner: "EhhBe2cr", base: 0n, motherlode: 10_960_849_513n, why: "on the solo winning square but not the top miner: Motherlode share only" },
    { round: "423508", miner: "62tFaatd", base: 0n, motherlode: 51_655_554_544n, why: "same, 3,600,000 on the square" },
    { round: "423800", miner: "DPuAM9Kz", base: 1_140_356_254n, motherlode: 0n, why: "plain split: 1e11 × 3,296,296 / 289,058,440" },
    { round: "423800", miner: "2SYFhEmU", base: 1_729n, motherlode: 0n, why: "5 lamports a square still mines dust: floor(1e11 × 5 / 289,058,440)" },
    { round: "423800", miner: "GrkpgNM9", base: 34_595_080n, motherlode: 0n, why: "plain split, 100,000" },
    { round: "423800", miner: "5KxXxBCv", base: 34_595_080n, motherlode: 0n, why: "plain split, 100,000 on 13 squares" },
  ];
  for (const c of cases) {
    it(`${c.round} ${c.miner}: ${c.why}`, () => {
      const r = round(c.round);
      const m = minerOf(r, c.miner);
      const d = perSquare(deploysOf(m));
      const fromReset = oreMined(m.authority, d, outcomeFromReset(rowOf(resetOf(r), r.reset.signature)));
      const fromAccount = oreMined(m.authority, d, outcomeFromRoundAccount(accountOf(r))!);
      expect(fromReset).toEqual({ base: c.base, motherlode: c.motherlode, total: c.base + c.motherlode });
      expect(fromAccount).toEqual(fromReset);
      const ore = logged(m.checkpoint.logs);
      expect([ore.base, ore.motherlode]).toEqual([c.base, c.motherlode]);
    });
  }

  it("sums a miner's several DeployEvents in one round, including ORE's zero-square ones", () => {
    const m = minerOf(round("423508"), "98YeM6PZ");
    const deps = deploysOf(m);
    expect(deps).toHaveLength(25); // one single-square deploy per square, different amounts
    expect(perSquare(deps)[1]).toBe(39_385_500n);
    const dup = deploysOf(minerOf(round("423310"), "9ABZDJbz"));
    expect(dup.filter((e) => e.totalSquares === 0).length).toBe(2); // racing automation executors that placed nothing
    expect(perSquare(dup).every((x) => x === 1_927_710n)).toBe(true);
  });
});

describe("SOL returned per miner (checkpoint.rs fee arithmetic)", () => {
  it("is exact with the Round account's per-square totals for all ten miners", () => {
    for (const r of fixture.rounds) {
      const o = outcomeFromRoundAccount(accountOf(r))!;
      for (const m of r.miners) {
        const got = solReturned(perSquare(deploysOf(m)), o);
        expect(got.exact).toBe(true);
        expect(got.lamports, `${r.round_id} ${m.authority}`).toBe(logged(m.checkpoint.logs).sol);
      }
    }
  });

  it("hand-computed: the winning square returns d × (T − max(T/100, 1)) / T", () => {
    // 9ABZ on square 24 of round 423,310: 1,927,710 × (449,324,716 − 4,493,247) / 449,324,716
    expect(squareReturn(1_927_710n, 449_324_716n, true)).toBe(1_908_432n);
    // A tiny square: admin and protocol fees are at least 1 lamport each.
    expect(squareReturn(5n, 5n, false)).toBe(3n);
    expect(squareReturn(5n, 5n, true)).toBe(4n);
  });

  it("with only the ResetEvent, ORE mined stays exact and losing squares are within 2 lamports each", () => {
    for (const r of fixture.rounds) {
      const o = outcomeFromReset(rowOf(resetOf(r), r.reset.signature));
      for (const m of r.miners) {
        const d = perSquare(deploysOf(m));
        const got = solReturned(d, o);
        expect(got.exact).toBe(false);
        const want = logged(m.checkpoint.logs).sol!;
        const squares = d.filter((x) => x > 0n).length;
        expect(want - got.lamports).toBeGreaterThanOrEqual(0n);
        expect(want - got.lamports).toBeLessThanOrEqual(2n * BigInt(squares));
      }
    }
  });

  it("refunds everything when a round has no entropy", () => {
    const o = { ...outcomeFromReset(rowOf(resetOf(round("423800")), "x")), winningSquare: null };
    const d = perSquare([{ amount: 1_000n, mask: 0b111 }]);
    expect(solReturned(d, o)).toEqual({ lamports: 3_000n, exact: true });
    expect(oreMined("x", d, o).total).toBe(0n);
  });

  it("the split marker is ORE's SPLIT_ADDRESS", () => {
    expect(outcomeFromReset(rowOf(resetOf(round("423310")), "x")).split).toBe(true);
    expect(resetOf(round("423310")).topMiner).toBe(ORE_SPLIT_ADDRESS);
    expect(outcomeFromReset(rowOf(resetOf(round("423508")), "x")).split).toBe(false);
  });
});
