package xyz.headsdown.ml.pickup

import xyz.headsdown.ml.FailClosedReason
import xyz.headsdown.ml.MotionWindow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Feature pipeline v1 (`ml/foreman/classifier/FEATURE_SPEC.md`), a line-by-line port of the
 * Python reference `ml/foreman/classifier/features.py`: integer-nanosecond resampling onto a
 * 250-point 50 Hz grid, sequential sums, the same operation order and constants. Checked against
 * `pickup_vectors.json` at 1e-6 (observed ~1e-12).
 */
object PickupFeatures {
    const val SPEC_VERSION = 1
    const val G = 9.80665
    const val N = 250
    const val T0 = 100
    const val STEP_NS = 20_000_000L
    const val DT = 0.02
    const val TAU = 0.4
    const val ALPHA = DT / (TAU + DT)
    const val RAD_TO_DEG = 57.29577951308232
    const val PRE_NS = 2_000_000_000L
    const val POST_NS = 3_000_000_000L
    const val MIN_SAMPLES = 100
    const val MAX_GAP_NS = 500_000_000L
    private const val N_FFT = 150

    val FEATURE_NAMES: List<String> = listOf(
        "pre_tilt", "pre_motion", "pre_mag", "tilt_end", "tilt_max", "tilt_min", "tilt_path",
        "rot_end", "rot_max", "rot_1s", "rot_2s", "rot_path", "late_motion", "tail_motion",
        "late_mag_std", "early_dyn", "mid_dyn", "late_dyn", "dyn_peak", "mag_dev_peak", "settle",
        "jerk_post", "jerk_late", "jerk_peak", "up_peak", "down_peak", "vel_up_max", "vel_up_min",
        "disp_up_max", "horiz_rms", "horiz_peak", "vel_h_max", "spec_lo", "spec_mid", "spec_hi",
        "spec_log_energy", "spec_centroid", "rot_late_std", "late_mag_dev",
    )
    val CHANNEL_NAMES: List<String> = listOf("mag_dev", "up_dyn", "horiz", "tilt", "rot")

