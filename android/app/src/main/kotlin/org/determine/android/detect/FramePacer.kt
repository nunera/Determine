package org.determine.android.detect

/**
 * Decides which assembled JPEG goes to the detector. Rules (Arch §7.1 freshness gate, §6.3 budget):
 *  - at most one frame in flight; while the detector is busy, newer frames are skipped, never queued, so
 *    when it frees up it takes whatever is newest at that moment;
 *  - each frame at most once. Frames are identified by (frameId, captureBoardUs), not by id order, because
 *    the camera board restarts its u32 frame_id at 0 after a reboot;
 *  - never a frame already older than [maxAgeUs] (a stale frame can only produce a stale observation);
 *  - MediaPipe LIVE_STREAM needs strictly increasing timestamps, so submission timestamps are forced
 *    monotonic;
 *  - MediaPipe may drop an input without calling back, so an in-flight frame older than [stuckTimeoutUs]
 *    is abandoned and its late result (if any) rejected.
 *
 * Not thread-safe: the caller serialises access.
 */
class FramePacer(
    private val maxAgeUs: Long = 500_000,
    private val stuckTimeoutUs: Long = 500_000,
) {
    data class Submission(val frameId: Long, val captureBoardUs: Long, val capturePhoneUs: Long, val timestampMs: Long)

    private var lastFrameId = Long.MIN_VALUE
    private var lastCaptureBoardUs = Long.MIN_VALUE
    private var lastBusySkipFrameId = Long.MIN_VALUE
    private var lastBusySkipCaptureUs = Long.MIN_VALUE
    private var lastTimestampMs = Long.MIN_VALUE
    private var inflight: Submission? = null
    private var inflightSinceUs = 0L

    var submitted = 0L; private set
    var skippedBusy = 0L; private set
    var skippedStale = 0L; private set
    var abandoned = 0L; private set

    val busy: Boolean get() = inflight != null

    /**
     * Offer the newest assembled frame. Returns the submission to run, or null if it must be skipped.
     * [capturePhoneUs] is the frame's capture time mapped onto the phone clock.
     */
    fun offer(frameId: Long, captureBoardUs: Long, capturePhoneUs: Long, nowUs: Long): Submission? {
        if (frameId == lastFrameId && captureBoardUs == lastCaptureBoardUs) return null // already handled
        inflight?.let {
            if (nowUs - inflightSinceUs <= stuckTimeoutUs) {
                if (frameId != lastBusySkipFrameId || captureBoardUs != lastBusySkipCaptureUs) {
                    lastBusySkipFrameId = frameId
                    lastBusySkipCaptureUs = captureBoardUs
                    skippedBusy++
                }
                return null
            }
            abandoned++
            inflight = null
        }
        lastFrameId = frameId
        lastCaptureBoardUs = captureBoardUs
        if (nowUs - capturePhoneUs > maxAgeUs) {
            skippedStale++
            return null
        }
        val captureMs = capturePhoneUs / 1000
        val ts = if (lastTimestampMs == Long.MIN_VALUE) captureMs else maxOf(captureMs, lastTimestampMs + 1)
        val s = Submission(frameId, captureBoardUs, capturePhoneUs, ts)
        lastTimestampMs = ts
        inflight = s
        inflightSinceUs = nowUs
        submitted++
        return s
    }

    /**
     * A detector result arrived for the input submitted with [timestampMs]. Returns its submission, or null
     * if it is not the current in-flight frame (abandoned or unknown) and must be discarded.
     */
    fun onResult(timestampMs: Long): Submission? {
        val s = inflight ?: return null
        if (s.timestampMs != timestampMs) return null
        inflight = null
        return s
    }

    /** The in-flight submission failed (decode error, detector exception). */
    fun onError(timestampMs: Long) {
        if (inflight?.timestampMs == timestampMs) inflight = null
    }
}
