/**
 * heads_down events against the program's own golden file, programs/heads-down/vectors/events.json
 * (bytes captured from real LiteSVM runs on a fork of live mainnet ORE), read from its canonical
 * location so the two can never drift silently. Plus the original independent-oracle goldens
 * (Python `struct.pack`) for tags 1-5, negative cases and a fuzz loop.
 */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { encodeBase58 } from "../src/codec/base58.ts";
import { fromHex, toHex } from "../src/codec/bytes.ts";
import {
  BREAK_REASON_NAMES,
  HD_ERROR_NAMES,
  HD_EVENT_LAYOUTS,
  HD_EVENT_SIZE,
  HD_EVENT_TAG,
  SKIPS_AFTER_HEARTBEAT,
  breakReasonName,
  decodeHdEvent,
  encodeHdEvent,
  eventLayoutWithOffsets,
  hdErrorName,
  hdErrorRange,
  skipLabel,
  type HdEvent,
  type HdEventKind,
} from "../src/codec/events.ts";

// ---------------------------------------------------------------- the program's golden file

const EVENTS_JSON = new URL("../../../programs/heads-down/vectors/events.json", import.meta.url);
interface GoldenEvent {
  tag: number;
  event: string;
  length: number;
  layout: { name: string; type: string; offset: number; size: number }[];
  sample: { captured_from: string; hex: string; base64: string; fields: Record<string, string | number> };
}
interface GoldenSkip {
  error: number;
  error_hex: string;
  name: string;
  trigger: string;
  hex: string;
  fields: Record<string, string | number>;
}
const golden = JSON.parse(readFileSync(EVENTS_JSON, "utf8")) as { format: string; interface_version: string; events: GoldenEvent[]; skip_codes: GoldenSkip[] };
const camel = (s: string) => s.replace(/_([a-z])/g, (_m, c: string) => c.toUpperCase());

describe("events.json (program golden file): drift check", () => {
  it("is the v1.1 golden file", () => {
    expect(golden.format).toBe("heads-down/golden-events");
    expect(golden.interface_version).toBe("1.1");
  });

  it("covers exactly the tags the indexer decodes", () => {
    expect(golden.events.map((e) => e.tag).sort((a, b) => a - b)).toEqual(Object.values(HD_EVENT_TAG).sort((a, b) => a - b));
    for (const e of golden.events) expect(HD_EVENT_TAG[e.event as HdEventKind]).toBe(e.tag);
  });

  for (const e of golden.events) {
    describe(`tag ${e.tag} ${e.event}`, () => {
      const kind = e.event as HdEventKind;

      it("layout matches field for field (name, type, offset, size) and length", () => {
        expect(eventLayoutWithOffsets(kind)).toEqual(e.layout);
        expect(HD_EVENT_SIZE[e.tag]).toBe(e.length);
      });

      it(`decodes the captured sample (${e.sample.captured_from}) with every field equal`, () => {
        const bytes = fromHex(e.sample.hex);
        expect(Buffer.from(e.sample.base64, "base64").toString("hex")).toBe(e.sample.hex);
        expect(bytes.length).toBe(e.length);
        const ev = decodeHdEvent(bytes) as unknown as Record<string, unknown>;
        expect(ev.kind).toBe(e.event);
        for (const [name, want] of Object.entries(e.sample.fields)) {
          if (name === "tag") continue;
          expect(String(ev[camel(name)]), `${e.event}.${name}`).toBe(String(want));
        }
        expect(Object.keys(ev).length).toBe(1 + HD_EVENT_LAYOUTS[kind].length);
      });

      it("re-encodes to the captured bytes", () => {
        const ev = decodeHdEvent(fromHex(e.sample.hex)) as HdEvent;
        expect(toHex(encodeHdEvent(ev))).toBe(e.sample.hex);
      });
    });
  }

  it(`decodes all ${golden.skip_codes.length} captured RigSkipped codes and names each exactly as the program does`, () => {
    expect(golden.skip_codes.length).toBe(20);
    for (const s of golden.skip_codes) {
      const ev = decodeHdEvent(fromHex(s.hex));
      expect(ev).toMatchObject({ kind: "RigSkipped", rig: s.fields.rig, roundId: BigInt(String(s.fields.round_id)), error: s.error });
      expect(Number.parseInt(s.error_hex, 16)).toBe(s.error);
      expect(hdErrorName(s.error), s.trigger).toBe(s.name);
      expect(skipLabel(s.error)).not.toMatch(/unknown/);
    }
  });
});

