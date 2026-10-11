package xyz.headsdown.surface.widget

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.RemoteViews
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.core.design.HdArgb
import kotlin.math.roundToInt

/**
 * The widgets as a launcher gets them: the Glance tree translated to RemoteViews, one layout per
 * declared size, the platform's own choice among them for a box, inflated into real views and
 * measured with real fonts (Robolectric's native graphics) on a 360dp phone.
 *
 * The Glance unit tests see the tree before translation, where nothing is ever cut. These see
 * what is left after it: a container keeps ten children and drops the rest without a word, a
 * platform view in a column takes all the height it is offered, a line of text is only as wide as
 * its box, and a text squeezed from below loses its last line without an ellipsis to say so.
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

    /** Every heat, then the longest each line gets: a first night, a rig that cannot dig, a focus shift, a long shift, a long haul. */
    private val states: List<RigWidgetState> = RigHeat.entries.map { state(it) } + listOf(
        RigWidgetState(),
        state(RigHeat.HOT, canDig = false),
        state(RigHeat.HOT, focusOnly = true),
        state(RigHeat.ARMED, focusOnly = true),
        state(RigHeat.ARMED, canDig = false),
        state(RigHeat.COLD).copy(lastShiftRounds = 1312),
        state(RigHeat.COOLING, minutesDark = 11 * 60 + 11, haul = 123_456_000_000_000L, streak = 365),
        state(RigHeat.HOT, minutesDark = 11 * 60 + 11, haul = 123_456_000_000_000L, streak = 365, focusOnly = true),
        state(RigHeat.HOT, haul = 5_000_000L, streak = 1),
    )

    // One activity to a font scale: a test here lays out hundreds of widgets, and an activity apiece does not fit the test JVM.
    private var host: ActivityController<Activity>? = null
    private var hostScale = 0f

    private fun activity(fontScale: Float): Activity {
        if (host == null || hostScale != fontScale) {
            host?.pause()?.stop()?.destroy()
            RuntimeEnvironment.setFontScale(fontScale)
            host = Robolectric.buildActivity(Activity::class.java).setup()
            hostScale = fontScale
        }
        return checkNotNull(host).get()
    }

    private fun compose(size: DpSize, content: @Composable () -> Unit): RemoteViews {
        val context: Context = ApplicationProvider.getApplicationContext()
        return runBlocking { GlanceRemoteViews().compose(context, size, content = content).remoteViews }
    }

    /** The Rig widget composed for exactly [size] and laid out in [box]. */
    private fun rig(size: DpSize, state: RigWidgetState, fontScale: Float = 1f, box: DpSize = size): Inflated {
        val activity = activity(fontScale)
        val views = compose(size) {
            GlanceTheme(colors = WidgetColors.brand) {
                RigWidgetContent(RigWidgetCopy.render(state), WidgetColorMode.BRAND, open = null, previewNowWallMillis = now)
            }
        }
        return inflate(activity, views, box, size)
    }

    /**
     * The Rig widget as a launcher shows it in [box]: laid out for the declared size the platform
     * itself takes for that box (RigWidgetSizesTest holds the platform to "the widest and tallest
     * that fits" over every box).
     */
    private fun rigIn(box: DpSize, state: RigWidgetState, fontScale: Float = 1f): Inflated {
        val context: Context = ApplicationProvider.getApplicationContext()
        val chosen = checkNotNull(PlatformChoice.of(PlatformChoice.sized(context, RigWidget.SIZES), context, box))
        return rig(chosen, state, fontScale, box)
    }

    private fun inflate(activity: Activity, views: RemoteViews, box: DpSize, chosen: DpSize? = null): Inflated {
        val frame = FrameLayout(activity)
        return Inflated(frame, views.apply(activity, frame), box, activity, chosen)
    }

    private fun inflate(layout: Int, box: DpSize): Inflated {
        val activity = activity(1f)
        val frame = FrameLayout(activity)
        return Inflated(frame, LayoutInflater.from(activity).inflate(layout, frame, false), box, activity, null)
    }

    private fun crew(box: DpSize, fontScale: Float = 1f): Inflated {
        val activity = activity(fontScale)
        return inflate(activity, compose(CrewWidget.SIZE) { GlanceTheme(colors = WidgetColors.brand) { CrewPlaceholderContent(WidgetColorMode.BRAND, open = null) } }, box)
    }

    /** A widget inflated and laid out in a box of a given size. */
    private class Inflated(host: FrameLayout, val root: View, box: DpSize, activity: Activity, val chosen: DpSize?) {
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

        /** What the widget shows, as a reader sees it (a no-break space is a space). */
        val strings: List<String> get() = texts.map { it.text.toString().replace(NO_BREAK_SPACE, " ") }
        val chronometer: Chronometer? get() = texts.filterIsInstance<Chronometer>().singleOrNull()

        fun text(value: String): TextView = texts.single { it.text.toString().replace(NO_BREAK_SPACE, " ") == value }

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

        /** The view is as tall as its lines: a text squeezed from below draws only the top of its last line. */
        fun unclipped(view: TextView): Boolean {
            val layout = view.layout ?: return false
            return layout.height + view.paddingTop + view.paddingBottom <= view.height + 1
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
            val what = listOfNotNull(
                "cut".takeIf { !whole(view) },
                "clipped from below".takeIf { !unclipped(view) },
                "outside the box".takeIf { !inside(view) },
            )
            if (what.isEmpty()) null else "\"${view.text}\" is ${what.joinToString(" and ")}"
        }
    }

    private val running = listOf(RigHeat.HOT, RigHeat.COOLING)

    /** The sizes whose layout has the seam and the ORE under it. */
    private val full = listOf(RigWidget.STANDARD, RigWidget.WIDE, RigWidget.SQUARE, RigWidget.TALL)

    /** What home screens give: four columns on a 360dp phone (a low and a high row), five columns, a 320dp phone, a screen on its side. */
    private val homeScreenBoxes = listOf(
        DpSize(156.dp, 86.dp), DpSize(164.dp, 104.dp),
        DpSize(240.dp, 86.dp), DpSize(250.dp, 104.dp),
        DpSize(324.dp, 86.dp), DpSize(338.dp, 104.dp),
        DpSize(160.dp, 196.dp), DpSize(246.dp, 196.dp), DpSize(332.dp, 196.dp), DpSize(332.dp, 300.dp),
        DpSize(185.dp, 108.dp), DpSize(185.dp, 212.dp),
        DpSize(140.dp, 80.dp), DpSize(140.dp, 170.dp), DpSize(214.dp, 170.dp), DpSize(288.dp, 170.dp),
        DpSize(420.dp, 60.dp),
    )

    @Test
    fun `each layout keeps the lines it is made of`() {
        for (heat in RigHeat.entries) {
            val text = RigWidgetCopy.render(state(heat))
            val clock = heat in running
            for (size in full) {
                val widget = rig(size, state(heat))
                val name = listOf("HEADS DOWN").filter { size != RigWidget.SQUARE }
                assertEquals("$heat at $size", (name + listOf(text.headline, text.line, text.haul, text.streak)).sorted(), widget.strings.filter { widget.chronometer?.text?.toString() != it }.sorted())
                if (clock) assertNotNull("$heat at $size runs the chronometer", widget.chronometer) else assertNull(widget.chronometer)
            }
            // One row: the name, the rig word and the status, with the time; two cells have no room for the name.
            rig(RigWidget.ROW, state(heat)).let { widget ->
                assertEquals("$heat in a row", listOf("HEADS DOWN", text.headline, text.line).sorted(), widget.strings.filter { widget.chronometer?.text?.toString() != it }.sorted())
                assertEquals(clock, widget.chronometer != null)
            }
            rig(RigWidget.ROW_NARROW, state(heat)).let { widget ->
                assertEquals("$heat in a narrow row", listOf(text.headline, text.line).sorted(), widget.strings.filter { widget.chronometer?.text?.toString() != it }.sorted())
                assertEquals(clock, widget.chronometer != null)
            }
            // A short row: the rig word and the status, with the time.
            rig(RigWidget.STRIP, state(heat)).let { widget ->
                assertEquals("$heat in a strip", listOf(text.headline, text.line).sorted(), widget.strings.filter { widget.chronometer?.text?.toString() != it }.sorted())
                assertEquals(clock, widget.chronometer != null)
            }
            // The least there is: the rig word, and the time if it runs or else the status.
            rig(RigWidget.COMPACT, state(heat)).let { widget ->
                assertTrue("$heat at the least size", text.headline in widget.strings)
                if (clock) assertNotNull(widget.chronometer) else assertTrue(text.line in widget.strings)
                assertTrue("no room for the name or the haul", "HEADS DOWN" !in widget.strings && text.haul !in widget.strings)
            }
        }
    }

    @Test
    fun `at every declared size nothing is cut, clipped or outside the slab, whatever the rig is doing`() {
        for (size in RigWidget.SIZES) {
            for (state in states) {
                val widget = rig(size, state)
                val problems = widget.problems()
                // The one exception, at the least a launcher may give: 110dp cannot hold a status, which ends in an ellipsis.
                val allowed = if (size == RigWidget.COMPACT) problems.filterNot { it == "\"${RigWidgetCopy.render(state).line}\" is cut" } else problems
                assertEquals("${state.rig.heat} at $size: ${widget.strings}", emptyList<String>(), allowed)
            }
        }
    }

    @Test
    fun `in the boxes home screens give, the platform's choice fills the box and nothing is cut`() {
        for (box in homeScreenBoxes) {
            for (state in states) {
                val widget = rigIn(box, state)
                assertEquals("${state.rig.heat} in $box (laid out for ${widget.chosen}): ${widget.strings}", emptyList<String>(), widget.problems())
            }
            // Two rows always show the seam and the ORE under it; nothing taller than a row is left with one line.
            val shown = rigIn(box, state(RigHeat.HOT))
            if (box.height >= RigWidget.TALL.height) assertTrue("$box shows the haul: ${shown.strings}", "Last haul 0.0194 ORE" in shown.strings)
            if (box.height >= RigWidget.ROW.height) assertTrue("$box shows the status: ${shown.strings}", "Dark for" in shown.strings)
        }
    }

    @Test
    fun `a running time is never cut short, whatever gives way beside it`() {
        // Eleven hours: the longest a chronometer string gets in a night.
        for (size in RigWidget.SIZES) {
            for (heat in running) {
                for (scale in listOf(0.85f, 1f, 2f)) {
                    val widget = rig(size, state(heat, minutesDark = 11 * 60 + 11, focusOnly = true), fontScale = scale)
                    val clock = checkNotNull(widget.chronometer) { "$heat at $size" }
                    assertEquals("11:11:00", clock.text.toString())
                    assertTrue("$heat at $size, font scale $scale: the time is whole", widget.whole(clock) && widget.unclipped(clock))
                    assertTrue("$heat at $size, font scale $scale: the time is inside the slab", widget.inside(clock))
                    // And the rig word beside or above it is whole too: "Ri…" is no word.
                    assertTrue("$heat at $size, font scale $scale: the rig word is whole", widget.whole(widget.text(RigWidgetCopy.render(state(heat)).headline)))
                }
            }
        }
    }

    @Test
    fun `an amount of ORE is never cut short, and its number is not parted from ORE`() {
        val long = state(RigHeat.HOT, haul = 123_456_000_000_000L)
        assertEquals("Last haul 1234.56 ORE", RigWidgetCopy.render(long).haul)
        val boxes = full.map { it to it } + listOf(RigWidget.SQUARE to DpSize(140.dp, 170.dp), RigWidget.SQUARE to DpSize(160.dp, 196.dp))
        for ((size, box) in boxes) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                val widget = rig(size, long, fontScale = scale, box = box)
                val haul = widget.text("Last haul 1234.56 ORE")
                assertTrue("$size in $box at font scale $scale: ${widget.problems()}", widget.whole(haul) && widget.unclipped(haul) && widget.inside(haul))
                // Where the line wraps, the amount goes to the second line whole.
                val layout = haul.layout
                val lines = (0 until layout.lineCount).map { haul.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).replace(NO_BREAK_SPACE, " ").trim() }
                assertTrue("$size in $box at font scale $scale wraps as $lines", lines.single { "ORE" in it }.contains("1234.56 ORE"))
            }
        }
        // Two cells wrap it; the words are the widget's own, and "No haul yet" is left as it is.
        assertEquals("Last haul 0.0194${NO_BREAK_SPACE}ORE", amountKeptWhole("Last haul 0.0194 ORE"))
        assertEquals(listOf("Last haul", "1234.56 ORE"), rig(RigWidget.SQUARE, long).text("Last haul 1234.56 ORE").let { v -> (0 until v.layout.lineCount).map { v.text.substring(v.layout.getLineStart(it), v.layout.getLineEnd(it)).replace(NO_BREAK_SPACE, " ").trim() } })
        assertEquals("No haul yet", rig(RigWidget.SQUARE, RigWidgetState()).text("No haul yet").text.toString())
    }

    @Test
    fun `a rig that cannot dig says so wherever a status is shown`() {
        for (state in listOf(state(RigHeat.HOT, canDig = false), state(RigHeat.HOT, focusOnly = true))) {
            val line = RigWidgetCopy.render(state).line
            assertTrue(line, line.endsWith("no digs") || line.endsWith("focus only"))
            for (size in RigWidget.SIZES - RigWidget.COMPACT) {
                val widget = rig(size, state)
                assertTrue("$size shows \"$line\": ${widget.strings}", line in widget.strings)
                assertTrue("$size shows it whole", widget.whole(widget.text(line)))
            }
            // The least size has the rig word and the time only; the slab's description still says it.
            assertFalse(line in rig(RigWidget.COMPACT, state).strings)
            assertTrue(RigWidgetCopy.render(state).contentDescription.let { "No digs." in it || "Focus only." in it })
        }
    }

    @Test
    fun `a larger system font leaves the text at its size, and a smaller one is honoured`() {
        // Every layout is full at the size it is declared for: text that grew would leave the slab.
        for (size in RigWidget.SIZES) {
            val normal = rig(size, state(RigHeat.HOT))
            for (scale in listOf(1.15f, 1.3f, 2f)) {
                val large = rig(size, state(RigHeat.HOT), fontScale = scale)
                assertEquals("$size at $scale", normal.strings, large.strings)
                assertEquals("$size at $scale", normal.texts.map { it.textSize.roundToInt() }, large.texts.map { it.textSize.roundToInt() })
                assertEquals("$size at $scale", emptyList<String>(), large.problems())
            }
            val small = rig(size, state(RigHeat.HOT), fontScale = 0.85f)
            assertTrue("$size at 0.85", small.texts.zip(normal.texts).all { (a, b) -> a.textSize < b.textSize })
        }
        // The same in the boxes of a home screen, for every state.
        for (box in listOf(DpSize(246.dp, 196.dp), DpSize(160.dp, 196.dp), DpSize(250.dp, 104.dp), DpSize(156.dp, 86.dp))) {
            for (state in states) assertEquals("${state.rig.heat} in $box at 2.0", emptyList<String>(), rigIn(box, state, fontScale = 2f).problems())
        }
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
        for (size in RigWidget.SIZES) {
            val hot = rig(size, state(RigHeat.HOT))
            assertEquals("$size", HdArgb.CHALK, hot.text("Rig hot").currentTextColor)
            assertEquals("$size", HdArgb.CHALK, checkNotNull(hot.chronometer).currentTextColor)
            assertEquals("$size", HdArgb.EMBER, rig(size, state(RigHeat.COOLING)).text("Cooling").currentTextColor)
            assertEquals("$size", HdArgb.EMBER, rig(size, state(RigHeat.ARMED)).text("Armed").currentTextColor)
            assertEquals("$size", HdArgb.CHALK, rig(size, state(RigHeat.FROZEN)).text("Frozen").currentTextColor)
            assertEquals("$size", HdArgb.ASH, rig(size, state(RigHeat.COLD)).text("Rig cold").currentTextColor)
        }
        for (size in full) {
            val hot = rig(size, state(RigHeat.HOT))
            assertEquals("$size", HdArgb.SEAM, hot.text("Last haul 0.0194 ORE").currentTextColor)
            for (ash in listOf("Dark for", "Streak 23 nights")) assertEquals("$size $ash", HdArgb.ASH, hot.text(ash).currentTextColor)
            // No haul yet is a sentence, not an amount: it is not gold.
            assertEquals("$size", HdArgb.ASH, rig(size, RigWidgetState()).text("No haul yet").currentTextColor)
        }
        assertEquals(HdArgb.ASH, widget.text("HEADS DOWN").currentTextColor)
    }

    @Test
    fun `the slab is one node to a screen reader, and one target`() {
        fun described(root: View): List<View> = buildList {
            fun walk(v: View) {
                if (!v.contentDescription.isNullOrEmpty()) add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
        }
        fun clickable(root: View): List<View> = buildList {
            fun walk(v: View) {
                if (v.hasOnClickListeners()) add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
        }
        val activity = activity(1f)
        val open = androidx.glance.appwidget.action.actionStartActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).setPackage("xyz.headsdown"))
        for (size in RigWidget.SIZES) {
            for (heat in RigHeat.entries) {
                val text = RigWidgetCopy.render(state(heat))
                val views = compose(size) {
                    GlanceTheme(colors = WidgetColors.brand) { RigWidgetContent(text, WidgetColorMode.BRAND, open, previewNowWallMillis = now) }
                }
                val widget = inflate(activity, views, size)
                // One description, the sentence that says everything the slab shows; the lights and the seam say nothing.
                // The time is not in it: the platform's chronometer says its own ("1 hour, 12 minutes, 0 seconds").
                val nodes = described(widget.root).filter { it !is Chronometer }
                assertEquals("$heat at $size", listOf(text.contentDescription), nodes.map { it.contentDescription.toString() })
                assertEquals("$heat at $size", heat in running, widget.chronometer?.contentDescription?.isNotEmpty() == true)
                // One target, the whole slab: nothing inside it takes a tap of its own.
                val targets = clickable(widget.root)
                assertEquals("$heat at $size", 1, targets.size)
                val target = targets.single()
                val node = nodes.single()
                fun within(inner: View, outer: View): Boolean = generateSequence(inner) { it.parent as? View }.any { it === outer }
                assertTrue("$heat at $size: the target is the described slab (${target.javaClass.simpleName} and ${node.javaClass.simpleName})", within(node, target) || within(target, node))
                assertEquals("$heat at $size: the target is the whole widget", widget.width to widget.height, target.width to target.height)
                assertEquals("$heat at $size: so is what is described", widget.width to widget.height, node.width to node.height)
            }
        }
        // A generated preview starts nothing.
        assertEquals(emptyList<View>(), clickable(rig(RigWidget.TALL, state(RigHeat.HOT)).root))
        // The Crew placeholder says it is one, in one node.
        assertEquals(listOf(CrewPlaceholderCopy.DESCRIPTION), described(crew(CrewWidget.SIZE).root).map { it.contentDescription.toString() })
        assertEquals(emptyList<View>(), clickable(crew(CrewWidget.SIZE).root))
    }

    @Test
    fun `the crew placeholder fits its row, and a shorter row than it asks for`() {
        // 250 by 90 is what it asks for; a launcher's row can be lower, and a 320dp phone gives 288 by 80 or so.
        for (box in listOf(CrewWidget.SIZE, DpSize(324.dp, 86.dp), DpSize(338.dp, 104.dp), DpSize(288.dp, 80.dp), DpSize(250.dp, 72.dp))) {
            for (scale in listOf(1f, 2f)) {
                val crew = crew(box, scale)
                assertEquals(listOf(CrewPlaceholderCopy.TITLE, CrewPlaceholderCopy.TAG, CrewPlaceholderCopy.LINE), crew.strings)
                assertEquals("$box at font scale $scale", emptyList<String>(), crew.problems())
            }
        }
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

        // The generated preview is the widget itself, composed for the same state, and the static one is laid out as it is.
        val generated = inflate(
            activity(1f),
            compose(RigWidget.TALL) {
                GlanceTheme(colors = WidgetColors.brand) {
                    RigWidgetContent(text, WidgetColorMode.BRAND, open = null, previewNowWallMillis = RigWidget.PREVIEW_STATE.rig.darkSinceWallMillis!! + 72 * 60_000L)
                }
            },
            RigWidget.TALL,
        )
        assertEquals(rig.strings, generated.strings)
        assertEquals(generated.texts.map { it.textSize }, rig.texts.map { it.textSize })

        // The Crew placeholder: the same three texts in the same two inks, whole in its row.
        val crew = inflate(R.layout.hd_widget_crew_preview, CrewWidget.SIZE)
        val real = crew(CrewWidget.SIZE)
        assertEquals(listOf(CrewPlaceholderCopy.TITLE, CrewPlaceholderCopy.TAG, CrewPlaceholderCopy.LINE), crew.strings)
        assertEquals(real.strings, crew.strings)
        assertEquals(emptyList<String>(), crew.problems())
        assertEquals(real.texts.map { it.currentTextColor }, crew.texts.map { it.currentTextColor })
        assertEquals(listOf(HdArgb.ASH, HdArgb.CHALK, HdArgb.ASH), crew.texts.map { it.currentTextColor })
        assertEquals(real.texts.map { it.textSize }, crew.texts.map { it.textSize })
    }
}
