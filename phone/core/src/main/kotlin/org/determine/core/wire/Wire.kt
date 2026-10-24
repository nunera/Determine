package org.determine.core.wire

import org.determine.core.BoardId

/**
 * Codec for PROTOCOL.md v1 (frozen). Little-endian, 8-byte header, no padding.
 * u32 fields are carried in Long (0..2^32-1); u64 fields in Long (two's-complement bit pattern).
 */
class WireException(message: String) : Exception(message)

const val MAGIC = 0x5444
const val VERSION = 1
const val HEADER_LEN = 8
const val MAX_CHUNK = 1400
const val GAIN_MUTE = -128

object PacketType {
    const val BEAT = 0x01
    const val RANGE = 0x02
    const val FRAME = 0x03
    const val PING = 0x10
    const val PONG = 0x11
    const val CUE = 0x20
    const val ACK = 0x21
    const val PROFILE = 0x22
}

object CueId {
    const val READY = 0
    const val OBSTACLE_FAR = 1
    const val OBSTACLE_MID = 2
    const val OBSTACLE_NEAR = 3
    const val PERSON_FAR = 4
    const val PERSON_MID = 5
    const val PERSON_NEAR = 6
    const val SYSTEM_OFF = 7
    const val CAL_TONE_500 = 8
    const val CAL_TONE_1K = 9
    const val CAL_TONE_2K = 10
    const val CAL_OBSTACLE = 11
}

object Side { const val L = 0; const val R = 1; const val BOTH = 2 }

object Haptic { const val NONE = 0; const val PULSE40 = 1; const val PULSE60 = 2; const val BUZZ150 = 3; const val LSS = 4 }

object AckStatus {
    const val PLAYED = 0; const val EXPIRED = 1; const val PLAYED_CLAMPED = 2
    const val NO_SYNC = 3; const val BAD_CUE = 4; const val BUSY_DROPPED = 5
}

object Fault {
    const val SENSOR_INIT_FAIL = 1 shl 0
    const val SENSOR_READ_ERR = 1 shl 1
    const val I2S_ERR = 1 shl 2
    const val HAPTIC_L_ERR = 1 shl 3
    const val HAPTIC_R_ERR = 1 shl 4
    const val BROWNOUT_SEEN = 1 shl 5
    const val NO_CLOCK_SYNC = 1 shl 6
    const val WATCHDOG_ACTIVE = 1 shl 7
    const val LOW_HEAP = 1 shl 8
    const val CAPTURE_FAIL = 1 shl 9
}

sealed class Packet { abstract val type: Int }

data class Beat(
    val board: Int, val resetReason: Int, val faults: Int, val tUs: Long,
    val rssiDbm: Int, val tempC: Int, val heapKb: Int,
) : Packet() { override val type get() = PacketType.BEAT }

data class RangePkt(val tUs: Long, val mm: IntArray, val status: IntArray, val res: Int = 64) : Packet() {
    override val type get() = PacketType.RANGE
    override fun equals(other: Any?) = other is RangePkt && tUs == other.tUs && res == other.res &&
        mm.contentEquals(other.mm) && status.contentEquals(other.status)
    override fun hashCode() = tUs.hashCode() * 31 + mm.contentHashCode()
}

data class FramePkt(
    val frameId: Long, val tCapUs: Long, val w: Int, val h: Int, val totalLen: Long, val offset: Long,
    val chunkIdx: Int, val chunkCount: Int, val data: ByteArray,
) : Packet() {
    override val type get() = PacketType.FRAME
    override fun equals(other: Any?) = other is FramePkt && frameId == other.frameId && tCapUs == other.tCapUs &&
        w == other.w && h == other.h && totalLen == other.totalLen && offset == other.offset &&
        chunkIdx == other.chunkIdx && chunkCount == other.chunkCount && data.contentEquals(other.data)
    override fun hashCode() = (frameId * 31 + offset).hashCode() * 31 + data.contentHashCode()
}

