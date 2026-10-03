package xyz.headsdown.core.chain.ix

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Golden
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.arr
import xyz.headsdown.core.chain.obj
import xyz.headsdown.core.chain.pubkey
import xyz.headsdown.core.chain.str
import xyz.headsdown.core.chain.tx.AccountMeta
import xyz.headsdown.core.chain.u64

/**
 * What the golden vectors cannot show about the v1.2 builders: the named PDA helpers, the paths
 * no vector executes from the phone's side (a remote join, a join above the guest cap), and the
 * values the builders refuse to encode.
 */
class SkrInstructionsTest {

    private val vectors = Golden.vectors

    private fun role(name: String, role: String): Pubkey =
        vectors.getValue(name).arr("accounts").map { it.jsonObject }.single { it.str("role") == role }.pubkey("pubkey")

    private fun args(name: String): JsonObject = vectors.getValue(name).obj("args")

    @Test
    fun `the named v1_2 PDA helpers derive the accounts the vectors executed with`() {
        val host = role("open_stack", "host")
        val table = HeadsDownProgram.stackTable(host, args("open_stack").u64("table_id")).address
        assertEquals(role("open_stack", "stack_table"), table)
        assertEquals(role("open_stack", "table_skr_vault"), HeadsDownProgram.skrVault(table))
        val rig = HeadsDownProgram.rig(host).address
        assertEquals(role("join_stack", "rig"), rig)
        assertEquals(role("join_stack", "stack_seat"), HeadsDownProgram.stackSeat(table, rig).address)
        assertEquals(role("join_stack", "authority_skr"), Skr.account(host))

        val kai = role("lock_focus_bond", "authority")
        val kaiRig = HeadsDownProgram.rig(kai).address
        val bond = HeadsDownProgram.focusBond(kaiRig, 1uL).address
        assertEquals(role("lock_focus_bond", "focus_bond"), bond)
        assertEquals(role("lock_focus_bond", "bond_skr_vault"), HeadsDownProgram.skrVault(bond))
        assertEquals(role("lock_focus_bond", "shift_log"), HeadsDownProgram.shiftLog(kaiRig, 1uL).address)

        val sender = role("create_gift_wallet", "sender")
        assertEquals(role("create_gift_wallet", "gift_escrow"), HeadsDownProgram.giftEscrow(sender, 1uL).address)
        assertEquals(role("create_gift_sgt", "gift_escrow"), HeadsDownProgram.giftEscrow(sender, 2uL).address)
        assertEquals(role("refund_gift", "gift_escrow"), HeadsDownProgram.giftEscrow(sender, 3uL).address)

        assertEquals(role("forfeit_focus_bond", "bury_vault"), HeadsDownProgram.buryVault.address)
        assertEquals(role("forfeit_focus_bond", "bury_skr_vault"), HeadsDownProgram.skrVault(HeadsDownProgram.buryVault.address))
    }

    @Test
    fun `a remote join seats the SGT mint and passes the SGT accounts last`() {
        val authority = role("join_stack", "authority")
        val table = role("join_stack", "stack_table")
        val sgt = SgtAccounts(role("claim_gift_sgt", "sgt_token_account"), role("claim_gift_sgt", "sgt_mint"))
        val ix = SkrInstructions.joinStack(authority, table, remote = true, sgt = sgt)
        assertEquals("10", ix.data.joinToString("") { "%02x".format(it) })
        assertEquals(11, ix.accounts.size)
        // The seat is ["stackseat", table, sgt_mint]: one seat per Seeker, not per rig.
        assertEquals(AccountMeta.writable(HeadsDownProgram.stackSeat(table, sgt.mint).address), ix.accounts[3])
        assertEquals(listOf(AccountMeta.readonly(sgt.tokenAccount), AccountMeta.readonly(sgt.mint)), ix.accounts.takeLast(2))
        // The first nine accounts are the in-person ones, except the seat.
        val inPerson = SkrInstructions.joinStack(authority, table, remote = false)
        assertEquals(inPerson.accounts.filterIndexed { i, _ -> i != 3 }, ix.accounts.take(9).filterIndexed { i, _ -> i != 3 })
        assertThrows(IllegalArgumentException::class.java) { SkrInstructions.joinStack(authority, table, remote = true, sgt = null) }
    }

    @Test
    fun `an in-person join above the guest cap keeps the rig seat and adds the SGT accounts`() {
        val authority = role("join_stack", "authority")
        val table = role("join_stack", "stack_table")
        val sgt = SgtAccounts(role("claim_gift_sgt", "sgt_token_account"), role("claim_gift_sgt", "sgt_mint"))
        val ix = SkrInstructions.joinStack(authority, table, remote = false, sgt = sgt)
        assertEquals(role("join_stack", "stack_seat"), ix.accounts[3].pubkey)
        assertEquals(11, ix.accounts.size)
        assertEquals(WellKnown.SPL_TOKEN, ix.accounts[7].pubkey)
    }

    private fun params(
        bond: ULong = 200uL * Skr.ONE_SKR,
        start: ULong = 100uL,
        end: ULong = 109uL,
        grace: Long = 2,
        flags: Int = 0,
        seats: Int = 4,
    ) = StackParams(1uL, bond, start, end, grace, flags, seats)

