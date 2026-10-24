#!/usr/bin/env python3
"""Measure a running inference server: p50/p95/max latency, tokens/s, cost per request.

Evidence for Annapurna judges (Arch §11.6): every number printed here comes from requests this script made.

  python3 bench.py --url http://10.0.1.23:8080 --key $INFERENCE_KEY --images ./frames -n 50 --hourly-usd 0.76
  python3 bench.py --url http://127.0.0.1:8080 -n 5 --hourly-usd 0 --out cpu.json     # generated test image

Cost per request = hourly price / 3600 × mean server latency (one request at a time, instance otherwise idle).
inf2.xlarge on-demand is $0.76/h (https://aws.amazon.com/ec2/instance-types/inf2/, us-east-1, checked 2026-10-24).
"""
from __future__ import annotations

import argparse
import base64
import glob
import json
import math
import os
import statistics
import sys
import time
import urllib.error
import urllib.request

PROMPT = ("You are describing a photo taken from a blind pedestrian's eye level, facing forward. In at most two "
          "short sentences, describe the surroundings ahead: walkable path, doors, steps, obstacles and where they "
          "are (left, ahead, right). Mention people only as \"a person\" or \"people\" and where they are. Do not "
          "guess anyone's identity, age, gender, race, emotion or intent. Never say the path is clear, safe or empty.")


def percentile(values: list[float], p: float) -> float | None:
    """Nearest-rank percentile (p in 0..100)."""
    if not values:
        return None
    s = sorted(values)
    k = max(1, math.ceil(p / 100 * len(s)))
    return s[k - 1]


def summarize(lat_ms: list[float], server_ms: list[float], tokens: list[int], errors: int, hourly_usd: float) -> dict:
    total_s = sum(server_ms) / 1000
    mean_server = statistics.fmean(server_ms) if server_ms else None
    return {
        "requests": len(lat_ms) + errors,
        "ok": len(lat_ms),
        "errors": errors,
        "client_ms": {"p50": percentile(lat_ms, 50), "p95": percentile(lat_ms, 95), "max": max(lat_ms) if lat_ms else None},
        "server_ms": {"p50": percentile(server_ms, 50), "p95": percentile(server_ms, 95), "mean": mean_server},
        "tokens_per_s": (sum(tokens) / total_s) if total_s > 0 else None,
        "hourly_usd": hourly_usd,
        "usd_per_request": (hourly_usd / 3600 * mean_server / 1000) if mean_server is not None else None,
    }


def test_jpeg() -> bytes:
    """A 320×240 synthetic scene (door frame and a box), generated with Pillow."""
    import io
    from PIL import Image, ImageDraw
    img = Image.new("RGB", (320, 240), (180, 180, 170))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 170, 320, 240], fill=(110, 100, 90))
    d.rectangle([220, 40, 290, 200], outline=(60, 40, 20), width=8)
    d.rectangle([60, 140, 130, 200], fill=(150, 90, 40))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=85)
    return buf.getvalue()


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://127.0.0.1:8080")
    p.add_argument("--key", default=os.environ.get("INFERENCE_KEY", ""))
    p.add_argument("--images", help="directory of .jpg files (default: one generated test image)")
    p.add_argument("-n", type=int, default=20)
    p.add_argument("--max-new-tokens", type=int, default=64)
    p.add_argument("--hourly-usd", type=float, required=True, help="instance price; 0.76 for inf2.xlarge on-demand")
    p.add_argument("--timeout", type=float, default=60)
    p.add_argument("--out", help="write the JSON summary here")
    args = p.parse_args(argv)

    files = sorted(glob.glob(os.path.join(args.images, "*.jp*g"))) if args.images else []
    images = [open(f, "rb").read() for f in files] or [test_jpeg()]
    health = json.loads(urllib.request.urlopen(args.url + "/health", timeout=10).read())
    if not health.get("ready"):
        print(f"server not ready: {health}", file=sys.stderr)
        return 2

    lat, srv, toks, errors = [], [], [], 0
    for i in range(args.n):
        body = json.dumps({"request_id": f"bench-{i}", "image_jpeg_b64": base64.b64encode(images[i % len(images)]).decode(),
                           "prompt": PROMPT, "max_new_tokens": args.max_new_tokens, "temperature": 0}).encode()
        req = urllib.request.Request(args.url + "/v1/generate", body, {"Content-Type": "application/json",
                                                                        "X-Determine-Key": args.key})
        t0 = time.monotonic()
        try:
            r = json.loads(urllib.request.urlopen(req, timeout=args.timeout).read())
        except (urllib.error.URLError, TimeoutError) as e:
            errors += 1
            print(f"request {i}: {e}", file=sys.stderr)
            continue
        lat.append((time.monotonic() - t0) * 1000)
        srv.append(r["latency_ms"]["total"])
        toks.append(r["tokens"])
        if i == 0:
            print(f"first answer: {r['text']!r}")

    result = {"health": health, "images": len(images), "max_new_tokens": args.max_new_tokens,
              **summarize(lat, srv, toks, errors, args.hourly_usd)}
    text = json.dumps(result, indent=1)
    print(text)
    if args.out:
        with open(args.out, "w") as f:
            f.write(text + "\n")
    return 0 if errors == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
