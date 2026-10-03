/**
 * heads_down program events (INTERFACE.md v1.3: §7, §11.9, §12.8): logged with `sol_log_data` as
 * ONE slice, byte 0 = tag, then the fields in declaration order, little-endian, no padding.
 *
 *    1 RigDug              rig[32] round_id u64 lamports u64 mask u32 ema_ev u64                       61 B
 *    2 RigSkipped          rig[32] round_id u64 error u32                                              45 B
 *    3 ShiftArmed          rig[32] shift_id u64                                                        41 B
 *    4 ShiftEnded          rig[32] shift_id u64 dark_rounds u64 rounds_dug u64 lamports u64 reason u8  66 B
 *    5 SeekerVerified      rig[32] sgt_mint[32] member_number u64                                     73 B
 *    6 RigRegistered       rig[32] authority[32] tier u8 attestation_level u8                          67 B
 *    7 RigClosed           rig[32]                                                                     33 B
 *    8 HeartbeatsRecorded  rig[32] round_id u64 dark_rounds_added u64                                  49 B
 *    9 ShiftBroken         rig[32] shift_id u64 reason u8                                              42 B
 *   10 ShiftEndedV2        tag 4's fields, then start_round u64 end_round u64 mode u8                  83 B
 *   11..=23 (v1.2, SKR: Stack, Focus Bond, Gift a Rig, Bury auction) and 24..=27 (v1.3: governance
 *   rotation, ShiftLogClosed): see {@link HD_EVENT_LAYOUTS}. They decode into {@link HdExtEvent},
 *   a name plus its fields, because the haul and the v1.1 metrics read none of them.
 *
 * {@link HD_EVENT_LAYOUTS} is the single source of truth: decoding and encoding are driven by it,
 * and test/events.test.ts checks it field by field against programs/heads-down/vectors/events.json
 * (the program's machine-checked golden file), so a drift in either fails the build.
 *
 * Decoding is strict: exact length per tag, no trailing bytes, `mask` confined to the 25 ORE
 * squares. Unknown tags are reported as {@link UnknownHdEvent}, never guessed at. `end_shift`
 * emits ShiftEnded (4) then ShiftEndedV2 (10); the transaction extractor keeps only tag 10 when
 * both are present (codec/tx.ts), so a shift is never counted twice.
 */
import { ByteReader, ByteWriter, DecodeError } from "./bytes.ts";
import { decodeBase58 } from "./base58.ts";

export const HD_EVENT_TAG = {
  RigDug: 1,
  RigSkipped: 2,
  ShiftArmed: 3,
  ShiftEnded: 4,
  SeekerVerified: 5,
  RigRegistered: 6,
  RigClosed: 7,
  HeartbeatsRecorded: 8,
  ShiftBroken: 9,
  ShiftEndedV2: 10,
  // v1.2 (SKR), INTERFACE §11.9
  StackOpened: 11,
  StackJoined: 12,
  StackCheckin: 13,
  StackSettled: 14,
  StackClaimed: 15,
  FocusBondLocked: 16,
  FocusBondReleased: 17,
  FocusBondForfeited: 18,
  GiftCreated: 19,
  GiftClaimed: 20,
  GiftRefunded: 21,
  BuryLotAdded: 22,
  BuryAuctionSold: 23,
  // v1.3, INTERFACE §12.8
  GovernanceProposed: 24,
  GovernanceAccepted: 25,
  GovernanceCancelled: 26,
  ShiftLogClosed: 27,
} as const;

export type HdEventKind = keyof typeof HD_EVENT_TAG;
/** First tag decoded into {@link HdExtEvent} instead of its own interface. */
export const FIRST_EXT_EVENT_TAG = 11;

export type EventFieldType = "u8" | "u32" | "u64" | "i64" | "pubkey";
export interface EventField {
  /** snake_case, as in INTERFACE.md and events.json. */
  name: string;
  type: EventFieldType;
}

const FIELD_SIZE: Record<EventFieldType, number> = { u8: 1, u32: 4, u64: 8, i64: 8, pubkey: 32 };

