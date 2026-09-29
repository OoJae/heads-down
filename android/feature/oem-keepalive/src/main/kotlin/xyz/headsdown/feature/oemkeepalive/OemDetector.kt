package xyz.headsdown.feature.oemkeepalive

enum class OemFamily { XIAOMI, SAMSUNG, HUAWEI_HONOR, OPPO_REALME_ONEPLUS, VIVO, OTHER }

enum class XiaomiSkin { HYPEROS, MIUI, UNKNOWN }

data class OemProfile(
    val family: OemFamily,
    /** Only meaningful for [OemFamily.XIAOMI]. */
    val xiaomiSkin: XiaomiSkin? = null,
    /** e.g. "OS1.0" (HyperOS) or "V140" (MIUI), when readable. */
    val skinVersion: String? = null,
) {
    /** OEMs known (dontkillmyapp.com) to kill foreground services without extra user settings. */
    val needsAutostartStep: Boolean get() = family == OemFamily.XIAOMI
}

/**
 * Identifies the OEM and, for Xiaomi / Redmi / POCO, whether it runs HyperOS or MIUI.
 * Pure: the caller supplies `Build` fields and a system-property reader.
 *
 * HyperOS exposes `ro.mi.os.version.name` (e.g. `OS1.0`) and an `OS…` build incremental;
 * MIUI exposes `ro.miui.ui.version.name` (e.g. `V140`) and a `V…` incremental.
 */
object OemDetector {
    private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")

    fun detect(
        manufacturer: String,
        brand: String,
        incremental: String,
        systemProperty: (String) -> String?,
    ): OemProfile {
        val m = manufacturer.trim().lowercase()
        val b = brand.trim().lowercase()
        return when {
            m in XIAOMI_BRANDS || b in XIAOMI_BRANDS -> xiaomi(incremental, systemProperty)
            m == "samsung" -> OemProfile(OemFamily.SAMSUNG)
            m == "huawei" || m == "honor" -> OemProfile(OemFamily.HUAWEI_HONOR)
            m == "oppo" || m == "realme" || m == "oneplus" -> OemProfile(OemFamily.OPPO_REALME_ONEPLUS)
            m == "vivo" || m == "iqoo" -> OemProfile(OemFamily.VIVO)
            else -> OemProfile(OemFamily.OTHER)
        }
    }

    private fun xiaomi(incremental: String, prop: (String) -> String?): OemProfile {
        val hyper = prop("ro.mi.os.version.name")?.takeIf { it.isNotBlank() }
        if (hyper != null) return OemProfile(OemFamily.XIAOMI, XiaomiSkin.HYPEROS, hyper)
        val miui = prop("ro.miui.ui.version.name")?.takeIf { it.isNotBlank() }
        if (miui != null) return OemProfile(OemFamily.XIAOMI, XiaomiSkin.MIUI, miui)
        return when {
            incremental.startsWith("OS") -> OemProfile(OemFamily.XIAOMI, XiaomiSkin.HYPEROS)
            incremental.startsWith("V") -> OemProfile(OemFamily.XIAOMI, XiaomiSkin.MIUI)
            else -> OemProfile(OemFamily.XIAOMI, XiaomiSkin.UNKNOWN)
        }
    }
}
