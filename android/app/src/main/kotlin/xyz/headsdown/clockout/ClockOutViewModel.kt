package xyz.headsdown.clockout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.clockout.BondOutcome
import xyz.headsdown.core.chain.clockout.ClockOutRefusedException
import xyz.headsdown.core.chain.clockout.ClockOutRequest
import xyz.headsdown.core.chain.clockout.PreparedClockOut
import xyz.headsdown.core.chain.clockout.ShiftOutcome
import xyz.headsdown.core.wallet.ConfirmationOutcome
import xyz.headsdown.core.wallet.WalletAccount
import xyz.headsdown.core.wallet.WalletCapabilities
import xyz.headsdown.core.wallet.WalletResult
import xyz.headsdown.core.wallet.WalletSession
import xyz.headsdown.feature.shift.ShiftController
import xyz.headsdown.rig.RigBindingStore
import javax.inject.Inject

sealed interface ClockOutState {
    data object Loading : ClockOutState

    /** No clock-in has confirmed on this phone yet: there is no rig to clock out. */
    data object NoRig : ClockOutState

    /** The chain could not be read. Nothing was sent. */
    data class Unavailable(val message: String) : ClockOutState

    data class Ready(
        /** What the default clock-out does (keep the ORE, end nothing early). */
        val facts: ClockOutFacts,
        /** False keeps the ORE in the Miner (the default); true claims all of it. */
        val claimAll: Boolean = false,
        /** The user chose to end a shift that is still inside its window. Never the default. */
        val endEarly: Boolean = false,
        /** The wallet is open or the transaction is being confirmed. */
        val working: Boolean = false,
        /** Why the last attempt changed nothing, in fixed words. */
        val problem: String? = null,
        /** The buy on offer and what the user chose of it. Off unless they turn it on. */
        val buy: BuyChoice = BuyChoice(),
    ) : ClockOutState {
        /** The facts as the current choices make them: what the screen states and the wallet signs. */
        val shown: ClockOutFacts get() = if (endEarly) facts.endedEarly() else facts

        /** A buy is signed only for a quote the sheet is showing; while one is being read, nothing is. */
        val canSign: Boolean get() = !buy.quoting && (facts.somethingToSign(claimAll, endEarly) || buy.quote != null)
    }

    /** Confirmed on-chain with no error. [signature] is the clock-out's own transaction, for a link to it. */
    data class Done(val message: String, val signature: String? = null) : ClockOutState
}

/**
 * "Buy ORE" on the clock-out sheet. [offers] are the spends on offer (empty: this build cannot
 * buy, and the sheet has no such row). [spendLamports] 0 is off, which is where it starts.
 */
data class BuyChoice(
    val offers: List<ULong> = emptyList(),
    val spendLamports: ULong = 0uL,
    /** A quote for [spendLamports] is being read. */
    val quoting: Boolean = false,
    /** The quote the sheet states; the wallet is asked for exactly this spend and this minimum. */
    val quote: BuyQuote? = null,
    /** The market gave no quote for [spendLamports]: nothing will be bought. */
    val noQuote: Boolean = false,
) {
    val available: Boolean get() = offers.isNotEmpty()

    companion object {
        /** Small and fixed, like the digs: 0.001, 0.002 and 0.005 SOL. */
        val OFFERS: List<ULong> = listOf(1_000_000uL, 2_000_000uL, 5_000_000uL)
    }
}

/** One wallet session: authorize, build inside it, sign and send, confirm. Supplied by the Activity. */
typealias ClockOutSigner = suspend (
    prepare: suspend (WalletAccount, WalletCapabilities) -> PreparedClockOut?,
) -> WalletResult<WalletSession<PreparedClockOut>>

/**
 * The clock-out screen's logic. It states what the transaction will do before the wallet opens
 * (a read-only preview), builds the transaction inside the wallet session from a fresh read, and
 * reports success only when every signature confirmed with no error.
 */
