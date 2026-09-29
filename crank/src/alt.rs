//! Address lookup tables for v0 dig transactions.
//!
//! The crank owns one or more tables holding the accounts every dig repeats (heads_down
//! Config and Executor, ORE Board/Config/Treasury, System, ORE program, entropy Var and
//! program, instructions sysvar) plus each registered rig's `rig, authority, automation,
//! miner`. With the table, a rig costs 4 index bytes instead of 128 key bytes.
//!
//! The instructions are built by hand from the Address Lookup Table program's (bincode)
//! wire format; `tests/litesvm_alt.rs` runs them against the real ALT program in LiteSVM.

use solana_address::Address;
use solana_instruction::{AccountMeta, Instruction};
use solana_message::AddressLookupTableAccount;

use crate::bytes::{read_address, read_u32, read_u64, read_u8};
use crate::{hd, ore};

/// `AddressLookupTab1e1111111111111111111111111`.
pub const ALT_PROGRAM_ID: Address =
    Address::from_str_const("AddressLookupTab1e1111111111111111111111111");
/// Serialized `ProgramState::LookupTable(LookupTableMeta)` header size.
pub const LOOKUP_TABLE_META_SIZE: usize = 56;
/// Maximum addresses per table.
pub const LOOKUP_TABLE_MAX_ADDRESSES: usize = 256;
/// Addresses per `ExtendLookupTable` that keep the tx comfortably under 1232 bytes.
pub const MAX_ADDRESSES_PER_EXTEND: usize = 20;

/// `[authority, recent_slot LE]` under the ALT program.
pub fn derive_table_address(authority: &Address, recent_slot: u64) -> (Address, u8) {
    Address::find_program_address(&[authority.as_ref(), &recent_slot.to_le_bytes()], &ALT_PROGRAM_ID)
}

/// `CreateLookupTable { recent_slot, bump_seed }` (enum tag 0). `recent_slot` must be in
/// the SlotHashes sysvar (use a recent finalized slot). The authority need not sign.
pub fn create_table_ix(authority: &Address, payer: &Address, recent_slot: u64) -> (Instruction, Address) {
    let (table, bump) = derive_table_address(authority, recent_slot);
    let mut data = Vec::with_capacity(13);
    data.extend_from_slice(&0u32.to_le_bytes());
    data.extend_from_slice(&recent_slot.to_le_bytes());
    data.push(bump);
    let ix = Instruction {
        program_id: ALT_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(table, false),
            AccountMeta::new_readonly(*authority, false),
            AccountMeta::new(*payer, true),
            AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
        ],
        data,
    };
    (ix, table)
}

/// `ExtendLookupTable { new_addresses: Vec<Pubkey> }` (enum tag 2, u64 length prefix).
pub fn extend_table_ix(table: &Address, authority: &Address, payer: &Address, addresses: &[Address]) -> Instruction {
    let mut data = Vec::with_capacity(12 + 32 * addresses.len());
    data.extend_from_slice(&2u32.to_le_bytes());
    data.extend_from_slice(&(addresses.len() as u64).to_le_bytes());
    for a in addresses {
        data.extend_from_slice(a.as_ref());
    }
    Instruction {
        program_id: ALT_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*table, false),
            AccountMeta::new_readonly(*authority, true),
            AccountMeta::new(*payer, true),
            AccountMeta::new_readonly(ore::SYSTEM_PROGRAM_ID, false),
        ],
        data,
    }
}

/// A decoded lookup table account.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LookupTable {
    /// Table address.
    pub key: Address,
    /// `u64::MAX` while active.
    pub deactivation_slot: u64,
    /// Slot of the last extend.
    pub last_extended_slot: u64,
    /// Index of the first address added in `last_extended_slot`.
    pub last_extended_slot_start_index: u8,
    /// Table authority (None once frozen).
    pub authority: Option<Address>,
    /// Stored addresses.
    pub addresses: Vec<Address>,
}

