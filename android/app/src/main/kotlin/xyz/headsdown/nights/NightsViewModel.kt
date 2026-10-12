package xyz.headsdown.nights

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
import xyz.headsdown.core.chain.indexer.IndexerShift
import xyz.headsdown.rig.RigBindingStore
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

sealed interface NightsState {
    data object Loading : NightsState

    /** No clock-in has confirmed on this phone yet: there is no rig whose nights could be listed. */
    data object NoRig : NightsState

    /** Neither the chain nor the indexer could be read. */
    data class Unavailable(val message: String) : NightsState

    data class Ready(val facts: NightsFacts) : NightsState
}

/**
 * The night log's logic: two reads for the wallet this phone is bound to, the Rig account from
 * the chain and the finished shifts from the indexer. It signs nothing and sends nothing. Each
 * read may fail by itself, and the screen then shows the half that was read.
 */
class NightsModel(
    private val chain: NightsChain,
    private val boundAuthority: () -> Pubkey?,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val _state = MutableStateFlow<NightsState>(NightsState.Loading)
    val state: StateFlow<NightsState> = _state.asStateFlow()

    fun load() {
        val wallet = boundAuthority()
        if (wallet == null) {
            _state.value = NightsState.NoRig
            return
        }
        _state.value = NightsState.Loading
        scope.launch {
            val rig = read { chain.rig(wallet) }
            val shifts = read { chain.shifts(wallet) }
            _state.value = if (rig.failed && shifts.failed) {
                NightsState.Unavailable(NightsCopy.UNREADABLE)
            } else {
                val now = Instant.ofEpochMilli(nowMillis())
                val here = zone()
                NightsState.Ready(
                    NightsFacts(
                        rig = rig.value,
                        // Newest first is the indexer's order; it is not taken on trust.
                        shifts = shifts.value?.sortedWith(NEWEST_FIRST),
                        today = now.atZone(here).toLocalDate(),
                        zone = here,
                        todayUtcDay = Math.floorDiv(now.epochSecond, DAY_SECONDS),
                        rigProblem = when {
                            rig.failed -> NightsCopy.RIG_UNREADABLE
                            rig.value == null -> NightsCopy.RIG_GONE
                            else -> null
                        },
                        shiftsProblem = when {
                            shifts.failed -> NightsCopy.LIST_UNREADABLE
                            shifts.value == null -> NightsCopy.NO_INDEXER
                            else -> null
                        },
                        incomplete = rig.failed || shifts.failed,
                    ),
                )
            }
        }
    }

    /** What a read gave, or that it failed. The text of a network or decode error is not ours to show. */
    private class Read<T>(val value: T?, val failed: Boolean)

    private suspend fun <T> read(block: suspend () -> T?): Read<T> = try {
        Read(block(), failed = false)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Read(null, failed = true)
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
        val NEWEST_FIRST: Comparator<IndexerShift> = compareByDescending<IndexerShift> { it.endedAt }.thenByDescending { it.shiftId }
    }
}

@HiltViewModel
class NightsViewModel @Inject constructor(
    chain: NightsChain,
    binding: RigBindingStore,
) : ViewModel() {
    val model = NightsModel(chain, binding::authority, viewModelScope)

    init {
        model.load()
    }
}
