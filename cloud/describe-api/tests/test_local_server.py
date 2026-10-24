import contextlib
import io
import json
import os
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from unittest import mock

import support
from support import API_KEY, INFERENCE_KEY, REQUEST_ID

import backends
import local_server


class LocalServerTest(unittest.TestCase):
    def setUp(self):
        backends.clear_secret_cache()
        self.addCleanup(backends.clear_secret_cache)
        self.inference = support.FakeInferenceServer().__enter__()
        self.addCleanup(self.inference.__exit__, None, None, None)
        patcher = mock.patch.dict(
            os.environ,
            {"DESCRIBE_API_KEY": API_KEY, "INFERENCE_URL": self.inference.url, "INFERENCE_KEY": INFERENCE_KEY},
            clear=True,
        )
        patcher.start()
        self.addCleanup(patcher.stop)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), local_server.DescribeRequestHandler)
        self.server.daemon_threads = True
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        # Keep the handler's JSON log lines and the access log out of the test output.
        for stream in ("stdout", "stderr"):
            redirect = getattr(contextlib, f"redirect_{stream}")(io.StringIO())
            redirect.__enter__()
            self.addCleanup(redirect.__exit__, None, None, None)

    def post(self, body: dict, token: str | None = API_KEY, path: str = "/v1/describe"):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(self.base + path, data=json.dumps(body).encode(), headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read())

    def test_describe_round_trip(self):
        status, body = self.post(support.request_body())
        self.assertEqual(status, 200, body)
        self.assertEqual(body["request_id"], REQUEST_ID)
        self.assertEqual(body["backend"], "inf2")
        self.assertEqual(len(self.inference.requests), 1)

    def test_errors_pass_through(self):
        status, body = self.post(support.request_body(), token=None)
        self.assertEqual((status, body["error"]), (401, "unauthorized"))
        status, body = self.post(support.request_body(redacted=False))
        self.assertEqual((status, body["error"], body["request_id"]), (400, "bad_request", REQUEST_ID))

    def test_event_shape(self):
        evt = local_server.build_event(
            "POST", "/v1/describe?x=1", {"authorization": "Bearer k", "host": "h"}, b'{"a":1}', "10.0.0.2"
        )
        self.assertEqual(evt["version"], "2.0")
        self.assertEqual(evt["rawPath"], "/v1/describe")
        self.assertEqual(evt["rawQueryString"], "x=1")
        self.assertEqual(evt["requestContext"]["http"]["method"], "POST")
        self.assertEqual(evt["requestContext"]["http"]["sourceIp"], "10.0.0.2")
        self.assertEqual((evt["body"], evt["isBase64Encoded"]), ('{"a":1}', False))
        binary = local_server.build_event("POST", "/", {}, b"\xff\xd8", "1.2.3.4")
        self.assertTrue(binary["isBase64Encoded"])
        self.assertEqual(binary["body"], "/9g=")


if __name__ == "__main__":
    unittest.main()
