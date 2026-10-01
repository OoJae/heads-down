//! Shared test helpers: phones that sign heartbeats like Android Keystore, rigs, params.
#![allow(dead_code)]

pub mod skr;

use hd_crank::hd::{self, HeartbeatFields, RigAccounts};
use hd_crank::heartbeat::{verify_signature, VerifiedHeartbeat};
use hd_crank::tx::{BuildParams, CuEstimate, RigDig, TxFormat, DEFAULT_MAX_ACCOUNT_LOCKS};
use hd_crank::{alt, ore};
use p256::ecdsa::{signature::Signer, Signature, SigningKey};
use solana_address::Address;
use solana_message::AddressLookupTableAccount;

/// A phone's rig key.
pub struct Phone {
    pub sk: SigningKey,
}

impl Phone {
    pub fn new(seed: u32) -> Self {
        let mut b = [0u8; 32];
        b[..4].copy_from_slice(&seed.wrapping_add(1).to_le_bytes());
        b[31] = 1;
        Phone { sk: SigningKey::from_slice(&b).unwrap() }
    }

    pub fn pubkey(&self) -> [u8; 33] {
        self.sk.verifying_key().to_sec1_point(true).as_bytes().try_into().unwrap()
    }

    /// Raw (possibly high-S) signature over SHA-256(digest), as Keystore's SHA256withECDSA.
    pub fn sign_raw(&self, program: &Address, rig: &Address, f: &HeartbeatFields) -> [u8; 64] {
        let digest = hd::digest(&hd::heartbeat_preimage(program, rig, f));
        let sig: Signature = self.sk.sign(&digest);
        sig.to_bytes().into()
    }

    /// A heartbeat as the intake would store it.
    pub fn verified(&self, program: &Address, rig: &Address, f: HeartbeatFields) -> VerifiedHeartbeat {
        let digest = hd::digest(&hd::heartbeat_preimage(program, rig, &f));
        let raw = self.sign_raw(program, rig, &f);
        let sig = verify_signature(&self.pubkey(), &digest, &raw).unwrap();
        VerifiedHeartbeat { rig: *rig, fields: f, sig, pubkey: self.pubkey(), digest }
    }
}

pub fn addr(tag: u32, salt: u8) -> Address {
    let mut b = [salt; 32];
    b[..4].copy_from_slice(&tag.to_le_bytes());
    Address::new_from_array(b)
}

/// Rig `i` with derived ORE accounts.
pub fn rig_accounts(i: u32) -> RigAccounts {
    let authority = addr(i, 0xA1);
    RigAccounts::derive(hd::rig_pda(&hd::PROGRAM_ID, &authority).0, authority)
}

pub fn rig_dig(i: u32, with_hb: bool, checkpoint: Option<u64>) -> RigDig {
    let accounts = rig_accounts(i);
    let heartbeat = with_hb.then(|| {
        Phone::new(i).verified(
            &hd::PROGRAM_ID,
            &accounts.rig,
            HeartbeatFields { counter: 100 + u64::from(i), shift_id: 7, round_id: 422_601, lease_rounds: 2 },
        )
    });
    RigDig { accounts, heartbeat, checkpoint_round: checkpoint }
}

pub fn params(format: TxFormat, cranker: Address) -> BuildParams {
    BuildParams {
        program_id: hd::PROGRAM_ID,
        cranker,
        round_id: 422_601,
        format,
        cu_price_micro_lamports: 10_000,
        cu_limit: None,
        cu_estimate: CuEstimate::default(),
        loaded_accounts_data_size_limit: 64 * 1024 * 1024,
        max_account_locks: DEFAULT_MAX_ACCOUNT_LOCKS,
        max_rigs_per_tx: 64,
        tip: None,
    }
}

/// A lookup table with the shared accounts and every rig's four accounts.
pub fn lookup_table(rigs: &[RigDig]) -> AddressLookupTableAccount {
    let mut addresses = alt::shared_addresses(&hd::PROGRAM_ID);
    for r in rigs {
        addresses.extend(alt::rig_addresses(&r.accounts));
    }
    assert!(addresses.len() <= 256);
    AddressLookupTableAccount { key: addr(0xFFFF, 0xEE), addresses }
}

pub fn board_round() -> Address {
    ore::round_pda(422_601)
}
