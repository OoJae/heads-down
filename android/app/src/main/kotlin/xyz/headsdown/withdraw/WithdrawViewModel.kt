package xyz.headsdown.withdraw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.withdraw.PreparedWithdraw
import xyz.headsdown.core.chain.withdraw.WithdrawRefusedException
import xyz.headsdown.core.chain.withdraw.WithdrawRequest
import xyz.headsdown.core.wallet.ConfirmationOutcome
import xyz.headsdown.core.wallet.WalletAccount
import xyz.headsdown.core.wallet.WalletCapabilities
import xyz.headsdown.core.wallet.WalletResult
import xyz.headsdown.core.wallet.WalletSession
import xyz.headsdown.rig.RigBindingStore
import javax.inject.Inject

sealed interface WithdrawState {
    data object Loading : WithdrawState

    /**
     * No wallet is bound to this phone (a fresh install, or the rig was closed): the wallet is
     * asked for its address first. Nothing is signed by connecting.
     */
    data class NeedsWallet(val working: Boolean = false, val problem: String? = null) : WithdrawState

    /** The chain could not be read. Nothing was sent. */
    data class Unavailable(val message: String) : WithdrawState

    data class Ready(
        val facts: WithdrawFacts,
        /** Close the ORE Automation and take its SOL back. Never on by default. */
        val revoke: Boolean = false,
        /** Close the rig. Never on by default. */
        val closeRig: Boolean = false,
        /** The wallet is open or the transaction is being confirmed. */
        val working: Boolean = false,
        /** Why the last attempt changed nothing, in fixed words. */
        val problem: String? = null,
    ) : WithdrawState {
        val canSign: Boolean get() = facts.somethingToSign(revoke, closeRig)
    }

    /** Confirmed on-chain with no error. */
    data class Done(val message: String) : WithdrawState
}

/** One wallet session: authorize, build inside it, sign and send, confirm. Supplied by the Activity. */
typealias WithdrawSigner = suspend (
    prepare: suspend (WalletAccount, WalletCapabilities) -> PreparedWithdraw?,
) -> WalletResult<WalletSession<PreparedWithdraw>>

/**
 * The withdraw screen's logic: what the wallet's ORE Automation holds and whether its rig can be
 * closed (a read-only preview), then one transaction built inside the wallet session from a
 * fresh read. Success is reported only when the signature confirmed with no error.
 *
 * It works without a rig bound to this phone: the wallet is asked for its address, so SOL left in
 * an Automation can be taken back after a reinstall or from another phone.
 */
class WithdrawModel(
    private val chain: WithdrawChain,
    private val boundAuthority: () -> Pubkey?,
    private val scope: CoroutineScope,
    /** Called once a withdrawal that closed [authority]'s rig is confirmed. */
    private val onRigClosed: (authority: Pubkey) -> Unit = {},
) {
    private val _state = MutableStateFlow<WithdrawState>(WithdrawState.Loading)
    val state: StateFlow<WithdrawState> = _state.asStateFlow()

    /** The wallet the preview on screen was read for. */
    private var authority: Pubkey? = null

    fun load() {
        val wallet = authority ?: boundAuthority()
        if (wallet == null) {
            _state.value = WithdrawState.NeedsWallet()
            return
        }
        authority = wallet
        _state.value = WithdrawState.Loading
        scope.launch {
            _state.value = try {
                WithdrawState.Ready(chain.preview(wallet))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The text of a network or decode error is not ours to show.
                WithdrawState.Unavailable(UNREADABLE)
            }
        }
    }

    /** Asks the wallet which account it is, then reads what that account could take back. */
    fun connect(ask: suspend () -> WalletResult<WalletAccount>) {
        val waiting = _state.value as? WithdrawState.NeedsWallet ?: return
        if (waiting.working) return
        _state.value = WithdrawState.NeedsWallet(working = true)
        scope.launch {
            val result = try {
                ask()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                WalletResult.Failed(FAILED)
            }
            when (result) {
                WalletResult.NoWalletInstalled -> _state.value = WithdrawState.NeedsWallet(problem = NO_WALLET)
                is WalletResult.Failed -> _state.value = WithdrawState.NeedsWallet(problem = "Wallet: ${result.reason}")
                is WalletResult.Success -> {
                    val key = runCatching { Pubkey(result.value.publicKey) }.getOrNull()
                    if (key == null) {
                        _state.value = WithdrawState.NeedsWallet(problem = "Wallet: $FAILED")
                    } else {
                        authority = key
                        load()
                    }
                }
            }
        }
    }

    fun setRevoke(revoke: Boolean) = choose { it.copy(revoke = revoke && it.facts.canRevoke) }

    fun setCloseRig(closeRig: Boolean) = choose { it.copy(closeRig = closeRig && it.facts.canCloseRig) }

    private fun choose(change: (WithdrawState.Ready) -> WithdrawState.Ready) {
        val ready = _state.value as? WithdrawState.Ready ?: return
        if (!ready.working) _state.value = change(ready).copy(problem = null)
    }

    fun confirm(sign: WithdrawSigner) {
        val ready = _state.value as? WithdrawState.Ready ?: return
        val wallet = authority ?: return
        if (ready.working || !ready.canSign) return
        val request = WithdrawRequest(revoke = ready.revoke, closeRig = ready.closeRig)
        _state.value = ready.copy(working = true, problem = null)
        scope.launch {
            var refusal: String? = null
            val result = try {
                sign { account, capabilities ->
                    // The preview was read for one wallet: another one has nothing to sign here.
                    if (!wallet.contentEquals(account.publicKey)) {
                        refusal = OTHER_WALLET
                        throw IllegalStateException("another wallet")
                    }
                    try {
                        chain.prepare(wallet, request, capabilities)
                    } catch (e: WithdrawRefusedException) {
                        refusal = e.reason + " Nothing was sent."
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
                    is WalletSession.NothingToSign -> ready.copy(problem = NOTHING_LEFT)
                    is WalletSession.Submitted ->
                        if (session.report.allConfirmed) {
                            val plan = session.prepared.plan
                            if (plan.rigClose != null) runCatching { onRigClosed(wallet) }
                            WithdrawState.Done(WithdrawCopy.done(plan))
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
        const val UNREADABLE = "Could not read the chain. Nothing was sent. Check the connection and try again."
        const val OTHER_WALLET = "That is not the wallet this screen was read for. Nothing was sent."
        const val NO_WALLET = "No Solana wallet app was found on this phone. Install Solflare, Phantom or another Mobile Wallet Adapter wallet."
        const val NOTHING_LEFT = "There was nothing left to sign: the chain already shows it done."
        const val NOT_LANDED = "The transaction did not go through on-chain. Nothing changed."
        const val NOT_CONFIRMED = "The transaction could not be confirmed on-chain. Check your wallet's activity before trying again."
        const val FAILED = "Wallet request failed"
    }
}

@HiltViewModel
class WithdrawViewModel @Inject constructor(
    chain: WithdrawChain,
    binding: RigBindingStore,
) : ViewModel() {
    val model = WithdrawModel(
        chain, binding::authority, viewModelScope,
        // The phone stays bound to a rig only while that rig exists.
        onRigClosed = { closed -> if (binding.authority() == closed) binding.clear() },
    )

    init {
        model.load()
    }
}
