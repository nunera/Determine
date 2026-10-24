package org.determine.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import org.determine.core.CueCommand
import org.determine.core.LocalCueSink
import org.determine.core.hearing.HearingProfile
import org.determine.core.wire.AckStatus
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-audio cue output (Arch §4.1 CueSink "phone audio to headset · phone vibration (non-directional)"), the
 * fallback when the glasses transducers are missing (Arch §11.3). It plays the cue board's own cue bank ([CueBank])
 * on a low-latency stereo [AudioTrack] routed to whatever the phone is using (wired or Bluetooth headset, else the
 * speaker), and the haptic patterns on the phone's single vibrator.
 *
 * Like the board it decides BAD_CUE / BUSY_DROPPED / PLAYED / PLAYED_CLAMPED itself, clamping each gain to
 * min(profile ceiling, −10 dBFS) independently of the engine (invariant 5), and muting audio in HAPTIC_ONLY mode.
 *
 * Threading: [play] runs on the engine thread inside the engine lock and only decides and hands the request to a
 * one-slot mailbox; a dedicated urgent-audio thread renders the PCM in 5 ms chunks, writes it, and fires the
 * vibration, so the engine never waits for audio. The interrupt flag replaces the mailbox content and the output
 * thread switches to it at the next chunk boundary, as the board's audio task does.
 *
 * Failure is loud: if the audio output cannot be opened or a write fails and the track cannot be rebuilt,
 * [play] returns [FAILED] (not an ACK status), the engine counts a missing ACK and escalates to UNAVAILABLE, and the
 * phone's own alarm path speaks "System off".
 */
class PhoneCueSink(
    context: Context,
    /** Current profile; read inside [play], i.e. under the engine lock. */
    private val profile: () -> HearingProfile,
) : LocalCueSink {
    data class Stats(val outputOk: Boolean, val sampleRate: Int, val played: Long, val dropped: Long, val writeErrors: Long, val error: String?)

    private class Request(val cueId: Int, val gainLDb: Int, val gainRDb: Int, val hapticPattern: Int, val hapticAmp: Int)

    private val vibrator: Vibrator? = (
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        ).takeIf { it.hasVibrator() }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val vibrationAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Native output rate keeps the track on the low-latency fast path; 48 kHz if the device does not say. */
    val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        .takeIf { it in 22_050..96_000 } ?: 48_000
    private val chunkFrames = sampleRate * CHUNK_MS / 1000

    private val mailbox = AtomicReference<Request?>(null)
    private val wake = Object()
    @Volatile private var rendering = false
    @Volatile private var hapticUntilUs = Long.MIN_VALUE
    @Volatile private var running = true
    @Volatile private var outputOk = false
    @Volatile private var error: String? = null
    @Volatile private var played = 0L
    @Volatile private var dropped = 0L
    @Volatile private var writeErrors = 0L

    @Volatile private var track: AudioTrack? = openTrack()

    private val thread = Thread({ outputLoop() }, "determine-cue-audio").apply {
        isDaemon = true
        start()
    }

    override fun play(cmd: CueCommand, nowPhoneUs: Long): Int {
        val p = profile()
        val busy = rendering || mailbox.get() != null || nowPhoneUs < hapticUntilUs
        val d = CueBank.decide(cmd, p.effectiveCeilingDb, p.mode, p.hapticMax, busy)
        if (!d.play) {
            if (d.status == AckStatus.BUSY_DROPPED) dropped++
            return d.status
        }
        // Non-directional phone vibrator: the stronger side's intensity (Arch §4.1).
        val amp = maxOf(d.hapticL, d.hapticR)
        val haptic = cmd.hapticPattern != 0 && amp > 0
        if (d.audible && !outputOk) return FAILED
        if (haptic && vibrator == null && !d.audible) return FAILED
        // A muted, haptic-free command (the engine's link probe) only proves the output path is open.
        if (!d.audible && !haptic) return if (outputOk) d.status else FAILED
        if (haptic) hapticUntilUs = nowPhoneUs + CueBank.hapticLengthMs(cmd.hapticPattern) * 1000L
        mailbox.set(Request(cmd.cueId, d.gainLDb, d.gainRDb, if (haptic) cmd.hapticPattern else 0, amp))
        synchronized(wake) { wake.notify() }
        played++
        return d.status
    }

    private fun openTrack(): AudioTrack? = try {
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
            outputOk = false
            null
        } else {
            outputOk = true
            error = null
            t
        }
    } catch (e: Exception) {
        Log.e(TAG, "cannot open cue audio output", e)
        error = e.message ?: e.javaClass.simpleName
        outputOk = false
        null
    }

    private fun outputLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(chunkFrames * 2)
        val silence = ShortArray(chunkFrames * 2)
        var player: CueBank.Player? = null
        var idleSinceMs = 0L
        var paused = true
        while (running) {
            val req = mailbox.getAndSet(null)
            if (req != null) {
                rendering = true
                if (req.hapticPattern != 0) vibrate(req.hapticPattern, req.hapticAmp)
                val cue = CueBank.cue(req.cueId)
                player = if (cue != null && (req.gainLDb != CueBank.GAIN_MUTE || req.gainRDb != CueBank.GAIN_MUTE)) {
                    CueBank.Player(cue, sampleRate, CueBank.dbToQ15(req.gainLDb), CueBank.dbToQ15(req.gainRDb))
                } else {
                    null
                }
            }
            val t = track
            val active = player
            if (active != null && t != null) {
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
                if (SystemClock.uptimeMillis() - idleSinceMs < IDLE_WARM_MS) {
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
            if (track == null && running) track = openTrack()
        }
    }

    /** Blocking write of one chunk; on failure (e.g. audio server died) rebuilds the track once. */
    private fun write(t: AudioTrack, pcm: ShortArray): Boolean {
        val r = t.write(pcm, 0, pcm.size)
        if (r == pcm.size) return true
        writeErrors++
        Log.w(TAG, "cue audio write failed ($r); reopening")
        t.release()
        track = openTrack()
        if (track == null) error = "audio write failed ($r)"
        return false
    }

    private fun vibrate(pattern: Int, amplitude: Int) {
        val v = vibrator ?: return
        val (timings, amps) = CueBank.vibrationWaveform(pattern, amplitude) ?: return
        try {
            val effect = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, -1)
            else VibrationEffect.createWaveform(timings, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, vibrationAttributes)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "vibration failed", e)
        }
    }

    fun stats(): Stats = Stats(outputOk, sampleRate, played, dropped, writeErrors, error)

    fun close() {
        running = false
        synchronized(wake) { wake.notifyAll() }
        thread.join(500)
        vibrator?.cancel()
        track?.release()
        track = null
    }

    companion object {
        private const val TAG = "PhoneCueSink"
        /** Returned instead of an ACK status when the output path is not usable; the engine treats it as a failure. */
        const val FAILED = -1
        private const val CHUNK_MS = 5
        private const val IDLE_WARM_MS = 2_000L
        private const val IDLE_WAIT_MS = 1_000L
    }
}
