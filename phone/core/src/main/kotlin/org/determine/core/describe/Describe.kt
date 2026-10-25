package org.determine.core.describe

import org.determine.core.Alert
import org.determine.core.AssembledFrame
import org.determine.core.Band
import org.determine.core.Engine
import org.determine.core.SystemState
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/** One upload to describe-api (cloud/API.md §1). [jpeg] is already face-redacted. */
data class DescribeRequest(val requestId: String, val jpeg: ByteArray, val width: Int, val height: Int) {
    override fun equals(other: Any?): Boolean =
        other is DescribeRequest && requestId == other.requestId && width == other.width && height == other.height &&
            jpeg.contentEquals(other.jpeg)

    override fun hashCode(): Int = (requestId.hashCode() * 31 + width) * 31 + height + jpeg.contentHashCode()

    override fun toString(): String = "DescribeRequest(requestId=$requestId, jpeg=${jpeg.size} B, width=$width, height=$height)"
}

sealed class DescribeResult {
    abstract val requestId: String

    /** HTTP 200 (cloud/API.md §1): filtered text plus where it ran. */
    data class Ok(
        override val requestId: String,
        val text: String,
        val backend: String,
        val silicon: String,
        val inferenceMs: Long?,
        val totalMs: Long?,
    ) : DescribeResult()

    /** Any error: HTTP error code/body, network failure, cancellation by the transport. */
    data class Failed(override val requestId: String, val reason: String, val retryable: Boolean) : DescribeResult()
}

/** The phone → describe-api client. Asynchronous: [send] must not block; [onResult] may run on any thread. */
interface DescribeTransport {
    fun send(req: DescribeRequest, onResult: (DescribeResult) -> Unit)
    fun cancel(requestId: String)
}

/** Blurs faces and sensitive detail. Returns the redacted JPEG, or null when it could not redact → nothing is uploaded. */
fun interface Redactor {
    fun redact(frame: AssembledFrame): ByteArray?
}

/** Spoken output for Describe. [stopDescription] must stop only description speech, never hazard speech. */
interface DescribeSpeech {
    fun speakDescription(text: String)
    fun stopDescription()
    fun say(text: String)
}

enum class DescribeState { IDLE, REDACTING, WAITING, SPEAKING }

enum class DescribeOutcome { STARTED, DISABLED, SYSTEM_UNAVAILABLE, NO_FRESH_FRAME, BUSY, REDACTION_FAILED }

data class DescribeConfig(
    /** cloud/API.md §1: phone timeout 8 s end to end. */
    val timeoutUs: Long = 8_000_000,
    /** Only a frame captured at most this long ago is described (Arch §4.2 freshness bound). */
    val maxFrameAgeUs: Long = 500_000,
)

/**
 * The optional Describe loop (Arch §7.3), outside the hazard loop: opt-in ([enabled]), user-initiated only
 * ([request] on long press / button), interrupted by any MID or NEAR hazard, unavailable while the system is
 * UNAVAILABLE, never reassuring.
 *
 * Not thread-safe: call every method under the same lock as the [Engine]. Transport callbacks may arrive on any thread;
 * the callback handed to [DescribeTransport.send] only enqueues, and [tick] delivers queued results through [onResult].
 * Hosts call [tick] alongside [Engine.tick]; they may also call [onResult] themselves (duplicates are ignored).
 *
 * SPEAKING has no completion callback from [DescribeSpeech]; it ends at a generous estimate of the utterance length,
 * on the next request, or on an interrupt. Every MID/NEAR alert calls [DescribeSpeech.stopDescription] whatever the
 * state, so a description never talks over an urgent hazard even if speech outlasts the estimate.
 */
