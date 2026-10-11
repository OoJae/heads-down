package xyz.headsdown.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.components.HdHaptics
import xyz.headsdown.core.design.components.HeroSpec
import xyz.headsdown.surface.haptics.HapticCue
import xyz.headsdown.surface.haptics.HapticMoment
import xyz.headsdown.surface.haptics.Haptics
import xyz.headsdown.ui.slab.SlabHero
import xyz.headsdown.ui.slab.SlabPalette
import xyz.headsdown.core.design.components.SlabState as HeroState
import xyz.headsdown.ui.slab.SlabState as SlabHeroState

/**
 * The live hero behind the design module's hero slot: the 3D slab, in the palette the theme is
 * showing. Provided once, in MainActivity; a screen composed without it (a test, a preview)
 * keeps the design module's still slab and touches no shader and no sensor.
 */
val LiveHero: @Composable (HeroSpec, Modifier) -> Unit = { spec, modifier ->
    SlabHero(
        state = spec.state.slab(),
        heat = spec.heat,
        modifier = modifier,
        palette = if (Hd.colors.isDark) SlabPalette.Dark else SlabPalette.Light,
        scrollPx = spec.scrollPx,
        interactive = spec.interactive,
        enter = spec.enter,
        onFlipped = spec.onFlipped,
        onKnock = spec.onKnock,
    )
}

private fun HeroState.slab(): SlabHeroState = when (this) {
    HeroState.Cold -> SlabHeroState.Cold
    HeroState.Armed -> SlabHeroState.Armed
    HeroState.Hot -> SlabHeroState.Hot
    HeroState.Cooling -> SlabHeroState.Cooling
    HeroState.Broken -> SlabHeroState.Broken
    HeroState.Frozen -> SlabHeroState.Frozen
}

/**
 * The phone's motor behind the screens' four gestures. While a shift is running every cue is
 * handed to the governor as a shift moment, which refuses it: a buzz shakes the accelerometer
 * the shift depends on. There is no cue for a refusal yet, so that one stays silent.
 */
class AppHaptics(private val haptics: Haptics, private val shiftRunning: () -> Boolean) : HdHaptics {
    private fun play(cue: HapticCue) {
        haptics.play(cue, if (shiftRunning()) HapticMoment.SHIFT else HapticMoment.FOREGROUND)
    }

    override fun knock() = play(HapticCue.UI_KNOCK)

    override fun snap() = play(HapticCue.UI_SNAP)

    override fun confirm() = play(HapticCue.UI_CONFIRM)

    override fun refuse() = Unit
}
