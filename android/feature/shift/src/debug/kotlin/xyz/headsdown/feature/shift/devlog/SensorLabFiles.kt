package xyz.headsdown.feature.shift.devlog

import java.io.File
import java.util.Locale
import java.util.Properties

/* DEBUG BUILDS ONLY (src/debug). CSV format and on-disk store for sensor lab sessions. */

/** What the user says a session's motions were. */
enum class SensorLabLabel(val wire: String, val title: String) {
    BUMP("bump", "Bump"),
    PICKUP("pickup", "Pickup"),
    SLIDE("slide", "Slide"),
    SET_DOWN("set_down", "Set down"),
    UNLABELED("unlabeled", "Unlabeled");

    companion object {
        fun fromWire(s: String?): SensorLabLabel = entries.firstOrNull { it.wire == s } ?: UNLABELED
    }
}

/** Ground-truth events recorded alongside the windows. */
enum class GroundTruth(val wire: String) {
    SCREEN_ON("screen_on"),
    SCREEN_OFF("screen_off"),

    /** `ACTION_USER_PRESENT`: the phone was unlocked. */
    UNLOCK("unlock"),
    SESSION_START("session_start"),
    SESSION_END("session_end"),
}

data class SessionMeta(
    val id: String,
    val startedWallMillis: Long,
    val device: String,
    val sdkInt: Int,
    val sensor: String,
    val rateHz: Int,
    val intendedLabel: SensorLabLabel,
)

/**
 * One CSV per session, long format:
 * `row_type,window_id,t_ns,recv_ns,ax,ay,az,event`.
 *
 * - `sample` rows: `t_ns` is `SensorEvent.timestamp`, `recv_ns` is `elapsedRealtimeNanos` at
 *   delivery (the two share a clock base on most phones; both are kept so it can be checked).
 * - `window_start` / `window_end` rows bracket each motion window (`t_ns` = trigger time).
 * - `event` rows carry ground truth (`screen_on`, `screen_off`, `unlock`) in `elapsedRealtimeNanos`.
 * - Lines starting with `#` are metadata.
 */
object SensorLogCsv {
    const val VERSION = 1
    const val COLUMNS = "row_type,window_id,t_ns,recv_ns,ax,ay,az,event"
    const val EXPORT_COLUMNS = "session_id,label,$COLUMNS"

    fun header(meta: SessionMeta): List<String> = listOf(
        "# heads-down sensorlab v$VERSION (debug build; accelerometer only)",
        "# session=${meta.id} started_wall_ms=${meta.startedWallMillis} intended_label=${meta.intendedLabel.wire}",
        "# device=${clean(meta.device)} sdk=${meta.sdkInt} sensor=${clean(meta.sensor)} rate_hz=${meta.rateHz}",
        COLUMNS,
    )

    fun window(w: MotionWindow): List<String> = buildList {
        add("window_start,${w.id},${w.triggerNanos},,,,,")
        w.samples.forEach { add(sample(w.id, it)) }
        add("window_end,${w.id},${w.samples.lastOrNull()?.tNanos ?: w.triggerNanos},,,,,")
    }

    fun sample(windowId: Int, s: AccelSample): String =
        "sample,$windowId,${s.tNanos},${s.recvNanos},${f(s.x)},${f(s.y)},${f(s.z)},"

    fun event(truth: GroundTruth, elapsedNanos: Long): String = "event,,$elapsedNanos,$elapsedNanos,,,,${truth.wire}"

    fun intendedLabel(lines: Sequence<String>): SensorLabLabel {
        val meta = lines.takeWhile { it.startsWith("#") }.firstOrNull { "intended_label=" in it } ?: return SensorLabLabel.UNLABELED
        return SensorLabLabel.fromWire(meta.substringAfter("intended_label=").substringBefore(' ').trim())
    }

    private fun f(v: Float) = String.format(Locale.ROOT, "%.5f", v)

    /** Metadata values never contain separators or newlines. */
    private fun clean(s: String) = s.replace(Regex("[,\\r\\n=#]"), " ").trim()
}

data class SessionInfo(
    val id: String,
    val file: File,
    val label: SensorLabLabel,
    val windows: Int,
    val events: Int,
    val bytes: Long,
)

/**
 * Sessions live in app-private storage (`filesDir/sensorlab`, never backed up: the app's
 * data-extraction rules exclude every domain). Labels are kept beside them in
 * `labels.properties`, so relabelling never rewrites a session file.
 */
class SensorLabStore(private val dir: File) {
    private val labelsFile = File(dir, "labels.properties")
    private val exportDir = File(dir, "export")

    fun sessionFile(id: String): File = File(dir.apply { mkdirs() }, "session-$id.csv")

    fun sessions(): List<SessionInfo> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("session-") && f.name.endsWith(".csv") }.orEmpty()
        val labels = loadLabels()
        return files.sortedByDescending { it.name }.map { file ->
            val id = file.name.removePrefix("session-").removeSuffix(".csv")
            var windows = 0
            var events = 0
            var intended = SensorLabLabel.UNLABELED
            file.useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("#") && "intended_label=" in line -> intended = SensorLogCsv.intendedLabel(sequenceOf(line))
                        line.startsWith("window_start,") -> windows++
                        line.startsWith("event,") -> events++
                    }
                }
            }
            SessionInfo(id, file, labels[id]?.let(SensorLabLabel::fromWire) ?: intended, windows, events, file.length())
        }
    }

    fun setLabel(id: String, label: SensorLabLabel) {
        val labels = loadLabels()
        labels[id] = label.wire
        dir.mkdirs()
        labelsFile.outputStream().use { out -> Properties().apply { putAll(labels) }.store(out, "sensorlab labels") }
    }

    fun delete(id: String) {
        sessionFile(id).delete()
        val labels = loadLabels()
        if (labels.remove(id) != null) {
            labelsFile.outputStream().use { out -> Properties().apply { putAll(labels) }.store(out, "sensorlab labels") }
        }
    }

    fun deleteAll() {
        dir.deleteRecursively()
    }

    /**
     * One CSV with every session, labels applied: `session_id,label,` + the session columns.
     * Previous exports are removed, so at most one copy of the data sits in the export folder.
     */
    fun export(stamp: String): File {
        exportDir.deleteRecursively()
        exportDir.mkdirs()
        val out = File(exportDir, "sensorlab-export-$stamp.csv")
        val sessions = sessions().sortedBy { it.id }
        out.bufferedWriter().use { w ->
            w.write("# heads-down sensorlab export v${SensorLogCsv.VERSION}: ${sessions.size} sessions\n")
            sessions.forEach { s -> w.write("# session=${s.id} label=${s.label.wire} windows=${s.windows} events=${s.events}\n") }
            w.write(SensorLogCsv.EXPORT_COLUMNS + "\n")
            sessions.forEach { s ->
                s.file.useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") && it != SensorLogCsv.COLUMNS }
                        .forEach { w.write("${s.id},${s.label.wire},$it\n") }
                }
            }
        }
        return out
    }

    private fun loadLabels(): MutableMap<String, String> {
        if (!labelsFile.exists()) return mutableMapOf()
        val p = Properties()
        labelsFile.inputStream().use(p::load)
        return p.stringPropertyNames().associateWith { p.getProperty(it) }.toMutableMap()
    }
}
