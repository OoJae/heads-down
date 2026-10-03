package xyz.headsdown.core.chain

/**
 * The wallet this phone's rig belongs to, known once a clock-in confirmed on-chain. Screens use
 * it to **read** the chain for that wallet before anything is signed (balances, a table seat, a
 * bond). It is never what authorizes a transaction: every signing session asks the wallet app
 * which account it is, and builds for that one.
 */
fun interface BoundWallet {
    /** null: no clock-in has confirmed on this phone yet. */
    fun authority(): Pubkey?
}
