/**
 * `create_program_address` with a known bump: one SHA-256 plus an off-curve check.
 * Used to re-derive every heads_down account address from its own contents and stored bump,
 * so a mislabelled or foreign account can never be counted as a rig.
 */
import { createHash } from "node:crypto";
import { isOffCurveAddress, type Address } from "@solana/addresses";
import { decodeBase58, encodeBase58 } from "./base58.ts";

const PDA_MARKER = new TextEncoder().encode("ProgramDerivedAddress");

export function createProgramAddress(seeds: Uint8Array[], bump: number, programId: string): string | null {
  if (!Number.isInteger(bump) || bump < 0 || bump > 255) return null;
  const h = createHash("sha256");
  for (const s of seeds) {
    if (s.length > 32) return null;
    h.update(s);
  }
  h.update(Uint8Array.of(bump));
  h.update(decodeBase58(programId, 32));
  h.update(PDA_MARKER);
  const out = encodeBase58(new Uint8Array(h.digest()));
  return isOffCurveAddress(out as Address) ? out : null;
}

/**
 * Canonical-bump address (what `find_program_address` returns) iff `bump` is the highest bump
 * that yields an off-curve point; null otherwise.
 */
export function canonicalProgramAddress(seeds: Uint8Array[], bump: number, programId: string): string | null {
  const addr = createProgramAddress(seeds, bump, programId);
  if (addr === null) return null;
  for (let b = 255; b > bump; b--) {
    if (createProgramAddress(seeds, b, programId) !== null) return null;
  }
  return addr;
}

export const seed = (s: string) => new TextEncoder().encode(s);
export const addrBytes = (a: string) => decodeBase58(a, 32);
export function u64le(v: bigint): Uint8Array {
  const b = new Uint8Array(8);
  new DataView(b.buffer).setBigUint64(0, v, true);
  return b;
}

export function rigAddress(authority: string, bump: number, programId: string): string | null {
  return canonicalProgramAddress([seed("rig"), addrBytes(authority)], bump, programId);
}
export function seekerSeatAddress(sgtMint: string, bump: number, programId: string): string | null {
  return canonicalProgramAddress([seed("seeker"), addrBytes(sgtMint)], bump, programId);
}
export function shiftLogAddress(rig: string, shiftId: bigint, bump: number, programId: string): string | null {
  return canonicalProgramAddress([seed("shift"), addrBytes(rig), u64le(shiftId)], bump, programId);
}

/** find_program_address, synchronously (tests, simulator). */
export function findProgramAddress(seeds: Uint8Array[], programId: string): { address: string; bump: number } {
  for (let b = 255; b >= 0; b--) {
    const a = createProgramAddress(seeds, b, programId);
    if (a !== null) return { address: a, bump: b };
  }
  throw new Error("no viable bump");
}
