package xyz.headsdown.feature.shift.foreman

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import xyz.headsdown.ml.ForemanModels
import xyz.headsdown.ml.PickupClassifier
import xyz.headsdown.ml.pickup.MotionTrigger
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** An accelerometer stream as the sensor thread sees it: primitive arrays, nothing to allocate while feeding. */
class Stream(val t: LongArray, val x: FloatArray, val y: FloatArray, val z: FloatArray) {
    val size: Int get() = t.size

    inline fun forEach(action: (tNanos: Long, x: Float, y: Float, z: Float) -> Unit) {
        for (i in t.indices) action(t[i], x[i], y[i], z[i])
    }
}

/**
 * Deterministic streams for the wiring tests (which windows get judged, what a verdict does).
 * Not classifier accuracy: for that the tests replay the Python-generated vector windows.
 * The posture is the angle of gravity from "screen straight down": 0 reads (0, 0, -g).
 */
class StreamBuilder(
    seed: Long = 1,
    private val rateHz: Double = 50.0,
    private val noise: Double = 0.02,
    startNanos: Long = 1_000_000_000L,
) {
    private val rnd = Random(seed)
    private val ts = ArrayList<Long>()
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val zs = ArrayList<Float>()
    private val stepNanos = (1e9 / rateHz).toLong()

    var nowNanos: Long = startNanos
        private set
    var tiltDegrees: Double = 0.0
        private set

    private fun emit(tilt: Double, dx: Double = 0.0, dz: Double = 0.0) {
        val a = Math.toRadians(tilt)
        ts += nowNanos
        xs += (G * sin(a) + dx + rnd.nextGaussian() * noise).toFloat()
        ys += (rnd.nextGaussian() * noise).toFloat()
        zs += (-G * cos(a) + dz + rnd.nextGaussian() * noise).toFloat()
        nowNanos += stepNanos
    }

    private fun samples(seconds: Double): Int = (seconds * rateHz).toInt()

    /** The phone lies still in its current posture. */
    fun rest(seconds: Double) = apply { repeat(samples(seconds)) { emit(tiltDegrees) } }

    /** A knock on the furniture: a few samples far off 1 g, the posture unchanged. */
    fun knock(peak: Double = 5.0, samples: Int = 3) = apply {
        repeat(samples) { i -> emit(tiltDegrees, dx = if (i % 2 == 0) peak * 0.4 else -peak * 0.3, dz = if (i % 2 == 0) -peak else peak * 0.5) }
    }

    /** Rotates smoothly to [tiltDegrees] over [seconds] and stays there. */
    fun rotateTo(tiltDegrees: Double, seconds: Double) = apply {
        val from = this.tiltDegrees
        val n = samples(seconds).coerceAtLeast(1)
        for (i in 1..n) emit(from + (tiltDegrees - from) * i / n)
        this.tiltDegrees = tiltDegrees
    }

    /** Held in a hand: the posture sways and the hand trembles. */
    fun sway(seconds: Double, amplitudeDegrees: Double = 5.0, hz: Double = 0.7, tremor: Double = 0.15) = apply {
        for (i in 0 until samples(seconds)) {
            val phase = 2 * PI * hz * i / rateHz
            emit(tiltDegrees + amplitudeDegrees * sin(phase), dx = tremor * sin(9.0 * phase), dz = tremor * cos(11.0 * phase))
        }
    }

    /** The stream stalls: no samples for [seconds]. */
    fun gap(seconds: Double) = apply { nowNanos += (seconds * 1e9).toLong() }

    fun build(): Stream = Stream(ts.toLongArray(), xs.toFloatArray(), ys.toFloatArray(), zs.toFloatArray())

    companion object {
        const val G = 9.80665
    }
}

/** One window of `pickup_vectors.json`: recorded synthetic samples and what the reference model said. */
class VectorWindow(val name: String, val label: String, val triggerNanos: Long, val stream: Stream, val expectedPickup: Boolean?)

/**
 * The files :ml ships and is pinned to (read as test resources straight from :ml's folders, see
 * build.gradle.kts): the Python-generated vectors and the model asset itself.
 */
object ForemanVectors {
    fun text(path: String): String =
        ForemanVectors::class.java.getResource(path)?.readText() ?: error("missing test resource $path (from :ml)")

