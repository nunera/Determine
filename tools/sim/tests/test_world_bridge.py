"""Determine World bridge (wire v2): WebSocket framing/handshake, phone cli line → event translation, cue-board log
→ `played`, the live cue board's single forward RANGE/fault behaviour over loopback UDP, engine tuning (argv and the
restart), and the describe proxy mapping."""
import io
import json
import os
import socket
import stat
import sys
import tempfile
import time
import unittest
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import jpeg as jpg  # noqa: E402
import wsserver as ws  # noqa: E402
from dtwire import FAULT_NO_CLOCK_SYNC, dw  # noqa: E402
from simclock import BoardClock  # noqa: E402
from world_bridge import (FAULT_SENSOR_READ_ERR, Bridge, DescribeClient, LiveCueBoard, LiveScenario,  # noqa: E402
                          LogThrottle, WorldFeed, board_event, build_parser, describe_route, engine_cmd,
                          engine_event, parse_range, parse_tuning)

RFC_KEY = "dGhlIHNhbXBsZSBub25jZQ=="
RFC_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
MASK = bytes.fromhex("37fa213d")


class FramingTest(unittest.TestCase):
    def test_accept_key_rfc6455_example(self):
        self.assertEqual(ws.accept_key(RFC_KEY), RFC_ACCEPT)

    def test_rfc_examples(self):
        # RFC 6455 §5.7: unmasked and masked "Hello"
        self.assertEqual(ws.encode_frame(ws.OP_TEXT, b"Hello"), bytes.fromhex("810548656c6c6f"))
        masked = bytes.fromhex("818537fa213d7f9f4d5158")
        self.assertEqual(ws.encode_frame(ws.OP_TEXT, b"Hello", mask=MASK), masked)
        self.assertEqual(ws.read_frame(io.BytesIO(masked)), (True, ws.OP_TEXT, b"Hello"))
        self.assertEqual(ws.read_frame(io.BytesIO(bytes.fromhex("810548656c6c6f")), require_mask=False),
                         (True, ws.OP_TEXT, b"Hello"))

    def test_extended_lengths(self):
        p256 = bytes(range(256))
        f = ws.encode_frame(ws.OP_BINARY, p256)
        self.assertEqual(f[:4], bytes.fromhex("827e0100"))
        self.assertEqual(len(f), 4 + 256)
        p64k = bytes(i & 0xFF for i in range(65536))
        f = ws.encode_frame(ws.OP_BINARY, p64k)
        self.assertEqual(f[:10], bytes.fromhex("827f0000000000010000"))
        for payload, hdr in ((p256, bytes.fromhex("82fe0100")), (p64k, bytes.fromhex("82ff0000000000010000"))):
            m = ws.encode_frame(ws.OP_BINARY, payload, mask=MASK)
            self.assertEqual(m[:len(hdr)], hdr)
            self.assertEqual(m[len(hdr):len(hdr) + 4], MASK)
            self.assertNotEqual(m[len(hdr) + 4:], payload)
            self.assertEqual(ws.read_frame(io.BytesIO(m)), (True, ws.OP_BINARY, payload))

    def test_fragmented_message_with_interleaved_ping(self):
        stream = io.BytesIO(ws.encode_frame(ws.OP_TEXT, b"Hel", fin=False, mask=MASK)
                            + ws.encode_frame(ws.OP_PING, b"p", mask=MASK)
                            + ws.encode_frame(ws.OP_CONT, b"lo", mask=MASK)
                            + ws.encode_frame(ws.OP_CLOSE, ws.close_payload(1000), mask=MASK))
        msgs = ws.read_messages(stream)
        self.assertEqual(next(msgs), (ws.OP_PING, b"p"))
        self.assertEqual(next(msgs), (ws.OP_TEXT, b"Hello"))
        self.assertEqual(next(msgs), (ws.OP_CLOSE, b"\x03\xe8"))
        with self.assertRaises(ConnectionError):
            next(msgs)

    def test_protocol_errors(self):
        with self.assertRaises(ws.ProtocolError) as cm:
            ws.read_frame(io.BytesIO(bytes.fromhex("810548656c6c6f")))  # unmasked client frame
        self.assertEqual(cm.exception.code, 1002)
        with self.assertRaises(ws.ProtocolError):
            next(ws.read_messages(io.BytesIO(ws.encode_frame(ws.OP_CONT, b"x", mask=MASK))))


