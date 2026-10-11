package xyz.headsdown.clockout

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationUtils
import android.view.animation.TranslateAnimation
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import xyz.headsdown.R
import xyz.headsdown.config.ExplorerLinks
import xyz.headsdown.config.openExplorerLink

/**
 * What the two sheets do as activities of their own. Each comes up from the bottom edge when it
 * opens and goes back down when it closes, over the home screen that stays where it is: 300 ms,
 * one curve (the system runs no window animation at all under "Remove animations"). And the one
 * thing either sends outside the app: the link to a confirmed transaction.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class SheetActivityTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun aHostActivity() {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }

    private fun sheet(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()

    private fun <T> onAndroid(sdk: Int, block: () -> T): T {
        val real = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        try {
            return block()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", real)
        }
    }

    @Test
    fun `the sheet rises and falls in 300 ms on one curve, and the screen under it holds for as long`() {
        val rise = AnimationUtils.loadAnimation(app, R.anim.sheet_rise)
        val fall = AnimationUtils.loadAnimation(app, R.anim.sheet_fall)
        val stay = AnimationUtils.loadAnimation(app, R.anim.sheet_stay)
        assertTrue(rise is TranslateAnimation && fall is TranslateAnimation)
        assertTrue("the screen underneath only waits", stay is AlphaAnimation)
        for (animation in listOf(rise, fall, stay)) assertEquals(300L, animation.duration)

        // Out fast, then a long arrival: most of the way in a third of the time.
        val curve = AnimationUtils.loadInterpolator(app, R.anim.sheet_curve)
        assertEquals(0f, curve.getInterpolation(0f), 0.001f)
        assertEquals(1f, curve.getInterpolation(1f), 0.001f)
        assertTrue("a third of the way through it is at ${curve.getInterpolation(0.33f)}", curve.getInterpolation(0.33f) > 0.85f)
        var last = 0f
        for (step in 1..20) {
            val at = curve.getInterpolation(step / 20f)
            assertTrue("it never goes back, and never past the end: $at after $last", at >= last && at <= 1.001f)
            last = at
        }
        // Both directions run on it.
        for (moving in listOf(rise, fall)) {
            assertEquals(curve.getInterpolation(0.33f), moving.interpolator.getInterpolation(0.33f), 0.001f)
        }
    }

    @Test
    fun `from Android 14 the sheet names both of its window animations when it is created`() {
        assertTrue("the tests run on Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val activity = sheet()
        activity.riseAsSheet()
        val shadow = shadowOf(activity)
        val open = checkNotNull(shadow.getOverriddenActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN))
        assertEquals(R.anim.sheet_rise to R.anim.sheet_stay, open.enterAnim to open.exitAnim)
        // Closing: the screen underneath is the one that enters, and it holds; the sheet leaves, downwards.
        val close = checkNotNull(shadow.getOverriddenActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE))
        assertEquals(R.anim.sheet_stay to R.anim.sheet_fall, close.enterAnim to close.exitAnim)
        // Nothing is left to say when it finishes, and the older call is not used.
        activity.fallAsSheet()
        assertEquals(-1, shadow.pendingTransitionEnterAnimationResourceId)
    }

    @Test
    fun `before Android 14 it names the opening when it is created and the closing when it finishes`() {
        val activity = sheet()
        val shadow = shadowOf(activity)
        onAndroid(Build.VERSION_CODES.TIRAMISU) {
            activity.riseAsSheet()
            assertEquals(R.anim.sheet_rise to R.anim.sheet_stay, shadow.pendingTransitionEnterAnimationResourceId to shadow.pendingTransitionExitAnimationResourceId)
            assertNull(shadow.getOverriddenActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN))
            activity.fallAsSheet()
            assertEquals(R.anim.sheet_stay to R.anim.sheet_fall, shadow.pendingTransitionEnterAnimationResourceId to shadow.pendingTransitionExitAnimationResourceId)
        }
    }

    @Test
    fun `the link to a transaction is handed to the phone's browser as a plain view of one address`() {
        val activity = sheet()
        val url = checkNotNull(ExplorerLinks.tx(SheetTestBase.SIGNATURE, ExplorerLinks.MAINNET))
        assertTrue(activity.openExplorerLink(url))
        val sent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, sent.action)
        assertEquals(url, sent.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), sent.categories)
        // Nothing rides along: no extras, no component chosen for the user, no permission granted.
        assertNull(sent.extras)
        assertNull(sent.component)
        assertEquals(0, sent.flags)
    }

    @Test
    fun `on a phone with nothing that opens a web page the link does nothing, and says so to its caller`() {
        // As on a device: starting an activity nothing answers to throws.
        shadowOf(app).checkActivities(true)
        val activity = sheet()
        assertFalse(activity.openExplorerLink("https://solscan.io/tx/${SheetTestBase.SIGNATURE}"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