const SHIFT_ENDED_FIELDS: readonly EventField[] = [
  { name: "rig", type: "pubkey" },
  { name: "shift_id", type: "u64" },
  { name: "dark_rounds", type: "u64" },
  { name: "rounds_dug", type: "u64" },
  { name: "lamports", type: "u64" },
  { name: "reason", type: "u8" },
];

/** Field layout after the tag byte, per event (INTERFACE.md §7, §11.9, §12.8). */
export const HD_EVENT_LAYOUTS: Record<HdEventKind, readonly EventField[]> = {
  RigDug: [
    { name: "rig", type: "pubkey" },
    { name: "round_id", type: "u64" },
    { name: "lamports", type: "u64" },
    { name: "mask", type: "u32" },
    { name: "ema_ev", type: "u64" },
  ],
  RigSkipped: [
    { name: "rig", type: "pubkey" },
    { name: "round_id", type: "u64" },
    { name: "error", type: "u32" },
  ],
  ShiftArmed: [
    { name: "rig", type: "pubkey" },
    { name: "shift_id", type: "u64" },
  ],
  ShiftEnded: SHIFT_ENDED_FIELDS,
  SeekerVerified: [
    { name: "rig", type: "pubkey" },
    { name: "sgt_mint", type: "pubkey" },
    { name: "member_number", type: "u64" },
  ],
  RigRegistered: [
    { name: "rig", type: "pubkey" },
    { name: "authority", type: "pubkey" },
    { name: "tier", type: "u8" },
    { name: "attestation_level", type: "u8" },
  ],
  RigClosed: [{ name: "rig", type: "pubkey" }],
  HeartbeatsRecorded: [
    { name: "rig", type: "pubkey" },
    { name: "round_id", type: "u64" },
    { name: "dark_rounds_added", type: "u64" },
  ],
  ShiftBroken: [
    { name: "rig", type: "pubkey" },
    { name: "shift_id", type: "u64" },
    { name: "reason", type: "u8" },
  ],
  ShiftEndedV2: [
    ...SHIFT_ENDED_FIELDS,
    { name: "start_round", type: "u64" },
    { name: "end_round", type: "u64" },
    { name: "mode", type: "u8" },
  ],
  StackOpened: [
    { name: "table", type: "pubkey" },
    { name: "host", type: "pubkey" },
    { name: "table_id", type: "u64" },
    { name: "bond", type: "u64" },
    { name: "start_round", type: "u64" },
    { name: "end_round", type: "u64" },
    { name: "grace_gaps", type: "u32" },
    { name: "flags", type: "u8" },
    { name: "max_seats", type: "u8" },
  ],
  StackJoined: [
    { name: "table", type: "pubkey" },
    { name: "rig", type: "pubkey" },
    { name: "authority", type: "pubkey" },
    { name: "sgt_mint", type: "pubkey" },
    { name: "bond", type: "u64" },
    { name: "seat_index", type: "u8" },
  ],
  StackCheckin: [
    { name: "table", type: "pubkey" },
    { name: "rig", type: "pubkey" },
    { name: "round_id", type: "u64" },
    { name: "checked_rounds", type: "u64" },
    { name: "result", type: "u32" },
  ],
  StackSettled: [
    { name: "table", type: "pubkey" },
    { name: "total_bonds", type: "u64" },
    { name: "finisher_bonds", type: "u64" },
    { name: "payouts_total", type: "u64" },
    { name: "bury_amount", type: "u64" },
    { name: "seats", type: "u8" },
    { name: "finishers", type: "u8" },
  ],
  StackClaimed: [
    { name: "table", type: "pubkey" },
    { name: "rig", type: "pubkey" },
    { name: "authority", type: "pubkey" },
    { name: "amount", type: "u64" },
    { name: "kind", type: "u8" },
  ],
  FocusBondLocked: [
    { name: "bond", type: "pubkey" },
    { name: "rig", type: "pubkey" },
    { name: "authority", type: "pubkey" },
    { name: "shift_id", type: "u64" },
    { name: "amount", type: "u64" },
  ],
  FocusBondReleased: [
    { name: "bond", type: "pubkey" },
    { name: "rig", type: "pubkey" },
    { name: "shift_id", type: "u64" },
    { name: "amount", type: "u64" },
  ],
  FocusBondForfeited: [
    { name: "bond", type: "pubkey" },
    { name: "rig", type: "pubkey" },
    { name: "shift_id", type: "u64" },
    { name: "amount", type: "u64" },
    { name: "reason", type: "u8" },
  ],
  GiftCreated: [
    { name: "gift", type: "pubkey" },
    { name: "sender", type: "pubkey" },
    { name: "recipient", type: "pubkey" },
    { name: "lamports", type: "u64" },
    { name: "expiry_ts", type: "i64" },
    { name: "recipient_kind", type: "u8" },
  ],
  GiftClaimed: [
    { name: "gift", type: "pubkey" },
    { name: "claimer", type: "pubkey" },
    { name: "lamports", type: "u64" },
    { name: "recipient_kind", type: "u8" },
  ],
  GiftRefunded: [
    { name: "gift", type: "pubkey" },
    { name: "sender", type: "pubkey" },
    { name: "lamports", type: "u64" },
  ],
  BuryLotAdded: [
    { name: "source", type: "pubkey" },
    { name: "amount", type: "u64" },
    { name: "lot_skr", type: "u64" },
    { name: "start_price", type: "u64" },
    { name: "start_slot", type: "u64" },
    { name: "source_kind", type: "u8" },
  ],
  BuryAuctionSold: [
    { name: "buyer", type: "pubkey" },
    { name: "skr_amount", type: "u64" },
    { name: "price", type: "u64" },
    { name: "ore_paid", type: "u64" },
    { name: "ore_burned", type: "u64" },
    { name: "ore_shared", type: "u64" },
    { name: "lot_remaining", type: "u64" },
  ],
  GovernanceProposed: [
    { name: "governance", type: "pubkey" },
    { name: "pending_governance", type: "pubkey" },
    { name: "eta_slot", type: "u64" },
    { name: "eta_ts", type: "i64" },
  ],
  GovernanceAccepted: [
    { name: "governance", type: "pubkey" },
    { name: "previous_governance", type: "pubkey" },
  ],
  GovernanceCancelled: [
    { name: "governance", type: "pubkey" },
    { name: "cancelled_governance", type: "pubkey" },
  ],
  ShiftLogClosed: [
    { name: "shift_log", type: "pubkey" },
    { name: "rig", type: "pubkey" },
    { name: "shift_id", type: "u64" },
    { name: "lamports", type: "u64" },
  ],
};

