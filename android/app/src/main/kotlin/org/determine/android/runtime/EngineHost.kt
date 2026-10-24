package org.determine.android.runtime

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.determine.android.alerts.AndroidPhoneAlerts
import org.determine.android.detect.DetectorRunner
import org.determine.android.net.BoardAddressBook
import org.determine.android.net.UdpLink
import org.determine.core.Alert
import org.determine.core.BoardId
import org.determine.core.Diagnostics
import org.determine.core.Engine
import org.determine.core.EngineConfig
import org.determine.core.EngineListener
import org.determine.core.SystemState
import org.determine.core.hearing.CalBand
import org.determine.core.hearing.CalStep
import org.determine.core.hearing.CalibrationSession
import org.determine.core.hearing.HearingMode
import org.determine.android.audio.PhoneCueSink
import org.determine.android.camera.PhoneCameraSource
import org.determine.android.describe.DescribeEndpoint
import org.determine.android.describe.FaceRedactor
import org.determine.android.describe.HttpDescribeTransport
import org.determine.core.CueOutput
import org.determine.core.FrameSourceKind
import org.determine.core.describe.DescribeController
import java.net.SocketException

/** Session options fixed for one engine run; changing any of them restarts the service's session. */
data class HostOptions(
    val spearcons: Boolean,
    val frameSource: FrameSourceKind,
    val cueOutput: CueOutput,
    val describeEnabled: Boolean,
    /** Read on the Describe HTTP thread for every request (URL + key may change while running). */
    val describeEndpoint: () -> DescribeEndpoint?,
)

/**
 * Owns the phone's hazard loop (Arch §4, §7): the [Engine], the UDP link, the detector and a 10 ms tick.
 *
 * Threading: [Engine] is not thread-safe, so every engine call happens under [lock]. Three threads take it:
 *  - the UDP receive thread (packets go straight in, no hand-off latency);
 *  - the engine thread (10 ms tick, UI commands, calibration);
 *  - the MediaPipe result thread (detections).
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

    /** Arch §4.1 CueSink fallback: phone audio to a headset when the transducers are missing. */
    private val phoneCues: PhoneCueSink? =
        if (options.cueOutput == CueOutput.PHONE_AUDIO) PhoneCueSink(context) { engine.profile } else null

    private val engine: Engine = Engine(
        // Judge screen refreshes ages at 4 Hz; the rest of the config is the core's Arch defaults.
        EngineConfig(
            diagnosticsIntervalUs = 250_000,
            spearcons = options.spearcons,
            frameSource = options.frameSource,
            cueOutput = options.cueOutput,
        ),
        udp,
        alerts,
        listener,
        phoneCues,
    )

    /** Tier 0 FrameSource: the phone's own camera on a headband (Arch §11.4 software fallback). */
    private val phoneCamera: PhoneCameraSource? = if (options.frameSource == FrameSourceKind.PHONE_CAMERA) {
        PhoneCameraSource(context) { jpeg, w, h, capUs -> synchronized(lock) { engine.onPhoneFrame(jpeg, w, h, capUs) } }
    } else null

    private val detector = DetectorRunner(context, ::nowUs) { frameId, capturePhoneUs, detections, now ->
        synchronized(lock) { engine.onDetections(frameId, capturePhoneUs, detections, now) }
    }

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
            val frame = synchronized(lock) {
                engine.tick(now)
                describeController?.tick(now)
                engine.latestJpeg()
            }
            // Outside the lock: offering is cheap, and decode/inference never holds the engine.
            frame?.let { detector.offer(it, now) }
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
            detector.close()
            redactor.close()
            phoneCues?.close()
            alerts.close()
        }
        thread.quitSafely()
    }

    /** Phone battery critical → UNAVAILABLE (Arch §4.2). */
    fun setBatteryCritical(critical: Boolean) = onEngineThread { engine.setBatteryCritical(critical) }

    fun setPaused(paused: Boolean) = onEngineThread {
        pausedByUser = paused
        // Calibration keeps the engine paused; the user's choice applies when calibration ends.
        if (calibration == null) engine.setPaused(paused)
    }

    fun setSpearcons(enabled: Boolean) = onEngineThread { engine.setSpearcons(enabled) }

    /** Live mode switch (Arch §11.4 "one-ear mode"): same calibrated levels, new mode on both ends. */
    fun setMode(mode: HearingMode) = onEngineThread {
        val p = engine.profile.copy(mode = mode)
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
        publishCalibration()
    }

    fun cancelCalibration() = onEngineThread {
        calibration = null
        engine.setPaused(pausedByUser)
        DetermineRuntime._calibration.value = null
    }

    fun calibrate(action: CalibrationAction) = onEngineThread {
        val s = calibration ?: return@onEngineThread
        try {
            when (action) {
                is CalibrationAction.Fit -> s.setFit(action.deviceId, action.weakerEar)
                is CalibrationAction.Heard -> s.answerHeard(action.heard)
                is CalibrationAction.SensationLevel -> s.setSensationLevel(action.db)
                is CalibrationAction.NudgeBalance -> s.nudgeBalance(action.deltaDb)
                CalibrationAction.ConfirmBalance -> { s.confirmBalance(); calibrationConfirms++ }
                is CalibrationAction.NameSide -> s.answerSide(action.ear)
                CalibrationAction.Replay -> s.replay()
                CalibrationAction.HapticOnly -> s.chooseHapticOnly()
                CalibrationAction.Apply -> {
                    val result = s.result() ?: return@onEngineThread
                    engine.setProfile(result)
                    DetermineRuntime._profile.value = result
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
            result = if (s.step == CalStep.DONE) s.result() else null,
        )
    }

    /** Slower-changing views for the UI (range grid, detector, link), 10 Hz. */
    private fun publishSlow() {
        val range = synchronized(lock) { engine.latestRange() }
        if (range != null) DetermineRuntime._range.value = range
        DetermineRuntime._detector.value = detector.stats()
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
            cueOutput = options.cueOutput,
            phoneCameraError = phoneCamera?.stats()?.error,
            phoneAudioError = phoneCues?.stats()?.error,
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
