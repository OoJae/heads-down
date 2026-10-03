package xyz.headsdown.core.chain.accounts

import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.chain.tx.AddressLookupTable

/**
 * Decoder for Address Lookup Table accounts (the swap provider's routes need them: a real
 * Jupiter route has 40 to 65 accounts, which only fit a packet when most are loaded from tables).
 *
 * Layout (`solana-address-lookup-table-interface`, `state.rs`): a 56-byte header
 * `type u32 (1 = LookupTable) | deactivation_slot u64 | last_extended_slot u64 |
 * last_extended_slot_start_index u8 | authority Option<Pubkey> (1 + 32) | padding u16`,
 * then the addresses, 32 bytes each.
 *
 * Checked before any address is used: owned by the lookup-table program, a whole number of
 * addresses, the LookupTable type, and **still active** (`deactivation_slot == u64::MAX`). A
 * table being deactivated is refused: its transaction could stop landing mid-flight.
 */
object AddressLookupTables {
    const val META_SIZE = 56
    private const val TYPE_LOOKUP_TABLE = 1L

    /**
     * @param slot the slot the account was read at, when known. Addresses appended in that very
     *   slot are not usable until the next one (the runtime's warm-up rule), so they are left out;
     *   the compiler then simply keeps those keys in the message.
     */
    fun decode(address: Pubkey, account: AccountInfo, slot: ULong? = null): AddressLookupTable {
        if (account.owner != WellKnown.ADDRESS_LOOKUP_TABLE) throw AccountLayoutException("not owned by the address lookup table program")
        val b = AccountBytes(account.data)
        if (b.size < META_SIZE || (b.size - META_SIZE) % Pubkey.BYTES != 0) throw AccountLayoutException("lookup table has a partial address")
        if (b.u32(0) != TYPE_LOOKUP_TABLE) throw AccountLayoutException("not an initialized lookup table")
        if (b.u64(4) != ULong.MAX_VALUE) throw AccountLayoutException("lookup table is deactivated")
        val count = (b.size - META_SIZE) / Pubkey.BYTES
        if (count > AddressLookupTable.MAX_ADDRESSES) throw AccountLayoutException("lookup table holds more than 256 addresses")
        val lastExtendedSlot = b.u64(12)
        val lastExtendedStart = b.u8(20)
        val active = if (slot != null && lastExtendedSlot >= slot) minOf(count, lastExtendedStart) else count
        return AddressLookupTable(address, List(active) { b.pubkey(META_SIZE + Pubkey.BYTES * it) })
    }
}
