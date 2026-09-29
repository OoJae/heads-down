package xyz.headsdown.surface.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RigNotificationCopyTest {

    private fun all(darkRounds: Int = 55, planned: Int? = 369, since: Long? = 1_000L, focus: Boolean = false) =
        RigPhase.entries.associateWith {
            RigNotificationCopy.from(RigNotificationState(it, darkRounds, planned, since, focus))
        }

    @Test
    fun `only the user-started shift is ongoing and promotable`() {
        val copies = all()
        assertEquals(
            setOf(RigPhase.ARMED, RigPhase.HOT, RigPhase.COOLING),
            copies.filterValues { it.promote }.keys,
        )
        copies.values.forEach { assertEquals("promote implies ongoing", it.promote, it.ongoing) }
    }

    @Test
    fun `no price tickers or currency anywhere`() {
        val banned = listOf("$", "SOL", "USD", "NGN", "price", "earn", "yield", "profit", "₦")
        all().values.forEach { copy ->
            val text = listOfNotNull(copy.title, copy.text, copy.chip).joinToString(" ")
            banned.forEach { word -> assertFalse("'$word' in: $text", text.contains(word, ignoreCase = true)) }
        }
    }

    @Test
    fun `cooling never shows a countdown`() {
        val cooling = all().getValue(RigPhase.COOLING)
        assertFalse(cooling.text.any(Char::isDigit))
        assertFalse(cooling.title.any(Char::isDigit))
        assertFalse(cooling.countUpChronometer)
    }

    @Test
    fun `chronometer is count-up and only while hot`() {
        val copies = all()
        assertEquals(setOf(RigPhase.HOT), copies.filterValues { it.countUpChronometer }.keys)
        assertFalse(all(since = null).getValue(RigPhase.HOT).countUpChronometer)
    }

    @Test
    fun `hot shows rounds dark and planned progress`() {
        val hot = all(darkRounds = 55, planned = 369).getValue(RigPhase.HOT)
        assertEquals("Rig hot", hot.title)
        assertEquals("55 rounds dark", hot.text)
        assertEquals(55, hot.progress)
        assertEquals(369, hot.progressMax)
        assertEquals("hot", hot.chip)
    }

    @Test
    fun `progress is clamped and optional`() {
        val over = all(darkRounds = 500, planned = 369).getValue(RigPhase.HOT)
        assertEquals(369, over.progress)
        val open = all(planned = null).getValue(RigPhase.HOT)
        assertNull(open.progressMax)
        assertNull(open.progress)
        assertNull(all(planned = 0).getValue(RigPhase.HOT).progressMax)
    }

    @Test
    fun `singular round and focus-only suffix`() {
        assertEquals("1 round dark · focus only", all(darkRounds = 1, focus = true).getValue(RigPhase.HOT).text)
        assertTrue(all(darkRounds = -3).getValue(RigPhase.HOT).text.startsWith("0 rounds"))
    }

    @Test
    fun `status chip text stays short`() {
        all().values.mapNotNull { it.chip }.forEach { assertTrue(it, it.length <= 8) }
    }
}