data class Ping(val tPhoneUs: Long) : Packet() { override val type get() = PacketType.PING }

data class Pong(val tPhoneUs: Long, val tBoardRxUs: Long, val tBoardTxUs: Long) : Packet() {
    override val type get() = PacketType.PONG
}

data class CuePkt(
    val tIssuedPhoneUs: Long, val deadlineBoardUs: Long, val cueId: Int, val side: Int,
    val gainLDb: Int, val gainRDb: Int, val hapticPattern: Int, val hapticL: Int, val hapticR: Int,
    val flags: Int, val ttlMs: Int,
) : Packet() { override val type get() = PacketType.CUE }

data class Ack(
    val cueSeq: Long, val status: Int, val appliedGainLDb: Int, val appliedGainRDb: Int, val tBoardUs: Long,
) : Packet() { override val type get() = PacketType.ACK }

data class ProfilePkt(val ceilingDb: Int, val mode: Int, val hapticMax: Int) : Packet() {
    override val type get() = PacketType.PROFILE
}

data class Decoded(val seq: Long, val packet: Packet)

object Wire {
    private val BODY_LEN = mapOf(
        PacketType.BEAT to 16, PacketType.RANGE to 204, PacketType.PING to 8, PacketType.PONG to 24,
        PacketType.CUE to 26, PacketType.ACK to 16, PacketType.PROFILE to 4,
    )
    private const val FRAME_HDR = 30

    fun encode(p: Packet, seq: Long): ByteArray {
        val bodyLen = if (p is FramePkt) FRAME_HDR + p.data.size else BODY_LEN.getValue(p.type)
        val w = Writer(HEADER_LEN + bodyLen)
        w.u16(MAGIC); w.u8(VERSION); w.u8(p.type); w.u32(seq)
        when (p) {
            is Beat -> { w.u8(p.board); w.u8(p.resetReason); w.u16(p.faults); w.u64(p.tUs); w.i8(p.rssiDbm); w.i8(p.tempC); w.u16(p.heapKb) }
            is RangePkt -> {
                if (p.mm.size != 64 || p.status.size != 64) throw WireException("RANGE needs 64 zones")
                w.u64(p.tUs); w.u8(p.res); w.u8(0); w.u16(0)
                p.mm.forEach { w.u16(it) }; p.status.forEach { w.u8(it) }
            }
            is FramePkt -> {
                if (p.data.size > MAX_CHUNK) throw WireException("chunk too large")
                w.u32(p.frameId); w.u64(p.tCapUs); w.u16(p.w); w.u16(p.h); w.u32(p.totalLen); w.u32(p.offset)
                w.u16(p.data.size); w.u16(p.chunkIdx); w.u16(p.chunkCount); w.bytes(p.data)
            }
            is Ping -> w.u64(p.tPhoneUs)
            is Pong -> { w.u64(p.tPhoneUs); w.u64(p.tBoardRxUs); w.u64(p.tBoardTxUs) }
            is CuePkt -> {
                w.u64(p.tIssuedPhoneUs); w.u64(p.deadlineBoardUs); w.u8(p.cueId); w.u8(p.side); w.i8(p.gainLDb); w.i8(p.gainRDb)
                w.u8(p.hapticPattern); w.u8(p.hapticL); w.u8(p.hapticR); w.u8(p.flags); w.u16(p.ttlMs)
            }
            is Ack -> { w.u32(p.cueSeq); w.u8(p.status); w.u8(0); w.i8(p.appliedGainLDb); w.i8(p.appliedGainRDb); w.u64(p.tBoardUs) }
            is ProfilePkt -> { w.i8(p.ceilingDb); w.u8(p.mode); w.u8(p.hapticMax); w.u8(0) }
        }
        return w.buf
    }

