/**
 * heads_down program events (INTERFACE.md, "Events"): logged with `sol_log_data`, first byte
 * is the event tag, then the fields in declaration order, little-endian, no padding.
 *
 * Field widths are not spelled out in INTERFACE.md; this module pins them (see
 * services/indexer/INTERFACE-NOTES.md, N1):
 *
 *   1 RigDug         rig[32] round_id u64 lamports u64 mask u32 ema_ev u64                 61 B
 *   2 RigSkipped     rig[32] round_id u64 error u32                                        45 B
 *   3 ShiftArmed     rig[32] shift_id u64                                                  41 B
 *   4 ShiftEnded     rig[32] shift_id u64 dark_rounds u64 rounds_dug u64 lamports u64 reason u8  66 B
 *   5 SeekerVerified rig[32] sgt_mint[32] member_number u64                               73 B
 *
 * Decoding is strict: exact length per tag, no trailing bytes, `mask` confined to the 25 ORE
 * squares. Unknown tags are reported as {@link UnknownHdEvent}, never guessed at.
 */
import { ByteReader, ByteWriter, DecodeError } from "./bytes.ts";
import { decodeBase58 } from "./base58.ts";

export const HD_EVENT_TAG = {
  RigDug: 1,
  RigSkipped: 2,
  ShiftArmed: 3,
  ShiftEnded: 4,
  SeekerVerified: 5,
} as const;

export type HdEventKind = keyof typeof HD_EVENT_TAG;

export const HD_EVENT_SIZE: Record<number, number> = {
  1: 61,
  2: 45,
  3: 41,
  4: 66,
  5: 73,
};

/** 25 ORE squares. */
export const ORE_SQUARE_MASK = 0x1ff_ffff;

export interface RigDug {
  kind: "RigDug";
  rig: string;
  roundId: bigint;
  /** SOL actually deployed into ORE squares by this dig (per_tile × squares), lamports. */
  lamports: bigint;
  mask: number;
  /** Pot-adjusted production cost the gate compared against (lamports per ORE). */
  emaEv: bigint;
}
export interface RigSkipped {
  kind: "RigSkipped";
  rig: string;
  roundId: bigint;
  /** heads_down error code (INTERFACE.md "Errors"). */
  error: number;
}
export interface ShiftArmed {
  kind: "ShiftArmed";
  rig: string;
  shiftId: bigint;
}
export interface ShiftEnded {
  kind: "ShiftEnded";
  rig: string;
  shiftId: bigint;
  darkRounds: bigint;
  roundsDug: bigint;
  lamports: bigint;
  /** ShiftLog.break_reason code. */
  reason: number;
}
export interface SeekerVerified {
  kind: "SeekerVerified";
  rig: string;
  sgtMint: string;
  memberNumber: bigint;
}
export interface UnknownHdEvent {
  kind: "Unknown";
  tag: number;
  length: number;
}

export type HdEvent = RigDug | RigSkipped | ShiftArmed | ShiftEnded | SeekerVerified;

export const HD_ERROR_NAMES: readonly string[] = [
  "InvalidInstruction",
  "CostGate",
  "InvalidExecutor",
  "InvalidOreAccount",
  "InvalidAccountTag",
  "Unauthorized",
  "InvalidHeartbeat",
  "StaleHeartbeat",
  "LeaseExpired",
  "AlreadyDugRound",
  "CapsExpired",
  "OutsideWindow",
  "BudgetExhausted",
  "RigNotArmed",
  "RigFrozen",
  "PlanExceedsCaps",
  "InvalidSgt",
  "SeatTaken",
  "Paused",
  "TimelockNotElapsed",
  "MathOverflow",
  "InvalidAttestation",
  "DuplicateRig",
  "StrategyMismatch",
];
export const HD_ERROR_COST_GATE = 1;

export const BREAK_REASON_NAMES: readonly string[] = [
  "completed",
  "pickup",
  "screen_on",
  "freeze",
  "lease_lapse",
  "budget",
  "manual",
];

