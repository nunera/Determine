package org.determine.android.audio

import org.determine.core.CueCommand
import org.determine.core.hearing.HearingMode
import org.determine.core.wire.AckStatus
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Kotlin port of the cue board's pure cue logic (`firmware/cue-board/lib/cue_logic/cue_logic.c`), so the phone-audio
 * fallback (Arch §4.1 CueSink "phone audio to headset") speaks exactly the same cue language (Arch §8) as the glasses:
 * the same note table, waveforms, dBFS gain maths, CUE decision and haptic pattern timing. Keep the two in step:
 * a change to the firmware bank must be mirrored here (CueBankTest checks the timing rules on this copy).
 *
 * Pure: no Android dependencies.
 */
object CueBank {
    enum class Wave { WOODBLOCK, MARIMBA, TONE, TICK }

    class Note(val wave: Wave, val freqHz: Int, val durMs: Int, val gapMs: Int)

    class Cue(val name: String, val notes: List<Note>, val periodMs: Int)

    // PROTOCOL.md "Cue IDs"; firmware DT_CUE_* order.
    const val READY = 0
    const val OBSTACLE_FAR = 1
    const val OBSTACLE_MID = 2
    const val OBSTACLE_NEAR = 3
    const val PERSON_FAR = 4
    const val PERSON_MID = 5
    const val PERSON_NEAR = 6
    const val SYSTEM_OFF = 7
    const val CAL_TONE_500 = 8
    const val CAL_TONE_1K = 9
    const val CAL_TONE_2K = 10
    const val CAL_OBSTACLE = 11

    // PROTOCOL.md CUE side / flags, PROFILE mode.
    const val SIDE_L = 0
    const val SIDE_R = 1
    const val SIDE_BOTH = 2
    const val FLAG_INTERRUPT = 1

    const val GAIN_MUTE = -128
    /** firmware DT_FW_HARD_CEILING_DB: the sink never plays louder than this, whatever the profile says. */
    const val HARD_CEILING_DB = -10

    // Arch §8.1 timing rules (firmware DT_NOTE_MIN_MS … DT_FREQ_MAX_HZ).
    const val NOTE_MIN_MS = 83
    const val INTER_CUE_GAP_MS = 100
    const val MAX_NOTES_PER_S = 6
    const val FREQ_MIN_HZ = 300
    const val FREQ_MAX_HZ = 4000

    private val W = Wave.WOODBLOCK
    private val M = Wave.MARIMBA
    private val T = Wave.TONE
    private val K = Wave.TICK

    /** Index = cue id. Values copied from the firmware k_cues table. */
    val CUES: List<Cue> = listOf(
        Cue("READY", listOf(Note(K, 2000, 85, 0)), 1000),
        Cue("OBSTACLE_FAR", listOf(Note(W, 800, 90, 30), Note(W, 800, 90, 0)), 1000),
        Cue("OBSTACLE_MID", listOf(Note(W, 1000, 85, 25), Note(W, 1000, 85, 0)), 400),
        Cue("OBSTACLE_NEAR", listOf(Note(W, 1600, 90, 0)), 200),
        Cue("PERSON_FAR", listOf(Note(M, 523, 100, 20), Note(M, 784, 100, 0)), 1000),
        Cue("PERSON_MID", listOf(Note(M, 659, 90, 20), Note(M, 988, 90, 0)), 400),
        Cue("PERSON_NEAR", listOf(Note(M, 1319, 90, 0)), 200),
        Cue("SYSTEM_OFF", listOf(Note(M, 880, 120, 40), Note(M, 659, 120, 40), Note(M, 440, 120, 0)), 5000),
        Cue("CAL_TONE_500", listOf(Note(T, 500, 300, 0)), 1000),
        Cue("CAL_TONE_1K", listOf(Note(T, 1000, 300, 0)), 1000),
        Cue("CAL_TONE_2K", listOf(Note(T, 2000, 300, 0)), 1000),
        Cue("CAL_OBSTACLE", listOf(Note(W, 800, 90, 30), Note(W, 800, 90, 0)), 1000),
    )

    fun cue(id: Int): Cue? = CUES.getOrNull(id)

    /** Audible length of one cue: notes + internal gaps, trailing gap excluded (firmware dt_cue_length_ms). */
    fun lengthMs(c: Cue): Int = c.notes.withIndex().sumOf { (i, n) -> n.durMs + if (i + 1 < c.notes.size) n.gapMs else 0 }

