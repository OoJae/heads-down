/**
 * heads_down account decoders (INTERFACE.md "Account header", "Config", "Rig", "SeekerSeat",
 * "ShiftLog"). Each decoder checks exact length, tag and version before reading a field,
 * mirroring the rule every on-chain handler follows ("MUST check tag + owner before reading").
 * Owner and address re-derivation are checked by {@link verifyAccount}.
 */
import { ByteReader, ByteWriter, DecodeError, toHex } from "./bytes.ts";
import { decodeBase58 } from "./base58.ts";
import { rigAddress, seekerSeatAddress, shiftLogAddress, canonicalProgramAddress, seed } from "./pda.ts";

export const ACCOUNT_TAG = { Config: 1, Rig: 2, SeekerSeat: 3, ShiftLog: 4 } as const;
export const ACCOUNT_SIZE = { Config: 256, Rig: 384, SeekerSeat: 128, ShiftLog: 128 } as const;
export const ACCOUNT_VERSION = 1;
const ZERO_ADDRESS = "11111111111111111111111111111111";

export const RIG_STATE_NAMES = ["Idle", "Armed", "Down", "Cooling", "Broken", "Frozen"] as const;
export const SHIFT_MODE_NAMES = ["night", "day", "focus_only"] as const;

interface Header {
  tag: number;
  version: number;
  bump: number;
}

function header(r: ByteReader, kind: keyof typeof ACCOUNT_TAG, len: number): Header {
  if (len !== ACCOUNT_SIZE[kind]) {
    throw new DecodeError("BAD_LENGTH", `${kind} must be ${ACCOUNT_SIZE[kind]} bytes, got ${len}`);
  }
  const tag = r.u8("tag");
  if (tag !== ACCOUNT_TAG[kind]) throw new DecodeError("BAD_TAG", `${kind} tag ${tag}`);
  const version = r.u8("version");
  if (version !== ACCOUNT_VERSION) throw new DecodeError("BAD_TAG", `${kind} version ${version}`);
  const bump = r.u8("bump");
  r.skip(5, "reserved");
  return { tag, version, bump };
}

function optAddress(a: string): string | null {
  return a === ZERO_ADDRESS ? null : a;
}

export interface RigAccount {
  kind: "Rig";
  bump: number;
  authority: string;
  p256Pubkey: string; // hex, 33-byte SEC1 compressed
  attestationLevel: number;
  tier: 0 | 1;
  state: number;
  sgtMint: string | null;
  attestationExpirySlot: bigint;
  capWeek: bigint;
  capShift: bigint;
  capRound: bigint;
  capMaxCost: bigint;
  capsExpiryTs: bigint;
  planMaxEvCost: bigint;
  planDigLamports: bigint;
  planSplitTiles: number;
  planSoloTiles: number;
  planLeaseRounds: number;
  planFlags: number;
  planWindowStartTs: bigint;
  planWindowEndTs: bigint;
  shiftId: bigint;
  hbCounter: bigint;
  leaseFromRound: bigint;
  leaseToRound: bigint;
  gapCount: number;
  spentShift: bigint;
  spentWeek: bigint;
  weekStartTs: bigint;
  lastDugRound: bigint;
  shiftStartRound: bigint;
  shiftDarkRounds: bigint;
  shiftRoundsDug: bigint;
  lifetimeDarkRounds: bigint;
  lifetimeRoundsDug: bigint;
  lifetimeLamportsDeployed: bigint;
  streak: number;
  freezesLeft: number;
  lastShiftDay: bigint;
  /** v1.1 (§3.3, the former reserved bytes): 1 from arm_shift to end_shift. Optional for v1-shaped fixtures (0). */
  shiftOpen?: number;
  /** BREAK / shift-interrupting FREEZE reason, written into the ShiftLog by end_shift. */
  breakReason?: number;
  oreAutomationBump?: number;
  oreMinerBump?: number;
  /** unix time of arm_shift (becomes ShiftLog.start_ts). */
  shiftStartTs?: bigint;
}

