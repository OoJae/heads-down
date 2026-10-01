//! heads_down instruction builders the dev stack needs (wallet side). Byte layouts are the
//! program's own (`programs/heads-down/INTERFACE-NOTES.md` §4) and match the reference client
//! in `programs/heads-down/tests/src/lib.rs`.

use hd_crank::hd::{config_pda, executor_pda, rig_pda, PROGRAM_ID};
use hd_crank::ore::{BOARD_ADDRESS, BPF_UPGRADEABLE_LOADER_ID, SYSTEM_PROGRAM_ID};
use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};

/// heads_down's ProgramData account (upgradeable loader).
pub fn program_data() -> Address {
    Address::find_program_address(&[PROGRAM_ID.as_ref()], &BPF_UPGRADEABLE_LOADER_ID).0
}

/// Config PDA.
pub fn config() -> Address {
    config_pda(&PROGRAM_ID).0
}

/// Executor PDA.
pub fn executor() -> Address {
    executor_pda(&PROGRAM_ID).0
}

/// Rig PDA of `authority`.
pub fn rig(authority: &Address) -> Address {
    rig_pda(&PROGRAM_ID, authority).0
}

/// `initialize_config` (tag 0), signed by the program's upgrade authority.
pub fn initialize_config_ix(
    upgrade_authority: &Address,
    governance: &Address,
    registrar: &Address,
    crank_fee: u64,
    executor_fee: u64,
    bury_bps: u16,
) -> Instruction {
    let mut data = vec![0u8];
    data.extend_from_slice(governance.as_ref());
    data.extend_from_slice(registrar.as_ref());
    data.extend_from_slice(&crank_fee.to_le_bytes());
    data.extend_from_slice(&executor_fee.to_le_bytes());
    data.extend_from_slice(&bury_bps.to_le_bytes());
    data.extend_from_slice(&heads_down::ore::layout_hash());
    Instruction {
        program_id: PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*upgrade_authority, true),
            AccountMeta::new(config(), false),
            AccountMeta::new_readonly(program_data(), false),
            AccountMeta::new_readonly(SYSTEM_PROGRAM_ID, false),
        ],
        data,
    }
}

/// `propose_config` (tag 12), signed by `Config.governance`. `paused = 1` pauses `dig`
/// immediately; everything else (and un-pausing) waits for `apply_config` after the timelock.
pub fn propose_config_ix(governance: &Address, registrar: &Address, crank_fee: u64, bury_bps: u16, paused: u8) -> Instruction {
    let mut data = vec![12u8];
    data.extend_from_slice(registrar.as_ref());
    data.extend_from_slice(&crank_fee.to_le_bytes());
    data.extend_from_slice(&bury_bps.to_le_bytes());
    data.push(paused);
    Instruction {
        program_id: PROGRAM_ID,
        accounts: vec![AccountMeta::new_readonly(*governance, true), AccountMeta::new(config(), false)],
        data,
    }
}

/// `apply_config` (tag 13): anyone, once `slot >= pending_eta_slot`.
pub fn apply_config_ix() -> Instruction {
    Instruction { program_id: PROGRAM_ID, accounts: vec![AccountMeta::new(config(), false)], data: vec![13u8] }
}

/// The pending-proposal half of the Config account (INTERFACE §3.1 offsets 128..187), which
/// `hd_crank::hd::HdConfig` does not decode. Call only on bytes `HdConfig::decode` accepted.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Pending {
    /// A proposal is waiting.
    pub exists: bool,
    /// Slot from which `apply_config` succeeds.
    pub eta_slot: u64,
    /// Proposed registrar.
    pub registrar: Address,
    /// Proposed crank fee.
    pub crank_fee: u64,
    /// Proposed bury share.
    pub bury_bps: u16,
    /// Proposed pause flag.
    pub paused: u8,
}

/// Decode [`Pending`] from raw Config bytes.
pub fn pending(data: &[u8]) -> Option<Pending> {
    let u64_at = |o: usize| data.get(o..o + 8).and_then(|s| s.try_into().ok()).map(u64::from_le_bytes);
    let addr: [u8; 32] = data.get(144..176)?.try_into().ok()?;
    Some(Pending {
        exists: *data.get(128)? == 1,
        eta_slot: u64_at(136)?,
        registrar: Address::new_from_array(addr),
        crank_fee: u64_at(176)?,
        bury_bps: data.get(184..186).and_then(|s| s.try_into().ok()).map(u16::from_le_bytes)?,
        paused: *data.get(186)?,
    })
}

/// `register_rig` (tag 1) without a registrar attestation (attestation_level 0).
pub fn register_rig_ix(authority: &Address, p256: &[u8; 33]) -> Instruction {
    let mut data = vec![1u8];
    data.extend_from_slice(p256);
    data.push(0);
    Instruction {
        program_id: PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*authority, true),
            AccountMeta::new(rig(authority), false),
            AccountMeta::new_readonly(config(), false),
            AccountMeta::new_readonly(SYSTEM_PROGRAM_ID, false),
        ],
        data,
    }
}

/// Wallet-signed caps.
#[derive(Clone, Copy, Debug)]
pub struct Caps {
    /// lamports / week.
    pub week: u64,
    /// lamports / shift.
    pub shift: u64,
    /// lamports / round.
    pub round: u64,
    /// ceiling on the pot-adjusted cost `ema_ev` (lamports per ORE).
    pub max_cost: u64,
    /// unix seconds.
    pub expiry: i64,
}

