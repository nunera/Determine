package org.determine.app.audio

import kotlin.math.PI
import kotlin.math.sin

/** Phone-side alarm sound and speech settings, shared by the Android and desktop alert outputs. */
object PhoneSounds {
    const val SYSTEM_OFF_TEXT = "System off"

    /** Spearcons: sped-up speech (Arch §8.2). System-off and descriptions use normal rate. */
    const val SPEARCON_RATE = 1.8f

    const val OFF_TONE_RATE = 22_050

    /**
     * Three descending notes (Arch §8 "System: descending three-note"), each 150 ms with 100 ms gaps,
     * mono 16-bit at [OFF_TONE_RATE], 5 ms linear attack/release to avoid clicks.
     */
    fun systemOffTone(rate: Int = OFF_TONE_RATE): ShortArray {
        val noteSamples = rate * 150 / 1000
        val gapSamples = rate * 100 / 1000
        val edge = rate * 5 / 1000
        val freqs = doubleArrayOf(988.0, 784.0, 587.0)
        val pcm = ShortArray(freqs.size * (noteSamples + gapSamples))
        var i = 0
        for (f in freqs) {
            for (n in 0 until noteSamples) {
                val env = minOf(1.0, n.toDouble() / edge, (noteSamples - n).toDouble() / edge)
                pcm[i++] = (sin(2 * PI * f * n / rate) * env * 0.8 * Short.MAX_VALUE).toInt().toShort()
            }
            i += gapSamples
        }
        return pcm
    }
}
