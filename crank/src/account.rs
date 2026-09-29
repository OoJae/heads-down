//! A fetched account, independent of where it came from (RPC, WebSocket, LiteSVM).

use solana_address::Address;

/// Owner, lamports and data of one account.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RawAccount {
    /// Owning program.
    pub owner: Address,
    /// Balance.
    pub lamports: u64,
    /// Account data.
    pub data: Vec<u8>,
}

impl RawAccount {
    /// A closed account as RPC reports it: System-owned, empty.
    pub fn is_closed(&self) -> bool {
        self.lamports == 0 && self.data.is_empty()
    }
}
