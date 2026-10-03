/**
 * heads_down instruction decoder (INTERFACE.md v1.1 §5), all 15 tags, both auth paths.
 *
 * The indexer reads instruction data for two reasons:
 *  1. The morning haul needs every heartbeat lease a rig was granted (to mark each ORE round of a
 *     shift as dark or not). A heartbeat applied inside `dig` emits no event of its own (§10), so
 *     the lease comes from the `dig` / `record_heartbeats` entry (`round_id`, `lease_rounds`),
 *     and the plan's lease cap from the `arm_shift` data.
 *  2. `decode <signature>` prints every heads_down instruction with its fields and account roles.
 *
 * Decoding mirrors the program's strictness: exact data length per tag and mode, `n` in 1..=32,
 * `has_attestation` / `mode` in {0, 1}. It is checked field by field against
 * programs/heads-down/vectors/instructions.json (test/ix.test.ts).
 */
import { ByteReader, DecodeError, toHex } from "./bytes.ts";

export const HD_IX = {
  initialize_config: 0,
  register_rig: 1,
  verify_seeker: 2,
  set_caps: 3,
  rotate_key: 4,
  arm_shift: 5,
  dig: 6,
  record_heartbeats: 7,
  break_shift: 8,
  freeze_rig: 9,
  unfreeze_rig: 10,
  end_shift: 11,
  propose_config: 12,
  apply_config: 13,
  close_rig: 14,
} as const;
export type HdIxName = keyof typeof HD_IX;
const NAME_BY_TAG = new Map<number, HdIxName>(Object.entries(HD_IX).map(([k, v]) => [v, k as HdIxName]));

export type IxFieldType = "u8" | "u16" | "u64" | "i64" | "pubkey" | "[u8;33]" | "[u8;32]";
export interface IxField {
  /** snake_case, as in instructions.json (`entry[i].counter` for per-rig entries). */
  name: string;
  type: IxFieldType;
  offset: number;
  size: number;
  /** u8/u16: number; u64/i64: bigint; pubkey: base58; byte arrays: hex. */
  value: number | bigint | string;
}

/** One 20-byte `dig` / `record_heartbeats` entry. */
export interface HeartbeatEntry {
  /** Absolute top-level index of the Secp256r1SigVerify instruction; 0xFF = reuse the current lease (dig only). */
  hbIx: number;
  hbSigIndex: number;
  counter: bigint;
  /** The ORE round the phone signed for. */
  roundId: bigint;
  /** Lease requested by the phone (the program applies min(lease_rounds, plan_lease_rounds)). */
  leaseRounds: number;
}
export const NO_HEARTBEAT = 0xff;
export const MAX_RIGS_PER_IX = 32;
export const ENTRY_LEN = 20;

export interface ArmPlan {
  /** 0 wallet, 1 P-256 PLAN. */
  mode: number;
  maxEvCost: bigint;
  digLamports: bigint;
  split: number;
  solo: number;
  lease: number;
  flags: number;
  windowStart: bigint;
  windowEnd: bigint;
  counter: bigint | null;
}

export interface DecodedHdIx {
  tag: number;
  name: HdIxName;
  fields: IxField[];
  /** dig / record_heartbeats only. */
  entries: HeartbeatEntry[];
  /** arm_shift only. */
  plan: ArmPlan | null;
  /** break_shift / freeze_rig only. */
  signal: { mode: number; reason: number; counter: bigint | null } | null;
}

class FieldReader {
  readonly fields: IxField[] = [];
  private readonly r: ByteReader;
  constructor(data: Uint8Array) {
    this.r = new ByteReader(data);
  }
  private push(name: string, type: IxFieldType, size: number, value: number | bigint | string) {
    this.fields.push({ name, type, offset: this.r.position - size, size, value });
    return value;
  }
  u8(name: string): number {
    const v = this.r.u8(name);
    return this.push(name, "u8", 1, v) as number;
  }
  u16(name: string): number {
    const v = this.r.u16(name);
    return this.push(name, "u16", 2, v) as number;
  }
  u64(name: string): bigint {
    const v = this.r.u64(name);
    return this.push(name, "u64", 8, v) as bigint;
  }
  i64(name: string): bigint {
    const v = this.r.i64(name);
    return this.push(name, "i64", 8, v) as bigint;
  }
  pubkey(name: string): string {
    const v = this.r.address(name);
    return this.push(name, "pubkey", 32, v) as string;
  }
  bytes(name: string, n: 32 | 33): string {
    const v = toHex(this.r.fixed(n, name));
    return this.push(name, n === 33 ? "[u8;33]" : "[u8;32]", n, v) as string;
  }
  end(what: string) {
    this.r.end(what);
  }
}

