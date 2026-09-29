/**
 * ORE events against REAL mainnet bytes: the inner `Log` instruction of
 *  - a manual ORE deploy, tx 5uZWBsNQ…MjfQ (slot 451,727,379), and
 *  - an ORE reset, tx 4jjLVs2Q…XW3jp (slot 451,728,874, a v1 transaction),
 * cross-checked against api.ore.com's JSON for the same round.
 */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { encodeBase58 } from "../src/codec/base58.ts";
import { fromHex } from "../src/codec/bytes.ts";
import {
  decodeOreDeployEvent,
  decodeOreLogInstruction,
  decodeOreResetEvent,
  normalizeWinningSquare,
} from "../src/codec/ore.ts";
import { ORE_SPLIT_ADDRESS } from "../src/constants.ts";

const DEPLOY_LOG_IX_HEX =
  "08" +
  "0200000000000000" +
  "7aac785b72905097ccd58aa3ac5a278ac390e4083068b48a8929536be7658412" +
  "5b4b400000000000" +
  "0020000000000000" +
  "1373060000000000" +
  "7aac785b72905097ccd58aa3ac5a278ac390e4083068b48a8929536be7658412" +
  "ffffffffffffffff" +
  "0100000000000000" +
  "8208bc6a00000000";

const RESET_LOG_IX_HEX =
  "0800000000000000001873060000000000cad4ec1a00000000bad5ec1a000000001600000000000000069d0c318759b1e6736f299e774dfefd381d7c5cd8512f10c8918e0000000001aa00000000000000000000000000000076e8eb9d0200000097829e3f0000000029669a570200000000b08ef01b000000130abc6a00000000510e91295653ad0e06b44d1b00000000";

describe("ORE DeployEvent (real mainnet bytes)", () => {
  it("decodes the Log instruction of tx 5uZWBsNQ…", () => {
    const ev = decodeOreLogInstruction(fromHex(DEPLOY_LOG_IX_HEX));
    expect(ev).toEqual({
      kind: "OreDeploy",
      authority: "9FsGp26UkKndmewwV5sNTPXfBoNP1BxywifBrpWrxpVP",
      amount: 4_213_595n,
      mask: 0x2000,
      roundId: 422_675n,
      signer: "9FsGp26UkKndmewwV5sNTPXfBoNP1BxywifBrpWrxpVP",
      strategy: 0xffff_ffff_ffff_ffffn, // manual deploy
      totalSquares: 1,
      ts: 1_790_707_842n,
    });
  });

  it("matches the fixture's own inner instruction data", () => {
    const tx = JSON.parse(readFileSync(new URL("./fixtures/ore-deploy-manual-mainnet.json", import.meta.url), "utf8"));
    const keys: string[] = tx.transaction.message.accountKeys;
    const inner = tx.meta.innerInstructions.flatMap((g: { instructions: unknown[] }) => g.instructions);
    const log = inner.find((ix: { programIdIndex: number }) => keys[ix.programIdIndex]!.startsWith("oreV3"));
    expect(log).toBeDefined();
    expect(keys[log.accounts[0]]).toBe("BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi");
  });

  it("rejects total_squares that disagree with the mask", () => {
    const b = fromHex(DEPLOY_LOG_IX_HEX).subarray(1);
    b[104] = 2; // total_squares at event offset 104 (strategy is 96..104)
    expect(() => decodeOreDeployEvent(b)).toThrow(/BAD_FIELD/);
  });

  it("rejects a wrong length and a wrong discriminator", () => {
    const b = fromHex(DEPLOY_LOG_IX_HEX).subarray(1);
    expect(() => decodeOreDeployEvent(b.subarray(0, 119))).toThrow(/BAD_LENGTH/);
    const c = b.slice();
    c[0] = 3;
    expect(() => decodeOreDeployEvent(c)).toThrow(/BAD_TAG/);
  });

  it("returns null for non-Log ORE instructions and OreOther for unknown discriminators", () => {
    expect(decodeOreLogInstruction(new Uint8Array([6, 1, 2, 3]))).toBeNull();
    expect(decodeOreLogInstruction(new Uint8Array())).toBeNull();
    const other = new Uint8Array(1 + 8 + 16);
    other[0] = 8;
    other[1] = 4; // ClaimEvent
    expect(decodeOreLogInstruction(other)).toEqual({ kind: "OreOther", disc: 4n });
    expect(() => decodeOreLogInstruction(new Uint8Array([8, 2, 0]))).toThrow(/TRUNCATED/);
  });
});

describe("ORE ResetEvent (real mainnet bytes, v1 transaction)", () => {
  it("decodes round 422,680 and matches api.ore.com field for field", () => {
    const ev = decodeOreLogInstruction(fromHex(RESET_LOG_IX_HEX));
    expect(ev).toEqual({
      kind: "OreReset",
      roundId: 422_680n,
      startSlot: 451_728_586n,
      endSlot: 451_728_826n,
      winningSquare: 22,
      topMiner: ORE_SPLIT_ADDRESS,
      totalMiners: 170n,
      motherlode: 0n,
      totalDeployed: 11_239_417_974n,
      totalVaulted: 1_067_352_727n,
      totalWinnings: 10_059_671_081n,
      totalMinted: 120_000_000_000n,
      ts: 1_790_708_243n,
      rng: 1_057_593_117_031_599_697n,
      deployedWinningSquare: 458_077_190n,
    });
    // api.ore.com calls total_miners "num_winners" (same struct position, same value).
    const api = JSON.parse(readFileSync(new URL("./fixtures/api-ore-events-reset-page.json", import.meta.url), "utf8"));
    const row = api.find((it: [number[], { round_id: number }]) => it[1].round_id === 422680);
    expect(row).toBeDefined();
    expect(row[1].num_winners).toBe(170);
    expect(row[1].deployed_winning_square).toBe(458077190);
    expect(encodeBase58(Uint8Array.from(row[1].top_miner))).toBe(ORE_SPLIT_ADDRESS);
    // The first element is the reset transaction's signature: the explorer link for the round.
    expect(encodeBase58(Uint8Array.from(row[0]))).toBe(
      "4jjLVs2Q1fEVqpdpcjS9iWCBHPYRPUAyADCPUHmMxZARCoZPpnAVvRhpD72VkBmcBLMav2ofVKS9XFpFisSXW3jp",
    );
  });

  it("maps winning_square u64::MAX to null (no entropy, full refund) and rejects > 24", () => {
    expect(normalizeWinningSquare(0xffff_ffff_ffff_ffffn)).toBeNull();
    expect(normalizeWinningSquare(24n)).toBe(24);
    expect(() => normalizeWinningSquare(25n)).toThrow(/BAD_FIELD/);
  });

  it("rejects a truncated ResetEvent", () => {
    expect(() => decodeOreResetEvent(fromHex(RESET_LOG_IX_HEX).subarray(1, 100))).toThrow(/BAD_LENGTH/);
  });
});
