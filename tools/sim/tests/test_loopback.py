"""Runs both simulated boards over real UDP on localhost against an in-process fake phone that
uses only the reference codec, and against bench.py (bench self-test)."""
import os
import select
import socket
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import jpeg as jpg  # noqa: E402
from bench import Bench, run_fault_test, run_latency  # noqa: E402
from boards import ClockSpec, SimRig  # noqa: E402
from dtwire import FAULT_NO_CLOCK_SYNC, FAULT_WATCHDOG_ACTIVE, dw  # noqa: E402
from phonelib import ClockSync, FrameAssembler  # noqa: E402
from scenarios import Scenario  # noqa: E402
from scene import Box, zone_index  # noqa: E402
from simclock import host_us  # noqa: E402

JPEGS = jpg.make_test_jpegs(2, 160, 120)


def centre_wall(t):
    return [Box(2.0, 0.0, 1.2, 2.0, 1.0, 0.05)]


class FakePhone:
    def __init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 21)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.setblocking(False)
        self.port = self.sock.getsockname()[1]
        self.addr = {}            # board id → addr
        self.rx = []              # (host_us, addr, msg)
        self.sync = {dw.BOARD_CUE: ClockSync(), dw.BOARD_CAM: ClockSync()}
        self.seq = {}
        self.pinging = False
        self.next_ping = 0
        self.last_ping = None

    def send(self, board, msg):
        # CUE and PROFILE share one counter (PROTOCOL.md header seq rule).
        key = dw.Cue.TYPE if msg.TYPE in (dw.Cue.TYPE, dw.Profile.TYPE) else msg.TYPE
        s = self.seq.get(key, 0)
        self.seq[key] = s + 1
        self.sock.sendto(dw.encode(msg, s), self.addr[board])
        return s

    def run(self, seconds):
        end = host_us() + int(seconds * 1e6)
        while host_us() < end:
            now = host_us()
            if self.pinging and now >= self.next_ping:
                for b in list(self.addr):
                    self.send(b, dw.Ping(host_us()))
                self.last_ping = now
                self.next_ping = now + 100_000
            select.select([self.sock], [], [], 0.002)
            while True:
                try:
                    pkt, addr = self.sock.recvfrom(2048)
                except BlockingIOError:
                    break
                t4 = host_us()
                _, msg = dw.decode(pkt)
                if isinstance(msg, dw.Beat):
                    self.addr.setdefault(msg.board, addr)
                if isinstance(msg, dw.Pong):
                    board = next(b for b, a in self.addr.items() if a == addr)
                    self.sync[board].add(msg.t_phone_us, msg.t_board_rx_us, msg.t_board_tx_us, t4)
                self.rx.append((t4, addr, msg))

    def of(self, typ, since=0, board_addr=None):
        return [(t, m) for t, a, m in self.rx if isinstance(m, typ) and t >= since and
                (board_addr is None or a == board_addr)]

    def wait_ack(self, seq, timeout=1.0):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for _, m in self.of(dw.Ack):
                if m.cue_seq == seq:
                    return m
            self.run(0.02)
        return None


