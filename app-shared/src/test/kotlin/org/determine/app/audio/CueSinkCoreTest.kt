package org.determine.app.audio

import org.determine.core.CueCommand
import org.determine.core.GAIN_MUTE
import org.determine.core.hearing.HearingProfile
import org.determine.core.wire.AckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueSinkCoreTest {
    @Test
    fun failedPcmOpenCannotAcknowledgeEvenAMutedProbe() {
        val sink = sink(TestOutput("no system audio device"))
        try {
            assertEquals("no system audio device", sink.error)
            assertEquals(CueSinkCore.FAILED, sink.play(probe(), 0))
            assertEquals(CueSinkCore.FAILED, sink.play(probe().copy(gainLDb = -30, gainRDb = -30), 1))
            assertNull("desktop must never invent a verified Bluetooth route", sink.headsetInfo())
        } finally {
            sink.close()
        }
    }

    @Test
    fun openPcmLineCanAcknowledgeProbeButDoesNotVerifyHeadset() {
        val sink = sink(TestOutput(null))
        try {
            assertNull(sink.error)
            assertEquals(AckStatus.APPLIED, sink.play(probe(), 0))
            assertNull("a system audio device is not a verified headset", sink.headsetInfo())
        } finally {
            sink.close()
        }
        assertEquals(CueSinkCore.FAILED, sink.play(probe(), 1))
    }

    private fun sink(output: PcmOutput) = CueSinkCore(
        profile = { HearingProfile.uncalibrated() },
        out = output,
        uptimeMs = { System.nanoTime() / 1_000_000 },
    )

    private fun probe() = CueCommand(
        cueId = CueBank.READY,
        side = CueBank.SIDE_BOTH,
        gainLDb = GAIN_MUTE,
        gainRDb = GAIN_MUTE,
        hapticPattern = 0,
        hapticL = 0,
        hapticR = 0,
        flags = CueBank.FLAG_INTERRUPT,
        ttlMs = 0,
    )

    private class TestOutput(private val openError: String?) : PcmOutput {
        override val sampleRate = 48_000
        override fun open(): String? = openError
        override fun play() = error("muted probes must not start playback")
        override fun write(pcm: ShortArray): Boolean = error("muted probes must not render samples")
        override fun pauseAndFlush() = Unit
        override fun release() = Unit
    }
}
