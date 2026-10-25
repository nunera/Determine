#if canImport(AVFoundation)
import DetermineEngine
import DetermineLink
import Foundation

/// Link facts the Judge and Settings screens show.
struct LinkInfo: Equatable, Sendable {
    var bound = false
    var bindError: String?
    var camIp: String?
    var cueIp: String?
    var unattributedPackets: UInt64 = 0
    var sendErrors: UInt64 = 0
    var sendsWithoutAddress: UInt64 = 0
}

/// Session options fixed for one engine run; changing the camera restarts the session.
struct HostOptions: Sendable {
    var spearcons: Bool
    var frameSource: FrameSourceKind
    /// Engine mode: HAPTIC_ONLY exactly when `soundOutput` is `.off`.
    var mode: HearingMode
    var soundOutput: SoundOutput
    var hapticMax: Int
    var tuning: Tuning
}

/// Result of one Read text request, produced off the engine queue.
enum ReadTextResult: Sendable {
    case text(String)
    case noText
    /// No camera frame, or none fresh enough (older than `ReadTextSettings.maxFrameAgeUs`).
    case noFreshFrame
    case failed(String)
}

enum ReadTextSettings {
    /// A frame older than this is not read: it shows a scene the wearer has already left.
    static let maxFrameAgeUs: Int64 = 500_000
}

/// Debug speaker mode needs levels a phone speaker can be heard at; the uncalibrated headset levels (-40 dBFS) are not.
/// -10 dBFS is the hard ceiling every sink clamps to anyway.
enum HostProfiles {
    static func make(mode: HearingMode, soundOutput: SoundOutput, hapticMax: Int) throws -> HearingProfile {
        let base = HearingProfile.uncalibrated(mode: mode)
        guard soundOutput == .speaker else { return try base.copy(hapticMax: hapticMax) }
        let bands = try CalBand.hz.map { try BandProfile(hz: $0, thrDbL: -30, thrDbR: -30, slDb: 20, offsetDbL: 0, offsetDbR: 0) }
        return try base.copy(deviceId: "debug-speaker", ceilingDb: HARD_CEILING_DB, bands: bands, hapticMax: hapticMax)
    }
}

/// Everything the engine queue publishes. The consumer hops to the main actor in FIFO order.
enum HostEvent: Sendable {
    case state(SystemState, reason: String)
    case alert(Alert?)
    case diagnostics(Diagnostics)
    /// `ranges` are the RANGE samples that arrived since the previous publish (for the chart), `range` the latest.
    case slow(range: RangeSnapshot?, ranges: [RangeSnapshot], link: LinkInfo, camera: PhoneCameraStatus?, profile: HearingProfile, sound: SoundStats)
    /// `setTuning` refused the value (the engine keeps the previous tuning).
    case tuningRejected(String)
    case testBuzz(pattern: Int, intensity: Int, sent: Bool)
}

private struct ForwardingListener: EngineListener {
    let publish: @Sendable (HostEvent) -> Void
    func onState(_ state: SystemState, reason: String) { publish(.state(state, reason: reason)) }
    func onAlert(_ alert: Alert?) { publish(.alert(alert)) }
    func onDiagnostics(_ d: Diagnostics) { publish(.diagnostics(d)) }
}

/// One live engine run: the `Engine`, the UDP link, the optional iPhone camera and a 10 ms tick (Arch §4, §7).
///
/// The engine is not thread-safe, so it lives on `queue` and is touched only there: the UDP receive thread and the
/// camera queue hop onto it, as do UI commands and the tick. The main actor never calls into the engine.
///
/// `@unchecked Sendable`: all mutable state is confined to `queue`; `stopped` is only read and written on it too.
/// A host is single-use: after `stop` build a new one.
final class SessionHost: @unchecked Sendable {
    static let tickInterval: DispatchTimeInterval = .milliseconds(10)
    private static let publishIntervalUs: Int64 = 100_000

    private let queue = DispatchQueue(label: "org.determine.engine", qos: .userInteractive)
    private let options: HostOptions
    private let alerts: IosPhoneAlerts
    private let publish: @Sendable (HostEvent) -> Void

