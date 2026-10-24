"""Model backends for the Describe API (cloud/API.md §1–§2) and secret loading.

* ``InferenceBackend`` — our own inference server (EC2 inf2 / trn2, or a CPU dev box) over ``POST /v1/generate``.
* ``BedrockBackend`` — Amazon Bedrock Converse API with Amazon Nova Lite; the silicon behind it is not disclosed,
  so it is reported as ``silicon: "unspecified"``.

Every failure is raised as ``BackendError`` carrying the contract error code the API returns to the phone:
``inference_failed`` (502), ``unavailable`` (503) or ``timeout`` (504). Only stdlib is imported at module load;
boto3 is imported lazily, so tests and local runs work without it.
"""

from __future__ import annotations

import http.client
import json
import math
import os
import socket
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable, Optional

# Generation settings shared by every backend. Two short sentences fit comfortably in 96 tokens; greedy decoding
# keeps the small VLM from inventing detail.
MAX_NEW_TOKENS = 96
TEMPERATURE = 0.0

# The inference server reports which device produced the answer; the API reports it as backend + silicon.
DEVICE_SILICON = {"inf2": "Inferentia2", "trn2": "Trainium2", "cpu": "CPU"}

DEFAULT_BEDROCK_MODEL_ID = "us.amazon.nova-lite-v1:0"
BEDROCK_MODEL_IDS = frozenset({"us.amazon.nova-lite-v1:0", "amazon.nova-lite-v1:0"})

# Largest inference-server response we read (the text is ≤128 tokens; anything bigger is a fault).
_MAX_RESPONSE_BYTES = 64 * 1024


class BackendError(Exception):
    """A backend failure, already mapped to a contract error code."""

    STATUS = {"inference_failed": 502, "unavailable": 503, "timeout": 504}

    def __init__(self, code: str, retryable: bool, detail: str):
        if code not in self.STATUS:
            raise ValueError(f"not a backend error code: {code}")
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.retryable = retryable
        self.detail = detail  # short, never contains model output or image data

    @property
    def status(self) -> int:
        return self.STATUS[self.code]


@dataclass(frozen=True)
class Generation:
    text: str
    model: str
    backend: str  # inf2 | trn2 | bedrock | cpu
    silicon: str  # Inferentia2 | Trainium2 | unspecified | CPU


# --------------------------------------------------------------------------------------------------- secrets


class SecretError(Exception):
    pass


_secret_cache: dict[str, str] = {}
_secret_lock = threading.Lock()


def _ssm_get_parameter(name: str) -> str:
    """Read a SecureString from SSM Parameter Store (boto3 imported lazily)."""
    import boto3  # noqa: PLC0415 — lazy on purpose

    client = boto3.client("ssm")
    return client.get_parameter(Name=name, WithDecryption=True)["Parameter"]["Value"]


def load_secret(env_name: str, param_env_name: str) -> str:
    """Secret from env ``env_name``, else fetched once from the SSM parameter named by ``param_env_name``.

    Successful SSM reads are cached for the life of the Lambda execution environment; failures are not cached, so
    the next request retries. Raises ``SecretError`` when neither source is configured or SSM fails.
    """
    direct = os.environ.get(env_name, "")
    if direct:
        return direct
    param = os.environ.get(param_env_name, "")
    if not param:
        raise SecretError(f"neither {env_name} nor {param_env_name} is set")
    with _secret_lock:
        cached = _secret_cache.get(param)
        if cached:
            return cached
        try:
            value = _ssm_get_parameter(param)
        except Exception as exc:  # boto3/botocore errors, missing boto3, network
            raise SecretError(f"SSM read of {param} failed: {type(exc).__name__}") from exc
        if not value:
            raise SecretError(f"SSM parameter {param} is empty")
        _secret_cache[param] = value
        return value


def clear_secret_cache() -> None:
    with _secret_lock:
        _secret_cache.clear()


# --------------------------------------------------------------------------------------------- inference server