const KIND_BY_TAG = new Map<number, HdEventKind>(Object.entries(HD_EVENT_TAG).map(([k, t]) => [t, k as HdEventKind]));

/** Total length (tag byte included) per tag. */
export const HD_EVENT_SIZE: Record<number, number> = Object.fromEntries(
  Object.entries(HD_EVENT_LAYOUTS).map(([k, fields]) => [HD_EVENT_TAG[k as HdEventKind], 1 + fields.reduce((n, f) => n + FIELD_SIZE[f.type], 0)]),
);

/** Offsets of every field (tag at 0), for the drift check against events.json. */
export function eventLayoutWithOffsets(kind: HdEventKind): { name: string; type: EventFieldType | "u8"; offset: number; size: number }[] {
  const out = [{ name: "tag", type: "u8" as const, offset: 0, size: 1 }];
  let o = 1;
  for (const f of HD_EVENT_LAYOUTS[kind]) {
    out.push({ name: f.name, type: f.type as "u8", offset: o, size: FIELD_SIZE[f.type] });
    o += FIELD_SIZE[f.type];
  }
  return out;
}

/** 25 ORE squares. */
export const ORE_SQUARE_MASK = 0x1ff_ffff;

export interface RigDug {
  kind: "RigDug";
  rig: string;
  /** Live Board.round_id. */
  roundId: bigint;
  /** SOL placed on ORE squares by this dig (per_tile × squares), lamports. Excludes the Automation fee (v1.1). */
  lamports: bigint;
  /** The mask passed to ORE; squares the Miner already held were excluded, so requested = credited. */
  mask: number;
  /** Pot-adjusted production cost the gate compared against (lamports per ORE, saturating). */
  emaEv: bigint;
}
export interface RigSkipped {
  kind: "RigSkipped";
  rig: string;
  roundId: bigint;
  /** Precise code: heads_down 0..=31, p256-introspect 0x2560_00xx, sgt-verify 0x5347_00xx, or a builtin u32::MAX - k. */
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
  /** spent_shift: SOL on squares PLUS Automation fees. */
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
export interface RigRegistered {
  kind: "RigRegistered";
  rig: string;
  authority: string;
  /** 0 guest, 1 seeker (always 0 at registration; verify_seeker upgrades it). */
  tier: number;
  /** 0 none, 1 TEE, 2 StrongBox. */
  attestationLevel: number;
}
export interface RigClosed {
  kind: "RigClosed";
  rig: string;
}
export interface HeartbeatsRecorded {
  kind: "HeartbeatsRecorded";
  rig: string;
  /** Live Board.round_id when the heartbeat was recorded (NOT the round the phone signed). */
  roundId: bigint;
  darkRoundsAdded: bigint;
}
export interface ShiftBroken {
  kind: "ShiftBroken";
  rig: string;
  shiftId: bigint;
  reason: number;
}
export interface ShiftEndedV2 {
  kind: "ShiftEndedV2";
  rig: string;
  shiftId: bigint;
  darkRounds: bigint;
  roundsDug: bigint;
  lamports: bigint;
  reason: number;
  startRound: bigint;
  /** Board.round_id at end_shift (inclusive). */
  endRound: bigint;
  /** 0 night, 1 day, 2 focus-only. */
  mode: number;
}
export interface UnknownHdEvent {
  kind: "Unknown";
  tag: number;
  length: number;
}

export type HdEvent =
  | RigDug
  | RigSkipped
  | ShiftArmed
  | ShiftEnded
  | SeekerVerified
  | RigRegistered
  | RigClosed
  | HeartbeatsRecorded
  | ShiftBroken
  | ShiftEndedV2;

/** The v1.2 (SKR) and v1.3 event names: every tag from {@link FIRST_EXT_EVENT_TAG} up. */
export type HdExtEventKind = Exclude<HdEventKind, HdEvent["kind"]>;

/**
 * A v1.2 / v1.3 event, decoded by its layout. `fields` keeps the contract's snake_case names:
 * u8 / u32 are numbers, u64 / i64 bigints, addresses base58.
 */
export interface HdExtEvent {
  kind: "Ext";
  name: HdExtEventKind;
  tag: number;
  fields: Record<string, number | bigint | string>;
}

/** `StackClaimed.kind`. */
export const STACK_CLAIM_KIND_NAMES = ["payout", "refund"] as const;
/** `BuryLotAdded.source_kind` (index = code; 0 is unused). */
export const BURY_LOT_SOURCE_NAMES = ["unknown", "stack", "focus_bond"] as const;
/** `FocusBondForfeited.reason` when the bonded shift can never be sealed. */
export const BOND_ABANDONED = 255;

// ------------------------------------------------------------------ error names

/** heads_down's own codes, INTERFACE.md §8, §11.10 (32..=48) and §12.9 (49, 50); index = code. */
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
  "InvalidRigState",
  "RoundNotActive",
  "MinerNotCheckpointed",
  "MotherlodeCondition",
  "InsufficientAutomationBalance",
  "OreNoOp",
  "FocusOnly",
  "ExecutorUnderfunded",
  "InvalidTokenAccount",
  "AmountOutOfRange",
  "InvalidStackParams",
  "StackJoinClosed",
  "StackIneligible",
  "StackNotEnded",
  "InvalidStackState",
  "StackSeatMismatch",
  "StackShiftMismatch",
  "StackLeaseTooLong",
  "StackSeatBroken",
  "BondNotResolvable",
  "GiftNotClaimable",
  "GiftExpiry",
  "AuctionEmpty",
  "PriceAboveMax",
  "BuryMismatch",
  "ShiftLogNotExpired",
  "ShiftLogInUse",
];
export const HD_ERROR = Object.fromEntries(HD_ERROR_NAMES.map((n, i) => [n, i])) as Record<string, number>;
export const HD_ERROR_COST_GATE = 1;

