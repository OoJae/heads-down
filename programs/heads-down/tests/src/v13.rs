//! v1.3 reference client: one builder per new instruction (tags 28..=31) and
//! the decoders for what `close_rig` now leaves behind, all following
//! `INTERFACE.md` §12.

use heads_down::state;

use crate::*;

/// Rent-exempt lamports of a 32-byte account (the RigTombstone).
pub const TOMBSTONE_RENT: u64 = 1_113_600;
/// Rent-exempt lamports of a 384-byte account (the Rig).
pub const RIG_RENT: u64 = 3_563_520;

/// What the Rig PDA holds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RigSlot {
    /// Nothing: never registered, or a rig that never armed nor signed was
    /// closed (lamports 0).
    Empty,
    /// A System-owned account with lamports but no data (pre-funded).
    Prefunded,
    /// A live Rig (tag 2, 384 bytes).
    Rig,
    /// The tombstone a closed rig left (tag 10, 32 bytes).
    Tombstone,
}

impl Env {
    /// Classify the account at a Rig PDA the way every client must: only a
    /// heads_down-owned, 384-byte, tag-2 account is a registered rig.
    pub fn rig_slot(&self, rig: &Address) -> RigSlot {
        match self.svm.get_account(rig) {
            None => RigSlot::Empty,
            Some(a) if a.lamports == 0 && a.data.is_empty() => RigSlot::Empty,
            Some(a) if a.owner == HD && a.data.len() == 384 && a.data[0] == state::tag::RIG => {
                RigSlot::Rig
            }
            Some(a)
                if a.owner == HD
                    && a.data.len() == 32
                    && a.data[0] == state::tag::RIG_TOMBSTONE =>
            {
                RigSlot::Tombstone
            }
            Some(a) if a.owner == SYSTEM && a.data.is_empty() => RigSlot::Prefunded,
            Some(a) => panic!("unexpected account at a Rig PDA: {a:?}"),
        }
    }

    /// Decode a RigTombstone.
    pub fn tombstone(&self, rig: &Address) -> state::RigTombstone {
        *bytemuck_view::<state::RigTombstone>(&self.account(rig).data)
    }

    /// Move the slot clock (same wall time).
    pub fn advance_slots(&mut self, slots: u64) {
        self.set_clock(self.slot + slots, self.now);
    }
}

/// `propose_governance` (tag 28), signed by the current governance.
pub fn ix_propose_governance(governance: &Address, new_governance: &Address) -> Instruction {
    let mut data = vec![hd::tag::PROPOSE_GOVERNANCE];
    data.extend_from_slice(new_governance.as_ref());
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new_readonly(*governance, true),
            AccountMeta::new(CONFIG, false),
        ],
        data,
    }
}

/// `accept_governance` (tag 29), signed by the pending governance.
pub fn ix_accept_governance(new_governance: &Address) -> Instruction {
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new_readonly(*new_governance, true),
            AccountMeta::new(CONFIG, false),
        ],
        data: vec![hd::tag::ACCEPT_GOVERNANCE],
    }
}

/// `cancel_governance` (tag 30), signed by the current governance.
pub fn ix_cancel_governance(governance: &Address) -> Instruction {
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new_readonly(*governance, true),
            AccountMeta::new(CONFIG, false),
        ],
        data: vec![hd::tag::CANCEL_GOVERNANCE],
    }
}

/// `close_shift_log` (tag 31) for the log of `(rig, shift_id)`, paying
/// `recipient` (the address the log's `payer_prefix` names).
pub fn ix_close_shift_log(rig: &Address, shift_id: u64, recipient: &Address) -> Instruction {
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(shift_log_pda(rig, shift_id), false),
            AccountMeta::new(*recipient, false),
            AccountMeta::new_readonly(bond_pda(rig, shift_id), false),
        ],
        data: vec![hd::tag::CLOSE_SHIFT_LOG],
    }
}
