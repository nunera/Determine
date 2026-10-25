package org.determine.app.audio

import org.determine.app.runtime.PlatformCueSink
import org.determine.app.runtime.HeadsetInfo
import org.determine.core.CueCommand
import org.determine.core.hearing.HearingProfile
import org.determine.core.wire.AckStatus
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/** A stereo 16-bit PCM stream (desktop Java Sound). Writes happen only on the audio thread. */
interface PcmOutput {
    val sampleRate: Int

    /** Open (or reopen) the stream; returns null on success or the error text. */
    fun open(): String?
    fun play()

    /** Blocking write of interleaved L/R samples; false if the stream failed (it is then reopened). */
    fun write(pcm: ShortArray): Boolean
    fun pauseAndFlush()
    fun release()
}

/**
 * Local PCM audio output: [CueBank.decide] applies the gain ceiling and busy rule; a dedicated audio thread
 * renders [CHUNK_MS] chunks so [play] never blocks the engine. Haptics belong exclusively to the v2 cue board.
 *
 * [play] returns an ACK status, or [FAILED] when the actual output path is unusable. Opening a Java Sound line
 * does not verify a physical Bluetooth headset, so [headsetInfo] is always null.
 */
class CueSinkCore(
    /** Current profile; read inside [play], i.e. under the engine lock. */
    private val profile: () -> HearingProfile,
    private val out: PcmOutput,
    private val uptimeMs: () -> Long,
) : PlatformCueSink {
    private class Request(val cueId: Int, val gainLDb: Int, val gainRDb: Int)

    private val chunkFrames = out.sampleRate * CHUNK_MS / 1000
    private val mailbox = AtomicReference<Request?>(null)
    private val wake = Object()
    @Volatile private var rendering = false
    @Volatile private var running = true
    @Volatile private var outputOk = false
    @Volatile private var lastError: String? = null
    @Volatile var played = 0L; private set
    @Volatile var dropped = 0L; private set
    @Volatile var writeErrors = 0L; private set

    init {
        reopen()
    }

    private val thread = Thread(::outputLoop, "determine-cue-audio").apply {
        isDaemon = true
        start()
    }

    override val error: String? get() = lastError
    override fun headsetInfo(): HeadsetInfo? = null

    override fun play(cmd: CueCommand, nowPhoneUs: Long): Int {
        val p = profile()
        val busy = rendering || mailbox.get() != null
        val d = CueBank.decide(cmd, p.effectiveCeilingDb, busy)
        if (!d.play) {
            if (d.status == AckStatus.BUSY_DROPPED) dropped++
            return d.status
        }
        // Even a muted link probe succeeds only when the real PCM line opened successfully.
        if (!running || !outputOk) return FAILED
        if (!d.audible) return d.status
        mailbox.set(Request(cmd.cueId, d.gainLDb, d.gainRDb))
        synchronized(wake) { wake.notify() }
        played++
        return d.status
    }

    private fun reopen() {
        lastError = try {
            out.open()
        } catch (e: Exception) {
            log.log(Level.SEVERE, "cannot open cue audio output", e)
            e.message ?: e.javaClass.simpleName
        }
        outputOk = lastError == null
    }

    private fun outputLoop() {
        val buf = ShortArray(chunkFrames * 2)
        val silence = ShortArray(chunkFrames * 2)
        var player: CueBank.Player? = null
        var idleSinceMs = 0L
        var paused = true
        while (running) {
            val req = mailbox.getAndSet(null)
            if (req != null) {
                rendering = true
                val cue = CueBank.cue(req.cueId)
                player = if (cue != null && (req.gainLDb != CueBank.GAIN_MUTE || req.gainRDb != CueBank.GAIN_MUTE)) {
                    CueBank.Player(cue, out.sampleRate, CueBank.dbToQ15(req.gainLDb), CueBank.dbToQ15(req.gainRDb))
                } else {
                    null
                }
            }
            val active = player
            if (active != null && outputOk) {
                if (paused) {
                    try {
                        out.play()
                        paused = false
                    } catch (e: Exception) {
                        outputFailed(e.message ?: e.javaClass.simpleName)
                        player = null
                        rendering = false
                        continue
                    }
                }
                val n = active.render(buf, chunkFrames)
                if (n < chunkFrames) buf.fill(0, 2 * n, buf.size)
                if (!write(buf)) { player = null; paused = true; rendering = false; continue }
                if (active.done) {
                    player = null
                    rendering = false
                    idleSinceMs = uptimeMs()
                }
                continue
            }
            player = null
            rendering = false
            if (outputOk && !paused) {
                // Keep the stream warm with silence between repetitions (FAR cues repeat every second), then pause.
                if (uptimeMs() - idleSinceMs < IDLE_WARM_MS) {
                    if (!write(silence)) paused = true
                    continue
                }
                try {
                    out.pauseAndFlush()
                } catch (e: Exception) {
                    outputFailed(e.message ?: e.javaClass.simpleName)
                }
                paused = true
            }
            synchronized(wake) {
                if (running && mailbox.get() == null) {
                    try { wake.wait(IDLE_WAIT_MS) } catch (_: InterruptedException) { return }
                }
            }
            if (!outputOk && running) reopen()
        }
    }

    /** An output failure remains visible until a later successful reopen. Never claim the failed chunk played. */
    private fun write(pcm: ShortArray): Boolean {
        return try {
            if (out.write(pcm)) true else {
                outputFailed("audio write failed")
                false
            }
        } catch (e: Exception) {
            outputFailed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun outputFailed(reason: String) {
        outputOk = false
        lastError = reason
        writeErrors++
        log.warning("cue audio output failed: $reason")
        out.release()
    }

    override fun close() {
        running = false
        synchronized(wake) { wake.notifyAll() }
        thread.join(500)
        out.release()
    }

    companion object {
        private val log: Logger = Logger.getLogger("CueSink")
        /** Returned instead of an ACK status when the output path is not usable; the engine treats it as a failure. */
        const val FAILED = -1
        private const val CHUNK_MS = 5
        private const val IDLE_WARM_MS = 2_000L
        private const val IDLE_WAIT_MS = 1_000L
    }
}
