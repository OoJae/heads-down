package xyz.headsdown.ui

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.ui.setup.HeroFirstScreen
import xyz.headsdown.ui.setup.HeroPage
import xyz.headsdown.ui.setup.PageViewport

/**
 * The scaffold under the home screen and setup, with plain blocks in its slots, so every rule of
 * the first screenful is a number: who gives way to whom when the screen is too short for all.
 * The order is: the overlay always; then what must fit; then the mark; then the slab; then a
 * notice's place under the slab; then the peek at what comes next.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class HeroPageTest : ScreenTest() {

    @Composable
    private fun Block(tag: String, height: Dp) = Box(Modifier.fillMaxWidth().height(height).testTag(tag))

    /** A first screenful of blocks, in a viewport 600dp tall with nothing over its top. */
    private fun firstScreen(overlay: Dp = 50.dp, mark: Dp = 30.dp, must: Dp, notice: Dp = 0.dp, rest: Dp = 0.dp, peek: Dp? = null) {
        show {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                HeroFirstScreen(
                    viewport = PageViewport(with(LocalDensity.current) { 600.dp.roundToPx() }, 0),
                    minShare = 0.34f,
                    maxShare = 0.58f,
                    hero = { Box(Modifier.testTag("hero")) },
                    overlay = { Block("overlay", overlay) },
                    mustFit = { Block("must", must) },
                    modifier = Modifier.testTag("first"),
                    mark = { Block("mark", mark) },
                    notice = { if (notice > 0.dp) Block("notice", notice) },
                    rest = { if (rest > 0.dp) Block("rest", rest) },
                    peek = peek,
                )
            }
        }
    }

    private fun box(tag: String): DpRect = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun span(tag: String): Pair<Dp, Dp> = box(tag).let { it.top to it.bottom }

    @Test
    fun `with room to spare the slab takes what is left, up to the most it is given`() {
        firstScreen(must = 150.dp)
        // 600 - 150 is left; 58% of 600 is the most.
        assertEquals(0.dp to 50.dp, span("overlay"))
        assertEquals(50.dp to 318.dp, span("hero"))
        assertEquals(318.dp to 348.dp, span("mark"))
        assertEquals(348.dp to 498.dp, span("must"))
    }

    @Test
    fun `what must fit shrinks the slab below its share, and ends at the foot of the screenful`() {
        firstScreen(must = 420.dp)
        assertEquals(50.dp to 150.dp, span("hero"))
        assertEquals(150.dp to 180.dp, span("mark"))
        assertEquals(180.dp to 600.dp, span("must"))
    }

    @Test
    fun `a slab left with less than 72dp is not drawn - the two bands close over it`() {
        firstScreen(must = 470.dp)
        assertEquals("no room for a slab", 0.dp, box("hero").height)
        assertEquals(50.dp to 80.dp, span("mark"))
        assertEquals(80.dp to 550.dp, span("must"))
    }

    @Test
    fun `when even the two bands leave no room, the mark follows what must fit`() {
        firstScreen(must = 540.dp)
        // 50 + 30 + 540 is more than the 600 there is: the mark gives way, and what must fit is whole.
        assertEquals(0.dp, box("hero").height)
        assertEquals(50.dp to 590.dp, span("must"))
        assertEquals(590.dp to 620.dp, span("mark"))
    }

    @Test
    fun `a notice sits under the slab while what must fit still fits under it`() {
        firstScreen(must = 300.dp, notice = 100.dp)
        assertEquals(170.dp to 200.dp, span("mark"))
        assertEquals(200.dp to 300.dp, span("notice"))
        assertEquals(300.dp to 600.dp, span("must"))
    }

    @Test
    fun `a notice too tall for that follows what must fit, and the slab keeps its share`() {
        firstScreen(must = 300.dp, notice = 250.dp)
        // 34% of 600: the slab's share is not given up for something that is under the fold anyway.
        assertEquals(174.dp to 204.dp, span("mark"))
        assertEquals(204.dp to 504.dp, span("must"))
        assertEquals(504.dp to 754.dp, span("notice"))
    }

    @Test
    fun `with a peek the next thing starts that far above the dock, or under the fold, never between`() {
        // Everything fits with the peek to spare: the first screenful ends 50dp short of the viewport.
        firstScreen(must = 150.dp, rest = 100.dp, peek = 50.dp)
        assertEquals(550.dp, box("first").height)
        assertEquals(270.dp to 300.dp, span("mark"))
        assertEquals(450.dp to 550.dp, span("rest"))
    }

    @Test
    fun `a first screenful that leaves less than the peek fills the viewport instead`() {
        // 34% of the 550 that are left is 187: 187 + 150 + 250 is 587, which leaves 13dp, not 50.
        firstScreen(must = 150.dp, rest = 250.dp, peek = 50.dp)
        assertEquals(157.dp to 187.dp, span("mark"))
        assertEquals(600.dp, box("first").height)
    }

    @Test
    fun `a first screenful longer than the viewport is as long as what is in it`() {
        firstScreen(must = 150.dp, rest = 400.dp, peek = 50.dp)
        assertEquals(737.dp, box("first").height)
        assertEquals(337.dp to 737.dp, span("rest"))
    }

    // ------------------------------------------------------------------ the page

    @Test
    fun `what a page pins stays where it is while the page moves under it, and the page is told what it covers`() {
        val scroll = ScrollState(0)
        var told: PageViewport? = null
        show {
            HeroPage(
                scrollState = scroll,
                pinned = { Block("pinned", 56.dp) },
                dock = { Block("dock", 100.dp) },
            ) { viewport ->
                told = viewport
                Block("page", 2000.dp)
            }
        }
        // 800dp less the dock, at two pixels to a dp; the pinned band covers the top 56dp of it.
        assertEquals(PageViewport(heightPx = 1400, topInsetPx = 112), told)
        assertEquals(0.dp to 56.dp, span("pinned"))
        assertEquals(700.dp to 800.dp, span("dock"))
        assertEquals("the page starts under the band, at the top of the screen", 0.dp, box("page").top)

        rule.runOnUiThread { runBlocking { scroll.scrollTo(900) } }
        rule.waitForIdle()
        assertEquals((-450).dp, box("page").top)
        assertEquals(0.dp to 56.dp, span("pinned"))
    }

    @Test
    fun `a page that pins nothing is told only of the status bar`() {
        var told: PageViewport? = null
        show {
            HeroPage(scrollState = rememberScrollState(), dock = { Block("dock", 100.dp) }) { viewport ->
                told = viewport
                Block("page", 300.dp)
            }
        }
        // Robolectric's window has no status bar: nothing is covered.
        assertEquals(PageViewport(heightPx = 1400, topInsetPx = 0), told)
    }
}
