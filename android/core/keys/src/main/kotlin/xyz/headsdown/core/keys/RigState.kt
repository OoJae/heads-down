package xyz.headsdown.core.keys

/**
 * `Rig.state` (INTERFACE, Rig @75). The byte values are **wire format** shared with the
 * on-chain `heads_down` program; never renumber, only append.
 *
 * Only [ARMED] and [DOWN] rigs can be dug; a DOWN rig is one whose phone holds a live lease.
 */
enum class RigSignalState(val wire: Int) {
    IDLE(0),
    ARMED(1),
    DOWN(2),
    COOLING(3),
    BROKEN(4),
    FROZEN(5),
    ;

    companion object {
        fun fromWire(value: Int): RigSignalState =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown rig state byte $value")
    }
}
