package org.determine.android.camera

import java.nio.ByteBuffer

/**
 * Pure helpers for the Tier 0 phone camera: YUV_420_888 planes → upright NV21 (for YuvImage → JPEG) and the
 * camera-timestamp → phone-clock mapping. No Android dependencies, so they are unit-tested on the JVM.
 */
object Nv21 {
    /** Output size after rotating a [w]×[h] image by [rotationDegrees], cropped to even sides (NV21 needs 2×2 chroma). */
    fun outputSize(w: Int, h: Int, rotationDegrees: Int): Pair<Int, Int> {
        val ew = w and 1.inv()
        val eh = h and 1.inv()
        return if (normalise(rotationDegrees) % 180 == 0) ew to eh else eh to ew
    }

    fun normalise(rotationDegrees: Int): Int {
        val r = ((rotationDegrees % 360) + 360) % 360
        require(r % 90 == 0) { "rotation must be a multiple of 90, got $rotationDegrees" }
        return r
    }

    /**
     * Packs YUV_420_888 planes (any row/pixel stride, as CameraX delivers them) into an NV21 buffer of
     * [outputSize], rotated clockwise by [rotationDegrees] so the picture is upright. [out] is reused when large
     * enough. Absolute buffer reads only: plane positions are not modified.
     */
    fun fromYuv420(
        w: Int,
        h: Int,
        y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
        rotationDegrees: Int,
        out: ByteArray? = null,
    ): ByteArray {
        val rot = normalise(rotationDegrees)
        val sw = w and 1.inv()
        val sh = h and 1.inv()
        val (ow, oh) = outputSize(w, h, rot)
        val size = ow * oh * 3 / 2
        val dst = if (out != null && out.size >= size) out else ByteArray(size)
        // Luma.
        for (sy in 0 until sh) {
            val rowBase = sy * yRowStride
            for (sx in 0 until sw) {
                val d = dest(sx, sy, sw, sh, rot, ow)
                dst[d] = y.get(rowBase + sx * yPixelStride)
            }
        }
        // Chroma, one V/U pair per 2×2 block, interleaved V first (NV21).
        val cw = sw / 2
        val ch = sh / 2
        val ocw = ow / 2
        val base = ow * oh
        for (cy in 0 until ch) {
            val rowBase = cy * uvRowStride
            for (cx in 0 until cw) {
                val src = rowBase + cx * uvPixelStride
                val d = base + 2 * dest(cx, cy, cw, ch, rot, ocw)
                dst[d] = v.get(src)
                dst[d + 1] = u.get(src)
            }
        }
        return dst
    }

    /** Index in a row-major plane of width [ow] of source pixel ([x], [y]) of a [w]×[h] plane rotated clockwise. */
    private fun dest(x: Int, y: Int, w: Int, h: Int, rot: Int, ow: Int): Int = when (rot) {
        0 -> y * ow + x
        90 -> x * ow + (h - 1 - y)
        180 -> (h - 1 - y) * ow + (w - 1 - x)
        else -> (w - 1 - x) * ow + y // 270
    }

    /**
     * Maps a camera timestamp onto the engine clock (`SystemClock.elapsedRealtimeNanos()/1000` µs).
     * Camera2 reports SENSOR_INFO_TIMESTAMP_SOURCE REALTIME (elapsedRealtime base) on most devices, but UNKNOWN
     * sources commonly use the monotonic/uptime base (`System.nanoTime()`, which stops in deep sleep). The base is
     * whichever "now" the timestamp is closer to; the result never lies in the future, and a genuinely old frame keeps
     * its old time (so the engine's freshness gate rejects it). A timestamp within [unknownBaseNs] of neither base has
     * an unknown time base: it is mapped to arrival time minus [fallbackPipelineNs], which overstates rather than
     * understates the age of a frame that has just been delivered.
     */
    fun capturePhoneUs(
        timestampNs: Long,
        realtimeNowNs: Long,
        monotonicNowNs: Long,
        unknownBaseNs: Long = 10_000_000_000L,
        fallbackPipelineNs: Long = 100_000_000L,
    ): Long {
        val ageRealtime = realtimeNowNs - timestampNs
        val ageMonotonic = monotonicNowNs - timestampNs
        val ageNs = if (kotlin.math.abs(ageRealtime) <= kotlin.math.abs(ageMonotonic)) ageRealtime else ageMonotonic
        if (kotlin.math.abs(ageNs) > unknownBaseNs) return (realtimeNowNs - fallbackPipelineNs) / 1000
        return (realtimeNowNs - ageNs.coerceAtLeast(0)) / 1000
    }
}
