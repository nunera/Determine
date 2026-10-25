import DetermineEngine
import DetermineLink
import Dispatch
import Foundation

/// Headless phone: the real `Engine` over UDP, no camera detector. Output format is the stable one in
/// phone/core/API.md ("Stable cli output"); tools/sim/e2e.py parses it.
///
/// Audio: this process has no audio device. Every cue's audio half is LOGGED as an AUDIO line by `LogCueSink`
/// and acknowledged as APPLIED so the engine's audio-path state machine runs exactly as it does on the phone;
/// the sounds themselves only exist on a real phone with its headset. The board half (haptics) goes on the
/// wire as real CUE packets.

/// Phone monotonic clock in µs (CLOCK_MONOTONIC on Linux).
func nowUs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }

/// Formats events as `KIND <ms> ...`; `nowUs` is the phone monotonic clock. Thread-safe.
final class EventLog: @unchecked Sendable { // @unchecked: `lock` serializes every write to stdout.
    private let lock = NSLock()
    private let startUs: Int64

    init(startUs: Int64) { self.startUs = startUs }

    func emit(_ kind: String, _ rest: String) {
        let ms = String(format: "%.1f", Double(nowUs() - startUs) / 1000.0)
        write("\(kind) \(ms)\(rest.isEmpty ? "" : " " + rest)")
    }

    /// One whole line, flushed so the e2e harness sees it immediately.
    func write(_ line: String) {
        lock.lock()
        defer { lock.unlock() }
        FileHandle.standardOutput.write(Data((line + "\n").utf8)) // unbuffered: no flush needed
    }
}

/// The headless stand-in for the phone's headset audio: logs every cue and acknowledges APPLIED. There is no
/// speaker or headset in this process, the AUDIO line IS the audio path.
struct LogCueSink: LocalCueSink {
    let log: EventLog

    func play(_ cmd: CueCommand, nowPhoneUs: Int64) throws -> Int {
        log.emit("AUDIO", audioLine(cmd, status: AckStatus.applied))
        return AckStatus.applied
    }
}

struct LogAlerts: PhoneAlerts {
    let log: EventLog

    func speak(_ text: String) {
        log.emit("SPEAK", "text=\"\(text.replacingOccurrences(of: "\"", with: "'"))\"")
    }

    func vibrate(pattern: [Int64]) {
        log.emit("VIBRATE", "pattern=\(pattern.map { String($0) }.joined(separator: ","))")
    }

    func systemOff() { log.emit("SYSTEM-OFF", "") }
}

struct LogListener: EngineListener {
    let log: EventLog

    func onState(_ state: SystemState, reason: String) { log.emit("STATE", "\(state.token) reason=\(tok(reason))") }
    func onAlert(_ alert: Alert?) { log.emit("ALERT", alertLine(alert)) }
    func onDiagnostics(_ d: Diagnostics) { log.emit("DIAG", diagLine(d)) }
}

/// The engine's outbox: logs CUE/PROFILE like the Kotlin cli, then sends on the link. Boards not heard from yet
/// are skipped silently (nothing logged), as in the Kotlin cli.
struct LoggingOutbox: Outbox {
    let log: EventLog
    let link: UdpLink

    func send(board: BoardId, packet: [UInt8]) {
        guard link.addressBook.destination(board) != nil else { return }
        if let (k, rest) = txLine(packet) { log.emit(k, rest) }
        link.send(board: board, packet: packet)
    }
}

/// Owns the engine and the one lock every engine call goes through: the Engine is single-threaded and is driven
/// from the UDP receive thread (packets) and the tick thread.
final class Headless: @unchecked Sendable { // @unchecked: `engine` is only touched while holding `lock`.
    private let lock = NSLock()
    private var engine: Engine!
    private var running = true
    private let link: UdpLink
    private let log: EventLog

    init(_ o: CliOptions, log: EventLog) {
        self.log = log
        let book = BoardAddressBook(cam: o.cam, cue: o.cue)
        // The link's callbacks need the engine and the engine's outbox needs the link, hence `engine` is set after.
        var linkRef: UdpLink!
        var selfRef: Headless?
        linkRef = UdpLink(
            addressBook: book,
            clockUs: nowUs,
            onPacket: { board, bytes, rx in selfRef?.onPacket(board, bytes, rx) }
        )
        self.link = linkRef
        selfRef = self
        let profile = HearingProfile.uncalibrated(ceilingDb: o.ceilingDb, mode: o.mode)
        self.engine = Engine(
            config: EngineConfig(initialProfile: profile, spearcons: o.spearcons),
            outbox: LoggingOutbox(log: log, link: linkRef),
            alerts: LogAlerts(log: log),
            listener: LogListener(log: log),
            localCueSink: LogCueSink(log: log)
        )
    }

    func start(bind: String, port: UInt16) throws {
        try link.start(bind: bind, port: port)
    }

    private func onPacket(_ board: BoardId, _ bytes: [UInt8], _ rx: Int64) {
        lock.lock()
        defer { lock.unlock() }
        engine.onPacket(from: board, bytes: bytes, rxPhoneUs: rx)
    }

    /// Ticks the engine every 10 ms until `stop()`.
    func tickLoop() {
        while true {
            lock.lock()
            let go = running
            if go { engine.tick(nowPhoneUs: nowUs()) }
            lock.unlock()
            if !go { return }
            usleep(10_000)
        }
    }

    func stop() {
        lock.lock()
        running = false
        lock.unlock()
        link.stop()
    }
}

func run(_ o: CliOptions) -> Int32 {
    let start = nowUs()
    let log = EventLog(startUs: start)
    let phone = Headless(o, log: log)
    do {
        try phone.start(bind: o.bind, port: o.port)
    } catch {
        FileHandle.standardError.write(Data("error: \(error)\n".utf8))
        return 1
    }
    log.write("determine-phone listening on \(o.bind):\(o.port) (headless: AUDIO lines stand in for headset playback)")

    let ticker = Thread { phone.tickLoop() }
    ticker.name = "tick"
    ticker.start()

    // SIGINT/SIGTERM end the run like the Kotlin shutdown hook.
    let done = DispatchSemaphore(value: 0)
    var sources: [DispatchSourceSignal] = []
    for sig in [SIGINT, SIGTERM] {
        signal(sig, SIG_IGN)
        let src = DispatchSource.makeSignalSource(signal: sig, queue: .global())
        src.setEventHandler { done.signal() }
        src.resume()
        sources.append(src)
    }

    if let d = o.durationS {
        let endUs = start + Int64(d * 1e6)
        _ = done.wait(timeout: .now() + .nanoseconds(Int(max(0, endUs - nowUs())) * 1000))
    } else {
        done.wait()
    }
    phone.stop()
    sources.forEach { $0.cancel() }
    return 0
}

let args = Array(CommandLine.arguments.dropFirst())
if args.contains("--help") || args.contains("-h") {
    print(usage)
    exit(0)
}
let options: CliOptions
do {
    options = try parseArgs(args)
} catch let e as UsageError {
    FileHandle.standardError.write(Data("error: \(e.message)\n\(usage)\n".utf8))
    exit(2)
}
exit(run(options))