class LoopbackTest(unittest.TestCase):
    def make_rig(self, phone_port, scen=None, log=None):
        scen = scen or Scenario("t", "test", 30.0, 1, centre_wall)
        self.lines = []
        rig = SimRig(scen, phone=("127.0.0.1", phone_port), bind="127.0.0.1", cue_port=0, cam_port=0,
                     log=log or self.lines.append, jpegs=JPEGS, fps=11.0,
                     cue_clock=ClockSpec(2.5, 35.0), cam_clock=ClockSpec(4.2, -22.0))
        return rig

    def test_full_protocol_against_fake_phone(self):
        ph = FakePhone()
        rig = self.make_rig(ph.port)
        rig.start()
        try:
            ph.run(0.35)
            beats = ph.of(dw.Beat)
            self.assertGreaterEqual(len(beats), 4)
            self.assertTrue(all(m.faults & FAULT_NO_CLOCK_SYNC for _, m in beats), "NO_CLOCK_SYNC before PING")
            self.assertEqual({m.board for _, m in beats}, {dw.BOARD_CUE, dw.BOARD_CAM})

            ph.pinging = True
            t_b = host_us()
            ph.run(1.5)
            t_e = host_us()
            # --- clock sync recovers each board's configured offset (localhost: tolerance 1.5 ms)
            for board, clk in ((dw.BOARD_CUE, rig.cue.clock), (dw.BOARD_CAM, rig.cam.clock)):
                s = ph.sync[board]
                self.assertTrue(s.synced)
                truth = clk.offset_at(s._best().t4)
                self.assertLess(abs(s.offset - truth), 1500, (board, s.offset, truth))
            self.assertAlmostEqual((ph.sync[dw.BOARD_CAM].offset - ph.sync[dw.BOARD_CUE].offset) / 1e6, 1.7, delta=0.01)
            late_beats = ph.of(dw.Beat, since=t_b + 300_000)
            self.assertTrue(late_beats and not any(m.faults & FAULT_NO_CLOCK_SYNC for _, m in late_beats))
            # --- rates
            dur = (t_e - t_b) / 1e6
            ranges = ph.of(dw.Range, since=t_b)
            self.assertAlmostEqual(len(ranges) / dur, 15.0, delta=2.0)
            r = ranges[-1][1]
            self.assertIn(r.status[zone_index(3, 3)], dw.VALID_STATUS)
            self.assertAlmostEqual(r.mm[zone_index(3, 3)], 2005, delta=60)
            self.assertEqual(r.status[0], dw.STATUS_NO_TARGET)
            beats_cue = [m for _, m in ph.of(dw.Beat, since=t_b) if m.board == dw.BOARD_CUE]
            self.assertAlmostEqual(len(beats_cue) / dur, 10.0, delta=1.5)
            asm, frames = FrameAssembler(), []
            for t, m in ph.of(dw.Frame, since=t_b):
                f = asm.add(m, t)
                if f:
                    frames.append(f)
            self.assertTrue(10.0 * 0.85 <= len(frames) / dur <= 12.0 * 1.15, len(frames) / dur)
            for f in frames:
                self.assertEqual(f.jpeg[:2], b"\xFF\xD8")
                self.assertEqual(f.jpeg[-2:], b"\xFF\xD9")
                self.assertEqual((f.w, f.h), (160, 120))
            ids = [f.frame_id for f in frames]
            self.assertEqual(ids, sorted(ids))
            # capture timestamp is in the cam board's clock, ~45 ms before send
            age_ms = (frames[-1].done_rx_us - (frames[-1].t_cap_board_us - ph.sync[dw.BOARD_CAM].offset)) / 1000
            self.assertTrue(40 <= age_ms <= 120, age_ms)

            # --- CUE handling + ACK (interrupt flag set so back-to-back cues are not BUSY)
            def bt():
                return ph.sync[dw.BOARD_CUE].board_time(host_us())

            def send_cue(gl, gr, deadline, cue_id=1, flags=1):
                return ph.send(dw.BOARD_CUE, dw.Cue(host_us(), deadline, cue_id, dw.SIDE_BOTH, gl, gr, 1, 200, 200,
                                                    flags, 150))
            a = ph.wait_ack(send_cue(-20, -20, bt() + 150_000))
            self.assertEqual((a.status, a.applied_gain_l_db, a.applied_gain_r_db), (dw.ACK_PLAYED, -20, -20))
            a = ph.wait_ack(send_cue(0, -20, bt() + 150_000))
            self.assertEqual((a.status, a.applied_gain_l_db), (dw.ACK_PLAYED_CLAMPED, -10))
            a = ph.wait_ack(send_cue(-20, -20, bt() - 1_000))
            self.assertEqual(a.status, dw.ACK_EXPIRED)
            a = ph.wait_ack(send_cue(-20, -20, 0))
            self.assertEqual(a.status, dw.ACK_NO_SYNC)
            a = ph.wait_ack(send_cue(-20, -20, bt() + 150_000, cue_id=99))
            self.assertEqual(a.status, dw.ACK_BAD_CUE)
            a = ph.wait_ack(ph.send(dw.BOARD_CUE, dw.Profile(-25, dw.MODE_STEREO, 255)))
            self.assertEqual((a.status, a.applied_gain_l_db), (dw.ACK_PLAYED, -25))
            a = ph.wait_ack(send_cue(-20, -30, bt() + 150_000))
            self.assertEqual((a.status, a.applied_gain_l_db, a.applied_gain_r_db), (dw.ACK_PLAYED_CLAMPED, -25, -30))
            # BUSY without interrupt flag right after a cue
            s1 = send_cue(-30, -30, bt() + 150_000, cue_id=2, flags=1)
            s2 = send_cue(-30, -30, bt() + 150_000, cue_id=2, flags=0)
            self.assertEqual(ph.wait_ack(s1).status, dw.ACK_PLAYED)
            self.assertEqual(ph.wait_ack(s2).status, dw.ACK_BUSY_DROPPED)
            cue_lines = [ln for ln in self.lines if ln.startswith("CUE ")]
            self.assertTrue(any("id=OBSTACLE_FAR side=BOTH gl=-10 gr=-20 haptic=PULSE40 status=PLAYED_CLAMPED" in ln
                                for ln in cue_lines), cue_lines)

            # --- watchdog over the network: stop pinging
            ph.pinging = False
            last_ping = ph.last_ping
            ph.run(1.3)
            wd = [t for t, m in ph.of(dw.Beat, since=last_ping) if m.board == dw.BOARD_CUE and
                  m.faults & FAULT_WATCHDOG_ACTIVE]
            self.assertTrue(wd, "no WATCHDOG_ACTIVE beat")
            self.assertTrue(780_000 <= wd[0] - last_ping <= 1_000_000, wd[0] - last_ping)
            wd_lines = [ln for ln in self.lines if "src=watchdog" in ln]
            self.assertEqual(len(wd_lines), 1)  # one alarm so far; the next only after 5 s
            self.assertIn("id=SYSTEM_OFF side=BOTH gl=-25 gr=-25 haptic=LSS", wd_lines[0])  # min(ceiling, -20)
            self.assertTrue(any(ln.startswith("WATCHDOG enter") for ln in self.lines))
            ph.pinging = True
            ph.next_ping = 0
            t_r = host_us()
            ph.run(0.4)
            after = [m for t, m in ph.of(dw.Beat, since=t_r + 150_000) if m.board == dw.BOARD_CUE]
            self.assertTrue(after and not any(m.faults & FAULT_WATCHDOG_ACTIVE for m in after))
            self.assertTrue(any(ln.startswith("WATCHDOG exit") for ln in self.lines))
        finally:
            rig.stop()

    def test_dropout_silences_board_both_ways(self):
        ph = FakePhone()
        scen = Scenario("d", "dropout", 30.0, 1, centre_wall, cue_dropouts=[(0.6, 1.4)])
        rig = self.make_rig(ph.port, scen)
        rig.start()
        try:
            ph.pinging = True
            ph.run(2.0)
            cue_addr = ph.addr[dw.BOARD_CUE]
            cam_addr = ph.addr[dw.BOARD_CAM]
            in_window = [t for t, a, _ in ph.rx if a == cue_addr and rig.t0 + 620_000 <= t <= rig.t0 + 1_380_000]
            self.assertEqual(in_window, [])
            cam_in = [t for t, a, _ in ph.rx if a == cam_addr and rig.t0 + 620_000 <= t <= rig.t0 + 1_380_000]
            self.assertGreater(len(cam_in), 20)
            after = [t for t, a, _ in ph.rx if a == cue_addr and t > rig.t0 + 1_450_000]
            self.assertTrue(after, "cue board did not come back")
            # it heard no PINGs for 0.8 s → its own watchdog fired during the dropout
            self.assertTrue(any("src=watchdog" in ln for ln in self.lines))
            self.assertTrue(any("event=dropout_start board=cue" in ln for ln in self.lines))
        finally:
            rig.stop()

    def test_bench_self_test_against_sim(self):
        bench = Bench(bind="127.0.0.1", port=0)
        rig = self.make_rig(bench.port)
        rig.start()
        try:
            self.assertTrue(bench.wait_for(lambda: bench.synced("cue") and bench.synced("cam"), 3.0))
            bench.poll(0.5)
            s = bench.summary()
            for name, clk in (("cue", rig.cue.clock), ("cam", rig.cam.clock)):
                self.assertLess(abs(s["boards"][name]["offset_us"] - clk.offset_at(host_us())), 2000)
            self.assertAlmostEqual(s["range_hz"], 15.0, delta=2.5)
            self.assertTrue(9.0 <= s["frame_fps"] <= 13.0, s["frame_fps"])
            msgs = []
            res = run_latency(bench, 5, "OBSTACLE_FAR", dw.SIDE_L, -20, 0.3, msgs.append)
            self.assertEqual(res["acked"], 5)
            self.assertEqual(res["status"], {"PLAYED": 5})
            self.assertLess(res["p95_ms"], 50.0)
            self.assertTrue(run_fault_test(bench, 1.3, msgs.append), msgs)
            self.assertTrue(any(m.startswith("PASS: WATCHDOG_ACTIVE") for m in msgs), msgs)
            seq = bench.send_profile(-30, dw.MODE_HAPTIC_ONLY, 100)
            r = bench.wait_ack(seq)
            self.assertEqual((r.status, r.applied), ("PLAYED", (-30, -30)))
            r = bench.wait_ack(bench.send_cue("OBSTACLE_NEAR", dw.SIDE_BOTH, -20, flags=1))
            self.assertEqual(r.applied, (dw.GAIN_MUTE, dw.GAIN_MUTE))  # HAPTIC_ONLY mutes audio
            self.assertIn("r3", "\n".join(bench.render(color=False).splitlines()))
        finally:
            rig.stop()
            bench.close()


if __name__ == "__main__":
    unittest.main()
