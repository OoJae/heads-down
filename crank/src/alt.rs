//! Address lookup tables for v0 dig transactions.
//!
//! The crank owns one or more tables holding the accounts every dig repeats (heads_down
//! Config and Executor, ORE Board/Config/Treasury, System, ORE program, entropy Var and
//! program, instructions sysvar) plus each registered rig's `rig, authority, automation,
//! miner`. With the table, a rig costs 4 index bytes instead of 128 key bytes.
//!
//! The instructions are built by hand from the Address Lookup Table program's (bincode)
//! wire format; `tests/litesvm_alt.rs` runs them against the real ALT program in LiteSVM.
//!
//! A table locks rent that only comes back when its authority deactivates and closes it by
//! hand. So the crank must never lose track of a table it created, and must not keep sending
//! creates that fail. The parts of that which need no I/O are here: the state file
//! ([`TableState`]), the backoff ([`Retry`]) and the rule for when a read of the tables can
//! be trusted after a transaction whose outcome was not seen ([`settled`]). `crank.rs` does
//! the reads and the transactions, and `tests/lookup_tables.rs` runs them against the real
//! ALT program.

use serde_json::{json, Value};
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
/// The wait after a lookup-table transaction that did not land. It doubles with every
/// further failure in a row.
pub const RETRY_BASE_SECS: i64 = 60;
/// The longest wait between two attempts.
pub const RETRY_MAX_SECS: i64 = 3_600;
/// After a lookup-table transaction whose outcome was not seen, the tables are read again
/// only from a node that is this many blocks past the last block the transaction could land
/// in (a blockhash is valid for 150 blocks). The read also names that node's slot, so the
/// margin only matters with a provider that ignores `minContextSlot`: it then stands in for
/// how far one of its nodes may lag.
pub const SETTLE_MARGIN_BLOCKS: u64 = 150;

/// Account data length of a table that holds `addresses` addresses (what its rent pays for).
pub const fn table_len(addresses: usize) -> usize {
    LOOKUP_TABLE_META_SIZE + 32 * addresses
}

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
    /// A table as `CreateLookupTable` leaves it: active, owned by `authority`, no addresses.
    pub fn empty(key: Address, authority: Address) -> Self {
        LookupTable {
            key,
            deactivation_slot: u64::MAX,
            last_extended_slot: 0,
            last_extended_slot_start_index: 0,
            authority: Some(authority),
            addresses: Vec::new(),
        }
    }

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

/// Backoff after a lookup-table transaction that did not land: [`RETRY_BASE_SECS`], then
/// twice as long after every further failure in a row, at most [`RETRY_MAX_SECS`]. It is kept
/// in the state file, so a restart does not start the count again.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Retry {
    /// Failures in a row.
    pub failures: u32,
    /// No attempt before this unix time.
    pub not_before_unix: i64,
}

impl Retry {
    /// The wait after the `failures`-th failure in a row.
    pub fn delay_secs(failures: u32) -> i64 {
        let doublings = failures.saturating_sub(1).min(16);
        RETRY_BASE_SECS.saturating_mul(1i64 << doublings).min(RETRY_MAX_SECS)
    }

    /// May an attempt be made at `now_unix`?
    pub fn due(&self, now_unix: i64) -> bool {
        now_unix >= self.not_before_unix
    }

    /// An attempt failed at `now_unix`.
    pub fn fail(&mut self, now_unix: i64) {
        self.failures = self.failures.saturating_add(1);
        self.not_before_unix = now_unix.saturating_add(Self::delay_secs(self.failures));
    }

    /// An attempt worked.
    pub fn clear(&mut self) {
        *self = Retry::default();
    }

    /// The value as it holds at `now_unix`, when it is read back from the state file and
    /// each time the running crank looks at it: the wait is never longer than the cap,
    /// whatever the stamp says (the system clock may have been set back since the failure).
    pub fn restored(self, now_unix: i64) -> Self {
        Retry { failures: self.failures, not_before_unix: self.not_before_unix.min(now_unix.saturating_add(RETRY_MAX_SECS)) }
    }
}

/// May the tables be trusted as a node at `block_height` shows them, when lookup-table
/// transactions whose outcome was not seen can land up to `settle_height` (0: there is
/// none)? Only once that node is [`SETTLE_MARGIN_BLOCKS`] past it: each such transaction has
/// then landed in what the node shows, or it never will.
pub fn settled(block_height: u64, settle_height: u64) -> bool {
    settle_height == 0 || block_height > settle_height.saturating_add(SETTLE_MARGIN_BLOCKS)
}

