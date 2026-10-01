package xyz.headsdown.ml.pickup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.headsdown.ml.json.arr
import xyz.headsdown.ml.json.doubles
import xyz.headsdown.ml.json.int
import xyz.headsdown.ml.json.num
import xyz.headsdown.ml.json.obj
import xyz.headsdown.ml.json.str
import xyz.headsdown.ml.json.strOrNull
import kotlin.math.exp

/**
 * A pickup model: features (or CNN channels) → raw logit z. The three kinds exported by
 * `ml/foreman/classifier` (logistic, gbdt, cnn) are evaluated here in pure Kotlin with the loop
 * order of the Python reference `models.predict_json`, in float64.
 */
sealed interface PickupModel {
    val kind: String

    fun logit(features: DoubleArray, channels: Array<DoubleArray>): Double
}

/** z = intercept + Σ coef_j · clip((x_j − mean_j) / scale_j, ±stdClip). */
class LogisticPickupModel(
    private val mean: DoubleArray,
    private val scale: DoubleArray,
    private val coef: DoubleArray,
    private val intercept: Double,
    private val stdClip: Double,
) : PickupModel {
    override val kind = "logistic"

    init {
        require(mean.size == PickupFeatures.FEATURE_NAMES.size && scale.size == mean.size && coef.size == mean.size) {
            "logistic model expects ${PickupFeatures.FEATURE_NAMES.size} features"
        }
        require(scale.all { it > 0.0 && it.isFinite() }) { "scale must be positive" }
    }

    override fun logit(features: DoubleArray, channels: Array<DoubleArray>): Double {
        var z = intercept
        for (j in coef.indices) {
            val v = ((features[j] - mean[j]) / scale[j]).coerceIn(-stdClip, stdClip)
            z += coef[j] * v
        }
        return z
    }
}

/**
 * Gradient-boosted trees (scikit-learn HistGradientBoosting, exported node by node):
 * z = baseline + Σ tree(x). A numeric split goes left iff x <= threshold; NaN follows missingLeft.
 */
class GbdtPickupModel(private val baseline: Double, private val trees: List<Tree>) : PickupModel {
    override val kind = "gbdt"

    class Tree(
        val feature: IntArray,
        val threshold: DoubleArray,
        val left: IntArray,
        val right: IntArray,
        val missingLeft: BooleanArray,
        val isLeaf: BooleanArray,
        val value: DoubleArray,
    ) {
        init {
            val n = feature.size
            require(n > 0 && listOf(threshold.size, left.size, right.size, missingLeft.size, isLeaf.size, value.size).all { it == n })
            for (i in 0 until n) {
                if (!isLeaf[i]) {
                    require(left[i] in (i + 1) until n && right[i] in (i + 1) until n) { "tree node $i: children must follow it" }
                    require(feature[i] in PickupFeatures.FEATURE_NAMES.indices) { "tree node $i: bad feature ${feature[i]}" }
                }
            }
        }

        fun eval(x: DoubleArray): Double {
            var i = 0
            while (!isLeaf[i]) {
                val v = x[feature[i]]
                i = if (v.isNaN()) (if (missingLeft[i]) left[i] else right[i]) else if (v <= threshold[i]) left[i] else right[i]
            }
            return value[i]
        }
    }

    override fun logit(features: DoubleArray, channels: Array<DoubleArray>): Double {
        var z = baseline
        for (t in trees) z += t.eval(features)
        return z
    }
}

/**
 * The tiny 1D-CNN: 3 x (zero-pad, Conv1D, ReLU) → [global average ‖ global max] → Dense(1).
 * Kernel layout is Keras's (k, in, out), flattened C-order.
 */
class CnnPickupModel(private val layers: List<Layer>) : PickupModel {
    override val kind = "cnn"

    sealed interface Layer
    class Conv(val kernel: Int, val stride: Int, val pad: Int, val cin: Int, val cout: Int, val w: DoubleArray, val b: DoubleArray) : Layer {
        init {
            require(kernel > 0 && stride > 0 && pad >= 0 && w.size == kernel * cin * cout && b.size == cout)
        }
    }
    object AvgMaxPool : Layer
    class Dense(val cin: Int, val w: DoubleArray, val b: Double) : Layer {
        init {
            require(w.size == cin)
        }
    }

    init {
        require(layers.lastOrNull() is Dense) { "cnn must end with a dense head" }
        val first = layers.first()
        require(first is Conv && first.cin == PickupFeatures.CHANNEL_NAMES.size) { "cnn input must have 5 channels" }
    }

    override fun logit(features: DoubleArray, channels: Array<DoubleArray>): Double {
        var h = channels
        for (layer in layers) {
            when (layer) {
                is Conv -> h = conv(h, layer)
                AvgMaxPool -> {
                    val n = h.size
                    val ch = h[0].size
                    val out = DoubleArray(2 * ch)
                    for (c in 0 until ch) {
                        var sm = 0.0
                        var best = h[0][c]
                        for (t in 0 until n) {
                            sm += h[t][c]
                            if (h[t][c] > best) best = h[t][c]
                        }
                        out[c] = sm / n
                        out[ch + c] = best
                    }
                    h = arrayOf(out)
                }
                is Dense -> {
                    val v = h[0]
                    var acc = 0.0
                    for (i in 0 until layer.cin) acc += v[i] * layer.w[i]
                    return acc + layer.b
                }
            }
        }
        error("unreachable: validated in init")
    }

