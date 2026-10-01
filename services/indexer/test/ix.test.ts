/**
 * heads_down instruction decoding against programs/heads-down/vectors/instructions.json: all 15
 * instructions, both auth paths, 25 vectors executed in LiteSVM on a fork of live mainnet ORE.
 * Every data field (name, offset, size, value) and every account role must match.
 */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { fromHex } from "../src/codec/bytes.ts";
import { HD_IX, decodeHdInstruction, hdAccountRoles, rigAccountIndex } from "../src/codec/ix.ts";

const FILE = new URL("../../../programs/heads-down/vectors/instructions.json", import.meta.url);
interface Vector {
  name: string;
  tag: number;
  instruction: string;
  data_len: number;
  data_hex: string;
  data_layout: { name: string; type: string; offset: number; size: number; value: string | number }[];
  accounts: { index: number; role: string; pubkey: string }[];
  litesvm: { result: string; events?: { event: string; fields: Record<string, unknown> }[] };
}
const file = JSON.parse(readFileSync(FILE, "utf8")) as { interface_version: string; program_id: string; instructions: Vector[] };

describe("instructions.json (program golden file)", () => {
  it("is v1.1 and covers all 15 instructions with 25 vectors", () => {
    expect(file.interface_version).toBe("1.1");
    expect(file.program_id).toBe("HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p");
    expect(file.instructions).toHaveLength(25);
    expect(new Set(file.instructions.map((v) => v.tag)).size).toBe(15);
    expect(Object.keys(HD_IX)).toHaveLength(15);
  });

  for (const v of file.instructions) {
    it(`${v.name}: every field and account role matches`, () => {
      const data = fromHex(v.data_hex);
      expect(data.length).toBe(v.data_len);
      const ix = decodeHdInstruction(data);
      expect(ix.tag).toBe(v.tag);
      expect(ix.name).toBe(v.instruction);
      expect(ix.fields.map((f) => ({ name: f.name, offset: f.offset, size: f.size, value: String(f.value) }))).toEqual(
        v.data_layout.map((f) => ({ name: f.name, offset: f.offset, size: f.size, value: String(f.value) })),
      );
      // Account roles: the program names rig[i]/authority[i]/... per entry; ours carry the same names.
      expect(hdAccountRoles(ix, v.accounts.length)).toEqual(v.accounts.map((a) => a.role));
      // The rig of every heartbeat entry (and of single-rig instructions) is where the program reads it.
      if (ix.entries.length) {
        ix.entries.forEach((_e, i) => expect(v.accounts[rigAccountIndex(ix, i)!]!.role).toBe(`rig[${i}]`));
      } else {
        const at = rigAccountIndex(ix);
        if (at !== null) expect(v.accounts[at]!.role).toBe("rig");
      }
    });
  }

  it("exposes the heartbeat entries and the arm plan the haul replays", () => {
    const dig = decodeHdInstruction(fromHex(file.instructions.find((v) => v.name === "dig_batch_two_rigs")!.data_hex));
    expect(dig.entries).toEqual([
      { hbIx: 1, hbSigIndex: 0, counter: 1n, roundId: 422_700n, leaseRounds: 2 },
      { hbIx: 1, hbSigIndex: 1, counter: 1n, roundId: 422_700n, leaseRounds: 3 },
    ]);
    const reuse = decodeHdInstruction(fromHex(file.instructions.find((v) => v.name === "dig_reuse_lease")!.data_hex));
    expect(reuse.entries[0]!.hbIx).toBe(0xff);
    const arm = decodeHdInstruction(fromHex(file.instructions.find((v) => v.name === "arm_shift_p256")!.data_hex));
    expect(arm.plan).toEqual({
      mode: 1, maxEvCost: 700_000_000n, digLamports: 1_000_000n, split: 10, solo: 0, lease: 3, flags: 0,
      windowStart: 1_790_636_400n, windowEnd: 1_790_668_800n, counter: 2n,
    });
    const brk = decodeHdInstruction(fromHex(file.instructions.find((v) => v.name === "break_shift_p256")!.data_hex));
    expect(brk.signal).toEqual({ mode: 1, reason: 1, counter: 4n });
  });
});

describe("instruction decoding: negative cases", () => {
  const hex = (name: string) => file.instructions.find((v) => v.name === name)!.data_hex;

  it("rejects every vector with one byte more or one byte less", () => {
    for (const v of file.instructions) {
      const d = fromHex(v.data_hex);
      expect(() => decodeHdInstruction(fromHex(v.data_hex + "00")), v.name).toThrow(/BAD_LENGTH|TRAILING/);
      if (d.length > 1) expect(() => decodeHdInstruction(d.subarray(0, d.length - 1)), v.name).toThrow(/BAD_LENGTH|TRUNCATED/);
    }
  });

  it("enforces the program's own field rules", () => {
    expect(() => decodeHdInstruction(new Uint8Array([15]))).toThrow(/BAD_TAG/);
    expect(() => decodeHdInstruction(new Uint8Array())).toThrow(/BAD_LENGTH/);
    // dig with n = 0, and n inconsistent with the length
    expect(() => decodeHdInstruction(new Uint8Array([6, 0]))).toThrow(/BAD_LENGTH/);
    const two = fromHex(hex("dig_batch_two_rigs"));
    two[1] = 1;
    expect(() => decodeHdInstruction(two)).toThrow(/BAD_LENGTH/);
    // arm_shift mode 1 needs the 10-byte P-256 tail; mode 2 is invalid
    const arm = fromHex(hex("arm_shift_wallet"));
    arm[1] = 1;
    expect(() => decodeHdInstruction(arm)).toThrow(/BAD_LENGTH/);
    arm[1] = 2;
    expect(() => decodeHdInstruction(arm)).toThrow(/BAD_FIELD/);
    // register_rig has_attestation must be 0 or 1
    const reg = fromHex(hex("register_rig_guest"));
    reg[34] = 2;
    expect(() => decodeHdInstruction(reg)).toThrow(/BAD_FIELD/);
  });

  it("never throws anything but DecodeError on random input", () => {
    let seed = 11;
    const rnd = () => ((seed = (seed * 1103515245 + 12345) >>> 0) >>> 16) & 0xff;
    for (let i = 0; i < 4000; i++) {
      const len = rnd() % 120;
      const b = Uint8Array.from({ length: len }, rnd);
      if (len > 0) b[0] = rnd() % 16;
      try {
        decodeHdInstruction(b);
      } catch (e) {
        expect((e as Error).name).toBe("DecodeError");
      }
    }
  });
});
