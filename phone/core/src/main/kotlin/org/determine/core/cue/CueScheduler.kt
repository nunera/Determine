package org.determine.core.cue

import org.determine.core.Alert
import org.determine.core.Band
import org.determine.core.CueCommand
import org.determine.core.GAIN_MUTE
import org.determine.core.Tuning
import org.determine.core.hearing.CalBand
import org.determine.core.hearing.Ear
import org.determine.core.hearing.HearingMode
import org.determine.core.hearing.HearingProfile
import org.determine.core.wire.CueId
import org.determine.core.wire.Haptic
import org.determine.core.wire.Side
import java.util.Locale

data class CueConfig(
    /** Arch §8: NEAR is +6 dB, always within the ceiling. */
    val nearStepDb: Int = 6,
    val ttlMs: Int = 150,
    /** Haptic intensity scale per band (Arch §8.3 "intensity is user-tunable"). */
    val hapticScaleFar: Float = 0.6f,
    val hapticScaleMid: Float = 0.8f,
    val hapticScaleNear: Float = 1.0f,
)

/**
 * Turns the current alert into one repetition of the warning: the phone plays the sound ([CueCommand] gains →
 * LocalCueSink) and the cue board vibrates the temples (haptic fields → CUE packet). The phone drives
 * repetition. Onset and any band change play immediately with the interrupt flag.
 *
 * The single forward zone never lateralizes: audio and haptics are always side BOTH. PERSON_* cue ids stay
 * defined for the phone's sound bank, but the policy never selects them — a person/range label must never be
 * inferred from arbitrary image detections.
 */
class CueScheduler(var tuning: Tuning = Tuning(), val config: CueConfig = CueConfig()) {
    private var current: Alert? = null
    private var nextDueUs = 0L

    /** Arch §8 repetition period (Tuning: FAR 1/s, MID 2.5/s, NEAR 5/s). */
    fun period(b: Band): Long = when (b) {
        Band.FAR -> tuning.farPeriodMs * 1000L
        Band.MID -> tuning.midPeriodMs * 1000L
        Band.NEAR -> tuning.nearPeriodMs * 1000L
        Band.NONE -> Long.MAX_VALUE
    }

    /** @return a cue to play now, or null. Call on every tick and whenever the alert changes. */
    fun update(alert: Alert?, onset: Boolean, profile: HearingProfile, nowUs: Long): CueCommand? {
        if (alert == null || alert.band == Band.NONE) { current = null; return null }
        val prev = current
        current = alert
        val changed = onset || prev == null || prev.band != alert.band
        if (!changed && nowUs < nextDueUs) return null
        val p = period(alert.band)
        nextDueUs = if (changed || nowUs - nextDueUs >= p) nowUs + p else nextDueUs + p
        return build(alert, profile, interrupt = changed)
    }

    fun reset() { current = null }

    fun build(alert: Alert, profile: HearingProfile, interrupt: Boolean): CueCommand {
        val cueId = when (alert.band) {
            Band.NEAR -> CueId.OBSTACLE_NEAR
            Band.MID -> CueId.OBSTACLE_MID
            else -> CueId.OBSTACLE_FAR
        }
        // Calibration band matching the timbre: urgent note ~2 kHz, wood block ~1 kHz.
        val calBand = if (alert.band == Band.NEAR) CalBand.B2K else CalBand.B1K
        val step = if (alert.band == Band.NEAR) config.nearStepDb else 0
        val ceil = profile.effectiveCeilingDb
        fun level(e: Ear): Int = profile.levelDb(calBand, e)?.let { minOf(it + step, ceil) } ?: GAIN_MUTE
        val l = level(Ear.LEFT)
        val r = level(Ear.RIGHT)

        // SSD and STEREO both play every usable side at its own calibrated level: the forward zone never
        // lateralizes, so the modes differ only in calibration/verification semantics. HAPTIC_ONLY mutes
        // the phone audio; the board haptics are unaffected.
        val gl: Int
        val gr: Int
        when (profile.mode) {
            HearingMode.STEREO, HearingMode.SSD -> { gl = l; gr = r }
            HearingMode.HAPTIC_ONLY -> { gl = GAIN_MUTE; gr = GAIN_MUTE }
        }

        val pattern = when (alert.band) {
            Band.NEAR -> Haptic.BUZZ150
            Band.MID -> Haptic.PULSE60
            else -> Haptic.PULSE40
        }
        val scale = when (alert.band) {
            Band.NEAR -> config.hapticScaleNear
            Band.MID -> config.hapticScaleMid
            else -> config.hapticScaleFar
        }
        // Forward zone: both temples, never a left/right claim.
        fun hap(gain: Int) = minOf((gain * scale).toInt(), profile.hapticMax).coerceIn(0, 255)
        val hl = hap(profile.hapticGainL)
        val hr = hap(profile.hapticGainR)

        return CueCommand(cueId, Side.BOTH, gl, gr, pattern, hl, hr, if (interrupt) 1 else 0, config.ttlMs)
    }

    companion object {
        /** Spearcon text (Arch §8.2); distance only when backed by valid ToF. */
        fun spearcon(a: Alert): String {
            val dist = a.distanceMm?.let { String.format(Locale.ROOT, ", %.1f metres", it / 1000.0) } ?: ""
            return "obstacle ahead$dist"
        }
    }
}
