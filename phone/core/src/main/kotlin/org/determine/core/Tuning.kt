package org.determine.core

/**
 * Every runtime-adjustable knob of the engine (feature contract §2–§7). Applied immediately by
 * [Engine.setTuning] without a restart; [EngineConfig.tuning] carries the initial value. Times are in
 * milliseconds, distances in millimetres.
 */
data class Tuning(
    /**
     * Near-field demo bands (PROPOSED, measured/tunable): NEAR < [nearMm], MID < [midMm], FAR ≤ [farMm], anything
     * beyond [farMm] is not reported. The VL53L4CD's useful warning distance tops out at 1.2 m — the phone never
     * claims a longer range than the single forward sensor can honestly measure.
     */
    val nearMm: Int = 500,
    val midMm: Int = 900,
    val farMm: Int = 1200,

    /** Entering a band (or a more urgent one) needs readings that consistently indicate it for this long. */
    val bandEnterMs: Int = 150,
    /** Leaving a band (towards less urgent, or to nothing to report) needs this long of consistent evidence. */
    val bandExitMs: Int = 600,
    /** Moving out of a band additionally needs the distance to exceed the band's upper bound by this much. */
    val bandHysteresisMm: Int = 50,

    /** Arch §8: FAR 1/s, MID 2.5/s, NEAR 5/s. */
    val farPeriodMs: Int = 1000,
    val midPeriodMs: Int = 400,
    val nearPeriodMs: Int = 200,

    /** Spoken READY/DEGRADED announcements wait until the state held this long; the same phrase is not repeated within the repeat window. */
    val announceHoldMs: Int = 1500,
    val announceRepeatMs: Int = 10_000,

    /** Cue-board RSSI below [weakLinkRssiDbm] for [weakLinkHoldMs] = weak link; recovers at threshold + 5 dB held the same time. */
    val weakLinkRssiDbm: Int = -70,
    val weakLinkHoldMs: Int = 2000,
    val weakLinkRepeatMs: Int = 30_000,

    /** EXPERIMENTAL: escalate the reported band by one when the wearer closes on an obstacle fast. */
    val approachEnabled: Boolean = false,
    val approachWindowMs: Int = 300,
    val approachTtcMs: Int = 1500,
    val approachMinSpeedMmPerS: Int = 300,

    /** EXPERIMENTAL: replace band cues by a distance-proportional haptic pulse train. */
    val exploreEnabled: Boolean = false,
    val explorePeriodMs: Int = 120,
    val exploreMaxMm: Int = 1200,
    val exploreMinIntensity: Int = 30,
) {
    /** @throws IllegalArgumentException when any value is out of range (the engine keeps its previous tuning). */
    fun validate() {
        require(nearMm > 0 && nearMm < midMm && midMm < farMm && farMm <= MAX_RANGE_MM) {
            "bands must satisfy 0 < nearMm < midMm < farMm <= $MAX_RANGE_MM (got $nearMm, $midMm, $farMm)"
        }
        require(bandEnterMs >= 0) { "bandEnterMs must be >= 0 (got $bandEnterMs)" }
        require(bandExitMs >= 0) { "bandExitMs must be >= 0 (got $bandExitMs)" }
        require(bandHysteresisMm >= 0) { "bandHysteresisMm must be >= 0 (got $bandHysteresisMm)" }
        require(farPeriodMs > 0) { "farPeriodMs must be > 0 (got $farPeriodMs)" }
        require(midPeriodMs > 0) { "midPeriodMs must be > 0 (got $midPeriodMs)" }
        require(nearPeriodMs > 0) { "nearPeriodMs must be > 0 (got $nearPeriodMs)" }
        require(announceHoldMs >= 0) { "announceHoldMs must be >= 0 (got $announceHoldMs)" }
        require(announceRepeatMs >= 0) { "announceRepeatMs must be >= 0 (got $announceRepeatMs)" }
        require(weakLinkRssiDbm in -127..0) { "weakLinkRssiDbm must be in -127..0 (got $weakLinkRssiDbm)" }
        require(weakLinkHoldMs >= 0) { "weakLinkHoldMs must be >= 0 (got $weakLinkHoldMs)" }
        require(weakLinkRepeatMs >= 0) { "weakLinkRepeatMs must be >= 0 (got $weakLinkRepeatMs)" }
        require(approachWindowMs > 0) { "approachWindowMs must be > 0 (got $approachWindowMs)" }
        require(approachTtcMs > 0) { "approachTtcMs must be > 0 (got $approachTtcMs)" }
        require(approachMinSpeedMmPerS > 0) { "approachMinSpeedMmPerS must be > 0 (got $approachMinSpeedMmPerS)" }
        require(explorePeriodMs > 0) { "explorePeriodMs must be > 0 (got $explorePeriodMs)" }
        require(exploreMaxMm in 1..MAX_RANGE_MM) { "exploreMaxMm must be in 1..$MAX_RANGE_MM (got $exploreMaxMm)" }
        require(exploreMinIntensity in 0..255) { "exploreMinIntensity must be in 0..255 (got $exploreMinIntensity)" }
    }

    companion object {
        /** The sensor's outer limit; no band may reach past it. */
        const val MAX_RANGE_MM = 8000
    }
}
