package org.determine.android.power

/**
 * Phone battery → "battery critical" flag for the engine (Arch §4.2: phone battery critical → UNAVAILABLE).
 *
 * Android has no public "critical" broadcast (ACTION_BATTERY_LOW fires at the ~15 % "low" warning), so the
 * phone is critical at ≤ [enterPct] % while not charging. It recovers above [exitPct] % or as soon as it is
 * charging. The hysteresis band stops the system flapping between READY and UNAVAILABLE around one percent
 * value. Thresholds are PROPOSED (the phone must still have energy to announce "system off").
 */
class BatteryCriticalGate(
    private val enterPct: Int = 5,
    private val exitPct: Int = 8,
) {
    init {
        require(exitPct > enterPct) { "exit threshold must be above enter threshold" }
    }

    var critical: Boolean = false
        private set

    /**
     * Feed the latest battery sample. [levelPct] < 0 means unknown (no battery / not reported): unknown
     * does not raise the flag on its own, because Android reports it for mains-powered devices.
     * Returns true when [critical] changed.
     */
    fun update(levelPct: Int, charging: Boolean): Boolean {
        val next = when {
            levelPct < 0 -> critical
            charging -> false
            critical -> levelPct <= exitPct
            else -> levelPct <= enterPct
        }
        val changed = next != critical
        critical = next
        return changed
    }
}
