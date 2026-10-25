"""OpenRouter backend for the Describe API: vision answers (``POST /v1/describe``) and voice routing
(``POST /v1/voice``).

* ``OpenRouterClient.describe`` sends one JPEG and a prompt to any OpenRouter vision model (chat completions) and
  returns the text.
* ``OpenRouterClient.route_voice`` sends only the words the wearer said (never audio, never an image) to Jev, a
  decision model, through OpenRouter's Decisions API. Jev answers one Choice question with the chosen route and a
  probability per route; the call acts on it only above ``VOICE_MIN_CONFIDENCE``. It returns one of the five fixed
  commands, "ask about the scene" (the phone then uploads a frame to ``/v1/describe`` with what the wearer said as
  the question), or nothing.

Vision calls ask OpenRouter not to route to providers that collect data (``provider.data_collection = "deny"``);
``OPENROUTER_ZDR=1`` additionally restricts them to zero-data-retention endpoints. The Decisions API has no such
option, so a voice transcript goes to TypeSafe under OpenRouter's default policy. Failures raise
``backends.BackendError`` with the contract code. Stdlib only.
"""

from __future__ import annotations

import base64
import http.client
import json
import os
import socket
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Optional

import backends

DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
DEFAULT_VISION_MODEL = "openai/gpt-6-luna"
# Jev is TypeSafe's decision model (not a chat model): it answers typed questions with probabilities through the
# Decisions API. Pinned, because the probability threshold below is tuned to one release.
DEFAULT_ROUTER_MODEL = "typesafe/jev-1.14"

# Room for a short answer plus any hidden reasoning a model spends first; the safety filter keeps two sentences.
VISION_MAX_TOKENS = 400
VISION_TEMPERATURE = 0.2
# The chosen route is acted on only when Jev gives it at least this probability; otherwise the phone does nothing.
VOICE_MIN_CONFIDENCE = 0.6
MAX_RESPONSE_BYTES = 256 * 1024

COMMANDS = ("whatsAhead", "readText", "pauseWarnings", "resumeWarnings", "stopSpeaking")

# One Choice question. Each key is an option; each value says when it applies. Jev returns the chosen key and a
# probability for every key, so the caller can refuse to act on a doubtful answer. The transcript is state (data):
# there is nothing in it Jev could be talked into doing, because the only possible output is one of these keys.
ROUTE_INSTRUCTIONS = (
    "A blind pedestrian said this to their navigation assistant after its wake phrase (the speech may be mis-heard). "
    "Which of these do they want?"
)
ROUTE_CRITERIA = {
    "whatsAhead": "They want a general description of what is in front of them: what's ahead, what's in front of me, describe the scene.",
    "readText": "They want text read aloud: read this, read the sign, read the label, what does it say.",
    "pauseWarnings": "They want the obstacle warnings paused or muted: pause, stop the warnings, quiet the alerts for a while.",
    "resumeWarnings": "They want paused obstacle warnings turned back on: resume, start the warnings again.",
    "stopSpeaking": "They want the assistant to stop talking or cancel what it is reading out.",
    "askAboutScene": "They ask a specific question that needs the camera: is the door open, what colour is the car, where is the bench, how many people are there.",
    "none": "It is not addressed to the assistant, is unclear, is small talk, or asks for anything else.",
}


@dataclass(frozen=True)
class VoiceDecision:
    action: str  # command | ask | none
    command: Optional[str]
    question: Optional[str]
    model: str
    confidence: float  # Jev's probability for the chosen route (0 when there is no usable answer)


