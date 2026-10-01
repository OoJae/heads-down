package xyz.headsdown.feature.shift.devlog

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import xyz.headsdown.feature.shift.foreman.ForemanSettings
import xyz.headsdown.ml.ForemanModels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Serves only `filesDir/sensorlab/export/` to the app picked in the share sheet (debug builds). */
class SensorLabExportProvider : FileProvider()

/**
 * DEBUG BUILDS ONLY. The sensor lab screen: pick what you are about to do (bump, pickup, slide,
 * set-down), record a session, relabel sessions afterwards, and export everything as one CSV
 * through the share sheet. Plain Views, so feature/shift needs no Compose for a debug tool.
 */
@SuppressLint("SetTextI18n") // a developer tool, English only, never shipped
class SensorLabActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var record: Button
    private lateinit var sessionsBox: LinearLayout
    private var chosen = SensorLabLabel.PICKUP
    private var renderedSessions: String? = null

    // The pickup classifier's debug hooks: time it on this phone, and take it out of the shift.
    private val foremanSettings by lazy { ForemanSettings(applicationContext) }
    private lateinit var classifierStatus: TextView
    private lateinit var breaksSwitch: Button
    private var benchmarking = false
    private var benchmarkLine = "Not benchmarked yet."

    private val poll = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SensorLabService.isDebuggable(this)) {
            finish()
            return
        }
        title = "Sensor lab"
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        column.addView(text("Sensor lab", 24f, bold = true))
        column.addView(text("DEBUG BUILD ONLY", 12f, color = COOLING, mono = true))
        column.addView(
            text(
                "Records the accelerometer at ${SensorLabService.RATE_HZ} Hz around every motion (2 s before to 3 s after), " +
                    "plus screen-on, screen-off and unlock as ground truth. Accelerometer only: no gyroscope is used. " +
                    "Everything stays in this app's private storage until you export it.",
                14f, color = ASH_MUTED,
            ),
        )
        column.addView(text("What will you do in the next session?", 16f, bold = true, top = 20))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        SensorLabLabel.entries.forEachIndexed { i, label ->
            group.addView(
                RadioButton(this).apply {
                    id = View.generateViewId()
                    text = label.title
                    setTextColor(ASH)
                    isChecked = label == chosen
                    setOnCheckedChangeListener { _, checked -> if (checked) chosen = label }
                    tag = i
                },
            )
        }
        column.addView(group)
        column.addView(
            text(
                "Start, lay the phone where it will sleep (nightstand, desk, table), then repeat the motion 10 to 20 times " +
                    "with a few seconds of stillness between. For pickups, really pick it up and look at it. Stop when done.",
                14f, color = ASH_MUTED,
            ),
        )
        record = Button(this).apply {
            setOnClickListener {
                if (SensorLabService.status.value.recording) SensorLabService.stop(this@SensorLabActivity)
                else SensorLabService.start(this@SensorLabActivity, chosen)
                main.postDelayed({ render() }, 200)
            }
        }
        column.addView(record)
        status = text("", 14f, mono = true)
        column.addView(status)
        column.addView(text("Sessions", 18f, bold = true, top = 24))
        sessionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sessionsBox)
        column.addView(Button(this).apply { text = "Export all as CSV"; setOnClickListener { export() } })
        column.addView(
            Button(this).apply {
                text = "Delete all sessions"
                setOnClickListener {
                    if (!SensorLabService.status.value.recording) {
                        SensorLabService.store(this@SensorLabActivity).deleteAll()
                        renderedSessions = null
                        render()
                    }
                }
            },
        )
        column.addView(text("Pickup classifier", 18f, bold = true, top = 24))
        column.addView(
            text(
                "The model the shift service runs (trained on synthetic data only). The benchmark times one motion " +
                    "window from raw samples to a verdict, on a background-priority thread like the service's. " +
                    "The switch takes the classifier out of a shift: tilt, screen-on, unlock and unplugging still break it.",
                14f, color = ASH_MUTED,
            ),
        )
        classifierStatus = text("", 13f, mono = true)
        column.addView(classifierStatus)
        column.addView(Button(this).apply { text = "Benchmark the classifier on this phone"; setOnClickListener { benchmark() } })
        breaksSwitch = Button(this).apply {
            setOnClickListener {
                foremanSettings.pickupBreaksEnabled = !foremanSettings.pickupBreaksEnabled
                renderClassifier()
            }
        }
        column.addView(breaksSwitch)
        renderClassifier()
        setContentView(ScrollView(this).apply { setBackgroundColor(CHARCOAL); addView(column) })
    }

    private fun renderClassifier() {
        val on = foremanSettings.pickupBreaksEnabled
        breaksSwitch.text = if (on) "Pickup breaks: ON (tap to switch off)" else "Pickup breaks: OFF (tap to switch on)"
        classifierStatus.text = if (benchmarking) "Benchmarking…" else benchmarkLine
    }

    /** Debug hook: per-window inference time on this device, off the main thread. */
    private fun benchmark() {
        if (benchmarking) return
        benchmarking = true
        renderClassifier()
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val line = try {
                    PickupBenchmark.run(ForemanModels.pickupClassifier(assets), PickupBenchmark.syntheticWindows()).summary()
                } catch (e: RuntimeException) {
                    "Benchmark failed: ${e.javaClass.simpleName}"
                }
                main.post {
                    benchmarking = false
                    benchmarkLine = line
                    if (!isDestroyed) renderClassifier()
                }
            },
            "hd-pickup-benchmark",
        ).start()
    }

    override fun onStart() {
        super.onStart()
        main.post(poll)
    }

    override fun onStop() {
        main.removeCallbacks(poll)
        super.onStop()
    }

    private fun render() {
        val s = SensorLabService.status.value
        record.text = if (s.recording) "Stop recording" else "Start recording (${chosen.title.lowercase()})"
        status.text = if (s.recording) {
            "● REC ${s.sessionId} · ${s.label.wire}\n${s.samples} samples · ${s.windows} windows · ${s.events} screen events"
        } else {
            "Not recording."
        }
        val sessions = SensorLabService.store(this).sessions()
        val signature = sessions.joinToString { "${it.id}:${it.label}:${it.windows}:${it.events}" } + s.recording
        if (signature == renderedSessions) return
        renderedSessions = signature
        sessionsBox.removeAllViews()
        if (sessions.isEmpty()) sessionsBox.addView(text("No sessions yet.", 14f, color = ASH_MUTED))
        sessions.forEach { info ->
            val live = s.recording && s.sessionId == info.id
            sessionsBox.addView(
                text(
                    "${info.id} · ${info.label.wire} · ${info.windows} windows · ${info.events} events · ${info.bytes / 1024} KB" +
                        if (live) " · recording" else "",
                    13f, mono = true, top = 12,
                ),
            )
            if (live) return@forEach
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            SensorLabLabel.entries.filter { it != SensorLabLabel.UNLABELED }.forEach { label ->
                row.addView(
                    Button(this).apply {
                        text = label.title
                        textSize = 11f
                        isEnabled = label != info.label
                        setOnClickListener {
                            SensorLabService.store(this@SensorLabActivity).setLabel(info.id, label)
                            render()
                        }
                    },
                )
            }
            row.addView(
                Button(this).apply {
                    text = "Delete"
                    textSize = 11f
                    setOnClickListener {
                        SensorLabService.store(this@SensorLabActivity).delete(info.id)
                        render()
                    }
                },
            )
            sessionsBox.addView(android.widget.HorizontalScrollView(this).apply { addView(row) })
        }
    }

    private fun export() {
        val store = SensorLabService.store(this)
        if (store.sessions().isEmpty()) return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = store.export(stamp)
        val uri = FileProvider.getUriForFile(this, "$packageName.sensorlab", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        try {
            startActivity(Intent.createChooser(send, "Export sensor lab data"))
        } catch (_: ActivityNotFoundException) {
            status.text = "No app can receive a CSV. The export is at ${file.name}."
        }
    }

    private fun text(
        value: String,
        sizeSp: Float,
        bold: Boolean = false,
        color: Int = ASH,
        mono: Boolean = false,
        top: Int = 8,
    ) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.START
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val CHARCOAL = "#121314".toColorInt()
        val ASH = "#ECE7E1".toColorInt()
        val ASH_MUTED = "#9A948D".toColorInt()
        val COOLING = "#FFB067".toColorInt()
    }
}
