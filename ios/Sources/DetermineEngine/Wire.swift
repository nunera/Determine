// Codec for PROTOCOL.md v2 (port of wire/Wire.kt). Little-endian, 8-byte header, no padding.
// u32 fields are carried in Int64 (0...2^32-1); u64 fields in Int64 (two's-complement bit pattern).
//
// v2: the cue board no longer plays audio (the phone synthesises every sound), so CUE/ACK/PROFILE carry no gain
// fields, and RANGE is the single forward ToF zone.

public struct WireError: Error, Equatable, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

public enum PacketType {
    public static let beat = 0x01
    public static let range = 0x02
    public static let frame = 0x03
    public static let ping = 0x10
    public static let pong = 0x11
    public static let cue = 0x20
    public static let ack = 0x21
    public static let profile = 0x22
}

public enum CueId {
    public static let ready = 0
    public static let obstacleFar = 1
    public static let obstacleMid = 2
    public static let obstacleNear = 3
    public static let personFar = 4
    public static let personMid = 5
    public static let personNear = 6
    public static let systemOff = 7
    public static let calTone500 = 8
    public static let calTone1k = 9
    public static let calTone2k = 10
    public static let calObstacle = 11
}

public enum Side {
    public static let l = 0
    public static let r = 1
    public static let both = 2
}

public enum Haptic {
    public static let none = 0
    public static let pulse40 = 1
    public static let pulse60 = 2
    public static let buzz150 = 3
    public static let lss = 4
}

/// Canonical RANGE status (PROTOCOL.md v2): 0 VALID, no target NO_TARGET, anything invalid/error UNKNOWN.
public enum RangeStatus {
    public static let valid = 0
    public static let noTarget = 1
    public static let unknown = 2
}

public enum AckStatus {
    public static let applied = 0
    public static let expired = 1
    public static let appliedClamped = 2
    public static let noSync = 3
    public static let badCue = 4
    public static let busyDropped = 5
}

public enum Fault {
    public static let sensorInitFail = 1 << 0
    public static let sensorReadErr = 1 << 1
    /// Reserved (the board's I2S audio path was removed in v2; the board is haptic-only).
    public static let reservedBit2 = 1 << 2
    public static let hapticLErr = 1 << 3
    public static let hapticRErr = 1 << 4
    public static let brownoutSeen = 1 << 5
    public static let noClockSync = 1 << 6
    public static let watchdogActive = 1 << 7
    public static let lowHeap = 1 << 8
    public static let captureFail = 1 << 9
}

public struct Beat: Equatable, Sendable {
    public var board: Int
    public var resetReason: Int
    public var faults: Int
    public var tUs: Int64
    public var rssiDbm: Int
    public var tempC: Int
    public var heapKb: Int

    public init(board: Int, resetReason: Int, faults: Int, tUs: Int64, rssiDbm: Int, tempC: Int, heapKb: Int) {
        self.board = board
        self.resetReason = resetReason
        self.faults = faults
        self.tUs = tUs
        self.rssiDbm = rssiDbm
        self.tempC = tempC
        self.heapKb = heapKb
    }
}

/// One forward ToF measurement from the cue board's VL53L4CD (single zone, no grid, no mount transform).
public struct RangePkt: Equatable, Sendable {
    public var tUs: Int64
    public var mm: Int
    public var status: Int

    public init(tUs: Int64, mm: Int, status: Int) {
        self.tUs = tUs
        self.mm = mm
        self.status = status
    }
}

public struct FramePkt: Equatable, Sendable {
    public var frameId: Int64
    public var tCapUs: Int64
    public var w: Int
    public var h: Int
    public var totalLen: Int64
    public var offset: Int64
    public var chunkIdx: Int
    public var chunkCount: Int
    public var data: [UInt8]

