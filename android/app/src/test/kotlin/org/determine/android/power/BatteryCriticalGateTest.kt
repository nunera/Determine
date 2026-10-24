package org.determine.android.power

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryCriticalGateTest {
    private val gate = BatteryCriticalGate(enterPct = 5, exitPct = 8)

    @Test
    fun entersAtThresholdWhenNotCharging() {
        assertFalse(gate.update(6, charging = false))
        assertFalse(gate.critical)
        assertTrue(gate.update(5, charging = false))
        assertTrue(gate.critical)
    }

    @Test
    fun hysteresisPreventsFlapping() {
        gate.update(5, charging = false)
        assertFalse(gate.update(6, charging = false))
        assertFalse(gate.update(8, charging = false))
        assertTrue(gate.critical)
        assertTrue(gate.update(9, charging = false))
        assertFalse(gate.critical)
    }

    @Test
    fun chargingClearsImmediately() {
        gate.update(3, charging = false)
        assertTrue(gate.update(3, charging = true))
        assertFalse(gate.critical)
        // Unplugging at 3 % re-enters.
        assertTrue(gate.update(3, charging = false))
    }

    @Test
    fun unknownLevelKeepsCurrentState() {
        assertFalse(gate.update(-1, charging = false))
        assertFalse(gate.critical)
        gate.update(4, charging = false)
        assertFalse(gate.update(-1, charging = false))
        assertTrue(gate.critical)
    }
}
