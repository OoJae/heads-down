package xyz.headsdown.surface.widget

import android.content.Context
import android.util.SizeF
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.SizeMode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which layout a home screen ends up showing.
 *
 * From Android 12 a launcher is handed one layout per declared size and picks one itself for the
 * box it gives the widget: of the sizes that fit, the nearest (`RemoteViews.findBestFitLayout`).
 * Nearest is not largest. With the sizes the widget declared before, a box of four cells by two
 * rows took the 100dp layout (260x100 is nearer to 332x196 than 180x170 is) and a box of two
 * cells by two rows took the 40dp one, the only size narrow enough.
 *
 * These tests ask the platform's own code (Robolectric runs the real `RemoteViews`), so they hold
 * for what a launcher does and not for what this module believes it does.
 */
@RunWith(RobolectricTestRunner::class)
class RigWidgetSizesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun declared(): Set<DpSize> = (RigWidget().sizeMode as SizeMode.Responsive).sizes

    /** The widest declared width and the tallest declared height that fit [box], if together they are a declared size. */
    private fun widestAndTallest(box: DpSize, sizes: Set<DpSize>): DpSize? {
        val fitting = sizes.filter { it.width <= box.width && it.height <= box.height }
        if (fitting.isEmpty()) return null
        return DpSize(fitting.maxOf { it.width }, fitting.maxOf { it.height })
    }

    @Test
    fun `the declared sizes are the documented seven`() {
        assertEquals(RigWidget.SIZES, declared())
        assertEquals(
            setOf(RigWidget.COMPACT, RigWidget.STRIP, RigWidget.ROW_NARROW, RigWidget.ROW, RigWidget.STANDARD, RigWidget.SQUARE, RigWidget.TALL),
            RigWidget.SIZES,
        )
        // WIDE draws what STANDARD draws: declared, it is the size that stole the two-row box.
        assertFalse(RigWidget.WIDE in RigWidget.SIZES)
        assertEquals(RigPlan.of(RigWidget.STANDARD).layout, RigPlan.of(RigWidget.WIDE).layout)
        // The provider asks for no less than the smallest of them (res/xml/hd_widget_rig_info.xml).
        assertEquals(RigWidget.COMPACT, DpSize(RigWidget.SIZES.minOf { it.width }, RigWidget.SIZES.minOf { it.height }))
    }

    @Test
    fun `the wider of two sizes with the taller of them is a size too`() {
        // This is what makes the nearest size that fits the largest one: whatever fits, their join fits and is declared.
        val sizes = declared()
        for (a in sizes) {
            for (b in sizes) {
                val join = DpSize(maxOf(a.width, b.width), maxOf(a.height, b.height))
                assertTrue("$a with $b gives $join, which is not declared", join in sizes)
            }
        }
        // And the generated preview's sizes are a chain of them.
        val preview = (RigWidget().previewSizeMode as SizeMode.Responsive).sizes
        assertEquals(setOf(RigWidget.STANDARD, RigWidget.TALL), preview)
    }

    @Test
    fun `for any box the platform takes the widest and tallest declared size that fits`() {
        val sizes = declared()
        val multi = PlatformChoice.sized(context, sizes)
        var boxes = 0
        var width = 110
        while (width <= 460) {
            var height = 40
            while (height <= 330) {
                val box = DpSize(width.dp, height.dp)
                val expected = widestAndTallest(box, sizes)
                assertEquals("a box of $box", expected, PlatformChoice.of(multi, context, box))
                boxes++
                height += 7
            }
            width += 9
        }
        assertTrue("the grid was walked: $boxes boxes", boxes > 1500)
    }

    @Test
    fun `the boxes of a home screen get the layout made for them`() {
        val multi = PlatformChoice.sized(context, declared())
        fun layout(width: Int, height: Int): Pair<RigLayout, Boolean> {
            val plan = RigPlan.of(checkNotNull(PlatformChoice.of(multi, context, DpSize(width.dp, height.dp))))
            return plan.layout to plan.narrow
        }
        // Four columns on a 360dp phone: cells of about 82 by 98dp.
        assertEquals("two cells, one row", RigLayout.ROW to true, layout(156, 86))
        assertEquals("three cells, one row", RigLayout.ROW to false, layout(240, 86))
        assertEquals("four cells, one row", RigLayout.ROW to false, layout(324, 86))
        assertEquals("three cells, one tall row", RigLayout.STANDARD to false, layout(250, 104))
        assertEquals("two cells, one tall row", RigLayout.ROW to true, layout(164, 104))
        assertEquals("two cells, two rows", RigLayout.TALL to true, layout(160, 196))
        assertEquals("three cells, two rows: what the widget asks for", RigLayout.TALL to false, layout(246, 196))
        assertEquals("four cells, two rows", RigLayout.TALL to false, layout(332, 196))
        assertEquals("four cells, three rows", RigLayout.TALL to false, layout(332, 300))
        // Five columns: three cells are barely wider than the regular layouts' 180dp.
        assertEquals(RigLayout.STANDARD to false, layout(185, 108))
        // A 320dp phone.
        assertEquals(RigLayout.ROW to true, layout(140, 80))
        assertEquals(RigLayout.TALL to true, layout(140, 170))
        assertEquals(RigLayout.TALL to false, layout(288, 170))
        // A home screen on its side: wide, short rows.
        assertEquals(RigLayout.STRIP to false, layout(420, 60))
        // The least a launcher may give.
        assertEquals(RigLayout.STRIP to true, layout(110, 40))
    }

    @Test
    fun `with the sizes declared before, four cells by two rows took the small layout`() {
        // The defect this set of sizes replaces, kept as a fact about the platform's rule.
        val before = setOf(DpSize(110.dp, 40.dp), DpSize(190.dp, 40.dp), DpSize(180.dp, 100.dp), DpSize(260.dp, 100.dp), DpSize(180.dp, 170.dp))
        val multi = PlatformChoice.sized(context, before)
        assertEquals(DpSize(260.dp, 100.dp), PlatformChoice.of(multi, context, DpSize(332.dp, 196.dp)))
        assertEquals(DpSize(110.dp, 40.dp), PlatformChoice.of(multi, context, DpSize(160.dp, 196.dp)))
    }

    @Test
    fun `each declared size draws its own layout`() {
        fun plan(size: DpSize) = RigPlan.of(size).let { it.layout to it.narrow }
        assertEquals(RigLayout.STRIP to true, plan(RigWidget.COMPACT))
        assertEquals(RigLayout.STRIP to false, plan(RigWidget.STRIP))
        assertEquals(RigLayout.ROW to true, plan(RigWidget.ROW_NARROW))
        assertEquals(RigLayout.ROW to false, plan(RigWidget.ROW))
        assertEquals(RigLayout.STANDARD to false, plan(RigWidget.STANDARD))
        assertEquals(RigLayout.TALL to true, plan(RigWidget.SQUARE))
        assertEquals(RigLayout.TALL to false, plan(RigWidget.TALL))
        // No two declared sizes draw the same thing.
        assertEquals(RigWidget.SIZES.size, RigWidget.SIZES.map { plan(it) }.toSet().size)
    }
}

