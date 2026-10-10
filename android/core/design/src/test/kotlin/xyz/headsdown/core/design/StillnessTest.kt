package xyz.headsdown.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A still screen draws nothing. No source of this module, of the app or of the reveal asks for an
 * animation that never ends, and nothing in this module can reach a shader, a sensor, a motor or
 * the log: its components are composed in unit tests and previews with nothing behind them.
 */
class StillnessTest {

    private val design = SourceTree.module
    private val app = File(SourceTree.android, "app")
    private val reveal = File(SourceTree.android, "feature/reveal")

    /** Compose's two ways to animate forever. */
    private val endless = listOf("rememberInfiniteTransition", "infiniteRepeatable", "InfiniteRepeatableSpec")

    /** A hand-written frame loop: allowed where one is the point, and it ends. */
    private val frameLoop = listOf("withFrameNanos", "withFrameMillis")

    /**
     * KNOWN EXCEPTION, for the next stage to remove: the home screen's `emberBreath()` runs an
     * infinite transition around the rig card while the rig is hot. When it goes, this test fails
     * until the entry is deleted, so the list cannot outlive what it excuses.
     */
    private val endlessExceptions = setOf("app/src/main/kotlin/xyz/headsdown/ui/HomeScreen.kt")

    /** The board replay is driven by `withFrameNanos` and stops when the night has been replayed. */
    private val frameLoopAllowed = setOf("feature/reveal/src/main/kotlin/xyz/headsdown/feature/reveal/ui/RevealBoard.kt")

    private fun mainSources(): List<File> = listOf(design, app, reveal).flatMap(SourceTree::mainKotlin)

    private fun filesNaming(words: List<String>): Set<String> =
        mainSources().filter { file -> code(file).let { text -> words.any { it in text } } }.map(SourceTree::relative).toSet()

    @Test
    fun `nothing animates forever, except the one known place`() {
        assertTrue("found the sources of all three modules", mainSources().size > 40)
        assertEquals(endlessExceptions, filesNaming(endless))
    }

    @Test
    fun `a frame loop exists only in the board replay`() {
        assertEquals(frameLoopAllowed, filesNaming(frameLoop))
    }

    @Test
    fun `the design module names no endless animation at all`() {
        val offenders = SourceTree.mainKotlin(design).filter { file -> (endless + frameLoop).any { it in code(file) } }
        assertEquals(emptyList<File>(), offenders)
    }

    @Test
    fun `the design module cannot reach a shader, a sensor, a motor or the log`() {
        val forbidden = listOf("RuntimeShader", "SensorManager", "Vibrator", "VibrationEffect", "android.util.Log", "Log.d(", "Log.w(", "Log.e(", "Log.i(")
        val offenders = SourceTree.mainKotlin(design).flatMap { file ->
            forbidden.filter { it in code(file) }.map { "${SourceTree.relative(file)}: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `no word the app has banned is spoken or drawn by a component`() {
        // HonestCopy.BANNED in :feature:reveal, which this module cannot depend on.
        val banned = listOf(
            "earn", "yield", "stake", "profit", "guarantee", "returns", "interest", "bet", "jackpot", "lottery",
            "gamble", "apy", "apr", "passive income", "risk-free", "free ore",
        )
        val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
        val offenders = SourceTree.mainKotlin(design).flatMap { file ->
            literal.findAll(code(file)).map { it.groupValues[1].lowercase() }.flatMap { text ->
                banned.filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(text) }.map { "${SourceTree.relative(file)}: \"$text\"" }
            }.toList()
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** The file without its comments: a KDoc may name what the code must not use. */
    private fun code(file: File): String =
        file.readText().replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }
}
