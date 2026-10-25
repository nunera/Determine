#!/usr/bin/env python3
"""Describe bake-off: the same photos through several OpenRouter vision models with the API's own prompt and safety
filter, printing each answer, the latency and the token usage OpenRouter reports.

Run it where the key lives (the server), so the key never has to be copied:

    OPENROUTER_API_KEY=... python3 describe_bakeoff.py --images DIR [--models a,b,c] [--question "..."] [--runs 2]

Only the standard library. Answers are printed after the same filter the API applies, so what you read is what the
wearer would hear. Costs are the live per-token prices from OpenRouter's public model list times the reported usage.
"""

from __future__ import annotations

import argparse
import base64
import glob
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "describe-api", "app"))

import openrouter  # noqa: E402
import safety  # noqa: E402

DEFAULT_MODELS = [
    "openai/gpt-6-luna",
    "anthropic/claude-haiku-5.5",
    "google/gemini-3.1-flash-lite",
    "openai/gpt-5.6-terra",
]


def prices() -> dict[str, tuple[float, float]]:
    with urllib.request.urlopen("https://openrouter.ai/api/v1/models", timeout=30) as resp:
        data = json.load(resp)["data"]
    return {m["id"]: (float(m["pricing"].get("prompt", 0)), float(m["pricing"].get("completion", 0))) for m in data}


def call(key: str, model: str, jpeg: bytes, prompt: str) -> tuple[str, float, dict]:
    body = {
        "model": model,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()}},
        ]}],
        "max_tokens": openrouter.VISION_MAX_TOKENS,
        "temperature": openrouter.VISION_TEMPERATURE,
        "reasoning": {"effort": os.environ.get("OPENROUTER_REASONING", "low")},
        "provider": {"data_collection": "deny"},
    }
    req = urllib.request.Request(
        openrouter.DEFAULT_BASE_URL + "/chat/completions", data=json.dumps(body).encode(), method="POST",
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json", "X-Title": "Determine bake-off"},
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.load(resp)
    except urllib.error.HTTPError as exc:
        return f"HTTP {exc.code}: {exc.read(300).decode(errors='replace')}", time.monotonic() - started, {}
    elapsed = time.monotonic() - started
    try:
        text = payload["choices"][0]["message"]["content"] or ""
    except (KeyError, IndexError, TypeError):
        text = f"(no content) {json.dumps(payload)[:200]}"
    return text, elapsed, payload.get("usage") or {}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", required=True, help="directory of .jpg files")
    ap.add_argument("--models", default=",".join(DEFAULT_MODELS))
    ap.add_argument("--question", default=None)
    ap.add_argument("--runs", type=int, default=1)
    args = ap.parse_args()
    key = os.environ.get("OPENROUTER_API_KEY", "")
    if not key:
        print("set OPENROUTER_API_KEY", file=sys.stderr)
        return 2
    question = openrouter.clean_question(args.question) if args.question else None
    prompt = safety.prompt_for(question)
    price = prices()
    images = sorted(glob.glob(os.path.join(args.images, "*.jpg")))
    summary: dict[str, dict] = {}
    for model in [m.strip() for m in args.models.split(",") if m.strip()]:
        pin, pout = price.get(model, (0.0, 0.0))
        times: list[float] = []
        costs: list[float] = []
        print(f"\n===== {model}  (${pin * 1e6:.2f} / ${pout * 1e6:.2f} per 1M tokens)")
        for path in images:
            jpeg = open(path, "rb").read()
            for run in range(args.runs):
                text, elapsed, usage = call(key, model, jpeg, prompt)
                filtered = safety.apply_filter(text).text if text and not text.startswith(("HTTP", "(no content")) else text
                cost = usage.get("prompt_tokens", 0) * pin + usage.get("completion_tokens", 0) * pout
                if not text.startswith(("HTTP", "(no content")):
                    times.append(elapsed)
                    costs.append(cost)
                print(f"[{os.path.basename(path)} #{run + 1}] {elapsed:4.1f}s  in={usage.get('prompt_tokens', '-')} "
                      f"out={usage.get('completion_tokens', '-')}  ${cost:.5f}\n    {filtered}")
        if times:
            summary[model] = {"p50_s": statistics.median(times), "max_s": max(times), "avg_cost": statistics.mean(costs)}
    print("\n===== summary")
    for model, s in summary.items():
        print(f"{model:40} p50 {s['p50_s']:.1f}s  max {s['max_s']:.1f}s  avg ${s['avg_cost']:.5f}/answer")
    return 0


if __name__ == "__main__":
    sys.exit(main())
