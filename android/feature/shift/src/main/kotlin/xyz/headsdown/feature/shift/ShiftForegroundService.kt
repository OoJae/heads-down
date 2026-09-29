package xyz.headsdown.feature.shift

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.SignedHeartbeat
import xyz.headsdown.surface.notification.NotificationChannels
import xyz.headsdown.surface.notification.ShiftNotificationFactory
import javax.inject.Inject

/**
 * The user-started shift: a `specialUse` foreground service that turns device signals into
 * [ShiftStateMachine] events and, while the rig is DOWN, signs one heartbeat per ORE round.
 *
 * Inputs:
 * - accelerometer at 5 Hz with 1 s hardware batching -> [FaceDownDetector] (soft);
 * - `SCREEN_ON` / `SCREEN_OFF` / `USER_PRESENT` receivers (hard);
 * - charger state from the sticky `ACTION_BATTERY_CHANGED` + power receivers (hard).
 *
 * Power: a partial wake lock is held only while a shift is running (armed/down/cooling) and
 * is re-armed with a timeout every round, so a bug can never pin the CPU forever. Night
 * Shift is on the charger by definition; Day Shift is user-chosen and time-boxed.
 *
 * Fail-safe: `START_NOT_STICKY`. If HyperOS kills the process, the shift stays dead: no
 * heartbeats, so no digs, and the journal lets the next launch report the kill.
 */
@AndroidEntryPoint
class ShiftForegroundService : LifecycleService() {

    @Inject lateinit var repository: ShiftStatusRepository
    @Inject lateinit var signerProvider: RigSignerProvider
    @Inject lateinit var bindingProvider: RigBindingProvider
    @Inject lateinit var roundSource: OreRoundSource
    @Inject lateinit var sink: HeartbeatSink
    @Inject lateinit var counter: HeartbeatCounter
    @Inject lateinit var journal: ShiftJournal

