package xyz.headsdown.devtools

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.headsdown.BuildConfig
import xyz.headsdown.ui.slab.LayDownGlow
import xyz.headsdown.ui.slab.LocalSlabConfig
import xyz.headsdown.ui.slab.LocalSlabDebug
import xyz.headsdown.ui.slab.LocalTiltSource
import xyz.headsdown.ui.slab.NoTilt
import xyz.headsdown.ui.slab.SensorTiltSource
import xyz.headsdown.ui.slab.SlabConfig
import xyz.headsdown.ui.slab.SlabDebug
import xyz.headsdown.ui.slab.SlabGeometry
import xyz.headsdown.ui.slab.SlabHero
import xyz.headsdown.ui.slab.SlabLook
import xyz.headsdown.ui.slab.SlabMotion
import xyz.headsdown.ui.slab.SlabPalette
import xyz.headsdown.ui.slab.SlabProbe
import xyz.headsdown.ui.slab.SlabRenderer
import xyz.headsdown.ui.slab.SlabState
import xyz.headsdown.ui.slab.SlabWindow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * DEBUG AND LOCALDEV BUILDS ONLY (src/devtools; release does not compile this file). Entry point
 * to the slab lab, opened from the rig debug screen.
 */
object SlabLab {
    fun intent(context: Context): Intent = Intent(context, SlabLabActivity::class.java)
}

object SlabLabTags {
    const val READOUT = "slab-lab-readout"
    const val HERO = "slab-lab-hero"
}

/** Where the lab takes the slab's pose from. */
enum class LabPose { Rest, Sensor, Orbit, Fixed }

/** Everything the lab can switch, as snapshot state. */
internal class SlabLabModel {
    var renderer by mutableStateOf(SlabRenderer.Shader)
    var motion by mutableStateOf(SlabMotion.Live)
    var state by mutableStateOf(SlabState.Armed)
    var heat by mutableIntStateOf(0)
    var light by mutableStateOf(false)
    var pose by mutableStateOf(LabPose.Rest)
    var fixedX by mutableFloatStateOf(0f)
    var fixedY by mutableFloatStateOf(SlabGeometry.REST_DEGREES)
    var interactive by mutableStateOf(true)
    var glowArmed by mutableStateOf(false)
    var controls by mutableStateOf(true)
    var accelerometerOnly by mutableStateOf(false)
    var enterKey by mutableIntStateOf(0)

    /** The page's scroll, as a fraction of the hero's height. */
    var scroll by mutableFloatStateOf(0f)
    var heroHeightPx by mutableFloatStateOf(0f)

    /** A timed run owns the frame meter and the readout until it ends. */
    var running by mutableStateOf(false)
    var readout by mutableStateOf("No run yet. \"Run 10 s\" is the worst case: underside, five rows, orbiting.")
    val debug = SlabDebug()

    /**
     * `adb shell am start -n <package>/xyz.headsdown.devtools.SlabLabActivity --es pose 0,143 ...`
     * (the activity is exported in the localdev build only). Every extra is optional.
     */
    fun apply(intent: Intent) {
        intent.getStringExtra("renderer")?.let { renderer = if (it == "polygon") SlabRenderer.Polygon else SlabRenderer.Shader }
        intent.getStringExtra("motion")?.let { motion = if (it == "static") SlabMotion.Static else SlabMotion.Live }
        intent.getStringExtra("state")?.let { name -> SlabState.entries.firstOrNull { it.name.equals(name, true) }?.let { state = it } }
        if (intent.hasExtra("heat")) heat = intent.getIntExtra("heat", 0).coerceIn(0, 5)
        intent.getStringExtra("palette")?.let { light = it == "light" }
        intent.getStringExtra("pose")?.let { text ->
            when (text) {
                "rest" -> pose = LabPose.Rest
                "sensor" -> pose = LabPose.Sensor
                "orbit" -> pose = LabPose.Orbit
                else -> {
                    val parts = text.split(',').mapNotNull { it.trim().toFloatOrNull() }
                    if (parts.size == 2) {
                        fixedX = parts[0]
                        fixedY = parts[1]
                        pose = LabPose.Fixed
                    }
                }
            }
        }
        if (intent.hasExtra("controls")) controls = intent.getBooleanExtra("controls", true)
        if (intent.hasExtra("interactive")) interactive = intent.getBooleanExtra("interactive", true)
        if (intent.hasExtra("glow")) glowArmed = intent.getBooleanExtra("glow", false)
        if (intent.hasExtra("accel")) accelerometerOnly = intent.getBooleanExtra("accel", false)
        if (intent.hasExtra("passes")) debug.passes = intent.getIntExtra("passes", 1).coerceIn(1, 4)
        if (intent.hasExtra("halo")) debug.glowFraction = intent.getFloatExtra("halo", SlabLook.GLOW_FRACTION)
        if (intent.hasExtra("width")) debug.widthFraction = intent.getFloatExtra("width", SlabGeometry.WIDTH_FRACTION)
        if (intent.hasExtra("scroll")) scroll = intent.getFloatExtra("scroll", 0f).coerceIn(0f, 1f)
        if (intent.hasExtra("grain")) debug.grain = if (intent.getBooleanExtra("grain", true)) 1f else 0f
        if (intent.hasExtra("emboss")) debug.emboss = if (intent.getBooleanExtra("emboss", true)) 1f else 0f
        if (intent.hasExtra("squared")) debug.squared = intent.getBooleanExtra("squared", true)
        if (intent.getBooleanExtra("enter", false)) enterKey++
    }
}

