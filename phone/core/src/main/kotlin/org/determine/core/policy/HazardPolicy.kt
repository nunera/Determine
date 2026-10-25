package org.determine.core.policy

import org.determine.core.Alert
import org.determine.core.Band
import org.determine.core.Tuning
import org.determine.core.range.ForwardRange

data class PolicyConfig(
    /** Rate limit: at most one onset (silence → warning) per interval. */
    val minOnsetIntervalUs: Long = 700_000,
)

/**
 * Arch §7.2 hazard policy, forward-zone edition. The distance comes only from the forward ToF measurement —
 * never from camera detections, and no left/right is ever inferred (the sensor cannot lateralize):
 *  - band(d): NEAR < nearMm, MID < midMm, FAR ≤ farMm, else NONE (no report), thresholds from [Tuning];
 *  - time-based smoothing on the board time of the RANGE samples: a band (or a more urgent one) is entered once
 *    the readings have consistently indicated it for [Tuning.bandEnterMs]; leaving it (towards less urgent, or to
 *    nothing to report from VALID-beyond-far / NO_TARGET) takes [Tuning.bandExitMs] of consistent evidence AND a
 *    distance beyond the band's upper bound by [Tuning.bandHysteresisMm];
 *  - rate limit: ≤ 1 onset per [PolicyConfig.minOnsetIntervalUs];
 *  - head scanning holds FAR cues only; NEAR/MID always pass;
 *  - EXPERIMENTAL approach escalation ([Tuning.approachEnabled]): a fast closing speed with a short
 *    time-to-contact raises the reported band by one (NONE within farMm → FAR);
 *  - never CLEAR: the output is an alert or null (= nothing to report). An UNKNOWN sample means the measurement
 *    no longer exists: the active alert is dropped at once (no stale band keeps repeating) and nothing is
 *    claimed in its place — not far, not clear. When VALID readings resume it re-enters through the normal
 *    enter/onset rules.
 */
class HazardPolicy(tuning: Tuning = Tuning(), val config: PolicyConfig = PolicyConfig()) {
    var tuning: Tuning = tuning
        set(value) { field = value; if (!value.approachEnabled) approachCondition = false }

    /** Smoothed band (before approach escalation, scan hold and onset gating). */
    private var active = Band.NONE
    private var runDirection = 0 // +1 = readings indicate a more urgent band, −1 = less urgent, 0 = none
    private var runBand = Band.NONE
    private var runStartUs = 0L
    private var lastSampleUs = Long.MIN_VALUE
    private var lastMm: Int? = null

    private var shown = Band.NONE
    private var lastOnsetUs = Long.MIN_VALUE / 2
    private var onsetPending = false

    private val samples = ArrayDeque<Pair<Long, Int>>()
    private var closingSpeed: Double? = null
    private var approachCondition = false

    /** Least-squares closing speed (positive = getting closer) over the approach window; null with < 3 VALID samples. */
    val closingSpeedMmPerS: Double? get() = closingSpeed

    /** True while approach escalation is enabled and the closing speed / time-to-contact criteria hold. */
    val approachActive: Boolean get() = tuning.approachEnabled && approachCondition

    fun band(mm: Int): Band = when {
        mm < tuning.nearMm -> Band.NEAR
        mm < tuning.midMm -> Band.MID
        mm <= tuning.farMm -> Band.FAR
        else -> Band.NONE
    }

    private fun upperMm(b: Band): Int = when (b) {
        Band.NEAR -> tuning.nearMm
        Band.MID -> tuning.midMm
        Band.FAR -> tuning.farMm
        Band.NONE -> Int.MAX_VALUE
    }

    private fun lessUrgent(b: Band): Band = Band.entries[b.ordinal - 1]