impl LookupTable {
    /// Decode, requiring the ALT program as owner and a well-formed body.
    pub fn decode(key: Address, owner: &Address, data: &[u8]) -> Option<Self> {
        if owner != &ALT_PROGRAM_ID || data.len() < LOOKUP_TABLE_META_SIZE {
            return None;
        }
        if read_u32(data, 0)? != 1 {
            return None; // not ProgramState::LookupTable
        }
        let body = data.get(LOOKUP_TABLE_META_SIZE..)?;
        if body.len() % 32 != 0 || body.len() / 32 > LOOKUP_TABLE_MAX_ADDRESSES {
            return None;
        }
        let authority = match read_u8(data, 21)? {
            0 => None,
            1 => Some(read_address(data, 22)?),
            _ => return None,
        };
        let addresses = (0..body.len() / 32).map(|i| read_address(body, i * 32)).collect::<Option<Vec<_>>>()?;
        Some(LookupTable {
            key,
            deactivation_slot: read_u64(data, 4)?,
            last_extended_slot: read_u64(data, 12)?,
            last_extended_slot_start_index: read_u8(data, 20)?,
            authority,
            addresses,
        })
    }

    /// Addresses a transaction landing at `current_slot` may load: none if the table is
    /// deactivating, and only the pre-extend prefix while the last extend is still in its
    /// own slot (the runtime rule).
    pub fn usable(&self, current_slot: u64) -> AddressLookupTableAccount {
        let addresses = if self.deactivation_slot != u64::MAX {
            Vec::new()
        } else if current_slot > self.last_extended_slot {
            self.addresses.clone()
        } else {
            let n = usize::from(self.last_extended_slot_start_index).min(self.addresses.len());
            self.addresses[..n].to_vec()
        };
        AddressLookupTableAccount { key: self.key, addresses }
    }

    /// Room left.
    pub fn free(&self) -> usize {
        LOOKUP_TABLE_MAX_ADDRESSES.saturating_sub(self.addresses.len())
    }
}

/// The accounts every dig repeats (never including invoked program ids that must be
/// static keys; including them would be harmless but wasteful).
pub fn shared_addresses(program_id: &Address) -> Vec<Address> {
    vec![
        hd::config_pda(program_id).0,
        hd::executor_pda(program_id).0,
        ore::BOARD_ADDRESS,
        ore::CONFIG_ADDRESS,
        ore::TREASURY_ADDRESS,
        ore::SYSTEM_PROGRAM_ID,
        ore::ORE_PROGRAM_ID,
        ore::VAR_ADDRESS,
        ore::ENTROPY_PROGRAM_ID,
        hd::INSTRUCTIONS_SYSVAR_ID,
    ]
}

/// The four per-rig accounts.
pub fn rig_addresses(r: &hd::RigAccounts) -> [Address; 4] {
    [r.rig, r.authority, r.automation, r.miner]
}

/// Addresses in `wanted` that no table holds yet, in order, deduplicated.
pub fn missing(tables: &[LookupTable], wanted: &[Address]) -> Vec<Address> {
    let have: std::collections::HashSet<&Address> = tables.iter().flat_map(|t| t.addresses.iter()).collect();
    let mut seen = std::collections::HashSet::new();
    wanted.iter().filter(|a| !have.contains(a) && seen.insert(**a)).copied().collect()
}

