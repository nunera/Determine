package org.determine.core.wire

import org.determine.core.BoardId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** Minimal JSON reader for the golden vectors (objects, arrays, strings, integers). */
object MiniJson {
    fun parse(s: String): Any? = P(s).run { value().also { ws(); require(i == s.length) } }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws()
                    if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); require(s[i++] == ':'); m[k] = value(); ws()
                        if (s[i++] == '}') break }
                    m }
                '[' -> { i++; val l = ArrayList<Any?>(); ws()
                    if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i++] == ']') break }
                    l }
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { require(c == '-' || c.isDigit()); val st = i; i++
                    while (i < s.length && (s[i].isDigit())) i++
                    s.substring(st, i).toLong() }
            }
        }
        fun str(): String {
            require(s[i++] == '"'); val b = StringBuilder()
            while (s[i] != '"') { if (s[i] == '\\') { i++; b.append(if (s[i] == 'n') '\n' else s[i]) } else b.append(s[i]); i++ }
            i++; return b.toString()
        }
    }
}

fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

class WireTest {
    @Suppress("UNCHECKED_CAST")
    private val vectors: List<Map<String, Any?>> by lazy {
        val path = System.getProperty("determine.vectors") ?: "../../protocol/vectors/vectors.json"
        MiniJson.parse(File(path).readText()) as List<Map<String, Any?>>
    }

    private fun Map<String, Any?>.l(k: String) = (this[k] as Long)
    private fun Map<String, Any?>.i(k: String) = l(k).toInt()
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.ints(k: String) = (this[k] as List<Long>).map { it.toInt() }.toIntArray()

    private fun expected(type: String, f: Map<String, Any?>): Packet = when (type) {
        "BEAT" -> Beat(f.i("board"), f.i("reset_reason"), f.i("faults"), f.l("t_us"), f.i("rssi_dbm"), f.i("temp_c"), f.i("heap_kb"))
        "RANGE" -> RangePkt(f.l("t_us"), f.ints("mm"), f.ints("status"), f.i("res"))
        "FRAME" -> FramePkt(f.l("frame_id"), f.l("t_cap_us"), f.i("w"), f.i("h"), f.l("total_len"), f.l("offset"),
            f.i("chunk_idx"), f.i("chunk_count"), (f["data"] as String).hexToBytes())
        "PING" -> Ping(f.l("t_phone_us"))
        "PONG" -> Pong(f.l("t_phone_us"), f.l("t_board_rx_us"), f.l("t_board_tx_us"))
        "CUE" -> CuePkt(f.l("t_issued_phone_us"), f.l("deadline_board_us"), f.i("cue_id"), f.i("side"), f.i("gain_l_db"),
            f.i("gain_r_db"), f.i("haptic_pattern"), f.i("haptic_l"), f.i("haptic_r"), f.i("flags"), f.i("ttl_ms"))
        "ACK" -> Ack(f.l("cue_seq"), f.i("status"), f.i("applied_gain_l_db"), f.i("applied_gain_r_db"), f.l("t_board_us"))
        "PROFILE" -> ProfilePkt(f.i("ceiling_db"), f.i("mode"), f.i("haptic_max"))
        else -> error("unknown vector type $type")
    }

    @Test
    fun `golden vectors decode to fields and re-encode identically`() {
        assertTrue(vectors.size >= 8)
        val seen = HashSet<String>()
        for (v in vectors) {
            val hex = v["hex"] as String
            val type = v["type"] as String
            @Suppress("UNCHECKED_CAST") val fields = v["fields"] as Map<String, Any?>
            val d = Wire.decode(hex.hexToBytes())
            assertEquals(v.l("seq"), d.seq, v["name"] as String)
            assertEquals(expected(type, fields), d.packet, v["name"] as String)
            assertEquals(hex, Wire.encode(d.packet, d.seq).toHex(), v["name"] as String)
            assertEquals(hex, Wire.encode(expected(type, fields), v.l("seq")).toHex(), v["name"] as String)
            seen += type
        }
        assertEquals(setOf("BEAT", "RANGE", "FRAME", "PING", "PONG", "CUE", "ACK", "PROFILE"), seen)
    }

    private fun vectorBytes(name: String) = (vectors.first { it["name"] == name }["hex"] as String).hexToBytes()

    @Test
    fun `malformed packets are rejected`() {
        val beat = vectorBytes("beat_cue")
        assertThrows<WireException> { Wire.decode(beat.copyOf(7)) }
        assertThrows<WireException> { Wire.decode(beat.copyOf().also { it[0] = 0x45 }) }  // magic
        assertThrows<WireException> { Wire.decode(beat.copyOf().also { it[2] = 2 }) }     // version
        assertThrows<WireException> { Wire.decode(beat.copyOf().also { it[3] = 0x7F }) }  // unknown type
        assertThrows<WireException> { Wire.decode(beat.copyOf(beat.size - 1)) }           // short body
        assertThrows<WireException> { Wire.decode(beat.copyOf(beat.size + 1)) }           // long body
        val frame = vectorBytes("frame_chunk")
        assertThrows<WireException> { Wire.decode(frame.copyOf(frame.size - 1)) }         // chunk_len mismatch
        assertThrows<WireException> { Wire.decode(frame.copyOf(8 + 29)) }                 // short FRAME header
        // len parameter is honoured: trailing buffer garbage beyond len is ignored
        val big = beat.copyOf(64)
        assertEquals(Wire.decode(beat).packet, Wire.decode(big, beat.size).packet)
        assertNull(Wire.tryDecode(big))
    }

    @Test
    fun `oversized chunk cannot be encoded or decoded`() {
        assertThrows<WireException> { Wire.encode(FramePkt(1, 1, 1, 1, 2000, 0, 0, 2, ByteArray(MAX_CHUNK + 1)), 0) }
        val ok = Wire.encode(FramePkt(1, 1, 1, 1, 2000, 0, 0, 2, ByteArray(MAX_CHUNK)), 0)
        assertEquals(8 + 30 + MAX_CHUNK, ok.size)
        // Forge chunk_len = 1401 with matching body length.
        val forged = ByteArray(8 + 30 + 1401); ok.copyInto(forged)
        forged[8 + 24] = (1401 and 0xFF).toByte(); forged[8 + 25] = (1401 shr 8).toByte()
        assertThrows<WireException> { Wire.decode(forged) }
    }

    @Test
    fun `sender board attribution`() {
        assertEquals(BoardId.CUE, Wire.senderBoard(vectorBytes("beat_cue")))
        assertEquals(BoardId.CAM, Wire.senderBoard(vectorBytes("beat_cam_unknown_temp")))
        assertEquals(BoardId.CUE, Wire.senderBoard(vectorBytes("range")))
        assertEquals(BoardId.CUE, Wire.senderBoard(vectorBytes("ack_clamped")))
        assertEquals(BoardId.CAM, Wire.senderBoard(vectorBytes("frame_chunk")))
        assertNull(Wire.senderBoard(vectorBytes("pong")))
        assertNull(Wire.senderBoard(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `u32 and u64 extremes round trip`() {
        val p = Pong(-1L, Long.MAX_VALUE, 0)
        val d = Wire.decode(Wire.encode(p, 0xFFFFFFFFL))
        assertEquals(p, d.packet)
        assertEquals(0xFFFFFFFFL, d.seq)
        assertArrayEquals(Wire.encode(p, 0xFFFFFFFFL), Wire.encode(d.packet, d.seq))
    }
}
