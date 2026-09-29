/**
 * Base58 via Anza's @solana/codecs-strings, with input limits so an attacker-sized string
 * cannot make the (quadratic) base58 decoder burn CPU.
 */
import { getBase58Decoder, getBase58Encoder } from "@solana/codecs-strings";
import { DecodeError } from "./errors.ts";

const toBytes = getBase58Encoder(); // base58 string -> bytes
const toString = getBase58Decoder(); // bytes -> base58 string

const B58_RE = /^[1-9A-HJ-NP-Za-km-z]*$/;

export function encodeBase58(bytes: Uint8Array): string {
  return toString.decode(bytes);
}

/**
 * Decodes base58. `maxBytes` bounds the input length (a base58 string is at most
 * ceil(n * log(256)/log(58)) ≈ 1.37 n characters for n bytes).
 */
export function decodeBase58(s: string, maxBytes: number): Uint8Array {
  if (s.length > Math.ceil(maxBytes * 1.3658) + 1) {
    throw new DecodeError("BAD_LENGTH", `base58 string longer than ${maxBytes} bytes allows`);
  }
  if (!B58_RE.test(s)) throw new DecodeError("BAD_ENCODING", "invalid base58 character");
  const out = toBytes.encode(s);
  if (out.length > maxBytes) throw new DecodeError("BAD_LENGTH", `decoded ${out.length} > ${maxBytes} bytes`);
  return new Uint8Array(out);
}

/** True for a canonical base58 encoding of exactly 32 bytes. */
export function isAddress(s: unknown): s is string {
  if (typeof s !== "string" || s.length < 32 || s.length > 44) return false;
  try {
    const b = decodeBase58(s, 32);
    return b.length === 32 && encodeBase58(b) === s;
  } catch {
    return false;
  }
}

/** True for a canonical base58 encoding of exactly 64 bytes (a transaction signature). */
export function isSignature(s: unknown): s is string {
  if (typeof s !== "string" || s.length < 64 || s.length > 88) return false;
  try {
    const b = decodeBase58(s, 64);
    return b.length === 64 && encodeBase58(b) === s;
  } catch {
    return false;
  }
}