class OpenRouterClient:
    name = "openrouter"

    def __init__(self, key: str, base_url: Optional[str] = None):
        self.key = key
        base = (base_url or os.environ.get("OPENROUTER_BASE_URL", "").strip() or DEFAULT_BASE_URL).rstrip("/")
        self.url = base + "/chat/completions"
        # The Decisions API lives beside /v1, not under it: https://openrouter.ai/api/alpha/decisions
        root = base[: -len("/v1")] if base.endswith("/v1") else base
        self.decisions_url = root + "/alpha/decisions"

    # ------------------------------------------------------------------------------------------ requests

    @staticmethod
    def _provider() -> dict:
        provider: dict[str, Any] = {"data_collection": "deny"}
        if os.environ.get("OPENROUTER_ZDR", "").strip() in ("1", "true", "yes"):
            provider["zdr"] = True
        return provider

    def _post(self, body: dict, timeout_s: float, url: Optional[str] = None) -> dict:
        if timeout_s <= 0:
            raise backends.BackendError("timeout", True, "no time left for OpenRouter")
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        req = urllib.request.Request(
            url or self.url,
            data=data,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "Authorization": f"Bearer {self.key}",
                "HTTP-Referer": "https://github.com/nunera/Determine",
                "X-Title": "Determine",
            },
        )
        deadline = time.monotonic() + timeout_s
        try:
            with urllib.request.urlopen(req, timeout=timeout_s) as resp:
                raw = resp.read(MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as exc:
            raise self._map_http_error(exc) from None
        except urllib.error.URLError as exc:
            raise self._map_transport_error(exc.reason) from None
        except (TimeoutError, socket.timeout) as exc:
            raise backends.BackendError("timeout", True, "OpenRouter did not answer in time") from exc
        except OSError as exc:
            raise self._map_transport_error(exc) from None
        except http.client.HTTPException as exc:
            raise backends.BackendError("unavailable", True, f"OpenRouter connection broken: {type(exc).__name__}") from None
        if time.monotonic() > deadline + 0.25:
            raise backends.BackendError("timeout", True, "OpenRouter answered after the budget")
        if len(raw) > MAX_RESPONSE_BYTES:
            raise backends.BackendError("inference_failed", True, "OpenRouter response too large")
        try:
            payload = json.loads(raw)
        except ValueError:
            raise backends.BackendError("inference_failed", True, "OpenRouter response is not JSON") from None
        if not isinstance(payload, dict):
            raise backends.BackendError("inference_failed", True, "OpenRouter response is not an object")
        if isinstance(payload.get("error"), dict) and "choices" not in payload and "answers" not in payload:
            # An error object with a 200: the routed provider failed after OpenRouter accepted the request.
            code = payload["error"].get("code")
            retryable = not (isinstance(code, int) and 400 <= code < 500 and code not in (408, 429))
            raise backends.BackendError("inference_failed", retryable, f"OpenRouter provider error {code}")
        return payload

    @staticmethod
    def _map_http_error(exc: urllib.error.HTTPError) -> backends.BackendError:
        code = exc.code
        try:
            exc.read(2048)
        except OSError:
            pass
        finally:
            exc.close()
        if code in (401, 403):
            return backends.BackendError("unavailable", False, f"OpenRouter rejected the key ({code})")
        if code == 402:
            return backends.BackendError("unavailable", False, "OpenRouter account is out of credits (402)")
        if code in (408, 504):
            return backends.BackendError("timeout", True, f"OpenRouter {code}")
        if code == 429:
            return backends.BackendError("unavailable", True, "OpenRouter rate limit (429)")
        if code == 404:
            return backends.BackendError("unavailable", False, "OpenRouter has no such model or route (404)")
        if code == 400:
            return backends.BackendError("inference_failed", False, "OpenRouter rejected the request (400)")
        if code >= 500:
            return backends.BackendError("unavailable", True, f"OpenRouter {code}")
        return backends.BackendError("inference_failed", True, f"OpenRouter {code}")

    @staticmethod
    def _map_transport_error(reason: Any) -> backends.BackendError:
        if isinstance(reason, (TimeoutError, socket.timeout)):
            return backends.BackendError("timeout", True, "OpenRouter did not answer in time")
        name = type(reason).__name__ if isinstance(reason, BaseException) else "URLError"
        return backends.BackendError("unavailable", True, f"OpenRouter unreachable: {name}")

    @staticmethod
    def _message(payload: dict) -> tuple[dict, str]:
        try:
            message = payload["choices"][0]["message"]
        except (KeyError, IndexError, TypeError):
            raise backends.BackendError("inference_failed", True, "OpenRouter response has no message") from None
        model = payload.get("model")
        return message, model if isinstance(model, str) and model else "openrouter"

    # ---------------------------------------------------------------------------------------------- vision

    def describe(self, image_bytes: bytes, prompt: str, model: str, timeout_s: float) -> backends.Generation:
        body: dict[str, Any] = {
            "model": model,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {
                            "type": "image_url",
                            "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(image_bytes).decode("ascii")},
                        },
                    ],
                }
            ],
            "max_tokens": VISION_MAX_TOKENS,
            "temperature": VISION_TEMPERATURE,
            "reasoning": {"effort": os.environ.get("OPENROUTER_REASONING", "").strip() or "low"},
            "provider": self._provider(),
        }
        message, served = self._message(self._post(body, timeout_s))
        content = message.get("content")
        if isinstance(content, list):
            content = " ".join(p.get("text", "") for p in content if isinstance(p, dict))
        if not isinstance(content, str) or not content.strip():
            raise backends.BackendError("inference_failed", True, "OpenRouter answer was empty")
        return backends.Generation(text=content, model=served, backend="openrouter", silicon="unspecified")

    # ------------------------------------------------------------------------------------------- voice route

    def route_voice(self, transcript: str, model: str, timeout_s: float) -> VoiceDecision:
        body: dict[str, Any] = {
            "model": model,
            "state": {"transcript": transcript},
            "questions": {
                "route": {"type": "choice", "instructions": ROUTE_INSTRUCTIONS, "criteria": ROUTE_CRITERIA},
            },
        }
        payload = self._post(body, timeout_s, self.decisions_url)
        served = payload.get("model")
        served = served if isinstance(served, str) and served else model
        return self._decision(payload, served, transcript)

    @staticmethod
    def _decision(payload: dict, model: str, transcript: str) -> VoiceDecision:
        none = VoiceDecision("none", None, None, model, 0.0)
        answer = ((payload.get("answers") or {}).get("route")) if isinstance(payload.get("answers"), dict) else None
        if not isinstance(answer, dict) or answer.get("type") != "choice":
            return none
        chosen = answer.get("choice")
        probabilities = answer.get("probabilities")
        if chosen not in ROUTE_CRITERIA or not isinstance(probabilities, dict):
            return none
        probability = probabilities.get(chosen)
        if isinstance(probability, bool) or not isinstance(probability, (int, float)):
            return none
        probability = float(probability)
        if probability < min_confidence():
            return VoiceDecision("none", None, None, model, probability)
        if chosen in COMMANDS:
            return VoiceDecision("command", chosen, None, model, probability)
        if chosen == "askAboutScene":
            # Jev only chooses; the question is what the wearer said, as plain text.
            question = clean_question(transcript)
            if question:
                return VoiceDecision("ask", None, question, model, probability)
        return VoiceDecision("none", None, None, model, probability)


def min_confidence() -> float:
    try:
        value = float(os.environ.get("VOICE_MIN_CONFIDENCE", "").strip() or VOICE_MIN_CONFIDENCE)
    except ValueError:
        return VOICE_MIN_CONFIDENCE
    return value if 0.0 < value <= 1.0 else VOICE_MIN_CONFIDENCE


MAX_QUESTION_CHARS = 300


def clean_question(value: Any) -> Optional[str]:
    """A question as plain single-line text, or None. Quotes and control characters never reach a prompt."""
    if not isinstance(value, str):
        return None
    text = " ".join("".join(ch if ch.isprintable() else " " for ch in value).split())
    text = text.replace('"', "'")
    if not text or len(text) > MAX_QUESTION_CHARS:
        return None
    return text