/// `set_caps` (tag 3).
pub fn set_caps_ix(authority: &Address, c: &Caps) -> Instruction {
    let mut data = vec![3u8];
    for v in [c.week, c.shift, c.round, c.max_cost] {
        data.extend_from_slice(&v.to_le_bytes());
    }
    data.extend_from_slice(&c.expiry.to_le_bytes());
    Instruction {
        program_id: PROGRAM_ID,
        accounts: vec![AccountMeta::new_readonly(*authority, true), AccountMeta::new(rig(authority), false)],
        data,
    }
}

/// A shift plan.
#[derive(Clone, Copy, Debug)]
pub struct Plan {
    /// Gate threshold (≤ cap_max_cost).
    pub max_ev_cost: u64,
    /// SOL per dig.
    pub dig_lamports: u64,
    /// Split tiles.
    pub split: u8,
    /// Solo tiles.
    pub solo: u8,
    /// Lease rounds per heartbeat (1..=3).
    pub lease: u8,
    /// Flags (bit0 focus-only, bit1 day).
    pub flags: u8,
    /// Window start (unix s).
    pub window_start: i64,
    /// Window end (unix s).
    pub window_end: i64,
}

/// `arm_shift` (tag 5), mode 0 (wallet-signed).
pub fn arm_wallet_ix(authority: &Address, p: &Plan) -> Instruction {
    let mut data = vec![5u8, 0];
    data.extend_from_slice(&p.max_ev_cost.to_le_bytes());
    data.extend_from_slice(&p.dig_lamports.to_le_bytes());
    data.extend_from_slice(&[p.split, p.solo, p.lease, p.flags]);
    data.extend_from_slice(&p.window_start.to_le_bytes());
    data.extend_from_slice(&p.window_end.to_le_bytes());
    Instruction {
        program_id: PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(rig(authority), false),
            AccountMeta::new_readonly(*authority, true),
            AccountMeta::new_readonly(BOARD_ADDRESS, false),
        ],
        data,
    }
}

#[cfg(test)]
mod tests {
    //! The admin instructions the mainnet scripts send, checked byte for byte against the
    //! program's golden vectors (`programs/heads-down/vectors/instructions.json`, generated
    //! from LiteSVM runs of the real program).
    use super::*;
    use serde_json::Value;

    fn vector(name: &str) -> Value {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../../programs/heads-down/vectors/instructions.json");
        let all: Value = serde_json::from_str(&std::fs::read_to_string(path).expect("golden vectors")).expect("json");
        all["instructions"].as_array().expect("instructions").iter().find(|v| v["name"] == name).cloned().expect(name)
    }

    fn addr(v: &Value) -> Address {
        v.as_str().expect("address").parse().expect("base58")
    }

    fn assert_matches(ix: &Instruction, v: &Value) {
        assert_eq!(hex::encode(&ix.data), v["data_hex"].as_str().unwrap(), "data of {}", v["name"]);
        let metas = v["accounts"].as_array().unwrap();
        assert_eq!(ix.accounts.len(), metas.len(), "account count of {}", v["name"]);
        for (m, want) in ix.accounts.iter().zip(metas) {
            assert_eq!(m.pubkey, addr(&want["pubkey"]), "{} {}", v["name"], want["role"]);
            assert_eq!(m.is_signer, want["is_signer"].as_bool().unwrap(), "{} {} signer", v["name"], want["role"]);
            assert_eq!(m.is_writable, want["is_writable"].as_bool().unwrap(), "{} {} writable", v["name"], want["role"]);
        }
    }

    #[test]
    fn initialize_config_matches_the_golden_vector() {
        let v = vector("initialize_config");
        let a = &v["args"];
        let ix = initialize_config_ix(
            &addr(&v["accounts"][0]["pubkey"]),
            &addr(&a["governance"]),
            &addr(&a["registrar"]),
            a["crank_fee"].as_str().unwrap().parse().unwrap(),
            a["executor_fee"].as_str().unwrap().parse().unwrap(),
            a["bury_bps"].as_u64().unwrap() as u16,
        );
        assert_matches(&ix, &v);
        assert_eq!(program_data(), addr(&v["accounts"][2]["pubkey"]));
        assert_eq!(hex::encode(heads_down::ore::layout_hash()), a["ore_layout_hash"].as_str().unwrap());
    }

    #[test]
    fn propose_and_apply_config_match_the_golden_vectors() {
        let v = vector("propose_config");
        let a = &v["args"];
        let ix = propose_config_ix(
            &addr(&v["accounts"][0]["pubkey"]),
            &addr(&a["registrar"]),
            a["crank_fee"].as_str().unwrap().parse().unwrap(),
            a["bury_bps"].as_u64().unwrap() as u16,
            a["paused"].as_u64().unwrap() as u8,
        );
        assert_matches(&ix, &v);
        assert_matches(&apply_config_ix(), &vector("apply_config"));
    }

    #[test]
    fn pending_fields_decode_at_their_interface_offsets() {
        let mut d = vec![0u8; 256];
        d[128] = 1;
        d[136..144].copy_from_slice(&864_123u64.to_le_bytes());
        d[144..176].copy_from_slice(&[7u8; 32]);
        d[176..184].copy_from_slice(&6_500u64.to_le_bytes());
        d[184..186].copy_from_slice(&250u16.to_le_bytes());
        d[186] = 1;
        let p = pending(&d).unwrap();
        assert!(p.exists);
        assert_eq!((p.eta_slot, p.crank_fee, p.bury_bps, p.paused), (864_123, 6_500, 250, 1));
        assert_eq!(p.registrar, Address::new_from_array([7; 32]));
        assert!(pending(&d[..100]).is_none());
    }
}
