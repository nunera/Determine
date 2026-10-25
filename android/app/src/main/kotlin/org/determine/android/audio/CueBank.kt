package org.determine.android.audio

import org.determine.core.CueCommand
import org.determine.core.wire.AckStatus
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The phone's cue sound bank (Arch §8): note table, waveforms, dBFS gain maths and the play/clamp decision for
 * every cue id in PROTOCOL.md. Since protocol v2 the cue board is haptic-only, so this is the single home of the
 * warning sounds, played through the Bluetooth bone-conduction headset by [PhoneCueSink]. (The table and
 * waveforms originate from the retired firmware audio bank; the timing rules are unchanged and checked by
 * CueBankTest.)
 *
 * Pure: no Android dependencies.
 */
object CueBank {
    enum class Wave { WOODBLOCK, MARIMBA, TONE, TICK }

    class Note(val wave: Wave, val freqHz: Int, val durMs: Int, val gapMs: Int)

    class Cue(val name: String, val notes: List<Note>, val periodMs: Int)

    // PROTOCOL.md "Cue IDs".
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

    // PROTOCOL.md CUE side / flags.
    const val SIDE_L = 0
    const val SIDE_R = 1
    const val SIDE_BOTH = 2
    const val FLAG_INTERRUPT = 1

    const val GAIN_MUTE = org.determine.core.GAIN_MUTE
    /** The sink never plays louder than this, whatever the profile says (= core HARD_CEILING_DB). */
    const val HARD_CEILING_DB = org.determine.core.hearing.HARD_CEILING_DB

    // Arch §8.1 timing rules.
    const val NOTE_MIN_MS = 83
    const val INTER_CUE_GAP_MS = 100
    const val MAX_NOTES_PER_S = 6
    const val FREQ_MIN_HZ = 300
    const val FREQ_MAX_HZ = 4000

    private val W = Wave.WOODBLOCK
    private val M = Wave.MARIMBA
    private val T = Wave.TONE
    private val K = Wave.TICK

    /** Index = cue id. */
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

    /** Audible length of one cue: notes + internal gaps, trailing gap excluded . */
    fun lengthMs(c: Cue): Int = c.notes.withIndex().sumOf { (i, n) -> n.durMs + if (i + 1 < c.notes.size) n.gapMs else 0 }

    /** Null when [c] obeys every Arch §8.1 rule, else the reason . */
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

    /** Cap at min(profile ceiling, hard ceiling); mute stays mute. */
    fun clampGain(requestedDb: Int, profileCeilingDb: Int): Int {
        val cap = minOf(profileCeilingDb, HARD_CEILING_DB)
        if (requestedDb == GAIN_MUTE) return GAIN_MUTE
        return if (requestedDb > cap) cap else requestedDb
    }

    // ---------------------------------------------------------------- synth

    private const val SINE_N = 1 shl 10
    private val SINE = FloatArray(SINE_N) { sin(2.0 * PI * it / SINE_N).toFloat() }

    /** Phase from the absolute sample index, so no accumulator drift . */
    private fun sineAt(n: Long, freqHz: Int, rateHz: Int): Float =
        SINE[((n * freqHz * SINE_N / rateHz) and (SINE_N - 1).toLong()).toInt()]

    private fun powi(b: Float, e: Int): Float {
        var r = 1f
        repeat(e) { r *= b }
        return r
    }

    /** Samples in a note window of [durMs] at [rateHz] (integer maths as on the board). */
    fun windowSamples(durMs: Int, rateHz: Int): Int = (durMs.toLong() * rateHz / 1000).toInt()

    /** One normalised sample (|x| ≤ 1) at index [n] of the note window; 0 outside it . */
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

    /** Normalised sample × Q15 gain → int16 with saturation . */
    fun scaleSample(x: Float, gainQ15: Int): Short {
        val v = x * gainQ15.toFloat() * (32767f / 32768f)
        return when {
            v > 32767f -> Short.MAX_VALUE
            v < -32768f -> Short.MIN_VALUE
            else -> v.roundToLong().toShort()
        }
    }

    /** Streams one cue as interleaved stereo int16 frames, chunk by chunk . */
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

    // ---------------------------------------------------------------- play decision

    /** What the sink does with one command; gains are the applied values (−128 = silent channel). */
    data class Decision(val status: Int, val play: Boolean, val gainLDb: Int, val gainRDb: Int) {
        val audible: Boolean get() = play && (gainLDb != GAIN_MUTE || gainRDb != GAIN_MUTE)
    }

    /**
     * BAD_CUE (unknown id/side) → BUSY_DROPPED (output busy and no interrupt flag) → APPLIED / APPLIED_CLAMPED
     * (clamped iff the per-side ceiling lowered a requested gain). The side then silences the other channel.
     * The commanded gains are authoritative for the mode: the engine already mutes both sides for HAPTIC_ONLY, and
     * calibration tones must stay audible whatever the previous profile chose. There is no deadline to miss (the
     * phone plays its own commands synchronously). Haptics are not decided here: they go to the cue board.
     */
    fun decide(cmd: CueCommand, ceilingDb: Int, outputBusy: Boolean): Decision {
        if (cmd.cueId !in CUES.indices || cmd.side !in SIDE_L..SIDE_BOTH) {
            return Decision(AckStatus.BAD_CUE, false, GAIN_MUTE, GAIN_MUTE)
        }
        if (outputBusy && (cmd.flags and FLAG_INTERRUPT) == 0) {
            return Decision(AckStatus.BUSY_DROPPED, false, GAIN_MUTE, GAIN_MUTE)
        }
        val gl = clampGain(cmd.gainLDb, ceilingDb)
        val gr = clampGain(cmd.gainRDb, ceilingDb)
        val status = if (gl != cmd.gainLDb || gr != cmd.gainRDb) AckStatus.APPLIED_CLAMPED else AckStatus.APPLIED
        val left = cmd.side != SIDE_R
        val right = cmd.side != SIDE_L
        return Decision(
            status = status,
            play = true,
            gainLDb = if (left) gl else GAIN_MUTE,
            gainRDb = if (right) gr else GAIN_MUTE,
        )
    }
}
