package xyz.headsdown.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.refusalLine
import xyz.headsdown.ui.setup.SetupCopy
import xyz.headsdown.ui.setup.SetupStep
import xyz.headsdown.ui.setup.SetupTags

/**
 * What a screen reader is given, in every state of the three screens: the tree as TalkBack walks
 * it (merged), read node by node. Headings, roles and states are there; everything that can be
 * tapped is a finger wide and tall; a fact and its label are one node; what changes by itself is
 * announced politely; drawing is not exposed; and the dock is the last thing reached.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class ScreensAccessibilityTest : ScreenTest() {

    private fun tree(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        walk(rule.onRoot().fetchSemanticsNode())
        return out
    }

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun SemanticsNode.says(): List<String> = texts() + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private val SemanticsNode.tag: String? get() = config.getOrNull(SemanticsProperties.TestTag)

    private val SemanticsNode.isHeading: Boolean get() = config.contains(SemanticsProperties.Heading)

    private val SemanticsNode.isPolite: Boolean get() = config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite

    private fun SemanticsNode.within(ancestorTag: String): Boolean = generateSequence(this) { it.parent }.any { it.tag == ancestorTag }

    private fun List<SemanticsNode>.saying(text: String): SemanticsNode =
        checkNotNull(firstOrNull { text in it.says() }) { "nothing says \"$text\": ${flatMap { it.says() }}" }

    /** The rules that hold for every screen of the track, whatever it shows. */
    private fun assertReadable(what: String, dockTag: String, nodes: List<SemanticsNode> = tree(), dockSpeaks: Boolean = true) {
        val spoken = nodes.filter { it.says().isNotEmpty() }
        assertTrue("$what: the screen was read: ${spoken.size} nodes", spoken.size >= 6)

        for (node in nodes) {
            val says = node.says()
            // Everything that takes a tap: a role, a name, and a finger's width and height.
            if (node.config.contains(SemanticsActions.OnClick)) {
                assertNotNull("$what: $says can be tapped and has no role", node.config.getOrNull(SemanticsProperties.Role))
                assertTrue("$what: something can be tapped and has no name", says.isNotEmpty())
                val width = node.size.width.toFloat().toDp()
                val height = node.size.height.toFloat().toDp()
                assertTrue("$what: $says is $width by $height", width >= 48.dp && height >= 48.dp)
            }
            // Drawing is not text: no blank, and no node that is only a badge's number or a tick.
            for (said in says) assertTrue("$what: a blank is read out", said.isNotBlank())
            assertFalse("$what: a drawing is read out: $says", says.size == 1 && says.single().trim().length == 1)
        }

        // One screen, one first heading, and it is reached before the dock.
        assertTrue("$what: no heading", nodes.any { it.isHeading })

        // The dock is the last thing reached: nothing that speaks comes after its own nodes.
        if (!dockSpeaks) {
            assertTrue("$what: the dock is a still block, and says nothing", spoken.none { it.within(dockTag) })
            return
        }
        val lastSpoken = spoken.last()
        assertTrue("$what: \"${lastSpoken.says()}\" comes after the dock", lastSpoken.within(dockTag))
        val firstInDock = spoken.indexOfFirst { it.within(dockTag) }
        assertTrue("$what: the dock has an action in it", firstInDock >= 0)
        assertTrue("$what: the dock's nodes are together at the end", spoken.drop(firstInDock).all { it.within(dockTag) })
    }

    @Test
    fun `the home screen, read state by state`() {
        var shown by mutableStateOf(Screens.idle)
        show(bars = true) { key(shown) { Screens.Home(shown) } }
        for (case in Screens.homeCases) {
            shown = case
            rule.waitForIdle()
            val what = "home, ${case.name}"
            val nodes = tree()
            assertReadable(what, HomeTags.DOCK, nodes)
            val state = case.snapshot.state

            // The page's heading, then its sections, each a heading.
            assertTrue(nodes.saying("Your phone's night shift").isHeading)
            for (section in listOf("HAUL", HomeCopy.SOL_AND_RIG, HomeCopy.RIG_KEY, "HOME SCREEN", HomeCopy.HOW_IT_WORKS)) {
                assertTrue("$what: $section is a heading", nodes.saying(section).isHeading)
            }
            assertEquals("$what: one heading names the page", 1, nodes.count { it.isHeading && "Your phone's night shift" in it.says() })

            // The hero: one image, one sentence, nothing under it.
            val hero = nodes.single { it.tag == HomeTags.HERO }
            assertEquals(Role.Image, hero.config.getOrNull(SemanticsProperties.Role))
            assertEquals(listOf(HomeCopy.hero(state)), hero.says())
            assertTrue(hero.children.isEmpty())

            // The rig's word is announced when it changes; so is why a shift ended, and what went wrong.
            val word = nodes.single { it.tag == HomeTags.RIG_WORD }
            assertTrue("$what: the rig word is a polite live region", word.isPolite)
            assertEquals(listOf(lookOf(state).word), word.texts())
            if (state is ShiftState.Broken) assertTrue("$what: the reason is announced", nodes.saying(lookOf(state).line).isPolite)
            case.crank.refusalLine()?.let { assertTrue("$what: the refusal is announced", nodes.saying(it).isPolite) }
            healthLine(case.health)?.let { assertTrue("$what: the banner is announced", nodes.saying(it).isPolite) }
            assertTrue("$what: the heat is said in words", nodes.any { "Heat ${lookOf(state).pixels} of 5" in it.says() })

            // A fact and its label are one node.
            val rounds = nodes.single { it.tag == HomeTags.ROUNDS_DARK }
            assertEquals(listOf("Rounds dark", case.snapshot.darkRounds.toString()), rounds.texts())
            nodes.singleOrNull { it.tag == HomeTags.SHIFT_CLOCK }?.let { clock ->
                assertTrue("$what: the clock is one node with its label: ${clock.texts()}", clock.texts().size == 2 || case.loading)
            }
            assertEquals("$what: the haul is one node", 1, nodes.count { it.tag == HomeTags.HAUL && it.texts().isNotEmpty() })
            // Under the haul, the way to every finished night: a button of its own inside the plate, there because a rig is bound.
            val nights = nodes.single { it.tag == xyz.headsdown.nights.NightsTags.HOME_LINK }
            assertEquals(listOf(xyz.headsdown.nights.NightsCopy.LINK), nights.texts())
            assertEquals(Role.Button, nights.config.getOrNull(SemanticsProperties.Role))
            assertTrue("$what: the link is in the haul's plate, and not in its words", nights.within(HomeTags.HAUL))
            assertFalse(nodes.single { it.tag == HomeTags.HAUL }.texts().contains(xyz.headsdown.nights.NightsCopy.LINK))

            // The Focus Bond: one of a group of four radio buttons is selected, by state and not by a description.
            val choices = nodes.filter { it.tag?.startsWith(HomeTags.BOND_CHOICE) == true }
            if (state is ShiftState.Idle || state is ShiftState.Broken || state is ShiftState.Frozen) {
                assertEquals(listOf("Off", "10 SKR", "50 SKR", "100 SKR"), choices.flatMap { it.texts() })
                assertTrue(choices.all { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton })
                assertEquals(listOf(false, true, false, false), choices.map { it.config[SemanticsProperties.Selected] })
                assertTrue("in a selectable group", choices.all { c -> generateSequence(c) { it.parent }.any { it.config.contains(SemanticsProperties.SelectableGroup) } })
            } else {
                assertTrue("$what: the bond is not offered while a shift runs", choices.isEmpty())
            }
        }
    }

    @Test
    fun `setup, read state by state`() {
        var shown by mutableStateOf(Screens.firstLaunch)
        show(bars = true) { key(shown) { Screens.Setup(shown) } }
        for (case in Screens.setupCases) {
            shown = case
            rule.waitForIdle()
            val what = "setup, ${case.name}"
            val nodes = tree()
            // Which step is next is not known while the phone is read: the bar is a still block.
            assertReadable(what, SetupTags.DOCK, nodes, dockSpeaks = !case.loading)

            // The statement is the page's heading and a button; the steps have their own heading.
            val turn = nodes.single { it.tag == SetupTags.TURN }
            assertTrue(turn.isHeading && turn.config.getOrNull(SemanticsProperties.Role) == Role.Button)
            assertTrue(nodes.saying(SetupCopy.TITLE).isHeading)
            val hero = nodes.single { it.tag == SetupTags.HERO }
            assertEquals(Role.Image, hero.config.getOrNull(SemanticsProperties.Role))
            assertEquals(listOf(SetupCopy.HERO_FACE_DOWN), hero.says())

            if (case.loading) continue
            // How far along: said in words, and announced when it changes.
            val done = SetupStep.entries.count { it.isDone(case.state) }
            val progress = nodes.single { it.tag == SetupTags.PROGRESS }
            assertEquals(listOf(SetupCopy.progress(done)), progress.says())
            assertTrue(progress.isPolite)
            // Each step is one node with its title, what it is for, and its state in words.
            for (step in SetupStep.entries) {
                val header = nodes.first { it.within(SetupTags.STEP + step.number) && SetupCopy.title(step) in it.texts() }
                assertEquals("$what: step ${step.number}: ${header.texts()}", 2, header.texts().size)
                assertNotNull("$what: step ${step.number} says its state", header.config.getOrNull(SemanticsProperties.StateDescription))
            }
            // The bar says what it does and is a button.
            val bar = nodes.single { it.tag == SetupTags.PRIMARY || it.tag == INTRO_CONTINUE_TAG }
            assertEquals(Role.Button, bar.config.getOrNull(SemanticsProperties.Role))
            assertEquals(1, bar.texts().size)
        }
    }

    @Test
    fun `the introduction, read from the top`() {
        show(bars = true) { NightShiftIntro(onContinue = {}, continueLabel = "Back") }
        val nodes = tree()
        assertTrue(nodes.saying(NightShiftCopy.TITLE).isHeading)
        // Each step of the ritual is one node: its name and what happens in it. The badge is drawing.
        for (step in NightShiftCopy.STEPS) assertEquals(listOf(step.title, step.body), nodes.saying(step.title).texts())
        assertTrue(nodes.none { it.says().size == 1 && it.says().single().trim().length == 1 })
        val bar = rule.onNodeWithTag(INTRO_CONTINUE_TAG).fetchSemanticsNode()
        assertEquals(listOf("Back"), bar.texts())
        assertEquals(Role.Button, bar.config.getOrNull(SemanticsProperties.Role))
        assertEquals("the bar is the last thing reached", bar.id, nodes.last { it.says().isNotEmpty() }.id)
        val width = bar.size.width.toFloat().toDp()
        val height = bar.size.height.toFloat().toDp()
        assertTrue(width >= 48.dp && height >= 56.dp)
    }
}
