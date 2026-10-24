package org.determine.core.range

import org.determine.core.Region

/** Zone classes per PROTOCOL.md RANGE status: 5/9 valid, 255 NO_TARGET, anything else UNKNOWN (never "far"). */
object ZoneStatus {
    const val NO_TARGET = 255
    fun isValid(s: Int) = s == 5 || s == 9
}

/**
 * Which of the 64 zones (index = row*8+col, row 0 top, col 0 wearer's left) belong to each region.
 * Configurable because the useful rows depend on sensor pitch and mounting; the default keeps the rows from
 * head to chest and excludes the bottom rows that see the floor.
 */
class RegionMasks(private val masks: Map<Region, Set<Int>>) {
    init {
        require(masks.keys == Region.entries.toSet()) { "every region needs a mask" }
        masks.values.forEach { z -> require(z.isNotEmpty() && z.all { it in 0..63 }) { "bad zone set" } }
    }

    fun zones(r: Region): Set<Int> = masks.getValue(r)

    companion object {
        /** rows: inclusive row range used; corridor: inclusive central columns; flanks are the remaining columns. */
        fun of(rows: IntRange = 0..5, corridor: IntRange = 2..5): RegionMasks {
            require(rows.first >= 0 && rows.last <= 7 && corridor.first >= 1 && corridor.last <= 6)
            fun cells(cols: IntRange) = rows.flatMap { r -> cols.map { c -> r * 8 + c } }.toSet()
            return RegionMasks(
                mapOf(
                    Region.LEFT to cells(0 until corridor.first),
                    Region.CENTER to cells(corridor),
                    Region.RIGHT to cells(corridor.last + 1..7),
                ),
            )
        }

        val DEFAULT = of()
    }
}

/** Result for one region. */
sealed class RegionRange {
    /** Nearest valid target in mm. */
    data class Target(val minMm: Int) : RegionRange()
    /** Sensor positively saw nothing (only NO_TARGET and no valid returns). Not "clear" — just nothing to report. */
    object NothingSeen : RegionRange()
    /** Every zone invalid: we do not know. Never treated as clear. */
    object Unknown : RegionRange()
}

data class RangeReading(
    val regions: Map<Region, RegionRange>,
    /** Fraction of zones in the corridor with an UNKNOWN status (confidence input for DEGRADED). */
    val corridorUnknownFraction: Float,
) {
    fun of(r: Region): RegionRange = regions.getValue(r)
}

class RangeCorridor(var masks: RegionMasks = RegionMasks.DEFAULT) {
    fun evaluate(mm: IntArray, status: IntArray): RangeReading {
        require(mm.size == 64 && status.size == 64)
        val out = HashMap<Region, RegionRange>()
        var corridorUnknown = 0
        for (r in Region.entries) {
            var min = Int.MAX_VALUE
            var anyNoTarget = false
            var unknown = 0
            for (z in masks.zones(r)) {
                val s = status[z]
                when {
                    ZoneStatus.isValid(s) -> if (mm[z] < min) min = mm[z]
                    s == ZoneStatus.NO_TARGET -> anyNoTarget = true
                    else -> unknown++
                }
            }
            if (r == Region.CENTER) corridorUnknown = unknown
            out[r] = when {
                min != Int.MAX_VALUE -> RegionRange.Target(min)
                anyNoTarget -> RegionRange.NothingSeen
                else -> RegionRange.Unknown
            }
        }
        return RangeReading(out, corridorUnknown.toFloat() / masks.zones(Region.CENTER).size)
    }
}