// ---------------------------------------------------------------- independent oracle (tags 1-5)

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
    event: { kind: "ShiftEnded", rig: RIG, shiftId: 12n, darkRounds: 370n, roundsDug: 23n, lamports: 22_999_770n, reason: 1 },
  },
  {
    name: "SeekerVerified",
    hex: `05${RIG_HEX}a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfcbd8010000000000`,
    event: { kind: "SeekerVerified", rig: RIG, sgtMint: MINT, memberNumber: 121035n },
  },
];

describe("heads_down events: independent golden bytes (tags 1-5)", () => {
  for (const g of GOLDEN) {
    it(`${g.name} decodes and encodes`, () => {
      const bytes = fromHex(g.hex);
      expect(bytes.length).toBe(HD_EVENT_SIZE[bytes[0]!]);
      expect(decodeHdEvent(bytes)).toEqual(g.event);
      expect(toHex(encodeHdEvent(g.event))).toBe(g.hex);
    });
  }

  it("the RigDug golden also matches its base64 log form", () => {
    const b64 = "AQECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gE3MGAAAAAAA2Qg8AAAAAAP9/AABAAT8gAAAAAA==";
    expect(toHex(new Uint8Array(Buffer.from(b64, "base64")))).toBe(GOLDEN[0]!.hex);
  });
});

describe("heads_down events: negative cases", () => {
  it("rejects a truncated event (one byte short) and a trailing byte, for every tag", () => {
    for (const e of golden.events) {
      const b = fromHex(e.sample.hex);
      expect(() => decodeHdEvent(b.subarray(0, b.length - 1)), e.event).toThrow(/BAD_LENGTH/);
      expect(() => decodeHdEvent(fromHex(e.sample.hex + "00")), e.event).toThrow(/BAD_LENGTH/);
    }
  });

  it("an 83-byte tag 4 is a length error, not a silent ShiftEndedV2 (the reason tag 10 exists)", () => {
    const v2 = fromHex(golden.events.find((e) => e.tag === 10)!.sample.hex);
    v2[0] = 4;
    expect(() => decodeHdEvent(v2)).toThrow(/BAD_LENGTH: event tag 4 must be 66 bytes, got 83/);
  });

  it("rejects an empty event", () => {
    expect(() => decodeHdEvent(new Uint8Array())).toThrow(/BAD_LENGTH/);
  });

  it("rejects a RigDug mask with bits beyond the 25 ORE squares", () => {
    const b = fromHex(GOLDEN[0]!.hex);
    b[49 + 3] = 0x02; // bit 25 (mask at 1 + 32 + 8 + 8 = 49)
    expect(() => decodeHdEvent(b)).toThrow(/BAD_FIELD/);
  });

  it("reports unknown tags without guessing a layout", () => {
    expect(decodeHdEvent(new Uint8Array([11, 1, 2, 3]))).toEqual({ kind: "Unknown", tag: 11, length: 4 });
    expect(decodeHdEvent(new Uint8Array([0x00]))).toEqual({ kind: "Unknown", tag: 0, length: 1 });
  });

  it("never throws anything but DecodeError on random input", () => {
    let seed = 7;
    const rnd = () => ((seed = (seed * 1103515245 + 12345) >>> 0) >>> 16) & 0xff;
    for (let i = 0; i < 4000; i++) {
      const len = rnd() % 100;
      const b = Uint8Array.from({ length: len }, rnd);
      if (len > 0) b[0] = rnd() % 12;
      try {
        decodeHdEvent(b);
      } catch (e) {
        expect((e as Error).name).toBe("DecodeError");
      }
    }
  });
});