    @Test
    fun `stack parameters the program would refuse are never encoded`() {
        assertEquals(10uL, params().rounds)
        // Flags: only bits 0..2.
        assertThrows(IllegalArgumentException::class.java) { params(flags = 0x08) }
        assertTrue(params(flags = StackFlags.REMOTE or StackFlags.BURY_ONLY).remote)
        assertTrue(params(flags = StackFlags.BURY_ONLY).buryOnly)
        assertFalse(params().remote)
        // Seats 2..8.
        assertThrows(IllegalArgumentException::class.java) { params(seats = 1) }
        assertThrows(IllegalArgumentException::class.java) { params(seats = 9) }
        // Bond 1 base unit up to the cap: 2,000 SKR in person, 1,000 SKR remote.
        assertThrows(IllegalArgumentException::class.java) { params(bond = 0uL) }
        params(bond = Skr.STACK_BOND_CAP)
        assertThrows(IllegalArgumentException::class.java) { params(bond = Skr.STACK_BOND_CAP + 1uL) }
        params(bond = Skr.REMOTE_BOND_CAP, flags = StackFlags.REMOTE)
        assertThrows(IllegalArgumentException::class.java) { params(bond = Skr.REMOTE_BOND_CAP + 1uL, flags = StackFlags.REMOTE) }
        // The window: not empty, at most 1,440 rounds, grace below its length.
        assertThrows(IllegalArgumentException::class.java) { params(start = 110uL, end = 109uL) }
        params(start = 1uL, end = 1_440uL, grace = 0)
        assertThrows(IllegalArgumentException::class.java) { params(start = 1uL, end = 1_441uL, grace = 0) }
        params(grace = 9)
        assertThrows(IllegalArgumentException::class.java) { params(grace = 10) }
        assertThrows(IllegalArgumentException::class.java) { params(grace = -1) }
        // Against the live round: the window starts later, within 10,080 rounds.
        assertTrue(params().admits(99uL))
        assertFalse(params().admits(100uL))
        assertTrue(params(start = 10_180uL, end = 10_180uL, grace = 0).admits(100uL))
        assertFalse(params(start = 10_181uL, end = 10_181uL, grace = 0).admits(100uL))
    }

    @Test
    fun `a focus bond is 1 base unit to 5,000 SKR on a real shift`() {
        val authority = role("lock_focus_bond", "authority")
        SkrInstructions.lockFocusBond(authority, 1uL, 1uL)
        SkrInstructions.lockFocusBond(authority, 1uL, Skr.FOCUS_BOND_CAP)
        assertThrows(IllegalArgumentException::class.java) { SkrInstructions.lockFocusBond(authority, 1uL, 0uL) }
        assertThrows(IllegalArgumentException::class.java) { SkrInstructions.lockFocusBond(authority, 1uL, Skr.FOCUS_BOND_CAP + 1uL) }
        // shift_id 0 is "never armed": there is no shift to bond.
        assertThrows(IllegalArgumentException::class.java) { SkrInstructions.lockFocusBond(authority, 0uL, 1uL) }
    }

    @Test
    fun `a gift needs a recipient and 1 lamport to 10 SOL`() {
        val sender = role("create_gift_wallet", "sender")
        val to = role("claim_gift_wallet", "claimer")
        SkrInstructions.createGift(sender, 9uL, GiftRecipientKind.WALLET, to, 1uL)
        SkrInstructions.createGift(sender, 9uL, GiftRecipientKind.SGT_MINT, to, 10_000_000_000uL)
        assertThrows(IllegalArgumentException::class.java) { SkrInstructions.createGift(sender, 9uL, GiftRecipientKind.WALLET, to, 0uL) }
        assertThrows(IllegalArgumentException::class.java) { SkrInstructions.createGift(sender, 9uL, GiftRecipientKind.WALLET, to, 10_000_000_001uL) }
        assertThrows(IllegalArgumentException::class.java) { SkrInstructions.createGift(sender, 9uL, GiftRecipientKind.WALLET, Pubkey.DEFAULT, 5uL) }
        assertEquals(GiftRecipientKind.SGT_MINT, GiftRecipientKind.fromWire(1))
        assertThrows(IllegalArgumentException::class.java) { GiftRecipientKind.fromWire(2) }
    }

    @Test
    fun `the generic ATA companion is CreateIdempotent for any owner, mint and token program`() {
        val payer = role("create_gift_wallet", "sender")
        val mint = role("claim_gift_sgt", "sgt_mint")
        val holder = role("claim_gift_sgt", "claimer")
        // The vector's SGT token account is the claimer's Token-2022 ATA for that mint.
        val ix = AssociatedTokenInstructions.createIdempotent(payer, holder, mint, WellKnown.TOKEN_2022)
        assertEquals(role("claim_gift_sgt", "sgt_token_account"), ix.accounts[1].pubkey)
        assertEquals(AccountMeta.readonly(WellKnown.TOKEN_2022), ix.accounts[5])
        assertEquals(1, ix.dataSize)
    }
}