function exactLen(name: string, data: Uint8Array, ...lens: number[]): void {
  if (!lens.includes(data.length)) {
    throw new DecodeError("BAD_LENGTH", `${name} data must be ${lens.join(" or ")} bytes, got ${data.length}`);
  }
}

function zeroOrOne(v: number, what: string): void {
  if (v !== 0 && v !== 1) throw new DecodeError("BAD_FIELD", `${what} must be 0 or 1, got ${v}`);
}

function readEntries(f: FieldReader, data: Uint8Array, name: string): HeartbeatEntry[] {
  if (data.length < 2) throw new DecodeError("BAD_LENGTH", `${name}: missing n`);
  const n = f.u8("n");
  if (n === 0 || n > MAX_RIGS_PER_IX || data.length !== 2 + ENTRY_LEN * n) {
    throw new DecodeError("BAD_LENGTH", `${name}: n=${n} with ${data.length} bytes (need 2 + 20n, n in 1..=32)`);
  }
  const out: HeartbeatEntry[] = [];
  for (let i = 0; i < n; i++) {
    const p = `entry[${i}].`;
    out.push({
      hbIx: f.u8(`${p}hb_ix`),
      hbSigIndex: f.u8(`${p}hb_sig_index`),
      counter: f.u64(`${p}counter`),
      roundId: f.u64(`${p}round_id`),
      leaseRounds: f.u8(`${p}lease_rounds`),
    });
    f.u8(`${p}_pad`);
  }
  return out;
}

function readP256Tail(f: FieldReader): bigint {
  const counter = f.u64("counter");
  f.u8("p256_ix");
  f.u8("p256_sig_index");
  return counter;
}

function readKeyRegistration(f: FieldReader, data: Uint8Array, name: string): void {
  exactLen(name, data, 35, 46);
  f.bytes("p256_pubkey", 33);
  const has = f.u8("has_attestation");
  zeroOrOne(has, "has_attestation");
  if ((has === 1) !== (data.length === 46)) throw new DecodeError("BAD_LENGTH", `${name}: has_attestation=${has} with ${data.length} bytes`);
  if (has === 1) {
    f.u8("ed25519_ix");
    f.u8("ed25519_sig_index");
    f.u8("level");
    f.u64("expiry_slot");
  }
}

/** Decodes one heads_down instruction's data (tag byte included). */
export function decodeHdInstruction(data: Uint8Array): DecodedHdIx {
  if (data.length === 0) throw new DecodeError("BAD_LENGTH", "empty instruction data");
  const tag = data[0]!;
  const name = NAME_BY_TAG.get(tag);
  if (name === undefined) throw new DecodeError("BAD_TAG", `unknown heads_down instruction tag ${tag}`);
  const f = new FieldReader(data);
  f.u8("tag");
  const out: DecodedHdIx = { tag, name, fields: f.fields, entries: [], plan: null, signal: null };
  switch (name) {
    case "initialize_config":
      exactLen(name, data, 115);
      f.pubkey("governance");
      f.pubkey("registrar");
      f.u64("crank_fee");
      f.u64("executor_fee");
      f.u16("bury_bps");
      f.bytes("ore_layout_hash", 32);
      break;
    case "register_rig":
    case "rotate_key":
      readKeyRegistration(f, data, name);
      break;
    case "set_caps":
      exactLen(name, data, 41);
      f.u64("cap_week");
      f.u64("cap_shift");
      f.u64("cap_round");
      f.u64("cap_max_cost");
      f.i64("caps_expiry_ts");
      break;
    case "arm_shift": {
      exactLen(name, data, 38, 48);
      const mode = f.u8("mode");
      zeroOrOne(mode, "mode");
      if ((mode === 1) !== (data.length === 48)) throw new DecodeError("BAD_LENGTH", `arm_shift: mode ${mode} with ${data.length} bytes`);
      const plan: ArmPlan = {
        mode,
        maxEvCost: f.u64("max_ev_cost"),
        digLamports: f.u64("dig_lamports"),
        split: f.u8("split"),
        solo: f.u8("solo"),
        lease: f.u8("lease"),
        flags: f.u8("flags"),
        windowStart: f.i64("window_start"),
        windowEnd: f.i64("window_end"),
        counter: null,
      };
      if (mode === 1) plan.counter = readP256Tail(f);
      out.plan = plan;
      break;
    }
    case "dig":
    case "record_heartbeats":
      out.entries = readEntries(f, data, name);
      break;
    case "break_shift":
    case "freeze_rig": {
      exactLen(name, data, 3, 13);
      const mode = f.u8("mode");
      zeroOrOne(mode, "mode");
      if ((mode === 1) !== (data.length === 13)) throw new DecodeError("BAD_LENGTH", `${name}: mode ${mode} with ${data.length} bytes`);
      const reason = f.u8("reason");
      out.signal = { mode, reason, counter: mode === 1 ? readP256Tail(f) : null };
      break;
    }
    case "propose_config": {
      exactLen(name, data, 44);
      f.pubkey("registrar");
      f.u64("crank_fee");
      f.u16("bury_bps");
      zeroOrOne(f.u8("paused"), "paused");
      break;
    }
    case "verify_seeker":
    case "unfreeze_rig":
    case "end_shift":
    case "apply_config":
    case "close_rig":
      exactLen(name, data, 1);
      break;
  }
  f.end(name);
  return out;
}

