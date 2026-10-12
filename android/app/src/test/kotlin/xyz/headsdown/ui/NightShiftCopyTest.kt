package xyz.headsdown.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.feature.reveal.haul.HonestCopy

/** The ritual's words say what the phone does today: how often it signs, and what the alarm opens. */
class NightShiftCopyTest {

    private fun step(title: String) = NightShiftCopy.STEPS.single { it.title == title }.body

    @Test
    fun `a heartbeat is about once a minute, and no step gives a count of seconds for it`() {
        assertEquals(
            "Lay the phone face-down on the charger. While it stays dark, it signs a heartbeat about once " +
                "a minute with a key that never leaves the phone's Android Keystore.",
            step("Heads down"),
        )
        assertFalse(NightShiftCopy.allText.any { "78" in it })
    }

    @Test
    fun `the alarm opens a page that is not the haul, and the haul comes after the clock-out`() {
        val morning = step("Morning haul")
        assertEquals(
            "After your alarm a page opens: the shift is over and not sealed yet. Clock out there and the haul " +
                "appears: what the rig dug, what you paid per ORE, and the market price next to it.",
            morning,
        )
        assertFalse("alarm opens the haul" in morning)
        // "Not sealed yet" comes before the haul, and the clock-out between them.
        assertTrue(morning.indexOf("not sealed yet") < morning.indexOf("Clock out"))
        assertTrue(morning.indexOf("Clock out") < morning.indexOf("the haul"))
        assertEquals("two sentences at most", 2, morning.split(". ").size)
        assertTrue(HonestCopy.violations(morning).isEmpty())
    }
}