class ClockOutModel(
    private val chain: ClockOutChain,
    private val authority: () -> Pubkey?,
    private val scope: CoroutineScope,
    /** Called once a clock-out that sealed the shift is confirmed: the phone stops signing for it. */
    private val onShiftSealed: () -> Unit = {},
) {
    private val _state = MutableStateFlow<ClockOutState>(ClockOutState.Loading)
    val state: StateFlow<ClockOutState> = _state.asStateFlow()
    private var quoting: Job? = null

    fun load() {
        val wallet = authority()
        if (wallet == null) {
            _state.value = ClockOutState.NoRig
            return
        }
        _state.value = ClockOutState.Loading
        scope.launch {
            _state.value = try {
                ClockOutState.Ready(chain.preview(wallet), buy = BuyChoice(offers = if (chain.buyAvailable) BuyChoice.OFFERS else emptyList()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The text of a network or decode error is not ours to show.
                ClockOutState.Unavailable(UNREADABLE)
            }
        }
    }

    fun setClaimAll(claimAll: Boolean) = choose { it.copy(claimAll = claimAll && it.facts.canClaim) }

    fun setEndEarly(endEarly: Boolean) = choose { it.copy(endEarly = endEarly && it.facts.canEndEarly) }

    /**
     * Chooses how much SOL to spend on ORE (0: none), and reads the quote the sheet then states.
     * Read-only: nothing is signed here, and without a quote there is no buy.
     */
    fun setBuy(spendLamports: ULong) {
        val ready = _state.value as? ClockOutState.Ready ?: return
        if (ready.working || !ready.buy.available || (spendLamports != 0uL && spendLamports !in ready.buy.offers)) return
        quoting?.cancel()
        if (spendLamports == 0uL) {
            _state.value = ready.copy(buy = BuyChoice(ready.buy.offers), problem = null)
            return
        }
        _state.value = ready.copy(buy = BuyChoice(ready.buy.offers, spendLamports, quoting = true), problem = null)
        quoting = scope.launch {
            val quote = try {
                chain.quoteBuy(spendLamports)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Still the choice this quote was read for: the user may have changed it, or signed.
            val now = _state.value as? ClockOutState.Ready ?: return@launch
            if (now.working || now.buy.spendLamports != spendLamports) return@launch
            _state.value = now.copy(buy = now.buy.copy(quoting = false, quote = quote, noQuote = quote == null))
        }
    }

    private fun choose(change: (ClockOutState.Ready) -> ClockOutState.Ready) {
        val ready = _state.value as? ClockOutState.Ready ?: return
        if (!ready.working) _state.value = change(ready).copy(problem = null)
    }

    fun confirm(sign: ClockOutSigner) {
        val ready = _state.value as? ClockOutState.Ready ?: return
        val wallet = authority() ?: return
        if (ready.working || !ready.canSign) return
        val request = ClockOutRequest(
            claimOreBps = if (ready.claimAll) CLAIM_ALL_BPS else 0,
            endShiftEarly = ready.endEarly,
            // Given only when the screen stated the forfeit beside the choice that causes it.
            acceptBondForfeit = ready.endEarly && ready.facts.bond is BondOutcome.StaysLocked,
            // The spend and the minimum the sheet stated: a fresh quote that guarantees less buys nothing.
            buy = ready.buy.quote?.leg(),
        )
        _state.value = ready.copy(working = true, problem = null)
        scope.launch {
            var refusal: String? = null
            val result = try {
                sign { account, capabilities ->
                    // The preview was read for the rig's own wallet: another wallet has nothing to sign here.
                    if (!wallet.contentEquals(account.publicKey)) {
                        refusal = OTHER_WALLET
                        throw IllegalStateException("another wallet")
                    }
                    try {
                        chain.prepare(wallet, request, capabilities)
                    } catch (e: ClockOutRefusedException) {
                        refusal = e.reason.message + " Nothing was sent."
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                WalletResult.Failed(FAILED)
            }
            _state.value = when (result) {
                WalletResult.NoWalletInstalled -> ready.copy(problem = NO_WALLET)
                // A refusal by our own composer says why (it is fixed text); anything else is the wallet's.
                is WalletResult.Failed -> ready.copy(problem = refusal ?: "Wallet: ${result.reason}")
                is WalletResult.Success -> when (val session = result.value) {
                    is WalletSession.NothingToSign -> ready.copy(problem = if (request.buy != null) NOTHING_BOUGHT else NOTHING_LEFT)
                    is WalletSession.Submitted ->
                        if (session.report.allConfirmed) {
                            val plan = session.prepared.plan
                            if (plan.shift is ShiftOutcome.Ends) runCatching(onShiftSealed)
                            ClockOutState.Done(ClockOutCopy.done(plan, session.prepared.buy), session.report.outcomes.firstOrNull()?.signature)
                        } else {
                            // Failed or expired is known: nothing happened. Anything else is unknown, and is said so.
                            val outcomes = session.report.outcomes
                            val known = outcomes.isNotEmpty() &&
                                outcomes.all { it is ConfirmationOutcome.FailedOnChain || it is ConfirmationOutcome.Expired }
                            ready.copy(problem = if (known) NOT_LANDED else NOT_CONFIRMED)
                        }
                }
            }
        }
    }

    companion object {
        const val CLAIM_ALL_BPS = 10_000
        const val UNREADABLE = "Could not read the chain. Nothing was sent. Check the connection and try again."
        const val OTHER_WALLET = "That is not the wallet this rig belongs to. Nothing was sent."
        const val NO_WALLET = "No Solana wallet app was found on this phone. Install Solflare, Phantom or another Mobile Wallet Adapter wallet."
        const val NOTHING_LEFT = "There was nothing left to sign: the chain already shows it done."
        const val NOTHING_BOUGHT = "No ORE could be bought just now, and there was nothing else to sign. Nothing was sent."
        const val NOT_LANDED = "The clock-out did not go through on-chain. Nothing changed."
        const val NOT_CONFIRMED = "The clock-out could not be confirmed on-chain. Check your wallet's activity before trying again."
        const val FAILED = "Wallet request failed"
    }
}

@HiltViewModel
class ClockOutViewModel @Inject constructor(
    chain: ClockOutChain,
    binding: RigBindingStore,
    shifts: ShiftController,
) : ViewModel() {
    val model = ClockOutModel(chain, binding::authority, viewModelScope, onShiftSealed = shifts::end)

    init {
        model.load()
    }
}