/// What the crank keeps in `state_dir/lookup_tables.json`:
///
/// ```json
/// {"tables":["<address>"],"pending":["<address>"],"settle_height":0,"min_slot":0,
///  "retry":{"failures":0,"not_before_unix":0}}
/// ```
///
/// Older files hold `tables` only; they still load.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct TableState {
    /// Tables this crank created. The crank never removes an address from the list.
    pub tables: Vec<Address>,
    /// Tables whose create was sent and not seen to land or fail. The address is written
    /// here before the transaction is sent.
    pub pending: Vec<Address>,
    /// The highest last-valid block height of a lookup-table transaction that was sent and
    /// whose outcome was not seen (0: none). See [`settled`].
    pub settle_height: u64,
    /// The last slot one of the crank's lookup-table transactions was seen to land in. A read
    /// of the tables is asked of a node that has processed it.
    pub min_slot: u64,
    /// The backoff after a failed lookup-table transaction.
    pub retry: Retry,
}

impl TableState {
    /// Add a table to the list (once) and drop its pending entry.
    pub fn remember(&mut self, table: Address) {
        self.pending.retain(|p| *p != table);
        if !self.tables.contains(&table) {
            self.tables.push(table);
        }
    }

    /// The file's contents.
    pub fn to_json(&self) -> String {
        let strings = |list: &[Address]| list.iter().map(ToString::to_string).collect::<Vec<_>>();
        json!({
            "tables": strings(&self.tables),
            "pending": strings(&self.pending),
            "settle_height": self.settle_height,
            "min_slot": self.min_slot,
            "retry": { "failures": self.retry.failures, "not_before_unix": self.retry.not_before_unix },
        })
        .to_string()
    }

    /// Parse the file. Anything that is not understood is an error rather than skipped: a
    /// file read as empty would let the crank forget a table and create another.
    pub fn from_json(text: &str) -> Result<Self, String> {
        let v: Value = serde_json::from_str(text).map_err(|e| format!("not JSON ({e})"))?;
        let Value::Object(root) = &v else {
            return Err("not a JSON object".into());
        };
        let addresses = |key: &str| match root.get(key) {
            None => Ok(Vec::new()),
            Some(Value::Array(a)) => a
                .iter()
                .map(|x| x.as_str().and_then(|s| s.parse::<Address>().ok()).ok_or_else(|| format!("an entry of `{key}` is not an address")))
                .collect::<Result<Vec<Address>, String>>(),
            Some(_) => Err(format!("`{key}` is not a list")),
        };
        let number = |key: &str| match root.get(key) {
            None => Ok(0),
            Some(n) => n.as_u64().ok_or_else(|| format!("`{key}` is not a number")),
        };
        let retry = match root.get("retry") {
            None => Retry::default(),
            Some(r) => match (r["failures"].as_u64().and_then(|f| u32::try_from(f).ok()), r["not_before_unix"].as_i64()) {
                (Some(failures), Some(not_before_unix)) => Retry { failures, not_before_unix },
                _ => return Err("`retry` is not understood".into()),
            },
        };
        Ok(TableState {
            tables: addresses("tables")?,
            pending: addresses("pending")?,
            settle_height: number("settle_height")?,
            min_slot: number("min_slot")?,
            retry,
        })
    }
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

    #[test]
    fn table_sizes_and_the_table_a_create_leaves() {
        assert_eq!(table_len(0), 56);
        assert_eq!(table_len(shared_addresses(&hd::PROGRAM_ID).len()), 376, "the table with the 10 shared accounts");
        assert_eq!(table_len(4) - table_len(0), 128, "a rig's four accounts");
        assert_eq!(table_len(LOOKUP_TABLE_MAX_ADDRESSES), 8_248);
        // What the crank holds for a table it just created equals what the chain will show.
        let (key, auth) = (Address::new_from_array([9; 32]), Address::new_from_array([5; 32]));
        let fresh = LookupTable::empty(key, auth);
        assert_eq!(LookupTable::decode(key, &ALT_PROGRAM_ID, &table_bytes(&[], 0, 0, u64::MAX)), Some(fresh.clone()));
        assert_eq!(fresh.free(), LOOKUP_TABLE_MAX_ADDRESSES);
        assert!(fresh.usable(1).addresses.is_empty());
    }

