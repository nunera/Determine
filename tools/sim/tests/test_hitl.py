"""HITL signal injection and real UDP forwarding; no firmware or JVM required.

Fixed board reply ports use 127.0.0.2/3, never the live simulator's 127.0.0.1 ports.
"""
import io
import json
import os
import socket
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import hitl  # noqa: E402
import jpeg as jpg  # noqa: E402
import wsserver  # noqa: E402
from dtwire import dw  # noqa: E402
from hitl import FrameBuffer, HitlRelay, fuse_range  # noqa: E402
from world_bridge import Bridge, Reading, WorldFeed, build_parser  # noqa: E402


class FusionTest(unittest.TestCase):
    def test_nearest_valid_and_unknown_real_is_preserved(self):
        now = 2_000_000
        for real_status in (dw.RANGE_VALID, dw.RANGE_NO_TARGET, dw.RANGE_UNKNOWN):
            for world_status in (dw.RANGE_VALID, dw.RANGE_NO_TARGET, dw.RANGE_UNKNOWN):
                for world_mm in (499, 500, 501, 899, 900, 901, 1199, 1200, 1201):
                    real = dw.Range(123456789, 900 if real_status == dw.RANGE_VALID else 0, real_status)
                    world = Reading(now, world_mm if world_status == dw.RANGE_VALID else 0, world_status)
                    with self.subTest(real=real_status, world=world_status, mm=world_mm):
                        out, source = fuse_range(real, world, now, "fuse")
                        wins = world_status == dw.RANGE_VALID and (
                            real_status == dw.RANGE_NO_TARGET or
                            (real_status == dw.RANGE_VALID and world_mm < real.mm))
                        self.assertEqual(source, "world" if wins else "real")
                        self.assertEqual(out.t_us, real.t_us)
                        self.assertEqual((out.mm, out.status),
                                         (world.mm, world.status) if wins else (real.mm, real.status))
                        if not wins:
                            self.assertIs(out, real)

    def test_exact_freshness_boundary_and_stale_fallback(self):
        real = dw.Range(55, 700, dw.RANGE_VALID)
        world = Reading(1_000_000, 400, dw.RANGE_VALID)
        for mode in ("world", "fuse"):
            for age in (0, 499_999, 500_000):
                out, source = fuse_range(real, world, world.host_us + age, mode)
                self.assertEqual((out.mm, out.t_us, source), (400, 55, "world"))
            for age in (-1, 500_001, 1_000_000):
                out, source = fuse_range(real, world, world.host_us + age, mode)
                self.assertIs(out, real)
                self.assertEqual(source, "real-stale-world")
            self.assertEqual(fuse_range(real, None, 9_000_000, mode), (real, "real-stale-world"))

    def test_world_replaces_status_but_real_mode_is_byte_passthrough(self):
        real = dw.Range(0x123456789, 0, dw.RANGE_UNKNOWN)
        for status in (dw.RANGE_VALID, dw.RANGE_NO_TARGET, dw.RANGE_UNKNOWN):
            world = Reading(1, 321, status)
            out, source = fuse_range(real, world, 2, "world")
            self.assertEqual((out.mm, out.status, out.t_us, source),
                             (321 if status == dw.RANGE_VALID else 0, status, real.t_us, "world"))
            out, source = fuse_range(real, world, 99_000_000, "real")
            self.assertIs(out, real)
            self.assertEqual(source, "real")
        with self.assertRaises(ValueError):
            fuse_range(real, None, 1, "nearest")


