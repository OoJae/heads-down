package xyz.headsdown.ml.pickup

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.ml.AccelSample
import xyz.headsdown.ml.FailClosedReason
import xyz.headsdown.ml.ForemanModels
import xyz.headsdown.ml.MotionWindow
import xyz.headsdown.ml.PickupVerdict
import xyz.headsdown.ml.TestResources

/** The on-device pieces around the model: window collection, loading, fallbacks. */
class PickupRuntimeTest {

    private val vectors = TestResources.json("/foreman/pickup_vectors.json")

    @Test
    fun `the collector cuts trigger - 2 s through the first sample at or after trigger + 3 s`() {
        val trig = vectors["trigger"]!!.jsonObject
        val t = trig["t_ns"]!!.jsonArray.map { it.jsonPrimitive.long }
        val xyz = trig["xyz"]!!.jsonArray
        val expected = trig["fired_ns"]!!.jsonArray.map { it.jsonPrimitive.long }
        val windows = mutableListOf<MotionWindow>()
        val collector = PickupWindowCollector { windows += it }
        val samples = t.indices.map { i ->
            val r = xyz[i].jsonArray
            AccelSample(t[i], r[0].jsonPrimitive.double.toFloat(), r[1].jsonPrimitive.double.toFloat(), r[2].jsonPrimitive.double.toFloat())
        }
        samples.forEach(collector::onSample)
        val distinct = samples.distinctBy { it.tNanos } // the collector drops the duplicate timestamp
        assertEquals("every trigger that has 3 s of stream after it closes a window",
            expected.filter { it + 3_000_000_000 <= t.last() }, windows.map { it.triggerNanos })
        for (w in windows) {
            val first = distinct.first { it.tNanos >= w.triggerNanos - 2_000_000_000 }
            val last = distinct.first { it.tNanos >= w.triggerNanos + 3_000_000_000 }
            assertEquals(first, w.samples.first())
            assertEquals(last, w.samples.last())
            assertEquals(distinct.count { it.tNanos in first.tNanos..last.tNanos }, w.samples.size)
        }
        assertEquals("the last trigger is still waiting for its 3 s", expected.size - windows.size, collector.openWindows)
    }

    @Test
    fun `reset drops history, so a trigger right after it is fail-closed`() {
        val windows = mutableListOf<MotionWindow>()
        val collector = PickupWindowCollector { windows += it }
        var t = 1_000_000_000L
        repeat(150) { collector.onSample(AccelSample(t, 0f, 0f, -9.81f)); t += 20_000_000 }
        collector.reset()
        // A fresh trigger needs a first sample; then a jolt fires immediately (no 2 s of history).
        collector.onSample(AccelSample(t, 0f, 0f, -9.81f)); t += 20_000_000
        collector.onSample(AccelSample(t, 0f, 0f, -14f)); t += 20_000_000
        repeat(200) { collector.onSample(AccelSample(t, 0f, 0f, -9.81f)); t += 20_000_000 }
        assertEquals(1, windows.size)
        val doc = PickupModelDocument.parse(TestResources.asset("pickup_model.json").readText())
        val d = ModelPickupClassifier(doc).classify(windows.single())
        assertEquals(PickupVerdict.PICKUP, d.verdict)
        assertEquals(FailClosedReason.GAP, d.failClosed)
    }

    @Test
    fun `the shipped model loads and agrees with its vectors`() {
        val shipped = TestResources.asset("pickup_model.json").readText()
        val classifier = ForemanModels.pickupClassifier(shipped)
        val doc = PickupModelDocument.parse(shipped)
        val kind = doc.model.kind
        assertTrue(classifier.modelName.startsWith("pickup_"))
        var n = 0
        for (w in vectors["windows"]!!.jsonArray.map { it.jsonObject }) {
            val out = w["outputs"]?.jsonObject?.get(kind)?.jsonObject ?: continue
            val d = classifier.classify(TestResources.window(w["input"]!!.jsonObject))
            assertEquals(out["p"]!!.jsonPrimitive.double, d.pPickup, 1e-9)
            n++
        }
        assertTrue(n >= 20)
    }

    @Test
    fun `without a LiteRT runtime the tflite path falls back to pure Kotlin`() {
        val tflite = TestResources.asset("pickup_model.tflite")
        assertTrue("the selected model ships as a .tflite too", tflite.exists() && tflite.length() > 0)
        // JVM unit tests have no LiteRT native runtime: the backend must report itself unavailable.
        assertNull(LiteRtPickupBackend.create(tflite.readBytes()))
        val shipped = TestResources.asset("pickup_model.json").readText()
        val classifier = ForemanModels.pickupClassifier(shipped, tflite.readBytes(), preferLiteRt = true)
        val w = vectors["windows"]!!.jsonArray.map { it.jsonObject }.first { it["outputs"] != null }
        val kind = PickupModelDocument.parse(shipped).model.kind
        val want = w["outputs"]!!.jsonObject[kind]!!.jsonObject["p"]!!.jsonPrimitive.double
        assertEquals(want, classifier.classify(TestResources.window(w["input"]!!.jsonObject)).pPickup, 1e-9)
    }

    @Test
    fun `a broken backend can never turn a pickup into a bump`() {
        val doc = PickupModelDocument.parse(TestResources.asset("pickup_model.json").readText())
        val w = vectors["windows"]!!.jsonArray.map { it.jsonObject }.first { it["outputs"] != null }
        val window = TestResources.window(w["input"]!!.jsonObject)
        val reference = ModelPickupClassifier(doc).classify(window)
        val throwing = ModelPickupClassifier(doc) { _, _ -> error("runtime crashed") }.classify(window)
        assertEquals("a throwing backend falls back to the Kotlin model", reference, throwing)
        val nan = ModelPickupClassifier(doc) { _, _ -> Double.NaN }.classify(window)
        assertEquals(PickupVerdict.PICKUP, nan.verdict)
        assertEquals(FailClosedReason.NON_FINITE, nan.failClosed)
    }

    @Test
    fun `malformed or missing models give the fail-closed classifier`() {
        val any = MotionWindow(0, emptyList())
        for (bad in listOf(null, "", "{}", "not json", """{"format":"headsdown.foreman.pickup","version":2}""",
            TestResources.asset("pickup_model.json").readText().replace("\"feature_spec\":1", "\"feature_spec\":2"))) {
            val c = ForemanModels.pickupClassifier(bad)
            assertSame(bad.toString(), FailClosedPickupClassifier, c)
            val d = c.classify(any)
            assertEquals(PickupVerdict.PICKUP, d.verdict)
            assertEquals(FailClosedReason.MODEL_UNAVAILABLE, d.failClosed)
        }
        // A model whose feature list differs from the spec is rejected rather than misread.
        val renamed = TestResources.text("/foreman/pickup_logistic.json").replace("\"pre_tilt\"", "\"pre_tilt_v2\"")
        assertSame(FailClosedPickupClassifier, ForemanModels.pickupClassifier(renamed))
    }

    @Test
    fun `an empty window is fail-closed`() {
        val doc = PickupModelDocument.parse(TestResources.asset("pickup_model.json").readText())
        val d = ModelPickupClassifier(doc).classify(MotionWindow(5_000_000_000, emptyList()))
        assertEquals(PickupVerdict.PICKUP, d.verdict)
        assertNotNull(d.failClosed)
    }
}
