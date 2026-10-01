package xyz.headsdown.feature.shift

import android.annotation.SuppressLint
import android.app.AlarmManager
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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.headsdown.core.keys.SignedShiftSignal
import xyz.headsdown.feature.shift.foreman.ForemanRuntime
import xyz.headsdown.feature.shift.foreman.ForemanSettings
import xyz.headsdown.feature.shift.foreman.PickupWatch
import xyz.headsdown.feature.shift.foreman.PlannerLog
import xyz.headsdown.feature.shift.foreman.PlannerRepository
import xyz.headsdown.feature.shift.foreman.RhythmRecorder
import xyz.headsdown.ml.ForemanGate
import xyz.headsdown.surface.notification.NotificationChannels
import xyz.headsdown.surface.notification.ShiftNotificationFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * The user-started shift: a `specialUse` foreground service that turns device signals into
 * [ShiftStateMachine] events and, while the rig is DOWN, signs one heartbeat per ORE round.
 *
 * Inputs:
 * - accelerometer at 50 Hz with 1 s hardware batching -> [FaceDownDetector] (soft) and the
 *   pickup classifier's [PickupWatch], which can add one break and nothing else;
 * - `SCREEN_ON` / `SCREEN_OFF` / `USER_PRESENT` receivers (hard);
 * - charger state from the sticky `ACTION_BATTERY_CHANGED` + power receivers (hard).
 *
 * Foreman (on-device models, see FOREMAN.md in this module):
 * - every sample feeds the motion-window collector; a window is classified only for a motion
 *   that began and ended on a rig that was DOWN with the screen off, on a background thread,
 *   and a PICKUP verdict dispatches [ShiftEvent.PickupDetected]. The sample path allocates
 *   nothing; a still night runs no inference at all.
 * - the same receivers write the Shift Planner's log ([RhythmRecorder]) to app-private storage.
 *   It is never uploaded.
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
    @Inject lateinit var journal: ShiftJournal
    @Inject lateinit var foreman: ForemanRuntime
    @Inject lateinit var settings: ForemanSettings
    @Inject lateinit var plannerLog: PlannerLog
    @Inject lateinit var planner: PlannerRepository

    private val clock = MonotonicClock { SystemClock.elapsedRealtime() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val detector = FaceDownDetector()
    private lateinit var machine: ShiftStateMachine
    private lateinit var notifications: ShiftNotificationFactory

    /**
     * One low-priority thread for everything Foreman does off the main and sensor threads:
     * loading the model, classifying a motion window, appending a planner log line. In order.
     */
    private val foremanExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            },
            "hd-foreman",
        )
    }
    private lateinit var pickupWatch: PickupWatch
    private lateinit var rhythm: RhythmRecorder

    private var sensorThread: HandlerThread? = null
    private var tickerJob: Job? = null
    private var ticker: HeartbeatTicker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var snapshot = ShiftSnapshot.IDLE

    /** Written on the main thread, read by the ticker thread. */
    @Volatile private var hotSpec: ShiftSpec? = null

    /**
     * When the last accelerometer batch was delivered (elapsedRealtime ms), written on the
     * sensor thread. Delivery time rather than `SensorEvent.timestamp`, whose clock base is
     * not guaranteed to be elapsedRealtime on every device; batching adds at most ~1 s.
     */
    @Volatile private var lastPostureSampleAt: Long = Long.MIN_VALUE

    // Each signal is written to the planner log as it arrives, then given to the machine.
    private val signalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    rhythm.screen(on = true)
                    dispatch(ShiftEvent.ScreenOn)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    rhythm.screen(on = false)
                    dispatch(ShiftEvent.ScreenOff)
                }
                Intent.ACTION_USER_PRESENT -> {
                    rhythm.userPresent()
                    dispatch(ShiftEvent.UserPresent)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    rhythm.power(connected = true)
                    dispatch(ShiftEvent.Power(charging = true))
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    rhythm.power(connected = false)
                    dispatch(ShiftEvent.Power(charging = false))
                }
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> rhythm.alarmNext(nextAlarmWallMillis())
            }
        }
    }

    /**
     * The sensor thread's whole job, 50 times a second: the face-down detector and the pickup
     * watch see the sample, and nothing is allocated (PickupWatchTest measures it). Only a
     * change of the face-down verdict, or a motion window closing, leaves this thread.
     */
    private val accelListener = object : SensorEventListener {
        private var lastVerdict = false
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val verdict = detector.onSample(x, y, z, event.timestamp)
            pickupWatch.onSample(event.timestamp, x, y, z)
            lastPostureSampleAt = SystemClock.elapsedRealtime()
            if (verdict != lastVerdict) {
                lastVerdict = verdict
                mainHandler.post {
                    rhythm.faceDown(verdict)
                    dispatch(ShiftEvent.Posture(verdict))
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        notifications = ShiftNotificationFactory(this)
        val screenOn = getSystemService<PowerManager>()?.isInteractive ?: true
        val charging = isPluggedIn()
        machine = ShiftStateMachine(
            clock,
            initialSignals = Signals(faceDown = false, screenOn = screenOn, charging = charging),
        )
        pickupWatch = PickupWatch(
            classifier = { foreman.pickupClassifier },
            executor = foremanExecutor,
            // Called on the Foreman thread; the machine lives on the main thread.
            onPickup = { mainHandler.post(::onPickupVerdict) },
            enabled = { settings.pickupBreaksEnabled },
        )
        // Read the model off the main thread now, not when the first motion window closes.
        foremanExecutor.execute { foreman.pickupClassifier }
        rhythm = RhythmRecorder(plannerLog, System::currentTimeMillis, foremanExecutor)
        rhythm.monitorStart(screenOn, charging, nextAlarmWallMillis())
        // Tonight's plan for whoever shows it, made once the lines above are on disk.
        foremanExecutor.execute { planner.requestRefresh() }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        // Only system broadcasts are wanted; other apps cannot reach this receiver.
        ContextCompat.registerReceiver(this, signalReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        startAccelerometer()
        // If the OS kills the service, the planner log's session is closed at the last mark.
        lifecycleScope.launch {
            while (isActive) {
                delay(ALIVE_MARK_MILLIS)
                rhythm.alive()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // startForegroundService() requires startForeground() promptly, whatever the action.
        promoteToForeground()
        when (intent?.action) {
            ACTION_ARM -> {
                val spec = repository.takePendingArm()
                if (spec == null) {
                    if (machine.state == ShiftState.Idle) finishShift(reason = null)
                } else {
                    arm(spec)
                }
            }
            ACTION_END -> dispatch(ShiftEvent.End)
            ACTION_FREEZE -> dispatch(ShiftEvent.Freeze)
            else -> if (machine.state == ShiftState.Idle) finishShift(reason = null)
        }
        return START_NOT_STICKY
    }

    private fun arm(spec: ShiftSpec) {
        val before = machine.state
        dispatch(ShiftEvent.Arm(spec))
        if (before == machine.state) return // already in a shift
        pickupWatch.clear() // a verdict left over from an earlier shift vetoes nothing in this one
        journal.onArmed(spec, System.currentTimeMillis())
        acquireWakeLock()
        startTicker(spec.leaseRounds)
    }

    private fun startTicker(leaseRounds: Int) {
        tickerJob?.cancel()
        val signer = signerProvider.messageSigner()
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
            sink = sink,
            leaseRounds = leaseRounds,
            onResult = { _, result -> mainHandler.post { onTick(result) } },
        )
        ticker = t
        runCatching { sink.open() } // uplink connects in the background; never blocks the shift
        tickerJob = lifecycleScope.launch(Dispatchers.Default) { t.run() }
        publish()
    }

    /**
     * DOWN and a posture sample from the last few seconds; otherwise no heartbeat. "Dark" is
     * decided by the machine's own signals and the detector alone. The classifier enters only
     * through `ForemanGate.heartbeatAllowed`, where a pickup verdict can veto a heartbeat (in
     * the instant before the break is dispatched) and no verdict can allow one.
     */
    private fun eligibleSpec(): ShiftSpec? {
        val spec = hotSpec ?: return null
        val age = SystemClock.elapsedRealtime() - lastPostureSampleAt
        val dark = age in 0..POSTURE_MAX_AGE_MILLIS
        return if (ForemanGate.heartbeatAllowed(dark, pickupWatch.veto)) spec else null
    }

    /**
     * Main thread: the classifier judged a motion of the hot rig a pickup. The machine has the
     * last word: only a rig that is still DOWN breaks, and it breaks as LIFTED (BREAK reason 1).
     * The same wiring, on one thread, is what PickupWatchTest's rig replays recorded windows on.
     */
    private fun onPickupVerdict() {
        if (machine.state is ShiftState.Down) dispatch(ShiftEvent.PickupDetected)
        pickupWatch.clear()
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
        // The classifier judges a motion only if the rig was hot from its start to its end.
        pickupWatch.onRig(hot = to is ShiftState.Down && !t.signals.screenOn)
        rhythm.onTransition(t) // shift_armed / shift_ended for the planner log
        for (effect in t.effects) handle(effect)
        if (to is ShiftState.Down && snapshot.darkSinceWallMillis == null) {
            snapshot = snapshot.copy(darkSinceWallMillis = System.currentTimeMillis() - (clock.nowMillis() - to.firstDownAt))
        }
        snapshot = snapshot.copy(state = to)
        if (t.isPickup) journal.onPickup(System.currentTimeMillis())
        publish()
        // Every exit from a running shift is journaled, so the health check can tell an
        // ended shift from one the OS killed (which never reaches this code).
        val wasRunning = t.from !is ShiftState.Idle
        when (to) {
            ShiftState.Idle -> finishShift(reason = if (wasRunning) "ended" else null)
            is ShiftState.Broken -> finishShift(reason = "broken:${to.reason}")
            is ShiftState.Frozen -> if (t.from !is ShiftState.Frozen) finishShift(reason = "frozen")
            else -> Unit
        }
    }

    private fun handle(effect: ShiftEffect) {
        when (effect) {
            is ShiftEffect.ScheduleTick -> {
                val delay = (effect.atMillis - clock.nowMillis()).coerceAtLeast(0)
                mainHandler.postDelayed({ dispatch(ShiftEvent.Tick) }, delay)
            }
            is ShiftEffect.SignBreak ->
                if (effect.spec.breakStillUseful(System.currentTimeMillis() / 1000)) {
                    relaySignal { it.signBreak(effect.spec.shiftId, effect.reason.wireReason) }
                }
            // The FREEZE preimage carries the rig's on-chain shift_id; with no shift running
            // that is the last one armed (journaled), or 0 before any.
            is ShiftEffect.SignFreeze ->
                relaySignal { it.signFreeze(effect.shiftId ?: journal.last()?.shiftId) }
            is ShiftEffect.CoolingStarted, ShiftEffect.WentDark, ShiftEffect.ShiftEnded -> Unit
        }
    }

    /** Best-effort BREAK/FREEZE. Liveness only: without heartbeats the rig is cold anyway. */
    @OptIn(DelicateCoroutinesApi::class) // CoroutineStart.ATOMIC, reason below
    private fun relaySignal(sign: (HeartbeatTicker) -> SignedShiftSignal) {
        val t = ticker ?: return
        // ATOMIC: the shift is ending and the service may be destroyed right after this; the
        // relay must still run (it has no suspension point before the hand-off to the sink).
        lifecycleScope.launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
            val signed = runCatching { sign(t) }.getOrNull()
            if (signed != null) runCatching { sink.deliver(signed) }
        }
    }

    /** @param reason journaled end reason, or null when no shift was running. */
    private fun finishShift(reason: String?) {
        tickerJob?.cancel()
        tickerJob = null
        // The sink closes its uplink after a short grace period on its own scope, so a
        // BREAK/FREEZE relayed a moment ago still goes out.
        runCatching { sink.close() }
        hotSpec = null
        mainHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        if (reason != null) journal.onEnded(System.currentTimeMillis(), reason)
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
        // Close the planner log's session, re-plan from the night just observed (after every
        // line is on disk), then let the Foreman thread finish what is queued and end.
        if (::rhythm.isInitialized) rhythm.monitorStop()
        runCatching { foremanExecutor.execute { planner.requestRefresh() } }
        foremanExecutor.shutdown()
        // Keep a finished shift's outcome (broken reason / frozen) visible; anything else is cold.
        val last = snapshot.state
        repository.publish(
            if (last is ShiftState.Broken || last is ShiftState.Frozen) snapshot else ShiftSnapshot.IDLE,
        )
        super.onDestroy()
    }

    private fun publish() {
        repository.publish(snapshot)
        if (snapshot.state == ShiftState.Idle) return
        getSystemService<NotificationManager>()?.notify(ShiftNotificationFactory.SHIFT_NOTIFICATION_ID, currentNotification())
    }

    private fun promoteToForeground() {
        // The specialUse type exists from Android 14; on 12/13 no type is required.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ShiftNotificationFactory.SHIFT_NOTIFICATION_ID, currentNotification(), type)
    }

    private fun currentNotification(): Notification =
        notifications.build(snapshot.toNotificationState(), contentIntent())

    private fun contentIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun startAccelerometer() {
        val sm = getSystemService<SensorManager>() ?: return
        // Prefer the wake-up variant where it can deliver 50 Hz; otherwise the default sensor,
        // and the wake lock keeps samples flowing.
        val wakeUp = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        val plain = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val sensor = when (AccelerometerChoice.pick(wakeUp?.minDelay, plain?.minDelay)) {
            AccelerometerChoice.Pick.WAKE_UP -> wakeUp
            AccelerometerChoice.Pick.DEFAULT -> plain
            AccelerometerChoice.Pick.NONE -> null
        } ?: return // no accelerometer: posture never goes face-down, so the rig never goes hot
        val thread = HandlerThread("hd-posture").also { it.start() }
        sensorThread = thread
        sm.registerListener(
            accelListener, sensor, AccelerometerChoice.SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US, Handler(thread.looper),
        )
    }

    /** `AlarmManager.getNextAlarmClock()` (no permission needed), for the planner log. */
    private fun nextAlarmWallMillis(): Long? = getSystemService<AlarmManager>()?.nextAlarmClock?.triggerTime

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

        /** Let the sensor hub batch 1 s of samples between deliveries (one wake-up a second, not fifty). */
        private const val MAX_REPORT_LATENCY_US = 1_000_000

        /** How often the planner log's "still observing" mark moves. */
        private const val ALIVE_MARK_MILLIS = 5 * 60 * 1000L

        /** A heartbeat needs a posture sample at most this old. */
        private const val POSTURE_MAX_AGE_MILLIS = 5_000L

        /** Three ORE rounds; renewed every round while the shift runs. */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 3 * StubOreRoundSource.ORE_ROUND_MILLIS
    }
}