class RelayTest(unittest.TestCase):
    def setUp(self):
        ports = mock.patch.dict(hitl.BOARD_PORTS, {"cue": 0, "cam": 0})
        ports.start()
        self.addCleanup(ports.stop)
        self.now = [10_000_000]
        self.feed = WorldFeed(host=lambda: self.now[0])
        self.engine = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.engine.bind(("127.0.0.1", 0))
        self.engine.settimeout(1)
        self.addCleanup(self.engine.close)
        self.events, self.previews, self.notes = [], [], []
        self.relay = HitlRelay(("127.0.0.1", 0), self.engine.getsockname(), self.feed,
                               host=lambda: self.now[0], emit=self.events.append,
                               preview=self.previews.append, note=self.notes.append)
        self.addCleanup(self.relay.stop)

    def receive(self):
        return self.engine.recvfrom(65535)

    def send_board(self, message, seq=42, role="cue"):
        addr = ("127.0.0.2" if role == "cue" else "127.0.0.3", 49123)
        raw = dw.encode(message, seq)
        self.relay.process_board(raw, addr)
        return raw

    def fixed_board(self, role):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.addCleanup(sock.close)
        sock.bind(("127.0.0.2" if role == "cue" else "127.0.0.3", hitl.BOARD_PORTS[role]))
        hitl.BOARD_PORTS[role] = sock.getsockname()[1]
        sock.settimeout(1)
        return sock

    def test_stable_role_twins_and_byte_exact_reverse_fixed_ports(self):
        addresses = {}
        for role, board_id in (("cue", dw.BOARD_CUE), ("cam", dw.BOARD_CAM)):
            board = self.fixed_board(role)
            raw = self.send_board(dw.Beat(board_id, 0, 0x123, 987654321, -57, 27, 90), role=role)
            received, twin = self.receive()
            self.assertEqual(received, raw)
            self.assertEqual(twin[0], "127.0.0.1")
            addresses[role] = twin
            # A source-port change must not change the role's engine-facing identity.
            pong = dw.encode(dw.Pong(222, 333, 444), 0xFFFFFFFF)
            self.relay.process_board(pong, (board.getsockname()[0], 49124))
            received, same_twin = self.receive()
            self.assertEqual((received, same_twin), (pong, twin))
            for msg in (dw.Ping(777), dw.Profile(dw.MODE_STEREO, 180),
                        dw.Cue(10, 20, 3, dw.SIDE_BOTH, 1, 120, 150, 0, 80)):
                reply = dw.encode(msg, 91)
                self.relay.process_engine(role, reply, self.engine.getsockname())
                forwarded, source = board.recvfrom(65535)
                self.assertEqual(forwarded, reply)
                self.assertEqual(source, self.relay.address)
        self.assertNotEqual(addresses["cue"], addresses["cam"])

    def test_range_rewrite_preserves_sequence_board_clock_and_stale_bytes(self):
        self.feed.put_range(499, dw.RANGE_VALID)
        raw = self.send_board(dw.Range(0x123456789AB, 1200, dw.RANGE_VALID), seq=0xFFFFFFFE)
        rewritten, _ = self.receive()
        seq, out = dw.decode(rewritten)
        self.assertEqual((seq, out.t_us, out.mm, out.status), (0xFFFFFFFE, 0x123456789AB, 499, dw.RANGE_VALID))
        self.assertEqual(self.relay.status()["range"]["source"], "world")
        self.now[0] += 500_001
        self.relay.process_board(raw, ("127.0.0.2", 49123))
        self.assertEqual(self.receive()[0], raw)
        self.assertEqual(self.relay.status()["range"]["source"], "real-stale-world")
        self.relay.set_modes(range="real")
        self.feed.put_range(100, dw.RANGE_VALID)
        self.relay.process_board(raw, ("127.0.0.2", 49123))
        self.assertEqual(self.receive()[0], raw)

    def test_unknown_pong_malformed_and_wrong_pinned_board_are_not_forwarded(self):
        self.relay.process_board(dw.encode(dw.Pong(1, 2, 3), 0), ("127.0.0.4", 49000))
        self.relay.process_board(b"not wire v2", ("127.0.0.2", 49000))
        self.relay.pins["cue"] = "127.0.0.2"
        self.relay.process_board(dw.encode(dw.Range(10, 450, 0), 5), ("127.0.0.4", 49000))
        self.engine.settimeout(0.03)
        with self.assertRaises(socket.timeout):
            self.receive()
        self.assertIsNone(self.relay.status()["cue"]["addr"])

    def test_real_ack_is_accepted_not_an_invented_motor_onset(self):
        board = self.fixed_board("cue")
        self.send_board(dw.Beat(dw.BOARD_CUE, 0, 0, 1000, -40, 22, 99))
        self.receive()
        command = dw.Cue(123, 456, 3, dw.SIDE_BOTH, dw.HAPTIC["PULSE40"], 80, 120, 0, 100)
        self.relay.process_engine("cue", dw.encode(command, 61), self.engine.getsockname())
        board.recvfrom(2048)
        raw = self.send_board(dw.Ack(61, dw.ACK_APPLIED, 123456), seq=987)
        self.assertEqual(self.receive()[0], raw)
        self.assertEqual(len(self.events), 1)
        ev = self.events[0]
        self.assertEqual((ev["t"], ev["seq"], ev["source"], ev["status"]), ("played", 61, "real", "APPLIED"))
        self.assertEqual((ev["hl"], ev["hr"]), (80, 120))
        self.assertTrue(any("accepted" in n and "not physical motor onset" in n for n in self.notes))
        self.send_board(dw.Ack(61, dw.ACK_APPLIED, 123456))  # duplicate is still relayed but no duplicate pulse
        self.receive()
        self.assertEqual(len(self.events), 1)

    def test_real_loop_no_traffic_until_a_board_sends_and_no_synthetic_outage_packets(self):
        board = self.fixed_board("cue")
        self.relay.start()
        self.engine.settimeout(0.04)
        with self.assertRaises(socket.timeout):
            self.receive()
        raw = dw.encode(dw.Beat(dw.BOARD_CUE, 0, 64, 999999, -65, 24, 88), 713)
        board.sendto(raw, self.relay.address)
        self.engine.settimeout(1)
        received, twin = self.receive()
        self.assertEqual(received, raw)
        reply = dw.encode(dw.Ping(123456789), 714)
        self.engine.sendto(reply, twin)
        self.assertEqual(board.recvfrom(2048), (reply, self.relay.address))
        self.now[0] += 2_000_000
        self.assertEqual(self.relay.status()["cue"]["ageMs"], 2000)
        self.engine.settimeout(0.04)
        with self.assertRaises(socket.timeout):
            self.receive()

    def test_status_has_browser_contract_and_live_world_age(self):
        self.feed.put_range(500, dw.RANGE_VALID)
        self.send_board(dw.Beat(dw.BOARD_CAM, 0, 512, 88, -61, 20, 50), role="cam")
        self.receive()
        self.now[0] += 200_000
        hello = self.relay.hello()
        self.assertEqual(set(hello), {"enabled", "listen", "range", "camera", "compositeAvailable"})
        status = self.relay.status()
        self.assertEqual(set(status), {"t", "cue", "cam", "range", "camera"})
        self.assertEqual(status["cam"], {"addr": "127.0.0.3:49123", "ageMs": 200,
                                         "rssi": -61, "faults": 512, "fps": 0})
        self.assertEqual(status["range"]["worldAgeMs"], 200)

    @unittest.skipUnless(hitl.composite_available(), "Pillow is optional outside composite mode")
    def test_composite_alpha_rechunks_with_original_metadata_and_sequence_wrap(self):
        self.relay.set_modes(camera="composite")
        overlay = io.BytesIO()
        hitl.Image.new("RGBA", (320, 240), (255, 0, 0, 128)).save(overlay, format="PNG")
        self.relay.put_overlay(overlay.getvalue())
        original = io.BytesIO()
        hitl.Image.new("RGB", (320, 240), (0, 0, 255)).save(original, format="JPEG")
        chunks = jpg.chunk_frame(123, 987654321, 320, 240, original.getvalue(), max_chunk=700)
        # Reordered input: output seq must derive from chunk index zero, not first arrival.
        for i in reversed(range(len(chunks))):
            self.send_board(chunks[i], (0xFFFFFFFF + i) & 0xFFFFFFFF, role="cam")
        output, seqs, twin = [], [], None
        while not output or len(output) < output[0].chunk_count:
            raw, addr = self.receive()
            seq, f = dw.decode(raw)
            seqs.append(seq)
            output.append(f)
            if twin is not None:
                self.assertEqual(addr, twin)
            twin = addr
            self.assertEqual((f.frame_id, f.t_cap_us, f.w, f.h), (123, 987654321, 320, 240))
            self.assertLessEqual(len(f.data), dw.MAX_CHUNK)
        self.assertEqual(seqs, [(0xFFFFFFFF + i) & 0xFFFFFFFF for i in range(len(output))])
        encoded = b"".join(f.data for f in output)
        with hitl.Image.open(io.BytesIO(encoded)) as result:
            r, g, b = result.getpixel((160, 120))
            self.assertTrue(110 <= r <= 145 and g <= 10 and 110 <= b <= 145, (r, g, b))
        self.assertEqual(self.previews, [b"\x11" + encoded])
        self.assertEqual(self.relay.status()["camera"],
                         {"mode": "composite", "framesOut": 1, "composited": 1, "lastError": None})

    @unittest.skipUnless(hitl.composite_available(), "Pillow is optional outside composite mode")
    def test_stale_overlay_and_failed_decode_forward_original_chunks(self):
        self.relay.set_modes(camera="composite")
        overlay = io.BytesIO()
        hitl.Image.new("RGBA", (320, 240), (255, 255, 255, 255)).save(overlay, format="PNG")
        self.relay.put_overlay(overlay.getvalue())
        self.now[0] += 1_000_001
        chunks = jpg.chunk_frame(1, 77, 320, 240, b"a real but undecodable JPEG", max_chunk=10)
        sent = [self.send_board(f, 21 + i, role="cam") for i, f in enumerate(chunks)]
        self.assertEqual([self.receive()[0] for _ in chunks], sent)
        self.assertEqual(self.previews, [b"\x11a real but undecodable JPEG"])
        self.assertIsNone(self.relay.last_error)  # stale overlay does not even attempt decoding
        self.relay.put_overlay(overlay.getvalue())
        chunks = jpg.chunk_frame(2, 88, 320, 240, b"undecodable JPEG", max_chunk=10)
        sent = [self.send_board(f, 31 + i, role="cam") for i, f in enumerate(chunks)]
        self.assertEqual([self.receive()[0] for _ in chunks], sent)
        self.assertIn("camera composite failed", self.relay.last_error)
        self.assertEqual(self.relay.composited, 0)

    def test_real_camera_passthrough_preview_rate_and_incomplete_deadline(self):
        chunks = jpg.chunk_frame(5, 800, 10, 10, b"real camera bytes", max_chunk=7)
        for i, f in enumerate(chunks):
            original = self.send_board(f, 50 + i, role="cam")
            self.assertEqual(self.receive()[0], original)
        self.assertEqual(self.previews, [b"\x11real camera bytes"])
        self.send_board(jpg.chunk_frame(6, 801, 10, 10, b"next frame")[0], role="cam")
        self.receive()
        self.assertEqual(len(self.previews), 1)  # <= 4 fps
        self.now[0] += 250_000
        self.send_board(jpg.chunk_frame(7, 802, 10, 10, b"third frame")[0], role="cam")
        self.receive()
        self.assertEqual(self.previews[-1], b"\x11third frame")
        pending = jpg.chunk_frame(8, 803, 10, 10, b"late frame", max_chunk=5)
        self.send_board(pending[0], role="cam")
        self.receive()
        self.now[0] += 150_001
        self.send_board(pending[1], role="cam")
        self.receive()  # real mode still forwards wire bytes, but cannot manufacture a preview of an incomplete frame
        self.assertEqual(self.relay.frames_out, 3)

    def test_composite_requires_pillow_and_mode_updates_are_atomic(self):
        with mock.patch.object(hitl, "Image", None):
            with self.assertRaisesRegex(ValueError, "requires Pillow"):
                self.relay.set_modes(range="world", camera="composite")
        self.assertEqual((self.relay.range_mode, self.relay.camera_mode), ("fuse", "real"))


