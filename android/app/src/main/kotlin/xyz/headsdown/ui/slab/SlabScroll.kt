package xyz.headsdown.ui.slab

/**
 * What the page's scroll does to the slab. Everything is a function of p, the scroll distance as
 * a fraction of the hero's own height, and is read in the draw phase only: scrolling invalidates
 * a draw, never a composition.
 *
 * As the hero leaves, the camera drops under the slab, gravity lets go, and the slab shrinks and
 * sinks inside its own bounds so it stays in sight a little longer. Nothing is drawn outside the
 * hero.
 */
object SlabScroll {
    /** The camera is fully under the slab after this much of the hero has scrolled away. */
    const val PITCH_END = 0.6f
    const val PITCH_DEGREES = 70f

    /** Gravity fades between these two. */
    const val GRAVITY_FADE_FROM = 0.5f
    const val GRAVITY_FADE_TO = 0.9f

    /** The slab's size once the hero has scrolled away entirely. */
    const val SCALE_END = 0.6f

    /** How far the slab's centre sinks, as a fraction of the hero's height. */
    const val SINK = 0.2f

    fun progress(scrollPx: Float, heroHeightPx: Float): Float =
        if (heroHeightPx > 0f && scrollPx > 0f) (scrollPx / heroHeightPx).coerceAtMost(1f) else 0f

    /** Added to the pose's upward lean: 0 to [PITCH_DEGREES] over the first [PITCH_END]. */
    fun pitchDegrees(p: Float): Float = PITCH_DEGREES * smooth(p / PITCH_END)

    /** 1 while the hero is in place, 0 once it is mostly gone. */
    fun gravityWeight(p: Float): Float = 1f - smooth((p - GRAVITY_FADE_FROM) / (GRAVITY_FADE_TO - GRAVITY_FADE_FROM))

    fun scale(p: Float): Float = 1f - (1f - SCALE_END) * smooth(p)

    fun sink(p: Float): Float = SINK * smooth(p)

    internal fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
