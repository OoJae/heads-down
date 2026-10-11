package xyz.headsdown.surface.widget

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.core.design.HdArgb
import kotlin.math.roundToInt

/**
 * The widgets as a launcher gets them: the Glance tree translated to RemoteViews, inflated into
 * real views and measured with real fonts (Robolectric's native graphics) on a 360dp phone.
 *
 * The Glance unit tests see the tree before translation, where nothing is ever cut. These see
 * what is left after it: a container keeps ten children and drops the rest without a word, a
 * platform view in a column takes all the height it is offered, and a line of text is only as
 * wide as its box.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class WidgetRemoteViewsTest {
    private val now = 1_790_000_000_000L

    /** A rig in [heat] with a haul and a streak, dark for [minutesDark] if it is running. */
    private fun state(heat: RigHeat, minutesDark: Int = 72, haul: Long? = 1_940_000_000L, streak: Int? = 23, canDig: Boolean = true, focusOnly: Boolean = false) =
        RigWidgetState(
            rig = WidgetRig(heat, darkSinceWallMillis = now - minutesDark * 60_000L, darkRounds = 3, canDig = canDig, focusOnly = focusOnly),
            haul = haul?.let { WidgetHaul(it, now - 3_600_000L) },
            streakNights = streak,
        )

    private fun rig(size: DpSize, state: RigWidgetState, fontScale: Float = 1f, box: DpSize = size): Inflated =
        inflate(size, box, fontScale) {
            GlanceTheme(colors = WidgetColors.brand) {
                RigWidgetContent(RigWidgetCopy.render(state), WidgetColorMode.BRAND, open = null, previewNowWallMillis = now)
            }
        }

    /** Composes [content] for [size], as Glance does for one size of a responsive widget, and lays it out in [box]. */
    private fun inflate(size: DpSize, box: DpSize, fontScale: Float, content: @Composable () -> Unit): Inflated {
        RuntimeEnvironment.setFontScale(fontScale)
        val context: Context = ApplicationProvider.getApplicationContext()
        val remoteViews = runBlocking { GlanceRemoteViews().compose(context, size, content = content).remoteViews }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        return Inflated(host, remoteViews.apply(activity, host), box, activity)
    }

    private fun inflate(layout: Int, box: DpSize): Inflated {
        RuntimeEnvironment.setFontScale(1f)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        return Inflated(host, LayoutInflater.from(activity).inflate(layout, host, false), box, activity)
    }

    /** A widget inflated and laid out in a box of a given size. */
    private class Inflated(host: FrameLayout, val root: View, box: DpSize, activity: Activity) {
        private val density = activity.resources.displayMetrics.density
        val width = (box.width.value * density).roundToInt()
        val height = (box.height.value * density).roundToInt()

        init {
            host.addView(root, FrameLayout.LayoutParams(width, height))
            activity.setContentView(host, ViewGroup.LayoutParams(width, height))
            shadowOf(Looper.getMainLooper()).idle()
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, width, height)
        }

        val texts: List<TextView> = buildList {
            fun walk(v: View) {
                if (v is TextView && v.text.isNotEmpty()) add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
        }

        val strings: List<String> get() = texts.map { it.text.toString() }
        val chronometer: Chronometer? get() = texts.filterIsInstance<Chronometer>().singleOrNull()

        fun text(value: String): TextView = texts.single { it.text.toString() == value }

        /** Every character is laid out, no line ends in an ellipsis, and no line is wider than the view. */
        fun whole(view: TextView): Boolean {
            val layout = view.layout ?: return false
            val lines = 0 until layout.lineCount
            val room = view.width - view.paddingLeft - view.paddingRight + 0.5f
            return lines.none { layout.getEllipsisCount(it) > 0 } &&
                layout.getLineEnd(layout.lineCount - 1) == view.text.length &&
                // The ink of the line: the space a wrapped line ends in may hang past the edge.
                lines.all { layout.getLineMax(it) <= room }
        }

        /** The view's box, top to bottom, is inside the widget. */
        fun inside(view: View): Boolean {
            var top = 0
            var left = 0
            var v: View = view
            while (v !== root) {
                top += v.top
                left += v.left
                v = v.parent as View
            }
            return top >= 0 && left >= 0 && top + view.height <= height && left + view.width <= width
        }

        fun problems(): List<String> = texts.mapNotNull { view ->
            val what = listOfNotNull("cut".takeIf { !whole(view) }, "outside the box".takeIf { !inside(view) })
            if (what.isEmpty()) null else "\"${view.text}\" is ${what.joinToString(" and ")}"
        }
    }

    private val running = listOf(RigHeat.HOT, RigHeat.COOLING)
    private val full = listOf(RigWidget.STANDARD, RigWidget.WIDE, RigWidget.TALL)

    @Test
    fun `every declared size is one of the layouts, and the full ones keep every line`() {
        assertEquals(setOf(RigWidget.COMPACT, RigWidget.STRIP, RigWidget.STANDARD, RigWidget.WIDE, RigWidget.TALL), (RigWidget().sizeMode as androidx.glance.appwidget.SizeMode.Responsive).sizes)
        for (size in full) {
            for (heat in RigHeat.entries) {
                val text = RigWidgetCopy.render(state(heat))
                val widget = rig(size, state(heat))
                val shown = widget.strings
                for (line in listOf("HEADS DOWN", text.headline, text.line, text.haul, text.streak)) {
                    assertTrue("$heat at $size shows \"$line\": $shown", line in shown)
                }
                if (heat in running) assertNotNull("$heat at $size runs the chronometer", widget.chronometer) else assertNull(widget.chronometer)
            }
        }
    }

    @Test
    fun `one row shows the rig word with the time or the status`() {
        for (size in listOf(RigWidget.COMPACT, RigWidget.STRIP)) {
            for (heat in RigHeat.entries) {
                val text = RigWidgetCopy.render(state(heat))
                val widget = rig(size, state(heat))
                assertTrue("$heat at $size", text.headline in widget.strings)
                if (heat in running) assertNotNull(widget.chronometer) else assertTrue(text.line in widget.strings)
                assertTrue("one row has no room for the name or the haul", "HEADS DOWN" !in widget.strings && text.haul !in widget.strings)
            }
        }
    }

    @Test
    fun `at two rows of a home screen nothing is cut and nothing leaves the slab`() {
        val states = RigHeat.entries.map { state(it) } + listOf(
            // The longest of each line: a first night, a rig that cannot dig, a focus shift, a long haul.
            RigWidgetState(),
            state(RigHeat.HOT, canDig = false),
            state(RigHeat.HOT, focusOnly = true),
            state(RigHeat.ARMED, focusOnly = true),
            state(RigHeat.COLD).copy(lastShiftRounds = 1312),
            state(RigHeat.HOT, minutesDark = 11 * 60 + 11, haul = 123_456_000_000_000L, streak = 365),
        )
        for (state in states) {
            assertEquals("${state.rig.heat} in ${RigWidget.TALL}", emptyList<String>(), rig(RigWidget.TALL, state).problems())
        }
    }

    @Test
    fun `the small layout holds a running rig in its 100dp`() {
        for (size in listOf(RigWidget.STANDARD, RigWidget.WIDE)) {
            assertEquals("hot in $size", emptyList<String>(), rig(size, state(RigHeat.HOT)).problems())
        }
    }

    @Test
    fun `a running time is never cut short, whatever gives way beside it`() {
        // Eleven hours: the longest a chronometer string gets in a night.
        for (size in RigWidget().let { (it.sizeMode as androidx.glance.appwidget.SizeMode.Responsive).sizes }) {
            for (heat in running) {
                for (scale in listOf(1f, 2f)) {
                    val widget = rig(size, state(heat, minutesDark = 11 * 60 + 11, focusOnly = true), fontScale = scale)
                    val clock = checkNotNull(widget.chronometer) { "$heat at $size" }
                    assertEquals("11:11:00", clock.text.toString())
                    assertTrue("$heat at $size, font scale $scale: the time is whole", widget.whole(clock))
                    assertTrue("$heat at $size, font scale $scale: the time is inside the slab", widget.inside(clock))
                }
            }
        }
    }

    @Test
    fun `an amount of ORE is never cut short`() {
        val long = state(RigHeat.HOT, haul = 123_456_000_000_000L)
        assertEquals("Last haul 1234.56 ORE", RigWidgetCopy.render(long).haul)
        for (size in full) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                // Two rows of a 360dp home screen for the tall size; the bare minimum for the others.
                val widget = rig(size, long, fontScale = scale)
                val haul = widget.text("Last haul 1234.56 ORE")
                assertTrue("$size at font scale $scale: ${widget.problems()}", widget.whole(haul) && widget.inside(haul))
            }
        }
    }

    @Test
    fun `at the largest system font the text grows to the cap and stays in the slab`() {
        // Two rows on the 360dp phone: about 250dp by 200dp, composed for the tall size.
        val box = DpSize(250.dp, 200.dp)
        val normal = rig(RigWidget.TALL, state(RigHeat.HOT), fontScale = 1f, box = box)
        val large = rig(RigWidget.TALL, state(RigHeat.HOT), fontScale = 2f, box = box)
        assertEquals(emptyList<String>(), normal.problems())
        assertEquals(emptyList<String>(), large.problems())
        // The streak is drawn 12sp at 1.0 (tall layout) and 10sp x 1.3 at 2.0 (small layout): larger, not doubled.
        val ratio = large.text("Streak 23 nights").textSize / normal.text("Streak 23 nights").textSize
        assertEquals(10f * WidgetType.MAX_SCALE / 12f, ratio, 0.02f)
        // And a box that is full at 1.0 keeps its text at 1.0.
        assertEquals(emptyList<String>(), rig(RigWidget.STANDARD, state(RigHeat.HOT), fontScale = 2f).problems())
    }

    @Test
    fun `the rig word is condensed bold, labels and figures are monospace`() {
        val widget = rig(RigWidget.TALL, state(RigHeat.HOT))
        fun families(view: TextView): List<String> =
            (view.text as android.text.Spanned).getSpans(0, view.text.length, android.text.style.TypefaceSpan::class.java).mapNotNull { it.family }
        assertEquals(listOf(WidgetType.CONDENSED_FAMILY), families(widget.text("Rig hot")))
        for (mono in listOf("HEADS DOWN", "Last haul 0.0194 ORE", "Streak 23 nights")) {
            assertEquals(mono, listOf("monospace"), families(widget.text(mono)))
        }
        assertEquals("a sentence is the platform's own face", emptyList<String>(), families(widget.text("Dark for")))
        // Inks: the word and the time are chalk, the haul is the gold, everything else ash.
        assertEquals(HdArgb.CHALK, widget.text("Rig hot").currentTextColor)
        assertEquals(HdArgb.CHALK, checkNotNull(widget.chronometer).currentTextColor)
        assertEquals(HdArgb.SEAM, widget.text("Last haul 0.0194 ORE").currentTextColor)
        for (ash in listOf("HEADS DOWN", "Dark for", "Streak 23 nights")) assertEquals(ash, HdArgb.ASH, widget.text(ash).currentTextColor)
        // No haul yet is a sentence, not an amount: it is not gold.
        assertEquals(HdArgb.ASH, rig(RigWidget.TALL, RigWidgetState()).text("No haul yet").currentTextColor)
        assertEquals(HdArgb.EMBER, rig(RigWidget.TALL, state(RigHeat.COOLING)).text("Cooling").currentTextColor)
        assertEquals(HdArgb.CHALK, rig(RigWidget.TALL, state(RigHeat.FROZEN)).text("Frozen").currentTextColor)
    }

    @Test
    fun `the crew placeholder fits its one row`() {
        val crew = inflate(CrewWidget.SIZE, CrewWidget.SIZE, 1f) {
            GlanceTheme(colors = WidgetColors.brand) { CrewPlaceholderContent(WidgetColorMode.BRAND, open = null) }
        }
        assertEquals(listOf(CrewPlaceholderCopy.TITLE, CrewPlaceholderCopy.TAG, CrewPlaceholderCopy.LINE), crew.strings)
        assertEquals(emptyList<String>(), crew.problems())
        val large = inflate(CrewWidget.SIZE, CrewWidget.SIZE, 2f) {
            GlanceTheme(colors = WidgetColors.brand) { CrewPlaceholderContent(WidgetColorMode.BRAND, open = null) }
        }
        assertEquals(emptyList<String>(), large.problems())
    }

    @Test
    fun `the static picker previews say what the widgets say`() {
        // Android 12 to 14 show these layouts in the picker; Android 15 and later compose the widget itself.
        val text = RigWidgetCopy.render(RigWidget.PREVIEW_STATE)
        val rig = inflate(R.layout.hd_widget_rig_preview, RigWidget.TALL)
        val time = android.text.format.DateUtils.formatElapsedTime(72 * 60L)
        assertEquals(listOf("HEADS DOWN", text.headline, text.line, time, text.haul, text.streak), rig.strings)
        assertEquals(emptyList<String>(), rig.problems())
        // And they are drawn in the same inks as the hot rig above.
        assertEquals(HdArgb.CHALK, rig.text(text.headline).currentTextColor)
        assertEquals(HdArgb.CHALK, rig.text(time).currentTextColor)
        assertEquals(HdArgb.SEAM, rig.text(text.haul).currentTextColor)
        for (ash in listOf("HEADS DOWN", text.line, text.streak)) assertEquals(ash, HdArgb.ASH, rig.text(ash).currentTextColor)

        // The generated preview is the widget itself, composed for the same state.
        val generated = inflate(RigWidget.TALL, RigWidget.TALL, 1f) {
            GlanceTheme(colors = WidgetColors.brand) {
                RigWidgetContent(text, WidgetColorMode.BRAND, open = null, previewNowWallMillis = RigWidget.PREVIEW_STATE.rig.darkSinceWallMillis!! + 72 * 60_000L)
            }
        }
        assertEquals(rig.strings, generated.strings)

        val crew = inflate(R.layout.hd_widget_crew_preview, CrewWidget.SIZE)
        assertEquals(emptyList<String>(), crew.problems())
        val tag = crew.strings.first()
        assertTrue(tag, CrewPlaceholderCopy.TITLE in tag && CrewPlaceholderCopy.TAG in tag)
        assertTrue(CrewPlaceholderCopy.LINE.startsWith(crew.strings.last()))
    }
}
