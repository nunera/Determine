package org.determine.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.determine.app.audio.CueBank
import org.determine.core.AUDIO_ROUTE_LOST
import org.determine.core.CueCommand
import org.determine.core.LocalCueSink
import org.determine.core.hearing.HearingProfile
import org.determine.core.wire.AckStatus
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone audio output for every warning sound (Arch §4.1 CueSink): the cue bank ([CueBank]) rendered on a
 * low-latency stereo [AudioTrack] bound to the connected Bluetooth bone-conduction headset (an A2DP or BLE audio
 * output such as the Shokz OpenRun). The cue board is haptic-only since protocol v2, so this is the only audio
 * path of the product, and the temple haptics are driven by the engine through the board, not here.
 *
 * There is no silent fallback. The sink only plays while a Bluetooth headset output exists AND the track is not
 * routed anywhere else:
 *  - no headset connected, or it disconnects → [play] returns [AUDIO_ROUTE_LOST] (the engine goes UNAVAILABLE with
 *    reason `headset_route_lost` and announces it); a cue in flight is cut, nothing is ever sent to the speaker;
 *  - the OS reroutes the track to another output (e.g. the speaker after a Bluetooth drop) → the chunk is not
 *    written, the track is rebuilt against the headset, and [play] reports the route as lost until that works;
 *  - the track cannot be opened or written → [FAILED], counted by the engine as a missing ACK.
 * A muted, haptic-free READY command (the engine's link probe) returns APPLIED only if the route is actually
 * ready, so reconnecting the headset recovers the system within one probe period.
 *
 * Like the board it replaces, it decides BAD_CUE / BUSY_DROPPED / APPLIED / APPLIED_CLAMPED itself, clamping each
 * gain to min(profile ceiling, −10 dBFS) independently of the engine (invariant 5).
 *
 * Threading: [play] runs on the engine thread inside the engine lock and only decides and hands the request to a
 * one-slot mailbox; a dedicated urgent-audio thread owns the track, renders PCM in 5 ms chunks and writes it, so
 * the engine never waits for audio. The interrupt flag replaces the mailbox content and the output thread switches
 * to it at the next chunk boundary.
 */
class PhoneCueSink(
    context: Context,
    /** Current profile; read inside [play], i.e. under the engine lock. */
    private val profile: () -> HearingProfile,
) : LocalCueSink {
    data class Stats(
        val headsetPresent: Boolean,
        val headsetName: String?,
        val routeReady: Boolean,
        val sampleRate: Int,
        val played: Long,
        val dropped: Long,
        val writeErrors: Long,
        val error: String?,
    )

    private class Request(val cueId: Int, val gainLDb: Int, val gainRDb: Int)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Native output rate keeps the track on the low-latency fast path; 48 kHz if the device does not say. */
    val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        .takeIf { it in 22_050..96_000 } ?: 48_000
    private val chunkFrames = sampleRate * CHUNK_MS / 1000

    private val mailbox = AtomicReference<Request?>(null)
    private val wake = Object()
    @Volatile private var rendering = false
    @Volatile private var running = true

    /** The connected Bluetooth headset output, or null. Written by the device callback and the constructor. */
    @Volatile private var headset: AudioDeviceInfo? = findHeadset()
    /** True only while the track is open, bound to [headset] and not diverted. Read by [play] under the engine lock. */
    @Volatile private var routeReady = false
    @Volatile private var error: String? = if (headset == null) NO_HEADSET else null
    @Volatile private var played = 0L
    @Volatile private var dropped = 0L
    @Volatile private var writeErrors = 0L

    // Owned by the output thread.
    private var track: AudioTrack? = null
    private var boundDeviceId = -1
    private var nextOpenAttemptMs = 0L
    private var paused = true

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
    }

    private val thread = Thread({ outputLoop() }, "determine-cue-audio").apply {
        isDaemon = true
        start()
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    override fun play(cmd: CueCommand, nowPhoneUs: Long): Int {
        val p = profile()
        val busy = rendering || mailbox.get() != null
        val d = CueBank.decide(cmd, p.effectiveCeilingDb, busy)
        if (!d.play) {
            if (d.status == AckStatus.BUSY_DROPPED) dropped++
            return d.status
        }
        // No headset route, no answer but "lost": never queue sound for whatever output the OS picks instead.
        if (headset == null || !routeReady) return AUDIO_ROUTE_LOST
        // A muted command (the engine's link probe) only proves the route is open.
        if (!d.audible) return d.status
        mailbox.set(Request(cmd.cueId, d.gainLDb, d.gainRDb))
        synchronized(wake) { wake.notify() }
        played++
        return d.status
    }

    private fun onDevicesChanged() {
        headset = findHeadset()
        synchronized(wake) { wake.notifyAll() }
    }

    private fun findHeadset(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isHeadset(it) }

    /** Reconciles the track with the headset: closes it when the headset is gone, rebuilds it when it changed. */
    private fun syncTrack() {
        val dev = headset
        val t = track
        if (dev == null) {
            if (t != null) { t.release(); track = null; paused = true }
            routeReady = false
            error = NO_HEADSET
            return
        }
        if (t != null && routeReady && boundDeviceId == dev.id) return
        val now = SystemClock.uptimeMillis()
        if (now < nextOpenAttemptMs) return
        nextOpenAttemptMs = now + OPEN_RETRY_MS
        t?.release()
        track = null
        paused = true
        track = openTrack(dev)
    }

    private fun openTrack(dev: AudioDeviceInfo): AudioTrack? = try {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minBytes, chunkFrames * 4 * 2))
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            error = "audio track not initialised"
            routeReady = false
            null
        } else if (!t.setPreferredDevice(dev)) {
            t.release()
            error = "cannot bind audio to the headset ${dev.productName}"
            routeReady = false
            null
        } else {
            boundDeviceId = dev.id
            error = null
            routeReady = true
            t
        }
    } catch (e: Exception) {
        Log.e(TAG, "cannot open headset audio output", e)
        error = e.message ?: e.javaClass.simpleName
        routeReady = false
        null
    }

    /** True if the track is (as far as Android reports) routed to something other than a Bluetooth headset. */
    private fun diverted(t: AudioTrack): Boolean {
        val rd = t.routedDevice ?: return false // not routed yet: cannot be proven elsewhere
        return !isHeadset(rd)
    }

    private fun outputLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(chunkFrames * 2)
        val silence = ShortArray(chunkFrames * 2)
        var player: CueBank.Player? = null
        var idleSinceMs = 0L
        while (running) {
            syncTrack()
            val req = mailbox.getAndSet(null)
            if (req != null) {
                rendering = true
                val cue = CueBank.cue(req.cueId)
                player = if (cue != null && (req.gainLDb != CueBank.GAIN_MUTE || req.gainRDb != CueBank.GAIN_MUTE)) {
                    CueBank.Player(cue, sampleRate, CueBank.dbToQ15(req.gainLDb), CueBank.dbToQ15(req.gainRDb))
                } else {
                    null
                }
            }
            val t = track
            val active = player
            if (active != null) {
                if (t == null || headset == null || diverted(t)) {
                    // The headset route vanished under a cue: cut it, never let it reach another output.
                    if (t != null) { t.pause(); t.flush(); paused = true }
                    routeReady = false
                    error = if (headset == null) NO_HEADSET else "audio was routed away from the headset"
                    player = null
                    rendering = false
                    continue
                }
                if (paused) { t.play(); paused = false }
                val n = active.render(buf, chunkFrames)
                if (n < chunkFrames) buf.fill(0, 2 * n, buf.size)
                if (!write(t, buf)) { player = null; paused = true; rendering = false; continue }
                if (active.done) {
                    player = null
                    rendering = false
                    idleSinceMs = SystemClock.uptimeMillis()
                }
                continue
            }
            rendering = false
            if (t != null && !paused) {
                // Keep the stream warm with silence between repetitions (FAR cues repeat every second), then pause.
                if (SystemClock.uptimeMillis() - idleSinceMs < IDLE_WARM_MS && headset != null && !diverted(t)) {
                    if (!write(t, silence)) paused = true
                    continue
                }
                t.pause()
                t.flush()
                paused = true
            }
            synchronized(wake) {
                if (running && mailbox.get() == null) {
                    try { wake.wait(IDLE_WAIT_MS) } catch (_: InterruptedException) { return }
                }
            }
        }
    }

    /** Blocking write of one chunk; on failure (e.g. audio server died) drops the track so it is rebuilt. */
    private fun write(t: AudioTrack, pcm: ShortArray): Boolean {
        val r = t.write(pcm, 0, pcm.size)
        if (r == pcm.size) return true
        writeErrors++
        Log.w(TAG, "cue audio write failed ($r); reopening")
        t.release()
        track = null
        routeReady = false
        error = "audio write failed ($r)"
        nextOpenAttemptMs = 0
        return false
    }

    fun stats(): Stats {
        val dev = headset
        return Stats(dev != null, dev?.productName?.toString(), routeReady && dev != null, sampleRate, played, dropped, writeErrors, error)
    }

    fun close() {
        running = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        synchronized(wake) { wake.notifyAll() }
        thread.join(500)
        track?.release()
        track = null
    }

    companion object {
        private const val TAG = "PhoneCueSink"
        const val NO_HEADSET = "no Bluetooth headset connected"

        /** Returned for a write/open failure that is not a route loss; the engine counts it as a missing ACK. */
        const val FAILED = -1
        private const val CHUNK_MS = 5
        private const val IDLE_WARM_MS = 2_000L
        private const val IDLE_WAIT_MS = 1_000L
        private const val OPEN_RETRY_MS = 500L

        /** A Bluetooth output that can carry the warning sounds: A2DP, or LE Audio headset on Android 12+. */
        fun isHeadset(d: AudioDeviceInfo): Boolean =
            d.isSink && isHeadsetType(d.type)

        fun isHeadsetType(type: Int): Boolean =
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
