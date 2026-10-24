package org.determine.core.hearing

import org.determine.core.CueCommand
import org.determine.core.wire.CueId
import org.determine.core.wire.GAIN_MUTE
import org.determine.core.wire.Haptic
import org.determine.core.wire.Side
import kotlin.random.Random

enum class CalStep { FIT, THRESHOLD, LEVEL, BALANCE, VERIFY, DONE }

/**
 * Arch §9 calibration: fit → per-side threshold in three bands → presentation level → balance → verify + mode.
 * Every presented gain is ≤ the ceiling (Arch §9.1). Cues go out through [sink] with ttl 1000 ms (PROTOCOL.md).
 */
class CalibrationSession(
    ceilingDb: Int,
    private val sink: (CueCommand) -> Unit,
    private val random: Random = Random.Default,
    private val staircaseFactory: (ceilingDb: Int) -> Staircase = { Staircase(ceilingDb = it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    val ceilingDb: Int = ceilingDb
    private val ceil = minOf(ceilingDb, HARD_CEILING_DB)

    var step = CalStep.FIT; private set
    private var deviceId = ""
    private var fitNote = ""

    // threshold phase
    private val order: List<Pair<Ear, Int>> = Ear.entries.flatMap { e -> CalBand.HZ.indices.map { e to it } }
    private var orderIdx = 0
    private var stair: Staircase? = null
    private val thr = HashMap<Pair<Ear, Int>, Int?>()

    val ear: Ear? get() = if (step == CalStep.THRESHOLD) order[orderIdx].first else null
    val bandHz: Int? get() = if (step == CalStep.THRESHOLD) CalBand.HZ[order[orderIdx].second] else null
    val currentLevelDb: Int? get() = stair?.levelDb

    // level / balance / verify
    var sensationLevelDb = 15; private set
    var balanceDb = 0; private set // + = right louder
    private val balanceConfirms = mutableListOf<Int>()
    private var balanceNextEar = Ear.LEFT
    private var verifyTarget: Ear? = null
    var verifyTrials = 0; private set
    var verifyCorrect = 0; private set
    private var mode: HearingMode? = null
    private var result: HearingProfile? = null

    fun setFit(deviceId: String, weakerEar: Ear?, note: String = "") {
        check(step == CalStep.FIT)
        this.deviceId = deviceId
        fitNote = listOfNotNull(note.ifBlank { null }, weakerEar?.let { "weaker=$it" }).joinToString("; ")
        step = CalStep.THRESHOLD
        startStair()
    }

    private fun startStair() {
        stair = staircaseFactory(ceil)
        replay()
    }

    /** Re-presents the current stimulus. */
    fun replay() {
        when (step) {
            CalStep.THRESHOLD -> {
                val (e, b) = order[orderIdx]
                val lvl = stair!!.levelDb
                sink(cue(CueId.CAL_TONE_500 + b, e, lvl))
            }
            CalStep.BALANCE -> {
                val e = balanceNextEar
                balanceNextEar = if (e == Ear.LEFT) Ear.RIGHT else Ear.LEFT
                sink(cue(CueId.CAL_OBSTACLE, e, balancedLevel(e)))
            }
            CalStep.VERIFY -> verifyTarget?.let { sink(cue(CueId.CAL_OBSTACLE, it, balancedLevel(it))) }
            else -> Unit
        }
    }

    fun answerHeard(heard: Boolean) {
        check(step == CalStep.THRESHOLD)
        val s = stair!!
        s.answer(heard)
        if (!s.done) { replay(); return }
        val key = order[orderIdx]
        thr[key] = s.thresholdDb // null = unusable: no more energy is pushed into that side
        if (s.unusable) {
            // A side that cannot hear one band at the ceiling is unusable; skip its remaining bands.
            while (orderIdx + 1 < order.size && order[orderIdx + 1].first == key.first) { orderIdx++; thr[order[orderIdx]] = null }
        }
        orderIdx++
        if (orderIdx < order.size) { startStair() } else { stair = null; step = CalStep.LEVEL }
    }

    fun setSensationLevel(db: Int) {
        check(step == CalStep.LEVEL)
        require(db in 10..20) { "Arch §9: sensation level 10–20 dB" }
        sensationLevelDb = db
        val l = Ear.LEFT.usable(); val r = Ear.RIGHT.usable()
        when {
            !l && !r -> finish(HearingMode.HAPTIC_ONLY)
            !l || !r -> finish(HearingMode.SSD) // one side cannot be detected at the ceiling
            else -> { step = CalStep.BALANCE; replay() }
        }
    }

    fun nudgeBalance(deltaDb: Int) {
        check(step == CalStep.BALANCE)
        balanceDb = (balanceDb + deltaDb).coerceIn(-12, 12)
        replay()
    }

    /** Confirm twice (Arch §9 "repeat once to check consistency"); the result is the mean of both settings. */
    fun confirmBalance() {
        check(step == CalStep.BALANCE)
        balanceConfirms += balanceDb
        if (balanceConfirms.size < 2) { replay(); return }
        balanceDb = Math.round(balanceConfirms.average()).toInt()
        step = CalStep.VERIFY
        nextVerify()
    }

    private fun nextVerify() {
        verifyTarget = if (random.nextBoolean()) Ear.LEFT else Ear.RIGHT
        replay()
    }

    fun answerSide(answer: Ear) {
        check(step == CalStep.VERIFY)
        verifyTrials++
        if (answer == verifyTarget) verifyCorrect++
        if (verifyTrials < VERIFY_TRIALS) { nextVerify(); return }
        // Stereo only if ≥ 4/5 correct and no side needs the ceiling (Arch §9).
        val atCeiling = Ear.entries.any { e -> CalBand.HZ.indices.any { b -> rawLevel(e, b)!! >= ceil } }
        finish(if (verifyCorrect >= 4 && !atCeiling) HearingMode.STEREO else HearingMode.SSD)
    }

    fun chooseHapticOnly() { finish(HearingMode.HAPTIC_ONLY) }

    fun result(): HearingProfile? = result

    private fun finish(m: HearingMode) {
        mode = m
        step = CalStep.DONE
        stair = null
        val offL = -balanceDb / 2
        val offR = balanceDb - balanceDb / 2
        result = HearingProfile(
            deviceId = deviceId, createdAtMs = nowMs(), mode = m, ceilingDb = ceilingDb,
            bands = CalBand.HZ.mapIndexed { b, hz ->
                BandProfile(hz, thr[Ear.LEFT to b], thr[Ear.RIGHT to b], sensationLevelDb, offL, offR)
            },
            hapticGainL = 200, hapticGainR = 200, hapticMax = 255,
            verifiedTrials = verifyTrials, verifiedCorrect = verifyCorrect, fitNote = fitNote,
        )
    }

    private fun Ear.usable() = CalBand.HZ.indices.all { thr[this to it] != null }

    private fun rawLevel(e: Ear, band: Int): Int? = thr[e to band]?.let { it + sensationLevelDb }

    private fun balancedLevel(e: Ear): Int {
        val base = rawLevel(e, CalBand.B1K)!!
        val off = if (e == Ear.LEFT) -balanceDb / 2 else balanceDb - balanceDb / 2
        return minOf(base + off, ceil)
    }

    private fun cue(id: Int, e: Ear, levelDb: Int): CueCommand {
        val g = minOf(levelDb, ceil) // ceiling first: no step can exceed it
        return CueCommand(
            cueId = id, side = if (e == Ear.LEFT) Side.L else Side.R,
            gainLDb = if (e == Ear.LEFT) g else GAIN_MUTE, gainRDb = if (e == Ear.RIGHT) g else GAIN_MUTE,
            hapticPattern = Haptic.NONE, hapticL = 0, hapticR = 0, flags = 1, ttlMs = CAL_TTL_MS,
        )
    }

    companion object {
        const val VERIFY_TRIALS = 5
        const val CAL_TTL_MS = 1000
    }
}