    private var engine: Engine?
    private var link: UdpLink?
    private var camera: PhoneCameraSource?
    private var timer: DispatchSourceTimer?
    private var bindError: String?
    private var stopped = false
    private var nextPublishUs: Int64 = 0
    private var sink: AudioEngineCueSink?
    /// RANGE samples seen since the last slow publish, oldest first (chart feed).
    private var pendingRanges: [RangeSnapshot] = []
    private var lastRangeBoardUs: Int64?

    init(options: HostOptions, alerts: IosPhoneAlerts, publish: @escaping @Sendable (HostEvent) -> Void) {
        self.options = options
        self.alerts = alerts
        self.publish = publish
    }

    func start() {
        queue.async { [self] in
            guard !stopped, engine == nil else { return }
            let udp = UdpLink(
                addressBook: BoardAddressBook(),
                clockUs: EngineClock.nowUs,
                onPacket: { [weak self] board, bytes, rxUs in
                    // Runs on the receive thread; the rx time was stamped before this hop.
                    guard let self else { return }
                    queue.async { self.engine?.onPacket(from: board, bytes: bytes, rxPhoneUs: rxUs) }
                }
            )
            link = udp
            let profile = (try? HostProfiles.make(mode: options.mode, soundOutput: options.soundOutput, hapticMax: options.hapticMax))
                ?? HearingProfile.uncalibrated(mode: options.mode)
            let cueSink = AudioEngineCueSink(output: options.soundOutput) { [weak self] in
                // Called inside `play`, i.e. on this queue.
                self?.engine?.profile.effectiveCeilingDb ?? HARD_CEILING_DB
            }
            // The session must be active before the engine's first muted route probe.
            cueSink.prepare()
            sink = cueSink
            engine = Engine(
                config: EngineConfig(
                    initialProfile: profile,
                    // The Judge screen refreshes ages at 4 Hz.
                    diagnosticsIntervalUs: 250_000,
                    spearcons: options.spearcons,
                    frameSource: options.frameSource,
                    tuning: options.tuning
                ),
                outbox: udp,
                alerts: alerts,
                listener: ForwardingListener(publish: publish),
                localCueSink: cueSink
            )
            do {
                try udp.start()
            } catch {
                // Without the socket nothing reaches the engine: its liveness rules take it to UNAVAILABLE and the
                // phone announces system off. Also surface the concrete cause.
                bindError = "\(error)"
                alerts.speak("Network port unavailable")
            }
            if options.frameSource == .phoneCamera {
                let cam = PhoneCameraSource(clockUs: EngineClock.nowUs) { [weak self] jpeg, w, h, capUs in
                    guard let self else { return }
                    queue.async { _ = try? self.engine?.onPhoneFrame(jpeg: jpeg, w: w, h: h, capturePhoneUs: capUs) }
                }
                camera = cam
                cam.start()
            }
            publishSlow()
            let t = DispatchSource.makeTimerSource(flags: .strict, queue: queue)
            t.schedule(deadline: .now() + Self.tickInterval, repeating: Self.tickInterval, leeway: .milliseconds(1))
            t.setEventHandler { [weak self] in self?.tick() }
            timer = t
            t.resume()
        }
    }

    private func tick() {
        guard !stopped, let engine else { return }
        let now = EngineClock.nowUs()
        engine.tick(nowPhoneUs: now)
        if let r = engine.latestRange(), r.tBoardUs != lastRangeBoardUs {
            lastRangeBoardUs = r.tBoardUs
            pendingRanges.append(r)
            if pendingRanges.count > 200 { pendingRanges.removeFirst(pendingRanges.count - 200) }
        }
        if now >= nextPublishUs {
            nextPublishUs = now + Self.publishIntervalUs
            publishSlow()
        }
    }

    /// Slower-changing views for the UI (forward range, link, camera), 10 Hz.
    private func publishSlow() {
        guard let engine, let link else { return }
        let book = link.addressBook
        let info = LinkInfo(
            bound: bindError == nil,
            bindError: bindError,
            camIp: book.destination(.cam)?.ipString,
            cueIp: book.destination(.cue)?.ipString,
            unattributedPackets: link.unattributedCount,
            sendErrors: link.sendErrors,
            sendsWithoutAddress: link.sendsWithoutAddress
        )
        let ranges = pendingRanges
        pendingRanges.removeAll(keepingCapacity: true)
        publish(.slow(
            range: engine.latestRange(), ranges: ranges, link: info, camera: camera?.currentStatus(),
            profile: engine.profile, sound: sink?.snapshot() ?? SoundStats()
        ))
    }

