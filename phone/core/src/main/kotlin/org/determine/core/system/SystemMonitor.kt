package org.determine.core.system

import org.determine.core.SystemState

data class MonitorConfig(
    val frameMaxAgeUs: Long = 500_000,
    val beatTimeoutUs: Long = 500_000,
    val rangeMaxAgeUs: Long = 500_000,
    /** Consecutive cues/profiles without a good ACK (missing within 300 ms, or NACK) → UNAVAILABLE. */
    val maxAckFailures: Int = 3,
    /** STARTING that never becomes ready is a failure too: fail loud rather than stay silent forever. */
    val startupTimeoutUs: Long = 10_000_000,
)

/** Snapshot of everything the state machine needs. Ages are null when never received. */
data class MonitorInputs(
    val nowUs: Long,
    val camBeatAgeUs: Long?,
    val cueBeatAgeUs: Long?,
    /** Age (offset-corrected capture → now) of the newest complete frame; null if none or no clock sync. */
    val frameAgeUs: Long?,
    val rangeAgeUs: Long?,
    /** The forward ToF zone's last status was known (VALID or NO_TARGET), not UNKNOWN. */
    val tofKnown: Boolean,
    val clocksSynced: Boolean,
    val cueLinkConfirmed: Boolean,
    val ackFailures: Int,
    val batteryCritical: Boolean,
    val paused: Boolean,
    /** False in Tier 0 (phone camera): no camera board exists, so its BEAT is not required. Frames still are. */
    val camBoardRequired: Boolean = true,
    /** The phone's headset sink reported AUDIO_ROUTE_LOST (no Bluetooth bone-conduction route): definitive, no grace. */
    val audioRouteLost: Boolean = false,
)

/**
 * Arch §4.2 runtime state machine.
 *  STARTING → READY when frames fresh ∧ ToF ok ∧ cue ack (and clocks synced, needed to judge freshness).
 *  READY ⇄ DEGRADED on ToF stale or UNKNOWN status.
 *  READY/DEGRADED → UNAVAILABLE on frame age > 500 ms, BEAT missing > 500 ms from either board, ACK failures,
 *  battery critical. The camera being down is UNAVAILABLE, never ToF-only.
 *  PAUSED only on explicit request; resuming re-enters STARTING.
 */
class SystemMonitor(val config: MonitorConfig = MonitorConfig()) {
    var state = SystemState.STARTING; private set
    var reason = "boot"; private set
    private var startingSinceUs: Long? = null

    data class Transition(val from: SystemState, val to: SystemState, val reason: String)

    /** First failure reason, or null when every liveness condition holds. */
    fun failure(i: MonitorInputs): String? = when {
        i.batteryCritical -> "battery_critical"
        i.audioRouteLost -> "headset_route_lost"
        i.camBoardRequired && (i.camBeatAgeUs == null || i.camBeatAgeUs > config.beatTimeoutUs) -> "cam_beat_missing"
        i.cueBeatAgeUs == null || i.cueBeatAgeUs > config.beatTimeoutUs -> "cue_beat_missing"
        i.ackFailures >= config.maxAckFailures -> "cue_ack_missing"
        !i.clocksSynced -> "clock_unsynced"
        i.frameAgeUs == null || i.frameAgeUs > config.frameMaxAgeUs -> "frame_stale"
        else -> null
    }

    /** ToF problem reason (DEGRADED), or null when ToF is usable. */
    fun tofProblem(i: MonitorInputs): String? = when {
        i.rangeAgeUs == null || i.rangeAgeUs > config.rangeMaxAgeUs -> "range_stale"
        !i.tofKnown -> "tof_unknown"
        else -> null
    }

    fun evaluate(i: MonitorInputs): Transition? {
        if (startingSinceUs == null) startingSinceUs = i.nowUs
        if (i.paused) return go(SystemState.PAUSED, "user_pause")
        val fail = failure(i)
        val tof = tofProblem(i)
        return when (state) {
            SystemState.PAUSED -> { startingSinceUs = i.nowUs; go(SystemState.STARTING, "resumed") }
            SystemState.STARTING -> when {
                fail == null && i.cueLinkConfirmed && tof == null -> go(SystemState.READY, "all_ok")
                fail == null && i.cueLinkConfirmed && tof == "tof_unknown" -> go(SystemState.DEGRADED, tof)
                i.audioRouteLost -> go(SystemState.UNAVAILABLE, "headset_route_lost")
                i.nowUs - startingSinceUs!! > config.startupTimeoutUs ->
                    go(SystemState.UNAVAILABLE, "startup_timeout:" + (fail ?: if (!i.cueLinkConfirmed) "cue_ack_missing" else tof))
                else -> null
            }
            SystemState.READY, SystemState.DEGRADED -> when {
                fail != null -> go(SystemState.UNAVAILABLE, fail)
                tof != null -> go(SystemState.DEGRADED, tof)
                else -> go(SystemState.READY, "all_ok")
            }
            SystemState.UNAVAILABLE -> when {
                fail != null || !i.cueLinkConfirmed -> null
                tof != null -> go(SystemState.DEGRADED, "recovered:$tof")
                else -> go(SystemState.READY, "recovered")
            }
        }
    }

    private fun go(to: SystemState, why: String): Transition? {
        if (to == state) return null
        val t = Transition(state, to, why)
        state = to
        reason = why
        return t
    }
}