    public init(frameId: Int64, tCapUs: Int64, w: Int, h: Int, totalLen: Int64, offset: Int64, chunkIdx: Int, chunkCount: Int, data: [UInt8]) {
        self.frameId = frameId
        self.tCapUs = tCapUs
        self.w = w
        self.h = h
        self.totalLen = totalLen
        self.offset = offset
        self.chunkIdx = chunkIdx
        self.chunkCount = chunkCount
        self.data = data
    }
}

public struct Ping: Equatable, Sendable {
    public var tPhoneUs: Int64
    public init(tPhoneUs: Int64) { self.tPhoneUs = tPhoneUs }
}

public struct Pong: Equatable, Sendable {
    public var tPhoneUs: Int64
    public var tBoardRxUs: Int64
    public var tBoardTxUs: Int64

    public init(tPhoneUs: Int64, tBoardRxUs: Int64, tBoardTxUs: Int64) {
        self.tPhoneUs = tPhoneUs
        self.tBoardRxUs = tBoardRxUs
        self.tBoardTxUs = tBoardTxUs
    }
}

/// A haptic command for the cue board. Audio gains live phone-side only; `cueId` identifies the sound the phone
/// plays in parallel.
public struct CuePkt: Equatable, Sendable {
    public var tIssuedPhoneUs: Int64
    public var deadlineBoardUs: Int64
    public var cueId: Int
    public var side: Int
    public var hapticPattern: Int
    public var hapticL: Int
    public var hapticR: Int
    public var flags: Int
    public var ttlMs: Int

    public init(tIssuedPhoneUs: Int64, deadlineBoardUs: Int64, cueId: Int, side: Int, hapticPattern: Int, hapticL: Int, hapticR: Int, flags: Int, ttlMs: Int) {
        self.tIssuedPhoneUs = tIssuedPhoneUs
        self.deadlineBoardUs = deadlineBoardUs
        self.cueId = cueId
        self.side = side
        self.hapticPattern = hapticPattern
        self.hapticL = hapticL
        self.hapticR = hapticR
        self.flags = flags
        self.ttlMs = ttlMs
    }
}

public struct Ack: Equatable, Sendable {
    public var cueSeq: Int64
    public var status: Int
    public var tBoardUs: Int64

    public init(cueSeq: Int64, status: Int, tBoardUs: Int64) {
        self.cueSeq = cueSeq
        self.status = status
        self.tBoardUs = tBoardUs
    }
}

/// Board haptics profile. `mode` keeps the phone's audio-profile semantics (0 STEREO, 1 SSD, 2 HAPTIC_ONLY).
public struct ProfilePkt: Equatable, Sendable {
    public var mode: Int
    public var hapticMax: Int

    public init(mode: Int, hapticMax: Int) {
        self.mode = mode
        self.hapticMax = hapticMax
    }
}

public enum Packet: Equatable, Sendable {
    case beat(Beat)
    case range(RangePkt)
    case frame(FramePkt)
    case ping(Ping)
    case pong(Pong)
    case cue(CuePkt)
    case ack(Ack)
    case profile(ProfilePkt)

    public var type: Int {
        switch self {
        case .beat: return PacketType.beat
        case .range: return PacketType.range
        case .frame: return PacketType.frame
        case .ping: return PacketType.ping
        case .pong: return PacketType.pong
        case .cue: return PacketType.cue
        case .ack: return PacketType.ack
        case .profile: return PacketType.profile
        }
    }
}

public struct Decoded: Equatable, Sendable {
    public let seq: Int64
    public let packet: Packet

    public init(seq: Int64, packet: Packet) {
        self.seq = seq
        self.packet = packet
    }
}

public enum Wire {
    public static let magic = 0x5444
    public static let version = 2
    public static let headerLen = 8
    public static let maxChunk = 1400

    private static let frameHdr = 30

    private static func bodyLen(_ type: Int) -> Int? {
        switch type {
        case PacketType.beat: return 16
        case PacketType.range: return 11
        case PacketType.ping: return 8
        case PacketType.pong: return 24
        case PacketType.cue: return 24
        case PacketType.ack: return 14
        case PacketType.profile: return 2
        default: return nil
        }
    }