/// Plan extends: fill existing tables with room first (in chunks of
/// [`MAX_ADDRESSES_PER_EXTEND`]); addresses that do not fit are returned as overflow and
/// need a new table.
pub fn plan_extends(tables: &[LookupTable], mut todo: Vec<Address>) -> (Vec<(Address, Vec<Address>)>, Vec<Address>) {
    let mut plan = Vec::new();
    for t in tables {
        if t.deactivation_slot != u64::MAX {
            continue;
        }
        let mut room = t.free();
        while room > 0 && !todo.is_empty() {
            let take = room.min(MAX_ADDRESSES_PER_EXTEND).min(todo.len());
            let chunk: Vec<Address> = todo.drain(..take).collect();
            room -= chunk.len();
            plan.push((t.key, chunk));
        }
    }
    (plan, todo)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn table_bytes(addrs: &[Address], last_slot: u64, start: u8, deact: u64) -> Vec<u8> {
        let mut d = vec![0u8; LOOKUP_TABLE_META_SIZE];
        d[0..4].copy_from_slice(&1u32.to_le_bytes());
        d[4..12].copy_from_slice(&deact.to_le_bytes());
        d[12..20].copy_from_slice(&last_slot.to_le_bytes());
        d[20] = start;
        d[21] = 1;
        d[22..54].copy_from_slice(&[5u8; 32]);
        for a in addrs {
            d.extend_from_slice(a.as_ref());
        }
        d
    }

    #[test]
    fn decode_and_warmup_rule() {
        let addrs: Vec<Address> = (0..5u8).map(|i| Address::new_from_array([i; 32])).collect();
        let key = Address::new_from_array([9; 32]);
        let t = LookupTable::decode(key, &ALT_PROGRAM_ID, &table_bytes(&addrs, 100, 3, u64::MAX)).unwrap();
        assert_eq!(t.addresses, addrs);
        assert_eq!(t.authority, Some(Address::new_from_array([5; 32])));
        assert_eq!(t.usable(100).addresses.len(), 3, "last extend not yet active");
        assert_eq!(t.usable(101).addresses.len(), 5);
        let dead = LookupTable::decode(key, &ALT_PROGRAM_ID, &table_bytes(&addrs, 100, 3, 150)).unwrap();
        assert!(dead.usable(200).addresses.is_empty());
        assert!(LookupTable::decode(key, &ore::SYSTEM_PROGRAM_ID, &table_bytes(&addrs, 1, 0, u64::MAX)).is_none());
        let mut bad = table_bytes(&addrs, 1, 0, u64::MAX);
        bad.push(0);
        assert!(LookupTable::decode(key, &ALT_PROGRAM_ID, &bad).is_none());
        assert!(LookupTable::decode(key, &ALT_PROGRAM_ID, &[1, 0, 0]).is_none());
    }

    #[test]
    fn extend_planning_fills_then_overflows() {
        let key = Address::new_from_array([9; 32]);
        let full: Vec<Address> = (0..250u32)
            .map(|i| {
                let mut b = [0u8; 32];
                b[..4].copy_from_slice(&i.to_le_bytes());
                Address::new_from_array(b)
            })
            .collect();
        let t = LookupTable::decode(key, &ALT_PROGRAM_ID, &table_bytes(&full, 1, 0, u64::MAX)).unwrap();
        let wanted: Vec<Address> = (0..30u8).map(|i| Address::new_from_array([i; 32])).collect();
        let need = missing(std::slice::from_ref(&t), &wanted);
        assert_eq!(need.len(), 29, "[0;32] is already in the table");
        let (plan, overflow) = plan_extends(&[t], need);
        assert_eq!(plan.len(), 1);
        assert_eq!(plan[0].1.len(), 6);
        assert_eq!(overflow.len(), 23);
    }

    #[test]
    fn instruction_encodings() {
        let auth = Address::new_from_array([1; 32]);
        let (ix, table) = create_table_ix(&auth, &auth, 1234);
        assert_eq!(table, derive_table_address(&auth, 1234).0);
        assert_eq!(&ix.data[..4], &[0, 0, 0, 0]);
        assert_eq!(&ix.data[4..12], &1234u64.to_le_bytes());
        assert_eq!(ix.data.len(), 13);
        let e = extend_table_ix(&table, &auth, &auth, &[auth, table]);
        assert_eq!(&e.data[..4], &[2, 0, 0, 0]);
        assert_eq!(&e.data[4..12], &2u64.to_le_bytes());
        assert_eq!(e.data.len(), 12 + 64);
        assert!(e.accounts[1].is_signer);
    }
}