    /**
     * One sample (a fresh forward ToF measurement) taken at [tUs] (board time, microseconds).
     * @return the alert to present now, or null for nothing to report.
     */
    fun step(reading: ForwardRange, scanning: Boolean, tUs: Long): Alert? {
        if (tUs < lastSampleUs) dropMeasurement() // the board clock restarted: nothing from before applies
        lastSampleUs = tUs
        if (reading == ForwardRange.Unknown) {
            // Unknown is never clear and never a distance: stop presenting the last band, say nothing instead.
            dropMeasurement()
            shown = Band.NONE
            return null
        }
        val mm = (reading as? ForwardRange.Target)?.mm
        lastMm = mm
        trackSpeed(mm, tUs)
        advance(targetBand(mm), tUs)
        return present(scanning, tUs)
    }

    /** The band the sample indicates given the active one: moving out of a band must clear its hysteresis margin. */
    private fun targetBand(mm: Int?): Band {
        val raw = if (mm == null) Band.NONE else band(mm)
        if (raw >= active) return raw
        var t = active
        while (t > raw && (mm == null || mm > upperMm(t) + tuning.bandHysteresisMm)) t = lessUrgent(t)
        return t
    }

    /** Run-based smoothing: a band change needs consistent evidence in one direction for enter/exit time. */
    private fun advance(target: Band, tUs: Long) {
        if (target == active) { runDirection = 0; return }
        val dir = if (target > active) 1 else -1
        if (dir != runDirection) { runDirection = dir; runBand = target; runStartUs = tUs }
        else runBand = if (dir > 0) minOf(runBand, target) else maxOf(runBand, target)
        val needMs = if (dir > 0) tuning.bandEnterMs else tuning.bandExitMs
        if (tUs - runStartUs >= needMs * 1000L) { active = runBand; runDirection = 0 }
    }

    private fun trackSpeed(mm: Int?, tUs: Long) {
        if (mm == null) { samples.clear(); closingSpeed = null; approachCondition = false; return }
        samples.addLast(tUs to mm)
        val windowUs = tuning.approachWindowMs * 1000L
        while (samples.isNotEmpty() && tUs - samples.first().first > windowUs) samples.removeFirst()
        closingSpeed = leastSquaresClosing()
        val v = closingSpeed
        approachCondition = v != null && v >= tuning.approachMinSpeedMmPerS && mm * 1000.0 / v < tuning.approachTtcMs
    }

    private fun leastSquaresClosing(): Double? {
        val n = samples.size
        if (n < 3) return null
        val t0 = samples.first().first
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for ((t, mm) in samples) {
            val x = (t - t0) / 1e6 // seconds
            sx += x; sy += mm; sxx += x * x; sxy += x * mm
        }
        val den = n * sxx - sx * sx
        if (den <= 0.0) return null
        return -(n * sxy - sx * sy) / den
    }

    /** Approach escalation, scan hold and onset gating: what the wearer is actually told. */
    private fun present(scanning: Boolean, tUs: Long): Alert? {
        var b = active
        if (approachActive) {
            b = when (b) {
                Band.NONE -> if ((lastMm ?: Int.MAX_VALUE) <= tuning.farMm) Band.FAR else Band.NONE
                Band.FAR -> Band.MID
                Band.MID, Band.NEAR -> Band.NEAR
            }
        }
        if (scanning && b == Band.FAR) b = Band.NONE
        if (b != Band.NONE && shown == Band.NONE) {
            if (tUs - lastOnsetUs < config.minOnsetIntervalUs) b = Band.NONE
            else { lastOnsetUs = tUs; onsetPending = true }
        }
        shown = b
        return if (b == Band.NONE) null else Alert(b, lastMm?.takeIf { it <= tuning.farMm })
    }

    /** True once per onset (consumed by [takeOnset]); the cue scheduler interrupts and the spearcon fires. */
    fun takeOnset(): Boolean = onsetPending.also { onsetPending = false }

    private fun dropMeasurement() {
        active = Band.NONE; runDirection = 0; runBand = Band.NONE; lastMm = null
        samples.clear(); closingSpeed = null; approachCondition = false
    }

    fun reset() {
        dropMeasurement()
        shown = Band.NONE; onsetPending = false
    }
}
