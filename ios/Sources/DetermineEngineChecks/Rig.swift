import DetermineEngine

/// What a headset sink returns when the phone -> bone-conduction route fails (not an ACK status).
let PHONE_ROUTE_FAILED = -1

/// A phone-side cue player standing in for the headset route; `status` nil = the player throws.
final class TestSink: LocalCueSink {
    struct Failure: Error {}
    var status: Int?
    var plays: [(at: Int64, cmd: CueCommand)] = []

    init(status: Int? = AckStatus.applied) { self.status = status }

    func play(_ cmd: CueCommand, nowPhoneUs: Int64) throws -> Int {
        plays.append((nowPhoneUs, cmd))
        guard let s = status else { throw Failure() }
        return s
    }

    func audible() -> [(at: Int64, cmd: CueCommand)] {
        plays.filter { $0.cmd.gainLDb != GAIN_MUTE || $0.cmd.gainRDb != GAIN_MUTE }
    }
}

/// Outbox + phone alerts + listener in one recorder, on a virtual clock shared with the rig.
final class Bus: Outbox, PhoneAlerts, EngineListener {
    var now: Int64 = 1_000_000_000
    var inbox: [(board: BoardId, bytes: [UInt8])] = []
    var states: [(state: SystemState, reason: String, at: Int64)] = []
    var alertsOut: [Alert?] = []
    var spoken: [String] = []
    var systemOffs = 0

    func send(board: BoardId, packet: [UInt8]) { inbox.append((board, packet)) }
    func speak(_ text: String) { spoken.append(text) }
    func vibrate(pattern: [Int64]) {}
    func systemOff() { systemOffs += 1 }
    func onState(_ state: SystemState, reason: String) { states.append((state, reason, now)) }
    func onAlert(_ alert: Alert?) { alertsOut.append(alert) }
    func onDiagnostics(_ d: Diagnostics) {}

    var lastAlert: Alert? { alertsOut.last ?? nil }
}

/// Two simulated boards wired to an Engine, all in virtual time. The cue board is haptic-only: it answers PING,
/// PROFILE and CUE (APPLIED) and streams the single forward RANGE zone. Every sound goes to `sink`, the stand-in
/// for the phone -> headset audio route.
final class Rig {
    let bus = Bus()
    let sink: TestSink
    let engine: Engine

    var now: Int64 { get { bus.now } set { bus.now = newValue } }
    var camOffset: Int64
    let cueOffset: Int64
    var camAlive = true
    var cueAlive = true
    var acksAlive = true
    var cueBeatT0: Int64 = 0
    var mm = 0
    var status = RangeStatus.noTarget
    var rangeDelayUs: Int64 = 0
    /// False = the cue board keeps beating and answering but sends no RANGE packets (sensor silence).
    var rangeAlive = true
    /// Wi-Fi RSSI the cue board reports in its BEATs.
    var cueRssi = -50

    var cues: [(at: Int64, pkt: CuePkt)] = []
    var profiles: [ProfilePkt] = []
    private var seq: Int64 = 0
    private var nextBeat: Int64 = 0
    private var nextRange: Int64 = 0
    private var frameId: Int64 = 0

    var states: [(state: SystemState, reason: String, at: Int64)] { bus.states }
    var alertsOut: [Alert?] { bus.alertsOut }
    var lastAlert: Alert? { bus.lastAlert }
    var spoken: [String] { bus.spoken }
    var systemOffs: Int { bus.systemOffs }

    init(config: EngineConfig = EngineConfig(), sink: TestSink = TestSink()) {
        self.sink = sink
        camOffset = 7_000_000 - bus.now
        cueOffset = 3_000_000 - bus.now
        engine = Engine(config: config, outbox: bus, alerts: bus, listener: bus, localCueSink: sink)
    }

    private func deliver(_ b: BoardId, _ p: Packet, delay: Int64 = 1_000) {
        let bytes = try! Wire.encode(p, seq: seq)
        seq += 1
        engine.onPacket(from: b, bytes: bytes, len: bytes.count, rxPhoneUs: now + delay)
    }

