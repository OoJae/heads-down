package xyz.headsdown.feature.shift.foreman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.headsdown.feature.shift.BreakReason
import xyz.headsdown.feature.shift.FakeClock
import xyz.headsdown.feature.shift.ShiftEvent
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.ShiftStateMachine
import xyz.headsdown.feature.shift.Signals
import xyz.headsdown.feature.shift.wireReason
import xyz.headsdown.ml.PlannerEvent
import xyz.headsdown.ml.PlannerEvent.Type
import xyz.headsdown.ml.planner.SlotLabels
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** The planner log as the shift service writes it: the schema's lines, bounded on disk, never lost to a kill. */
class PlannerLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val direct = Executor { it.run() }
    private val tz = 60 // WAT
    private val minute = 60_000L
    private val day0 = 20_000L // local day index, as in :ml's planner tests

    /** Epoch ms of [minuteOfDay] local time on local day [day]. */
    private fun at(day: Long, minuteOfDay: Long) = (day0 + day) * SlotLabels.DAY_MS + minuteOfDay * minute - tz * minute

    private var now = at(0, 23 * 60)

    private fun log(segmentBytes: Long = PlannerLog.SEGMENT_BYTES, segments: Int = PlannerLog.SEGMENTS, dir: File = tmp.newFolder()) =
        PlannerLog(dir, segmentBytes, segments)

    private fun recorder(log: PlannerLog, executor: Executor = direct) = RhythmRecorder(log, { now }, executor, tzMinutes = { tz })

    private fun lines(dir: File): List<String> = File(dir, "planner.jsonl").readLines()

    // ------------------------------------------------------------------ the schema's lines

    @Test
    fun `service start writes monitor_start, then the screen and charger state and the next alarm`() {
        val dir = tmp.newFolder()
        val r = recorder(log(dir = dir))
        now = 1_790_006_400_000
        r.monitorStart(screenOn = false, charging = true, nextAlarmTs = 1_790_034_300_000)
        assertEquals(
            listOf(
                """{"ts":1790006400000,"tz":60,"type":"monitor_start"}""",
                """{"ts":1790006400000,"tz":60,"type":"screen_off"}""",
                """{"ts":1790006400000,"tz":60,"type":"power_connected"}""",
                """{"ts":1790006400000,"tz":60,"type":"alarm_next","alarm_ts":1790034300000}""",
            ),
            lines(dir),
        )
    }

    @Test
    fun `every receiver event becomes its schema line, stamped when it happened`() {
        val dir = tmp.newFolder()
        val log = log(dir = dir)
        val r = recorder(log)
        now = 1_790_006_400_000
        r.monitorStart(screenOn = true, charging = false, nextAlarmTs = null)
        now += 5_000; r.screen(on = false)
        now += 1_000; r.power(connected = true)
        now += 2_000; r.faceDown(down = true)
        now += 28_000_000; r.faceDown(down = false)
        now += 500; r.screen(on = true)
        now += 700; r.userPresent()
        now += 60_000; r.power(connected = false)
        now += 1_000; r.alarmNext(1_790_120_700_000)
        now += 1_000; r.monitorStop()

        val events = log.events()
        assertEquals(
            listOf(
                Type.MONITOR_START, Type.SCREEN_ON, Type.POWER_DISCONNECTED, Type.ALARM_NEXT, Type.SCREEN_OFF,
                Type.POWER_CONNECTED, Type.FACE_DOWN_START, Type.FACE_DOWN_END, Type.SCREEN_ON, Type.USER_PRESENT,
                Type.POWER_DISCONNECTED, Type.ALARM_NEXT, Type.MONITOR_STOP,
            ),
            events.map { it.type },
        )
        assertTrue("the offset of that instant on every line", events.all { it.tz == 60 })
        assertEquals("timestamps never go backwards", events.map { it.ts }.sorted(), events.map { it.ts })
        assertNull("no alarm set: alarm_ts is omitted", events[3].alarmTs)
        assertEquals("""{"ts":1790006400000,"tz":60,"type":"alarm_next"}""", lines(dir)[3])
        assertEquals(1_790_120_700_000, events[11].alarmTs)
        // Every line is one of the schema's types and round-trips through the planner's parser.
        assertEquals(events, lines(dir).map { PlannerEvent.parse(it)!! })
        assertNull("an orderly stop leaves no open session", log.openSessionAliveAt())
    }

    @Test
    fun `shifts are logged from the machine's transitions with the on-chain reason codes`() {
        data class Case(val name: String, val reason: Int, val window: Long? = null, val events: List<ShiftEvent>, val advance: Long = 0)
        val cases = listOf(
            Case("classifier pickup", 1, events = listOf(ShiftEvent.PickupDetected)),
            Case("lifted, grace ran out", 1, events = listOf(ShiftEvent.Posture(false), ShiftEvent.Tick), advance = 10_000),
            Case("screen stayed on", 2, events = listOf(ShiftEvent.ScreenOn, ShiftEvent.Tick), advance = 10_000),
            Case("unplugged", 7, events = listOf(ShiftEvent.Power(false), ShiftEvent.Tick), advance = 10_000),
            Case("unlocked", 8, events = listOf(ShiftEvent.UserPresent)),
            Case("frozen", 3, events = listOf(ShiftEvent.Freeze)),
            Case("ended by hand inside the window", 6, window = now / 1000 + 3_600, events = listOf(ShiftEvent.End)),
            Case("ended after the window", 0, window = now / 1000 - 60, events = listOf(ShiftEvent.End)),
            Case("ended with no known window", 6, events = listOf(ShiftEvent.End)),
        )
        for (c in cases) {
            val log = log()
            val r = recorder(log)
            val clock = FakeClock()
            val m = ShiftStateMachine(clock, initialSignals = Signals(charging = true))
            // What the service does: every transition of the machine goes to the recorder.
            fun dispatch(e: ShiftEvent) = r.onTransition(m.dispatch(e))
            dispatch(ShiftEvent.Arm(ShiftSpec(41, ShiftMode.NIGHT, windowEndUnix = c.window)))
            dispatch(ShiftEvent.ScreenOff)
            dispatch(ShiftEvent.Posture(true))
            assertTrue(m.state is ShiftState.Down)
            assertEquals("arming is one line, going hot is none", listOf(Type.SHIFT_ARMED), log.events().map { it.type })
            assertEquals(41L, log.events().single().shiftId)
            for ((i, e) in c.events.withIndex()) {
                if (i == c.events.lastIndex && c.events.size > 1) clock.advance(c.advance)
                dispatch(e)
            }
            val events = log.events()
            assertEquals(c.name, listOf(Type.SHIFT_ARMED, Type.SHIFT_ENDED), events.map { it.type })
            assertEquals(c.name, c.reason, events.last().reason)
            assertEquals(c.name, 41L, events.last().shiftId)
            // Nothing more is written once the shift is over, whatever arrives.
            dispatch(ShiftEvent.ScreenOff)
            dispatch(ShiftEvent.PickupDetected)
            assertEquals(c.name, 2, log.events().size)
        }
        // The reason is the BREAK byte the phone signs for that break.
        val spec = ShiftSpec(1, ShiftMode.DAY)
        for (reason in BreakReason.entries) {
            assertEquals(reason.name, reason.wireReason.wire, RhythmRecorder.endReason(ShiftState.Broken(spec, 0, reason), spec, 0))
        }
    }

    // ------------------------------------------------------------------ a killed session

    @Test
    fun `a session the OS killed is closed on the next start, at the time it was last known alive`() {
        val dir = tmp.newFolder()
        val log = log(dir = dir)
        val first = recorder(log)
        now = at(0, 23 * 60)
        first.monitorStart(screenOn = false, charging = true, nextAlarmTs = null)
        // Alive marks every few minutes until 03:00, then the process dies without a word.
        while (now < at(1, 3 * 60)) {
            now += 5 * minute
            first.alive()
        }
        val diedAt = now
        assertEquals(diedAt, log.openSessionAliveAt())

        now = at(1, 8 * 60)
        recorder(log).monitorStart(screenOn = true, charging = false, nextAlarmTs = null)
        val events = log.events()
        val stop = events.single { it.type == Type.MONITOR_STOP }
        assertEquals("stamped with the last alive time, not with now", diedAt, stop.ts)
        assertTrue("written before the new session's monitor_start", events.indexOf(stop) < events.indexOfLast { it.type == Type.MONITOR_START })
        assertEquals("a new session is open", now, log.openSessionAliveAt())

        // What the planner learns: idle from 23:00 to 03:00, nothing (not busy) from 03:00 to 08:00.
        val labels = SlotLabels.labels(events, at(1, 9 * 60))
        for (slot in 92..95) assertEquals("23:00-24:00 slot $slot", SlotLabels.IDLE, labels[SlotLabels.key(day0, slot)])
        for (slot in 0..11) assertEquals("00:00-03:00 slot $slot", SlotLabels.IDLE, labels[SlotLabels.key(day0 + 1, slot)])
        for (slot in 13..31) assertNull("03:15-08:00 was not observed: slot $slot", labels[SlotLabels.key(day0 + 1, slot)])
        assertEquals("observing again from 08:00, screen on", SlotLabels.BUSY, labels[SlotLabels.key(day0 + 1, 32)])
    }

    @Test
    fun `without alive marks a killed session still ends at its last event`() {
        val log = log()
        now = at(0, 23 * 60)
        val r = recorder(log)
        r.monitorStart(screenOn = true, charging = true, nextAlarmTs = null)
        now += 3 * minute
        r.screen(on = false)
        val last = now
        now = at(1, 8 * 60)
        recorder(log).monitorStart(screenOn = true, charging = false, nextAlarmTs = null)
        assertEquals(last, log.events().single { it.type == Type.MONITOR_STOP }.ts)
    }

    @Test
    fun `a write after the service is gone is dropped, and the next start repairs the session`() {
        val log = log()
        now = at(0, 23 * 60)
        recorder(log).monitorStart(screenOn = false, charging = true, nextAlarmTs = null)
        val gone = RhythmRecorder(log, { now }, Executor { throw RejectedExecutionException() }, tzMinutes = { tz })
        now += minute
        gone.screen(on = true)
        gone.monitorStop()
        assertEquals("nothing was written, nothing was thrown", 4, log.events().size)
        now += minute
        recorder(log).monitorStart(screenOn = true, charging = true, nextAlarmTs = null)
        assertEquals(1, log.events().count { it.type == Type.MONITOR_STOP })
    }

    // ------------------------------------------------------------------ bounded on disk

    @Test
    fun `the log rotates and never exceeds its cap`() {
        val dir = tmp.newFolder()
        val log = log(segmentBytes = 2_000, segments = 3, dir = dir)
        val r = recorder(log)
        now = at(0, 0)
        var written = 0
        repeat(600) {
            now += 30_000
            r.screen(on = it % 2 == 0)
            written++
            assertTrue("cap exceeded: ${log.sizeBytes}", log.sizeBytes <= 3 * 2_000)
        }
        val files = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("planner.1.jsonl", "planner.2.jsonl", "planner.jsonl", "planner.session"), files)
        val events = log.events()
        assertTrue("old lines were dropped (${events.size} of $written kept)", events.size < written)
        assertTrue("at least two full segments are always kept", events.size * 52L >= 2 * 2_000 - 200)
        assertEquals("what is kept is the newest, in order", events.map { it.ts }.sorted(), events.map { it.ts })
        assertEquals(now, events.last().ts)
        assertEquals("a contiguous tail: one event every 30 s", events.size - 1L, (events.last().ts - events.first().ts) / 30_000)
    }

    @Test
    fun `the default cap holds the 26 weeks the planner can use`() {
        // LOG_SCHEMA.md: a busy day is about 12 KB; after a rotation three full segments remain.
        val kept = (PlannerLog.SEGMENTS - 1) * PlannerLog.SEGMENT_BYTES
        assertTrue(kept >= 26 * 7 * 12_000L)
        assertEquals(3L * 1024 * 1024, PlannerLog.SEGMENTS * PlannerLog.SEGMENT_BYTES)
    }

    @Test
    fun `a torn last line is closed off and skipped, and junk never breaks a read`() {
        val dir = tmp.newFolder()
        val log = log(dir = dir)
        val r = recorder(log)
        now = at(0, 23 * 60)
        r.screen(on = false)
        // The process died mid-write; someone else's junk is in there too.
        File(dir, "planner.jsonl").appendText("not json\n[1,2]\n{\"ts\":1,\"tz\":60,\"type\":\"teleport\"}\n{\"ts\":17900064")
        now += minute
        r.screen(on = true)
        val events = log.events()
        assertEquals(listOf(Type.SCREEN_OFF, Type.SCREEN_ON), events.map { it.type })
        assertEquals("the new line starts on its own line", """{"ts":$now,"tz":60,"type":"screen_on"}""", lines(dir).last())
    }

    @Test
    fun `disk trouble never throws into the shift`() {
        // The directory cannot be created: its path is an existing file.
        val blocked = PlannerLog(tmp.newFile("not-a-directory"))
        val r = recorder(blocked)
        r.monitorStart(screenOn = false, charging = true, nextAlarmTs = null)
        r.screen(on = true)
        r.alive()
        r.monitorStop()
        assertTrue(blocked.events().isEmpty())
        assertEquals(0L, blocked.sizeBytes)
        assertNull(blocked.openSessionAliveAt())
        blocked.clear()
    }

    @Test
    fun `clear deletes every file of the log`() {
        val dir = tmp.newFolder()
        val log = log(segmentBytes = 500, segments = 3, dir = dir)
        val r = recorder(log)
        repeat(60) { now += 1_000; r.screen(on = it % 2 == 0) }
        assertTrue(dir.listFiles()!!.size >= 3)
        log.clear()
        assertTrue(dir.listFiles()!!.isEmpty())
        assertTrue(log.events().isEmpty())
    }

    // ------------------------------------------------------------------ it stays on the phone

    @Test
    fun `no FileProvider path in the app can reach the planner log`() {
        // The log lives in noBackupFilesDir/foreman, which only a root-path could reach. The
        // app's FileProviders are declared by path XML files: none may expose the storage root,
        // and (should the log ever move back under filesDir) none may expose filesDir as a whole
        // or a foreman folder in it.
        // Gradle runs unit tests in the module directory: android/feature/shift.
        val module = File(checkNotNull(System.getProperty("user.dir")))
        val android = checkNotNull(module.parentFile?.parentFile) { "not inside android/: $module" }
        val pathFiles = android.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "xml" && it.parentFile?.name == "xml" && "<paths" in it.readText() }
            .toList()
        assertTrue("the sensor lab's and the reveal's path files are found: $pathFiles", pathFiles.size >= 2)
        val entry = Regex("""<([a-z-]+)\s[^>]*?path="([^"]*)"""")
        var checked = 0
        for (file in pathFiles) {
            for (m in entry.findAll(file.readText())) {
                val (kind, path) = m.destructured
                checked++
                assertFalse("${file.name}: $kind exposes the whole device or app storage", kind == "root-path" || kind == "external-path")
                if (kind == "files-path") {
                    val normal = path.trim('/')
                    assertFalse("${file.name}: files-path \"$path\" covers ${PlannerLog.DIRECTORY}/",
                        normal.isEmpty() || normal == "." || normal == PlannerLog.DIRECTORY || normal.startsWith(PlannerLog.DIRECTORY + "/"))
                }
            }
        }
        assertTrue(checked >= 2)
    }
}
