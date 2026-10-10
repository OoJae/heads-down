package xyz.headsdown.core.design.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the interface may ask of the phone's motor. Four cues and no others; what each one feels
 * like, and whether it plays at all (silent mode, a basic motor, the per-night limits), is the
 * haptics module's business.
 */
interface HdHaptics {
    /** A knuckle on the slab. */
    fun knock()

    /** Something clicked into place: the slab turned over, a choice was made. */
    fun snap()

    /** It went through. */
    fun confirm()

    /** It did not, or it cannot. */
    fun refuse()

    /** Does nothing. The default, so previews and unit tests never touch a Vibrator. */
    object None : HdHaptics {
        override fun knock() = Unit
        override fun snap() = Unit
        override fun confirm() = Unit
        override fun refuse() = Unit
    }
}

/**
 * The haptics in the composition. Silent unless the app provides the real ones at its root. The
 * components in this module do not call it themselves: a screen decides which of its moments
 * deserve a cue.
 */
val LocalHdHaptics = staticCompositionLocalOf<HdHaptics> { HdHaptics.None }