    /** Null when [c] obeys every Arch §8.1 rule, else the reason (firmware dt_cue_timing_violation). */
    fun timingViolation(c: Cue): String? {
        if (c.notes.isEmpty() || c.notes.size > 3) return "note count"
        for (n in c.notes) {
            if (n.durMs < NOTE_MIN_MS) return "note shorter than 83 ms"
            if (n.freqHz !in FREQ_MIN_HZ..FREQ_MAX_HZ) return "pitch outside 300-4000 Hz"
        }
        if (lengthMs(c) + INTER_CUE_GAP_MS > c.periodMs) return "cue + 100 ms gap exceeds period"
        if (c.notes.size * 1000 > MAX_NOTES_PER_S * c.periodMs) return "more than 6 notes/s"
        return null
    }

    // ---------------------------------------------------------------- gain

    /** dBFS → Q15 linear amplitude (32768 = 0 dBFS); −128 mutes; positive input is treated as 0 dBFS (never boost). */
    fun dbToQ15(db: Int): Int = when {
        db == GAIN_MUTE -> 0
        db >= 0 -> 32768
        else -> (32768.0 * 10.0.pow(db / 20.0)).roundToInt()
    }

    /** firmware dt_clamp_gain: cap at min(profile ceiling, hard ceiling); mute stays mute. */
    fun clampGain(requestedDb: Int, profileCeilingDb: Int): Int {
        val cap = minOf(profileCeilingDb, HARD_CEILING_DB)
        if (requestedDb == GAIN_MUTE) return GAIN_MUTE
        return if (requestedDb > cap) cap else requestedDb
    }

    // ---------------------------------------------------------------- synth

    private const val SINE_N = 1 shl 10
    private val SINE = FloatArray(SINE_N) { sin(2.0 * PI * it / SINE_N).toFloat() }

    /** Phase from the absolute sample index, so no accumulator drift (firmware sine_at). */
    private fun sineAt(n: Long, freqHz: Int, rateHz: Int): Float =
        SINE[((n * freqHz * SINE_N / rateHz) and (SINE_N - 1).toLong()).toInt()]

    private fun powi(b: Float, e: Int): Float {
        var r = 1f
        repeat(e) { r *= b }
        return r
    }

    /** Samples in a note window of [durMs] at [rateHz] (integer maths as on the board). */
    fun windowSamples(durMs: Int, rateHz: Int): Int = (durMs.toLong() * rateHz / 1000).toInt()

    /** One normalised sample (|x| ≤ 1) at index [n] of the note window; 0 outside it (firmware dt_note_sample). */
    fun noteSample(note: Note, n: Int, rateHz: Int): Float {
        val total = windowSamples(note.durMs, rateHz)
        if (total == 0 || n < 0 || n >= total) return 0f
        val x = n.toFloat() / total
        val rest = 1f - x
        val tMs = n * 1000f / rateHz
        val f = note.freqHz
        return when (note.wave) {
            Wave.WOODBLOCK -> {
                val att = if (tMs < 1f) tMs else 1f
                val f2 = (f * 2.76f).toInt()
                att * (0.7f * powi(rest, 4) * sineAt(n.toLong(), f, rateHz) + 0.3f * powi(rest, 8) * sineAt(n.toLong(), f2, rateHz))
            }
            Wave.MARIMBA -> {
                val att = if (tMs < 2f) tMs / 2f else 1f
                att * (0.75f * powi(rest, 3) * sineAt(n.toLong(), f, rateHz) + 0.25f * powi(rest, 8) * sineAt(n.toLong(), f * 4, rateHz))
            }
            Wave.TONE -> {
                val dur = note.durMs.toFloat()
                var ramp = when {
                    tMs < 5f -> tMs / 5f
                    dur - tMs < 5f -> (dur - tMs) / 5f
                    else -> 1f
                }
                if (ramp < 0f) ramp = 0f
                0.9f * ramp * sineAt(n.toLong(), f, rateHz)
            }
            Wave.TICK -> {
                val att = if (tMs < 0.5f) tMs / 0.5f else 1f
                0.6f * att * powi(rest, 12) * sineAt(n.toLong(), f, rateHz)
            }
        }
    }

    /** Normalised sample × Q15 gain → int16 with saturation (firmware dt_scale_sample). */
    fun scaleSample(x: Float, gainQ15: Int): Short {
        val v = x * gainQ15.toFloat() * (32767f / 32768f)
        return when {
            v > 32767f -> Short.MAX_VALUE
            v < -32768f -> Short.MIN_VALUE
            else -> v.roundToLong().toShort()
        }
    }

    /** Streams one cue as interleaved stereo int16 frames, chunk by chunk (firmware dt_player_t). */
    class Player(private val cue: Cue, private val rateHz: Int, private val gainLQ15: Int, private val gainRQ15: Int) {
        private var noteIdx = 0
        private var pos = 0
        var done: Boolean = cue.notes.isEmpty(); private set

