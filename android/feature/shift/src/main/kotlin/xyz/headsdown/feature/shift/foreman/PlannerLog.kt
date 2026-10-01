package xyz.headsdown.feature.shift.foreman

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.headsdown.ml.PlannerEvent
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Shift Planner's log on disk (ml/foreman/planner/LOG_SCHEMA.md): JSON Lines, one event per
 * line, about this phone only (screen, unlock, charger, face-down, the next alarm, shifts).
 *
 * **It never leaves the device.** The files live in the app's private `noBackupFilesDir/foreman`:
 * Android never backs that directory up or transfers it to a new device (on top of the app's own
 * rules, which exclude everything), and no FileProvider path of the app can reach it. This class
 * has no network code and hands the events to nothing but the on-device planner. Clearing the
 * app's data deletes them, and so does [clear].
 *
 * **It cannot grow without bound.** Lines go to `planner.jsonl`; when that segment would pass
 * [segmentBytes] it becomes `planner.1.jsonl`, the older ones shift up, and the oldest of
 * [segments] is deleted. The default (4 x 768 KiB) always keeps at least 2.25 MiB: about the 26
 * weeks the planner's 56-day half-life can still use, at the schema's busiest estimate of
 * 12 KB a day. A shift-only observer writes far less.
 *
 * A session marker (`planner.session`, the last time the observer was known alive) lets the next
 * start close a session the OS killed, as the schema asks: `monitor_stop` stamped with that time.
 *
 * Disk trouble is swallowed: a log line is never worth a broken shift. A torn last line (the
 * process died mid-write) is closed with a newline before the next append, and readers skip it.
 */
@Singleton
class PlannerLog internal constructor(
    private val dir: File,
    private val segmentBytes: Long = SEGMENT_BYTES,
    private val segments: Int = SEGMENTS,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, DIRECTORY))

    init {
        require(segmentBytes > 0 && segments >= 2) { "at least two segments, so rotation never empties the log" }
    }

    private fun segment(index: Int): File = File(dir, if (index == 0) "$NAME.jsonl" else "$NAME.$index.jsonl")

    private val sessionFile: File get() = File(dir, "$NAME.session")

    /** Bytes on disk across all segments (at most `segments * segmentBytes`). */
    val sizeBytes: Long
        @Synchronized get() = (0 until segments).sumOf { segment(it).length() }

    /** Appends [events] as one write, rotating first if the current segment is full. */
    @Synchronized
    internal fun append(events: List<PlannerEvent>) {
        if (events.isEmpty()) return
        val bytes = events.joinToString(separator = "") { it.toJsonLine() + "\n" }.toByteArray(Charsets.UTF_8)
        try {
            dir.mkdirs()
            val current = segment(0)
            if (current.length() > 0 && current.length() + bytes.size > segmentBytes) rotate()
            val torn = current.length() > 0 && lastByte(current) != NEWLINE
            FileOutputStream(current, true).use { out ->
                if (torn) out.write(NEWLINE.toInt())
                out.write(bytes)
            }
        } catch (_: IOException) {
            // Dropped: the planner works from whatever was written.
        } catch (_: SecurityException) {
        }
    }

    internal fun append(event: PlannerEvent) = append(listOf(event))

    /** Every stored event, oldest first. Lines that do not parse are skipped. */
    @Synchronized
    internal fun events(): List<PlannerEvent> {
        val out = ArrayList<PlannerEvent>()
        for (index in segments - 1 downTo 0) {
            val file = segment(index)
            try {
                if (file.isFile) file.useLines { lines -> lines.mapNotNullTo(out, PlannerEvent::parse) }
            } catch (_: IOException) {
                // An unreadable segment is skipped; the others still count.
            } catch (_: SecurityException) {
            }
        }
        return out
    }

    /** A session is open from here on: the observer was alive at [ts] (epoch ms). */
    @Synchronized
    internal fun markAlive(ts: Long) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$NAME.session.tmp")
            tmp.writeText("$ts\n")
            if (!tmp.renameTo(sessionFile)) {
                sessionFile.writeText("$ts\n")
                tmp.delete()
            }
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    /** When the observer was last known alive, if its session was never closed (the OS killed it). */
    @Synchronized
    internal fun openSessionAliveAt(): Long? = try {
        sessionFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    @Synchronized
    internal fun closeSession() {
        try {
            sessionFile.delete()
        } catch (_: SecurityException) {
        }
    }

    /** Deletes the whole log. */
    @Synchronized
    fun clear() {
        try {
            for (index in 0 until segments) segment(index).delete()
            sessionFile.delete()
            File(dir, "$NAME.session.tmp").delete()
        } catch (_: SecurityException) {
        }
    }

    private fun rotate() {
        segment(segments - 1).delete()
        for (index in segments - 2 downTo 0) {
            val from = segment(index)
            if (from.exists() && !from.renameTo(segment(index + 1))) throw IOException("cannot rotate ${from.name}")
        }
    }

    private fun lastByte(file: File): Byte = RandomAccessFile(file, "r").use { raf ->
        raf.seek(raf.length() - 1)
        raf.readByte()
    }

    companion object {
        /** Under `noBackupFilesDir`. No FileProvider path may ever include it. */
        const val DIRECTORY = "foreman"
        const val SEGMENT_BYTES = 768L * 1024
        const val SEGMENTS = 4
        private const val NAME = "planner"
        private const val NEWLINE: Byte = 10 // '\n'
    }
}