    /// Stops PINGs (so the board watchdog fires), closes the socket and camera, then calls `completion` on the main
    /// queue. The next host must not start before it: two sockets on :47000 would split the packets.
    func stop(completion: @escaping @Sendable () -> Void = {}) {
        queue.async { [self] in
            stopped = true
            timer?.cancel()
            timer = nil
            camera?.stop()
            camera = nil
            link?.stop()
            link = nil
            sink?.close()
            sink = nil
            engine = nil
            DispatchQueue.main.async(execute: completion)
        }
    }

    func setPaused(_ paused: Bool) {
        queue.async { [self] in
            guard !stopped else { return }
            engine?.setPaused(paused)
        }
    }

    func setSpearcons(_ enabled: Bool) {
        queue.async { [self] in
            guard !stopped else { return }
            engine?.setSpearcons(enabled)
        }
    }

    /// Board haptic cap (PROFILE.haptic_max), clamped to 1...255: the temples are the only failure channel that
    /// survives phone loss, so the cap cannot be turned off.
    func setHapticMax(_ value: Int) {
        updateProfile { try $0.copy(hapticMax: min(max(value, MIN_HAPTIC_MAX), 255)) }
    }

    /// Live switch of the warning-sound output together with the engine mode. Order matters: turning sound off
    /// first changes the engine to HAPTIC_ONLY (it stops probing the sink), and only then closes the sink route;
    /// turning it on opens the route first so the engine's first probe finds it.
    func setSoundOutput(_ output: SoundOutput, mode: HearingMode) {
        queue.async { [self] in
            guard !stopped, let engine, let sink else { return }
            guard let next = try? HostProfiles.make(mode: mode, soundOutput: output, hapticMax: engine.profile.hapticMax) else { return }
            if output == .off {
                engine.setProfile(next)
                sink.setOutput(.off)
            } else {
                sink.setOutput(output)
                engine.setProfile(next)
            }
            publishSlow()
        }
    }

    /// Applies a validated whole tuning at once. The engine keeps its previous tuning if it refuses.
    func setTuning(_ tuning: Tuning) {
        queue.async { [self] in
            guard !stopped, let engine else { return }
            do {
                try engine.setTuning(tuning)
            } catch {
                publish(.tuningRejected("\(error)"))
            }
        }
    }

    /// Debug buzz through the normal CUE path (deadline, haptic_max clamp, ACK).
    func sendTestHaptic(pattern: Int, intensity: Int) {
        queue.async { [self] in
            guard !stopped, let engine else { return }
            let sent = engine.sendTestHaptic(pattern: pattern, intensity: intensity, nowPhoneUs: EngineClock.nowUs())
            publish(.testBuzz(pattern: pattern, intensity: intensity, sent: sent))
        }
    }

    /// Read text: OCR of the newest camera frame if it is at most 500 ms old. Only reads the frame on the engine
    /// queue; the Vision request runs elsewhere so the tick is never delayed. Never touches hazard logic.
    func readText(_ done: @escaping @Sendable (ReadTextResult) -> Void) {
        queue.async { [self] in
            guard !stopped, let engine, let frame = engine.latestJpeg(),
                  EngineClock.nowUs() - frame.capturePhoneUs <= ReadTextSettings.maxFrameAgeUs
            else {
                done(.noFreshFrame)
                return
            }
            let jpeg = frame.jpeg
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    if let text = try TextRecognition.recognize(jpeg: jpeg) {
                        done(.text(text))
                    } else {
                        done(.noText)
                    }
                } catch {
                    done(.failed("\(error)"))
                }
            }
        }
    }

    private func updateProfile(_ change: @escaping @Sendable (HearingProfile) throws -> HearingProfile) {
        queue.async { [self] in
            guard !stopped, let engine, let next = try? change(engine.profile) else { return }
            engine.setProfile(next)
            publishSlow()
        }
    }
}
#endif