    fun json(path: String): JsonObject = Json.parseToJsonElement(text(path)).jsonObject

    /** The pickup model exactly as the APK ships it. */
    val shippedModelJson: String by lazy { text("/foreman/pickup_model.json") }

    fun shippedClassifier(): PickupClassifier = ForemanModels.pickupClassifier(shippedModelJson)

    private val vectors by lazy { json("/foreman/pickup_vectors.json") }

    /** The vectors' free-running stream: rest, a knock, then a slow rotation that stays. */
    val triggerStream: Stream by lazy { stream(vectors["trigger"]!!) }
    val triggerStreamFires: List<Long> by lazy { vectors["trigger"]!!.jsonObject["fired_ns"]!!.jsonArray.map { it.jsonPrimitive.long } }

    /** Every recorded window, with the shipped model's reference verdict where the window is usable. */
    val windows: List<VectorWindow> by lazy {
        val kind = Json.parseToJsonElement(shippedModelJson).jsonObject["model"]!!.jsonObject["type"]!!.jsonPrimitive.content
        vectors["windows"]!!.jsonArray.map { it.jsonObject }.map { w ->
            val input = w["input"]!!.jsonObject
            val verdict = w["outputs"]?.takeIf { it !is JsonNull }?.jsonObject?.get(kind)?.jsonObject?.get("pickup")?.jsonPrimitive?.boolean
            VectorWindow(
                name = w["name"]!!.jsonPrimitive.content,
                label = w["label"]!!.jsonPrimitive.content,
                triggerNanos = input["trigger_ns"]!!.jsonPrimitive.long,
                stream = stream(input),
                expectedPickup = verdict,
            )
        }
    }

    /**
     * The recorded windows that replay as a live stream: fed alone to a fresh trigger, it fires
     * first on the recorded trigger sample (true for every window that starts at rest), so the
     * collector cuts exactly the recorded window.
     */
    val replayable: List<VectorWindow> by lazy {
        windows.filter { w ->
            if (w.expectedPickup == null) return@filter false
            val trigger = MotionTrigger()
            var first: Long? = null
            w.stream.forEach { t, x, y, z -> if (trigger.onSample(t, x, y, z) && first == null) first = t }
            first == w.triggerNanos
        }
    }

    private fun stream(e: JsonElement): Stream {
        val t = e.jsonObject["t_ns"]!!.jsonArray.map { it.jsonPrimitive.long }
        val xyz = e.jsonObject["xyz"]!!.jsonArray.map { it.jsonArray }
        return Stream(
            t.toLongArray(),
            FloatArray(t.size) { xyz[it].f(0) },
            FloatArray(t.size) { xyz[it].f(1) },
            FloatArray(t.size) { xyz[it].f(2) },
        )
    }

    /** JSON number -> double -> float; null -> NaN (the vectors encode NaN inputs as null). */
    private fun JsonArray.f(i: Int): Float = this[i].let { if (it is JsonNull) Float.NaN else it.jsonPrimitive.double.toFloat() }
}

/**
 * Bytes allocated by the current thread, from HotSpot's `com.sun.management.ThreadMXBean`. Looked
 * up by reflection because Android unit tests compile against android.jar, which has no
 * `java.lang.management`. Tests that need it skip themselves on a JVM without it.
 */
object Allocations {
    private val bean: Any? = runCatching {
        Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    }.getOrNull()

    private val allocatedBytes = runCatching {
        Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
    }.getOrNull()

    val supported: Boolean = runCatching { read() >= 0 }.getOrDefault(false)

    @Suppress("DEPRECATION") // Thread.threadId() only exists from Java 19
    private fun read(): Long = allocatedBytes!!.invoke(bean, Thread.currentThread().id) as Long

    /** What the two reflective reads themselves allocate (boxing, argument arrays). */
    private fun overhead(): Long {
        var least = Long.MAX_VALUE
        repeat(20) {
            val before = read()
            val after = read()
            least = minOf(least, after - before)
        }
        return least
    }

    /** Bytes [block] allocated on this thread (0 for an allocation-free block). */
    fun during(block: () -> Unit): Long {
        val overhead = overhead()
        val before = read()
        block()
        val after = read()
        return (after - before - overhead).coerceAtLeast(0)
    }
}