    /** Decodes [len] bytes; throws [WireException] for wrong magic/version/type/length (PROTOCOL.md "Receivers drop"). */
    fun decode(bytes: ByteArray, len: Int = bytes.size): Decoded {
        if (len < HEADER_LEN || len > bytes.size) throw WireException("short header")
        val r = Reader(bytes, len)
        if (r.u16() != MAGIC) throw WireException("bad magic")
        if (r.u8() != VERSION) throw WireException("bad version")
        val type = r.u8()
        val seq = r.u32()
        val bodyLen = len - HEADER_LEN
        if (type == PacketType.FRAME) {
            if (bodyLen < FRAME_HDR) throw WireException("short FRAME")
        } else {
            val want = BODY_LEN[type] ?: throw WireException("unknown type 0x%02x".format(type))
            if (bodyLen != want) throw WireException("type 0x%02x length $bodyLen != $want".format(type))
        }
        val p: Packet = when (type) {
            PacketType.BEAT -> Beat(r.u8(), r.u8(), r.u16(), r.u64(), r.i8(), r.i8(), r.u16())
            PacketType.RANGE -> {
                val t = r.u64(); val res = r.u8(); r.u8(); r.u16()
                val mm = IntArray(64) { r.u16() }
                val st = IntArray(64) { r.u8() }
                RangePkt(t, mm, st, res)
            }
            PacketType.FRAME -> {
                val fid = r.u32(); val t = r.u64(); val w = r.u16(); val h = r.u16(); val total = r.u32(); val off = r.u32()
                val clen = r.u16(); val idx = r.u16(); val cnt = r.u16()
                if (clen > MAX_CHUNK || bodyLen != FRAME_HDR + clen) throw WireException("FRAME length mismatch")
                FramePkt(fid, t, w, h, total, off, idx, cnt, r.bytes(clen))
            }
            PacketType.PING -> Ping(r.u64())
            PacketType.PONG -> Pong(r.u64(), r.u64(), r.u64())
            PacketType.CUE -> CuePkt(r.u64(), r.u64(), r.u8(), r.u8(), r.i8(), r.i8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u16())
            PacketType.ACK -> { val s = r.u32(); val st = r.u8(); r.u8(); Ack(s, st, r.i8(), r.i8(), r.u64()) }
            PacketType.PROFILE -> { val c = r.i8(); val m = r.u8(); val h = r.u8(); r.u8(); ProfilePkt(c, m, h) }
            else -> throw WireException("unknown type")
        }
        return Decoded(seq, p)
    }

    fun tryDecode(bytes: ByteArray, len: Int = bytes.size): Decoded? =
        try { decode(bytes, len) } catch (_: WireException) { null }

    /**
     * Which board sent this packet, from content alone: BEAT → its board byte, RANGE/ACK → CUE, FRAME → CAM.
     * PONG and anything malformed → null (attribute by learned source address).
     */
    fun senderBoard(bytes: ByteArray, len: Int = bytes.size): BoardId? {
        val d = tryDecode(bytes, len) ?: return null
        return when (val p = d.packet) {
            is Beat -> BoardId.fromWire(p.board)
            is RangePkt, is Ack -> BoardId.CUE
            is FramePkt -> BoardId.CAM
            else -> null
        }
    }

    private class Writer(n: Int) {
        val buf = ByteArray(n); var pos = 0
        fun u8(v: Int) { buf[pos++] = v.toByte() }
        fun i8(v: Int) { require(v in -128..127) { "i8 out of range: $v" }; u8(v) }
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v ushr (8 * i)).toInt()) }
        fun u64(v: Long) { for (i in 0 until 8) u8((v ushr (8 * i)).toInt()) }
        fun bytes(b: ByteArray) { b.copyInto(buf, pos); pos += b.size }
    }

    private class Reader(val b: ByteArray, val len: Int) {
        var pos = 0
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun i8(): Int = b[pos++].toInt()
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Long { var v = 0L; for (i in 0 until 4) v = v or (u8().toLong() shl (8 * i)); return v }
        fun u64(): Long { var v = 0L; for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i)); return v }
        fun bytes(n: Int): ByteArray = b.copyOfRange(pos, pos + n).also { pos += n }
    }
}