/** `p256-introspect` (crates/p256-introspect/src/error.rs): 0x2560_0000 | n. */
export const P256_INTROSPECT_BASE = 0x2560_0000;
export const P256_INTROSPECT_NAMES: Readonly<Record<number, string>> = {
  1: "InvalidInstructionsSysvar",
  2: "MalformedInstructionsSysvar",
  3: "InstructionIndexOutOfBounds",
  4: "NotSecp256r1Instruction",
  5: "InvalidSignatureCount",
  6: "TruncatedOffsets",
  7: "ForeignInstructionIndex",
  8: "OffsetOutOfBounds",
  9: "SignatureIndexOutOfBounds",
  10: "HighS",
  11: "ScalarOutOfRange",
  12: "InvalidPublicKeyEncoding",
  13: "PublicKeyMismatch",
  14: "MessageMismatch",
};

/** `sgt-verify` (crates/sgt-verify/src/error.rs): 0x5347_0000 | n. */
export const SGT_VERIFY_BASE = 0x5347_0000;
export const SGT_VERIFY_NAMES: Readonly<Record<number, string>> = {
  1: "TokenAccountNotToken2022",
  2: "MintNotToken2022",
  3: "DuplicateAccount",
  4: "AccountBorrowFailed",
  10: "MintInvalidLength",
  11: "MintPaddingNotZero",
  12: "MintAccountTypeMismatch",
  13: "MintNotInitialized",
  14: "MintInvalidOption",
  15: "MintMissingExtensions",
  20: "MintAuthorityMismatch",
  21: "FreezeAuthorityMismatch",
  22: "DecimalsNotZero",
  23: "SupplyNotOne",
  30: "MalformedTlv",
  31: "DuplicateExtension",
  32: "TooManyExtensions",
  33: "InvalidExtensionLength",
  34: "MissingGroupMember",
  35: "GroupMismatch",
  36: "GroupMemberMintMismatch",
  37: "InvalidMemberNumber",
  38: "MissingGroupMemberPointer",
  39: "GroupMemberPointerMismatch",
  40: "MissingPermanentDelegate",
  41: "PermanentDelegateMismatch",
  42: "MissingMetadataPointer",
  43: "MetadataPointerMismatch",
  44: "MissingMintCloseAuthority",
  45: "MintCloseAuthorityMismatch",
  50: "TokenAccountInvalidLength",
  51: "TokenAccountTypeMismatch",
  52: "TokenAccountInvalidState",
  53: "TokenAccountNotInitialized",
  54: "TokenAccountInvalidOption",
  60: "TokenAccountMintMismatch",
  61: "TokenAccountOwnerMismatch",
  62: "AmountNotOne",
  63: "NativeTokenAccount",
};

