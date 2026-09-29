package xyz.headsdown.surface.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionPolicyTest {
    private fun step(sdk: Int, granted: Boolean = false, rationale: Boolean = false, asked: Boolean = false) =
        NotificationPermissionPolicy.nextStep(sdk, granted, rationale, asked)

    @Test
    fun `granted short-circuits on every version`() {
        for (sdk in listOf(31, 32, 33, 34, 36)) assertEquals(NotificationPermissionStep.GRANTED, step(sdk, granted = true))
    }

    @Test
    fun `android 12 has no runtime dialog - settings only`() {
        assertEquals(NotificationPermissionStep.OPEN_SETTINGS, step(31))
        assertEquals(NotificationPermissionStep.OPEN_SETTINGS, step(32))
    }

    @Test
    fun `first ask, rationale after one denial, settings after permanent denial`() {
        assertEquals(NotificationPermissionStep.REQUEST, step(34))
        assertEquals(NotificationPermissionStep.EXPLAIN_THEN_REQUEST, step(34, rationale = true, asked = true))
        assertEquals(NotificationPermissionStep.OPEN_SETTINGS, step(34, rationale = false, asked = true))
    }
}
