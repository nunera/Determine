"""Shared test helpers: import path, Lambda events, a local fake inference server, a fake Bedrock client."""

from __future__ import annotations

import base64
import contextlib
import io
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
APP = os.path.join(ROOT, "app")
for path in (APP, ROOT):
    if path not in sys.path:
        sys.path.insert(0, path)

import backends  # noqa: E402
import handler  # noqa: E402

API_KEY = "test-describe-key-0123456789"
INFERENCE_KEY = "test-inference-key-abcdef"
REQUEST_ID = "req_0001-abcd"


def jpeg(size: int = 2048) -> bytes:
    """Bytes that pass the API's JPEG check (SOI marker) padded to ``size``."""
    head = b"\xff\xd8\xff\xe0"
    tail = b"\xff\xd9"
    return head + b"\x00" * max(0, size - len(head) - len(tail)) + tail


def request_body(**overrides) -> dict:
    body = {
        "request_id": REQUEST_ID,
        "image_jpeg_b64": base64.b64encode(jpeg()).decode("ascii"),
        "width": 640,
        "height": 480,
        "redacted": True,
        "locale": "en",
    }
    body.update(overrides)
    return body


def event(body=None, *, token: str | None = API_KEY, method: str = "POST", path: str = "/v1/describe",
          raw_body: str | None = None, b64: bool = False, headers: dict | None = None) -> dict:
    if raw_body is None:
        raw_body = json.dumps(request_body() if body is None else body)
    hdrs = {"content-type": "application/json"}
    if token is not None:
        hdrs["authorization"] = f"Bearer {token}"
    if headers:
        hdrs.update(headers)
    if b64:
        raw_body = base64.b64encode(raw_body.encode("utf-8")).decode("ascii")
    return {
        "version": "2.0",
        "routeKey": "$default",
        "rawPath": path,
        "rawQueryString": "",
        "headers": hdrs,
        "requestContext": {"http": {"method": method, "path": path, "sourceIp": "127.0.0.1"}},
        "body": raw_body,
        "isBase64Encoded": b64,
    }


def invoke(evt: dict):
    """Run the handler, capturing its stdout log. Returns (response, parsed body, list of log records, raw log)."""
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        response = handler.handler(evt, None)
    raw_log = buf.getvalue()
    records = [json.loads(line) for line in raw_log.splitlines() if line.strip()]
    return response, json.loads(response["body"]), records, raw_log


class FakeInferenceServer:
    """A local HTTP server speaking the inference server's §2 contract, with scripted behaviour."""

    def __init__(self):
        self.status = 200
        self.payload: dict | None = None  # default: a valid answer echoing the request id
        self.text = "A door is open on your left. Two steps go up ahead."
        self.device = "inf2"
        self.delay_s = 0.0
        self.requests: list[dict] = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length) or b"{}")
                owner.requests.append({"path": self.path, "headers": dict(self.headers.items()), "body": body})
                if owner.delay_s:
                    time.sleep(owner.delay_s)
                if owner.payload is not None:
                    payload = owner.payload
                elif owner.status == 200:
                    payload = {
                        "request_id": body.get("request_id"),
                        "text": owner.text,
                        "model": "HuggingFaceTB/SmolVLM-256M-Instruct",
                        "device": owner.device,
                        "tokens": 17,
                        "latency_ms": {"preprocess": 5, "generate": 700, "total": 705},
                    }
                else:
                    payload = {"error": "not_ready" if owner.status == 503 else "internal", "retryable": True}
                data = json.dumps(payload).encode("utf-8")
                try:
                    self.send_response(owner.status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except OSError:
                    pass  # client gave up (timeout tests)

            def log_message(self, *args):
                pass

        class Server(ThreadingHTTPServer):
            daemon_threads = True

            def handle_error(self, request, client_address):
                pass

        self._server = Server(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}"
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._server.shutdown()
        self._server.server_close()


def closed_port_url() -> str:
    """A localhost URL nothing listens on (connection refused)."""
    import socket

    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return f"http://127.0.0.1:{port}"


class FakeBedrockClient:
    def __init__(self, text: str = "A bench is on your right. A doorway is ahead.", error: Exception | None = None):
        self.text = text
        self.error = error
        self.calls: list[dict] = []
        self.timeouts: list[float] = []

    def factory(self, read_timeout: float):
        self.timeouts.append(read_timeout)
        return self

    def converse(self, **kwargs):
        self.calls.append(kwargs)
        if self.error is not None:
            raise self.error
        return {
            "output": {"message": {"role": "assistant", "content": [{"text": self.text}]}},
            "stopReason": "end_turn",
            "usage": {"inputTokens": 1300, "outputTokens": 20, "totalTokens": 1320},
            "metrics": {"latencyMs": 640},
        }


class FakeClientError(Exception):
    """Shaped like botocore.exceptions.ClientError (``.response`` dict)."""

    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.response = {"Error": {"Code": code, "Message": "x"}, "ResponseMetadata": {"HTTPStatusCode": status}}


class ReadTimeoutError(Exception):
    """Same class name as botocore.exceptions.ReadTimeoutError."""


class EndpointConnectionError(Exception):
    """Same class name as botocore.exceptions.EndpointConnectionError."""


class FakeOpenRouterServer:
    """A local HTTP server speaking OpenRouter's OpenAI-compatible ``POST {base}/chat/completions``, scripted."""

    def __init__(self):
        self.status = 200
        self.payload: dict | None = None  # full response override
        self.content: str | None = "A door is open on your left. Two steps go up ahead."
        self.model = "openai/gpt-6-luna"
        # Decisions API (`/alpha/decisions`, used for voice routing): the chosen route and a probability per route,
        # or a full `answers` override for malformed-answer tests.
        self.decision: tuple[str, dict] | None = ("none", {"none": 0.95})
        self.decision_model = "typesafe/jev-1.14-20261001"
        self.decision_answers: object | None = None
        self.delay_s = 0.0
        self.requests: list[dict] = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length) or b"{}")
                owner.requests.append({"path": self.path, "headers": dict(self.headers.items()), "body": body})
                if owner.delay_s:
                    time.sleep(owner.delay_s)
                if owner.payload is not None:
                    payload = owner.payload
                elif owner.status != 200:
                    payload = {"error": {"code": owner.status, "message": "scripted failure"}}
                elif self.path.endswith("/alpha/decisions"):
                    if owner.decision_answers is not None:
                        answers = owner.decision_answers
                    else:
                        choice, probabilities = owner.decision
                        answers = {"route": {"type": "choice", "choice": choice, "confidence": 0.5, "probabilities": probabilities}}
                    payload = {"id": "gen-dec-1", "model": owner.decision_model, "provider": "TypeSafe", "answers": answers,
                               "usage": {"input_tokens": 300, "output_tokens": 20, "cost": 0.00001}}
                else:
                    message: dict = {"role": "assistant", "content": owner.content}
                    payload = {"id": "gen-1", "model": owner.model, "choices": [{"index": 0, "message": message}]}
                data = json.dumps(payload).encode("utf-8")
                try:
                    self.send_response(owner.status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except OSError:
                    pass  # client gave up (timeout tests)

            def log_message(self, *args):
                pass

        class Server(ThreadingHTTPServer):
            daemon_threads = True

            def handle_error(self, request, client_address):
                pass

        self._server = Server(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}"
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._server.shutdown()
        self._server.server_close()