class DescribeController(
    private val engine: Engine,
    private val transport: DescribeTransport,
    private val redactor: Redactor,
    private val speech: DescribeSpeech,
    private val config: DescribeConfig = DescribeConfig(),
) {
    /** User opt-in to cloud description; off by default. Turning it off cancels anything in progress. */
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value && state != DescribeState.IDLE) { interrupt(); speech.stopDescription() }
        }

    var state: DescribeState = DescribeState.IDLE
        private set

    private var currentId: String? = null
    private var startedUs = 0L
    private var urgentAlert = false
    private var speakingUntilUs = 0L
    private val inbox = ConcurrentLinkedQueue<DescribeResult>()

    // request_id: 8–64 chars of [A-Za-z0-9_-] (cloud/API.md §1), unique per controller and across restarts.
    private val session = java.lang.Long.toHexString(Random.Default.nextLong() and Long.MAX_VALUE).padStart(16, '0')
    private var counter = 0L

    /** "What's ahead?" Checks, in order: opt-in, system state, busy, fresh frame, redaction; then uploads. */
    fun request(nowPhoneUs: Long): DescribeOutcome {
        if (!enabled) return DescribeOutcome.DISABLED
        if (engine.state == SystemState.UNAVAILABLE) return DescribeOutcome.SYSTEM_UNAVAILABLE
        if (state == DescribeState.REDACTING || state == DescribeState.WAITING) return DescribeOutcome.BUSY
        val frame = engine.latestJpeg()
        if (frame == null || nowPhoneUs - frame.capturePhoneUs > config.maxFrameAgeUs ||
            frame.capturePhoneUs > nowPhoneUs
        ) {
            return DescribeOutcome.NO_FRESH_FRAME
        }
        // A new question replaces the previous answer.
        if (state == DescribeState.SPEAKING) speech.stopDescription()
        state = DescribeState.REDACTING
        // Invariant: upload only after redaction succeeded. A throwing redactor is a failed redaction.
        val redacted = try { redactor.redact(frame) } catch (_: RuntimeException) { null }
        if (redacted == null || redacted.isEmpty()) {
            state = DescribeState.IDLE
            speech.say(UNAVAILABLE_TEXT)
            return DescribeOutcome.REDACTION_FAILED
        }
        val id = "dsc-$session-${counter++}"
        currentId = id
        startedUs = nowPhoneUs
        state = DescribeState.WAITING
        transport.send(DescribeRequest(id, redacted, frame.w, frame.h)) { inbox.add(it) }
        return DescribeOutcome.STARTED
    }

    /** A transport result, on the engine thread/lock. Results for any request other than the current one are ignored. */
    fun onResult(result: DescribeResult, nowPhoneUs: Long) {
        if (state != DescribeState.WAITING || result.requestId != currentId) return // late, cancelled or duplicate
        currentId = null
        when (result) {
            is DescribeResult.Ok -> {
                val text = sanitize(result.text)
                when {
                    // A hazard that is MID/NEAR right now owns the audio (one that started since the request already
                    // cancelled it): drop the answer silently, the hazard cue is what the wearer must hear.
                    urgentAlert -> state = DescribeState.IDLE
                    text == null -> { state = DescribeState.IDLE; speech.say(UNAVAILABLE_TEXT) }
                    else -> {
                        state = DescribeState.SPEAKING
                        speakingUntilUs = nowPhoneUs + SPEECH_BASE_US + text.length * SPEECH_US_PER_CHAR
                        speech.speakDescription(text)
                    }
                }
            }
            is DescribeResult.Failed -> { state = DescribeState.IDLE; speech.say(UNAVAILABLE_TEXT) }
        }
    }

    /** Every alert change from [org.determine.core.EngineListener.onAlert]. MID/NEAR interrupt; FAR does not. */
    fun onAlert(alert: Alert?, nowPhoneUs: Long) {
        val urgent = alert != null && (alert.band == Band.MID || alert.band == Band.NEAR)
        urgentAlert = urgent
        if (urgent) {
            speech.stopDescription()
            interrupt()
        }
    }

    /** Every state change from [org.determine.core.EngineListener.onState]. UNAVAILABLE cancels. */
    fun onState(state: SystemState, nowPhoneUs: Long) {
        if (state == SystemState.UNAVAILABLE) { interrupt(); speech.stopDescription() }
    }

    /** Delivers queued transport results, then enforces the timeout. Call regularly (with [Engine.tick]). */
    fun tick(nowPhoneUs: Long) {
        while (true) onResult(inbox.poll() ?: break, nowPhoneUs)
        if (state == DescribeState.SPEAKING && nowPhoneUs >= speakingUntilUs) state = DescribeState.IDLE
        if (state == DescribeState.WAITING && nowPhoneUs - startedUs >= config.timeoutUs) {
            currentId?.let { transport.cancel(it) }
            currentId = null
            state = DescribeState.IDLE
            speech.say(UNAVAILABLE_TEXT)
        }
    }

    /** Silent cancel of the in-flight request; the hazard (or the system-off cue) takes the audio. */
    private fun interrupt() {
        currentId?.let { transport.cancel(it) }
        currentId = null
        state = DescribeState.IDLE
    }

    companion object {
        const val UNAVAILABLE_TEXT = "Description unavailable."

        // TTS at a slow user rate (~12 chars/s) plus engine start-up: SPEAKING never ends before the speech does.
        private const val SPEECH_BASE_US = 1_500_000L
        private const val SPEECH_US_PER_CHAR = 80_000L

        /**
         * Defence in depth for invariant 1 (cloud/API.md §3 step 2 runs in the API too): drops every sentence that
         * asserts safety or absence. Returns the remaining text, or null when nothing is left.
         */
        fun sanitize(text: String): String? {
            val kept = text.trim().split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() && !REASSURING.containsMatchIn(it) }
            return kept.joinToString(" ").ifEmpty { null }
        }

        private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

        // cloud/API.md §3 list, plus the obvious inflections ("clearly", "safely", "no obstacle").
        private val REASSURING = Regex(
            "\\b(all clear|clear|clearly|safe|safely|nothing ahead|no obstacles?|empty path|free of obstacles)\\b",
            setOf(RegexOption.IGNORE_CASE),
        )
    }
}
