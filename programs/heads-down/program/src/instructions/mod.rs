//! Instruction handlers. Each module documents its exact account list and
//! data layout (after the tag byte).

pub mod arm_shift;
pub mod close_rig;
pub mod dig;
pub mod end_shift;
pub mod governance;
pub mod initialize_config;
pub mod record_heartbeats;
pub mod register_rig;
pub mod rotate_key;
pub mod set_caps;
pub mod signals;
pub mod verify_seeker;

use pinocchio::{AccountView, Address};

use crate::{
    error::HdError,
    logic, message,
    state::{rig_state, Rig},
};

/// One per-rig heartbeat entry (20 bytes) as carried by `dig` and
/// `record_heartbeats`: `hb_ix u8 | hb_sig_index u8 | counter u64 |
/// round_id u64 | lease_rounds u8 | _pad u8`.
#[derive(Clone, Copy, Debug)]
pub struct HeartbeatEntry {
    /// Index of the Secp256r1SigVerify instruction, or [`NO_HEARTBEAT`].
    pub hb_ix: u8,
    /// Entry within that instruction.
    pub hb_sig_index: u8,
    /// P-256 counter.
    pub counter: u64,
    /// ORE round the phone signed for.
    pub round_id: u64,
    /// Requested lease.
    pub lease_rounds: u8,
}

/// Entry size in bytes.
pub const ENTRY_LEN: usize = 20;
/// `hb_ix` value meaning "reuse the rig's current lease".
pub const NO_HEARTBEAT: u8 = 0xFF;

impl HeartbeatEntry {
    /// Parse entry bytes.
    pub fn parse(b: &[u8]) -> Result<Self, HdError> {
        let mut r = crate::util::Reader::new(b);
        let e = Self {
            hb_ix: r.u8()?,
            hb_sig_index: r.u8()?,
            counter: r.u64()?,
            round_id: r.u64()?,
            lease_rounds: r.u8()?,
        };
        let _pad = r.u8()?;
        r.finish()?;
        Ok(e)
    }
}

/// Verify `entry`'s HEARTBEAT for `rig` and grant its lease. Returns
/// `Err(code)` to skip the rig; nothing is written unless every check
/// passes. `board_round` is the live `Board.round_id`.
pub fn apply_heartbeat(
    rig: &mut Rig,
    rig_address: &Address,
    instructions_sysvar: &AccountView,
    entry: &HeartbeatEntry,
    board_round: u64,
) -> Result<(), u32> {
    if entry.round_id > board_round || entry.lease_rounds == 0 {
        return Err(HdError::InvalidHeartbeat.code());
    }
    if entry.counter <= rig.hb_counter.get() {
        return Err(HdError::StaleHeartbeat.code());
    }
    let preimage = message::heartbeat_preimage(
        rig_address,
        entry.counter,
        rig.shift_id.get(),
        entry.round_id,
        entry.lease_rounds,
    );
    let digest = message::digest(&preimage);
    p256_introspect::verify_secp256r1_signature(
        instructions_sysvar,
        u16::from(entry.hb_ix),
        entry.hb_sig_index,
        &rig.p256_pubkey,
        &digest,
    )
    .map_err(|e| crate::error::skip_code(&e))?;

    let lease = entry.lease_rounds.min(rig.plan_lease_rounds);
    let grant = logic::grant_lease(
        rig.lease_from_round.get(),
        rig.lease_to_round.get(),
        rig.shift_start_round.get(),
        entry.round_id,
        lease,
    )
    .map_err(|e| e.code())?;

    rig.hb_counter.set(entry.counter);
    rig.lease_from_round.set(grant.from);
    rig.lease_to_round.set(grant.to);
    rig.shift_dark_rounds
        .set(rig.shift_dark_rounds.get().saturating_add(grant.dark_added));
    let gaps = u64::from(rig.gap_count.get()).saturating_add(grant.gap_added);
    rig.gap_count.set(u32::try_from(gaps).unwrap_or(u32::MAX));
    if rig.state == rig_state::ARMED || rig.state == rig_state::COOLING {
        rig.state = rig_state::DOWN;
    }
    Ok(())
}

/// `true` when a lease granted in this shift covers `round`.
#[inline]
pub fn lease_covers(rig: &Rig, round: u64) -> bool {
    let to = rig.lease_to_round.get();
    to != 0 && rig.lease_from_round.get() <= round && round <= to
}