/**
 * Collects `FrameMetrics` for the window on its own thread. The pass mark on the real phone: a
 * 10 s worst-case orbit with at most 1% janky frames and p95 at most 12 ms at 60 Hz, and at rest
 * at most 2 frames in 5 s.
 */
class FrameMeter(private val window: Window) {
    private val thread = HandlerThread("slab-lab-frames").also { it.start() }
    private val lock = Any()
    private var total = LongArray(4096)
    private var gpu = LongArray(4096)
    private var count = 0
    private var dropped = 0

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropCount ->
        synchronized(lock) {
            if (count == total.size) {
                total = total.copyOf(count * 2)
                gpu = gpu.copyOf(count * 2)
            }
            total[count] = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            gpu[count] = metrics.getMetric(FrameMetrics.GPU_DURATION)
            count++
            dropped += dropCount
        }
    }

    fun start() = window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))

    fun stop() {
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }

    fun reset() = synchronized(lock) {
        count = 0
        dropped = 0
    }

    fun frames(): Int = synchronized(lock) { count }

    /** One line of numbers for the frames since [reset], measured over [seconds] at [refreshHz]. */
    fun summary(seconds: Float, refreshHz: Float): String = synchronized(lock) {
        if (count == 0) return "0 frames in %.1f s: nothing was drawn".format(Locale.ROOT, seconds)
        val t = total.copyOf(count).also { it.sort() }
        val g = gpu.copyOf(count).also { it.sort() }
        // Janky: the frame took longer than one refresh from its intended vsync to its last buffer swap.
        val periodNanos = 1e9f / refreshHz
        val janky = t.count { it > periodNanos }
        fun ms(sorted: LongArray, q: Float) = sorted[((sorted.size - 1) * q).roundToInt()] / 1e6f
        val expected = (seconds * refreshHz).roundToInt()
        val jankyPercent = 100f * janky / count
        val p95 = ms(t, 0.95f)
        // The pass mark assumes 60 Hz; at another rate the numbers stand but the verdict does not.
        val verdict = when {
            refreshHz !in 59f..61f -> "NO VERDICT (not 60 Hz)"
            jankyPercent <= PASS_JANKY_PERCENT && p95 <= PASS_P95_MILLIS && count >= expected * 0.97f -> "PASS"
            else -> "FAIL"
        }
        val pattern = "%s: %d frames in %.1f s (%d expected at %.0f Hz), janky %.1f%% (%d), " +
            "total p50 %.1f p95 %.1f p99 %.1f max %.1f ms, gpu p95 %.1f ms, unreported %d. " +
            "Pass: janky at most 1%%, p95 at most 12 ms."
        pattern.format(
            Locale.ROOT, verdict, count, seconds, expected, refreshHz, jankyPercent, janky,
            ms(t, 0.5f), p95, ms(t, 0.99f), t.last() / 1e6f, ms(g, 0.95f), dropped,
        )
    }

    /** The same, short, for the once-a-second readout. */
    fun brief(refreshHz: Float): String = synchronized(lock) {
        if (count == 0) return "0 frames"
        val t = total.copyOf(count).also { it.sort() }
        val janky = t.count { it > 1e9f / refreshHz }
        "%d frames at %.0f Hz, janky %d, p50 %.1f p95 %.1f max %.1f ms".format(
            Locale.ROOT, count, refreshHz, janky,
            t[(count - 1) / 2] / 1e6f, t[((count - 1) * 0.95f).roundToInt()] / 1e6f, t.last() / 1e6f,
        )
    }

    companion object {
        const val PASS_JANKY_PERCENT = 1f
        const val PASS_P95_MILLIS = 12f
    }
}

/**
 * What the hero itself did over a stretch of time, from its own counters: a moving slab must be
 * drawn again and never composed again, and there is one shader for as long as it is on screen.
 */
internal class HeroCount {
    private val draws = SlabProbe.draws
    private val compositions = SlabProbe.compositions
    private val shaders = SlabProbe.shadersBuilt

