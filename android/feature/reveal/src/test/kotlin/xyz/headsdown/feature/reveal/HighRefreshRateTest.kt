package xyz.headsdown.feature.reveal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighRefreshRateTest {
    private val m60 = DisplayModeSpec(1, 720, 1640, 60f)
    private val m90 = DisplayModeSpec(2, 720, 1640, 90f)
    private val m120 = DisplayModeSpec(3, 720, 1640, 120f)
    private val hiRes120 = DisplayModeSpec(4, 1080, 2460, 120f)

    @Test
    fun `picks the fastest mode at the current resolution`() {
        assertEquals(m120, HighRefreshRate.pick(m60, listOf(m60, m90, m120, hiRes120)))
        assertEquals(m90, HighRefreshRate.pick(m60, listOf(m60, m90, hiRes120)))
    }

    @Test
    fun `never switches resolution and keeps an already fastest mode`() {
        assertNull(HighRefreshRate.pick(m60, listOf(m60, hiRes120)))
        assertNull(HighRefreshRate.pick(m120, listOf(m60, m90, m120)))
        assertNull(HighRefreshRate.pick(m60, emptyList()))
    }
}