/** A builtin ProgramError inside a skip maps to u32::MAX - k (program/src/error.rs `skip_code`). */
export const BUILTIN_SKIP_NAMES: readonly string[] = [
  "OtherBuiltin",
  "InvalidArgument",
  "InvalidInstructionData",
  "InvalidAccountData",
  "AccountBorrowFailed",
  "MissingRequiredSignature",
  "ArithmeticOverflow",
];
const U32_MAX = 0xffff_ffff;

export type ErrorRange = "heads_down" | "p256-introspect" | "sgt-verify" | "builtin" | "unknown";

export function hdErrorRange(code: number): ErrorRange {
  if (!Number.isInteger(code) || code < 0 || code > U32_MAX) return "unknown";
  if (code < HD_ERROR_NAMES.length) return "heads_down";
  if ((code & 0xffff_0000) >>> 0 === P256_INTROSPECT_BASE) return "p256-introspect";
  if ((code & 0xffff_0000) >>> 0 === SGT_VERIFY_BASE) return "sgt-verify";
  if (U32_MAX - code < BUILTIN_SKIP_NAMES.length) return "builtin";
  return "unknown";
}

const hex8 = (code: number) => `0x${code.toString(16).padStart(8, "0")}`;

/**
 * Name of any error code a `RigSkipped` (or a failed transaction) can carry. heads_down codes are
 * bare names ("StaleHeartbeat"); shared-crate codes carry their range and hex, exactly as
 * events.json names them ("p256-introspect MessageMismatch (0x2560000e)").
 */
