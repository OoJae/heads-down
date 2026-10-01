package xyz.headsdown.ml

import android.content.res.AssetManager
import kotlinx.serialization.SerializationException
import xyz.headsdown.ml.pickup.FailClosedPickupClassifier
import xyz.headsdown.ml.pickup.LiteRtPickupBackend
import xyz.headsdown.ml.pickup.ModelPickupClassifier
import xyz.headsdown.ml.pickup.PickupModelDocument
import xyz.headsdown.ml.planner.PlannerParams
import xyz.headsdown.ml.planner.RhythmShiftPlanner
import java.io.IOException

/**
 * Loads the Foreman models that ship inside the APK (signed with it; nothing is downloaded).
 * Every failure is safe: no pickup model → [FailClosedPickupClassifier] (every motion window
 * breaks); no LiteRT runtime → the pure-Kotlin path; no planner parameters → the built-in
 * defaults, which only ever propose a window (auto-arm stays off until the user has history).
 */
object ForemanModels {
    const val PICKUP_MODEL_ASSET = "foreman/pickup_model.json"
    const val PICKUP_TFLITE_ASSET = "foreman/pickup_model.tflite"
    const val PLANNER_PARAMS_ASSET = "foreman/planner_params.json"

    /**
     * The pickup classifier from the model JSON (and optionally its `.tflite`, run through LiteRT
     * when [preferLiteRt] and a runtime exist). Malformed input gives the fail-closed classifier.
     */
    fun pickupClassifier(modelJson: String?, tflite: ByteArray? = null, preferLiteRt: Boolean = false): PickupClassifier {
        val doc = modelJson?.let { parseOrNull(it) } ?: return FailClosedPickupClassifier
        val backend = if (preferLiteRt && tflite != null) LiteRtPickupBackend.create(tflite) else null
        return ModelPickupClassifier(doc, backend)
    }

    fun pickupClassifier(assets: AssetManager, preferLiteRt: Boolean = false): PickupClassifier =
        pickupClassifier(assets.readText(PICKUP_MODEL_ASSET), if (preferLiteRt) assets.readBytes(PICKUP_TFLITE_ASSET) else null, preferLiteRt)

    fun shiftPlanner(paramsJson: String?): ShiftPlanner =
        RhythmShiftPlanner(paramsJson?.let { runCatching { PlannerParams.parse(it) }.getOrNull() } ?: PlannerParams.DEFAULT)

    fun shiftPlanner(assets: AssetManager): ShiftPlanner = shiftPlanner(assets.readText(PLANNER_PARAMS_ASSET))

    private fun parseOrNull(json: String): PickupModelDocument? = try {
        PickupModelDocument.parse(json)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalStateException) {
        null
    }

    private fun AssetManager.readText(name: String): String? = try {
        open(name).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: IOException) {
        null
    }

    private fun AssetManager.readBytes(name: String): ByteArray? = try {
        open(name).use { it.readBytes() }
    } catch (e: IOException) {
        null
    }
}