    func step() {
        // boards answer what the phone sent
        while !bus.inbox.isEmpty {
            let (b, bytes) = bus.inbox.removeFirst()
            let alive = b == .cam ? camAlive : cueAlive
            let off = b == .cam ? camOffset : cueOffset
            let d = try! Wire.decode(bytes)
            switch d.packet {
            // board receives 0.5 ms after send and replies 0.5 ms before phone receive: symmetric, offset exact
            case .ping(let p):
                if alive { deliver(b, .pong(Pong(tPhoneUs: p.tPhoneUs, tBoardRxUs: p.tPhoneUs + off + 500, tBoardTxUs: now + off + 500))) }
            // record the phone's real issue time: the rig drains the outbox one step (10 ms) after tick()
            case .cue(let p):
                cues.append((p.tIssuedPhoneUs, p))
                if alive && acksAlive { deliver(b, .ack(Ack(cueSeq: d.seq, status: AckStatus.applied, tBoardUs: now + off))) }
            case .profile(let p):
                profiles.append(p)
                if alive && acksAlive { deliver(b, .ack(Ack(cueSeq: d.seq, status: AckStatus.applied, tBoardUs: now + off))) }
            default: break
            }
        }
        if now >= nextBeat {
            nextBeat = now + 100_000
            if camAlive {
                deliver(.cam, .beat(Beat(board: 1, resetReason: 1, faults: 0, tUs: now + camOffset, rssiDbm: -50, tempC: 40, heapKb: 100)))
                let jpeg = (0..<2000).map { UInt8(truncatingIfNeeded: $0) }
                deliver(.cam, .frame(FramePkt(frameId: frameId, tCapUs: now + camOffset - 30_000, w: 320, h: 240, totalLen: 2000, offset: 0,
                                              chunkIdx: 0, chunkCount: 2, data: Array(jpeg[0..<1400]))))
                deliver(.cam, .frame(FramePkt(frameId: frameId, tCapUs: now + camOffset - 30_000, w: 320, h: 240, totalLen: 2000, offset: 1400,
                                              chunkIdx: 1, chunkCount: 2, data: Array(jpeg[1400..<2000]))))
                frameId += 1
            }
            if cueAlive {
                deliver(.cue, .beat(Beat(board: 2, resetReason: 1, faults: 0, tUs: now + cueOffset - cueBeatT0, rssiDbm: cueRssi, tempC: 40, heapKb: 100)))
            }
        }
        if now >= nextRange {
            nextRange = now + 66_667
            if cueAlive && rangeAlive {
                deliver(.cue, .range(RangePkt(tUs: now + cueOffset - 20_000 - rangeDelayUs, mm: mm, status: status)))
            }
        }
        engine.tick(nowPhoneUs: now)
        now += 10_000
    }

    func run(_ us: Int64) {
        let end = now + us
        while now < end { step() }
    }

    /// A RANGE that was measured `boardAgeUs` earlier than "now" but only arrives now (UDP reordering).
    func injectLateRange(boardAgeUs: Int64, mm: Int, status: Int) {
        deliver(.cue, .range(RangePkt(tUs: now + cueOffset - 20_000 - boardAgeUs, mm: mm, status: status)))
    }

    /// Camera board reboots: its clock restarts with a new offset and frame_id counts from 0 again.
    func rebootCam(newOffset: Int64) {
        camOffset = newOffset
        frameId = 0
    }

    /// One valid forward reading at `distanceMm`, or NO_TARGET for nil.
    func obstacle(_ distanceMm: Int?) {
        if let d = distanceMm { mm = d; status = RangeStatus.valid } else { mm = 0; status = RangeStatus.noTarget }
    }

    func obstacleCues(since: Int64 = 0) -> [(at: Int64, pkt: CuePkt)] {
        cues.filter { $0.at >= since && (CueId.obstacleFar...CueId.obstacleNear).contains($0.pkt.cueId) }
    }

    func obstacleSounds(since: Int64 = 0) -> [(at: Int64, cmd: CueCommand)] {
        sink.plays.filter { $0.at >= since && (CueId.obstacleFar...CueId.obstacleNear).contains($0.cmd.cueId) }
    }
}

/// Boards (optional camera board, cue board) + phone camera, all in virtual time, for the Tier 0 phone-camera mode.
final class FallbackRig {
    let bus = Bus()
    let sink: TestSink
    let engine: Engine

    var now: Int64 { get { bus.now } set { bus.now = newValue } }
    private let camOffset: Int64
    private let cueOffset: Int64
    var camBoard: Bool
    var phoneFrames: Bool
    var cueAlive = true
    var mm = 0
    var status = RangeStatus.noTarget

