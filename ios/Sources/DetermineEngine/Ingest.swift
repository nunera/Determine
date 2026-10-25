// Port of ingest/Ingest.kt: FRAME chunk reassembly and the freshness gate.

/// A JPEG fully reassembled from FRAME chunks (board capture time, phone receive time of the first chunk).
public struct RawFrame: Equatable, Sendable {
    public let frameId: Int64
    public let tCapBoardUs: Int64
    public let w: Int
    public let h: Int
    public let firstRxPhoneUs: Int64
    public let jpeg: [UInt8]
}

/// Reassembles FRAME chunks (PROTOCOL.md FRAME). Rules:
///  - a frame not complete within `timeoutUs` (150 ms) of its first chunk is dropped;
///  - chunks of a frame_id <= the newest completed/abandoned one are dropped (late, out of order) unless the id
///    jumped back by more than `rebootJump`, which means the camera rebooted and restarted counting;
///  - at most `maxInFlight` partial frames and `maxFrameBytes` per frame are held (memory bound);
///  - inconsistent chunks (bad offset/count/length) drop the whole frame.
public final class FrameAssembler {
    private struct Partial {
        let first: FramePkt
        let rxUs: Int64
        var buf: [UInt8]
        var got: [Bool]
        var gotCount = 0
        var bytes: Int64 = 0

        init(first: FramePkt, rxUs: Int64) {
            self.first = first
            self.rxUs = rxUs
            self.buf = [UInt8](repeating: 0, count: Int(first.totalLen))
            self.got = [Bool](repeating: false, count: first.chunkCount)
        }
    }

    private let timeoutUs: Int64
    private let maxInFlight: Int
    private let maxFrameBytes: Int
    private let rebootJump: Int64

    /// Insertion-ordered (oldest first), like the JVM's LinkedHashMap; at most `maxInFlight` entries.
    private var inFlight: [(id: Int64, partial: Partial)] = []
    private var newestDoneId: Int64 = -1
    public private(set) var dropped: Int64 = 0
    public private(set) var completed: Int64 = 0

    public init(timeoutUs: Int64 = 150_000, maxInFlight: Int = 3, maxFrameBytes: Int = 512 * 1024, rebootJump: Int64 = 1000) {
        self.timeoutUs = timeoutUs
        self.maxInFlight = maxInFlight
        self.maxFrameBytes = maxFrameBytes
        self.rebootJump = rebootJump
    }

    public var inFlightCount: Int { inFlight.count }

    /// Returns the frame when this chunk completes it.
    public func onChunk(_ c: FramePkt, rxPhoneUs: Int64) -> RawFrame? {
        expire(rxPhoneUs)
        if newestDoneId >= 0 && c.frameId <= newestDoneId {
            if newestDoneId - c.frameId > rebootJump { newestDoneId = -1; inFlight.removeAll() } else { return nil }
        }
        if c.totalLen <= 0 || c.totalLen > Int64(maxFrameBytes) || c.chunkCount == 0 || c.chunkIdx >= c.chunkCount ||
            Int64(c.chunkCount) * Int64(Wire.maxChunk) < c.totalLen {
            drop(c.frameId)
            return nil
        }
        var idx = inFlight.firstIndex { $0.id == c.frameId }
        if idx == nil {
            if inFlight.count >= maxInFlight {
                drop(inFlight[0].id)
            }
            inFlight.append((c.frameId, Partial(first: c, rxUs: rxPhoneUs)))
            idx = inFlight.count - 1
        }
        let i = idx!
        let f = inFlight[i].partial.first
        if c.totalLen != f.totalLen || c.chunkCount != f.chunkCount || c.tCapUs != f.tCapUs ||
            c.offset + Int64(c.data.count) > f.totalLen {
            drop(c.frameId)
            return nil
        }
        if inFlight[i].partial.got[c.chunkIdx] { return nil } // duplicate
        inFlight[i].partial.got[c.chunkIdx] = true
        inFlight[i].partial.gotCount += 1
        let off = Int(c.offset)
        inFlight[i].partial.buf.replaceSubrange(off..<(off + c.data.count), with: c.data)
        inFlight[i].partial.bytes += Int64(c.data.count)
        if inFlight[i].partial.gotCount < f.chunkCount { return nil }
        let p = inFlight.remove(at: i).partial
        if p.bytes != f.totalLen { dropped += 1; markDone(c.frameId); return nil }
        markDone(c.frameId)
        completed += 1
        // Older partials can no longer be useful: a newer frame is complete.
        for id in inFlight.map({ $0.id }) where id < c.frameId { drop(id) }
        return RawFrame(frameId: f.frameId, tCapBoardUs: f.tCapUs, w: f.w, h: f.h, firstRxPhoneUs: p.rxUs, jpeg: p.buf)
    }

    /// Drops partial frames older than the timeout. Call periodically too.
    public func expire(_ nowPhoneUs: Int64) {
        for id in inFlight.filter({ nowPhoneUs - $0.partial.rxUs > timeoutUs }).map({ $0.id }) { drop(id) }
    }

    /// Forget all frame history: the camera rebooted or reconnected and restarts frame_id.
    public func reset() {
        inFlight.removeAll()
        newestDoneId = -1
    }

    private func drop(_ id: Int64) {
        inFlight.removeAll { $0.id == id }
        dropped += 1
        markDone(id)
    }

    private func markDone(_ id: Int64) { if id > newestDoneId { newestDoneId = id } }
}

/// Arch §4.3: discard FRAME/RANGE whose offset-corrected capture age or receive age exceeds the bound.
/// Without clock sync the capture age cannot be known, so nothing is fresh (unknown is never "ok").
public final class FreshnessGate {
    private let maxAgeUs: Int64

    public init(maxAgeUs: Int64 = 500_000) { self.maxAgeUs = maxAgeUs }

    /// - Parameter capturePhoneUs: capture time converted to phone time, or nil when unsynced.
    public func isFresh(capturePhoneUs: Int64?, rxPhoneUs: Int64, nowPhoneUs: Int64) -> Bool {
        guard let cap = capturePhoneUs else { return false }
        return nowPhoneUs &- cap <= maxAgeUs && nowPhoneUs &- rxPhoneUs <= maxAgeUs
    }
}
