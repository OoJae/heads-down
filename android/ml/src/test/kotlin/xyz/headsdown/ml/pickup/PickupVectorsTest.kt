package xyz.headsdown.ml.pickup

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.ml.FailClosedReason
import xyz.headsdown.ml.PickupVerdict
import xyz.headsdown.ml.TestResources
import kotlin.math.abs

/**
 * Cross-language check against `ml/foreman/classifier` (python): `pickup_vectors.json` holds raw
 * windows and what the reference computed from them at every stage. Tolerance 1e-6 (the task's
 * bar); the observed differences are ~1e-12, reported by [`max observed difference is tiny`].
 */
class PickupVectorsTest {

    private val vectors = TestResources.json("/foreman/pickup_vectors.json")
    private val tol = vectors["tolerance"]!!.jsonPrimitive.double
    private val windows = vectors["windows"]!!.jsonArray.map { it.jsonObject }
    private val docs = listOf("logistic", "gbdt", "cnn").associateWith {
        PickupModelDocument.parse(TestResources.text("/foreman/pickup_$it.json"))
    }
    private var maxDiff = 0.0

    private fun close(expected: Double, actual: Double, what: String) {
        val d = abs(expected - actual)
        maxDiff = maxOf(maxDiff, d)
        assertTrue("$what: expected $expected, got $actual (|diff| $d > $tol)", d <= tol)
    }

    @Test
    fun `the spec version, feature names and channel names match`() {
        assertEquals(PickupFeatures.SPEC_VERSION, vectors["feature_spec"]!!.jsonPrimitive.int)
        assertEquals(PickupFeatures.FEATURE_NAMES, vectors["feature_names"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(PickupFeatures.CHANNEL_NAMES, vectors["channels"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("need a real set of vectors", windows.size >= 20)
    }

    @Test
    fun `the motion trigger fires on exactly the reference samples`() {
        val trig = vectors["trigger"]!!.jsonObject
        val t = trig["t_ns"]!!.jsonArray.map { it.jsonPrimitive.long }
        val xyz = trig["xyz"]!!.jsonArray
        val expected = trig["fired_ns"]!!.jsonArray.map { it.jsonPrimitive.long }
        val trigger = MotionTrigger()
        val fired = t.indices.filter { i ->
            val r = xyz[i].jsonArray
            trigger.onSample(t[i], r[0].jsonPrimitive.double.toFloat(), r[1].jsonPrimitive.double.toFloat(), r[2].jsonPrimitive.double.toFloat())
        }.map { t[it] }
        assertEquals(expected, fired)
        assertEquals("rest, a knock, then a slow rotation that outlasts the refractory period", 3, fired.size)
    }

    @Test
    fun `cleaning and the fail-closed quality check match`() {
        var failClosed = 0
        for (w in windows) {
            val window = TestResources.window(w["input"]!!.jsonObject)
            val c = PickupFeatures.clean(window)
            assertEquals(w["name"].toString(), w["clean_count"]!!.jsonPrimitive.int, c.size)
            val q = PickupFeatures.quality(c, window.triggerNanos)
            val expected = w["quality"]
            if (expected == null || expected is JsonNull) {
                assertNull(w["name"].toString(), q)
            } else {
                failClosed++
                val want = when (expected.jsonPrimitive.content) {
                    "too_few_samples" -> FailClosedReason.TOO_FEW_SAMPLES
                    "gap" -> FailClosedReason.GAP
                    else -> error("unknown reason $expected")
                }
                assertEquals(w["name"].toString(), want, q)
            }
        }
        assertTrue("the edge cases include fail-closed windows", failClosed >= 3)
    }

    @Test
    fun `resampling, features and CNN channels match the python reference`() {
        var checked = 0
        for (w in windows) {
            if (w["features"] == null) continue
            val name = w["name"]!!.jsonPrimitive.content
            val ex = PickupFeatures.extract(TestResources.window(w["input"]!!.jsonObject))
            assertNull(name, ex.failReason)
            val grid = ex.grid!!
            for ((row, values) in w["grid_rows"]!!.jsonObject) {
                val exp = TestResources.doubles(values)
                for (k in 0..2) close(exp[k], grid[row.toInt()][k], "$name grid[$row][$k]")
            }
            val f = TestResources.doubles(w["features"]!!)
            assertEquals(PickupFeatures.FEATURE_NAMES.size, f.size)
            for (j in f.indices) close(f[j], ex.features!![j], "$name ${PickupFeatures.FEATURE_NAMES[j]}")
            for ((row, values) in w["channels_rows"]!!.jsonObject) {
                val exp = TestResources.doubles(values)
                for (k in exp.indices) close(exp[k], ex.channels!![row.toInt()][k], "$name channel[$row][$k]")
            }
            val sums = TestResources.doubles(w["channels_sum"]!!)
            for (k in sums.indices) close(sums[k], ex.channels!!.sumOf { it[k] }, "$name channel sum $k")
            checked++
        }
        assertTrue(checked >= 20)
    }

    @Test
    fun `all three exported models reproduce the reference logits, probabilities and verdicts`() {
        var pickups = 0
        var others = 0
        for (w in windows) {
            val outs = w["outputs"]?.jsonObject ?: continue
            val name = w["name"]!!.jsonPrimitive.content
            val ex = PickupFeatures.extract(TestResources.window(w["input"]!!.jsonObject))
            for ((kind, doc) in docs) {
                val o = outs[kind]!!.jsonObject
                val z = doc.model.logit(ex.features!!, ex.channels!!)
                close(o.num("logit"), z, "$name $kind logit")
                val lg = doc.calibratedLogit(z)
                close(o.num("calibrated_logit"), lg, "$name $kind calibrated logit")
                close(o.num("p"), doc.probability(lg), "$name $kind p")
                assertEquals("$name $kind verdict", o["pickup"]!!.jsonPrimitive.boolean, lg >= doc.thresholdLogit)
                val decision = ModelPickupClassifier(doc).classify(TestResources.window(w["input"]!!.jsonObject))
                assertEquals(o["pickup"]!!.jsonPrimitive.boolean, decision.verdict == PickupVerdict.PICKUP)
                assertNull(decision.failClosed)
            }
            if (w["label"]!!.jsonPrimitive.content == "pickup") pickups++ else others++
        }
        assertTrue("vectors cover both classes", pickups >= 5 && others >= 5)
    }

    @Test
    fun `fail-closed windows are pickups without asking the model`() {
        val doc = docs.getValue("logistic")
        val bad = windows.filter { it["quality"] != null && it["quality"] !is JsonNull }
        assertTrue(bad.size >= 3)
        for (w in bad) {
            val d = ModelPickupClassifier(doc).classify(TestResources.window(w["input"]!!.jsonObject))
            assertEquals(PickupVerdict.PICKUP, d.verdict)
            assertNotNull(d.failClosed)
            assertEquals(1.0, d.pPickup, 0.0)
        }
    }

    @Test
    fun `max observed difference is tiny`() {
        `resampling, features and CNN channels match the python reference`()
        `all three exported models reproduce the reference logits, probabilities and verdicts`()
        println("pickup vectors: max |kotlin - python| = $maxDiff over ${windows.size} windows")
        assertTrue("expected float64 agreement far below the 1e-6 bar, got $maxDiff", maxDiff < 1e-9)
    }

    private fun JsonObject.num(key: String): Double = this[key]!!.jsonPrimitive.double
}
