package org.determine.core.policy

import org.determine.core.Alert
import org.determine.core.Band
import org.determine.core.Detection
import org.determine.core.Region
import org.determine.core.range.RangeReading
import org.determine.core.range.RegionRange

/** One region's input to the policy. [known] = false means UNKNOWN (never treated as nothing-there). */
data class RegionObservation(val band: Band, val distanceMm: Int?, val known: Boolean)

data class PolicyConfig(
    val nearMm: Int = 1000,
    val midMm: Int = 2000,
    val farMm: Int = 3000,
    val enterSamples: Int = 3,
    val exitSamples: Int = 5,
    val minOnsetIntervalUs: Long = 700_000,
    val detectionScoreThreshold: Float = 0.5f,
    /** Relative (no-ToF) path: box area must grow by this factor within [growthWindowUs]. */
    val growthFactor: Float = 1.3f,
    val growthWindowUs: Long = 1_500_000,
    val growthMinAgeUs: Long = 400_000,
    val growthMinArea: Float = 0.02f,
)

/**
 * Arch §7.2 hazard policy v0. Picks at most one alert, with persistence and hysteresis:
 *  - band(d): NEAR < 1000, MID < 2000, FAR < 3000 mm, else NONE;
 *  - enter: same region with band ≥ FAR for ≥ 3 consecutive samples;
 *  - exit: band NONE for ≥ 5 samples, or region change (re-enter with a new onset);
 *  - priority: NEAR corridor > NEAR flank > MID corridor > MID flank > FAR corridor (FAR flank never alerts);
 *  - rate limit: ≤ 1 onset per 700 ms;
 *  - head scanning holds FAR cues only; NEAR/MID always pass;
 *  - label = matched detection (by bearing) if score ≥ threshold, else "obstacle";
 *  - never CLEAR: the output is an alert or null (= nothing to report). UNKNOWN samples do not count toward exit.
 */
class HazardPolicy(val config: PolicyConfig = PolicyConfig()) {
    private var active: Alert? = null
    private var candRegion: Region? = null
    private var candCount = 0
    private var noneCount = 0
    private var lastOnsetUs = Long.MIN_VALUE / 2
    private var scanningHeld = false

    /** True once per onset (consumed by [takeOnset]); the cue scheduler interrupts and the spearcon fires. */
    private var onsetPending = false

    fun band(mm: Int): Band = when {
        mm < config.nearMm -> Band.NEAR
        mm < config.midMm -> Band.MID
        mm < config.farMm -> Band.FAR
        else -> Band.NONE
    }

    fun observe(reading: RangeReading): Map<Region, RegionObservation> = Region.entries.associateWith { r ->
        when (val rr = reading.of(r)) {
            is RegionRange.Target -> {
                val b = band(rr.minMm)
                RegionObservation(b, if (b == Band.NONE) null else rr.minMm, true)
            }
            RegionRange.NothingSeen -> RegionObservation(Band.NONE, null, true)
            RegionRange.Unknown -> RegionObservation(Band.NONE, null, false)
        }
    }

    private data class Candidate(val region: Region, val band: Band, val distanceMm: Int?)

    private fun candidate(obs: Map<Region, RegionObservation>): Candidate? {
        fun flank(b: Band): Candidate? = listOf(Region.LEFT, Region.RIGHT)
            .mapNotNull { r -> obs[r]?.takeIf { it.band == b }?.let { Candidate(r, b, it.distanceMm) } }
            .minByOrNull { it.distanceMm ?: Int.MAX_VALUE }
        fun center(b: Band): Candidate? = obs[Region.CENTER]?.takeIf { it.band == b }?.let { Candidate(Region.CENTER, b, it.distanceMm) }
        return center(Band.NEAR) ?: flank(Band.NEAR) ?: center(Band.MID) ?: flank(Band.MID) ?: center(Band.FAR)
    }

    /**
     * One sample (a fresh ToF grid, or a relative-cue sample when ToF is unusable).
     * @return the alert to present now, or null for nothing to report.
     */
    fun step(obs: Map<Region, RegionObservation>, detections: List<Detection>, scanning: Boolean, nowUs: Long): Alert? {
        val c = candidate(obs)
        if (c == null) {
            val anyUnknown = obs.values.any { !it.known }
            candRegion = null
            candCount = 0
            // Unknown is never clear: an UNKNOWN sample neither confirms nor clears the active alert.
            if (!anyUnknown && active != null && ++noneCount >= config.exitSamples) active = null
            return output(scanning)
        }
        if (c.region == candRegion) candCount++ else { candRegion = c.region; candCount = 1 }
        val cur = active
        if (cur != null) {
            if (c.region == cur.region) {
                noneCount = 0
                active = Alert(c.region, c.band, label(c.region, detections), c.distanceMm)
                return output(scanning)
            }
            active = null // region change: exit, re-enter with a new onset
        }
        val heldForScan = scanning && c.band == Band.FAR
        if (candCount >= config.enterSamples && nowUs - lastOnsetUs >= config.minOnsetIntervalUs && !heldForScan) {
            active = Alert(c.region, c.band, label(c.region, detections), c.distanceMm)
            noneCount = 0
            lastOnsetUs = nowUs
            onsetPending = true
        }
        return output(scanning)
    }

    private fun output(scanning: Boolean): Alert? {
        val a = active ?: return null
        scanningHeld = scanning && a.band == Band.FAR
        return if (scanningHeld) null else a
    }

    fun takeOnset(): Boolean = onsetPending.also { onsetPending = false }

    fun reset() {
        active = null; candRegion = null; candCount = 0; noneCount = 0; onsetPending = false
    }

    /** Bearing of a detection: normalised x → L/C/R using the same thirds split as the default corridor. */
    fun regionOf(d: Detection): Region = when {
        d.centerX < 1f / 3f -> Region.LEFT
        d.centerX > 2f / 3f -> Region.RIGHT
        else -> Region.CENTER
    }

    fun label(region: Region, detections: List<Detection>): String =
        detections.filter { it.score >= config.detectionScoreThreshold && regionOf(it) == region }
            .maxByOrNull { it.score }?.label?.lowercase() ?: "obstacle"

    // ---- Relative cues without ToF (Arch §7.1: bounding-box growth over time, never metres) ----
    private val history = HashMap<Region, ArrayDeque<Pair<Long, Float>>>()

    /**
     * Builds observations from detections alone: a confident detection whose box grew by ≥ [PolicyConfig.growthFactor]
     * within the window is reported as FAR in its region with no distance. Everything else is NONE (known): the
     * camera saw nothing approaching, which is "nothing to report", never "clear".
     */
    fun relativeObservations(detections: List<Detection>, captureUs: Long): Map<Region, RegionObservation> {
        val byRegion = detections.filter { it.score >= config.detectionScoreThreshold }.groupBy { regionOf(it) }
        return Region.entries.associateWith { r ->
            val h = history.getOrPut(r) { ArrayDeque() }
            while (h.isNotEmpty() && captureUs - h.first().first > config.growthWindowUs) h.removeFirst()
            val area = byRegion[r]?.maxOf { it.area } ?: 0f
            val baseline = h.filter { captureUs - it.first >= config.growthMinAgeUs && it.second > 0f }.minOfOrNull { it.second }
            h.addLast(captureUs to area)
            val growing = area >= config.growthMinArea && baseline != null && baseline > 0f &&
                area >= baseline * config.growthFactor
            RegionObservation(if (growing) Band.FAR else Band.NONE, null, true)
        }
    }
}
