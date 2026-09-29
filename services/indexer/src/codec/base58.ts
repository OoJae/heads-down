/**
 * Base58 (Bitcoin alphabet) using the base-x byte-array algorithm: O(n^2) on small integers,
 * ~10x faster than BigInt-based codecs on the 32/64/121-byte values the indexer handles in bulk.
 * Equivalence with Anza's @solana/codecs-strings is fuzz-tested (test/codec.test.ts).
 *
 * Inputs are length-bounded before decoding so an attacker-sized string cannot burn CPU.
 */
import { DecodeError } from "./errors.ts";

const ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
const MAP = new Int8Array(128).fill(-1);
for (let i = 0; i < ALPHABET.length; i++) MAP[ALPHABET.charCodeAt(i)] = i;

export function encodeBase58(source: Uint8Array): string {
  let zeroes = 0;
  while (zeroes < source.length && source[zeroes] === 0) zeroes++;
  const size = (((source.length - zeroes) * 138) / 100 + 1) >>> 0;
  const b58 = new Uint8Array(size);
  let length = 0;
  for (let p = zeroes; p < source.length; p++) {
    let carry = source[p]!;
    let i = 0;
    for (let it = size - 1; (carry !== 0 || i < length) && it >= 0; it--, i++) {
      carry += 256 * b58[it]!;
      b58[it] = carry % 58;
      carry = (carry / 58) >>> 0;
    }
    length = i;
  }
  let it = size - length;
  while (it < size && b58[it] === 0) it++;
  let out = "1".repeat(zeroes);
  for (; it < size; it++) out += ALPHABET[b58[it]!];
  return out;
}

/**
 * Decodes base58. `maxBytes` bounds the input length (a base58 string is at most
 * ceil(n * log(256)/log(58)) ≈ 1.37 n characters for n bytes).
 */
export function decodeBase58(s: string, maxBytes: number): Uint8Array {
  if (s.length > Math.ceil(maxBytes * 1.3658) + 1) {
    throw new DecodeError("BAD_LENGTH", `base58 string longer than ${maxBytes} bytes allows`);
  }
  let zeroes = 0;
  while (zeroes < s.length && s.charCodeAt(zeroes) === 49 /* '1' */) zeroes++;
  const size = (((s.length - zeroes) * 733) / 1000 + 1) >>> 0;
  const b256 = new Uint8Array(size);
  let length = 0;
  for (let p = zeroes; p < s.length; p++) {
    const c = s.charCodeAt(p);
    const v = c < 128 ? MAP[c]! : -1;
    if (v < 0) throw new DecodeError("BAD_ENCODING", "invalid base58 character");
    let carry = v;
    let i = 0;
    for (let it = size - 1; (carry !== 0 || i < length) && it >= 0; it--, i++) {
      carry += 58 * b256[it]!;
      b256[it] = carry & 0xff;
      carry >>>= 8;
    }
    length = i;
  }
  let it = size - length;
  while (it < size && b256[it] === 0) it++;
  const out = new Uint8Array(zeroes + (size - it));
  out.set(b256.subarray(it), zeroes);
  if (out.length > maxBytes) throw new DecodeError("BAD_LENGTH", `decoded ${out.length} > ${maxBytes} bytes`);
  return out;
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