export function hdErrorName(code: number): string {
  return HD_ERROR_NAMES[code] ?? `unknown(${code})`;
}

export function breakReasonName(code: number): string {
  return BREAK_REASON_NAMES[code] ?? `unknown(${code})`;
}

function checkMask(mask: number): number {
  if ((mask & ~ORE_SQUARE_MASK) !== 0) {
    throw new DecodeError("BAD_FIELD", `mask 0x${mask.toString(16)} has bits beyond the 25 ORE squares`);
  }
  return mask;
}

export function decodeHdEvent(data: Uint8Array): HdEvent | UnknownHdEvent {
  if (data.length === 0) throw new DecodeError("BAD_LENGTH", "empty event");
  const tag = data[0]!;
  const expected = HD_EVENT_SIZE[tag];
  if (expected === undefined) return { kind: "Unknown", tag, length: data.length };
  if (data.length !== expected) {
    throw new DecodeError("BAD_LENGTH", `event tag ${tag} must be ${expected} bytes, got ${data.length}`);
  }
  const r = new ByteReader(data);
  r.u8("tag");
  let ev: HdEvent;
  switch (tag) {
    case HD_EVENT_TAG.RigDug:
      ev = {
        kind: "RigDug",
        rig: r.address("rig"),
        roundId: r.u64("round_id"),
        lamports: r.u64("lamports"),
        mask: checkMask(r.u32("mask")),
        emaEv: r.u64("ema_ev"),
      };
      break;
    case HD_EVENT_TAG.RigSkipped:
      ev = { kind: "RigSkipped", rig: r.address("rig"), roundId: r.u64("round_id"), error: r.u32("error") };
      break;
    case HD_EVENT_TAG.ShiftArmed:
      ev = { kind: "ShiftArmed", rig: r.address("rig"), shiftId: r.u64("shift_id") };
      break;
    case HD_EVENT_TAG.ShiftEnded:
      ev = {
        kind: "ShiftEnded",
        rig: r.address("rig"),
        shiftId: r.u64("shift_id"),
        darkRounds: r.u64("dark_rounds"),
        roundsDug: r.u64("rounds_dug"),
        lamports: r.u64("lamports"),
        reason: r.u8("reason"),
      };
      // Semantic invariants (e.g. rounds_dug <= dark_rounds) are checked by the metrics
      // consistency report, not here: a decoder must not drop a well-formed on-chain event.
      break;
    case HD_EVENT_TAG.SeekerVerified:
      ev = {
        kind: "SeekerVerified",
        rig: r.address("rig"),
        sgtMint: r.address("sgt_mint"),
        memberNumber: r.u64("member_number"),
      };
      break;
    default:
      throw new DecodeError("BAD_TAG", `unhandled tag ${tag}`);
  }
  r.end(`event ${ev.kind}`);
  return ev;
}

function addr(a: string): Uint8Array {
  const b = decodeBase58(a, 32);
  if (b.length !== 32) throw new RangeError(`not a 32-byte address: ${a}`);
  return b;
}

/** Inverse of {@link decodeHdEvent}; used by the simulator and golden tests. */
export function encodeHdEvent(ev: HdEvent): Uint8Array {
  const tag = HD_EVENT_TAG[ev.kind];
  const w = new ByteWriter(HD_EVENT_SIZE[tag]!).u8(tag).bytes(addr(ev.rig));
  switch (ev.kind) {
    case "RigDug":
      w.u64(ev.roundId).u64(ev.lamports).u32(ev.mask).u64(ev.emaEv);
      break;
    case "RigSkipped":
      w.u64(ev.roundId).u32(ev.error);
      break;
    case "ShiftArmed":
      w.u64(ev.shiftId);
      break;
    case "ShiftEnded":
      w.u64(ev.shiftId).u64(ev.darkRounds).u64(ev.roundsDug).u64(ev.lamports).u8(ev.reason);
      break;
    case "SeekerVerified":
      w.bytes(addr(ev.sgtMint)).u64(ev.memberNumber);
      break;
  }
  return w.finish();
}
