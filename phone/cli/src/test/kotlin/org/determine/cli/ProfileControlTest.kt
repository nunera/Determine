package org.determine.cli

import org.determine.core.Alert
import org.determine.core.Diagnostics
import org.determine.core.Engine
import org.determine.core.EngineConfig
import org.determine.core.EngineListener
import org.determine.core.LocalCueSink
import org.determine.core.Outbox
import org.determine.core.PhoneAlerts
import org.determine.core.SystemState
import org.determine.core.hearing.HearingMode
import org.determine.core.wire.AckStatus
import org.determine.core.wire.ProfilePkt
import org.determine.core.wire.Wire
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.DatagramSocket
import java.net.InetSocketAddress

class ProfileControlTest {
    private val output = ByteArrayOutputStream()
    private val profiles = mutableListOf<ProfilePkt>()
    private val engine = Engine(
        EngineConfig(),
        Outbox { _, bytes -> (Wire.decode(bytes).packet as? ProfilePkt)?.let { profiles += it } },
        object : PhoneAlerts {
            override fun speak(text: String) {}
            override fun vibrate(pattern: LongArray) {}
            override fun systemOff() {}
        },
        object : EngineListener {
            override fun onState(state: SystemState, reason: String) {}
            override fun onAlert(alert: Alert?) {}
            override fun onDiagnostics(d: Diagnostics) {}
        },
        LocalCueSink { _, _ -> AckStatus.APPLIED },
    )
    private val controls = ProfileControl(engine, EventLog(PrintStream(output), nowUs()))

    @Test
    fun `valid control changes real engine and next wire profile without replacing engine`() {
        engine.tick(1_000_000)
        profiles.clear()
        controls.line("PROFILE 7 HAPTIC_ONLY -35 96")
        assertEquals(HearingMode.HAPTIC_ONLY, engine.profile.mode)
        assertEquals(-35, engine.profile.ceilingDb)
        assertEquals(96, engine.profile.hapticMax)
        assertTrue(output.toString().contains("request=7 status=applied mode=HAPTIC_ONLY ceiling_db=-35 haptic_max=96"))
        engine.tick(1_010_000)
        assertEquals(listOf(ProfilePkt(HearingMode.HAPTIC_ONLY.wire, 96)), profiles)
    }

    @Test
    fun `invalid control preserves applied profile and does not dirty wire state`() {
        controls.line("PROFILE 1 SSD -30 120")
        engine.tick(1_000_000)
        profiles.clear()
        val previous = engine.profile
        for (line in listOf(
            "PROFILE 2 INVALID -30 96", "PROFILE 3 SSD -9 96", "PROFILE 4 SSD -61 96",
            "PROFILE 5 SSD -30 0", "PROFILE 6 SSD -30 256", "PROFILE 7 SSD -30 -1",
            "PROFILE 8 SSD -30 1.5", "PROFILE 9 SSD -30 999999999999999999999",
            "PROFILE 10 SSD -30", "PROFILE -1 SSD -30 96", "PROFILE 11 SSD -30 96 extra",
            "X".repeat(161),
        )) {
            output.reset()
            controls.line(line)
            assertSame(previous, engine.profile, line)
            assertTrue(output.toString().contains("status=rejected"), line)
            assertFalse(output.toString().contains("status=applied"), line)
        }
        engine.tick(1_010_000)
        assertTrue(profiles.isEmpty())
    }

    @Test
    fun `CLI stdin opt in is bounded and recovers after oversized input`() {
        val stdin = ByteArrayInputStream(("X".repeat(100_000) + "\nPROFILE 12 HAPTIC_ONLY -40 80\n").toByteArray())
        DatagramSocket(0).use { cue ->
            val options = CliOptions(port = 0, cue = InetSocketAddress("127.0.0.1", cue.localPort),
                durationS = 0.5, mode = HearingMode.HAPTIC_ONLY, hapticMax = 96, controlStdin = true)
            assertEquals(0, run(options, PrintStream(output), stdin))
            val log = output.toString()
            assertTrue(log.contains("request=0 status=applied mode=HAPTIC_ONLY ceiling_db=-20 haptic_max=96"))
            assertTrue(log.contains("status=rejected error=control_line_too_long"))
            assertTrue(log.contains("request=12 status=applied mode=HAPTIC_ONLY ceiling_db=-40 haptic_max=80"))
            assertTrue(log.lineSequence().any { it.startsWith("PROFILE-TX ") && it.contains("haptic_max=80") })
        }
    }

    @Test
    fun `CLI controls require valid initial bounds without changing normal CLI ceiling policy`() {
        assertThrows(IllegalArgumentException::class.java) { parseArgs(arrayOf("--control-stdin", "--ceiling", "-9")) }
        assertThrows(IllegalArgumentException::class.java) { parseArgs(arrayOf("--haptic-max", "0")) }
        assertEquals(-9, parseArgs(arrayOf("--ceiling", "-9")).ceilingDb)
        val options = parseArgs(arrayOf("--control-stdin", "--haptic-max", "1", "--ceiling", "-60"))
        assertTrue(options.controlStdin)
        assertEquals(1, options.hapticMax)
        assertEquals(-60, options.ceilingDb)
    }
}