describe("error codes, ranges, labels and break reasons (INTERFACE v1.1 §8, §3.5)", () => {
  it("names heads_down codes 0..31, including the v1.1 codes 24..31", () => {
    expect(HD_ERROR_NAMES).toHaveLength(32);
    expect([24, 25, 26, 27, 28, 29, 30, 31].map(hdErrorName)).toEqual([
      "InvalidRigState",
      "RoundNotActive",
      "MinerNotCheckpointed",
      "MotherlodeCondition",
      "InsufficientAutomationBalance",
      "OreNoOp",
      "FocusOnly",
      "ExecutorUnderfunded",
    ]);
    expect(hdErrorName(1)).toBe("CostGate");
    expect(hdErrorName(7)).toBe("StaleHeartbeat");
    expect(hdErrorName(23)).toBe("StrategyMismatch");
    expect(hdErrorName(99)).toBe("unknown(99)");
  });

  it("labels the shared-crate ranges: p256-introspect 0x2560_00xx and sgt-verify 0x5347_00xx", () => {
    expect(hdErrorName(0x2560_000e)).toBe("p256-introspect MessageMismatch (0x2560000e)");
    expect(hdErrorName(0x2560_000a)).toBe("p256-introspect HighS (0x2560000a)");
    expect(hdErrorName(0x2560_0007)).toBe("p256-introspect ForeignInstructionIndex (0x25600007)");
    expect(hdErrorName(0x2560_00ff)).toBe("p256-introspect unknown(255) (0x256000ff)");
    expect(hdErrorName(0x5347_0023)).toBe("sgt-verify GroupMismatch (0x53470023)");
    expect(hdErrorName(0x5347_003e)).toBe("sgt-verify AmountNotOne (0x5347003e)");
    expect(hdErrorRange(0x2560_000e)).toBe("p256-introspect");
    expect(hdErrorRange(0x5347_0001)).toBe("sgt-verify");
    expect(hdErrorRange(31)).toBe("heads_down");
    expect(hdErrorRange(32)).toBe("unknown");
    // A builtin ProgramError inside a skip: u32::MAX - k (program/src/error.rs skip_code).
    expect(hdErrorName(0xffff_ffff - 3)).toBe("builtin InvalidAccountData (u32::MAX - 3)");
    expect(hdErrorRange(0xffff_ffff)).toBe("builtin");
  });

  it("gives every skip an honest plain label", () => {
    expect(skipLabel(7)).toBe("replay rejected: heartbeat counter not newer");
    expect(skipLabel(8)).toBe("phone went quiet: no heartbeat lease covers this round");
    expect(skipLabel(1)).toMatch(/price gate closed/);
    expect(skipLabel(0x2560_000e)).toMatch(/signed message did not match/);
    for (let c = 0; c < 32; c++) expect(skipLabel(c)).not.toMatch(/\b(earn|yield|stake|profit|income)\b/i);
  });

  it("knows which skips come after a verified heartbeat (its lease was granted)", () => {
    for (const c of [1, 8, 9, 10, 11, 12, 25, 26, 27, 28, 29, 30, 31]) expect(SKIPS_AFTER_HEARTBEAT.has(c)).toBe(true);
    for (const c of [2, 3, 6, 7, 13, 14, 20, 23, 0x2560_000e]) expect(SKIPS_AFTER_HEARTBEAT.has(c)).toBe(false);
  });

  it("names break reasons 0..8, including v1.1's unplugged and unlocked", () => {
    expect(BREAK_REASON_NAMES).toHaveLength(9);
    expect(breakReasonName(7)).toBe("unplugged");
    expect(breakReasonName(8)).toBe("unlocked");
    expect(breakReasonName(1)).toBe("pickup");
    expect(breakReasonName(42)).toBe("unknown(42)");
  });
});