    /// Throws `WireError` for a FRAME chunk larger than `maxChunk` or an RSSI/temperature outside i8.
    public static func encode(_ p: Packet, seq: Int64) throws -> [UInt8] {
        let bodyLen: Int
        if case .frame(let f) = p { bodyLen = frameHdr + f.data.count } else { bodyLen = Wire.bodyLen(p.type)! }
        var w = Writer(capacity: headerLen + bodyLen)
        w.u16(magic); w.u8(version); w.u8(p.type); w.u32(seq)
        switch p {
        case .beat(let b):
            w.u8(b.board); w.u8(b.resetReason); w.u16(b.faults); w.u64(b.tUs)
            try w.i8(b.rssiDbm); try w.i8(b.tempC); w.u16(b.heapKb)
        case .range(let r):
            w.u64(r.tUs); w.u16(r.mm); w.u8(r.status)
        case .frame(let f):
            if f.data.count > maxChunk { throw WireError("chunk too large") }
            w.u32(f.frameId); w.u64(f.tCapUs); w.u16(f.w); w.u16(f.h); w.u32(f.totalLen); w.u32(f.offset)
            w.u16(f.data.count); w.u16(f.chunkIdx); w.u16(f.chunkCount); w.bytes(f.data)
        case .ping(let q):
            w.u64(q.tPhoneUs)
        case .pong(let q):
            w.u64(q.tPhoneUs); w.u64(q.tBoardRxUs); w.u64(q.tBoardTxUs)
        case .cue(let c):
            w.u64(c.tIssuedPhoneUs); w.u64(c.deadlineBoardUs); w.u8(c.cueId); w.u8(c.side)
            w.u8(c.hapticPattern); w.u8(c.hapticL); w.u8(c.hapticR); w.u8(c.flags); w.u16(c.ttlMs)
        case .ack(let a):
            w.u32(a.cueSeq); w.u8(a.status); w.u8(0); w.u64(a.tBoardUs)
        case .profile(let q):
            w.u8(q.mode); w.u8(q.hapticMax)
        }
        return w.buf
    }

    /// Decodes the first `len` bytes (default: all); throws `WireError` for wrong magic/version/type/length
    /// (PROTOCOL.md "Receivers drop").
    public static func decode(_ bytes: [UInt8], len: Int? = nil) throws -> Decoded {
        let len = len ?? bytes.count
        if len < headerLen || len > bytes.count { throw WireError("short header") }
        var r = Reader(bytes)
        if r.u16() != magic { throw WireError("bad magic") }
        if r.u8() != version { throw WireError("bad version") }
        let type = r.u8()
        let seq = r.u32()
        let body = len - headerLen
        if type == PacketType.frame {
            if body < frameHdr { throw WireError("short FRAME") }
        } else {
            guard let want = bodyLen(type) else { throw WireError("unknown type 0x\(hex2(type))") }
            if body != want { throw WireError("type 0x\(hex2(type)) length \(body) != \(want)") }
        }
        let p: Packet
        switch type {
        case PacketType.beat:
            p = .beat(Beat(board: r.u8(), resetReason: r.u8(), faults: r.u16(), tUs: r.u64(), rssiDbm: r.i8(), tempC: r.i8(), heapKb: r.u16()))
        case PacketType.range:
            p = .range(RangePkt(tUs: r.u64(), mm: r.u16(), status: r.u8()))
        case PacketType.frame:
            let fid = r.u32(), t = r.u64(), w = r.u16(), h = r.u16(), total = r.u32(), off = r.u32()
            let clen = r.u16(), idx = r.u16(), cnt = r.u16()
            if clen > maxChunk || body != frameHdr + clen { throw WireError("FRAME length mismatch") }
            p = .frame(FramePkt(frameId: fid, tCapUs: t, w: w, h: h, totalLen: total, offset: off, chunkIdx: idx, chunkCount: cnt, data: r.bytes(clen)))
        case PacketType.ping:
            p = .ping(Ping(tPhoneUs: r.u64()))
        case PacketType.pong:
            p = .pong(Pong(tPhoneUs: r.u64(), tBoardRxUs: r.u64(), tBoardTxUs: r.u64()))
        case PacketType.cue:
            p = .cue(CuePkt(tIssuedPhoneUs: r.u64(), deadlineBoardUs: r.u64(), cueId: r.u8(), side: r.u8(),
                            hapticPattern: r.u8(), hapticL: r.u8(), hapticR: r.u8(), flags: r.u8(), ttlMs: r.u16()))
        case PacketType.ack:
            let s = r.u32(), st = r.u8()
            _ = r.u8()
            p = .ack(Ack(cueSeq: s, status: st, tBoardUs: r.u64()))
        case PacketType.profile:
            p = .profile(ProfilePkt(mode: r.u8(), hapticMax: r.u8()))
        default:
            throw WireError("unknown type")
        }
        return Decoded(seq: seq, packet: p)
    }

