package xyz.headsdown.rig

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.headsdown.feature.shift.HeartbeatLog
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local copy of every signed rig message (the uplink fallback, and the evidence behind the
 * morning reveal's dark-round count). JSON lines in `noBackupFilesDir`, so it is never backed
 * up or transferred; bounded to the most recent [MAX_LINES]. Contents are public on-chain data
 * (rig address, counters, round ids, signatures), and nothing is ever written to logcat.
 */
@Singleton
class FileHeartbeatLog @Inject constructor(
    @ApplicationContext context: Context,
) : HeartbeatLog {
    private val file = File(context.noBackupFilesDir, FILE)

    @Synchronized
    override fun append(json: String) {
        require(!json.contains('\n')) { "one message per line" }
        file.appendText(json + "\n")
        if (file.length() > MAX_BYTES) {
            val keep = file.readLines().takeLast(MAX_LINES / 2)
            file.writeText(keep.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    @Synchronized
    fun recent(limit: Int = MAX_LINES): List<String> =
        if (!file.exists()) emptyList() else file.readLines().takeLast(limit)

    private companion object {
        const val FILE = "heartbeats.jsonl"
        const val MAX_LINES = 256
        const val MAX_BYTES = 96L * 1024
    }
}