export function hdErrorName(code: number): string {
  const range = hdErrorRange(code);
  switch (range) {
    case "heads_down":
      return HD_ERROR_NAMES[code]!;
    case "p256-introspect":
      return `p256-introspect ${P256_INTROSPECT_NAMES[code & 0xffff] ?? `unknown(${code & 0xffff})`} (${hex8(code)})`;
    case "sgt-verify":
      return `sgt-verify ${SGT_VERIFY_NAMES[code & 0xffff] ?? `unknown(${code & 0xffff})`} (${hex8(code)})`;
    case "builtin": {
      const k = U32_MAX - code;
      return `builtin ${BUILTIN_SKIP_NAMES[k]} (u32::MAX - ${k})`;
    }
    default:
      return `unknown(${code})`;
  }
}

/**
 * Plain-language meaning of a skip, for dashboards and captions. It describes what happened
 * on-chain and never implies an outcome for the user's money beyond that.
 */
const SKIP_LABELS: Readonly<Record<number, string>> = {
  0: "malformed request (rejected)",
  1: "price gate closed: mining cost more than the plan allows",
  2: "Automation revoked or not pointed at Heads Down",
  3: "ORE miner account missing",
  6: "heartbeat for a future round (rejected)",
  7: "replay rejected: heartbeat counter not newer",
  8: "phone went quiet: no heartbeat lease covers this round",
  9: "already dug this round",
  10: "wallet caps expired",
  11: "outside the shift window",
  12: "budget used up",
  13: "no active shift (idle, broken, or cooling without a fresh heartbeat)",
  14: "rig frozen",
  20: "arithmetic overflow (rejected)",
  23: "Automation fee or strategy does not match Heads Down",
  25: "ORE round not accepting deploys",
  26: "ORE miner not checkpointed yet",
  27: "Automation's Motherlode condition not met",
  28: "Automation balance too low",
  29: "ORE placed nothing",
  30: "focus-only shift: never digs",
  31: "Executor float too low",
  36: "attestation not live at an attested-only table",
  40: "seat is bound to another shift of this rig",
  41: "plan allows leases longer than one round",
  42: "seat broken: a break or freeze was recorded in its shift",
};
const P256_LABELS: Readonly<Record<number, string>> = {
  10: "high-S signature rejected",
  13: "signed by a different key",
  14: "signed message did not match (wrong shift or round)",
};

export function skipLabel(code: number): string {
  const range = hdErrorRange(code);
  if (range === "heads_down") return SKIP_LABELS[code] ?? `${HD_ERROR_NAMES[code]} (rejected)`;
  if (range === "p256-introspect") return P256_LABELS[code & 0xffff] ?? "heartbeat signature check failed";
  if (range === "sgt-verify") return "Seeker Genesis Token check failed";
  if (range === "builtin") return "runtime error inside the skip path";
  return "unknown code";
}

/** Skip codes that are decided AFTER a fresh heartbeat verified (steps 4-9 of INTERFACE §6.2): the heartbeat's lease was granted. */
export const SKIPS_AFTER_HEARTBEAT: ReadonlySet<number> = new Set([8, 9, 10, 11, 30, 1, 12, 28, 25, 26, 27, 31, 29]);

// ------------------------------------------------------------------ break reasons