    public static func tryDecode(_ bytes: [UInt8], len: Int? = nil) -> Decoded? {
        try? decode(bytes, len: len)
    }

    /// Which board sent this packet, from content alone: BEAT -> its board byte, RANGE/ACK -> CUE, FRAME -> CAM.
    /// PONG and anything malformed -> nil (attribute by learned source address).
    public static func senderBoard(_ bytes: [UInt8], len: Int? = nil) -> BoardId? {
        guard let d = tryDecode(bytes, len: len) else { return nil }
        switch d.packet {
        case .beat(let b): return BoardId.fromWire(b.board)
        case .range, .ack: return .cue
        case .frame: return .cam
        default: return nil
        }
    }

    private static func hex2(_ v: Int) -> String {
        let s = String(v, radix: 16)
        return s.count < 2 ? "0" + s : s
    }

    private struct Writer {
        var buf: [UInt8] = []
        init(capacity: Int) { buf.reserveCapacity(capacity) }
        mutating func u8(_ v: Int) { buf.append(UInt8(truncatingIfNeeded: v)) }
        mutating func i8(_ v: Int) throws {
            guard (-128...127).contains(v) else { throw WireError("i8 out of range: \(v)") }
            u8(v)
        }
        mutating func u16(_ v: Int) { u8(v); u8(v >> 8) }
        mutating func u32(_ v: Int64) { for i in 0..<4 { buf.append(UInt8(truncatingIfNeeded: v >> Int64(8 * i))) } }
        mutating func u64(_ v: Int64) { for i in 0..<8 { buf.append(UInt8(truncatingIfNeeded: v >> Int64(8 * i))) } }
        mutating func bytes(_ b: [UInt8]) { buf.append(contentsOf: b) }
    }

    private struct Reader {
        let b: [UInt8]
        var pos = 0
        init(_ b: [UInt8]) { self.b = b }
        mutating func u8() -> Int { defer { pos += 1 }; return Int(b[pos]) }
        mutating func i8() -> Int { defer { pos += 1 }; return Int(Int8(bitPattern: b[pos])) }
        mutating func u16() -> Int { let lo = u8(); return lo | (u8() << 8) }
        mutating func u32() -> Int64 {
            var v: UInt64 = 0
            for i in 0..<4 { v |= UInt64(u8()) << UInt64(8 * i) }
            return Int64(v)
        }
        mutating func u64() -> Int64 {
            var v: UInt64 = 0
            for i in 0..<8 { v |= UInt64(u8()) << UInt64(8 * i) }
            return Int64(bitPattern: v)
        }
        mutating func bytes(_ n: Int) -> [UInt8] { defer { pos += n }; return Array(b[pos..<(pos + n)]) }
    }
}
