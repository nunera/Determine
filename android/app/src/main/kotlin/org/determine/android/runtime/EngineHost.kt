package org.determine.android.runtime

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.determine.android.alerts.AndroidPhoneAlerts
import org.determine.android.audio.PhoneCueSink
import org.determine.android.camera.PhoneCameraSource
import org.determine.android.describe.DescribeEndpoint
import org.determine.android.describe.FaceRedactor
import org.determine.android.describe.HttpDescribeTransport
import org.determine.android.net.BoardAddressBook
import org.determine.android.net.UdpLink
import org.determine.core.Alert
import org.determine.core.BoardId
import org.determine.core.Diagnostics
import org.determine.core.Engine
import org.determine.core.EngineConfig
import org.determine.core.EngineListener
import org.determine.core.FrameSourceKind
import org.determine.core.SystemState
import org.determine.core.describe.DescribeController
import org.determine.core.hearing.CalBand
import org.determine.core.hearing.CalStep
import org.determine.core.hearing.CalibrationSession
import org.determine.core.hearing.HearingMode
import org.determine.core.hearing.HearingProfile
import org.determine.core.hearing.MIN_HAPTIC_MAX
import java.net.SocketException

/** Session options fixed for one engine run; changing any of them restarts the service's session. */
data class HostOptions(
    val spearcons: Boolean,
    val frameSource: FrameSourceKind,
    val describeEnabled: Boolean,
    /** Board haptic cap at start (1..255); changed live with [EngineHost.setHapticMax]. */
    val hapticMax: Int,
    /** Read on the Describe HTTP thread for every request (URL + key may change while running). */
    val describeEndpoint: () -> DescribeEndpoint?,
)

/**
 * Owns the phone's hazard loop (Arch §4, §7): the [Engine], the UDP link, the headset audio sink and a 10 ms tick.
 *
 * Output: every warning sound plays on the phone through the Bluetooth bone-conduction headset
 * ([PhoneCueSink], explicit failure when the route is missing) and the temple haptics go to the cue board in the
 * same cue. The single forward ToF zone is the only distance source; the camera frames only feed Describe.
 *
 * Threading: [Engine] is not thread-safe, so every engine call happens under [lock]. Two threads take it:
 *  - the UDP receive thread (packets go straight in, no hand-off latency);
 *  - the engine thread (10 ms tick, UI commands, calibration).
 * The UI thread never touches the engine directly: Outbox sends happen inside engine calls and Android
 * forbids network I/O on the main thread.
 */
