import { describe, expect, it } from "vitest";
import { decodeBase58, encodeBase58, isAddress, isSignature } from "../src/codec/base58.ts";
import { ByteReader, ByteWriter, DecodeError, U64_MAX, decodeBase64Strict, popcount32 } from "../src/codec/bytes.ts";
import {
  CONFIG_BUMP,
  CONFIG_PDA,
  EXECUTOR_BUMP,
  EXECUTOR_PDA,
  HEADS_DOWN_PROGRAM_ID,
  derivePda,
  deriveExecutorPda,
  resolveExecutorPda,
} from "../src/constants.ts";

describe("ByteReader", () => {
  it("reads little-endian integers and addresses", () => {
    const w = new ByteWriter(8 + 4 + 2 + 1 + 32)
      .u64(0x0102030405060708n)
      .u32(0xdeadbeef)
      .u16(0xbeef)
      .u8(7)
      .bytes(new Uint8Array(32));
    const r = new ByteReader(w.finish());
    expect(r.u64()).toBe(0x0102030405060708n);
    expect(r.u32()).toBe(0xdeadbeef);
    expect(r.u16()).toBe(0xbeef);
    expect(r.u8()).toBe(7);
    expect(r.address()).toBe("11111111111111111111111111111111");
    expect(() => r.end("x")).not.toThrow();
  });

  it("u64 max and i64 negative round-trip", () => {
    const r = new ByteReader(new ByteWriter(16).u64(U64_MAX).i64(-5n).finish());
    expect(r.u64()).toBe(U64_MAX);
    expect(r.i64()).toBe(-5n);
  });

  it("throws DecodeError(TRUNCATED) instead of reading past the end", () => {
    const r = new ByteReader(new Uint8Array(7));
    expect(() => r.u64("field")).toThrow(DecodeError);
    try {
      r.u64("field");
    } catch (e) {
      expect((e as DecodeError).code).toBe("TRUNCATED");
    }
  });

  it("rejects trailing bytes", () => {
    const r = new ByteReader(new Uint8Array(3));
    r.u16();
    expect(() => r.end("thing")).toThrow(/TRAILING_BYTES/);
  });

  it("ByteWriter rejects out-of-range values", () => {
    expect(() => new ByteWriter(1).u8(256)).toThrow(RangeError);
    expect(() => new ByteWriter(8).u64(-1n)).toThrow(RangeError);
    expect(() => new ByteWriter(8).u64(U64_MAX + 1n)).toThrow(RangeError);
    expect(() => new ByteWriter(1).u16(1)).toThrow(RangeError);
  });
});

describe("base58 / base64", () => {
  it("round-trips and rejects bad characters", () => {
    const bytes = new Uint8Array([0, 0, 1, 2, 3, 255]);
    const s = encodeBase58(bytes);
    expect(s.startsWith("11")).toBe(true);
    expect(decodeBase58(s, 6)).toEqual(bytes);
    expect(() => decodeBase58("0OIl", 32)).toThrow(/BAD_ENCODING/);
  });

  it("bounds input length before decoding", () => {
    expect(() => decodeBase58("2".repeat(10_000), 64)).toThrow(/BAD_LENGTH/);
  });

  it("validates addresses and signatures canonically", () => {
    expect(isAddress(HEADS_DOWN_PROGRAM_ID)).toBe(true);
    expect(isAddress("11111111111111111111111111111111")).toBe(true);
    expect(isAddress("not-an-address")).toBe(false);
    expect(isAddress(HEADS_DOWN_PROGRAM_ID + "1")).toBe(false);
    expect(isAddress(42)).toBe(false);
    expect(
      isSignature("5uZWBsNQamLCRqmLrCpf5G78U1G3eK2wY8qVaQQBvk1Zk4sjNaz56JDZi4VAoBMSDv6YS5vUcnejYJHsGD24MjfQ"),
    ).toBe(true);
    expect(isSignature(HEADS_DOWN_PROGRAM_ID)).toBe(false);
  });

  it("strict base64 rejects characters Node would silently skip", () => {
    expect(decodeBase64Strict("AQID", 3)).toEqual(new Uint8Array([1, 2, 3]));
    expect(() => decodeBase64Strict("AQ*D", 3)).toThrow(/BAD_ENCODING/);
    expect(() => decodeBase64Strict("AQIDBA==", 3)).toThrow(/BAD_LENGTH/);
  });

  it("popcount32", () => {
    expect(popcount32(0)).toBe(0);
    expect(popcount32(0x1ffffff)).toBe(25);
    expect(popcount32(0x2000)).toBe(1);
  });
});

describe("pinned PDAs", () => {
  it("Executor PDA matches `solana find-program-derived-address <id> string:executor`", async () => {
    const { address, bump } = await deriveExecutorPda();
    expect(address).toBe(EXECUTOR_PDA);
    expect(bump).toBe(EXECUTOR_BUMP);
    await expect(resolveExecutorPda()).resolves.toBe(EXECUTOR_PDA);
  });

  it("Config PDA matches the CLI", async () => {
    const { address, bump } = await derivePda(HEADS_DOWN_PROGRAM_ID, "config");
    expect(address).toBe(CONFIG_PDA);
    expect(bump).toBe(CONFIG_BUMP);
  });

  it("derives a different executor for a different (localnet) program id", async () => {
    const other = await resolveExecutorPda("oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv");
    expect(other).not.toBe(EXECUTOR_PDA);
  });
});
