package org.determine.desktop

import org.determine.app.audio.CueSinkCore
import org.determine.app.audio.PcmOutput
import org.determine.app.audio.PhoneSounds
import org.determine.app.runtime.EngineLoop
import org.determine.app.runtime.HostPlatform
import org.determine.app.runtime.PlatformAlerts
import org.determine.app.runtime.PlatformCueSink
import org.determine.app.runtime.PlatformFrameSource
import org.determine.app.runtime.PlatformRedactor
import org.determine.core.AssembledFrame
import org.determine.core.hearing.HearingProfile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Desktop pieces of the hazard loop. Same engine, UDP link, Describe client and cue bank as the phone; the
 * platform differences are explicit:
 *  - speech: `espeak-ng` when on PATH (the nix shell provides it), otherwise printed;
 *  - vibration: printed (`VIBRATE …` on stdout), there is no motor;
 *  - phone camera (Tier 0): not available;
 *  - face blur: not available, so Describe uploads are refused (invariant 4);
 *  - Java Sound uses the system device, possibly speakers; no Bluetooth headset is verified;
 *  - distance is the scalar forward ToF zone, not camera detections or an inferred side.
 */
class DesktopHostPlatform(private val log: (String) -> Unit = ::println) : HostPlatform {
    override fun nowUs(): Long = System.nanoTime() / 1000

    override fun engineLoop(): EngineLoop = ThreadEngineLoop()

    override fun alerts(): PlatformAlerts = DesktopAlerts(log)

    override fun cueSink(profile: () -> HearingProfile): PlatformCueSink =
        CueSinkCore(profile, JavaSoundOutput(), uptimeMs = { System.nanoTime() / 1_000_000 })

    override fun phoneCamera(onFrame: (ByteArray, Int, Int, Long) -> Unit): PlatformFrameSource? = null

    override fun redactor(): PlatformRedactor = NoRedactor
}

/** One thread: queued blocks and a fixed-rate tick that skips ahead (never bursts) when the thread is late. */
private class ThreadEngineLoop : EngineLoop {
    private val queue = LinkedBlockingQueue<() -> Unit>()
    @Volatile private var tick: (() -> Unit)? = null
    @Volatile private var periodNs = 0L
    @Volatile private var quitting = false
    private val thread = Thread(::run, "determine-engine").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
        start()
    }

    override fun post(block: () -> Unit) {
        queue.put(block)
    }

    override fun startTicking(periodMs: Long, tick: () -> Unit) {
        periodNs = periodMs * 1_000_000
        this.tick = tick
        queue.put {}
    }

    override fun quit() {
        quitting = true
        tick = null
        queue.put {}
    }

    private fun run() {
        var next = System.nanoTime()
        while (true) {
            val t = tick
            val waitNs = if (t == null) Long.MAX_VALUE else next - System.nanoTime()
            val block = if (waitNs <= 0) null else queue.poll(waitNs, TimeUnit.NANOSECONDS)
            if (block != null) {
                block()
                // quit() lets already-posted blocks run, then the thread ends.
                if (quitting && queue.isEmpty()) return
                continue
            }
            if (quitting) return
            val current = tick ?: continue
            current()
            next += periodNs
            val now = System.nanoTime()
            if (next <= now) next = now + periodNs
        }
    }
}

/** Java Sound stereo 16-bit line at 48 kHz (ALSA/PulseAudio/PipeWire on Linux). */
private class JavaSoundOutput : PcmOutput {
    override val sampleRate = 48_000
    private val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
    private var line: SourceDataLine? = null
    private var bytes = ByteArray(0)

    override fun open(): String? {
        val l = AudioSystem.getSourceDataLine(format)
        // ~20 ms buffer: small enough that a cue starts within one engine tick of the command.
        try {
            l.open(format, sampleRate / 50 * format.frameSize)
        } catch (e: Exception) {
            l.close()
            throw e
        }
        line = l
        return null
    }

    override fun play() { checkNotNull(line) { "audio line is not open" }.start() }

    override fun write(pcm: ShortArray): Boolean {
        val l = line ?: return false
        if (bytes.size != pcm.size * 2) bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[2 * i] = pcm[i].toByte()
            bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        return l.write(bytes, 0, bytes.size) == bytes.size
    }

    override fun pauseAndFlush() {
        line?.stop()
        line?.flush()
    }

    override fun release() {
        line?.close()
        line = null
    }
}

/**
 * Phone alerts on a desktop: the same rules as the Android TTS (spearcon rate for hazard words, normal rate for
 * system-off and descriptions, each utterance replaces the previous one) through `espeak-ng`; the same three-note
 * system-off tone through Java Sound.
 */
private class DesktopAlerts(private val log: (String) -> Unit) : PlatformAlerts {
    private val espeak: String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, "espeak-ng") }.firstOrNull { it.canExecute() }?.path
    @Volatile private var speech: Process? = null
    @Volatile private var describing = false

    init {
        if (espeak == null) log("SPEECH espeak-ng not on PATH: spoken words are printed only")
    }

    override fun speak(text: String) = say(text, PhoneSounds.SPEARCON_RATE)

    override fun vibrate(pattern: LongArray) = log("VIBRATE ms=${pattern.joinToString(",")}")

    override fun systemOff() {
        playOffTone()
        say(PhoneSounds.SYSTEM_OFF_TEXT, 1.0f)
    }

    override fun speakDescription(text: String) {
        say(text, 1.0f)
        describing = true
    }

    override fun stopDescription() {
        if (describing) {
            describing = false
            speech?.destroy()
        }
    }

    override fun say(text: String) = say(text, 1.0f)

    private fun say(text: String, rate: Float) {
        describing = false
        log("SPEAK \"$text\"")
        val exe = espeak ?: return
        speech?.destroy() // QUEUE_FLUSH: the newest utterance replaces the previous one
        speech = try {
            ProcessBuilder(exe, "-s", (BASE_WPM * rate).toInt().toString(), text)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: Exception) {
            log("SPEECH failed: ${e.message}")
            null
        }
    }

    private fun playOffTone() {
        Thread({
            try {
                val pcm = PhoneSounds.systemOffTone()
                val fmt = AudioFormat(PhoneSounds.OFF_TONE_RATE.toFloat(), 16, 1, true, false)
                val bytes = ByteArray(pcm.size * 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
                AudioSystem.getSourceDataLine(fmt).use { l ->
                    l.open(fmt)
                    l.start()
                    l.write(bytes, 0, bytes.size)
                    l.drain()
                }
            } catch (e: Exception) {
                log("AUDIO system-off tone failed: ${e.message}")
            }
        }, "determine-off-tone").apply { isDaemon = true }.start()
    }

    override fun close() {
        speech?.destroy()
    }

    private companion object {
        /** espeak-ng's default speed in words per minute; Android's rate 1.0. */
        const val BASE_WPM = 175
    }
}

/** No face detector on desktop, so nothing may be uploaded (invariant 4): every redaction fails. */
private object NoRedactor : PlatformRedactor {
    override val available = false
    override fun redact(frame: AssembledFrame): ByteArray? = null
    override fun close() {}
}