    private val clock = MonotonicClock { SystemClock.elapsedRealtime() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val detector = FaceDownDetector()
    private lateinit var machine: ShiftStateMachine
    private lateinit var notifications: ShiftNotificationFactory

    private var sensorThread: HandlerThread? = null
    private var tickerJob: Job? = null
    private var ticker: HeartbeatTicker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var snapshot = ShiftSnapshot.IDLE

    /** Written on the main thread, read by the ticker thread. */
    @Volatile private var hotSpec: ShiftSpec? = null

    /** Last accelerometer sample time (elapsedRealtime ms), written on the sensor thread. */
    @Volatile private var lastPostureSampleAt: Long = Long.MIN_VALUE

    private val signalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> dispatch(ShiftEvent.ScreenOn)
                Intent.ACTION_SCREEN_OFF -> dispatch(ShiftEvent.ScreenOff)
                Intent.ACTION_USER_PRESENT -> dispatch(ShiftEvent.UserPresent)
                Intent.ACTION_POWER_CONNECTED -> dispatch(ShiftEvent.Power(charging = true))
                Intent.ACTION_POWER_DISCONNECTED -> dispatch(ShiftEvent.Power(charging = false))
            }
        }
    }

    private val accelListener = object : SensorEventListener {
        private var lastVerdict = false
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
            val verdict = detector.onSample(event.values[0], event.values[1], event.values[2], event.timestamp)
            lastPostureSampleAt = event.timestamp / 1_000_000
            if (verdict != lastVerdict) {
                lastVerdict = verdict
                mainHandler.post { dispatch(ShiftEvent.Posture(verdict)) }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        notifications = ShiftNotificationFactory(this)
        val power = getSystemService<PowerManager>()
        machine = ShiftStateMachine(
            clock,
            initialSignals = Signals(faceDown = false, screenOn = power?.isInteractive ?: true, charging = isPluggedIn()),
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // Only system broadcasts are wanted; other apps cannot reach this receiver.
        ContextCompat.registerReceiver(this, signalReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        startAccelerometer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // startForegroundService() requires startForeground() promptly, whatever the action.
        promoteToForeground()
        when (intent?.action) {
            ACTION_ARM -> {
                val spec = repository.takePendingArm()
                if (spec == null) {
                    if (machine.state == ShiftState.Idle) finishShift("no pending arm")
                } else {
                    arm(spec)
                }
            }
            ACTION_END -> dispatch(ShiftEvent.End)
            ACTION_FREEZE -> dispatch(ShiftEvent.Freeze)
            else -> if (machine.state == ShiftState.Idle) finishShift("unknown start")
        }
        return START_NOT_STICKY
    }

    private fun arm(spec: ShiftSpec) {
        val before = machine.state
        dispatch(ShiftEvent.Arm(spec))
        if (before == machine.state) return // already in a shift
        journal.onArmed(spec, System.currentTimeMillis())
        acquireWakeLock()
        startTicker()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        val signer = signerProvider.heartbeatSigner()
        snapshot = snapshot.copy(signing = signer != null, localOnly = !bindingProvider.current().isRegistered)
        if (signer == null) {
            publish()
            return // no rig key yet: the shift still runs (focus/streak), but nothing is signed
        }
        val t = HeartbeatTicker(
            rounds = roundSource,
            eligibleSpec = ::eligibleSpec,
            binding = bindingProvider::current,
            signer = signer,
            counter = counter,
            sink = sink,
            onResult = { _, result -> mainHandler.post { onTick(result) } },
        )
        ticker = t
        tickerJob = lifecycleScope.launch(Dispatchers.Default) { t.run() }
        publish()
    }

    /** DOWN and a posture sample from the last few seconds; otherwise no heartbeat. */
    private fun eligibleSpec(): ShiftSpec? {
        val spec = hotSpec ?: return null
        val age = SystemClock.elapsedRealtime() - lastPostureSampleAt
        return if (age in 0..POSTURE_MAX_AGE_MILLIS) spec else null
    }

    private fun onTick(result: TickResult) {
        acquireWakeLock() // renew the timeout every round
        if (result is TickResult.Signed) {
            val now = System.currentTimeMillis()
            snapshot = snapshot.copy(darkRounds = snapshot.darkRounds + 1, lastHeartbeatWallMillis = now)
            journal.onHeartbeat(now, snapshot.darkRounds)
            publish()
        }
    }

    private fun dispatch(event: ShiftEvent) {
        if (!::machine.isInitialized) return
        val t = machine.dispatch(event)
        val to = t.to
        hotSpec = (to as? ShiftState.Down)?.spec
        for (effect in t.effects) handle(effect)
        if (to is ShiftState.Down && snapshot.darkSinceWallMillis == null) {
            snapshot = snapshot.copy(darkSinceWallMillis = System.currentTimeMillis() - (clock.nowMillis() - to.firstDownAt))
        }
        snapshot = snapshot.copy(state = to)
        publish()
        when (to) {
            ShiftState.Idle -> finishShift("ended")
            is ShiftState.Broken -> finishShift("broken:${to.reason}")
            is ShiftState.Frozen -> if (t.from !is ShiftState.Frozen) finishShift("frozen")
            else -> Unit
        }
    }

    private fun handle(effect: ShiftEffect) {
        when (effect) {
            is ShiftEffect.ScheduleTick -> {
                val delay = (effect.atMillis - clock.nowMillis()).coerceAtLeast(0)
                mainHandler.postDelayed({ dispatch(ShiftEvent.Tick) }, delay)
            }
            is ShiftEffect.SignBreak -> relaySignal(RigSignalState.BROKEN, effect.spec.shiftId)
            is ShiftEffect.SignFreeze -> relaySignal(RigSignalState.FROZEN, effect.shiftId)
            is ShiftEffect.CoolingStarted, ShiftEffect.WentDark, ShiftEffect.ShiftEnded -> Unit
        }
    }

    /** Best-effort BREAK/FREEZE. Liveness only: without heartbeats the rig is cold anyway. */
    private fun relaySignal(state: RigSignalState, shiftId: Long?) {
        val t = ticker ?: return
        lifecycleScope.launch(Dispatchers.Default) {
            val signed: SignedHeartbeat? = runCatching { t.signSignal(state, shiftId) }.getOrNull()
            if (signed != null) runCatching { sink.deliver(signed) }
        }
    }

    private fun finishShift(reason: String) {
        tickerJob?.cancel()
        tickerJob = null
        mainHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        if (snapshot.state != ShiftState.Idle || reason != "ended") journal.onEnded(System.currentTimeMillis(), reason)
        val finalState = machine.state
        if (finalState is ShiftState.Broken || finalState is ShiftState.Frozen) {
            // Leave a non-ongoing "rig cold/frozen" notification behind.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            getSystemService<NotificationManager>()?.notify(
                ShiftNotificationFactory.SHIFT_NOTIFICATION_ID,
                notifications.build(snapshot.toNotificationState(), contentIntent()),
            )
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(signalReceiver) }
        getSystemService<SensorManager>()?.unregisterListener(accelListener)
        sensorThread?.quitSafely()
        releaseWakeLock()
        mainHandler.removeCallbacksAndMessages(null)
        repository.publish(ShiftSnapshot.IDLE)
        super.onDestroy()
    }

    private fun publish() {
        repository.publish(snapshot)
        if (snapshot.state == ShiftState.Idle) return
        getSystemService<NotificationManager>()?.notify(ShiftNotificationFactory.SHIFT_NOTIFICATION_ID, currentNotification())
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            ShiftNotificationFactory.SHIFT_NOTIFICATION_ID,
            currentNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun currentNotification(): Notification =
        notifications.build(snapshot.toNotificationState(), contentIntent())

    private fun contentIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun startAccelerometer() {
        val sm = getSystemService<SensorManager>() ?: return
        // Prefer the wake-up variant where present; otherwise the wake lock keeps samples flowing.
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return // no accelerometer: posture never goes face-down, so the rig never goes hot
        val thread = HandlerThread("hd-posture").also { it.start() }
        sensorThread = thread
        sm.registerListener(accelListener, sensor, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US, Handler(thread.looper))
    }

    private fun isPluggedIn(): Boolean {
        val sticky = ContextCompat.registerReceiver(
            this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        ) ?: return false
        return sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    @SuppressLint("WakelockTimeout") // a timeout is always passed
    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeadsDown:shift")
            ?.also { it.setReferenceCounted(false); wakeLock = it }
            ?: return
        lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    companion object {
        const val ACTION_ARM = "xyz.headsdown.shift.ARM"
        const val ACTION_END = "xyz.headsdown.shift.END"
        const val ACTION_FREEZE = "xyz.headsdown.shift.FREEZE"

        /** 5 Hz: enough for the 1.5 s enter / 0.3 s exit dwell, cheap on battery. */
        private const val SAMPLING_PERIOD_US = 200_000

        /** Let the sensor hub batch 1 s of samples between deliveries. */
        private const val MAX_REPORT_LATENCY_US = 1_000_000

        /** A heartbeat needs a posture sample at most this old. */
        private const val POSTURE_MAX_AGE_MILLIS = 5_000L

        /** Three ORE rounds; renewed every round while the shift runs. */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 3 * StubOreRoundSource.ORE_ROUND_MILLIS
    }
}
