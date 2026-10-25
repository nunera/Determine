package org.determine.core.range

import org.determine.core.wire.RangeStatus

/**
 * The cue board's single forward ToF zone (VL53L4CD, one Modulino Distance ahead of the wearer). There is no
 * grid, no left/right split and no mounting transform: anything to the sides is simply not measured, and the
 * caller must present it as unavailable — never as far.
 */
sealed class ForwardRange {
    /** Forward distance in mm from a VALID status. */
    data class Target(val mm: Int) : ForwardRange()

    /** The sensor positively saw nothing (NO_TARGET). Not "clear" — just nothing to report. */
    object NothingSeen : ForwardRange()

    /** Invalid/error status: we do not know. Never treated as clear, never as far. */
    object Unknown : ForwardRange()
}

object ForwardTo {
    /** Canonical status (PROTOCOL.md v2 RANGE) → reading. Unknown statuses stay UNKNOWN. */
    fun reading(status: Int, mm: Int): ForwardRange = when (status) {
        RangeStatus.VALID -> ForwardRange.Target(mm)
        RangeStatus.NO_TARGET -> ForwardRange.NothingSeen
        else -> ForwardRange.Unknown
    }
}
