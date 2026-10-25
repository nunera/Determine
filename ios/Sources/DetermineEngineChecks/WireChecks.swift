import DetermineEngine

struct Vector {
    let name: String
    let type: String
    let seq: Int64
    let fields: Json
    let hex: String
}

func loadVectors(_ path: String) throws -> [Vector] {
    guard let data = FileManagerShim.read(path) else { throw Json.ParseError(message: "cannot read vectors at \(path)") }
    guard let list = try Json.parse(data).arrayValue else { throw Json.ParseError(message: "vectors root is not an array") }
    return try list.map { v in
        guard let name = v["name"]?.stringValue, let type = v["type"]?.stringValue, let seq = v["seq"]?.intValue,
              let fields = v["fields"], let hex = v["hex"]?.stringValue
        else { throw Json.ParseError(message: "malformed vector entry") }
        return Vector(name: name, type: type, seq: seq, fields: fields, hex: hex)
    }
}

private func expected(_ type: String, _ f: Json) throws -> Packet {
    func l(_ k: String) -> Int64 { f[k]!.intValue! }
    func i(_ k: String) -> Int { Int(l(k)) }
    switch type {
    case "BEAT":
        return .beat(Beat(board: i("board"), resetReason: i("reset_reason"), faults: i("faults"), tUs: l("t_us"),
                          rssiDbm: i("rssi_dbm"), tempC: i("temp_c"), heapKb: i("heap_kb")))
    case "RANGE": return .range(RangePkt(tUs: l("t_us"), mm: i("mm"), status: i("status")))
    case "FRAME":
        return .frame(FramePkt(frameId: l("frame_id"), tCapUs: l("t_cap_us"), w: i("w"), h: i("h"), totalLen: l("total_len"),
                               offset: l("offset"), chunkIdx: i("chunk_idx"), chunkCount: i("chunk_count"),
                               data: hexToBytes(f["data"]!.stringValue!)))
    case "PING": return .ping(Ping(tPhoneUs: l("t_phone_us")))
    case "PONG": return .pong(Pong(tPhoneUs: l("t_phone_us"), tBoardRxUs: l("t_board_rx_us"), tBoardTxUs: l("t_board_tx_us")))
    case "CUE":
        return .cue(CuePkt(tIssuedPhoneUs: l("t_issued_phone_us"), deadlineBoardUs: l("deadline_board_us"), cueId: i("cue_id"),
                           side: i("side"), hapticPattern: i("haptic_pattern"), hapticL: i("haptic_l"), hapticR: i("haptic_r"),
                           flags: i("flags"), ttlMs: i("ttl_ms")))
    case "ACK": return .ack(Ack(cueSeq: l("cue_seq"), status: i("status"), tBoardUs: l("t_board_us")))
    case "PROFILE": return .profile(ProfilePkt(mode: i("mode"), hapticMax: i("haptic_max")))
    default: throw Json.ParseError(message: "unknown vector type \(type)")
    }
}