    var boardCues: [CuePkt] = []
    var profiles: [ProfilePkt] = []
    var pings: [BoardId: Int] = [.cam: 0, .cue: 0]
    var lastPhoneCaptureUs: Int64 = 0
    private var seq: Int64 = 0
    private var nextBeat: Int64 = 0
    private var nextRange: Int64 = 0
    private var nextPhoneFrame: Int64 = 0
    private var camFrameId: Int64 = 0

    var states: [(state: SystemState, reason: String, at: Int64)] { bus.states }
    var alertsOut: [Alert?] { bus.alertsOut }
    var spoken: [String] { bus.spoken }
    var systemOffs: Int { bus.systemOffs }

    init(_ config: EngineConfig, sink: TestSink = TestSink(), camBoard: Bool = false, phoneFrames: Bool = true) {
        self.sink = sink
        self.camBoard = camBoard
        self.phoneFrames = phoneFrames
        camOffset = 7_000_000 - bus.now
        cueOffset = 3_000_000 - bus.now
        engine = Engine(config: config, outbox: bus, alerts: bus, listener: bus, localCueSink: sink)
    }

    private func deliver(_ b: BoardId, _ p: Packet) {
        let bytes = try! Wire.encode(p, seq: seq)
        seq += 1
        engine.onPacket(from: b, bytes: bytes, len: bytes.count, rxPhoneUs: now + 1_000)
    }

    func step() {
        while !bus.inbox.isEmpty {
            let (b, bytes) = bus.inbox.removeFirst()
            let d = try! Wire.decode(bytes)
            let alive = b == .cam ? camBoard : cueAlive
            let off = b == .cam ? camOffset : cueOffset
            switch d.packet {
            case .ping(let p):
                pings[b, default: 0] += 1
                if alive { deliver(b, .pong(Pong(tPhoneUs: p.tPhoneUs, tBoardRxUs: p.tPhoneUs + off + 500, tBoardTxUs: now + off + 500))) }
            case .cue(let p):
                boardCues.append(p)
                if alive { deliver(b, .ack(Ack(cueSeq: d.seq, status: AckStatus.applied, tBoardUs: now + off))) }
            case .profile(let p):
                profiles.append(p)
                if alive { deliver(b, .ack(Ack(cueSeq: d.seq, status: AckStatus.applied, tBoardUs: now + off))) }
            default: break
            }
        }
        if now >= nextBeat {
            nextBeat = now + 100_000
            if camBoard {
                deliver(.cam, .beat(Beat(board: 1, resetReason: 1, faults: 0, tUs: now + camOffset, rssiDbm: -50, tempC: 40, heapKb: 100)))
                deliver(.cam, .frame(FramePkt(frameId: camFrameId, tCapUs: now + camOffset - 30_000, w: 320, h: 240, totalLen: 100, offset: 0,
                                              chunkIdx: 0, chunkCount: 1, data: [UInt8](repeating: 7, count: 100))))
                camFrameId += 1
            }
            if cueAlive {
                deliver(.cue, .beat(Beat(board: 2, resetReason: 1, faults: 0, tUs: now + cueOffset, rssiDbm: -50, tempC: 40, heapKb: 100)))
            }
        }
        if now >= nextRange {
            nextRange = now + 66_667
            if cueAlive { deliver(.cue, .range(RangePkt(tUs: now + cueOffset - 20_000, mm: mm, status: status))) }
        }
        if phoneFrames && now >= nextPhoneFrame {
            nextPhoneFrame = now + 33_333
            lastPhoneCaptureUs = now - 20_000
            try! engine.onPhoneFrame(jpeg: [UInt8](repeating: 3, count: 500), w: 640, h: 480, capturePhoneUs: lastPhoneCaptureUs)
        }
        engine.tick(nowPhoneUs: now)
        now += 10_000
    }

    func run(_ us: Int64) {
        let end = now + us
        while now < end { step() }
    }

    func obstacle(_ distanceMm: Int?) {
        if let d = distanceMm { mm = d; status = RangeStatus.valid } else { mm = 0; status = RangeStatus.noTarget }
    }
}

/// An engine with no boards attached (everything the engine sends is recorded in the returned bus and ignored).
func silentEngine(_ config: EngineConfig, sink: LocalCueSink = TestSink()) -> (Engine, Bus) {
    let bus = Bus()
    return (Engine(config: config, outbox: bus, alerts: bus, listener: bus, localCueSink: sink), bus)
}
