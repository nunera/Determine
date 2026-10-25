package org.determine.android.runtime

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import org.determine.android.alerts.AndroidPhoneAlerts
import org.determine.android.audio.PhoneCueSink
import org.determine.android.camera.PhoneCameraSource
import org.determine.android.describe.FaceRedactor
import org.determine.app.runtime.EngineLoop
import org.determine.app.runtime.HostPlatform
import org.determine.app.runtime.HeadsetInfo
import org.determine.app.runtime.PlatformAlerts
import org.determine.app.runtime.PlatformCueSink
import org.determine.app.runtime.PlatformFrameSource
import org.determine.app.runtime.PlatformRedactor
import org.determine.core.CueCommand
import org.determine.core.hearing.HearingProfile

/** Android I/O: TTS/vibrator, Bluetooth-only cues, CameraX, MediaPipe face redaction, and a HandlerThread. */
class AndroidHostPlatform(context: Context) : HostPlatform {
    private val context = context.applicationContext

    /** Phone monotonic clock in µs (PROTOCOL.md "Clocks"); keeps counting in deep sleep. */
    override fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000

    override fun engineLoop(): EngineLoop = HandlerEngineLoop()

    override fun alerts(): PlatformAlerts = AndroidPhoneAlerts(context)

    override fun cueSink(profile: () -> HearingProfile): PlatformCueSink {
        val sink = PhoneCueSink(context, profile)
        return object : PlatformCueSink {
            override fun play(cmd: CueCommand, nowPhoneUs: Long): Int = sink.play(cmd, nowPhoneUs)
            override val error: String? get() = sink.stats().error
            override fun close() = sink.close()
            override fun headsetInfo(): HeadsetInfo {
                val stats = sink.stats()
                return HeadsetInfo(stats.headsetPresent, stats.headsetName, stats.routeReady, stats.error)
            }
        }
    }

    override fun phoneCamera(onFrame: (ByteArray, Int, Int, Long) -> Unit): PlatformFrameSource {
        val cam = PhoneCameraSource(context, onFrame)
        return object : PlatformFrameSource {
            override fun start() { cam.start() }
            override fun stop() = cam.stop()
            override val error: String? get() = cam.stats().error
        }
    }

    override fun redactor(): PlatformRedactor = FaceRedactor(context)
}

/** HandlerThread at audio priority; the tick is rescheduled at a fixed rate and skips ahead when late. */
private class HandlerEngineLoop : EngineLoop {
    private val thread = HandlerThread("determine-engine", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var ticking = true

    override fun post(block: () -> Unit) {
        handler.post(block)
    }

    override fun startTicking(periodMs: Long, tick: () -> Unit) {
        var next = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (!ticking) return
                tick()
                next += periodMs
                val now = SystemClock.uptimeMillis()
                if (next <= now) next = now + periodMs
                handler.postAtTime(this, next)
            }
        }
        handler.post(r)
    }

    override fun quit() {
        ticking = false
        thread.quitSafely()
    }
}
