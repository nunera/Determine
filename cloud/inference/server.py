#!/usr/bin/env python3
"""Describe inference server (cloud/API.md §2): POST /v1/generate, GET /health.

Stdlib HTTP server around one model instance. One generation at a time: a concurrent request waits up to
BUSY_WAIT_S for the model, then gets 503 busy (retryable). While the model loads, /v1/generate answers 503
not_ready. Logs one JSON line per request with sizes and timings, never image bytes or output text.

  INFERENCE_KEY=... python3 server.py --backend neuron --artefact smolvlm256m-inf2 --bind 10.0.1.23
  python3 server.py --backend cpu --insecure-dev          # local development, no key
"""
from __future__ import annotations

import argparse
import base64
import binascii
import hmac
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backends import DEFAULT_MODEL, Backend, NeuronBackend, TransformersCpuBackend  # noqa: E402

MAX_JPEG = 512 * 1024
MAX_BODY = MAX_JPEG * 4 // 3 + 16 * 1024
BUSY_WAIT_S = 1.0


class ApiError(Exception):
    def __init__(self, status: int, code: str, retryable: bool):
        super().__init__(code)
        self.status, self.code, self.retryable = status, code, retryable


def validate(body: dict) -> tuple[str, bytes, str, int, float]:
    """Contract §2 request checks. Returns (request_id, jpeg, prompt, max_new_tokens, temperature)."""
    def bad():
        return ApiError(400, "bad_request", False)
    if not isinstance(body, dict):
        raise bad()
    rid, b64, prompt = body.get("request_id"), body.get("image_jpeg_b64"), body.get("prompt")
    mnt, temp = body.get("max_new_tokens"), body.get("temperature")
    if not isinstance(rid, str) or not rid or len(rid) > 64:
        raise bad()
    if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 4000:
        raise bad()
    if not isinstance(mnt, int) or isinstance(mnt, bool) or not 1 <= mnt <= 128:
        raise bad()
    if not isinstance(temp, (int, float)) or isinstance(temp, bool) or not 0 <= temp <= 1:
        raise bad()
    if not isinstance(b64, str):
        raise bad()
    try:
        jpeg = base64.b64decode(b64, validate=True)
    except (binascii.Error, ValueError):
        raise bad() from None
    if len(jpeg) > MAX_JPEG:
        raise ApiError(400, "bad_request", False)
    if jpeg[:2] != b"\xff\xd8":
        raise bad()
    return rid, jpeg, prompt, mnt, float(temp)


class ModelHost:
    """Owns the backend: background load, readiness, single-flight generation."""

    def __init__(self, backend: Backend):
        self.backend = backend
        self.ready = False
        self.load_error: str | None = None
        self._gen_lock = threading.Lock()

    def load_async(self) -> threading.Thread:
        def run():
            try:
                self.backend.load()
                self.ready = True
            except Exception as e:  # noqa: BLE001 - reported via /health and 503
                self.load_error = f"{type(e).__name__}: {e}"
                log({"event": "load_failed", "error": self.load_error})
        t = threading.Thread(target=run, name="model-load", daemon=True)
        t.start()
        return t

    def health(self) -> dict:
        info = self.backend.info()
        return {"ready": self.ready, "model": info["model"], "device": info["device"],
                "compiled": info.get("compiled"), "neuron_cores": info.get("neuron_cores"),
                **({"error": self.load_error} if self.load_error else {})}

    def generate(self, rid: str, jpeg: bytes, prompt: str, mnt: int, temp: float) -> dict:
        if not self.ready:
            raise ApiError(503, "not_ready", True)
        if not self._gen_lock.acquire(timeout=BUSY_WAIT_S):
            raise ApiError(503, "busy", True)
        try:
            text, tokens, t = self.backend.generate(jpeg, prompt, mnt, temp)
        except Exception as e:  # noqa: BLE001 - one bad image must not kill the server
            log({"event": "generate_failed", "request_id": rid, "error": type(e).__name__})
            raise ApiError(500, "inference_failed", False) from None
        finally:
            self._gen_lock.release()
        return {"request_id": rid, "text": text, "model": self.backend.model_id, "device": self.backend.device,
                "tokens": tokens,
                "latency_ms": {"preprocess": t.preprocess_ms, "generate": t.generate_ms, "total": t.total_ms}}


def log(obj: dict) -> None:
    obj.setdefault("ts", round(time.time(), 3))
    sys.stdout.write(json.dumps(obj, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def make_handler(host: ModelHost, key: str | None):
    class Handler(BaseHTTPRequestHandler):
        server_version = "determine-inference/1"

        def log_message(self, fmt, *args):  # replaced by structured log()
            pass

        def _send(self, status: int, obj: dict):
            data = json.dumps(obj).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/health":
                self._send(200, host.health())
            else:
                self._send(404, {"error": "not_found", "retryable": False})

        def do_POST(self):
            t0 = time.monotonic()
            rid, status, code, size, tokens = None, 200, None, 0, None
            try:
                if self.path != "/v1/generate":
                    raise ApiError(404, "not_found", False)
                if key is not None and not hmac.compare_digest(self.headers.get("X-Determine-Key", ""), key):
                    raise ApiError(401, "unauthorized", False)
                n = int(self.headers.get("Content-Length") or 0)
                if n <= 0 or n > MAX_BODY:
                    raise ApiError(400, "bad_request", False)
                try:
                    body = json.loads(self.rfile.read(n))
                except (json.JSONDecodeError, UnicodeDecodeError):
                    raise ApiError(400, "bad_request", False) from None
                rid, jpeg, prompt, mnt, temp = validate(body)
                size = len(jpeg)
                resp = host.generate(rid, jpeg, prompt, mnt, temp)
                tokens = resp["tokens"]
                self._send(200, resp)
            except ApiError as e:
                status, code = e.status, e.code
                self._send(e.status, {"error": e.code, "retryable": e.retryable})
            finally:
                log({"event": "generate", "request_id": rid, "status": status, "error": code, "image_bytes": size,
                     "tokens": tokens, "ms": int((time.monotonic() - t0) * 1000)})

    return Handler


def build_backend(args) -> Backend:
    if args.backend == "cpu":
        return TransformersCpuBackend(args.model, threads=args.threads)
    if not args.artefact:
        sys.exit("--backend neuron needs --artefact (output of compile.py)")
    return NeuronBackend(args.artefact, device=args.device)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--backend", choices=["neuron", "cpu"], default="neuron")
    p.add_argument("--artefact", help="compiled model directory (neuron backend)")
    p.add_argument("--device", choices=["inf2", "trn1", "trn2"], default="inf2", help="reported device (neuron)")
    p.add_argument("--model", default=DEFAULT_MODEL, help="Hub model id (cpu backend)")
    p.add_argument("--threads", type=int, help="torch CPU threads (cpu backend)")
    p.add_argument("--bind", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--insecure-dev", action="store_true", help="run without INFERENCE_KEY (local development only)")
    args = p.parse_args(argv)

    key = os.environ.get("INFERENCE_KEY") or None
    if key is None and not args.insecure_dev:
        print("refusing to start: set INFERENCE_KEY (or --insecure-dev for local development)", file=sys.stderr)
        return 2
    host = ModelHost(build_backend(args))
    host.load_async()
    srv = ThreadingHTTPServer((args.bind, args.port), make_handler(host, key))
    log({"event": "listening", "bind": args.bind, "port": args.port, "backend": args.backend, "auth": key is not None})
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
