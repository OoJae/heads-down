/**
 * ORE `Round` account (api/src/state/round.rs @ b92c5043), read after the round's reset. It is
 * the only place ORE keeps each square's total SOL, which ORE's `checkpoint` divides by, so it
 * makes the SOL-returned arithmetic exact (the ResetEvent carries only the winning square's
 * total). It is closed about a day after the round ends, so the indexer snapshots it early.
 *
 * Steel layout, 952 bytes: disc u64 (= 109) | id u64 | deployed [u64; 25] | mass [u64; 25] |
 * count [u64; 25] | slot_hash [u8; 32] | expires_at u64 | motherlode u64 | rent_payer [32] |
 * rewards [u64; 25] | total_vaulted u64 | total_returned_sol u64 | total_miners u64 | top_miner [32]
 */
import { ByteReader, ByteWriter, DecodeError, U64_MAX } from "./bytes.ts";
import { decodeBase58 } from "./base58.ts";
import { findProgramAddress, seed, u64le } from "./pda.ts";
import { ORE_PROGRAM_ID } from "../constants.ts";

export const ORE_ROUND_DISC = 109n;
export const ORE_ROUND_SIZE = 952;
export const ORE_BOARD_DISC = 105n;
export const ORE_BOARD_SIZE = 40;
export const SQUARES = 25;

export interface OreRoundAccount {
  id: bigint;
  /** SOL on each square (lamports). */
  deployed: bigint[];
  slotHash: Uint8Array;
  expiresAt: bigint;
  /** ORE paid out as the Motherlode this round (atoms; 0 when it did not hit). */
  motherlode: bigint;
  rentPayer: string;
  /** rewards[0] = the round's +1 ORE mint; `top_miner_reward()` is their sum. */
  rewards: bigint[];
  totalVaulted: bigint;
  totalReturnedSol: bigint;
  totalMiners: bigint;
  /** SPLIT_ADDRESS when the +1 ORE is split pro rata, else the winning miner's authority. */
  topMiner: string;
}

export function decodeOreRound(data: Uint8Array): OreRoundAccount {
  if (data.length !== ORE_ROUND_SIZE) throw new DecodeError("BAD_LENGTH", `Round must be ${ORE_ROUND_SIZE} bytes, got ${data.length}`);
  const r = new ByteReader(data);
  const disc = r.u64("disc");
  if (disc !== ORE_ROUND_DISC) throw new DecodeError("BAD_TAG", `Round discriminator ${disc}`);
  const id = r.u64("id");
  const deployed = Array.from({ length: SQUARES }, () => r.u64("deployed"));
  r.skip(8 * SQUARES, "mass");
  r.skip(8 * SQUARES, "count");
  const slotHash = r.fixed(32, "slot_hash");
  const out: OreRoundAccount = {
    id,
    deployed,
    slotHash,
    expiresAt: r.u64("expires_at"),
    motherlode: r.u64("motherlode"),
    rentPayer: r.address("rent_payer"),
    rewards: Array.from({ length: SQUARES }, () => r.u64("rewards")),
    totalVaulted: r.u64("total_vaulted"),
    totalReturnedSol: r.u64("total_returned_sol"),
    totalMiners: r.u64("total_miners"),
    topMiner: r.address("top_miner"),
  };
  r.end("Round");
  return out;
}

/** `Round::rng()`: XOR of the four u64 words of the slot hash; none while unset (0) or unusable (0xFF…). */
export function roundRng(slotHash: Uint8Array): bigint | null {
  if (slotHash.length !== 32) throw new DecodeError("BAD_LENGTH", "slot_hash must be 32 bytes");
  if (slotHash.every((b) => b === 0) || slotHash.every((b) => b === 0xff)) return null;
  const v = new DataView(slotHash.buffer, slotHash.byteOffset, 32);
  return v.getBigUint64(0, true) ^ v.getBigUint64(8, true) ^ v.getBigUint64(16, true) ^ v.getBigUint64(24, true);
}

/** A round's reset has run iff its slot hash is set (reset writes the entropy value into it). */
export function isRoundReset(r: OreRoundAccount): boolean {
  return !r.slotHash.every((b) => b === 0);
}

export function sumU64(xs: readonly bigint[]): bigint {
  let s = 0n;
  for (const x of xs) s += x;
  if (s > U64_MAX) throw new DecodeError("BAD_FIELD", "sum overflows u64");
  return s;
}

const roundPdaCache = new Map<bigint, string>();
/** ORE `["round", round_id u64 LE]`. */
export function oreRoundPda(roundId: bigint): string {
  let a = roundPdaCache.get(roundId);
  if (a === undefined) {
    a = findProgramAddress([seed("round"), u64le(roundId)], ORE_PROGRAM_ID).address;
    if (roundPdaCache.size > 50_000) roundPdaCache.clear();
    roundPdaCache.set(roundId, a);
  }
  return a;
}

/** Inverse of {@link decodeOreRound} (simulator and tests); mass and count are written as zeros. */
export function encodeOreRound(r: OreRoundAccount): Uint8Array {
  if (r.deployed.length !== SQUARES || r.rewards.length !== SQUARES || r.slotHash.length !== 32) throw new RangeError("Round arrays have the wrong length");
  const w = new ByteWriter(ORE_ROUND_SIZE).u64(ORE_ROUND_DISC).u64(r.id);
  for (const d of r.deployed) w.u64(d);
  w.skip(16 * SQUARES).bytes(r.slotHash).u64(r.expiresAt).u64(r.motherlode).bytes(decodeBase58(r.rentPayer, 32));
  for (const x of r.rewards) w.u64(x);
  w.u64(r.totalVaulted).u64(r.totalReturnedSol).u64(r.totalMiners).bytes(decodeBase58(r.topMiner, 32));
  return w.finish();
}

/** ORE Board (40 bytes): disc | round_id | start_slot | end_slot | production_cost_ema. */
export function decodeOreBoard(data: Uint8Array): { roundId: bigint; startSlot: bigint; endSlot: bigint; productionCostEma: bigint } {
  if (data.length !== ORE_BOARD_SIZE) throw new DecodeError("BAD_LENGTH", `Board must be ${ORE_BOARD_SIZE} bytes, got ${data.length}`);
  const r = new ByteReader(data);
  const disc = r.u64("disc");
  if (disc !== ORE_BOARD_DISC) throw new DecodeError("BAD_TAG", `Board discriminator ${disc}`);
  const out = { roundId: r.u64("round_id"), startSlot: r.u64("start_slot"), endSlot: r.u64("end_slot"), productionCostEma: r.u64("production_cost_ema") };
  r.end("Board");
  return out;
}
