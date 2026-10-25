package org.determine.core.hearing

/** Firmware hard ceiling (determine_wire.h DT_FW_HARD_CEILING_DB). The phone never asks for more. */
const val HARD_CEILING_DB = -10

/**
 * Lowest allowed board haptic cap (PROTOCOL.md v2: PROFILE.haptic_max is 1..255, the board rejects 0 as BAD_CUE).
 * The temple haptics are the only failure channel that survives phone loss (the board's watchdog), so the user
 * can never configure them off; a cap of 1 is the weakest setting and is NOT a measured perceptibility guarantee.
 */
const val MIN_HAPTIC_MAX = 1

enum class HearingMode(val wire: Int) { STEREO(0), SSD(1), HAPTIC_ONLY(2) }

enum class Ear { LEFT, RIGHT }

/** Calibration bands matching the cue timbres (Arch §9). Index used by [HearingProfile.bands]. */
object CalBand {
    const val B500 = 0
    const val B1K = 1
    const val B2K = 2
    val HZ = intArrayOf(500, 1000, 2000)
}

/** Per band; a null threshold means that side could not detect the tone at the ceiling (unusable). */
data class BandProfile(
    val hz: Int,
    val thrDbL: Int?,
    val thrDbR: Int?,
    val slDb: Int,
    val offsetDbL: Int,
    val offsetDbR: Int,
) {
    init { require(slDb in 10..20) { "sensation level must be 10..20 dB (Arch §9)" } }
}

/** Arch §9.1 HearingProfile. Every level it produces is ≤ [effectiveCeilingDb] ("ceiling first"). */
data class HearingProfile(
    val deviceId: String,
    val createdAtMs: Long,
    val mode: HearingMode,
    val ceilingDb: Int,
    val bands: List<BandProfile>,
    val hapticGainL: Int,
    val hapticGainR: Int,
    val hapticMax: Int,
    val verifiedTrials: Int,
    val verifiedCorrect: Int,
    val fitNote: String = "",
) {
    init {
        require(ceilingDb <= 0) { "ceiling is dBFS ≤ 0" }
        require(bands.size == CalBand.HZ.size)
        require(hapticGainL in 0..255 && hapticGainR in 0..255) { "haptic gains are 0..255" }
        require(hapticMax in MIN_HAPTIC_MAX..255) { "haptic_max is $MIN_HAPTIC_MAX..255: the watchdog failure channel cannot be disabled" }
    }

    val effectiveCeilingDb: Int get() = minOf(ceilingDb, HARD_CEILING_DB)

    /** thr + sensation level + balance offset, clamped to the ceiling; null = side unusable in this band. */
    fun levelDb(band: Int, ear: Ear): Int? {
        val b = bands[band]
        val thr = (if (ear == Ear.LEFT) b.thrDbL else b.thrDbR) ?: return null
        val off = if (ear == Ear.LEFT) b.offsetDbL else b.offsetDbR
        return minOf(thr + b.slDb + off, effectiveCeilingDb)
    }

    fun usable(ear: Ear): Boolean = bands.all { (if (ear == Ear.LEFT) it.thrDbL else it.thrDbR) != null }

    companion object {
        /**
         * Before calibration: a conservative quiet level 20 dB below the ceiling on both sides, STEREO.
         * Calibration is re-run each session (Arch §4.1); this exists so the system can run uncalibrated.
         */
        fun uncalibrated(ceilingDb: Int = -20, mode: HearingMode = HearingMode.STEREO, nowMs: Long = 0): HearingProfile {
            val c = minOf(ceilingDb, HARD_CEILING_DB)
            val thr = c - 35
            return HearingProfile(
                deviceId = "uncalibrated", createdAtMs = nowMs, mode = mode, ceilingDb = ceilingDb,
                bands = CalBand.HZ.map { BandProfile(it, thr, thr, 15, 0, 0) },
                hapticGainL = 200, hapticGainR = 200, hapticMax = 255, verifiedTrials = 0, verifiedCorrect = 0,
            )
        }
    }
}

/**
 * Adaptive 2-down-1-up staircase (Levitt 1971; converges near 70.7 % "heard"), Arch §9.
 * Starts quiet; two consecutive "heard" step down, one "not heard" steps up. Large steps until
 * [bigStepReversals] reversals, then small steps. Stops after [targetReversals] (6–8) reversals; threshold = mean of
 * the reversal levels after the big-step phase. Never steps above the ceiling: "not heard" at the ceiling marks the
 * side unusable (Arch §9.1 "ceiling first").
 */
class Staircase(
    startDb: Int = -60,
    val ceilingDb: Int,
    private val floorDb: Int = -90,
    private val bigStepDb: Int = 6,
    private val smallStepDb: Int = 2,
    private val bigStepReversals: Int = 2,
    private val targetReversals: Int = 8,
    private val maxTrials: Int = 80,
) {
    init { require(targetReversals in 6..8) { "Arch §9: stop after 6–8 reversals" } }

    var levelDb: Int = minOf(startDb, ceilingDb); private set
    var done = false; private set
    var unusable = false; private set
    var trials = 0; private set
    private val reversals = mutableListOf<Int>()
    private var lastDir = 0
    private var heardRun = 0

    val reversalLevels: List<Int> get() = reversals

    /** Mean of the small-step reversals (rounded), null while running or when unusable. */
    val thresholdDb: Int?
        get() {
            if (!done || unusable) return null
            val use = reversals.drop(bigStepReversals).ifEmpty { reversals }
            return Math.round(use.average()).toInt()
        }

    fun answer(heard: Boolean) {
        check(!done) { "staircase finished" }
        trials++
        val dir: Int
        if (heard) {
            heardRun++
            if (heardRun < 2) { checkTrialLimit(); return }
            heardRun = 0
            dir = -1
        } else {
            heardRun = 0
            if (levelDb >= ceilingDb) { unusable = true; done = true; return }
            dir = +1
        }
        if (lastDir != 0 && dir != lastDir) {
            reversals += levelDb
            if (reversals.size >= targetReversals) { done = true; return }
        }
        lastDir = dir
        val step = if (reversals.size < bigStepReversals) bigStepDb else smallStepDb
        levelDb = (levelDb + dir * step).coerceIn(floorDb, ceilingDb)
        checkTrialLimit()
    }

    private fun checkTrialLimit() {
        if (trials >= maxTrials && !done) {
            done = true
            // Too few reversals: either everything was heard down to the floor (threshold ≤ floor, usable)
            // or the answers never settled (unusable rather than guessing).
            if (reversals.size < 4) {
                if (levelDb <= floorDb) reversals += floorDb else unusable = true
            }
        }
    }
}
