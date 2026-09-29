/**
 * Golden bytes for heads_down events. The hex below was produced by an independent oracle
 * (Python `struct.pack("<...")` over the INTERFACE.md field order), not by the TS encoder.
 */
import { describe, expect, it } from "vitest";
import { encodeBase58 } from "../src/codec/base58.ts";
import { fromHex, toHex } from "../src/codec/bytes.ts";
import {
  HD_EVENT_SIZE,
  breakReasonName,
  decodeHdEvent,
  encodeHdEvent,
  hdErrorName,
  type HdEvent,
} from "../src/codec/events.ts";

const RIG = encodeBase58(Uint8Array.from({ length: 32 }, (_, i) => i + 1));
const MINT = encodeBase58(Uint8Array.from({ length: 32 }, (_, i) => 0xa0 + i));
const RIG_HEX = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

const GOLDEN: { name: string; hex: string; event: HdEvent }[] = [
  {
    name: "RigDug",
    hex: `01${RIG_HEX}137306000000000036420f0000000000ff7f000040013f2000000000`,
    event: { kind: "RigDug", rig: RIG, roundId: 422675n, lamports: 999_990n, mask: 0x7fff, emaEv: 541_000_000n },
  },
  {
    name: "RigSkipped",
    hex: `02${RIG_HEX}147306000000000007000000`,
    event: { kind: "RigSkipped", rig: RIG, roundId: 422676n, error: 7 },
  },
  {
    name: "ShiftArmed",
    hex: `03${RIG_HEX}0c00000000000000`,
    event: { kind: "ShiftArmed", rig: RIG, shiftId: 12n },
  },
  {
    name: "ShiftEnded",
    hex: `04${RIG_HEX}0c0000000000000072010000000000001700000000000000daf25e010000000001`,
    event: {
      kind: "ShiftEnded",
      rig: RIG,
      shiftId: 12n,
      darkRounds: 370n,
      roundsDug: 23n,
      lamports: 22_999_770n,
      reason: 1,
    },
  },
  {
    name: "SeekerVerified",
    hex: `05${RIG_HEX}a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfcbd8010000000000`,
    event: { kind: "SeekerVerified", rig: RIG, sgtMint: MINT, memberNumber: 121035n },
  },
];

describe("heads_down events: golden bytes", () => {
  for (const g of GOLDEN) {
    it(`${g.name} decodes from golden bytes`, () => {
      const bytes = fromHex(g.hex);
      expect(bytes.length).toBe(HD_EVENT_SIZE[bytes[0]!]);
      expect(decodeHdEvent(bytes)).toEqual(g.event);
    });
    it(`${g.name} encodes to golden bytes`, () => {
      expect(toHex(encodeHdEvent(g.event))).toBe(g.hex);
    });
  }

  it("the RigDug golden also matches its base64 log form", () => {
    const b64 = "AQECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gE3MGAAAAAAA2Qg8AAAAAAP9/AABAAT8gAAAAAA==";
    expect(toHex(new Uint8Array(Buffer.from(b64, "base64")))).toBe(GOLDEN[0]!.hex);
  });
});

describe("heads_down events: negative cases", () => {
  it("rejects a truncated event (one byte short)", () => {
    const bytes = fromHex(GOLDEN[0]!.hex).subarray(0, 60);
    expect(() => decodeHdEvent(bytes)).toThrow(/BAD_LENGTH/);
  });

  it("rejects an event with a trailing byte", () => {
    const b = fromHex(GOLDEN[2]!.hex + "00");
    expect(() => decodeHdEvent(b)).toThrow(/BAD_LENGTH/);
  });

  it("rejects an empty event", () => {
    expect(() => decodeHdEvent(new Uint8Array())).toThrow(/BAD_LENGTH/);
  });

  it("rejects a RigDug mask with bits beyond the 25 ORE squares", () => {
    const b = fromHex(GOLDEN[0]!.hex);
    // mask is at 1 + 32 + 8 + 8 = 49
    b[49 + 3] = 0x02; // bit 25
    expect(() => decodeHdEvent(b)).toThrow(/BAD_FIELD/);
  });

  it("reports unknown tags without guessing a layout", () => {
    expect(decodeHdEvent(new Uint8Array([0x09, 1, 2, 3]))).toEqual({ kind: "Unknown", tag: 9, length: 4 });
    expect(decodeHdEvent(new Uint8Array([0x00]))).toEqual({ kind: "Unknown", tag: 0, length: 1 });
  });

  it("never throws anything but DecodeError on random input", () => {
    let seed = 7;
    const rnd = () => ((seed = (seed * 1103515245 + 12345) >>> 0) >>> 16) & 0xff;
    for (let i = 0; i < 2000; i++) {
      const len = rnd() % 90;
      const b = Uint8Array.from({ length: len }, rnd);
      if (len > 0) b[0] = (rnd() % 7) as number;
      try {
        decodeHdEvent(b);
      } catch (e) {
        expect((e as Error).name).toBe("DecodeError");
      }
    }
  });

  it("names error and break-reason codes, including unknown ones", () => {
    expect(hdErrorName(1)).toBe("CostGate");
    expect(hdErrorName(7)).toBe("StaleHeartbeat");
    expect(hdErrorName(23)).toBe("StrategyMismatch");
    expect(hdErrorName(99)).toBe("unknown(99)");
    expect(breakReasonName(1)).toBe("pickup");
    expect(breakReasonName(42)).toBe("unknown(42)");
  });
});
