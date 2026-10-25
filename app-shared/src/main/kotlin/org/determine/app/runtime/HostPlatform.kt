package org.determine.app.runtime

import org.determine.core.LocalCueSink
import org.determine.core.PhoneAlerts
import org.determine.core.describe.DescribeSpeech
import org.determine.core.describe.Redactor
import org.determine.core.hearing.HearingProfile

/**
 * Everything [EngineHost] needs from the device it runs on. The Android app and the desktop test app implement
 * it; the hazard loop, calibration, Describe wiring and published UI state are the same code on both.
 */
interface HostPlatform {
    /** Monotonic clock in µs (PROTOCOL.md "Clocks"). */
    fun nowUs(): Long

    /** The engine thread: every engine call other than packet receipt runs here. */
    fun engineLoop(): EngineLoop

    /** Phone-side speech, vibration and system-off output; also speaks Describe answers. */
    fun alerts(): PlatformAlerts

    /** Local warning audio (the wire v2 cue board has no audio output). */
    fun cueSink(profile: () -> HearingProfile): PlatformCueSink

    /** Tier 0 frame source (phone camera), or null where the device has none. */
    fun phoneCamera(onFrame: (jpeg: ByteArray, w: Int, h: Int, capturePhoneUs: Long) -> Unit): PlatformFrameSource?

    /** Face redaction before Describe uploads (invariant 4). */
    fun redactor(): PlatformRedactor
}

/** A single thread with a fixed-rate tick. [post] blocks run in order on that thread, between ticks. */
interface EngineLoop {
    fun post(block: () -> Unit)

    /** Start calling [tick] every [periodMs]; if the thread falls behind it skips ahead instead of bursting. */
    fun startTicking(periodMs: Long, tick: () -> Unit)

    /** Stop ticking; already-posted blocks still run, then the thread ends. */
    fun quit()
}

interface PlatformAlerts : PhoneAlerts, DescribeSpeech {
    fun close()
}

interface PlatformCueSink : LocalCueSink {
    val error: String?
    /** Actual Bluetooth route facts, or null on desktop where no physical headset is verified. */
    fun headsetInfo(): HeadsetInfo?
    fun close()
}

interface PlatformFrameSource {
    fun start()
    fun stop()
    val error: String?
}

interface PlatformRedactor : Redactor {
    val available: Boolean
    fun close()
}

