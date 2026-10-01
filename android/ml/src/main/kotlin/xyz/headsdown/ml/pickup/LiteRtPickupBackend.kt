package xyz.headsdown.ml.pickup

import org.tensorflow.lite.InterpreterApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A `.tflite` pickup model run through LiteRT, behind [ModelPickupClassifier.LogitBackend].
 * ml/foreman exports float32 flatbuffers that return the model's RAW logit (calibration and
 * threshold stay in the JSON document):
 * - `pickup_model.tflite` when the selected model is logistic: input [1, 39] features;
 * - `pickup_cnn.tflite`: input [1, 250, 5] channels.
 * The input tensor's shape decides which one is fed.
 *
 * `:ml` compiles against LiteRT's Java API only. The runtime comes from the app
 * (`com.google.ai.edge.litert:litert`) or Google Play services; [create] returns null when there
 * is none, and [ModelPickupClassifier] then evaluates the same model in pure Kotlin. The float32
 * flatbuffers agree with the float64 Kotlin path to ~1e-5 in the logit (RESULTS.md, "Export
 * checks"); the decision threshold sits in a validation gap far wider than that.
 */
class LiteRtPickupBackend private constructor(
    private val interpreter: InterpreterApi,
    private val input: Input,
) : ModelPickupClassifier.LogitBackend, AutoCloseable {

    enum class Input(val floats: Int) {
        FEATURES(PickupFeatures.FEATURE_NAMES.size),
        CHANNELS(PickupFeatures.N * PickupFeatures.CHANNEL_NAMES.size),
    }

    private val inBuf = ByteBuffer.allocateDirect(4 * input.floats).order(ByteOrder.nativeOrder())
    private val outBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    @Synchronized
    override fun logit(features: DoubleArray, channels: Array<DoubleArray>): Double {
        inBuf.rewind()
        when (input) {
            Input.FEATURES -> {
                require(features.size == input.floats)
                for (v in features) inBuf.putFloat(v.toFloat())
            }
            Input.CHANNELS -> {
                require(channels.size == PickupFeatures.N && channels.all { it.size == PickupFeatures.CHANNEL_NAMES.size })
                for (row in channels) for (v in row) inBuf.putFloat(v.toFloat())
            }
        }
        inBuf.rewind()
        outBuf.rewind()
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        return outBuf.float.toDouble()
    }

    @Synchronized
    override fun close() = interpreter.close()

    companion object {
        /** Which input a flatbuffer's first tensor shape asks for, or null if it is neither. */
        fun inputFor(shape: IntArray): Input? = when {
            shape.contentEquals(intArrayOf(1, PickupFeatures.FEATURE_NAMES.size)) -> Input.FEATURES
            shape.contentEquals(intArrayOf(1, PickupFeatures.N, PickupFeatures.CHANNEL_NAMES.size)) -> Input.CHANNELS
            else -> null
        }

        /** A backend for [model] (flatbuffer bytes), or null without a LiteRT runtime or on a bad model. */
        fun create(model: ByteArray): LiteRtPickupBackend? {
            val buffer = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).put(model)
            buffer.rewind()
            var interpreter: InterpreterApi? = null
            return try {
                val options = InterpreterApi.Options()
                    .setRuntime(InterpreterApi.Options.TfLiteRuntime.PREFER_SYSTEM_OVER_APPLICATION)
                interpreter = InterpreterApi.create(buffer, options)
                val kind = inputFor(interpreter.getInputTensor(0).shape())
                if (kind == null || interpreter.outputTensorCount != 1) {
                    interpreter.close()
                    null
                } else {
                    LiteRtPickupBackend(interpreter, kind)
                }
            } catch (e: IllegalStateException) {
                interpreter?.close()
                null // no runtime linked into the app and none from Google Play services
            } catch (e: IllegalArgumentException) {
                interpreter?.close()
                null // not a valid flatbuffer for this runtime
            } catch (e: LinkageError) {
                null // native library missing or incompatible
            }
        }
    }
}