        /** Writes up to [frames] L/R frames into [lr] from index 0; returns frames written (< frames only at the end). */
        fun render(lr: ShortArray, frames: Int): Int {
            var i = 0
            while (i < frames && !done) {
                val note = cue.notes[noteIdx]
                val last = noteIdx + 1 == cue.notes.size
                val win = windowSamples(note.durMs, rateHz)
                val gap = if (last) 0 else windowSamples(note.gapMs, rateHz)
                if (pos >= win + gap) {
                    pos = 0
                    if (last) done = true else noteIdx++
                    continue
                }
                val s = noteSample(note, pos, rateHz) // 0 in the gap
                lr[2 * i] = scaleSample(s, gainLQ15)
                lr[2 * i + 1] = scaleSample(s, gainRQ15)
                pos++
                i++
            }
            return i
        }
    }

    // ---------------------------------------------------------------- haptics

    /** PROTOCOL.md haptic patterns, index = pattern id: alternating on, off, on … ms, starting with on. */
    val HAPTICS: List<IntArray> = listOf(
        intArrayOf(),                         // 0 NONE
        intArrayOf(40),                       // 1 PULSE40
        intArrayOf(60),                       // 2 PULSE60
        intArrayOf(150),                      // 3 BUZZ150
        intArrayOf(400, 100, 120, 100, 120),  // 4 LSS
    )

    fun hapticLengthMs(pattern: Int): Int = HAPTICS.getOrNull(pattern)?.sum() ?: 0

    /**
     * Android one-shot waveform for [pattern] (timings start with a 0 ms off segment), and amplitudes 0/[amplitude]
     * per segment. Null for NONE/unknown or zero intensity.
     */
    fun vibrationWaveform(pattern: Int, amplitude: Int): Pair<LongArray, IntArray>? {
        val segs = HAPTICS.getOrNull(pattern) ?: return null
        if (segs.isEmpty() || amplitude <= 0) return null
        val timings = LongArray(segs.size + 1)
        val amps = IntArray(segs.size + 1)
        for (i in segs.indices) {
            timings[i + 1] = segs[i].toLong()
            amps[i + 1] = if (i % 2 == 0) amplitude.coerceIn(1, 255) else 0
        }
        return timings to amps
    }

    // ---------------------------------------------------------------- CUE decision

    /** What the sink does with one command; gains are the applied values (−128 = silent channel). */
    data class Decision(
        val status: Int,
        val play: Boolean,
        val gainLDb: Int,
        val gainRDb: Int,
        val hapticL: Int,
        val hapticR: Int,
    ) {
        val audible: Boolean get() = play && (gainLDb != GAIN_MUTE || gainRDb != GAIN_MUTE)
    }

    /**
     * firmware dt_cue_decide without the clock check (the phone plays its own commands synchronously, so there is
     * no deadline to miss): BAD_CUE (unknown id/side/pattern) → BUSY_DROPPED (output busy and no interrupt flag) →
     * PLAYED / PLAYED_CLAMPED (PLAYED_CLAMPED iff clamping changed a requested gain). Side and HAPTIC_ONLY then
     * silence channels; haptic intensities are capped by [hapticMax].
     */
    fun decide(cmd: CueCommand, ceilingDb: Int, mode: HearingMode, hapticMax: Int, outputBusy: Boolean): Decision {
        if (cmd.cueId !in CUES.indices || cmd.side !in SIDE_L..SIDE_BOTH || cmd.hapticPattern !in HAPTICS.indices) {
            return Decision(AckStatus.BAD_CUE, false, GAIN_MUTE, GAIN_MUTE, 0, 0)
        }
        if (outputBusy && (cmd.flags and FLAG_INTERRUPT) == 0) {
            return Decision(AckStatus.BUSY_DROPPED, false, GAIN_MUTE, GAIN_MUTE, 0, 0)
        }
        val gl = clampGain(cmd.gainLDb, ceilingDb)
        val gr = clampGain(cmd.gainRDb, ceilingDb)
        val status = if (gl != cmd.gainLDb || gr != cmd.gainRDb) AckStatus.PLAYED_CLAMPED else AckStatus.PLAYED
        val left = cmd.side != SIDE_R
        val right = cmd.side != SIDE_L
        val audio = mode != HearingMode.HAPTIC_ONLY
        val haptic = cmd.hapticPattern != 0
        return Decision(
            status = status,
            play = true,
            gainLDb = if (left && audio) gl else GAIN_MUTE,
            gainRDb = if (right && audio) gr else GAIN_MUTE,
            hapticL = if (haptic && left) minOf(cmd.hapticL, hapticMax).coerceIn(0, 255) else 0,
            hapticR = if (haptic && right) minOf(cmd.hapticR, hapticMax).coerceIn(0, 255) else 0,
        )
    }
}
