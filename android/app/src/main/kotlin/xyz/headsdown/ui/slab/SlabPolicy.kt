package xyz.headsdown.ui.slab

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** Which of the two renderers draws the slab. */
enum class SlabRenderer {
    /** Renderer A: one AGSL fragment shader (API 33 and later). */
    Shader,

    /** Renderer B: polygons projected in Kotlin, on a plain Canvas. */
    Polygon,
}

/** Whether the slab moves. */
enum class SlabMotion {
    /** The slab follows gravity, touch and scroll, and state changes animate. */
    Live,

    /** A fixed pose: no sensor, no springs, no frame loop. State changes snap. */
    Static,
}

@Immutable
data class SlabConfig(val renderer: SlabRenderer, val motion: SlabMotion) {
    companion object {
        /** What a composition gets when nobody provides anything: nothing that can run. */
        val Inert = SlabConfig(SlabRenderer.Polygon, SlabMotion.Static)
    }
}

/**
 * How the slab is drawn and whether it moves. The DEFAULT IS INERT (polygons, fixed pose): only
 * an Activity provides a live value, from [SlabPolicy.resolve], so a test or a preview that
 * composes a screen never constructs a `RuntimeShader` or starts a frame loop.
 */
val LocalSlabConfig = staticCompositionLocalOf { SlabConfig.Inert }

/**
 * Where the slab's gravity comes from. The DEFAULT IS [NoTilt]: only an Activity provides a
 * [SensorTiltSource], so a test or a preview never registers a sensor listener.
 */
val LocalTiltSource = staticCompositionLocalOf<TiltSource> { NoTilt }

/** Decides [SlabConfig] from the device and its settings. */
object SlabPolicy {
    /** `RuntimeShader` exists from Android 13. */
    const val SHADER_SDK = 33

    /**
     * THE KILL SWITCH. Set it to true and every build draws the slab with polygons: the shader is
     * never compiled. The lab decides it on the real phone (see SlabLab in src/devtools).
     */
    const val SHADER_DISABLED = false

    /**
     * - The slab is STATIC when the user removed animations, in battery saver, below Android 13
     *   (no shader, and the polygon renderer is not asked to animate on those phones), and under
     *   Robolectric.
     * - The SHADER draws it from Android 13 on a real device unless [shaderDisabled] (the kill
     *   switch, and the fallback if the shader cannot hold 60 fps); otherwise polygons do.
     *
     * Battery saver and "Remove animations" keep the shader: one still frame costs nothing.
     */
    fun resolve(
        sdkInt: Int,
        animatorsEnabled: Boolean,
        powerSave: Boolean,
        robolectric: Boolean,
        shaderDisabled: Boolean,
    ): SlabConfig {
        val shader = sdkInt >= SHADER_SDK && !robolectric && !shaderDisabled
        val live = animatorsEnabled && !powerSave && sdkInt >= SHADER_SDK && !robolectric
        return SlabConfig(
            renderer = if (shader) SlabRenderer.Shader else SlabRenderer.Polygon,
            motion = if (live) SlabMotion.Live else SlabMotion.Static,
        )
    }

    /**
     * The same decision read from this device now. Call it from an Activity (in `onResume`, since
     * battery saver and the animation setting change while the app is in the background).
     */
    fun resolve(context: Context, shaderDisabled: Boolean = SHADER_DISABLED): SlabConfig = resolve(
        sdkInt = Build.VERSION.SDK_INT,
        animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
        powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true,
        robolectric = Build.FINGERPRINT == "robolectric",
        shaderDisabled = shaderDisabled,
    )
}