class ReassemblyTest(unittest.TestCase):
    def test_deadline_is_inclusive_and_overlap_is_rejected(self):
        parts = jpg.chunk_frame(1, 10, 2, 2, b"abcdef", max_chunk=3)
        frames = FrameBuffer()
        self.assertIsNone(frames.add(9, parts[0], dw.encode(parts[0], 9), 0))
        complete = frames.add(10, parts[1], dw.encode(parts[1], 10), 150_000)
        self.assertEqual(complete[1], b"abcdef")
        frames = FrameBuffer()
        frames.add(9, parts[0], dw.encode(parts[0], 9), 0)
        self.assertIsNone(frames.add(10, parts[1], dw.encode(parts[1], 10), 150_001))
        self.assertFalse(frames.pending)
        frames = FrameBuffer()
        frames.add(9, parts[0], dw.encode(parts[0], 9), 0)
        parts[1].offset = 2  # overlapping bytes and an uncovered tail must never count as a complete JPEG
        self.assertIsNone(frames.add(10, parts[1], dw.encode(parts[1], 10), 1))


class BridgeIntegrationTest(unittest.TestCase):
    def test_hitl_never_constructs_simulated_boards_and_ignores_sim_controls_and_jpegs(self):
        args = build_parser().parse_args(["--port", "0", "--hitl", "127.0.0.1:47100",
                                          "--engine-port", "47110", "--hitl-range", "world"])
        # Use an ephemeral listen port for this test, regardless of any user's real HITL process.
        args.hitl = ("127.0.0.1", 0)
        with mock.patch("world_bridge.LiveRig", side_effect=AssertionError("no simulated boards in HITL")):
            bridge = Bridge(args)
        self.addCleanup(bridge.stop)
        events = []
        bridge.emit = events.append
        self.assertIsNone(bridge.rig)
        self.assertTrue(bridge.hello()["hitl"]["enabled"])
        self.assertIn("47110", bridge.engine.cmd)
        for msg in ({"t": "board", "board": "cue", "down": True}, {"t": "net", "profile": "wifi_jitter"}):
            bridge.on_text(None, json.dumps(msg))
        self.assertEqual(bridge.status()["net"], "real")
        self.assertTrue(any("disabled in HITL" in ev.get("line", "") for ev in events))
        bridge.on_text(None, json.dumps({"t": "range", "mm": 899, "status": 0}))
        self.assertEqual(bridge.feed.reading.mm, 899)
        bridge.on_binary(None, b"\x01not a simulated camera")
        self.assertIsNone(bridge.feed.jpeg)
        bridge.on_text(None, json.dumps({"t": "hitl", "range": "real"}))
        self.assertEqual(events[-1]["range"]["mode"], "real")
        with mock.patch.object(bridge.hitl, "put_overlay") as overlay:
            bridge.on_binary(None, b"\x02png bytes")
            overlay.assert_called_once_with(b"png bytes")

    def test_engine_port_alias_and_binary_broadcast_framing(self):
        self.assertEqual(build_parser().parse_args(["--phone-port", "47110"]).phone_port, 47110)
        server = wsserver.WebSocketServer("127.0.0.1", 0)
        self.addCleanup(server.stop)
        connection = mock.Mock()
        server._add(connection)
        server.broadcast_binary(b"\x11jpeg")
        connection.send_frame.assert_called_once_with(wsserver.encode_frame(wsserver.OP_BINARY, b"\x11jpeg"))
        server._remove(connection)


if __name__ == "__main__":
    unittest.main()
