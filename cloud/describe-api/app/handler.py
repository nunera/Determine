"""Describe API — AWS Lambda (arm64 / Graviton2) handler for ``POST /v1/describe`` (cloud/API.md §1 and §3).

Accepts Lambda Function URL and API Gateway HTTP API (payload v2) events. The phone sends one face-redacted JPEG;
the handler checks the bearer key, validates the request, asks the configured model backend for two sentences
with the §3 prompt, runs the §3 safety filter and answers with the contract response.

The API stores nothing. It logs exactly one JSON line per request with the request id, sizes, backend, status and
timings — never image bytes and never model text.

Environment:
  DESCRIBE_API_KEY | DESCRIBE_API_KEY_PARAM   bearer key, or SSM SecureString name holding it (read once)
  DESCRIBE_BACKEND                            inf2 | trn2 | bedrock (default inf2)
  DESCRIBE_FALLBACK                           bedrock | none (default none); used only when the primary is
                                              unavailable or times out
  INFERENCE_URL                               inference server base URL, e.g. http://10.0.1.23:8080
  INFERENCE_KEY | INFERENCE_KEY_PARAM         X-Determine-Key, or SSM SecureString name holding it
  BEDROCK_MODEL_ID                            us.amazon.nova-lite-v1:0 (default) | amazon.nova-lite-v1:0
  DESCRIBE_BACKEND=openrouter                 any vision model OpenRouter serves (see openrouter.py)
  OPENROUTER_API_KEY | OPENROUTER_API_KEY_PARAM   OpenRouter key, or SSM SecureString name holding it
  OPENROUTER_MODEL                            vision model (default openai/gpt-6-luna)
  OPENROUTER_ROUTER_MODEL                     Jev model for /v1/voice (default typesafe/jev-1.14, Decisions API)
  VOICE_MIN_CONFIDENCE                        act on a voice route only above this probability (default 0.6)
  OPENROUTER_ZDR=1                            only zero-data-retention endpoints
"""

from __future__ import annotations

import base64
import binascii
import hmac
import json
import os
import re
import sys
import time
from typing import Any, Optional

import backends
import openrouter
import safety

ROUTE = "/v1/describe"
VOICE_ROUTE = "/v1/voice"
MAX_TRANSCRIPT_CHARS = 300
# Voice routing is a short text call; it gets less time than a vision answer so the phone can fall back to its
# local phrases quickly.
VOICE_BUDGET_S = 4.0