    #[test]
    fn the_backoff_doubles_up_to_the_cap_and_one_attempt_fits_each_wait() {
        assert_eq!((1..=8).map(Retry::delay_secs).collect::<Vec<_>>(), vec![60, 120, 240, 480, 960, 1_920, 3_600, 3_600]);
        assert_eq!(Retry::delay_secs(0), 60);
        assert_eq!(Retry::delay_secs(u32::MAX), RETRY_MAX_SECS, "no overflow");
        let mut r = Retry::default();
        let mut now = 1_790_000_000i64;
        assert!(r.due(now), "nothing failed yet");
        // An attempt is made whenever one is due, and each one fails: the attempts are exactly
        // one wait apart, never closer, however often the crank asks in between.
        let mut attempts = Vec::new();
        for _ in 0..12 * 3_600 {
            if r.due(now) {
                attempts.push(now);
                r.fail(now);
            }
            now += 1;
        }
        let gaps: Vec<i64> = attempts.windows(2).map(|w| w[1] - w[0]).collect();
        assert_eq!(&gaps[..7], &[60, 120, 240, 480, 960, 1_920, 3_600]);
        assert!(gaps[7..].iter().all(|g| *g == RETRY_MAX_SECS), "{gaps:?}");
        // A success starts over.
        r.clear();
        assert_eq!(r, Retry::default());
        assert!(r.due(now));
        // Read back from the state file: the count is kept and the wait is at most the cap.
        let late = Retry { failures: 9, not_before_unix: now + 10 * RETRY_MAX_SECS };
        assert_eq!(late.restored(now), Retry { failures: 9, not_before_unix: now + RETRY_MAX_SECS });
        let soon = Retry { failures: 2, not_before_unix: now + 30 };
        assert_eq!(soon.restored(now), soon);
        assert_eq!(Retry { failures: u32::MAX, not_before_unix: i64::MAX }.restored(i64::MAX).failures, u32::MAX);
    }

    #[test]
    fn a_read_is_trusted_only_well_past_a_transaction_whose_outcome_was_not_seen() {
        assert!(settled(0, 0) && settled(5, 0), "nothing is in doubt");
        assert!(!settled(900, 1_000), "it can still land");
        assert!(!settled(1_001, 1_000), "expired, but a node that lags may not show it yet");
        assert!(!settled(1_000 + SETTLE_MARGIN_BLOCKS, 1_000));
        assert!(settled(1_001 + SETTLE_MARGIN_BLOCKS, 1_000));
        assert!(!settled(u64::MAX, u64::MAX), "no overflow");
    }

    #[test]
    fn the_state_file_round_trips_and_a_damaged_one_is_never_read_as_empty() {
        let a = Address::new_from_array;
        let st = TableState {
            tables: vec![a([1; 32]), a([2; 32])],
            pending: vec![a([3; 32])],
            settle_height: 77,
            min_slot: 453_000_000,
            retry: Retry { failures: 3, not_before_unix: 1_790_000_240 },
        };
        assert_eq!(TableState::from_json(&st.to_json()), Ok(st.clone()));
        assert_eq!(TableState::from_json(&TableState::default().to_json()), Ok(TableState::default()));
        // The file an older crank wrote: tables only.
        let old = format!(r#"{{"tables":["{}","{}"]}}"#, a([1; 32]), a([2; 32]));
        assert_eq!(TableState::from_json(&old), Ok(TableState { tables: st.tables.clone(), ..TableState::default() }));
        assert_eq!(TableState::from_json("{}"), Ok(TableState::default()));
        // A landed create moves from pending to the list, once.
        let mut s = st.clone();
        s.remember(a([3; 32]));
        s.remember(a([3; 32]));
        assert_eq!((s.tables.len(), s.pending.len()), (3, 0));
        assert_eq!(s.tables[0], a([1; 32]), "the order is kept");
        // Damage is an error, not an empty list.
        for bad in [
            "",
            "[]",
            "not json",
            r#"{"tables":"x"}"#,
            r#"{"tables":["not-an-address"]}"#,
            r#"{"tables":[5]}"#,
            r#"{"tables":[],"pending":[{"table":"11111111111111111111111111111111"}]}"#,
            r#"{"tables":[],"pending":{}}"#,
            r#"{"tables":[],"settle_height":"soon"}"#,
            r#"{"tables":[],"min_slot":-4}"#,
            r#"{"tables":[],"retry":{"failures":-1,"not_before_unix":0}}"#,
            r#"{"tables":[],"retry":7}"#,
        ] {
            assert!(TableState::from_json(bad).is_err(), "{bad:?}");
        }
        let cut = &st.to_json()[..40];
        assert!(TableState::from_json(cut).is_err(), "a half-written file: {cut}");
    }
}
