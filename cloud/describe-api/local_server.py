#!/usr/bin/env python3
"""Run the Describe API handler on localhost for phone development.

Every HTTP request is translated into a Lambda Function URL (payload v2) event, passed to the same
``app/handler.py`` that runs on Lambda, and the handler's response is written back. Configuration comes from the
same environment variables as the Lambda (see README.md).

    DESCRIBE_API_KEY=dev-key INFERENCE_URL=http://127.0.0.1:8080 INFERENCE_KEY=dev-inference-key \
        python3 cloud/describe-api/local_server.py --port 8787
"""

from __future__ import annotations

import argparse
import base64
import os
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "app"))

import handler as describe_handler  # noqa: E402  (path set above)
import json
import backends
import streaming
import speech

# Lambda Function URLs reject request payloads above 6 MB before the function runs; mirror that.
MAX_PAYLOAD_BYTES = 6 * 1024 * 1024


class _Context:
    def __init__(self) -> None:
        self.aws_request_id = str(uuid.uuid4())
        self.function_name = "determine-describe-api-local"


def build_event(method: str, target: str, headers: dict[str, str], body: bytes, source_ip: str) -> dict:
    """The Lambda Function URL event shape (payload format 2.0) for one HTTP request."""
    path, _, query = target.partition("?")
    try:
        text, is_b64 = body.decode("utf-8"), False
    except UnicodeDecodeError:
        text, is_b64 = base64.b64encode(body).decode("ascii"), True
    now = time.time()
    return {
        "version": "2.0",
        "routeKey": "$default",
        "rawPath": path,
        "rawQueryString": query,
        "headers": headers,
        "requestContext": {
            "accountId": "anonymous",
            "apiId": "local",
            "domainName": headers.get("host", "localhost"),
            "http": {
                "method": method,
                "path": path,
                "protocol": "HTTP/1.1",
                "sourceIp": source_ip,
                "userAgent": headers.get("user-agent", ""),
            },
            "requestId": str(uuid.uuid4()),
            "routeKey": "$default",
            "stage": "$default",
            "time": time.strftime("%d/%b/%Y:%H:%M:%S +0000", time.gmtime(now)),
            "timeEpoch": int(now * 1000),
        },
        "body": text if body else None,
        "isBase64Encoded": is_b64,
    }


class DescribeRequestHandler(BaseHTTPRequestHandler):
    server_version = "determine-describe-local/1"

    def _dispatch(self) -> None:
        length_header = self.headers.get("Content-Length", "0") or "0"
        try:
            length = int(length_header)
        except ValueError:
            length = -1
        if length < 0:
            self._write(400, {"Content-Type": "application/json"}, b'{"request_id":null,"error":"bad_request","retryable":false}')
            return
        if length > MAX_PAYLOAD_BYTES:
            self._write(413, {"Content-Type": "application/json"}, b'{"request_id":null,"error":"too_large","retryable":false}')
            return
        body = self.rfile.read(length) if length else b""
        headers: dict[str, str] = {}
        for key, value in self.headers.items():
            k = key.lower()
            headers[k] = f"{headers[k]},{value}" if k in headers else value
        event = build_event(self.command, self.path, headers, body, self.client_address[0])
        path = self.path.partition("?")[0].rstrip("/")
        if path.endswith(("/v1/describe/stream", "/v1/speech", "/v1/speech/voices")):
            self._extended(event, path, body)
            return
        result = describe_handler.handler(event, _Context())
        payload = result.get("body") or ""
        data = base64.b64decode(payload) if result.get("isBase64Encoded") else payload.encode("utf-8")
        self._write(int(result.get("statusCode", 200)), result.get("headers") or {}, data)

    def _extended(self, event, path, body):
        started = False
        try:
            describe_handler._check_auth(describe_handler._headers(event))
            expected = "GET" if path.endswith("/voices") else "POST"
            if self.command != expected:
                raise describe_handler.ApiError("method_not_allowed")
            if path.endswith("/voices"):
                self._write(200, {"Content-Type": "application/json", "Cache-Control": "no-store"},
                            json.dumps(speech.catalog()).encode())
                return
            if path.endswith("/describe/stream"):
                req, length, style = streaming.prepare(event)
                events = streaming.events(req, length, style)
                # Start upstream before committing status; later errors are explicit NDJSON events.
                first = next(events)
                self._stream_headers("application/x-ndjson")
                started = True
                try:
                    self._event(first)
                    for item in events:
                        self._event(item)
                finally:
                    events.close()
            else:
                with speech.open_stream(body) as response:
                    self._stream_headers("audio/pcm")
                    started = True
                    total = 0
                    while True:
                        chunk = response.read1(4096)
                        if not chunk:
                            break
                        total += len(chunk)
                        if total > 8 * 1024 * 1024:
                            raise describe_handler.ApiError("too_large")
                        self.wfile.write(chunk)
                        self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except (describe_handler.ApiError, backends.BackendError) as exc:
            if started and path.endswith("/describe/stream"):
                try:
                    self._event({"type": "error", "error": exc.code, "retryable": exc.retryable})
                except OSError:
                    pass
            elif not started:
                self._write(describe_handler.ERROR_STATUS.get(exc.code, 502),
                            {"Content-Type": "application/json", "Cache-Control": "no-store"},
                            json.dumps({"error": exc.code, "retryable": exc.retryable}).encode())
        except (OSError, ValueError, StopIteration):
            if not started:
                self._write(502, {"Content-Type": "application/json"},
                            b'{"error":"inference_failed","retryable":true}')
        finally:
            self.close_connection = True

    def _stream_headers(self, content_type):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Accel-Buffering", "no")
        self.send_header("Connection", "close")
        self.end_headers()

    def _event(self, event):
        self.wfile.write((json.dumps(event, ensure_ascii=False) + "\n").encode())
        self.wfile.flush()

    def _write(self, status: int, headers: dict, data: bytes) -> None:
        self.send_response(status)
        for key, value in headers.items():
            self.send_header(key, str(value))
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_POST = _dispatch
    do_GET = _dispatch
    do_PUT = _dispatch
    do_DELETE = _dispatch
    do_PATCH = _dispatch

    def log_message(self, format: str, *args) -> None:  # noqa: A002 — BaseHTTPRequestHandler signature
        # The handler already logs one JSON line per request; keep the access log short and payload-free.
        sys.stderr.write(f"{self.address_string()} {self.command} {self.path} {args[1] if len(args) > 1 else ''}\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the Describe API handler locally.")
    parser.add_argument("--host", default="127.0.0.1", help="bind address (default 127.0.0.1; use 0.0.0.0 for a phone on the LAN)")
    parser.add_argument("--port", type=int, default=8787, help="listen port (default 8787)")
    args = parser.parse_args(argv)
    if not (os.environ.get("DESCRIBE_API_KEY") or os.environ.get("DESCRIBE_API_KEY_PARAM")):
        parser.error("set DESCRIBE_API_KEY (or DESCRIBE_API_KEY_PARAM) — the handler refuses requests without a key")
    server = ThreadingHTTPServer((args.host, args.port), DescribeRequestHandler)
    sys.stderr.write(f"describe-api listening on http://{args.host}:{args.port}/v1/describe\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