REQUEST_ID_RE = re.compile(r"[A-Za-z0-9_-]{8,64}", re.ASCII)
MAX_IMAGE_BYTES = 512 * 1024
# Longest base64 text that can decode to MAX_IMAGE_BYTES (4 chars per 3 bytes, padded).
MAX_IMAGE_B64_CHARS = 4 * ((MAX_IMAGE_BYTES + 2) // 3)
# Whole request body cap: the image plus a few hundred bytes of JSON. Anything larger is rejected before parsing.
MAX_BODY_BYTES = MAX_IMAGE_B64_CHARS + 16 * 1024
MIN_DIMENSION, MAX_DIMENSION = 16, 2048
LOCALES = frozenset({"en"})

# API → inference budget (cloud/API.md §1: 6 s; the phone gives up at 8 s end to end).
INFERENCE_BUDGET_S = 6.0
# With a fallback configured the primary gets part of the budget so the fallback still fits inside the 6 s.
PRIMARY_BUDGET_WITH_FALLBACK_S = 4.0
# A fallback attempt with less time than this cannot answer; return the primary's error instead.
MIN_FALLBACK_S = 1.0

PRIMARY_BACKENDS = frozenset({"inf2", "trn2", "bedrock", "openrouter"})
FALLBACK_BACKENDS = frozenset({"bedrock"})

ERROR_STATUS = {
    "bad_request": 400,
    "unauthorized": 401,
    "not_found": 404,
    "method_not_allowed": 405,
    "too_large": 413,
    "inference_failed": 502,
    "unavailable": 503,
    "timeout": 504,
}


class ApiError(Exception):
    def __init__(self, code: str, retryable: bool = False, detail: str = ""):
        super().__init__(code)
        self.code = code
        self.retryable = retryable
        self.detail = detail

    @property
    def status(self) -> int:
        return ERROR_STATUS[self.code]


# ------------------------------------------------------------------------------------------------ http helpers


def _response(status: int, payload: dict, extra_headers: Optional[dict] = None) -> dict:
    headers = {"Content-Type": "application/json", "Cache-Control": "no-store"}
    if extra_headers:
        headers.update(extra_headers)
    return {
        "statusCode": status,
        "headers": headers,
        "body": json.dumps(payload, separators=(",", ":"), ensure_ascii=False),
        "isBase64Encoded": False,
    }


def _error_response(err: ApiError, request_id: Optional[str]) -> dict:
    extra = {"Allow": "POST"} if err.code == "method_not_allowed" else None
    if err.code == "unauthorized":
        extra = {"WWW-Authenticate": "Bearer"}
    return _response(err.status, {"request_id": request_id, "error": err.code, "retryable": err.retryable}, extra)


def _headers(event: dict) -> dict:
    raw = event.get("headers") or {}
    return {str(k).lower(): str(v) for k, v in raw.items()}


def _method_and_path(event: dict) -> tuple[str, str]:
    http = (event.get("requestContext") or {}).get("http") or {}
    method = str(http.get("method") or event.get("httpMethod") or "").upper()
    path = str(event.get("rawPath") or http.get("path") or "")
    return method, path


def _route(path: str) -> Optional[str]:
    # API Gateway named stages prefix the path ("/prod/v1/describe"); Function URLs do not.
    path = path.rstrip("/")
    if path == ROUTE or path.endswith(ROUTE):
        return "describe"
    if path == VOICE_ROUTE or path.endswith(VOICE_ROUTE):
        return "voice"
    return None


def _emit(record: dict) -> None:
    sys.stdout.write(json.dumps(record, separators=(",", ":"), sort_keys=True) + "\n")
    sys.stdout.flush()


def _ms(seconds: float) -> int:
    return max(0, int(round(seconds * 1000)))


# --------------------------------------------------------------------------------------------------- auth


def _check_auth(headers: dict) -> None:
    value = headers.get("authorization", "")
    scheme, _, token = value.partition(" ")
    token = token.strip()
    if scheme.lower() != "bearer" or not token:
        raise ApiError("unauthorized", False, "missing bearer token")
    try:
        expected = backends.load_secret("DESCRIBE_API_KEY", "DESCRIBE_API_KEY_PARAM")
    except backends.SecretError as exc:
        # Fail closed. A missing configuration will not fix itself; an SSM hiccup may.
        configured = bool(os.environ.get("DESCRIBE_API_KEY_PARAM"))
        raise ApiError("unavailable", configured, f"api key: {exc}") from None
    if not hmac.compare_digest(token.encode("utf-8"), expected.encode("utf-8")):
        raise ApiError("unauthorized", False, "bad bearer token")


# --------------------------------------------------------------------------------------------- validation


def _body_bytes(event: dict) -> bytes:
    body = event.get("body")
    if body is None:
        raise ApiError("bad_request", False, "empty body")
    if event.get("isBase64Encoded"):
        if len(body) > 4 * ((MAX_BODY_BYTES + 2) // 3):
            raise ApiError("too_large", False, "body too large")
        try:
            return base64.b64decode(body, validate=True)
        except (binascii.Error, ValueError):
            raise ApiError("bad_request", False, "body is not valid base64") from None
    if not isinstance(body, str):
        raise ApiError("bad_request", False, "body is not text")
    return body.encode("utf-8")


def _dimension(payload: dict, name: str) -> int:
    value = payload.get(name)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ApiError("bad_request", False, f"{name} must be an integer")
    if not MIN_DIMENSION <= value <= MAX_DIMENSION:
        raise ApiError("bad_request", False, f"{name} out of range")
    return value


class DescribeRequest:
    __slots__ = ("request_id", "image_b64", "image", "width", "height", "question")

    def __init__(self, request_id: str, image_b64: str, image: bytes, width: int, height: int, question: Optional[str] = None):
        self.request_id = request_id
        self.image_b64 = image_b64
        self.image = image
        self.width = width
        self.height = height
        self.question = question


def _parse_request(raw: bytes, log: dict) -> DescribeRequest:
    if len(raw) > MAX_BODY_BYTES:
        raise ApiError("too_large", False, "body too large")
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise ApiError("bad_request", False, "body is not JSON") from None
    if not isinstance(payload, dict):
        raise ApiError("bad_request", False, "body is not a JSON object")

    request_id = payload.get("request_id")
    if not isinstance(request_id, str) or not REQUEST_ID_RE.fullmatch(request_id):
        raise ApiError("bad_request", False, "bad request_id")
    log["request_id"] = request_id

    redacted = payload.get("redacted")
    if redacted is not True:
        # Invariant: a frame leaves the phone face-redacted unless the wearer explicitly opted in to unredacted
        # upload, which the phone states with `unredacted_opt_in: true`. Anything else is refused outright.
        if redacted is not False or payload.get("unredacted_opt_in") is not True:
            raise ApiError("bad_request", False, "redacted must be true unless unredacted_opt_in is true")
    log["redacted"] = redacted
    if payload.get("locale") not in LOCALES:
        raise ApiError("bad_request", False, "unsupported locale")
    width = _dimension(payload, "width")
    height = _dimension(payload, "height")
    log["width"], log["height"] = width, height
    question = payload.get("question")
    if question is not None:
        question = openrouter.clean_question(question)
        if question is None:
            raise ApiError("bad_request", False, "question must be 1-300 characters of text")
        log["question"] = True

    image_b64 = payload.get("image_jpeg_b64")
    if not isinstance(image_b64, str) or not image_b64:
        raise ApiError("bad_request", False, "image_jpeg_b64 missing")
    if len(image_b64) > MAX_IMAGE_B64_CHARS:
        raise ApiError("too_large", False, "image too large")
    try:
        image = base64.b64decode(image_b64, validate=True)
    except (binascii.Error, ValueError):
        raise ApiError("bad_request", False, "image_jpeg_b64 is not valid base64") from None
    log["image_bytes"] = len(image)
    if len(image) > MAX_IMAGE_BYTES:
        raise ApiError("too_large", False, "image too large")
    if image[:2] != b"\xff\xd8":
        raise ApiError("bad_request", False, "image is not a JPEG")
    return DescribeRequest(request_id, image_b64, image, width, height, question)


# ---------------------------------------------------------------------------------------------- backends


def _backend_name(env: str, allowed: frozenset, default: str) -> str:
    value = os.environ.get(env, "").strip().lower() or default
    if value == "none" and env == "DESCRIBE_FALLBACK":
        return ""
    if value not in allowed:
        raise ApiError("unavailable", False, f"{env}={value!r} is not supported")
    return value


def _openrouter_client() -> openrouter.OpenRouterClient:
    try:
        key = backends.load_secret("OPENROUTER_API_KEY", "OPENROUTER_API_KEY_PARAM")
    except backends.SecretError as exc:
        retryable = bool(os.environ.get("OPENROUTER_API_KEY_PARAM"))
        raise backends.BackendError("unavailable", retryable, f"openrouter key: {exc}") from None
    return openrouter.OpenRouterClient(key)


def _run_backend(name: str, req: DescribeRequest, timeout_s: float) -> backends.Generation:
    prompt = safety.prompt_for(req.question)
    if name == "openrouter":
        model = os.environ.get("OPENROUTER_MODEL", "").strip() or openrouter.DEFAULT_VISION_MODEL
        return _openrouter_client().describe(req.image, prompt, model, timeout_s)
    if name == "bedrock":
        model_id = os.environ.get("BEDROCK_MODEL_ID", "").strip() or backends.DEFAULT_BEDROCK_MODEL_ID
        try:
            backend = backends.BedrockBackend(model_id)
        except ValueError:
            raise backends.BackendError("unavailable", False, "BEDROCK_MODEL_ID is not a Nova Lite id") from None
        return backend.generate(req.request_id, req.image, prompt, timeout_s)
    url = os.environ.get("INFERENCE_URL", "").strip()
    if not url:
        raise backends.BackendError("unavailable", False, "INFERENCE_URL is not set")
    try:
        key = backends.load_secret("INFERENCE_KEY", "INFERENCE_KEY_PARAM")
    except backends.SecretError as exc:
        retryable = bool(os.environ.get("INFERENCE_KEY_PARAM"))
        raise backends.BackendError("unavailable", retryable, f"inference key: {exc}") from None
    return backends.InferenceBackend(url, key).generate(req.request_id, req.image_b64, prompt, timeout_s)


def _generate(req: DescribeRequest, log: dict) -> backends.Generation:
    primary = _backend_name("DESCRIBE_BACKEND", PRIMARY_BACKENDS, "inf2")
    fallback = _backend_name("DESCRIBE_FALLBACK", FALLBACK_BACKENDS, "none")
    if fallback == primary:
        fallback = ""
    started = time.monotonic()
    deadline = started + INFERENCE_BUDGET_S
    primary_budget = min(PRIMARY_BUDGET_WITH_FALLBACK_S, INFERENCE_BUDGET_S) if fallback else INFERENCE_BUDGET_S
    log["backend_primary"] = primary
    try:
        try:
            return _run_backend(primary, req, primary_budget)
        except backends.BackendError as err:
            if not fallback or err.code not in ("unavailable", "timeout"):
                raise
            log["primary_error"] = err.code
            log["primary_detail"] = err.detail
            remaining = deadline - time.monotonic()
            if remaining < MIN_FALLBACK_S:
                raise
            log["fallback_used"] = True
            return _run_backend(fallback, req, remaining)
    finally:
        log["inference_s"] = time.monotonic() - started


# ------------------------------------------------------------------------------------------------- handler


def handler(event: dict, context: Any = None) -> dict:
    started = time.monotonic()
    log: dict[str, Any] = {
        "event": "describe",
        "request_id": None,
        "status": None,
        "error": None,
        "body_bytes": None,
        "image_bytes": None,
        "backend": None,
        "fallback_used": False,
    }
    if context is not None and getattr(context, "aws_request_id", None):
        log["lambda_request_id"] = context.aws_request_id
    payload: Optional[dict] = None
    error_response: Optional[dict] = None
    try:
        payload = _handle(event, log)
    except ApiError as err:
        log["error"], log["detail"] = err.code, err.detail
        error_response = _error_response(err, log["request_id"])
    except backends.BackendError as err:
        log["error"], log["detail"] = err.code, err.detail
        error_response = _error_response(ApiError(err.code, err.retryable), log["request_id"])
    except Exception as exc:  # never leak internals; never log payloads
        log["error"], log["detail"] = "inference_failed", f"unhandled {type(exc).__name__}"
        error_response = _error_response(ApiError("inference_failed", True), log["request_id"])
    total_ms = _ms(time.monotonic() - started)
    inference_s = log.pop("inference_s", None)
    inference_ms = None if inference_s is None else min(_ms(inference_s), total_ms)
    timings = {"inference": inference_ms, "api": total_ms - (inference_ms or 0), "total": total_ms}
    if payload is not None:
        payload["timings_ms"] = timings
        response = _response(200, payload)
    else:
        response = error_response
    log["status"] = response["statusCode"]
    log["timings_ms"] = timings
    _emit(log)
    return response


def _handle_voice(raw: bytes, log: dict) -> dict:
    """`POST /v1/voice`: what the wearer said after the wake phrase -> a fixed command, a question about the scene
    (the phone then calls /v1/describe with it) or nothing. Only the transcript text is sent to the router model."""
    log["event"] = "voice"
    if len(raw) > 8 * 1024:
        raise ApiError("too_large", False, "body too large")
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise ApiError("bad_request", False, "body is not JSON") from None
    if not isinstance(payload, dict):
        raise ApiError("bad_request", False, "body is not a JSON object")
    request_id = payload.get("request_id")
    if not isinstance(request_id, str) or not REQUEST_ID_RE.fullmatch(request_id):
        raise ApiError("bad_request", False, "bad request_id")
    log["request_id"] = request_id
    if payload.get("locale") not in LOCALES:
        raise ApiError("bad_request", False, "unsupported locale")
    transcript = payload.get("transcript")
    if not isinstance(transcript, str):
        raise ApiError("bad_request", False, "transcript must be text")
    transcript = " ".join(transcript.split())
    if not transcript or len(transcript) > MAX_TRANSCRIPT_CHARS:
        raise ApiError("bad_request", False, "transcript must be 1-300 characters")
    log["transcript_chars"] = len(transcript)
    model = os.environ.get("OPENROUTER_ROUTER_MODEL", "").strip() or openrouter.DEFAULT_ROUTER_MODEL
    started = time.monotonic()
    try:
        decision = _openrouter_client().route_voice(transcript, model, VOICE_BUDGET_S)
    finally:
        log["inference_s"] = time.monotonic() - started
    log["backend"], log["model"], log["action"] = "openrouter", decision.model, decision.action
    answer: dict[str, Any] = {
        "request_id": request_id,
        "action": decision.action,
        "model": decision.model,
        "backend": "openrouter",
        "confidence": round(decision.confidence, 3),
    }
    if decision.command:
        answer["command"] = decision.command
    if decision.question:
        answer["question"] = decision.question
    return answer


def _handle(event: dict, log: dict) -> dict:
    method, path = _method_and_path(event)
    route = _route(path)
    if route is None:
        raise ApiError("not_found", False, "unknown route")
    if method != "POST":
        raise ApiError("method_not_allowed", False, f"method {method}")
    _check_auth(_headers(event))
    raw = _body_bytes(event)
    log["body_bytes"] = len(raw)
    if route == "voice":
        return _handle_voice(raw, log)
    req = _parse_request(raw, log)

    generation = _generate(req, log)
    log["backend"] = generation.backend
    log["model"] = generation.model

    result = safety.apply_filter(generation.text)
    log["filter"] = {
        "candidates": result.candidates,
        "kept": len(result.sentences),
        "dropped_reassurance": result.dropped_reassurance,
        "dropped_person": result.dropped_person,
        "truncated": result.truncated,
    }
    if not result.sentences:
        raise ApiError("inference_failed", False, "safety filter left nothing")
    return {
        "request_id": req.request_id,
        "text": result.text,
        "sentences": result.sentences,
        "model": generation.model,
        "backend": generation.backend,
        "silicon": generation.silicon,
    }