    private fun conv(x: Array<DoubleArray>, l: Conv): Array<DoubleArray> {
        val lin = x.size
        val lout = (lin + 2 * l.pad - l.kernel) / l.stride + 1
        return Array(lout) { t ->
            DoubleArray(l.cout) { o ->
                var acc = 0.0
                for (j in 0 until l.kernel) {
                    val src = t * l.stride + j - l.pad
                    if (src < 0 || src >= lin) continue
                    val xs = x[src]
                    val base = (j * l.cin) * l.cout + o
                    for (c in 0 until l.cin) acc += xs[c] * l.w[base + c * l.cout]
                }
                val v = acc + l.b[o]
                if (v > 0.0) v else 0.0
            }
        }
    }
}

/**
 * A pickup model file (`format` = headsdown.foreman.pickup, version 1): the model, its Platt
 * calibration and its decision threshold. [decide] turns a raw logit into (calibrated logit, p).
 */
class PickupModelDocument(
    val name: String,
    val model: PickupModel,
    val calibrationA: Double,
    val calibrationB: Double,
    val logitClip: Double,
    val thresholdLogit: Double,
    val trainedOn: String?,
) {
    fun calibratedLogit(z: Double): Double = calibrationA * z.coerceIn(-logitClip, logitClip) + calibrationB

    fun probability(calibratedLogit: Double): Double = 1.0 / (1.0 + exp(-calibratedLogit))

    companion object {
        const val FORMAT = "headsdown.foreman.pickup"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): PickupModelDocument = parse(json.parseToJsonElement(text).jsonObject)

        fun parse(doc: JsonObject): PickupModelDocument {
            require(doc.str("format") == FORMAT) { "not a pickup model file" }
            require(doc.int("version") == 1) { "unsupported pickup model version" }
            require(doc.int("feature_spec") == PickupFeatures.SPEC_VERSION) { "feature spec mismatch" }
            val m = doc.obj("model")
            val cal = doc.obj("calibration")
            val threshold = doc.num("threshold_logit")
            require(threshold.isFinite()) { "threshold must be finite" }
            return PickupModelDocument(
                name = doc.strOrNull("name") ?: m.str("type"),
                model = parseModel(m),
                calibrationA = cal.num("a"),
                calibrationB = cal.num("b"),
                logitClip = cal.num("logit_clip"),
                thresholdLogit = threshold,
                trainedOn = doc.strOrNull("trained_on"),
            )
        }

        private fun parseModel(m: JsonObject): PickupModel = when (m.str("type")) {
            "logistic" -> {
                checkFeatureNames(m)
                LogisticPickupModel(
                    mean = m.arr("mean").doubles(),
                    scale = m.arr("scale").doubles(),
                    coef = m.arr("coef").doubles(),
                    intercept = m.num("intercept"),
                    stdClip = m.num("std_clip"),
                )
            }
            "gbdt" -> {
                checkFeatureNames(m)
                GbdtPickupModel(m.num("baseline"), m.arr("trees").map { parseTree(it.jsonArray) })
            }
            "cnn" -> CnnPickupModel(m.arr("layers").map { parseLayer(it.jsonObject) })
            else -> throw IllegalArgumentException("unknown model type ${m.str("type")}")
        }

        private fun checkFeatureNames(m: JsonObject) {
            val names = m.arr("features").map { it.jsonPrimitive.content }
            require(names == PickupFeatures.FEATURE_NAMES) { "feature list differs from spec v${PickupFeatures.SPEC_VERSION}" }
        }

        private fun parseTree(nodes: JsonArray): GbdtPickupModel.Tree {
            val n = nodes.size
            val rows = nodes.map { it.jsonArray.doubles() }
            require(rows.all { it.size == 7 }) { "tree nodes have 7 fields" }
            return GbdtPickupModel.Tree(
                feature = IntArray(n) { rows[it][0].toInt() },
                threshold = DoubleArray(n) { rows[it][1] },
                left = IntArray(n) { rows[it][2].toInt() },
                right = IntArray(n) { rows[it][3].toInt() },
                missingLeft = BooleanArray(n) { rows[it][4] != 0.0 },
                isLeaf = BooleanArray(n) { rows[it][5] != 0.0 },
                value = DoubleArray(n) { rows[it][6] },
            )
        }

        private fun parseLayer(l: JsonObject): CnnPickupModel.Layer = when (l.str("kind")) {
            "conv1d_relu" -> CnnPickupModel.Conv(
                kernel = l.int("kernel"),
                stride = l.int("stride"),
                pad = l.int("pad"),
                cin = l.int("in"),
                cout = l.int("out"),
                w = l.arr("w").doubles(),
                b = l.arr("b").doubles(),
            )
            "avg_max_pool" -> CnnPickupModel.AvgMaxPool
            "dense" -> CnnPickupModel.Dense(l.int("in"), l.arr("w").doubles(), l.num("b"))
            else -> throw IllegalArgumentException("unknown layer ${l.str("kind")}")
        }
    }
}