class ServerLoopbackTest(unittest.TestCase):
    def test_handshake_text_broadcast_post_and_close(self):
        got = []
        srv = ws.WebSocketServer("127.0.0.1", 0, on_text=lambda c, t: got.append(t),
                                 post_routes={"/api/describe": describe_route(None)})
        srv.start()
        try:
            c = socket.create_connection(("127.0.0.1", srv.port), timeout=2.0)
            c.sendall((f"GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                       f"Sec-WebSocket-Key: {RFC_KEY}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
            rf = c.makefile("rb")
            head = b""
            while not head.endswith(b"\r\n\r\n"):
                head += rf.readline()
            self.assertTrue(head.startswith(b"HTTP/1.1 101"), head)
            self.assertIn(f"Sec-WebSocket-Accept: {RFC_ACCEPT}".encode(), head)
            c.sendall(ws.encode_frame(ws.OP_TEXT, json.dumps({"t": "net"}).encode()[:4], fin=False, mask=MASK)
                      + ws.encode_frame(ws.OP_CONT, json.dumps({"t": "net"}).encode()[4:], mask=MASK))
            c.sendall(ws.encode_frame(ws.OP_PING, b"hb", mask=MASK))
            self.assertEqual(ws.read_frame(rf, require_mask=False), (True, ws.OP_PONG, b"hb"))
            self.assertEqual(got, ['{"t": "net"}'])  # the pong was sent after the text was handled
            srv.broadcast_text('{"t":"log","line":"x"}')
            self.assertEqual(ws.read_frame(rf, require_mask=False), (True, ws.OP_TEXT, b'{"t":"log","line":"x"}'))
            c.sendall(ws.encode_frame(ws.OP_CLOSE, ws.close_payload(1000), mask=MASK))
            self.assertEqual(ws.read_frame(rf, require_mask=False), (True, ws.OP_CLOSE, b"\x03\xe8"))
            c.close()

            h = socket.create_connection(("127.0.0.1", srv.port), timeout=2.0)
            h.sendall(b"POST /api/describe HTTP/1.1\r\nHost: x\r\nContent-Type: image/jpeg\r\n"
                      b"Content-Length: 2\r\nConnection: close\r\n\r\n\xff\xd8")
            resp = b""
            while chunk := h.recv(4096):
                resp += chunk
            h.close()
            head, _, body = resp.partition(b"\r\n\r\n")
            self.assertTrue(head.startswith(b"HTTP/1.1 503"), head)
            self.assertEqual(json.loads(body), {"ok": False, "error": "Describe service not configured"})
        finally:
            srv.stop()


class EngineLineTest(unittest.TestCase):
    def test_alert_none(self):
        self.assertEqual(engine_event("ALERT 1234.5 none"), {"t": "alert", "ms": 1234.5, "alert": None})

    def test_alert_values(self):
        self.assertEqual(engine_event("ALERT 1500.0 band=NEAR dist_mm=420"),
                         {"t": "alert", "ms": 1500.0, "alert": {"band": "NEAR", "distMm": 420}})
        self.assertIsNone(engine_event("ALERT 1600.0 band=FAR dist_mm=-")["alert"]["distMm"])

    def test_audio(self):
        self.assertEqual(engine_event("AUDIO 812.3 cue=2 side=2 gl=-26 gr=-26 status=0"),
                         {"t": "audio", "ms": 812.3, "cue": "OBSTACLE_MID", "side": "BOTH", "glDb": -26,
                          "grDb": -26, "status": "APPLIED"})
        self.assertEqual(engine_event("AUDIO 1.0 cue=0 side=2 gl=-128 gr=-128 status=5")["status"], "BUSY_DROPPED")
        self.assertEqual(engine_event("AUDIO 1.0 cue=0 side=2 gl=-128 gr=-128 status=APPLIED_CLAMPED")["status"],
                         "APPLIED_CLAMPED")

    def test_cue_tx(self):
        self.assertEqual(
            engine_event("CUE-TX 2000.1 seq=7 cue=3 side=2 hp=1 hl=200 hr=200 flags=1 ttl=150 deadline=123456789"),
            {"t": "cueTx", "ms": 2000.1, "seq": 7, "cue": "OBSTACLE_NEAR", "side": "BOTH", "haptic": "PULSE40",
             "hl": 200, "hr": 200, "flags": 1, "ttlMs": 150})
        ev = engine_event("CUE-TX 1.0 seq=8 cue=7 side=2 hp=4 hl=255 hr=255 flags=1 ttl=150 deadline=1")
        self.assertEqual((ev["cue"], ev["haptic"]), ("SYSTEM_OFF", "LSS"))

    def test_state(self):
        self.assertEqual(engine_event("STATE 10.0 READY reason=boards_synced"),
                         {"t": "state", "ms": 10.0, "state": "READY", "reason": "boards_synced"})

    def test_diag_keeps_every_v2_key(self):
        raw = ("DIAG 1000.0 state=READY cam_beat_ms=42 cue_synced=true frame_age_ms=- ack_p95=3.10 range_mm=870 "
               "range_status=VALID range_left=unavailable range_right=unavailable link_weak=false "
               "closing_mm_s=412.50 approach=true exploring=false")
        self.assertEqual(engine_event(raw), {"t": "diag", "ms": 1000.0, "kv": {
            "state": "READY", "cam_beat_ms": "42", "cue_synced": "true", "frame_age_ms": "-", "ack_p95": "3.10",
            "range_mm": "870", "range_status": "VALID", "range_left": "unavailable", "range_right": "unavailable",
            "link_weak": "false", "closing_mm_s": "412.50", "approach": "true", "exploring": "false"}})

    def test_speak_vibrate_system_off(self):
        self.assertEqual(engine_event('SPEAK 5.0 text="Obstacle ahead. Camera = off"'),
                         {"t": "speak", "ms": 5.0, "text": "Obstacle ahead. Camera = off"})
        self.assertEqual(engine_event("VIBRATE 6.0 pattern=0,80,40,80"),
                         {"t": "vibrate", "ms": 6.0, "pattern": [0, 80, 40, 80]})
        self.assertEqual(engine_event("SYSTEM-OFF 12.5"), {"t": "systemOff", "ms": 12.5})

    def test_profile_banner_and_unknown_are_logs(self):
        for raw in ("PROFILE-TX 5.0 seq=0 mode=0 haptic_max=255",
                    "determine-phone listening on 127.0.0.1:47000 (headless: AUDIO lines stand in for headset playback)",
                    "WHATEVER 1.0 x=1",
                    "STATE 1.0 SLEEPING reason=x",
                    "ALERT 1.0 region=LEFT band=NEAR_ish dist_mm=4",
                    "AUDIO 1.0 cue=1 side=2 gl=-20 gr=-20 status=-2",
                    "CUE-TX 1.0 seq=1 cue=99 side=2 hp=0 hl=0 hr=0 flags=0 ttl=150 deadline=0"):
            self.assertEqual(engine_event(raw), {"t": "log", "line": raw})


class BoardLineTest(unittest.TestCase):
    def test_phone_cue(self):
        line = ("CUE t=12.345678 id=OBSTACLE_MID side=BOTH haptic=PULSE60 status=APPLIED_CLAMPED hl=120 hr=120 "
                "rhl=180 rhr=180 flags=1 ttl=150 seq=42 deadline=12400000 late_us=-54322 haptic_max=120 src=phone "
                "host=987654321")
        self.assertEqual(board_event(line), {
            "t": "played", "hostMs": 987654.321, "seq": 42, "cue": "OBSTACLE_MID", "side": "BOTH",
            "status": "APPLIED_CLAMPED", "haptic": "PULSE60", "hl": 120, "hr": 120, "hapticMax": 120, "src": "phone",
            "lateUs": -54322})

    def test_watchdog_cue(self):
        line = ("CUE t=3.300000 id=SYSTEM_OFF side=BOTH haptic=LSS status=APPLIED hl=200 hr=200 flags=0 ttl=0 seq=- "
                "deadline=- late_us=0 haptic_max=200 src=watchdog host=5000000")
        self.assertEqual(board_event(line), {
            "t": "played", "hostMs": 5000.0, "seq": None, "cue": "SYSTEM_OFF", "side": "BOTH", "status": "APPLIED",
            "haptic": "LSS", "hl": 200, "hr": 200, "hapticMax": 200, "src": "watchdog", "lateUs": 0})

    def test_other_lines_are_logs(self):
        for line in ("SYNC board=cue first_ping t=2.512000 host=1", "WATCHDOG enter t=3.3 host=2 reason=no_ping_800ms",
                     "PROFILE t=1.0 applied seq=0 mode=STEREO haptic_max=255 host=3",
                     "SENSOR board=cue state=no_fresh_reading host=4",
                     "CUE t=1.0 id=99 side=BOTH haptic=NONE status=BAD_CUE hl=0 hr=0 rhl=0 rhr=0 flags=0 ttl=150 "
                     "seq=3 deadline=1 late_us=0 haptic_max=255 src=phone host=3"):
            self.assertEqual(board_event(line), {"t": "log", "line": line})


class LogThrottleTest(unittest.TestCase):
    def test_limits_per_kind_and_summarises(self):
        now = [0.0]
        th = LogThrottle(per_s=2, clock=lambda: now[0])
        out = [ln for i in range(5) for ln in th.admit(f"SIM n={i}")]
        self.assertEqual(out, ["SIM n=0", "SIM n=1"])
        self.assertEqual(th.admit("SYNC x"), ["SYNC x"])
        now[0] = 1.5
        self.assertEqual(th.flush(), ["BRIDGE suppressed 3 SIM log lines (more than 2/s)"])
        self.assertEqual(th.admit("SIM n=9"), ["SIM n=9"])


class RangeParseTest(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(parse_range({"t": "range", "simMs": 1, "mm": 812.6, "status": 0}), (813, 0))
        self.assertEqual(parse_range({"mm": 70000, "status": 0}), (0xFFFF, 0))
        self.assertEqual(parse_range({"mm": 640, "status": 1}), (0, dw.RANGE_NO_TARGET))  # mm 0 unless VALID
        self.assertEqual(parse_range({"mm": 300, "status": 2}), (0, dw.RANGE_UNKNOWN))
        for bad in ({"mm": 500, "status": 3}, {"mm": 500, "status": True}, {"mm": 500, "status": 0.0},
                    {"mm": "500", "status": 0}, {"mm": float("nan"), "status": 0}, {"status": 0}, {"mm": 500}):
            self.assertIsNone(parse_range(bad), bad)

    def test_tuning(self):
        self.assertEqual(parse_tuning({"approach": True, "explore": False}), {"approach": True, "explore": False})
        for bad in (None, {"approach": 1, "explore": False}, {"approach": True}):
            self.assertIsNone(parse_tuning(bad))


class LiveCueBoardTest(unittest.TestCase):
    def setUp(self):
        self.now = [50_000_000]
        host = lambda: self.now[0]  # noqa: E731 — deterministic host clock
        self.phone = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.phone.bind(("127.0.0.1", 0))
        self.phone.settimeout(2.0)
        self.feed = WorldFeed(host=host)
        self.lines = []
        self.scen = LiveScenario()
        self.board = LiveCueBoard(feed=self.feed, clock=BoardClock(2.5, 35.0, host, self.now[0]), scenario=self.scen,
                                  t0_host_us=self.now[0], phone_addr=self.phone.getsockname(), bind="127.0.0.1",
                                  port=0, log=self.lines.append, seed=1, host=host)

    def tearDown(self):
        self.board.close()
        self.phone.close()

    def recv(self):
        return dw.decode(self.phone.recv(2048))[1]

    def range_then_beat(self):
        """Ask for a RANGE, then a BEAT; return what arrived up to and including the BEAT."""
        self.board.send_range(self.now[0])
        self.board.send_beat(self.now[0])
        got = []
        while not got or not isinstance(got[-1], dw.Beat):
            got.append(self.recv())
        return got

    def test_no_reading_means_no_range_and_sensor_fault(self):
        got = self.range_then_beat()
        self.assertEqual(len(got), 1, got)
        self.assertTrue(got[0].faults & FAULT_SENSOR_READ_ERR)
        self.assertTrue(got[0].faults & FAULT_NO_CLOCK_SYNC)
        self.assertTrue(any(ln.startswith("SENSOR board=cue state=no_fresh_reading") for ln in self.lines))

    def test_fresh_reading_is_sent_exactly_once_then_goes_stale(self):
        self.feed.put_range(*parse_range({"t": "range", "simMs": 10, "mm": 873, "status": 0}))
        stamped = self.now[0]
        self.now[0] += 20_000
        rng, beat = self.range_then_beat()
        self.assertIsInstance(rng, dw.Range)
        self.assertEqual((rng.mm, rng.status), (873, dw.RANGE_VALID))
        self.assertEqual(rng.t_us, self.board.clock.at(stamped))
        self.assertFalse(beat.faults & FAULT_SENSOR_READ_ERR)
        self.assertTrue(any(ln.startswith("SENSOR board=cue state=ok") for ln in self.lines))

        self.now[0] += 20_000  # still fresh but already sent: a measurement is reported once, never repeated
        got = self.range_then_beat()
        self.assertEqual(len(got), 1, got)
        self.assertFalse(got[0].faults & FAULT_SENSOR_READ_ERR)

        self.now[0] = stamped + 600_000  # no new reading for 600 ms: a sensor failure, never a repeat
        got = self.range_then_beat()
        self.assertEqual(len(got), 1, got)
        self.assertTrue(got[0].faults & FAULT_SENSOR_READ_ERR)

    def test_no_target_and_unknown_carry_mm_zero(self):
        for status in (dw.RANGE_NO_TARGET, dw.RANGE_UNKNOWN):
            self.now[0] += 10_000
            self.feed.put_range(*parse_range({"mm": 640, "status": status}))
            rng, beat = self.range_then_beat()
            self.assertEqual((rng.mm, rng.status), (0, status))
            self.assertFalse(beat.faults & FAULT_SENSOR_READ_ERR)
        self.now[0] += 10_000
        self.feed.put_range(950, dw.RANGE_NO_TARGET)  # the feed enforces mm 0 too, whatever the caller passes
        rng, _ = self.range_then_beat()
        self.assertEqual((rng.mm, rng.status), (0, dw.RANGE_NO_TARGET))

    def test_unplugged_board_is_silent(self):
        self.scen.unplugged["cue"] = True
        self.board.send_beat(self.now[0])
        self.scen.unplugged["cue"] = False
        self.board.send_beat(self.now[0])
        self.recv()
        self.phone.settimeout(0.05)
        with self.assertRaises(socket.timeout):
            self.recv()  # only the second BEAT was sent


class TuningTest(unittest.TestCase):
    BASE = ["--port", "0", "--cue-port", "0", "--cam-port", "0", "--phone-port", "47999"]

    def bridge(self, extra: list[str]) -> tuple[Bridge, list[dict]]:
        b = Bridge(build_parser().parse_args(self.BASE + extra))
        events: list[dict] = []
        b.emit = events.append
        self.addCleanup(b.stop)
        return b, events

    def test_engine_cmd(self):
        base = ["eng", "--port", "47000", "--bind", "127.0.0.1", "--ceiling", "-20", "--mode", "STEREO"]
        self.assertEqual(engine_cmd("eng", 47000, -20, "STEREO", {"approach": False, "explore": False}), base)
        self.assertEqual(engine_cmd("eng", 47000, -20, "STEREO", {"approach": True, "explore": True}),
                         base + ["--approach", "--explore"])

    def test_initial_flags_and_tuning_message_restart_the_engine_with_matching_argv(self):
        d = tempfile.mkdtemp()
        fake = os.path.join(d, "fake-engine")
        with open(fake, "w") as f:
            f.write(f"#!{sys.executable}\nimport sys\nprint('ARGV', *sys.argv[1:], flush=True)\n")
        os.chmod(fake, os.stat(fake).st_mode | stat.S_IXUSR)
        self.addCleanup(lambda: (os.remove(fake), os.rmdir(d)))
        b, events = self.bridge(["--engine", fake, "--approach"])
        self.assertEqual(b.hello()["tuning"], {"approach": True, "explore": False})
        self.assertEqual(b.status()["tuning"], {"approach": True, "explore": False})
        self.assertIn("--approach", b.engine.cmd)
        self.assertNotIn("--explore", b.engine.cmd)

        b.on_text(None, json.dumps({"t": "tuning", "tuning": {"approach": False, "explore": True}}))
        self.assertEqual(b.engine.cmd[-1:], ["--explore"])
        self.assertNotIn("--approach", b.engine.cmd)
        deadline = time.monotonic() + 10.0
        while time.monotonic() < deadline and not any(e["t"] == "log" and e["line"].startswith("ARGV")
                                                      for e in list(events)):
            time.sleep(0.02)
        argv = [e["line"] for e in events if e["t"] == "log" and e["line"].startswith("ARGV")]
        self.assertEqual(argv, ["ARGV --port 47999 --bind 127.0.0.1 --ceiling -20 --mode STEREO --explore"])
        self.assertTrue(any(e["t"] == "log" and "tuning approach=off explore=on" in e["line"] for e in events))
        while time.monotonic() < deadline and not any(e["t"] == "bridge" and e["tuning"] == {
                "approach": False, "explore": True} for e in list(events)):
            time.sleep(0.02)
        self.assertTrue(any(e["t"] == "bridge" and e["tuning"] == {"approach": False, "explore": True}
                            for e in events))

    def test_external_phone_rejects_tuning(self):
        b, events = self.bridge(["--phone", "127.0.0.1:47998"])
        self.assertIsNone(b.hello()["tuning"])  # unknown: never a guessed {false, false}
        self.assertIsNone(b.status()["tuning"])
        b.on_text(None, json.dumps({"t": "tuning", "tuning": {"approach": True, "explore": False}}))
        logs = [e["line"] for e in events if e["t"] == "log"]
        self.assertEqual(len(logs), 1, logs)
        self.assertIn("tune the external phone on the phone", logs[0])
        self.assertIsNone(b.status()["tuning"])

    def test_status_messages_carry_contract_fields(self):
        b, _ = self.bridge(["--engine", "/nonexistent/determine-phone"])
        self.assertEqual(set(b.hello()), {"t", "engine", "phone", "describe", "tuning"})
        self.assertEqual(set(b.status()), {"t", "engine", "boards", "net", "tuning"})
        self.assertEqual(b.status()["engine"], "exited")
        self.assertEqual(b.status()["boards"], {"cue": "up", "cam": "up"})


class DescribeTest(unittest.TestCase):
    JPEG = jpg.make_test_jpegs(1, 32, 24)[0]

    def test_not_configured(self):
        status, ctype, body = describe_route(None)(self.JPEG, "image/jpeg")
        self.assertEqual((status, ctype), (503, "application/json"))
        self.assertEqual(json.loads(body), {"ok": False, "error": "Describe service not configured"})

    def test_forwards_per_cloud_api_and_maps_result(self):
        seen = {}

        class Resp(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        def urlopen(req, timeout):
            seen.update(url=req.full_url, auth=req.get_header("Authorization"), ctype=req.get_header("Content-type"),
                        body=json.loads(req.data), timeout=timeout)
            return Resp(json.dumps({
                "request_id": seen["body"]["request_id"], "text": "A doorway is open on your right.",
                "sentences": ["A doorway is open on your right."], "model": "HuggingFaceTB/SmolVLM-256M-Instruct",
                "backend": "inf2", "silicon": "Inferentia2",
                "timings_ms": {"inference": 812, "api": 43, "total": 855}}).encode())

        status, res = DescribeClient("https://example.invalid/", "k3y", urlopen=urlopen)(self.JPEG)
        self.assertEqual(status, 200)
        self.assertEqual(seen["url"], "https://example.invalid/v1/describe")
        self.assertEqual(seen["auth"], "Bearer k3y")
        self.assertEqual(seen["ctype"], "application/json")
        self.assertEqual(seen["timeout"], 8.0)
        b = seen["body"]
        self.assertEqual((b["width"], b["height"], b["redacted"], b["locale"]), (32, 24, True, "en"))
        self.assertRegex(b["request_id"], r"^[A-Za-z0-9_-]{8,64}$")
        self.assertEqual(b["image_jpeg_b64"][:4], "/9j/")  # base64 of FF D8 FF
        self.assertEqual(res["text"], "A doorway is open on your right.")
        self.assertEqual((res["ok"], res["backend"], res["silicon"], res["model"]),
                         (True, "inf2", "Inferentia2", "HuggingFaceTB/SmolVLM-256M-Instruct"))
        self.assertEqual({k: res["timings"][k] for k in ("inference", "api", "total")},
                         {"inference": 812, "api": 43, "total": 855})
        self.assertIn("bridge_round_trip", res["timings"])

    def test_upstream_error_is_reported_not_invented(self):
        def urlopen(req, timeout):
            raise urllib.error.HTTPError(req.full_url, 502, "Bad Gateway", {},
                                         io.BytesIO(b'{"request_id":"x","error":"inference_failed","retryable":false}'))

        status, res = DescribeClient("https://example.invalid/v1/describe", "k", urlopen=urlopen)(self.JPEG)
        self.assertEqual(status, 502)
        self.assertFalse(res["ok"])
        self.assertNotIn("text", res)
        self.assertIn("inference_failed", res["error"])


if __name__ == "__main__":
    unittest.main()