/**
 * The platform's own choice among sized layouts. `getRemoteViewsToApply` and `getIdealSize` are
 * hidden, so they are reached by name; Robolectric is pinned to one SDK (robolectric.properties),
 * and a platform that drops them fails these tests by name, not silently.
 */
internal object PlatformChoice {
    /** One empty layout per size: enough for the platform to choose between. */
    fun sized(context: Context, sizes: Set<DpSize>): RemoteViews =
        RemoteViews(sizes.associate { SizeF(it.width.value, it.height.value) to RemoteViews(context.packageName, android.R.layout.simple_list_item_1) })

    /** The layout the platform applies in [box]. */
    fun layoutIn(multi: RemoteViews, context: Context, box: DpSize): RemoteViews {
        val pick = RemoteViews::class.java.getMethod("getRemoteViewsToApply", Context::class.java, SizeF::class.java)
        return pick.invoke(multi, context, SizeF(box.width.value, box.height.value)) as RemoteViews
    }

    /** The declared size of the layout the platform applies in [box]. */
    fun of(multi: RemoteViews, context: Context, box: DpSize): DpSize? {
        val ideal = RemoteViews::class.java.getDeclaredMethod("getIdealSize").apply { isAccessible = true }
        val size = ideal.invoke(layoutIn(multi, context, box)) as SizeF? ?: return null
        return DpSize(size.width.dp, size.height.dp)
    }
}