export function decodeRig(data: Uint8Array): RigAccount {
  const r = new ByteReader(data);
  const h = header(r, "Rig", data.length);
  const authority = r.address("authority");
  const p256 = r.fixed(33, "p256_pubkey");
  if (p256[0] !== 0x02 && p256[0] !== 0x03) {
    throw new DecodeError("BAD_FIELD", `p256_pubkey prefix 0x${p256[0]!.toString(16)} is not SEC1 compressed`);
  }
  const attestationLevel = r.u8("attestation_level");
  const tier = r.u8("tier");
  if (tier !== 0 && tier !== 1) throw new DecodeError("BAD_FIELD", `tier ${tier}`);
  const state = r.u8("state");
  if (state >= RIG_STATE_NAMES.length) throw new DecodeError("BAD_FIELD", `state ${state}`);
  r.skip(4);
  const rig: RigAccount = {
    kind: "Rig",
    bump: h.bump,
    authority,
    p256Pubkey: toHex(p256),
    attestationLevel,
    tier,
    state,
    sgtMint: optAddress(r.address("sgt_mint")),
    attestationExpirySlot: r.u64(),
    capWeek: r.u64(),
    capShift: r.u64(),
    capRound: r.u64(),
    capMaxCost: r.u64(),
    capsExpiryTs: r.i64(),
    planMaxEvCost: r.u64(),
    planDigLamports: r.u64(),
    planSplitTiles: r.u8(),
    planSoloTiles: r.u8(),
    planLeaseRounds: r.u8(),
    planFlags: r.u8(),
    planWindowStartTs: (r.skip(4), r.i64()),
    planWindowEndTs: r.i64(),
    shiftId: r.u64(),
    hbCounter: r.u64(),
    leaseFromRound: r.u64(),
    leaseToRound: r.u64(),
    gapCount: r.u32(),
    spentShift: (r.skip(4), r.u64()),
    spentWeek: r.u64(),
    weekStartTs: r.i64(),
    lastDugRound: r.u64(),
    shiftStartRound: r.u64(),
    shiftDarkRounds: r.u64(),
    shiftRoundsDug: r.u64(),
    lifetimeDarkRounds: r.u64(),
    lifetimeRoundsDug: r.u64(),
    lifetimeLamportsDeployed: r.u64(),
    streak: r.u32(),
    freezesLeft: r.u8(),
    lastShiftDay: (r.skip(3), r.i64()),
  };
  if (r.position !== 336) throw new DecodeError("BAD_LENGTH", `Rig layout drift: at ${r.position}, expected 336`);
  // v1.1 §3.3: the v1 reserved[48] at 336 now holds these (still zero on a v1-era account).
  rig.shiftOpen = r.u8("shift_open");
  rig.breakReason = r.u8("break_reason");
  rig.oreAutomationBump = r.u8("ore_automation_bump");
  rig.oreMinerBump = r.u8("ore_miner_bump");
  r.skip(4);
  rig.shiftStartTs = r.i64("shift_start_ts");
  r.skip(32, "reserved");
  r.end("Rig");
  return rig;
}

export interface ShiftLogAccount {
  kind: "ShiftLog";
  bump: number;
  rig: string;
  shiftId: bigint;
  startRound: bigint;
  endRound: bigint;
  darkRounds: bigint;
  roundsDug: bigint;
  lamportsDeployed: bigint;
  breakReason: number;
  mode: number;
  startTs: bigint;
  endTs: bigint;
}

export function decodeShiftLog(data: Uint8Array): ShiftLogAccount {
  const r = new ByteReader(data);
  const h = header(r, "ShiftLog", data.length);
  const log: ShiftLogAccount = {
    kind: "ShiftLog",
    bump: h.bump,
    rig: r.address("rig"),
    shiftId: r.u64(),
    startRound: r.u64(),
    endRound: r.u64(),
    darkRounds: r.u64(),
    roundsDug: r.u64(),
    lamportsDeployed: r.u64(),
    breakReason: r.u8(),
    mode: r.u8(),
    startTs: (r.skip(6), r.i64()),
    endTs: r.i64(),
  };
  r.skip(16, "reserved");
  r.end("ShiftLog");
  return log;
}

export interface SeekerSeatAccount {
  kind: "SeekerSeat";
  bump: number;
  sgtMint: string;
  rig: string;
  authority: string;
  memberNumber: bigint;
  verifiedSlot: bigint;
}

export function decodeSeekerSeat(data: Uint8Array): SeekerSeatAccount {
  const r = new ByteReader(data);
  const h = header(r, "SeekerSeat", data.length);
  const seat: SeekerSeatAccount = {
    kind: "SeekerSeat",
    bump: h.bump,
    sgtMint: r.address("sgt_mint"),
    rig: r.address("rig"),
    authority: r.address("authority"),
    memberNumber: r.u64(),
    verifiedSlot: r.u64(),
  };
  r.skip(8, "reserved");
  r.end("SeekerSeat");
  return seat;
}

export interface ConfigAccount {
  kind: "Config";
  bump: number;
  governance: string;
  registrar: string;
  crankFee: bigint;
  executorFee: bigint;
  buryBps: number;
  paused: boolean;
  executorBump: number;
}

export function decodeConfig(data: Uint8Array): ConfigAccount {
  const r = new ByteReader(data);
  const h = header(r, "Config", data.length);
  return {
    kind: "Config",
    bump: h.bump,
    governance: r.address("governance"),
    registrar: r.address("registrar"),
    crankFee: r.u64(),
    executorFee: r.u64(),
    buryBps: r.u16(),
    paused: r.u8("paused") !== 0,
    executorBump: r.u8("executor_bump"),
    // pending_* and ore_layout_hash are not needed by the dashboard.
  };
}

export type HdAccount = RigAccount | ShiftLogAccount | SeekerSeatAccount | ConfigAccount;

