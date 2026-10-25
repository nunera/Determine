package org.determine.app.runtime

import org.determine.app.describe.DescribeEndpoint
import org.determine.app.describe.HttpDescribeTransport
import org.determine.app.net.BoardAddressBook
import org.determine.app.net.UdpLink
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
import java.util.logging.Level
import java.util.logging.Logger

/** Session options fixed for one engine run; changing any of them restarts the session. */
data class HostOptions(
    val spearcons: Boolean,
    val frameSource: FrameSourceKind,
    val describeEnabled: Boolean,
    /** Board haptic cap at start (1..255); changed live with [EngineHost.setHapticMax]. */
    val hapticMax: Int,
    /** Read on the Describe HTTP thread for every request (URL + key may change while running). */
    val describeEndpoint: () -> DescribeEndpoint?,
    /** UDP port the phone binds (PROTOCOL.md: 47000). */
    val port: Int = BoardAddressBook.PHONE_PORT,
)

/**
 * Owns the hazard loop (Arch §4, §7): the [Engine], UDP link, local audio sink and a 10 ms tick.
 * Platform audio, speech, camera, redaction and threads come from [HostPlatform].
 *
 * Warning sounds play through the platform audio sink (Android: Bluetooth bone-conduction headset with explicit
 * route failure; desktop: Java Sound, not a verified headset). Haptics go to the cue board in the same cue.
 * The single forward ToF zone is the only distance source; camera frames only feed Describe.
 *
 * Threading: [Engine] is not thread-safe, so every engine call happens under [lock]. Two threads take it:
 *  - the UDP receive thread (packets go straight in, no hand-off latency);
 *  - the engine thread (10 ms tick, UI commands, calibration).
 * The UI thread never touches the engine directly: Outbox sends happen inside engine calls and Android
 * forbids network I/O on the main thread.
 */
class EngineHost(private val platform: HostPlatform, private val options: HostOptions) {
    private val lock = Any()
    private val loop = platform.engineLoop()
    private val addressBook = BoardAddressBook()
    private val alerts = platform.alerts()
    @Volatile private var unattributed = 0L
    @Volatile private var bindError: String? = null
    @Volatile private var stopped = false

    private val udp = UdpLink(
        addressBook = addressBook,
        clockUs = platform::nowUs,
        onPacket = { board, bytes, len, rxUs -> synchronized(lock) { engine.onPacket(board, bytes, len, rxUs) } },
        onUnattributed = { unattributed++ },
    )

    private val listener = object : EngineListener {
        override fun onState(state: SystemState, reason: String) {
            DetermineRuntime._state.value = StateInfo(state, reason)
            describeController?.onState(state, platform.nowUs())
        }

        override fun onAlert(alert: Alert?) {
            DetermineRuntime._alert.value = alert
            // Arch §7.3: a MID/NEAR hazard cancels and silences any description.
            describeController?.onAlert(alert, platform.nowUs())
        }

        override fun onDiagnostics(d: Diagnostics) {
            DetermineRuntime._diagnostics.value = d
        }
    }

    /** Always local audio; the cue board is haptic-only in wire v2. */
    private val headsetAudio: PlatformCueSink = platform.cueSink { engine.profile }

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
    private val phoneCamera: PlatformFrameSource? = if (options.frameSource == FrameSourceKind.PHONE_CAMERA) {
        platform.phoneCamera { jpeg, w, h, capUs -> synchronized(lock) { engine.onPhoneFrame(jpeg, w, h, capUs) } }
    } else null

    private val redactor = platform.redactor()
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
    private var nextPublishUs = Long.MIN_VALUE

    private fun tick() {
        if (stopped) return
        val now = platform.nowUs()
        synchronized(lock) {
            engine.tick(now)
            failCalibrationIfRouteLost()
            describeController?.tick(now)
        }
        if (now >= nextPublishUs) {
            nextPublishUs = now + PUBLISH_INTERVAL_US
            publishSlow()
        }
    }

    /**
     * "What's ahead?" (Arch §7.3): user-initiated only. Runs on the engine thread; the outcome is published so
     * the UI can announce a refusal (disabled, unavailable, no frame, busy, redaction failed).
     */
    fun describe() = onEngineThread {
        val c = describeController ?: return@onEngineThread
        val now = platform.nowUs()
        DetermineRuntime._describeOutcome.value = DescribeOutcomeEvent(c.request(now), now)
    }

    fun start() {
        DetermineRuntime._state.value = StateInfo(engine.state, "starting")
        DetermineRuntime._profile.value = engine.profile
        try {
            udp.start(options.port)
        } catch (e: SocketException) {
            // Without the socket nothing reaches the engine: its liveness rules take it to UNAVAILABLE and
            // the phone announces system off (invariant 2). Also surface the concrete cause.
            bindError = e.message ?: "cannot bind UDP ${options.port}"
            log.log(Level.SEVERE, "UDP bind failed", e)
            alerts.speak("Network port unavailable")
        }
        publishSlow()
        phoneCamera?.start()
        loop.startTicking(TICK_MS, ::tick)
    }

    fun stop() {
        stopped = true
        udp.stop()
        phoneCamera?.stop()
        describeTransport.close()
        // Release on the engine thread after any in-flight tick, then let the thread end.
        loop.post {
            redactor.close()
            headsetAudio.close()
            alerts.close()
        }
        loop.quit()
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
            sink = { cmd -> engine.sendCalibrationCue(cmd, platform.nowUs()) },
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
            log.log(Level.WARNING, "calibration action $action ignored", e)
        }
        if (s.step == CalStep.FAILED) engine.setPaused(pausedByUser)
        publishCalibration()
    }

    /** A lost route aborts even an idle or finished-unapplied session, not just the next played tone. */
    private fun failCalibrationIfRouteLost() {
        val s = calibration ?: return
        if (s.step == CalStep.FAILED) return
        val route = headsetAudio.headsetInfo()
        if (route?.routeReady != false && headsetAudio.error == null) return
        s.fail(route?.error ?: headsetAudio.error
            ?: "Headset disconnected or audio route unavailable. Reconnect your headset and restart calibration.")
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
        DetermineRuntime._headset.value = headsetAudio.headsetInfo()
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
            phoneCameraError = when {
                options.frameSource != FrameSourceKind.PHONE_CAMERA -> null
                phoneCamera == null -> "no phone camera on this device"
                else -> phoneCamera.error
            },
            phoneAudioError = headsetAudio.error,
            redactorAvailable = redactor.available,
        )
    }

    private inline fun onEngineThread(crossinline block: () -> Unit) {
        loop.post { if (!stopped) synchronized(lock) { block() } }
    }

    companion object {
        private val log = Logger.getLogger("EngineHost")
        const val TICK_MS = 10L
        private const val PUBLISH_INTERVAL_US = 100_000L
    }
}
