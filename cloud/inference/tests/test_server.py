import base64
import http.client
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import bench  # noqa: E402
import server  # noqa: E402
from backends import Timings, read_compile_meta  # noqa: E402

JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"


class FakeBackend:
    """Test double: deterministic answers, optional delay / failure."""
    device = "cpu"
    model_id = "fake/model"

    def __init__(self, delay=0.0, fail=False, load_delay=0.0):
        self.delay, self.fail, self.load_delay = delay, fail, load_delay
        self.calls = 0

    def load(self):
        time.sleep(self.load_delay)

    def generate(self, jpeg, prompt, mnt, temp):
        self.calls += 1
        time.sleep(self.delay)
        if self.fail:
            raise RuntimeError("boom")
        return "A door is on your right.", 7, Timings(3, 40)

    def info(self):
        return {"model": self.model_id, "device": self.device, "compiled": None, "neuron_cores": None}


def body(**over):
    b = {"request_id": "r1", "image_jpeg_b64": base64.b64encode(JPEG).decode(), "prompt": "describe",
         "max_new_tokens": 32, "temperature": 0}
    b.update(over)
    return b


class Running:
    def __init__(self, backend, key="k", wait_ready=True):
        self.host = server.ModelHost(backend)
        t = self.host.load_async()
        if wait_ready:
            t.join()
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.host, key))
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.port = self.srv.server_address[1]

    def post(self, b, key="k", raw=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        data = raw if raw is not None else json.dumps(b).encode()
        c.request("POST", "/v1/generate", data, {"Content-Type": "application/json", "X-Determine-Key": key})
        r = c.getresponse()
        return r.status, json.loads(r.read())

    def get(self, path):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("GET", path)
        r = c.getresponse()
        return r.status, json.loads(r.read())

    def close(self):
        self.srv.shutdown()
        self.srv.server_close()


class ValidationTest(unittest.TestCase):
    def test_accepts_contract_request(self):
        rid, jpeg, prompt, mnt, temp = server.validate(body())
        self.assertEqual((rid, jpeg, mnt, temp), ("r1", JPEG, 32, 0.0))

    def test_rejects_out_of_contract_fields(self):
        cases = [
            body(max_new_tokens=0), body(max_new_tokens=129), body(max_new_tokens=True), body(temperature=1.5),
            body(temperature=-0.1), body(request_id=""), body(request_id="x" * 65), body(prompt=" "),
            body(image_jpeg_b64="not base64!"), body(image_jpeg_b64=base64.b64encode(b"\x89PNG....").decode()),
            body(image_jpeg_b64=base64.b64encode(b"\xff\xd8" + b"\0" * (512 * 1024)).decode()),
        ]
        for b in cases:
            with self.subTest(b={k: (v if k != "image_jpeg_b64" else len(v)) for k, v in b.items()}):
                with self.assertRaises(server.ApiError) as cm:
                    server.validate(b)
                self.assertEqual((cm.exception.status, cm.exception.code), (400, "bad_request"))

    def test_boundaries_accepted(self):
        server.validate(body(max_new_tokens=1, temperature=1))
        server.validate(body(max_new_tokens=128, request_id="x" * 64))
        server.validate(body(image_jpeg_b64=base64.b64encode(b"\xff\xd8" + b"\0" * (512 * 1024 - 2)).decode()))


class ServerTest(unittest.TestCase):
    def test_generate_ok_matches_contract_shape(self):
        r = Running(FakeBackend())
        try:
            status, resp = r.post(body())
            self.assertEqual(status, 200)
            self.assertEqual(resp["request_id"], "r1")
            self.assertEqual(resp["device"], "cpu")
            self.assertEqual(resp["tokens"], 7)
            self.assertEqual(resp["latency_ms"], {"preprocess": 3, "generate": 40, "total": 43})
        finally:
            r.close()

    def test_auth(self):
        r = Running(FakeBackend())
        try:
            self.assertEqual(r.post(body(), key="wrong"), (401, {"error": "unauthorized", "retryable": False}))
            self.assertEqual(r.post(body(), key="")[0], 401)
        finally:
            r.close()

    def test_not_ready_while_loading_then_ready(self):
        b = FakeBackend(load_delay=0.4)
        r = Running(b, wait_ready=False)
        try:
            self.assertEqual(r.post(body()), (503, {"error": "not_ready", "retryable": True}))
            self.assertFalse(r.get("/health")[1]["ready"])
            time.sleep(0.6)
            self.assertTrue(r.get("/health")[1]["ready"])
            self.assertEqual(r.post(body())[0], 200)
        finally:
            r.close()

    def test_second_concurrent_request_gets_busy_after_wait(self):
        b = FakeBackend(delay=1.6)
        r = Running(b)
        try:
            first = {}
            t = threading.Thread(target=lambda: first.setdefault("r", r.post(body())))
            t.start()
            time.sleep(0.1)
            t0 = time.monotonic()
            status, resp = r.post(body(request_id="r2"))
            waited = time.monotonic() - t0
            t.join()
            self.assertEqual((status, resp), (503, {"error": "busy", "retryable": True}))
            self.assertGreaterEqual(waited, server.BUSY_WAIT_S - 0.05)
            self.assertEqual(first["r"][0], 200)
            self.assertEqual(b.calls, 1)
        finally:
            r.close()

    def test_backend_failure_is_500_and_server_survives(self):
        b = FakeBackend(fail=True)
        r = Running(b)
        try:
            self.assertEqual(r.post(body()), (500, {"error": "inference_failed", "retryable": False}))
            b.fail = False
            self.assertEqual(r.post(body())[0], 200)
        finally:
            r.close()

    def test_malformed_json_and_wrong_path(self):
        r = Running(FakeBackend())
        try:
            self.assertEqual(r.post(None, raw=b"{nope")[0], 400)
            self.assertEqual(r.get("/v2/x")[0], 404)
        finally:
            r.close()

    def test_refuses_to_start_without_key(self):
        os.environ.pop("INFERENCE_KEY", None)
        self.assertEqual(server.main(["--backend", "cpu"]), 2)


class MetaAndBenchTest(unittest.TestCase):
    def test_compile_meta_requires_fields(self):
        with tempfile.TemporaryDirectory() as d:
            meta = {"model": "m", "batch_size": 1, "sequence_length": 2048, "tensor_parallel_size": 2,
                    "compile_seconds": 812.5, "compiled_at": "2026-10-24T20:00:00+00:00"}
            with open(os.path.join(d, "compile_meta.json"), "w") as f:
                json.dump(meta, f)
            self.assertEqual(read_compile_meta(d)["tensor_parallel_size"], 2)
            del meta["compile_seconds"]
            with open(os.path.join(d, "compile_meta.json"), "w") as f:
                json.dump(meta, f)
            with self.assertRaises(ValueError):
                read_compile_meta(d)

    def test_percentile_nearest_rank(self):
        v = [float(x) for x in range(1, 101)]
        self.assertEqual(bench.percentile(v, 50), 50)
        self.assertEqual(bench.percentile(v, 95), 95)
        self.assertEqual(bench.percentile([7.0], 95), 7.0)
        self.assertIsNone(bench.percentile([], 50))

    def test_cost_per_request(self):
        s = bench.summarize([1000.0, 1000.0], [900.0, 1100.0], [50, 50], 0, 0.76)
        self.assertAlmostEqual(s["usd_per_request"], 0.76 / 3600 * 1.0)
        self.assertAlmostEqual(s["tokens_per_s"], 50.0)


if __name__ == "__main__":
    unittest.main()