class EngineHost(context: Context, private val options: HostOptions) {
    private val lock = Any()
    private val thread = HandlerThread("determine-engine", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    private val addressBook = BoardAddressBook()
    private val alerts = AndroidPhoneAlerts(context)
    @Volatile private var unattributed = 0L
    @Volatile private var bindError: String? = null
    @Volatile private var stopped = false

    private val udp = UdpLink(
        addressBook = addressBook,
        clockUs = ::nowUs,
        onPacket = { board, bytes, len, rxUs -> synchronized(lock) { engine.onPacket(board, bytes, len, rxUs) } },
        onUnattributed = { unattributed++ },
    )

    private val listener = object : EngineListener {
        override fun onState(state: SystemState, reason: String) {
            DetermineRuntime._state.value = StateInfo(state, reason)
            describeController?.onState(state, nowUs())
        }

        override fun onAlert(alert: Alert?) {
            DetermineRuntime._alert.value = alert
            // Arch §7.3: a MID/NEAR hazard cancels and silences any description.
            describeController?.onAlert(alert, nowUs())
        }

        override fun onDiagnostics(d: Diagnostics) {
            DetermineRuntime._diagnostics.value = d
        }
    }

    /** The only audio output: the Bluetooth bone-conduction headset. Reads the live profile under the engine lock. */
    private val headsetAudio = PhoneCueSink(context) { engine.profile }

    private val engine: Engine = Engine(
        // Judge screen refreshes ages at 4 Hz; the rest of the config is the core's Arch defaults.
        EngineConfig(
            initialProfile = HearingProfile.uncalibrated().copy(hapticMax = options.hapticMax.coerceIn(MIN_HAPTIC_MAX, 255)),
            diagnosticsIntervalUs = 250_000,
            spearcons = options.spearcons,
            frameSource = options.frameSource,
        ),
        udp,
        alerts,
        listener,
        headsetAudio,
    )

    /** Tier 0 FrameSource: the phone's own camera on a headband (Arch §11.4 software fallback). */
    private val phoneCamera: PhoneCameraSource? = if (options.frameSource == FrameSourceKind.PHONE_CAMERA) {
        PhoneCameraSource(context) { jpeg, w, h, capUs -> synchronized(lock) { engine.onPhoneFrame(jpeg, w, h, capUs) } }
    } else null

    private val redactor = FaceRedactor(context)
    private val describeTransport = HttpDescribeTransport(
        endpoint = options.describeEndpoint,
        onFinished = { result, roundTripMs -> DetermineRuntime._describe.value = DescribeInfo.from(result, roundTripMs) },
    )
    // Created last: the listener above may fire during Engine construction, before this exists (hence nullable).
    private val describeController: DescribeController? =
        DescribeController(engine, describeTransport, redactor, alerts).apply { enabled = options.describeEnabled }

    private var calibration: CalibrationSession? = null
    private var calibrationConfirms = 0
    private var pausedByUser = false
    private var nextTickUptimeMs = 0L
    private var nextPublishUs = Long.MIN_VALUE

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            val now = nowUs()
            synchronized(lock) {
                engine.tick(now)
                failCalibrationIfRouteLost()
                describeController?.tick(now)
            }
            if (now >= nextPublishUs) {
                nextPublishUs = now + PUBLISH_INTERVAL_US
                publishSlow()
            }
            // Fixed-rate schedule; if the thread fell behind, skip ahead instead of bursting ticks.
            nextTickUptimeMs += TICK_MS
            val nowUptime = SystemClock.uptimeMillis()
            if (nextTickUptimeMs <= nowUptime) nextTickUptimeMs = nowUptime + TICK_MS
            handler.postAtTime(this, nextTickUptimeMs)
        }
    }

    /**
     * "What's ahead?" (Arch §7.3): user-initiated only. Runs on the engine thread; the outcome is published so
     * the UI can announce a refusal (disabled, unavailable, no frame, busy, redaction failed).
     */
    fun describe() = onEngineThread {
        val c = describeController ?: return@onEngineThread
        val now = nowUs()
        DetermineRuntime._describeOutcome.value = DescribeOutcomeEvent(c.request(now), now)
    }

    fun start() {
        DetermineRuntime._state.value = StateInfo(engine.state, "starting")
        DetermineRuntime._profile.value = engine.profile
        try {
            udp.start()
        } catch (e: SocketException) {
            // Without the socket nothing reaches the engine: its liveness rules take it to UNAVAILABLE and
            // the phone announces system off (invariant 2). Also surface the concrete cause.
            bindError = e.message ?: "cannot bind UDP ${BoardAddressBook.PHONE_PORT}"
            Log.e(TAG, "UDP bind failed", e)
            alerts.speak("Network port unavailable")
        }
        publishSlow()
        phoneCamera?.start()
        nextTickUptimeMs = SystemClock.uptimeMillis()
        handler.post(tick)
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        udp.stop()
        phoneCamera?.stop()
        describeTransport.close()
        // Release on the engine thread after any in-flight tick, then let the thread end.
        handler.post {
            redactor.close()
            headsetAudio.close()
            alerts.close()
        }
        thread.quitSafely()
    }

    /** Phone battery critical → UNAVAILABLE (Arch §4.2). */
    fun setBatteryCritical(critical: Boolean) = onEngineThread { engine.setBatteryCritical(critical) }

    fun setPaused(paused: Boolean) = onEngineThread {
        pausedByUser = paused
        // A failed session stays visible until restart/cancel, but no longer owns the pause.
        if (calibration == null || calibration?.step == CalStep.FAILED) engine.setPaused(paused)
    }

    fun setSpearcons(enabled: Boolean) = onEngineThread { engine.setSpearcons(enabled) }

    /** Live mode switch (Arch §11.4 "one-ear mode"): same calibrated levels, new mode for the phone's audio. */
    fun setMode(mode: HearingMode) = onEngineThread {
        val p = engine.profile.copy(mode = mode)
        engine.setProfile(p)
        DetermineRuntime._profile.value = p
    }

    /**
     * Board haptic cap (PROFILE.haptic_max). Never below [MIN_HAPTIC_MAX]: the temples are the only failure
     * channel that survives phone loss (the board's watchdog), so the cap cannot be turned off.
     */
    fun setHapticMax(value: Int) = onEngineThread {
        val p = engine.profile.copy(hapticMax = value.coerceIn(MIN_HAPTIC_MAX, 255))
        engine.setProfile(p)
        DetermineRuntime._profile.value = p
    }

    /** Arch §9: obstacle cues stay off for the whole calibration; PINGs and liveness continue. */
    fun startCalibration() = onEngineThread {
        engine.setPaused(true)
        calibrationConfirms = 0
        calibration = CalibrationSession(
            ceilingDb = engine.profile.ceilingDb,
            sink = { cmd -> engine.sendCalibrationCue(cmd, nowUs()) },
        )
        failCalibrationIfRouteLost()
        publishCalibration()
    }

    fun cancelCalibration() = onEngineThread {
        calibration = null
        engine.setPaused(pausedByUser)
        DetermineRuntime._calibration.value = null
    }

    fun calibrate(action: CalibrationAction) = onEngineThread {
        val s = calibration ?: return@onEngineThread
        failCalibrationIfRouteLost()
        if (s.step == CalStep.FAILED) return@onEngineThread
        try {
            when (action) {
                is CalibrationAction.Fit -> s.setFit(action.deviceId, action.weakerEar)
                is CalibrationAction.Heard -> s.answerHeard(action.heard)
                is CalibrationAction.SensationLevel -> s.setSensationLevel(action.db)
                is CalibrationAction.NudgeBalance -> s.nudgeBalance(action.deltaDb)
                CalibrationAction.ConfirmBalance -> {
                    s.confirmBalance()
                    if (s.step != CalStep.FAILED) calibrationConfirms++
                }
                is CalibrationAction.NameSide -> s.answerSide(action.ear)
                CalibrationAction.Replay -> s.replay()
                CalibrationAction.HapticOnly -> s.chooseHapticOnly()
                CalibrationAction.Apply -> {
                    // The route is checked again at commit, even if the session finished while it was connected.
                    failCalibrationIfRouteLost()
                    val result = s.result() ?: return@onEngineThread
                    // Calibration fits the phone audio; the user's board haptic cap is a separate setting and stays.
                    val applied = result.copy(hapticMax = engine.profile.hapticMax)
                    engine.setProfile(applied)
                    DetermineRuntime._profile.value = applied
                    calibration = null
                    engine.setPaused(pausedByUser)
                    DetermineRuntime._calibration.value = null
                    return@onEngineThread
                }
            }
        } catch (e: IllegalStateException) {
            // A stale tap for a step the session already left (double tap): ignore, the view is republished.
            Log.w(TAG, "calibration action $action ignored", e)
        }
        if (s.step == CalStep.FAILED) engine.setPaused(pausedByUser)
        publishCalibration()
    }

    /** A lost route aborts even an idle or finished-unapplied session, not just the next played tone. */
    private fun failCalibrationIfRouteLost() {
        val s = calibration ?: return
        if (s.step == CalStep.FAILED || headsetAudio.stats().routeReady) return
        s.fail("Headset disconnected or audio route unavailable. Reconnect your headset and restart calibration.")
        engine.setPaused(pausedByUser)
        publishCalibration()
    }

    private fun publishCalibration() {
        val s = calibration ?: return
        DetermineRuntime._calibration.value = CalibrationView(
            step = s.step,
            ear = s.ear,
            bandHz = s.bandHz,
            bandIndex = s.bandHz?.let { hz -> CalBand.HZ.indexOf(hz) },
            sensationLevelDb = s.sensationLevelDb,
            balanceDb = s.balanceDb,
            balanceConfirms = calibrationConfirms,
            verifyTrials = s.verifyTrials,
            verifyCorrect = s.verifyCorrect,
            ceilingDb = s.ceilingDb,
            result = s.result(),
            failureReason = s.failureReason,
        )
    }

    /** Slower-changing views for the UI (forward range, headset route, link), 10 Hz. */
    private fun publishSlow() {
        val range = synchronized(lock) { engine.latestRange() }
        if (range != null) DetermineRuntime._range.value = range
        val audio = headsetAudio.stats()
        DetermineRuntime._headset.value = HeadsetInfo(audio.headsetPresent, audio.headsetName, audio.routeReady, audio.error)
        DetermineRuntime._link.value = LinkInfo(
            bound = bindError == null,
            bindError = bindError,
            camIp = addressBook.destination(BoardId.CAM)?.address?.hostAddress,
            cueIp = addressBook.destination(BoardId.CUE)?.address?.hostAddress,
            unattributedPackets = unattributed,
            sendErrors = udp.sendErrors,
        )
        DetermineRuntime._fallbacks.value = FallbackInfo(
            frameSource = options.frameSource,
            phoneCameraError = phoneCamera?.stats()?.error,
            redactorAvailable = redactor.available,
        )
    }

    private inline fun onEngineThread(crossinline block: () -> Unit) {
        handler.post { if (!stopped) synchronized(lock) { block() } }
    }

    companion object {
        private const val TAG = "EngineHost"
        const val TICK_MS = 10L
        private const val PUBLISH_INTERVAL_US = 100_000L

        /** Phone monotonic clock in µs (PROTOCOL.md "Clocks"); keeps counting in deep sleep. */
        fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000
    }
}
