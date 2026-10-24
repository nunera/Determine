package org.determine.core.clock

/**
 * Per-board NTP-style clock estimate (PROTOCOL.md PING/PONG):
 *   offset = ((rx − t1) + (tx − t4)) / 2   (board − phone)
 *   rtt    = (t4 − t1) − (tx − rx)
 * The offset comes from the lowest-RTT sample in a sliding window, which rejects samples inflated by
 * queueing delay. Samples with negative or excessive RTT, or echoing a phone time we never sent, are dropped.
 */
class ClockSync(
    private val windowSize: Int = 32,
    private val maxRttUs: Long = 200_000,
    /** No accepted sample for this long → not synced (a dead link must not keep a stale offset forever). */
    private val staleAfterUs: Long = 2_000_000,
) {
    data class Sample(val offsetUs: Long, val rttUs: Long, val atPhoneUs: Long)

    private val window = ArrayDeque<Sample>()
    private var lastPingSentUs = Long.MIN_VALUE
    private var firstPingSentUs = Long.MIN_VALUE
    var rejected = 0L; private set

    /** Records an outgoing PING so stale or forged echoes can be rejected. */
    fun onPingSent(tPhoneUs: Long) {
        if (firstPingSentUs == Long.MIN_VALUE) firstPingSentUs = tPhoneUs
        lastPingSentUs = tPhoneUs
    }

    /** Returns the accepted sample or null if rejected as an outlier. */
    fun onPong(t1: Long, rx: Long, tx: Long, t4: Long): Sample? {
        val rtt = (t4 - t1) - (tx - rx)
        val valid = firstPingSentUs != Long.MIN_VALUE && t1 >= firstPingSentUs && t1 <= lastPingSentUs &&
            t4 >= t1 && tx >= rx && rtt >= 0 && rtt <= maxRttUs
        if (!valid) { rejected++; return null }
        val offset = ((rx - t1) + (tx - t4)) / 2
        val s = Sample(offset, rtt, t4)
        window.addLast(s)
        while (window.size > windowSize) window.removeFirst()
        return s
    }

    /** Forget everything, e.g. after the board rebooted (its clock restarted). */
    fun reset() {
        window.clear()
    }

    private fun best(): Sample? = window.minByOrNull { it.rttUs }

    fun synced(nowPhoneUs: Long): Boolean {
        val last = window.lastOrNull() ?: return false
        return nowPhoneUs - last.atPhoneUs <= staleAfterUs
    }

    val offsetUs: Long? get() = best()?.offsetUs
    val rttUs: Long? get() = best()?.rttUs

    fun toBoard(phoneUs: Long): Long? = best()?.let { phoneUs + it.offsetUs }
    fun toPhone(boardUs: Long): Long? = best()?.let { boardUs - it.offsetUs }
}