    fun since(): String = "Hero: ${SlabProbe.draws - draws} draws, ${SlabProbe.compositions - compositions} recompositions, " +
        "${SlabProbe.shadersBuilt - shaders} new shaders (${SlabProbe.shadersBuilt} built since the app started)."
}

class SlabLabActivity : ComponentActivity() {
    private val model = SlabLabModel()
    private var meter: FrameMeter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish() // never reachable in release: the class is not even compiled there
            return
        }
        model.apply(intent)
        val frames = FrameMeter(window).also { it.start() }
        meter = frames
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // The rate is read when a run ends: the 60 Hz pin below takes a moment to apply.
                SlabLabScreen(model, frames, { display?.refreshRate ?: 60f }) { SensorTiltSource(this, preferGravity = !it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        model.apply(intent)
    }

    // What MainActivity will do while a live slab is on screen: the shader is budgeted for 60 Hz.
    override fun onResume() {
        super.onResume()
        SlabWindow.pinSixtyHertz(window)
    }

    override fun onPause() {
        SlabWindow.unpin(window)
        super.onPause()
    }

    override fun onDestroy() {
        meter?.stop()
        meter = null
        super.onDestroy()
    }
}

@Composable
private fun SlabLabScreen(
    model: SlabLabModel,
    frames: FrameMeter,
    refreshHz: () -> Float,
    sensor: (accelerometerOnly: Boolean) -> SensorTiltSource,
) {
    val palette = if (model.light) SlabPalette.Light else SlabPalette.Dark
    val ink = if (model.light) Color(0xFF151413) else Color(0xFFEFEAE0)
    val scope = rememberCoroutineScope()
    val tilt = remember(model.accelerometerOnly) { sensor(model.accelerometerOnly) }
    val debug = model.debug

    // The synthetic poses. The orbit is the worst case for the shader: the underside, the whole
    // board on screen, the lean and its direction both moving.
    LaunchedEffect(model.pose, model.fixedX, model.fixedY) {
        debug.poseOverride = model.pose == LabPose.Orbit || model.pose == LabPose.Fixed
        if (model.pose == LabPose.Fixed) {
            debug.poseX = model.fixedX
            debug.poseY = model.fixedY
        }
        if (model.pose != LabPose.Orbit) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val t = (now - start) * 1e-9
                val lean = 138.0 + 20.0 * sin(2.0 * PI * t / 3.7)
                val direction = PI / 2.0 + 0.95 * sin(2.0 * PI * t / 5.3)
                debug.poseX = (lean * cos(direction)).toFloat()
                debug.poseY = (lean * sin(direction)).toFloat()
            }
        }
    }

    // The live readout, once a second, ONLY while the orbit is already drawing every frame: at
    // rest a readout that updated itself would be the only thing drawing, and would be measured.
    LaunchedEffect(model.pose, model.running) {
        if (model.pose != LabPose.Orbit || model.running) return@LaunchedEffect
        while (true) {
            frames.reset()
            delay(1_000)
            model.readout = "LIVE ${model.renderer} x${debug.passes}, last second: " + frames.brief(refreshHz())
        }
    }

    Box(Modifier.fillMaxSize().background(palette.pit)) {
        CompositionLocalProvider(
            LocalSlabConfig provides SlabConfig(model.renderer, model.motion),
            LocalTiltSource provides if (model.pose == LabPose.Sensor) tilt else NoTilt,
            LocalSlabDebug provides debug,
        ) {
            androidx.compose.runtime.key(model.enterKey) {
                SlabHero(
                    state = model.state,
                    heat = model.heat,
                    modifier = Modifier.fillMaxSize().testTag(SlabLabTags.HERO)
                        .onSizeChanged { model.heroHeightPx = it.height.toFloat() },
                    palette = palette,
                    scrollPx = { model.scroll * model.heroHeightPx },
                    interactive = model.interactive,
                    enter = model.enterKey > 0,
                    contentDescription = "The slab",
                )
            }
            if (model.glowArmed) LayDownGlow(armed = true, modifier = Modifier.fillMaxSize())
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 10.dp)) {
            Text(
                model.readout,
                color = ink,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().testTag(SlabLabTags.READOUT).clickable { model.controls = !model.controls },
            )
            Box(Modifier.weight(1f))
            if (model.controls) {
                Column(
                    Modifier.fillMaxWidth().height(250.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Choices("run", ink) {
                        chip("Run 10 s", false) {
                            scope.launch {
                                // The worst case, whatever was on screen.
                                if (model.running) return@launch
                                model.running = true
                                model.state = SlabState.Hot
                                model.heat = 5
                                model.pose = LabPose.Orbit
                                model.readout = "Running the 10 s orbit…"
                                delay(1_500)
                                frames.reset()
                                val hero = HeroCount()
                                delay(10_000)
                                model.readout = "ORBIT ${model.renderer} x${debug.passes} " +
                                    frames.summary(10f, refreshHz()) + " " + hero.since()
                                // Back to rest, so the result is not overwritten by the live readout.
                                model.pose = LabPose.Rest
                                model.running = false
                            }
                        }
                        chip("Rest 5 s", false) {
                            scope.launch {
                                if (model.running) return@launch
                                model.running = true
                                if (model.pose == LabPose.Orbit) model.pose = LabPose.Rest
                                // Let the chip's own ripple and this line finish drawing first.
                                model.readout = "Counting frames at rest for 5 s: hands off…"
                                delay(2_000)
                                frames.reset()
                                val hero = HeroCount()
                                delay(5_000)
                                val n = frames.frames()
                                val verdict = if (n <= 2) "PASS" else "FAIL"
                                model.readout = "REST ${model.pose} $verdict: $n frames in 5 s. Pass: at most 2. " + hero.since()
                                model.running = false
                            }
                        }
                        chip("Enter", false) { model.enterKey++ }
                        chip("Hide", false) { model.controls = false }
                    }
                    Choices("renderer", ink) {
                        SlabRenderer.entries.forEach { r -> chip(r.name, model.renderer == r) { model.renderer = r } }
                        SlabMotion.entries.forEach { m -> chip(m.name, model.motion == m) { model.motion = m } }
                    }
                    Choices("pose", ink) {
                        chip("Rest", model.pose == LabPose.Rest) { model.pose = LabPose.Rest }
                        chip("Sensor", model.pose == LabPose.Sensor) { model.pose = LabPose.Sensor }
                        chip("Orbit", model.pose == LabPose.Orbit) { model.pose = LabPose.Orbit }
                        listOf("Face" to 0f, "Edge" to 90f, "Under" to 143f, "Below" to 165f).forEach { (name, lean) ->
                            chip(name, model.pose == LabPose.Fixed && model.fixedY == lean && model.fixedX == 0f) {
                                model.fixedX = 0f
                                model.fixedY = lean
                                model.pose = LabPose.Fixed
                            }
                        }
                    }
                    Choices("state", ink) {
                        SlabState.entries.forEach { s -> chip(s.name, model.state == s) { model.state = s } }
                    }
                    Choices("heat", ink) {
                        (0..5).forEach { n -> chip("$n", model.heat == n) { model.heat = n } }
                        chip("Light", model.light) { model.light = !model.light }
                        chip("Drag", model.interactive) { model.interactive = !model.interactive }
                        chip("Glow", model.glowArmed) { model.glowArmed = !model.glowArmed }
                        chip("Accel", model.accelerometerOnly) { model.accelerometerOnly = !model.accelerometerOnly }
                    }
                    Choices("passes", ink) {
                        (1..4).forEach { n -> chip("x$n", debug.passes == n) { debug.passes = n } }
                        listOf(0f, 0.08f, SlabLook.GLOW_FRACTION, 0.26f).forEach { f ->
                            chip("halo ${(f * 100).roundToInt()}", debug.glowFraction == f) { debug.glowFraction = f }
                        }
                    }
                    Choices("scroll", ink) {
                        listOf(0f, 0.15f, 0.3f, 0.45f, 0.6f, 0.8f, 1f).forEach { f ->
                            chip("${(f * 100).roundToInt()}%", model.scroll == f) { model.scroll = f }
                        }
                    }
                    Choices("quality", ink) {
                        chip("grain", debug.grain > 0f) { debug.grain = if (debug.grain > 0f) 0f else 1f }
                        chip("emboss", debug.emboss > 0f) { debug.emboss = if (debug.emboss > 0f) 0f else 1f }
                        chip("squared", debug.squared) { debug.squared = !debug.squared }
                        listOf(0.5f, SlabGeometry.WIDTH_FRACTION, 0.75f).forEach { f ->
                            chip("w ${(f * 100).roundToInt()}", debug.widthFraction == f) { debug.widthFraction = f }
                        }
                    }
                }
            }
        }
    }
}

private class ChoiceScope(val ink: Color) {
    val items = ArrayList<Triple<String, Boolean, () -> Unit>>()
    fun chip(label: String, selected: Boolean, onClick: () -> Unit) {
        items += Triple(label, selected, onClick)
    }
}

@Composable
private fun Choices(label: String, ink: Color, content: ChoiceScope.() -> Unit) {
    val scope = ChoiceScope(ink).apply(content)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = ink.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        scope.items.forEach { (text, selected, onClick) ->
            val shape = RoundedCornerShape(6.dp)
            Text(
                text,
                color = if (selected) Color(0xFF0C0C0D) else ink,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(if (selected) Color(0xFFF2B233) else Color.Transparent, shape)
                    .border(1.dp, ink.copy(alpha = 0.35f), shape)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            )
        }
    }
}
