#if canImport(AVFoundation)
import AVFoundation
import CoreImage
import CoreMedia
import Foundation
import ImageIO

/// What the Judge screen and Main screen show about the iPhone camera fallback.
struct PhoneCameraStatus: Equatable, Sendable {
    var frames = 0
    var skipped = 0
    var width = 0
    var height = 0
    var lastEncodeMs: Double?
    /// Non-nil while the camera cannot deliver frames (permission, interruption, runtime error). The engine's
    /// frame-freshness rule independently takes the system to UNAVAILABLE (`frame_stale`).
    var error: String?
}

/// Tier 0 `FrameSourceKind.phoneCamera` (Arch §11.4): the iPhone's back camera, worn upright in portrait on a
/// headband, instead of the ESP32-CAM. Port of Android `PhoneCameraSource`: ~640×480 sensor frames (so 480×640 once
/// upright), at most one every 95 ms (~10 fps, PROTOCOL.md FRAME 10–15 fps), JPEG quality 75, delivered with the
/// capture time on the engine clock. Late frames are discarded by the output, never queued.
///
/// `@unchecked Sendable`: owns its capture queues and a lock; `session` is touched only on `sessionQueue`, the
/// per-frame state only on `sampleQueue`, and the status under `lock`.
final class PhoneCameraSource: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    static let minIntervalUs: Int64 = 95_000
    static let jpegQuality = 0.75
    private static let unknownBaseUs: Int64 = 10_000_000
    private static let fallbackPipelineUs: Int64 = 100_000

    private let onFrame: @Sendable ([UInt8], Int, Int, Int64) -> Void
    private let clockUs: @Sendable () -> Int64
    private let sessionQueue = DispatchQueue(label: "org.determine.camera.session")
    private let sampleQueue = DispatchQueue(label: "org.determine.camera.samples", qos: .userInitiated)
    private let lock = NSLock()
    private let session = AVCaptureSession()
    private var observers: [NSObjectProtocol] = []
    private var stopped = false
    private var status = PhoneCameraStatus()

    // sampleQueue only
    private var lastAcceptedUs: Int64?
    private let ciContext = CIContext()

    /// `onFrame` runs on the camera queue with an upright JPEG and its capture time on `clockUs`' time base.
    init(clockUs: @escaping @Sendable () -> Int64, onFrame: @escaping @Sendable ([UInt8], Int, Int, Int64) -> Void) {
        self.clockUs = clockUs
        self.onFrame = onFrame
    }

    func currentStatus() -> PhoneCameraStatus {
        lock.lock()
        defer { lock.unlock() }
        return status
    }

    private func update(_ change: (inout PhoneCameraStatus) -> Void) {
        lock.lock()
        change(&status)
        lock.unlock()
    }

    private var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    // MARK: Permission

    static func permissionDescription(_ status: AVAuthorizationStatus) -> String? {
        switch status {
        case .authorized: return nil
        case .notDetermined: return "camera permission not asked yet"
        case .denied: return "camera permission denied: allow Camera for Determine in iOS Settings"
        case .restricted: return "camera access is restricted on this iPhone"
        @unknown default: return "camera permission unknown"
        }
    }

    /// Asks for camera access if it was never asked. Returns the resulting status.
    static func requestPermission() async -> AVAuthorizationStatus {
        if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
            _ = await AVCaptureDevice.requestAccess(for: .video)
        }
        return AVCaptureDevice.authorizationStatus(for: .video)
    }

    // MARK: Lifecycle

    func start() {
        sessionQueue.async { [self] in
            guard !isStopped else { return }
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                configureAndRun()
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { [self] granted in
                    sessionQueue.async { [self] in
                        guard !isStopped else { return }
                        if granted {
                            configureAndRun()
                        } else {
                            update { $0.error = Self.permissionDescription(.denied) }
                        }
                    }
                }
            case let other:
                update { $0.error = Self.permissionDescription(other) }
            }
        }
    }

    func stop() {
        lock.lock()
        stopped = true
        lock.unlock()
        sessionQueue.async { [self] in
            for o in observers { NotificationCenter.default.removeObserver(o) }
            observers.removeAll()
            if session.isRunning { session.stopRunning() }
        }
    }

    private func configureAndRun() {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            update { $0.error = "cannot open back camera: no back camera on this device" }
            return
        }
        do {
            let input = try AVCaptureDeviceInput(device: device)
            session.beginConfiguration()
            // 640×480 (4:3), like the Android fallback's target size.
            session.sessionPreset = .vga640x480
            guard session.canAddInput(input) else {
                session.commitConfiguration()
                update { $0.error = "cannot open back camera: input rejected" }
                return
            }
            session.addInput(input)
            let output = AVCaptureVideoDataOutput()
            output.alwaysDiscardsLateVideoFrames = true
            output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            output.setSampleBufferDelegate(self, queue: sampleQueue)
            guard session.canAddOutput(output) else {
                session.commitConfiguration()
                update { $0.error = "cannot open back camera: output rejected" }
                return
            }
            session.addOutput(output)
            session.commitConfiguration()
        } catch {
            update { $0.error = "cannot open back camera: \(error.localizedDescription)" }
            return
        }
        observe()
        update { $0.error = "camera starting" }
        session.startRunning()
        // Frames clear this; a session that never starts keeps the visible reason.
        if !session.isRunning { update { $0.error = "camera session did not start" } }
    }

    private func observe() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: nil) { [self] note in
            var reason = "interrupted"
            if let raw = note.userInfo?[AVCaptureSessionInterruptionReasonKey] as? Int,
               let r = AVCaptureSession.InterruptionReason(rawValue: raw) {
                switch r {
                case .videoDeviceNotAvailableInBackground: reason = "app not in the foreground"
                case .videoDeviceInUseByAnotherClient: reason = "camera in use by another app"
                case .videoDeviceNotAvailableWithMultipleForegroundApps: reason = "camera unavailable with multiple apps on screen"
                case .videoDeviceNotAvailableDueToSystemPressure: reason = "camera unavailable: device too hot or under load"
                default: reason = "interrupted (\(raw))"
                }
            }
            update { $0.error = "camera interrupted: \(reason)" }
        })
        observers.append(center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: nil) { [self] _ in
            update { $0.error = "camera resuming" }
        })
        observers.append(center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: nil) { [self] note in
            let err = note.userInfo?[AVCaptureSessionErrorKey] as? AVError
            update { $0.error = "camera error: \(err?.localizedDescription ?? "unknown")" }
            if err?.code == .mediaServicesWereReset {
                sessionQueue.async { [self] in
                    if !isStopped, !session.isRunning { session.startRunning() }
                }
            }
        })
    }

    // MARK: Frames

    /// Capture time on the engine clock. The sample timestamp is on the host-time clock (`mach_absolute_time` base);
    /// only its age is used, so the engine clock's different base (and sleep behaviour) does not matter. Mirrors
    /// Android `Nv21.capturePhoneUs`: never in the future, and a timestamp that makes no sense is treated as
    /// "delivered ~100 ms ago", which overstates rather than understates the age.
    static func capturePhoneUs(nowUs: Int64, ageUs: Int64) -> Int64 {
        if abs(ageUs) > unknownBaseUs { return nowUs - fallbackPipelineUs }
        return nowUs - max(ageUs, 0)
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard !isStopped, let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let nowUs = clockUs()
        let age = CMTimeSubtract(CMClockGetTime(CMClockGetHostTimeClock()), CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
        let captureUs = Self.capturePhoneUs(nowUs: nowUs, ageUs: Int64(CMTimeGetSeconds(age) * 1_000_000))
        if let last = lastAcceptedUs, captureUs - last < Self.minIntervalUs {
            update { $0.skipped += 1 }
            return
        }
        lastAcceptedUs = captureUs
        // The back camera delivers landscape buffers; the phone is worn upright, so rotate to portrait.
        let image = CIImage(cvPixelBuffer: pixels).oriented(.right)
        let w = Int(image.extent.width)
        let h = Int(image.extent.height)
        let options = [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): Self.jpegQuality]
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let data = ciContext.jpegRepresentation(of: image, colorSpace: space, options: options) else {
            update { $0.skipped += 1 }
            return
        }
        let encodeMs = Double(clockUs() - nowUs) / 1000
        update {
            $0.frames += 1
            $0.width = w
            $0.height = h
            $0.lastEncodeMs = encodeMs
            $0.error = nil
        }
        onFrame([UInt8](data), w, h, captureUs)
    }
}
#endif