    private val COS = DoubleArray(N_FFT) { cos(2.0 * PI * it / N_FFT.toDouble()) }
    private val SIN = DoubleArray(N_FFT) { sin(2.0 * PI * it / N_FFT.toDouble()) }
    private val HANN = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2.0 * PI * it / (N_FFT - 1).toDouble()) }

    /** Samples after cleaning: strictly increasing timestamps, finite values, as doubles. */
    class Cleaned(val t: LongArray, val xyz: Array<DoubleArray>) {
        val size: Int get() = t.size
    }

    /** Resampled grid (250 x 3) plus features and CNN channels, or the fail-closed reason. */
    class Extraction(
        val failReason: FailClosedReason?,
        val grid: Array<DoubleArray>?,
        val features: DoubleArray?,
        val channels: Array<DoubleArray>?,
    )

    fun extract(window: MotionWindow): Extraction {
        val c = clean(window)
        val reason = quality(c, window.triggerNanos)
        if (reason != null) return Extraction(reason, null, null, null)
        val grid = resample(c, window.triggerNanos)
        val s = signals(grid)
        return Extraction(null, grid, features(grid, s), channels(s))
    }

    /** Drops non-finite samples and any sample whose timestamp does not increase (keep-first). */
    fun clean(window: MotionWindow): Cleaned {
        val t = ArrayList<Long>(window.samples.size)
        val v = ArrayList<DoubleArray>(window.samples.size)
        var last = Long.MIN_VALUE
        var any = false
        for (s in window.samples) {
            if (!s.x.isFinite() || !s.y.isFinite() || !s.z.isFinite()) continue
            if (any && s.tNanos <= last) continue
            t += s.tNanos
            v += doubleArrayOf(s.x.toDouble(), s.y.toDouble(), s.z.toDouble())
            last = s.tNanos
            any = true
        }
        return Cleaned(t.toLongArray(), v.toTypedArray())
    }

    /** Null if usable; else why the window is fail-closed. */
    fun quality(c: Cleaned, triggerNanos: Long): FailClosedReason? {
        val lo = triggerNanos - PRE_NS
        val hi = triggerNanos + POST_NS
        var count = 0
        var prev = lo
        var maxGap = 0L
        for (t in c.t) {
            if (t < lo || t > hi) continue
            count++
            maxGap = maxOf(maxGap, t - prev)
            prev = t
        }
        if (count < MIN_SAMPLES) return FailClosedReason.TOO_FEW_SAMPLES
        maxGap = maxOf(maxGap, hi - prev)
        return if (maxGap > MAX_GAP_NS) FailClosedReason.GAP else null
    }

    /** Linear interpolation onto t_i = trigger + (i - 100) * 20 ms; edges hold the end value. */
    fun resample(c: Cleaned, triggerNanos: Long): Array<DoubleArray> {
        require(c.size > 0) { "no samples" }
        val n = c.size
        val out = Array(N) { DoubleArray(3) }
        var j = -1
        for (i in 0 until N) {
            val g = triggerNanos + (i - T0).toLong() * STEP_NS
            while (j + 1 < n && c.t[j + 1] <= g) j++
            val row = out[i]
            when {
                j < 0 -> c.xyz[0].copyInto(row)
                j >= n - 1 -> c.xyz[n - 1].copyInto(row)
                else -> {
                    val num = (g - c.t[j]).toDouble()
                    val den = (c.t[j + 1] - c.t[j]).toDouble()
                    val frac = num / den
                    val y0 = c.xyz[j]
                    val y1 = c.xyz[j + 1]
                    for (k in 0..2) row[k] = y0[k] + (y1[k] - y0[k]) * frac
                }
            }
        }
        return out
    }

    /** Per-sample signals derived from the grid (see FEATURE_SPEC.md §3). */
    class Signals(
        val r: DoubleArray,
        val rm: Double,
        val u: DoubleArray,
        val g: Array<DoubleArray>,
        val m: DoubleArray,
        val theta: DoubleArray,
        val phi: DoubleArray,
        val dm: DoubleArray,
        val w: DoubleArray,
        val hvec: Array<DoubleArray>,
        val h: DoubleArray,
    )

    fun signals(a: Array<DoubleArray>): Signals {
        val r = DoubleArray(3)
        for (k in 0..2) {
            var s = 0.0
            for (i in 0 until 90) s += a[i][k]
            r[k] = s / 90.0
        }
        val rm = norm(r)
        val u = if (rm >= 1e-6) doubleArrayOf(r[0] / rm, r[1] / rm, r[2] / rm) else doubleArrayOf(0.0, 0.0, -1.0)
        val g = Array(N) { DoubleArray(3) }
        val cur = r.copyOf()
        for (i in 0 until N) {
            for (k in 0..2) cur[k] = cur[k] + ALPHA * (a[i][k] - cur[k])
            cur.copyInto(g[i])
        }
        val m = DoubleArray(N) { norm(a[it]) }
        val theta = DoubleArray(N) { tiltDeg(g[it]) }
        val phi = DoubleArray(N) { angleDeg(g[it], r) }
        val dm = DoubleArray(N) {
            val d0 = a[it][0] - g[it][0]
            val d1 = a[it][1] - g[it][1]
            val d2 = a[it][2] - g[it][2]
            sqrt(d0 * d0 + d1 * d1 + d2 * d2)
        }
        val p = DoubleArray(N) { dot(a[it], u) }
        val w = DoubleArray(N) { p[it] - rm }
        val hvec = Array(N) { i -> DoubleArray(3) { k -> a[i][k] - p[i] * u[k] } }
        val h = DoubleArray(N) { norm(hvec[it]) }
        return Signals(r, rm, u, g, m, theta, phi, dm, w, hvec, h)
    }

    fun features(a: Array<DoubleArray>, s: Signals = signals(a)): DoubleArray {
        val f = DoubleArray(FEATURE_NAMES.size)
        var n = 0
        val r = s.r
        f[n++] = tiltDeg(r) // pre_tilt
        var acc = 0.0
        for (i in 0 until 90) {
            val d0 = a[i][0] - r[0]
            val d1 = a[i][1] - r[1]
            val d2 = a[i][2] - r[2]
            acc += d0 * d0 + d1 * d1 + d2 * d2
        }
        f[n++] = sqrt(acc / 90.0) // pre_motion
        f[n++] = s.rm / G // pre_mag
        f[n++] = mean(s.theta, 225, 250) // tilt_end
        f[n++] = max(s.theta, 100, 250) // tilt_max
        f[n++] = min(s.theta, 100, 250) // tilt_min
        acc = 0.0
        for (i in 100 until 250) acc += abs(s.theta[i] - s.theta[i - 1])
        f[n++] = acc // tilt_path
        val tail = DoubleArray(3)
        for (k in 0..2) {
            var t = 0.0
            for (i in 225 until 250) t += a[i][k]
            tail[k] = t / 25.0
        }
        f[n++] = angleDeg(tail, r) // rot_end
        f[n++] = max(s.phi, 100, 250) // rot_max
        f[n++] = s.phi[150] // rot_1s
        f[n++] = s.phi[200] // rot_2s
        acc = 0.0
        for (i in 100 until 250) acc += angleDeg(s.g[i], s.g[i - 1])
        f[n++] = acc // rot_path
        f[n++] = vecMotion(a, 200, 250) // late_motion
        f[n++] = vecMotion(a, 225, 250) // tail_motion
        f[n++] = popStd(s.m, 200, 250) // late_mag_std
        val early = rms(s.dm, 100, 150)
        val mid = rms(s.dm, 150, 200)
        val late = rms(s.dm, 200, 250)
        f[n++] = early
        f[n++] = mid
        f[n++] = late
        f[n++] = max(s.dm, 100, 250) // dyn_peak
        var mdp = Double.NEGATIVE_INFINITY
        for (i in 100 until 250) mdp = maxOf(mdp, abs(s.m[i] - s.rm))
        f[n++] = mdp // mag_dev_peak
        f[n++] = ln((late + 0.01) / (early + 0.01)) // settle
        val jerk = DoubleArray(N - 1) {
            val d0 = a[it + 1][0] - a[it][0]
            val d1 = a[it + 1][1] - a[it][1]
            val d2 = a[it + 1][2] - a[it][2]
            sqrt(d0 * d0 + d1 * d1 + d2 * d2) / DT
        }
        acc = 0.0
        for (i in 100 until 249) acc += jerk[i] * jerk[i]
        f[n++] = ln(1.0 + sqrt(acc / 149.0)) // jerk_post
        acc = 0.0
        for (i in 200 until 249) acc += jerk[i] * jerk[i]
        f[n++] = ln(1.0 + sqrt(acc / 49.0)) // jerk_late
        f[n++] = ln(1.0 + max(jerk, 100, 249)) // jerk_peak
        f[n++] = max(s.w, 100, 250) // up_peak
        f[n++] = min(s.w, 100, 250) // down_peak
        var v = 0.0
        var d = 0.0
        var vMax = Double.NEGATIVE_INFINITY
        var vMin = Double.POSITIVE_INFINITY
        var dMax = Double.NEGATIVE_INFINITY
        for (i in 100 until 200) {
            v += s.w[i] * DT
            d += v * DT
            vMax = maxOf(vMax, v)
            vMin = minOf(vMin, v)
            dMax = maxOf(dMax, d)
        }
        f[n++] = vMax // vel_up_max
        f[n++] = vMin // vel_up_min
        f[n++] = dMax // disp_up_max
        f[n++] = rms(s.h, 100, 250) // horiz_rms
        f[n++] = max(s.h, 100, 250) // horiz_peak
        val vh = DoubleArray(3)
        var vhMax = Double.NEGATIVE_INFINITY
        for (i in 100 until 200) {
            for (k in 0..2) vh[k] += s.hvec[i][k] * DT
            vhMax = maxOf(vhMax, norm(vh))
        }
        f[n++] = vhMax // vel_h_max
        // Spectrum of |a| over POST (mean removed, Hann), bins k = 1..75 at k/3 Hz.
        var mu = 0.0
        for (i in 100 until 250) mu += s.m[i]
        mu /= 150.0
        val x = DoubleArray(N_FFT) { (s.m[100 + it] - mu) * HANN[it] }
        val power = DoubleArray(75)
        for (k in 1..75) {
            var re = 0.0
            var im = 0.0
            for (j in 0 until N_FFT) {
                val idx = (k * j) % N_FFT
                re += x[j] * COS[idx]
                im += x[j] * SIN[idx]
            }
            power[k - 1] = re * re + im * im
        }
        var e = 0.0
        for (k in 0 until 75) e += power[k]
        f[n++] = sum(power, 0, 5) / (e + 1e-9) // spec_lo
        f[n++] = sum(power, 5, 14) / (e + 1e-9) // spec_mid
        f[n++] = sum(power, 14, 29) / (e + 1e-9) // spec_hi
        f[n++] = ln(e / 150.0 + 1e-6) // spec_log_energy
        acc = 0.0
        for (k in 0 until 75) acc += power[k] * ((k + 1) / 3.0)
        f[n++] = acc / (e + 1e-9) // spec_centroid
        f[n++] = popStd(s.phi, 200, 250) // rot_late_std
        f[n++] = abs(mean(s.m, 200, 250) - s.rm) // late_mag_dev
        check(n == FEATURE_NAMES.size)
        return f
    }

    /** CNN input (250 x 5): |a| deviation, up dynamic, horizontal, tilt, rotation (all ~O(1)). */
    fun channels(s: Signals): Array<DoubleArray> = Array(N) { i ->
        doubleArrayOf(
            (s.m[i] - s.rm) / G,
            s.w[i] / G,
            s.h[i] / G,
            s.theta[i] / 180.0,
            s.phi[i] / 180.0,
        )
    }

    // ------------------------------------------------------------------------------ helpers

    private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** atan2(|a x b|, a . b) in degrees. */
    private fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val cx = a[1] * b[2] - a[2] * b[1]
        val cy = a[2] * b[0] - a[0] * b[2]
        val cz = a[0] * b[1] - a[1] * b[0]
        return atan2(sqrt(cx * cx + cy * cy + cz * cz), dot(a, b)) * RAD_TO_DEG
    }

    /** Angle from screen-straight-down: atan2(sqrt(gx² + gy²), -gz), degrees. */
    private fun tiltDeg(g: DoubleArray): Double = atan2(sqrt(g[0] * g[0] + g[1] * g[1]), -g[2]) * RAD_TO_DEG

    private fun sum(x: DoubleArray, lo: Int, hi: Int): Double {
        var s = 0.0
        for (i in lo until hi) s += x[i]
        return s
    }

    private fun mean(x: DoubleArray, lo: Int, hi: Int): Double = sum(x, lo, hi) / (hi - lo).toDouble()

    private fun rms(x: DoubleArray, lo: Int, hi: Int): Double {
        var s = 0.0
        for (i in lo until hi) s += x[i] * x[i]
        return sqrt(s / (hi - lo).toDouble())
    }

    private fun popStd(x: DoubleArray, lo: Int, hi: Int): Double {
        val mu = mean(x, lo, hi)
        var s = 0.0
        for (i in lo until hi) {
            val d = x[i] - mu
            s += d * d
        }
        return sqrt(s / (hi - lo).toDouble())
    }

    private fun max(x: DoubleArray, lo: Int, hi: Int): Double {
        var m = Double.NEGATIVE_INFINITY
        for (i in lo until hi) if (x[i] > m) m = x[i]
        return m
    }

    private fun min(x: DoubleArray, lo: Int, hi: Int): Double {
        var m = Double.POSITIVE_INFINITY
        for (i in lo until hi) if (x[i] < m) m = x[i]
        return m
    }

    /** sqrt(mean |a_i - mean(a)|²) over [lo, hi). */
    private fun vecMotion(a: Array<DoubleArray>, lo: Int, hi: Int): Double {
        val cnt = (hi - lo).toDouble()
        val mu = DoubleArray(3)
        for (k in 0..2) {
            var s = 0.0
            for (i in lo until hi) s += a[i][k]
            mu[k] = s / cnt
        }
        var s = 0.0
        for (i in lo until hi) {
            val d0 = a[i][0] - mu[0]
            val d1 = a[i][1] - mu[1]
            val d2 = a[i][2] - mu[2]
            s += d0 * d0 + d1 * d1 + d2 * d2
        }
        return sqrt(s / cnt)
    }
}
