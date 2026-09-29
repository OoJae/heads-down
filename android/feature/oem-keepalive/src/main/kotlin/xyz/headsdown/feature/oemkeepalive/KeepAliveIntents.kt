package xyz.headsdown.feature.oemkeepalive

import android.provider.Settings

enum class KeepAliveStep {
    /** Xiaomi "Autostart": without it HyperOS/MIUI may refuse to keep or restart our service. */
    AUTOSTART,

    /** Xiaomi per-app battery saver set to "No restrictions". */
    BATTERY_NO_RESTRICTIONS,

    /** Stock Android Doze exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). */
    IGNORE_BATTERY_OPTIMIZATIONS,
}

/** A framework-free description of an Intent, so candidate lists are unit-testable. */
data class IntentSpec(
    val action: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val dataUri: String? = null,
    val extras: Map<String, String> = emptyMap(),
) {
    val isAppDetailsFallback: Boolean get() = action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS
}

/**
 * Deep links per step, most specific first, always ending in a fallback that resolves on
 * every Android build (the app's own App info page). Components follow dontkillmyapp.com's
 * Xiaomi guidance; OEMs move these screens between releases, so each candidate is resolved
 * before launch and the next one is tried when it does not exist or is not exported.
 */
object KeepAliveIntents {
    const val MIUI_SECURITY_CENTER = "com.miui.securitycenter"
    const val MIUI_POWER_KEEPER = "com.miui.powerkeeper"

    fun candidates(step: KeepAliveStep, profile: OemProfile, appPackage: String, appLabel: String): List<IntentSpec> {
        val appDetails = IntentSpec(
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            dataUri = "package:$appPackage",
        )
        val xiaomi = profile.family == OemFamily.XIAOMI
        val specific: List<IntentSpec> = when (step) {
            KeepAliveStep.AUTOSTART -> if (!xiaomi) emptyList() else listOf(
                IntentSpec(
                    packageName = MIUI_SECURITY_CENTER,
                    className = "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
                IntentSpec(action = "miui.intent.action.OP_AUTO_START"),
            )
            KeepAliveStep.BATTERY_NO_RESTRICTIONS -> if (!xiaomi) emptyList() else listOf(
                IntentSpec(
                    packageName = MIUI_POWER_KEEPER,
                    className = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                    extras = mapOf("package_name" to appPackage, "package_label" to appLabel),
                ),
                // HyperOS: the app's page in Security's app manager hosts "Battery saver".
                IntentSpec(
                    packageName = MIUI_SECURITY_CENTER,
                    className = "com.miui.appmanager.ApplicationsDetailsActivity",
                    extras = mapOf("package_name" to appPackage, "package_label" to appLabel),
                ),
            )
            KeepAliveStep.IGNORE_BATTERY_OPTIMIZATIONS -> listOf(
                IntentSpec(action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, dataUri = "package:$appPackage"),
                IntentSpec(action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
        return specific + appDetails
    }
}

data class GuideStep(val title: String, val detail: String, val action: KeepAliveStep?)

/**
 * The onboarding checklist, adapted from dontkillmyapp.com (Xiaomi / generic), in plain
 * words. Heads Down is fail-safe either way: if the OS kills the shift, the rig goes cold
 * and nothing is spent. These steps only keep your shift alive until morning.
 */
object KeepAliveGuide {
    const val BATTERY_JUSTIFICATION =
        "Heads Down signs one heartbeat per ORE round (about every 78 seconds) while your phone " +
            "lies face-down. If Android puts the app to sleep, your rig goes cold and simply stops: " +
            "nothing is lost, but your shift ends early. Letting Heads Down ignore battery " +
            "optimization keeps the shift alive overnight on your charger."

    fun steps(profile: OemProfile): List<GuideStep> = buildList {
        if (profile.family == OemFamily.XIAOMI) {
            add(
                GuideStep(
                    "Turn on Autostart",
                    "Security > Permissions > Autostart > Heads Down. Without it, " +
                        (if (profile.xiaomiSkin == XiaomiSkin.MIUI) "MIUI" else "HyperOS") +
                        " stops the shift once the screen is off.",
                    KeepAliveStep.AUTOSTART,
                ),
            )
            add(
                GuideStep(
                    "Battery saver: No restrictions",
                    "Settings > Apps > Heads Down > Battery saver > No restrictions.",
                    KeepAliveStep.BATTERY_NO_RESTRICTIONS,
                ),
            )
            add(
                GuideStep(
                    "Lock Heads Down in Recents",
                    "Open Recents, long-press the Heads Down card and tap the lock, so \"Clear all\" skips it.",
                    null,
                ),
            )
        }
        add(GuideStep("Allow running in the background", BATTERY_JUSTIFICATION, KeepAliveStep.IGNORE_BATTERY_OPTIMIZATIONS))
    }
}
