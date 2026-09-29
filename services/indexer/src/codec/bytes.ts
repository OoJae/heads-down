/**
 * Bounds-checked little-endian reader/writer.
 *
 * Every byte the indexer decodes comes from a transaction or account that anyone can
 * create, so decoding must never throw anything but a {@link DecodeError}, must never read
 * past the buffer, and must never silently accept trailing bytes.
 */
import { encodeBase58 } from "./base58.ts";
import { DecodeError } from "./errors.ts";

export { DecodeError } from "./errors.ts";

export const U64_MAX = (1n << 64n) - 1n;

export class ByteReader {
  private readonly view: DataView;
  private offset = 0;
  readonly bytes: Uint8Array;

  constructor(bytes: Uint8Array) {
    this.bytes = bytes;
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  }

  get position(): number {
    return this.offset;
  }

  get remaining(): number {
    return this.bytes.length - this.offset;
  }

  private need(n: number, what: string): void {
    if (this.offset + n > this.bytes.length) {
      throw new DecodeError(
        "TRUNCATED",
        `need ${n} bytes for ${what} at offset ${this.offset}, have ${this.remaining}`,
      );
    }
  }

  u8(what = "u8"): number {
    this.need(1, what);
    const v = this.view.getUint8(this.offset);
    this.offset += 1;
    return v;
  }

  u16(what = "u16"): number {
    this.need(2, what);
    const v = this.view.getUint16(this.offset, true);
    this.offset += 2;
    return v;
  }

  u32(what = "u32"): number {
    this.need(4, what);
    const v = this.view.getUint32(this.offset, true);
    this.offset += 4;
    return v;
  }

  u64(what = "u64"): bigint {
    this.need(8, what);
    const v = this.view.getBigUint64(this.offset, true);
    this.offset += 8;
    return v;
  }

  i64(what = "i64"): bigint {
    this.need(8, what);
    const v = this.view.getBigInt64(this.offset, true);
    this.offset += 8;
    return v;
  }

  fixed(n: number, what = "bytes"): Uint8Array {
    this.need(n, what);
    const v = this.bytes.slice(this.offset, this.offset + n);
    this.offset += n;
    return v;
  }

  /** 32-byte address, returned as base58. */
  address(what = "address"): string {
    return encodeBase58(this.fixed(32, what));
  }

  skip(n: number, what = "padding"): void {
    this.need(n, what);
    this.offset += n;
  }

  /** Asserts the whole buffer was consumed (no trailing bytes). */
  end(what: string): void {
    if (this.offset !== this.bytes.length) {
      throw new DecodeError(
        "TRAILING_BYTES",
        `${what}: ${this.bytes.length - this.offset} unread bytes after offset ${this.offset}`,
      );
    }
  }
}

export class ByteWriter {
  private readonly buf: Uint8Array;
  private readonly view: DataView;
  private offset = 0;

  constructor(size: number) {
    this.buf = new Uint8Array(size);
    this.view = new DataView(this.buf.buffer);
  }

  private room(n: number): void {
    if (this.offset + n > this.buf.length) throw new RangeError("ByteWriter overflow");
  }

  u8(v: number): this {
    if (!Number.isInteger(v) || v < 0 || v > 0xff) throw new RangeError(`u8 out of range: ${v}`);
    this.room(1);
    this.view.setUint8(this.offset, v);
    this.offset += 1;
    return this;
  }

  u16(v: number): this {
    if (!Number.isInteger(v) || v < 0 || v > 0xffff) throw new RangeError(`u16 out of range: ${v}`);
    this.room(2);
    this.view.setUint16(this.offset, v, true);
    this.offset += 2;
    return this;
  }

  u32(v: number): this {
    if (!Number.isInteger(v) || v < 0 || v > 0xffff_ffff) throw new RangeError(`u32 out of range: ${v}`);
    this.room(4);
    this.view.setUint32(this.offset, v, true);
    this.offset += 4;
    return this;
  }

  u64(v: bigint): this {
    if (v < 0n || v > U64_MAX) throw new RangeError(`u64 out of range: ${v}`);
    this.room(8);
    this.view.setBigUint64(this.offset, v, true);
    this.offset += 8;
    return this;
  }

  i64(v: bigint): this {
    if (v < -(1n << 63n) || v >= 1n << 63n) throw new RangeError(`i64 out of range: ${v}`);
    this.room(8);
    this.view.setBigInt64(this.offset, v, true);
    this.offset += 8;
    return this;
  }

  bytes(b: Uint8Array): this {
    this.room(b.length);
    this.buf.set(b, this.offset);
    this.offset += b.length;
    return this;
  }

  skip(n: number): this {
    this.room(n);
    this.offset += n;
    return this;
  }

  seek(offset: number): this {
    if (offset < 0 || offset > this.buf.length) throw new RangeError("seek out of range");
    this.offset = offset;
    return this;
  }

  finish(): Uint8Array {
    return this.buf;
  }
}

export function popcount32(mask: number): number {
  let m = mask >>> 0;
  let n = 0;
  while (m) {
    m &= m - 1;
    n++;
  }
  return n;
}

export function toHex(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("hex");
}

export function fromHex(hex: string): Uint8Array {
  if (!/^(?:[0-9a-fA-F]{2})*$/.test(hex)) throw new DecodeError("BAD_ENCODING", "invalid hex");
  return new Uint8Array(Buffer.from(hex, "hex"));
}

const B64_RE = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

/** Strict base64 (Node's decoder silently skips invalid characters, which we do not want). */
export function decodeBase64Strict(s: string, maxBytes: number): Uint8Array {
  if (s.length > Math.ceil(maxBytes / 3) * 4) {
    throw new DecodeError("BAD_LENGTH", `base64 longer than ${maxBytes} bytes`);
  }
  if (!B64_RE.test(s)) throw new DecodeError("BAD_ENCODING", "invalid base64");
  return new Uint8Array(Buffer.from(s, "base64"));
}