/** Decodes by tag. */
export function decodeHdAccount(data: Uint8Array): HdAccount {
  if (data.length === 0) throw new DecodeError("BAD_LENGTH", "empty account");
  switch (data[0]) {
    case ACCOUNT_TAG.Rig:
      return decodeRig(data);
    case ACCOUNT_TAG.ShiftLog:
      return decodeShiftLog(data);
    case ACCOUNT_TAG.SeekerSeat:
      return decodeSeekerSeat(data);
    case ACCOUNT_TAG.Config:
      return decodeConfig(data);
    default:
      throw new DecodeError("BAD_TAG", `unknown account tag ${data[0]}`);
  }
}

/**
 * Checks that `address` is the canonical-bump PDA implied by the account's own contents,
 * and that `owner` is the program. Returns the reason when it is not.
 */
export function verifyAccount(acc: HdAccount, address: string, owner: string, programId: string): string | null {
  if (owner !== programId) return `owner ${owner} is not the heads_down program`;
  let expected: string | null;
  switch (acc.kind) {
    case "Rig":
      expected = rigAddress(acc.authority, acc.bump, programId);
      break;
    case "SeekerSeat":
      expected = seekerSeatAddress(acc.sgtMint, acc.bump, programId);
      break;
    case "ShiftLog":
      expected = shiftLogAddress(acc.rig, acc.shiftId, acc.bump, programId);
      break;
    case "Config":
      expected = canonicalProgramAddress([seed("config")], acc.bump, programId);
      break;
  }
  if (expected !== address) return `address ${address} is not the canonical ${acc.kind} PDA for its contents`;
  return null;
}

// ---------------------------------------------------------------- encoders (sim + tests)

function hdr(w: ByteWriter, kind: keyof typeof ACCOUNT_TAG, bump: number) {
  w.u8(ACCOUNT_TAG[kind]).u8(ACCOUNT_VERSION).u8(bump).skip(5);
}
const a32 = (a: string) => decodeBase58(a, 32);

export function encodeRig(x: RigAccount): Uint8Array {
  const w = new ByteWriter(ACCOUNT_SIZE.Rig);
  hdr(w, "Rig", x.bump);
  w.bytes(a32(x.authority)).bytes(Uint8Array.from(Buffer.from(x.p256Pubkey, "hex")));
  w.u8(x.attestationLevel).u8(x.tier).u8(x.state).skip(4);
  w.bytes(a32(x.sgtMint ?? ZERO_ADDRESS)).u64(x.attestationExpirySlot);
  w.u64(x.capWeek).u64(x.capShift).u64(x.capRound).u64(x.capMaxCost).i64(x.capsExpiryTs);
  w.u64(x.planMaxEvCost).u64(x.planDigLamports);
  w.u8(x.planSplitTiles).u8(x.planSoloTiles).u8(x.planLeaseRounds).u8(x.planFlags).skip(4);
  w.i64(x.planWindowStartTs).i64(x.planWindowEndTs);
  w.u64(x.shiftId).u64(x.hbCounter).u64(x.leaseFromRound).u64(x.leaseToRound).u32(x.gapCount).skip(4);
  w.u64(x.spentShift).u64(x.spentWeek).i64(x.weekStartTs).u64(x.lastDugRound);
  w.u64(x.shiftStartRound).u64(x.shiftDarkRounds).u64(x.shiftRoundsDug);
  w.u64(x.lifetimeDarkRounds).u64(x.lifetimeRoundsDug).u64(x.lifetimeLamportsDeployed);
  w.u32(x.streak).u8(x.freezesLeft).skip(3).i64(x.lastShiftDay);
  w.u8(x.shiftOpen ?? 0).u8(x.breakReason ?? 0).u8(x.oreAutomationBump ?? 0).u8(x.oreMinerBump ?? 0).skip(4).i64(x.shiftStartTs ?? 0n);
  return w.finish();
}

export function encodeShiftLog(x: ShiftLogAccount): Uint8Array {
  const w = new ByteWriter(ACCOUNT_SIZE.ShiftLog);
  hdr(w, "ShiftLog", x.bump);
  w.bytes(a32(x.rig)).u64(x.shiftId).u64(x.startRound).u64(x.endRound).u64(x.darkRounds);
  w.u64(x.roundsDug).u64(x.lamportsDeployed).u8(x.breakReason).u8(x.mode).skip(6);
  w.i64(x.startTs).i64(x.endTs);
  return w.finish();
}

export function encodeSeekerSeat(x: SeekerSeatAccount): Uint8Array {
  const w = new ByteWriter(ACCOUNT_SIZE.SeekerSeat);
  hdr(w, "SeekerSeat", x.bump);
  w.bytes(a32(x.sgtMint)).bytes(a32(x.rig)).bytes(a32(x.authority)).u64(x.memberNumber).u64(x.verifiedSlot);
  return w.finish();
}
