package org.determine.core.cue

import org.determine.core.Alert
import org.determine.core.Band
import org.determine.core.CueCommand
import org.determine.core.Region
import org.determine.core.hearing.CalBand
import org.determine.core.hearing.Ear
import org.determine.core.hearing.HearingMode
import org.determine.core.hearing.HearingProfile
import org.determine.core.wire.CueId
import org.determine.core.wire.GAIN_MUTE
import org.determine.core.wire.Haptic
import org.determine.core.wire.Side
import java.util.Locale

data class CueConfig(
    /** Arch §8: FAR 1/s, MID 2.5/s, NEAR 5/s. */
    val farPeriodUs: Long = 1_000_000,
    val midPeriodUs: Long = 400_000,
    val nearPeriodUs: Long = 200_000,
    /** Arch §8: NEAR is +6 dB, always within the ceiling. */
    val nearStepDb: Int = 6,
    val ttlMs: Int = 150,
    /** Haptic intensity scale per band (Arch §8.3 "intensity is user-tunable"). */
    val hapticScaleFar: Float = 0.6f,
    val hapticScaleMid: Float = 0.8f,
    val hapticScaleNear: Float = 1.0f,
)

/**
 * Turns the current alert into one CUE per repetition (the phone drives repetition, PROTOCOL.md CUE).
 * Onset and any change of region/band/label family play immediately with the interrupt flag.
 */
class CueScheduler(val config: CueConfig = CueConfig()) {
    private var current: Alert? = null
    private var nextDueUs = 0L

    fun period(b: Band): Long = when (b) {
        Band.FAR -> config.farPeriodUs
        Band.MID -> config.midPeriodUs
        Band.NEAR -> config.nearPeriodUs
        Band.NONE -> Long.MAX_VALUE
    }

    /** @return a cue to send now, or null. Call on every tick and whenever the alert changes. */
    fun update(alert: Alert?, onset: Boolean, profile: HearingProfile, nowUs: Long): CueCommand? {
        if (alert == null || alert.band == Band.NONE) { current = null; return null }
        val prev = current
        current = alert
        val changed = onset || prev == null || prev.region != alert.region || prev.band != alert.band ||
            isPerson(prev.label) != isPerson(alert.label)
        if (!changed && nowUs < nextDueUs) return null
        val p = period(alert.band)
        nextDueUs = if (changed || nowUs - nextDueUs >= p) nowUs + p else nextDueUs + p
        return build(alert, profile, interrupt = changed)
    }

    fun reset() { current = null }

    fun build(alert: Alert, profile: HearingProfile, interrupt: Boolean): CueCommand {
        val person = isPerson(alert.label)
        val cueId = when (alert.band) {
            Band.NEAR -> if (person) CueId.PERSON_NEAR else CueId.OBSTACLE_NEAR
            Band.MID -> if (person) CueId.PERSON_MID else CueId.OBSTACLE_MID
            else -> if (person) CueId.PERSON_FAR else CueId.OBSTACLE_FAR
        }
        // Calibration band matching the timbre: urgent note ~2 kHz, marimba ~500 Hz, wood block ~1 kHz.
        val calBand = when {
            alert.band == Band.NEAR -> CalBand.B2K
            person -> CalBand.B500
            else -> CalBand.B1K
        }
        val step = if (alert.band == Band.NEAR) config.nearStepDb else 0
        val ceil = profile.effectiveCeilingDb
        fun level(e: Ear): Int = profile.levelDb(calBand, e)?.let { minOf(it + step, ceil) } ?: GAIN_MUTE
        val l = level(Ear.LEFT)
        val r = level(Ear.RIGHT)

        val side: Int
        val gl: Int
        val gr: Int
        when (profile.mode) {
            HearingMode.STEREO -> when (alert.region) {
                Region.LEFT -> { side = Side.L; gl = l; gr = GAIN_MUTE }
                Region.RIGHT -> { side = Side.R; gl = GAIN_MUTE; gr = r }
                Region.CENTER -> { side = Side.BOTH; gl = l; gr = r }
            }
            // SSD: identical (mono) audio on every usable side; direction moves to haptics (Arch §9).
            HearingMode.SSD -> { side = Side.BOTH; gl = l; gr = r }
            HearingMode.HAPTIC_ONLY -> { side = Side.BOTH; gl = GAIN_MUTE; gr = GAIN_MUTE }
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
        fun hap(gain: Int) = minOf((gain * scale).toInt(), profile.hapticMax).coerceIn(0, 255)
        val hl = if (alert.region != Region.RIGHT) hap(profile.hapticGainL) else 0
        val hr = if (alert.region != Region.LEFT) hap(profile.hapticGainR) else 0

        return CueCommand(cueId, side, gl, gr, pattern, hl, hr, if (interrupt) 1 else 0, config.ttlMs)
    }

    companion object {
        fun isPerson(label: String) = label.lowercase(Locale.ROOT).let { it == "person" || it == "pedestrian" }

        /** Spearcon text (Arch §8.2): label + side; distance only when backed by valid ToF. */
        fun spearcon(a: Alert): String {
            val where = when (a.region) { Region.LEFT -> "left"; Region.RIGHT -> "right"; Region.CENTER -> "ahead" }
            val dist = a.distanceMm?.let { String.format(Locale.ROOT, ", %.1f metres", it / 1000.0) } ?: ""
            return "${a.label} $where$dist"
        }
    }
}
