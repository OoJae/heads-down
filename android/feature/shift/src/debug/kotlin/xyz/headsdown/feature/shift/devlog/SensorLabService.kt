package xyz.headsdown.feature.shift.devlog

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import xyz.headsdown.surface.notification.R as NotificationR

/** What the lab screen shows while a session records. */
data class LabStatus(
    val recording: Boolean = false,
    val sessionId: String? = null,
    val label: SensorLabLabel = SensorLabLabel.UNLABELED,
    val samples: Long = 0,
    val windows: Int = 0,
    val events: Int = 0,
)

/**
 * DEBUG BUILDS ONLY (src/debug: the class does not exist in release APKs, and it also refuses
 * to run in a non-debuggable app).
 *
 * Records a sensor lab session for the pickup/bump classifier: the accelerometer at 50 Hz, a
 * window of 2 s before to 3 s after every motion event, plus screen-on, screen-off and unlock
 * as ground truth, to `filesDir/sensorlab/session-<id>.csv`. A foreground service with a
 * time-limited partial wake lock, so it keeps recording with the screen off, face-down on a
 * nightstand or a table, exactly where the classifier will run.
 */
class SensorLabService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var writer: BufferedWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var trigger = MotionTrigger()
    @Volatile private var recorder: WindowRecorder? = null
    private var receiverRegistered = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
            val s = AccelSample(event.timestamp, SystemClock.elapsedRealtimeNanos(), event.values[0], event.values[1], event.values[2])
            recorder?.onSample(s, trigger.onSample(s))
            _status.value = _status.value.let { it.copy(samples = it.samples + 1) }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val truthReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val truth = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> GroundTruth.SCREEN_ON
                Intent.ACTION_SCREEN_OFF -> GroundTruth.SCREEN_OFF
                Intent.ACTION_USER_PRESENT -> GroundTruth.UNLOCK
                else -> return
            }
            val at = SystemClock.elapsedRealtimeNanos()
            handler?.post {
                write(listOf(SensorLogCsv.event(truth, at)))
                _status.value = _status.value.let { it.copy(events = it.events + 1) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (!isDebuggable(this)) {
            // Belt and braces: this class is only compiled into debug builds anyway.
            stopEverything()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> if (!_status.value.recording) begin(SensorLabLabel.fromWire(intent.getStringExtra(EXTRA_LABEL)))
            else -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun begin(label: SensorLabLabel) {
        val sm = getSystemService<SensorManager>()
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || sensor == null) {
            stopEverything()
            return
        }
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
        val file = store(this).sessionFile(id)
        val out = file.bufferedWriter()
        writer = out
        val meta = SessionMeta(
            id = id,
            startedWallMillis = now,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            sdkInt = Build.VERSION.SDK_INT,
            sensor = "${sensor.name} / ${sensor.vendor}",
            rateHz = RATE_HZ,
            intendedLabel = label,
        )
        write(SensorLogCsv.header(meta) + SensorLogCsv.event(GroundTruth.SESSION_START, SystemClock.elapsedRealtimeNanos()))
        trigger = MotionTrigger()
        recorder = WindowRecorder { window ->
            write(SensorLogCsv.window(window))
            _status.value = _status.value.copy(windows = window.id)
        }
        val t = HandlerThread("hd-sensorlab").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        _status.value = LabStatus(recording = true, sessionId = id, label = label)
        sm.registerListener(listener, sensor, 1_000_000 / RATE_HZ, h)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, truthReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        acquireWakeLock()
        h.postDelayed({ stopEverything() }, MAX_SESSION_MILLIS)
    }

    private fun write(lines: List<String>) {
        val w = writer ?: return
        runCatching {
            lines.forEach { w.write(it); w.write("\n") }
            w.flush()
        }
    }

    private fun stopEverything() {
        getSystemService<SensorManager>()?.unregisterListener(listener)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(truthReceiver) }
            receiverRegistered = false
        }
        val h = handler
        val finish = {
            recorder?.flush()
            recorder = null
            if (writer != null) write(listOf(SensorLogCsv.event(GroundTruth.SESSION_END, SystemClock.elapsedRealtimeNanos())))
            runCatching { writer?.close() }
            writer = null
        }
        if (h != null) {
            h.removeCallbacksAndMessages(null)
            h.post { finish() }
            thread?.quitSafely()
        } else {
            finish()
        }
        handler = null
        thread = null
        wakeLock?.let { if (it.isHeld) it.release() }
        _status.value = _status.value.copy(recording = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (_status.value.recording) stopEverything()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService<NotificationManager>()
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sensor lab (debug)", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, SensorLabService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(NotificationR.drawable.ic_stat_rig)
            .setContentTitle("Sensor lab recording (debug)")
            .setContentText("Accelerometer windows and screen events, stored on this phone")
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    @SuppressLint("WakelockTimeout") // a timeout is always passed
    private fun acquireWakeLock() {
        val lock = getSystemService<PowerManager>()?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeadsDown:sensorlab") ?: return
        lock.setReferenceCounted(false)
        lock.acquire(MAX_SESSION_MILLIS + 60_000)
        wakeLock = lock
    }

    companion object {
        private const val ACTION_START = "xyz.headsdown.sensorlab.START"
        private const val ACTION_STOP = "xyz.headsdown.sensorlab.STOP"
        private const val EXTRA_LABEL = "label"
        private const val CHANNEL = "hd.sensorlab"
        private const val NOTIFICATION_ID = 0x5342 // "SB"
        const val RATE_HZ = 50

        /** A session ends by itself after this long, whatever happens to the screen. */
        const val MAX_SESSION_MILLIS = 3 * 60 * 60 * 1000L

        private val _status = MutableStateFlow(LabStatus())
        val status: StateFlow<LabStatus> = _status.asStateFlow()

        fun store(context: Context) = SensorLabStore(File(context.filesDir, "sensorlab"))

        fun isDebuggable(context: Context): Boolean =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        fun start(context: Context, label: SensorLabLabel) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SensorLabService::class.java).setAction(ACTION_START).putExtra(EXTRA_LABEL, label.wire),
            )
        }

        fun stop(context: Context) {
            if (!_status.value.recording) return
            context.startService(Intent(context, SensorLabService::class.java).setAction(ACTION_STOP))
        }
    }
}