/// Port of WireTest.kt (the golden-vector test runs once per vector so every vector is counted).
func wireChecks(_ t: Harness, vectors: [Vector]) {
    t.test("wire: vector file has every packet type") {
        t.yes(vectors.count >= 8)
        t.eq(Set(["BEAT", "RANGE", "FRAME", "PING", "PONG", "CUE", "ACK", "PROFILE"]), Set(vectors.map { $0.type }))
    }

    for v in vectors {
        t.test("wire vector \(v.name) [\(v.type)]: decodes to fields and re-encodes identically") {
            let want = try expected(v.type, v.fields)
            let d = try Wire.decode(hexToBytes(v.hex))
            t.eq(v.seq, d.seq, v.name)
            t.eq(want, d.packet, v.name)
            t.eq(v.hex, toHex(try Wire.encode(d.packet, seq: d.seq)), v.name)
            t.eq(v.hex, toHex(try Wire.encode(want, seq: v.seq)), v.name)
        }
    }

    func vectorBytes(_ name: String) -> [UInt8] { hexToBytes(vectors.first { $0.name == name }!.hex) }

    t.test("wire: malformed packets are rejected") {
        let beat = vectorBytes("beat_cue")
        t.throwsError { _ = try Wire.decode(copyOf(beat, 7)) }
        t.throwsError { var b = beat; b[0] = 0x45; _ = try Wire.decode(b) }   // magic
        t.throwsError { var b = beat; b[2] = 1; _ = try Wire.decode(b) }      // v1 is no longer accepted
        t.throwsError { var b = beat; b[3] = 0x7F; _ = try Wire.decode(b) }   // unknown type
        t.throwsError { _ = try Wire.decode(copyOf(beat, beat.count - 1)) }   // short body
        t.throwsError { _ = try Wire.decode(copyOf(beat, beat.count + 1)) }   // long body
        let frame = vectorBytes("frame_chunk")
        t.throwsError { _ = try Wire.decode(copyOf(frame, frame.count - 1)) } // chunk_len mismatch
        t.throwsError { _ = try Wire.decode(copyOf(frame, 8 + 29)) }          // short FRAME header
        // len parameter is honoured: trailing buffer garbage beyond len is ignored
        let big = copyOf(beat, 64)
        t.eq(try Wire.decode(beat).packet, try Wire.decode(big, len: beat.count).packet)
        t.isNil(Wire.tryDecode(big))
    }

    t.test("wire: oversized chunk cannot be encoded or decoded") {
        t.throwsError {
            _ = try Wire.encode(.frame(FramePkt(frameId: 1, tCapUs: 1, w: 1, h: 1, totalLen: 2000, offset: 0, chunkIdx: 0, chunkCount: 2,
                                                data: [UInt8](repeating: 0, count: Wire.maxChunk + 1))), seq: 0)
        }
        let ok = try Wire.encode(.frame(FramePkt(frameId: 1, tCapUs: 1, w: 1, h: 1, totalLen: 2000, offset: 0, chunkIdx: 0, chunkCount: 2,
                                                 data: [UInt8](repeating: 0, count: Wire.maxChunk))), seq: 0)
        t.eq(8 + 30 + Wire.maxChunk, ok.count)
        // Forge chunk_len = 1401 with matching body length.
        var forged = copyOf(ok, 8 + 30 + 1401)
        forged[8 + 24] = UInt8(1401 & 0xFF)
        forged[8 + 25] = UInt8(1401 >> 8)
        t.throwsError { _ = try Wire.decode(forged) }
    }

    t.test("wire: sender board attribution") {
        t.eq(BoardId.cue, Wire.senderBoard(vectorBytes("beat_cue")))
        t.eq(BoardId.cam, Wire.senderBoard(vectorBytes("beat_cam_unknown_temp")))
        t.eq(BoardId.cue, Wire.senderBoard(vectorBytes("range_valid")))
        t.eq(BoardId.cue, Wire.senderBoard(vectorBytes("ack_applied_clamped")))
        t.eq(BoardId.cam, Wire.senderBoard(vectorBytes("frame_chunk")))
        t.isNil(Wire.senderBoard(vectorBytes("pong")))
        t.isNil(Wire.senderBoard([1, 2, 3]))
    }

    t.test("wire: u32 and u64 extremes round trip") {
        let p = Packet.pong(Pong(tPhoneUs: -1, tBoardRxUs: Int64.max, tBoardTxUs: 0))
        let d = try Wire.decode(try Wire.encode(p, seq: 0xFFFF_FFFF))
        t.eq(p, d.packet)
        t.eq(0xFFFF_FFFF, d.seq)
        t.eq(try Wire.encode(p, seq: 0xFFFF_FFFF), try Wire.encode(d.packet, seq: d.seq))
    }

    t.test("wire: v2 packet sizes and canonical range statuses") {
        t.eq(2, Wire.version)
        t.eq(19, try Wire.encode(.range(RangePkt(tUs: 1, mm: 800, status: RangeStatus.valid)), seq: 0).count)
        t.eq(32, try Wire.encode(.cue(CuePkt(tIssuedPhoneUs: 1, deadlineBoardUs: 2, cueId: CueId.obstacleNear, side: Side.both,
                                             hapticPattern: Haptic.buzz150, hapticL: 200, hapticR: 200, flags: 1, ttlMs: 150)), seq: 0).count)
        t.eq(22, try Wire.encode(.ack(Ack(cueSeq: 1, status: AckStatus.applied, tBoardUs: 2)), seq: 0).count)
        t.eq(10, try Wire.encode(.profile(ProfilePkt(mode: 0, hapticMax: 180)), seq: 0).count)
        t.eq(Set([0, 1, 2]), Set([RangeStatus.valid, RangeStatus.noTarget, RangeStatus.unknown]))
        // mm is a u16 on the wire.
        let big = try Wire.decode(try Wire.encode(.range(RangePkt(tUs: 1, mm: 65_535, status: RangeStatus.valid)), seq: 0)).packet
        if case .range(let r) = big { t.eq(65_535, r.mm) } else { t.yes(false, "not a RANGE") }
        // Old v1-sized bodies (grid RANGE, 26-byte CUE) can never be mistaken for v2.
        t.throwsError {
            var b = [UInt8](repeating: 0, count: 8 + 204)
            let hdr = try Wire.encode(.range(RangePkt(tUs: 0, mm: 0, status: 0)), seq: 0)
            b.replaceSubrange(0..<8, with: hdr[0..<8])
            _ = try Wire.decode(b)
        }
    }
}
