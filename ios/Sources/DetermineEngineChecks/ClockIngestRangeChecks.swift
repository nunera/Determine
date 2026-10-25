import DetermineEngine

/// Port of ClockIngestRangeTest.kt: ClockSyncTest, FrameAssemblerTest, ForwardRangeTest.
func clockIngestRangeChecks(_ t: Harness) {
    // MARK: ClockSync

    t.test("clock: offset and rtt follow the NTP formulas") {
        let c = ClockSync()
        c.onPingSent(1_000_000)
        // board = phone + 5 s; 10 ms each way; board holds 1 ms.
        let s = c.onPong(t1: 1_000_000, rx: 6_010_000, tx: 6_011_000, t4: 1_021_000)
        t.eq(5_000_000, s?.offsetUs)
        t.eq(20_000, s?.rttUs)
        t.eq(6_000_000, c.toBoard(1_000_000))
        t.eq(1_000_000, c.toPhone(6_000_000))
        t.yes(c.synced(1_021_000))
    }

    t.test("clock: asymmetric delay biases offset by half the asymmetry, min rtt sample wins") {
        let c = ClockSync()
        for x in [0, 100_000, 200_000] as [Int64] { c.onPingSent(x) }
        // queued sample: 80 ms outbound, 2 ms back -> offset biased +39 ms, rtt 82 ms
        c.onPong(t1: 0, rx: 5_080_000, tx: 5_080_000, t4: 82_000)
        t.eq(5_039_000, c.offsetUs)
        // clean sample: 2 ms each way
        c.onPong(t1: 100_000, rx: 5_102_000, tx: 5_102_000, t4: 104_000)
        t.eq(5_000_000, c.offsetUs)
        t.eq(4_000, c.rttUs)
        // another queued sample later does not displace the min-RTT one
        c.onPong(t1: 200_000, rx: 5_250_000, tx: 5_250_000, t4: 252_000)
        t.eq(5_000_000, c.offsetUs)
    }

    t.test("clock: outliers are rejected") {
        let c = ClockSync(maxRttUs: 200_000)
        t.isNil(c.onPong(t1: 0, rx: 10, tx: 10, t4: 100))                // no ping sent yet
        c.onPingSent(1_000)
        t.isNil(c.onPong(t1: 2_000, rx: 10, tx: 10, t4: 3_000))          // echo of a time we never sent
        t.isNil(c.onPong(t1: 1_000, rx: 10, tx: 10, t4: 500))            // receive before send
        t.isNil(c.onPong(t1: 1_000, rx: 10, tx: 5, t4: 2_000))           // tx before rx
        t.isNil(c.onPong(t1: 1_000, rx: 10, tx: 10, t4: 1_000 + 200_001)) // rtt too large
        t.no(c.synced(2_000))
        t.eq(5, c.rejected)
        t.notNil(c.onPong(t1: 1_000, rx: 10, tx: 10, t4: 1_000 + 200_000))
    }

    t.test("clock: window slides and staleness unsyncs") {
        let c = ClockSync(windowSize: 2, staleAfterUs: 1_000_000)
        for x in 0...2 { c.onPingSent(Int64(x) * 100_000) }
        c.onPong(t1: 0, rx: 1_000, tx: 1_000, t4: 2_000)                 // rtt 2 ms
        c.onPong(t1: 100_000, rx: 200_000, tx: 200_000, t4: 140_000)     // rtt 40 ms
        c.onPong(t1: 200_000, rx: 300_000, tx: 300_000, t4: 240_000)     // rtt 40 ms: first sample now out of window
        t.eq(40_000, c.rttUs)
        t.yes(c.synced(1_240_000))
        t.no(c.synced(1_240_001))
        c.reset()
        t.isNil(c.toBoard(0))
    }

    t.test("clock: arbitrary board u64 values never trap (wrapping arithmetic)") {
        let c = ClockSync()
        c.onPingSent(0)
        _ = c.onPong(t1: 0, rx: Int64.min, tx: Int64.max, t4: 100)
        _ = c.onPong(t1: 0, rx: Int64.max, tx: Int64.max, t4: 100)
        t.yes(true)
    }

    // MARK: FrameAssembler

    func chunks(_ id: Int64, _ data: [UInt8], cap: Int64 = 1_000, size: Int = 1400) -> [FramePkt] {
        let n = (data.count + size - 1) / size
        return (0..<n).map { i in
            let off = i * size
            return FramePkt(frameId: id, tCapUs: cap, w: 320, h: 240, totalLen: Int64(data.count), offset: Int64(off),
                            chunkIdx: i, chunkCount: n, data: Array(data[off..<min(off + size, data.count)]))
        }
    }
    let jpeg = (0..<3000).map { UInt8(truncatingIfNeeded: $0 * 7) }

    t.test("ingest: reassembles out-of-order chunks and ignores duplicates") {
        let a = FrameAssembler()
        let c = chunks(1, jpeg)
        t.isNil(a.onChunk(c[2], rxPhoneUs: 0))
        t.isNil(a.onChunk(c[0], rxPhoneUs: 1))
        t.isNil(a.onChunk(c[0], rxPhoneUs: 2))
        let f = a.onChunk(c[1], rxPhoneUs: 3)
        t.notNil(f)
        t.eq(jpeg, f?.jpeg ?? [])
        t.eq(0, f?.firstRxPhoneUs)
        t.eq(0, a.inFlightCount)
    }

    t.test("ingest: incomplete frame is dropped exactly after 150 ms") {
        let a = FrameAssembler()
        let c = chunks(1, jpeg)
        _ = a.onChunk(c[0], rxPhoneUs: 0); _ = a.onChunk(c[1], rxPhoneUs: 0)
        a.expire(150_000)
        t.eq(1, a.inFlightCount)                              // at the bound: still allowed
        t.isNil(a.onChunk(c[2], rxPhoneUs: 150_001))          // late chunk: frame expired, and id 1 is now old
        t.eq(0, a.inFlightCount)
    }

    t.test("ingest: old frame ids are dropped after a newer frame completes") {
        let a = FrameAssembler()
        let old = chunks(4, jpeg)
        _ = a.onChunk(old[0], rxPhoneUs: 0)
        for c in chunks(5, jpeg) { _ = a.onChunk(c, rxPhoneUs: 10) }
        t.eq(0, a.inFlightCount)                              // partial 4 discarded
        t.isNil(a.onChunk(old[1], rxPhoneUs: 20)); t.isNil(a.onChunk(old[2], rxPhoneUs: 20))
        t.eq(0, a.inFlightCount)
    }

    t.test("ingest: camera reboot restarts frame ids") {
        let a = FrameAssembler(rebootJump: 1000)
        for c in chunks(5000, jpeg) { _ = a.onChunk(c, rxPhoneUs: 0) }
        var got: RawFrame?
        for c in chunks(0, jpeg) { got = a.onChunk(c, rxPhoneUs: 10) ?? got }
        t.notNil(got)
    }

    t.test("ingest: memory bound and inconsistent chunks") {
        let a = FrameAssembler(maxInFlight: 2, maxFrameBytes: 10_000)
        _ = a.onChunk(chunks(1, jpeg)[0], rxPhoneUs: 0); _ = a.onChunk(chunks(2, jpeg)[0], rxPhoneUs: 0); _ = a.onChunk(chunks(3, jpeg)[0], rxPhoneUs: 0)
        t.eq(2, a.inFlightCount)
        func pkt(_ id: Int64, total: Int64, off: Int64, idx: Int, count: Int, _ n: Int) -> FramePkt {
            FramePkt(frameId: id, tCapUs: 1, w: 1, h: 1, totalLen: total, offset: off, chunkIdx: idx, chunkCount: count,
                     data: [UInt8](repeating: 0, count: n))
        }
        t.isNil(a.onChunk(pkt(9, total: 20_000, off: 0, idx: 0, count: 15, 10), rxPhoneUs: 0))  // too big
        t.isNil(a.onChunk(pkt(10, total: 100, off: 95, idx: 0, count: 1, 10), rxPhoneUs: 0))    // offset past end
        t.isNil(a.onChunk(pkt(11, total: 0, off: 0, idx: 0, count: 1, 0), rxPhoneUs: 0))        // empty frame
        // chunk_count lies (claims 1 chunk of 3000 bytes): impossible with <= 1400-byte chunks
        t.isNil(a.onChunk(pkt(12, total: 3000, off: 0, idx: 0, count: 1, 1400), rxPhoneUs: 0))
    }

    t.test("ingest: freshness gate bounds both ages and needs sync") {
        let g = FreshnessGate(maxAgeUs: 500_000)
        t.yes(g.isFresh(capturePhoneUs: 0, rxPhoneUs: 0, nowPhoneUs: 500_000))
        t.no(g.isFresh(capturePhoneUs: 0, rxPhoneUs: 400_000, nowPhoneUs: 500_001))
        t.no(g.isFresh(capturePhoneUs: 400_000, rxPhoneUs: 0, nowPhoneUs: 500_001))
        t.no(g.isFresh(capturePhoneUs: nil, rxPhoneUs: 0, nowPhoneUs: 0))
    }

    // MARK: ForwardRange

    t.test("range: canonical status maps to target, nothing seen or unknown") {
        t.eq(ForwardRange.target(mm: 640), ForwardTo.reading(status: RangeStatus.valid, mm: 640))
        t.eq(ForwardRange.nothingSeen, ForwardTo.reading(status: RangeStatus.noTarget, mm: 0))
        t.eq(ForwardRange.unknown, ForwardTo.reading(status: RangeStatus.unknown, mm: 0))
    }

    t.test("range: an unrecognised status is unknown, never far and never no target") {
        for s in [3, 5, 9, 127, 255] { t.eq(ForwardRange.unknown, ForwardTo.reading(status: s, mm: 300), "status \(s)") }
    }

    t.test("range: a near-looking distance with an invalid status is not a target") {
        t.eq(ForwardRange.unknown, ForwardTo.reading(status: RangeStatus.unknown, mm: 100))
    }
}
