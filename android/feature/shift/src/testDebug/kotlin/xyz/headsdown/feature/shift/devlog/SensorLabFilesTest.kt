package xyz.headsdown.feature.shift.devlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SensorLabFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun meta(id: String, label: SensorLabLabel = SensorLabLabel.PICKUP) = SessionMeta(
        id = id,
        startedWallMillis = 1_790_000_000_000L,
        device = "Xiaomi, Redmi 14C\n#evil=1",
        sdkInt = 34,
        sensor = "accel / vendor",
        rateHz = 50,
        intendedLabel = label,
    )

    private val window = MotionWindow(
        id = 3,
        triggerNanos = 2_000,
        samples = listOf(AccelSample(1_000, 1_100, 0.1f, -0.25f, -9.80665f), AccelSample(2_000, 2_100, 1f, 2f, -12f)),
    )

    @Test
    fun `csv rows have the documented columns`() {
        val cols = SensorLogCsv.COLUMNS.split(",").size
        val rows = SensorLogCsv.window(window) + SensorLogCsv.event(GroundTruth.UNLOCK, 5_000)
        rows.forEach { assertEquals(it, cols, it.split(",").size) }
        assertEquals("window_start,3,2000,,,,,", rows.first())
        assertEquals("sample,3,1000,1100,0.10000,-0.25000,-9.80665,", rows[1])
        assertEquals("window_end,3,2000,,,,,", rows[3])
        assertEquals("event,,5000,5000,,,,unlock", rows.last())
    }

    @Test
    fun `header metadata cannot break the csv`() {
        val header = SensorLogCsv.header(meta("20260929-231500"))
        assertEquals(4, header.size)
        assertTrue(header.take(3).all { it.startsWith("#") })
        assertFalse(header[2].contains("\n"))
        assertTrue(header[2].startsWith("# device=Xiaomi  Redmi 14C  evil 1 sdk=34"))
        assertEquals(SensorLogCsv.COLUMNS, header.last())
        assertEquals(SensorLabLabel.PICKUP, SensorLogCsv.intendedLabel(header.asSequence()))
    }

    @Test
    fun `store lists sessions, relabels without rewriting, and exports one labelled csv`() {
        val store = SensorLabStore(tmp.newFolder("sensorlab"))
        fun record(id: String, label: SensorLabLabel, windows: Int) {
            store.sessionFile(id).writeText(
                (
                    SensorLogCsv.header(meta(id, label)) +
                        SensorLogCsv.event(GroundTruth.SESSION_START, 1) +
                        (1..windows).flatMap { SensorLogCsv.window(window.copy(id = it)) } +
                        SensorLogCsv.event(GroundTruth.SCREEN_ON, 9) +
                        SensorLogCsv.event(GroundTruth.SESSION_END, 10)
                    ).joinToString("\n", postfix = "\n"),
            )
        }
        record("20260929-230000", SensorLabLabel.BUMP, windows = 2)
        record("20260929-231500", SensorLabLabel.UNLABELED, windows = 1)

        val listed = store.sessions()
        assertEquals(listOf("20260929-231500", "20260929-230000"), listed.map { it.id })
        assertEquals(listOf(SensorLabLabel.UNLABELED, SensorLabLabel.BUMP), listed.map { it.label })
        assertEquals(listOf(1, 2), listed.map { it.windows })
        assertEquals(listOf(3, 3), listed.map { it.events })

        val before = store.sessionFile("20260929-231500").readText()
        store.setLabel("20260929-231500", SensorLabLabel.SLIDE)
        assertEquals(before, store.sessionFile("20260929-231500").readText())
        assertEquals(SensorLabLabel.SLIDE, store.sessions().first().label)

        val export = store.export("20260930-070000")
        val lines = export.readLines()
        assertTrue(lines.first().startsWith("# heads-down sensorlab export v1: 2 sessions"))
        assertTrue(lines.contains(SensorLogCsv.EXPORT_COLUMNS))
        val data = lines.filter { !it.startsWith("#") && it != SensorLogCsv.EXPORT_COLUMNS }
        assertTrue(data.all { it.split(",").size == SensorLogCsv.EXPORT_COLUMNS.split(",").size })
        assertEquals(2 * 4 + 3, data.count { it.startsWith("20260929-230000,bump,") })
        assertEquals(1 * 4 + 3, data.count { it.startsWith("20260929-231500,slide,") })

        // A second export replaces the first.
        val again = store.export("20260930-070100")
        assertFalse(export.exists())
        assertTrue(again.exists())

        store.delete("20260929-230000")
        assertEquals(listOf("20260929-231500"), store.sessions().map { it.id })
        store.deleteAll()
        assertTrue(store.sessions().isEmpty())
    }

    @Test
    fun `labels round-trip through their wire names`() {
        SensorLabLabel.entries.forEach { assertEquals(it, SensorLabLabel.fromWire(it.wire)) }
        assertEquals(SensorLabLabel.UNLABELED, SensorLabLabel.fromWire("garbage"))
        assertEquals(setOf("bump", "pickup", "slide", "set_down", "unlabeled"), SensorLabLabel.entries.map { it.wire }.toSet())
    }

    @Test
    fun `the debug variant exposes the lab`() {
        assertTrue(SensorLab.AVAILABLE)
    }
}