/** ShiftLog.break_reason and the BREAK / FREEZE `reason` byte (INTERFACE.md v1.1 §3.5). */
export const BREAK_REASON_NAMES: readonly string[] = [
  "completed",
  "pickup",
  "screen_on",
  "freeze",
  "lease_lapse",
  "budget",
  "manual",
  "unplugged",
  "unlocked",
];

export function breakReasonName(code: number): string {
  return BREAK_REASON_NAMES[code] ?? `unknown(${code})`;
}

export const SHIFT_MODE_NAMES = ["night", "day", "focus_only"] as const;
export type ShiftModeName = (typeof SHIFT_MODE_NAMES)[number];

// ------------------------------------------------------------------ decode / encode

function checkMask(mask: number): number {
  if ((mask & ~ORE_SQUARE_MASK) !== 0) {
    throw new DecodeError("BAD_FIELD", `mask 0x${mask.toString(16)} has bits beyond the 25 ORE squares`);
  }
  return mask;
}

const camel = (s: string) => s.replace(/_([a-z])/g, (_m, c: string) => c.toUpperCase());

function readField(r: ByteReader, f: EventField): number | bigint | string {
  switch (f.type) {
    case "u8":
      return r.u8(f.name);
    case "u32":
      return r.u32(f.name);
    case "u64":
      return r.u64(f.name);
    case "i64":
      return r.i64(f.name);
    case "pubkey":
      return r.address(f.name);
  }
}

export function decodeHdEvent(data: Uint8Array): HdEvent | HdExtEvent | UnknownHdEvent {
  if (data.length === 0) throw new DecodeError("BAD_LENGTH", "empty event");
  const tag = data[0]!;
  const kind = KIND_BY_TAG.get(tag);
  if (kind === undefined) return { kind: "Unknown", tag, length: data.length };
  const expected = HD_EVENT_SIZE[tag]!;
  if (data.length !== expected) {
    throw new DecodeError("BAD_LENGTH", `event tag ${tag} must be ${expected} bytes, got ${data.length}`);
  }
  const r = new ByteReader(data);
  r.u8("tag");
  if (tag >= FIRST_EXT_EVENT_TAG) {
    const fields: Record<string, number | bigint | string> = {};
    for (const f of HD_EVENT_LAYOUTS[kind]) fields[f.name] = readField(r, f);
    r.end(`event ${kind}`);
    return { kind: "Ext", name: kind as HdExtEventKind, tag, fields };
  }
  const ev: Record<string, unknown> = { kind };
  for (const f of HD_EVENT_LAYOUTS[kind]) ev[camel(f.name)] = readField(r, f);
  r.end(`event ${kind}`);
  if (kind === "RigDug") checkMask(ev.mask as number);
  // Semantic invariants (rounds_dug <= dark_rounds, reason ranges) are checked by the metrics
  // consistency report, not here: a decoder must not drop a well-formed on-chain event.
  return ev as unknown as HdEvent;
}

function addr(a: string): Uint8Array {
  const b = decodeBase58(a, 32);
  if (b.length !== 32) throw new RangeError(`not a 32-byte address: ${a}`);
  return b;
}

/** Inverse of {@link decodeHdEvent}; used by the simulator and golden tests. */
export function encodeHdEvent(ev: HdEvent | HdExtEvent): Uint8Array {
  const kind: HdEventKind = ev.kind === "Ext" ? ev.name : ev.kind;
  const tag = HD_EVENT_TAG[kind];
  const w = new ByteWriter(HD_EVENT_SIZE[tag]!).u8(tag);
  const rec = ev as unknown as Record<string, unknown>;
  for (const f of HD_EVENT_LAYOUTS[kind]) {
    const v = ev.kind === "Ext" ? ev.fields[f.name] : rec[camel(f.name)];
    switch (f.type) {
      case "u8":
        w.u8(v as number);
        break;
      case "u32":
        w.u32(v as number);
        break;
      case "u64":
        w.u64(v as bigint);
        break;
      case "i64":
        w.i64(v as bigint);
        break;
      case "pubkey":
        w.bytes(addr(v as string));
        break;
    }
  }
  return w.finish();
}