const DIG_FIXED = [
  "cranker",
  "config",
  "executor",
  "ore_board",
  "ore_config",
  "ore_round",
  "ore_treasury",
  "system_program",
  "ore_program",
  "ore_entropy_var",
  "entropy_program",
  "instructions_sysvar",
];
const DIG_PER_RIG = ["rig", "authority", "ore_automation", "ore_miner"];

/**
 * Role of every account of an instruction, as instructions.json names them. Optional trailing
 * accounts get their role when present; anything beyond the known list is `extra[k]`.
 */
export function hdAccountRoles(ix: DecodedHdIx, count: number): string[] {
  let roles: string[];
  switch (ix.name) {
    case "initialize_config":
      roles = ["upgrade_authority", "config", "program_data", "system_program"];
      break;
    case "register_rig":
      roles = ["authority", "rig", "config", "system_program", "instructions_sysvar"];
      break;
    case "verify_seeker":
      roles = ["authority", "rig", "seeker_seat", "sgt_token_account", "sgt_mint", "system_program", "previous_rig"];
      break;
    case "set_caps":
      roles = ["authority", "rig"];
      break;
    case "rotate_key":
      roles = ["authority", "rig", "config", "instructions_sysvar"];
      break;
    case "arm_shift":
      roles = ["rig", "authority", "ore_board", "instructions_sysvar"];
      break;
    case "dig":
      roles = [...DIG_FIXED];
      for (let i = 0; i < ix.entries.length; i++) roles.push(...DIG_PER_RIG.map((r) => `${r}[${i}]`));
      break;
    case "record_heartbeats":
      roles = ["ore_board", "instructions_sysvar", ...ix.entries.map((_e, i) => `rig[${i}]`)];
      break;
    case "break_shift":
    case "freeze_rig":
      roles = ["rig", "authority", "instructions_sysvar"];
      break;
    case "unfreeze_rig":
      roles = ["rig", "authority"];
      break;
    case "end_shift":
      roles = ["caller", "rig", "shift_log", "ore_board", "system_program"];
      break;
    case "propose_config":
      roles = ["governance", "config"];
      break;
    case "apply_config":
      roles = ["config"];
      break;
    case "close_rig":
      roles = ["authority", "rig", "seeker_seat"];
      break;
  }
  const out = roles.slice(0, count);
  for (let k = out.length; k < count; k++) out.push(`extra[${k - roles.length}]`);
  return out;
}

/** Account position of the rig for entry `i` of a dig / record_heartbeats, or of a single-rig instruction. */
export function rigAccountIndex(ix: DecodedHdIx, entry = 0): number | null {
  switch (ix.name) {
    case "dig":
      return DIG_FIXED.length + DIG_PER_RIG.length * entry;
    case "record_heartbeats":
      return 2 + entry;
    case "arm_shift":
    case "break_shift":
    case "freeze_rig":
    case "unfreeze_rig":
      return 0;
    case "register_rig":
    case "verify_seeker":
    case "set_caps":
    case "rotate_key":
    case "end_shift":
    case "close_rig":
      return 1;
    default:
      return null;
  }
}
