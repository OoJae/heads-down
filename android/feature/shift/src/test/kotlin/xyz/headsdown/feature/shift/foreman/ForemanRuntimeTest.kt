package xyz.headsdown.feature.shift.foreman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.ml.FailClosedReason
import xyz.headsdown.ml.ForemanModels
import xyz.headsdown.ml.MotionWindow
import xyz.headsdown.ml.PlanRequest
import xyz.headsdown.ml.PickupVerdict
import xyz.headsdown.ml.pickup.FailClosedPickupClassifier

/** Loading the models can only fail safe. */
class ForemanRuntimeTest {

    private val planner = ForemanModels.shiftPlanner(ForemanVectors.text("/foreman/planner_params.json"))

    @Test
    fun `the shipped assets load as the model the vectors pin`() {
        var loads = 0
        val runtime = ForemanRuntime(
            { loads++; ForemanModels.pickupClassifier(ForemanVectors.shippedModelJson) },
            { planner },
        )
        assertEquals("nothing is read until it is needed", 0, loads)
        assertTrue(runtime.pickupModelLoaded)
        assertTrue(runtime.pickupModelName.startsWith("pickup_"))
        runtime.pickupClassifier
        assertEquals("and it is read once", 1, loads)
        assertSame(planner, runtime.shiftPlanner)
    }

    @Test
    fun `a pickup model that cannot be loaded gives the fail-closed classifier, whatever went wrong`() {
        val failures = listOf<() -> xyz.headsdown.ml.PickupClassifier>(
            { ForemanModels.pickupClassifier(null) }, // the asset is missing
            { ForemanModels.pickupClassifier("{ not json") }, // or damaged
            { ForemanModels.pickupClassifier(ForemanVectors.shippedModelJson.replace("\"feature_spec\":1", "\"feature_spec\":2")) }, // or for another spec
            { error("asset manager threw") },
            { throw SecurityException("no access") },
            { throw IndexOutOfBoundsException() },
        )
        for (load in failures) {
            val runtime = ForemanRuntime(load) { planner }
            assertSame(FailClosedPickupClassifier, runtime.pickupClassifier)
            assertFalse(runtime.pickupModelLoaded)
            assertEquals("fail-closed", runtime.pickupModelName)
            val decision = runtime.pickupClassifier.classify(MotionWindow(0, emptyList()))
            assertEquals(PickupVerdict.PICKUP, decision.verdict)
            assertEquals(FailClosedReason.MODEL_UNAVAILABLE, decision.failClosed)
        }
    }

    @Test
    fun `planner parameters that cannot be loaded give the defaults, which never call a window confident`() {
        val runtime = ForemanRuntime({ FailClosedPickupClassifier }, { error("asset unreadable") })
        val plan = runtime.shiftPlanner.plan(emptyList(), PlanRequest(nowTs = 1_790_000_000_000, tzMinutes = 60))
        assertEquals(96, plan.slots.size)
        assertFalse(plan.nextWindow?.autoArm ?: false)
        assertTrue(plan.week.none { it.window?.autoArm == true })
    }
}