class InferenceBackend:
    """Calls the inference server's ``POST /v1/generate`` with ``X-Determine-Key`` (cloud/API.md §2)."""

    name = "inference"

    def __init__(self, url: str, key: str):
        self.url = url.rstrip("/") + "/v1/generate"
        self.key = key

    def generate(self, request_id: str, image_b64: str, prompt: str, timeout_s: float) -> Generation:
        if timeout_s <= 0:
            raise BackendError("timeout", True, "no time left for inference")
        body = json.dumps(
            {
                "request_id": request_id,
                "image_jpeg_b64": image_b64,
                "prompt": prompt,
                "max_new_tokens": MAX_NEW_TOKENS,
                "temperature": TEMPERATURE,
            },
            separators=(",", ":"),
        ).encode("utf-8")
        req = urllib.request.Request(
            self.url,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "X-Determine-Key": self.key,
            },
        )
        deadline = time.monotonic() + timeout_s
        try:
            with urllib.request.urlopen(req, timeout=timeout_s) as resp:
                raw = resp.read(_MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as exc:
            raise self._map_http_error(exc) from None
        except urllib.error.URLError as exc:
            raise self._map_transport_error(exc.reason) from None
        except (TimeoutError, socket.timeout) as exc:
            raise BackendError("timeout", True, "inference server did not answer in time") from exc
        except OSError as exc:  # connection reset mid-read, etc.
            raise self._map_transport_error(exc) from None
        except http.client.HTTPException as exc:  # truncated or malformed HTTP response
            raise BackendError("unavailable", True, f"inference connection broken: {type(exc).__name__}") from None
        if time.monotonic() > deadline + 0.25:
            # urllib timeouts are per socket operation; enforce the overall budget too.
            raise BackendError("timeout", True, "inference server answered after the budget")
        if len(raw) > _MAX_RESPONSE_BYTES:
            raise BackendError("inference_failed", True, "inference response too large")
        return self._parse(request_id, raw)

    @staticmethod
    def _map_http_error(exc: urllib.error.HTTPError) -> BackendError:
        code = exc.code
        retryable_hint: Optional[bool] = None
        error_name = ""
        try:
            payload = json.loads(exc.read(4096) or b"{}")
            if isinstance(payload, dict):
                if isinstance(payload.get("retryable"), bool):
                    retryable_hint = payload["retryable"]
                if isinstance(payload.get("error"), str):
                    error_name = payload["error"][:40]
        except (ValueError, OSError):
            pass
        finally:
            exc.close()
        if code == 503:  # not_ready: model still loading/compiling
            return BackendError("unavailable", True, f"inference server 503 {error_name or 'not_ready'}")
        if code in (401, 403):  # our INFERENCE_KEY does not match the server's
            return BackendError("unavailable", False, f"inference server rejected the key ({code})")
        if code == 404:  # INFERENCE_URL points at the wrong place
            return BackendError("unavailable", False, "inference endpoint not found (404)")
        if code in (502, 504):  # proxy/load balancer in front of a dead server
            return BackendError("unavailable", True, f"inference gateway {code}")
        if code == 400:  # we sent something the server rejects: a bug, not worth retrying
            return BackendError("inference_failed", False, f"inference server 400 {error_name}".strip())
        retryable = True if retryable_hint is None else retryable_hint
        return BackendError("inference_failed", retryable, f"inference server {code} {error_name}".strip())

    @staticmethod
    def _map_transport_error(reason: Any) -> BackendError:
        if isinstance(reason, (TimeoutError, socket.timeout)):
            return BackendError("timeout", True, "inference server did not answer in time")
        if isinstance(reason, (ConnectionRefusedError, socket.gaierror)):
            return BackendError("unavailable", True, f"inference server unreachable: {type(reason).__name__}")
        if isinstance(reason, (ConnectionResetError, ConnectionAbortedError, BrokenPipeError)):
            return BackendError("unavailable", True, f"inference connection dropped: {type(reason).__name__}")
        name = type(reason).__name__ if isinstance(reason, BaseException) else "URLError"
        return BackendError("unavailable", True, f"inference server unreachable: {name}")

    @staticmethod
    def _parse(request_id: str, raw: bytes) -> Generation:
        try:
            payload = json.loads(raw)
        except ValueError:
            raise BackendError("inference_failed", True, "inference response is not JSON") from None
        if not isinstance(payload, dict):
            raise BackendError("inference_failed", True, "inference response is not an object")
        text, model, device = payload.get("text"), payload.get("model"), payload.get("device")
        if not isinstance(text, str) or not isinstance(model, str) or not model:
            raise BackendError("inference_failed", True, "inference response missing text/model")
        if device not in DEVICE_SILICON:
            raise BackendError("inference_failed", False, "inference response has an unknown device")
        if payload.get("request_id") != request_id:
            raise BackendError("inference_failed", True, "inference response request_id mismatch")
        return Generation(text=text, model=model, backend=device, silicon=DEVICE_SILICON[device])


# ------------------------------------------------------------------------------------------------------ bedrock

# Bedrock error codes (ClientError.response["Error"]["Code"]) → (contract code, retryable).
_BEDROCK_ERRORS = {
    "ThrottlingException": ("unavailable", True),
    "ServiceUnavailableException": ("unavailable", True),
    "ModelNotReadyException": ("unavailable", True),
    "ServiceQuotaExceededException": ("unavailable", True),
    "AccessDeniedException": ("unavailable", False),  # model access not granted / IAM
    "ResourceNotFoundException": ("unavailable", False),  # model id not available in this region
    "ModelTimeoutException": ("timeout", True),
    "ValidationException": ("inference_failed", False),
    "ModelErrorException": ("inference_failed", True),
    "InternalServerException": ("inference_failed", True),
}
_TIMEOUT_EXCEPTIONS = {"ReadTimeoutError", "ConnectTimeoutError", "TimeoutError", "timeout"}
_CONNECTION_EXCEPTIONS = {"EndpointConnectionError", "ConnectionClosedError", "ConnectionError", "NoCredentialsError"}

_bedrock_clients: dict[tuple[str, float], Any] = {}
_bedrock_lock = threading.Lock()


def _default_bedrock_client(read_timeout_s: float) -> Any:
    import boto3  # noqa: PLC0415 — lazy on purpose
    from botocore.config import Config  # noqa: PLC0415

    key = (os.environ.get("AWS_REGION", ""), read_timeout_s)
    with _bedrock_lock:
        client = _bedrock_clients.get(key)
        if client is None:
            client = boto3.client(
                "bedrock-runtime",
                config=Config(
                    connect_timeout=min(2.0, read_timeout_s),
                    read_timeout=read_timeout_s,
                    retries={"max_attempts": 1, "mode": "standard"},
                ),
            )
            _bedrock_clients[key] = client
        return client


class BedrockBackend:
    """Amazon Nova Lite through the Bedrock Converse API, image sent as raw JPEG bytes."""

    name = "bedrock"

    def __init__(self, model_id: str = DEFAULT_BEDROCK_MODEL_ID, client_factory: Optional[Callable[[float], Any]] = None):
        if model_id not in BEDROCK_MODEL_IDS:
            raise ValueError(f"unsupported Bedrock model id: {model_id}")
        self.model_id = model_id
        self._client_factory = client_factory or _default_bedrock_client

    def generate(self, request_id: str, image_bytes: bytes, prompt: str, timeout_s: float) -> Generation:
        if timeout_s <= 0:
            raise BackendError("timeout", True, "no time left for Bedrock")
        # botocore takes the read timeout per client; floor to 0.5 s steps (stays inside the budget, keeps the
        # client cache small).
        read_timeout = max(0.5, math.floor(timeout_s * 2) / 2)
        try:
            client = self._client_factory(read_timeout)
            response = client.converse(
                modelId=self.model_id,
                messages=[
                    {
                        "role": "user",
                        "content": [
                            {"image": {"format": "jpeg", "source": {"bytes": image_bytes}}},
                            {"text": prompt},
                        ],
                    }
                ],
                inferenceConfig={"maxTokens": MAX_NEW_TOKENS, "temperature": TEMPERATURE},
            )
        except BackendError:
            raise
        except ImportError:
            raise BackendError("unavailable", False, "boto3 is not installed") from None
        except Exception as exc:  # botocore exceptions, mapped by name so botocore stays a lazy import
            raise self._map_error(exc) from None
        return self._parse(response)

    @staticmethod
    def _map_error(exc: BaseException) -> BackendError:
        response = getattr(exc, "response", None)
        if isinstance(response, dict):
            code = str(response.get("Error", {}).get("Code", ""))
            mapped = _BEDROCK_ERRORS.get(code)
            if mapped:
                return BackendError(mapped[0], mapped[1], f"bedrock {code}")
            status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
            if isinstance(status, int) and status >= 500:
                return BackendError("unavailable", True, f"bedrock {code or status}")
            return BackendError("inference_failed", False, f"bedrock {code or 'client error'}")
        names = {cls.__name__ for cls in type(exc).__mro__}
        if names & _TIMEOUT_EXCEPTIONS:
            return BackendError("timeout", True, f"bedrock {type(exc).__name__}")
        if names & _CONNECTION_EXCEPTIONS:
            return BackendError("unavailable", True, f"bedrock {type(exc).__name__}")
        return BackendError("inference_failed", True, f"bedrock {type(exc).__name__}")

    def _parse(self, response: Any) -> Generation:
        try:
            content = response["output"]["message"]["content"]
            text = " ".join(block["text"] for block in content if isinstance(block, dict) and "text" in block)
        except (KeyError, TypeError):
            raise BackendError("inference_failed", True, "bedrock response has no message content") from None
        return Generation(text=text, model=self.model_id, backend="bedrock", silicon="unspecified")
