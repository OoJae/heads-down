/**
 * ORE events. ORE does not use `sol_log_data`: it self-CPIs its own `Log` instruction (tag 8)
 * with the event struct as instruction data, signed by the Board PDA (`sdk.rs` `program_log`,
 * `log.rs`). The event therefore appears in `meta.innerInstructions` as an ORE instruction
 * whose only account is the Board, and it is immune to log truncation.
 *
 * Layouts (api/src/event.rs @ b92c5043, `#[repr(C)]` Pod, all u64/i64 LE):
 *   DeployEvent (120 B): disc=2 | authority[32] | amount | mask | round_id | signer[32] |
 *                        strategy | total_squares | ts
 *   ResetEvent  (144 B): disc=0 | round_id | start_slot | end_slot | winning_square |
 *                        top_miner[32] | total_miners | motherlode | total_deployed |
 *                        total_vaulted | total_winnings | total_minted | ts | rng |
 *                        deployed_winning_square
 */
import { ByteReader, DecodeError, U64_MAX, popcount32 } from "./bytes.ts";
import { ORE_EVENT_DEPLOY, ORE_EVENT_RESET, ORE_LOG_IX_TAG } from "../constants.ts";
import { ORE_SQUARE_MASK } from "./events.ts";

export const ORE_DEPLOY_EVENT_SIZE = 120;
export const ORE_RESET_EVENT_SIZE = 144;

export interface OreDeployEvent {
  kind: "OreDeploy";
  authority: string;
  /** Lamports per square. */
  amount: bigint;
  /** Squares actually deployed (ORE rebuilds it from the squares it credited). */
  mask: number;
  roundId: bigint;
  signer: string;
  /** Automation strategy, or u64::MAX for a manual deploy. */
  strategy: bigint;
  totalSquares: number;
  ts: bigint;
}

export interface OreResetEvent {
  kind: "OreReset";
  roundId: bigint;
  startSlot: bigint;
  endSlot: bigint;
  /** 0..24, or null when the round had no entropy value (every lamport refunded). */
  winningSquare: number | null;
  topMiner: string;
  totalMiners: bigint;
  motherlode: bigint;
  totalDeployed: bigint;
  totalVaulted: bigint;
  totalWinnings: bigint;
  totalMinted: bigint;
  ts: bigint;
  rng: bigint;
  deployedWinningSquare: bigint;
}

export interface OtherOreEvent {
  kind: "OreOther";
  disc: bigint;
}

export type OreEvent = OreDeployEvent | OreResetEvent | OtherOreEvent;

export function decodeOreDeployEvent(ev: Uint8Array): OreDeployEvent {
  if (ev.length !== ORE_DEPLOY_EVENT_SIZE) {
    throw new DecodeError("BAD_LENGTH", `DeployEvent must be ${ORE_DEPLOY_EVENT_SIZE} bytes, got ${ev.length}`);
  }
  const r = new ByteReader(ev);
  const disc = r.u64("disc");
  if (disc !== ORE_EVENT_DEPLOY) throw new DecodeError("BAD_TAG", `DeployEvent disc ${disc}`);
  const authority = r.address("authority");
  const amount = r.u64("amount");
  const mask64 = r.u64("mask");
  const roundId = r.u64("round_id");
  const signer = r.address("signer");
  const strategy = r.u64("strategy");
  const totalSquares64 = r.u64("total_squares");
  const ts = r.i64("ts");
  r.end("DeployEvent");
  if (mask64 > BigInt(ORE_SQUARE_MASK)) throw new DecodeError("BAD_FIELD", `DeployEvent mask ${mask64}`);
  const mask = Number(mask64);
  if (totalSquares64 !== BigInt(popcount32(mask))) {
    throw new DecodeError("BAD_FIELD", `total_squares ${totalSquares64} != popcount(mask) ${popcount32(mask)}`);
  }
  return {
    kind: "OreDeploy",
    authority,
    amount,
    mask,
    roundId,
    signer,
    strategy,
    totalSquares: Number(totalSquares64),
    ts,
  };
}

export function decodeOreResetEvent(ev: Uint8Array): OreResetEvent {
  if (ev.length !== ORE_RESET_EVENT_SIZE) {
    throw new DecodeError("BAD_LENGTH", `ResetEvent must be ${ORE_RESET_EVENT_SIZE} bytes, got ${ev.length}`);
  }
  const r = new ByteReader(ev);
  const disc = r.u64("disc");
  if (disc !== ORE_EVENT_RESET) throw new DecodeError("BAD_TAG", `ResetEvent disc ${disc}`);
  const roundId = r.u64("round_id");
  const startSlot = r.u64("start_slot");
  const endSlot = r.u64("end_slot");
  const ws = r.u64("winning_square");
  const topMiner = r.address("top_miner");
  const out: OreResetEvent = {
    kind: "OreReset",
    roundId,
    startSlot,
    endSlot,
    winningSquare: normalizeWinningSquare(ws),
    topMiner,
    totalMiners: r.u64("total_miners"),
    motherlode: r.u64("motherlode"),
    totalDeployed: r.u64("total_deployed"),
    totalVaulted: r.u64("total_vaulted"),
    totalWinnings: r.u64("total_winnings"),
    totalMinted: r.u64("total_minted"),
    ts: r.i64("ts"),
    rng: r.u64("rng"),
    deployedWinningSquare: r.u64("deployed_winning_square"),
  };
  r.end("ResetEvent");
  return out;
}

export function normalizeWinningSquare(ws: bigint): number | null {
  if (ws === U64_MAX) return null;
  if (ws > 24n) throw new DecodeError("BAD_FIELD", `winning_square ${ws}`);
  return Number(ws);
}

/**
 * Decodes the data of an ORE `Log` instruction (tag byte + event). Returns null when the
 * instruction is not a Log (any other ORE instruction), throws DecodeError when it is a Log
 * whose event is malformed.
 */
export function decodeOreLogInstruction(data: Uint8Array): OreEvent | null {
  if (data.length === 0 || data[0] !== ORE_LOG_IX_TAG) return null;
  const ev = data.subarray(1);
  if (ev.length < 8) throw new DecodeError("TRUNCATED", "ORE log event shorter than its discriminator");
  const disc = new DataView(ev.buffer, ev.byteOffset, 8).getBigUint64(0, true);
  if (disc === ORE_EVENT_DEPLOY) return decodeOreDeployEvent(ev);
  if (disc === ORE_EVENT_RESET) return decodeOreResetEvent(ev);
  return { kind: "OreOther", disc };
}
