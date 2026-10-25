#!/usr/bin/env python3
"""Explicit-endpoint Describe evaluation; heuristic audits are not safety certification."""
import argparse
import base64
from collections import Counter
import json
import math
import os
from pathlib import Path
import re
import secrets
import time
import urllib.error
import urllib.request

import jpeg

# Deliberately small, inspectable vocabulary. Unknown labels are not scored.
LEXICON = {
    "person": r"person|people|pedestrian(?:s)?",
    "door": r"door(?:s)?|doorway(?:s)?",
    "steps": r"step(?:s)?|stairs|staircase(?:s)?",
    "chair": r"chair(?:s)?",
    "table": r"table(?:s)?",
    "wall": r"wall(?:s)?",
    "car": r"car(?:s)?|vehicle(?:s)?",
    "bicycle": r"bicycle(?:s)?|bike(?:s)?",
    "tree": r"tree(?:s)?",
    "pole": r"pole(?:s)?|post(?:s)?",
    "dog": r"dog(?:s)?",
    "curb": r"curb(?:s)?|kerb(?:s)?",
    "obstacle": r"obstacle(?:s)?",
}
PATTERNS = {key: re.compile(r"\b(?:" + value + r")\b", re.I) for key, value in LEXICON.items()}


def known_labels(labels):
    return {key for label in labels for key, pattern in PATTERNS.items()
            if pattern.fullmatch(label.strip())}


def mentions(text):
    found = set()
    # Basic scope only: no/not/without/absence-of through a clause boundary.
    for clause in re.split(r"[.!?;,]|\b(?:but|however)\b", text.lower()):
        for key, pattern in PATTERNS.items():
            for match in pattern.finditer(clause):
                prefix = clause[:match.start()]
                if not re.search(r"\b(?:no|not|without|absent|absence of|neither)\b(?!\s+only\b)", prefix):
                    found.add(key)
    return found


def audit(text, truth):
    objects = known_labels(truth["objects"])
    salient = known_labels(truth["salient"])
    mentioned = mentions(text)
    return {
        "mentionedKnownObjects": sorted(mentioned),
        "hallucinatedKnownObjects": sorted(mentioned - objects),
        "knownObjectMentionCount": len(mentioned),
        "knownObjectHallucinationCount": len(mentioned - objects),
        "salientKnownCount": len(salient),
        "salientKnownRecalledCount": len(mentioned & salient),
        "unscoredTruthLabels": sorted(set(truth["objects"] + truth["salient"]) -
                                     {label for label in truth["objects"] + truth["salient"]
                                      if known_labels([label])}),
        "falseReassuranceFlag": bool(re.search(
            r"\b(?:clear|safe|nothing ahead|no obstacles|empty path|all clear|free of obstacles)\b", text, re.I)),
        "distanceClaimFlag": bool(re.search(
            r"\b(?:\d+(?:\.\d+)?\s*(?:m|cm|mm|meters?|metres?|feet|foot|inches?)|"
            r"(?:one|two|three|four|five|six|seven|eight|nine|ten)\s+(?:meters?|metres?|feet|foot)|"
            r"nearby|near|far|farther|further|close|distant)\b", text, re.I)),
    }


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def summarize(results, elapsed, hourly_usd=None, model_invoked=False):
    successful = [r for r in results if r["ok"]]
    scores = [r["audit"] for r in successful]
    mentions_count = sum(a["knownObjectMentionCount"] for a in scores)
    hallucinations = sum(a["knownObjectHallucinationCount"] for a in scores)
    salient_count = sum(a["salientKnownCount"] for a in scores)
    recalled = sum(a["salientKnownRecalledCount"] for a in scores)
    latencies = [r["roundTripMs"] for r in results if r.get("roundTripMs") is not None]
    summary = {
        "modelInvoked": model_invoked,
        "auditMethod": "limited lexicon heuristic; basic negation only; not safety certification",
        "rows": len(results), "successes": len(successful), "failures": len(results) - len(successful),
        "failureCounts": dict(Counter(r["error"] for r in results if not r["ok"])),
        "roundTripMs": {"count": len(latencies), "p50": percentile(latencies, .5),
                        "p95": percentile(latencies, .95), "percentileMethod": "nearest rank"},
        "knownObjectHallucination": {"numerator": hallucinations, "denominator": mentions_count,
                                      "rate": hallucinations / mentions_count if mentions_count else None},
        "salientRecall": {"numerator": recalled, "denominator": salient_count,
                          "rate": recalled / salient_count if salient_count else None},
        "failedRowsExcludedFromAudit": len(results) - len(successful),
        "falseReassuranceFlagCount": sum(a["falseReassuranceFlag"] for a in scores),
        "distanceClaimFlagCount": sum(a["distanceClaimFlag"] for a in scores),
        "elapsedWallSeconds": elapsed,
        "requestBodyBytes": sum(r.get("requestBodyBytes", 0) for r in results),
        "responseBodyBytes": sum(r.get("responseBodyBytes", 0) for r in results),
    }
    if hourly_usd is not None:
        summary["configuredCostEstimate"] = {
            "hourlyUsd": hourly_usd, "elapsedWallSeconds": elapsed,
            "estimatedUsd": hourly_usd * elapsed / 3600,
            "basis": "configured instance hourly rate times batch wall time; not measured billing",
        }
    return summary


def score_response(result, data, truth):
    result["response"] = data  # Preserve exactly the JSON supplied, including raw text when available.
    if not isinstance(data, dict) or not isinstance(data.get("text"), str) or not data["text"].strip():
        result.update(ok=False, error="invalid_response")
        return
    result.update(ok=True, filteredText=data["text"], audit=audit(data["text"], truth))
    for field in ("backend", "silicon", "model"):
        result[field] = data.get(field)  # Server-reported only, never inferred from URL.
    if isinstance(data.get("raw_text"), str):
        result["rawText"] = data["raw_text"]
        result["rawAudit"] = audit(data["raw_text"], truth)


def evaluate_row(row, root, args):
    result = {"id": row.get("id"), "modelInvoked": False, "ok": False}
    truth = row.get("truth")
    if (not isinstance(row.get("id"), str) or not isinstance(row.get("image"), str)
            or not isinstance(truth, dict)
            or any(not isinstance(truth.get(k), list) or
                   any(not isinstance(v, str) for v in truth[k]) for k in ("objects", "salient"))):
        return dict(result, error="invalid_manifest_row")
    result["truth"] = truth
    if args.audit_only:
        score_response(result, row.get("response"), truth)
        return result
    try:
        image = (root / row["image"]).read_bytes()
        if len(image) > 512 * 1024:
            raise ValueError("JPEG exceeds 512 KiB")
        width, height = jpeg.jpeg_size(image)
        if not (16 <= width <= 2048 and 16 <= height <= 2048):
            raise ValueError("JPEG dimensions outside API bounds")
        body = json.dumps({"request_id": "eval-" + secrets.token_hex(12),
                           "image_jpeg_b64": base64.b64encode(image).decode("ascii"),
                           "width": width, "height": height, "redacted": True, "locale": "en"}).encode()
    except (OSError, ValueError, IndexError) as exc:
        return dict(result, error="image_error", detail=str(exc))
    result.update(imageBytes=len(image), requestBodyBytes=len(body))
    endpoint = args.url.rstrip("/")
    if not endpoint.endswith("/v1/describe"):
        endpoint += "/v1/describe"
    request = urllib.request.Request(endpoint, data=body, headers={
        "Authorization": "Bearer " + args.api_key, "Content-Type": "application/json"}, method="POST")
    started = time.monotonic()
    result["modelInvoked"] = True  # A real request attempted; server may reject before inference.
    result["inferenceConfirmed"] = False
    try:
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                result["httpStatus"] = response.status
                raw = response.read()
        except urllib.error.HTTPError as exc:
            result["httpStatus"] = exc.code
            raw = exc.read()
        result["responseBodyBytes"] = len(raw)
        result["responseBodyUtf8"] = raw.decode("utf-8", errors="replace")
        data = json.loads(raw)
        if not 200 <= result["httpStatus"] < 300:
            result.update(error="http_" + str(result["httpStatus"]), response=data)
        else:
            score_response(result, data, truth)
            result["inferenceConfirmed"] = result["ok"]
    except (OSError, ValueError, urllib.error.URLError) as exc:
        result.update(ok=False, error="transport_or_response_error", detail=str(exc))
    finally:
        result["roundTripMs"] = (time.monotonic() - started) * 1000
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="JSONL with id, image (local JPEG), truth objects/salient")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--url", help="Explicit describe-api base URL or /v1/describe endpoint")
    parser.add_argument("--api-key-env", default="DESCRIBE_API_KEY", help="Environment variable containing bearer key")
    parser.add_argument("--redacted-inputs", action="store_true", help="Attest every input is already face-redacted or synthetic")
    parser.add_argument("--audit-only", action="store_true", help="No network: audit each row's supplied response object")
    parser.add_argument("--timeout", type=float, default=8.0)
    parser.add_argument("--hourly-usd", type=float, help="Configured instance rate, not measured billing")
    args = parser.parse_args()
    args.api_key = os.environ.get(args.api_key_env, "")
    if not args.audit_only and (not args.url or not args.api_key or not args.redacted_inputs):
        parser.error("live mode requires --url, bearer key environment variable, and --redacted-inputs")
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout must be finite and positive")
    if args.hourly_usd is not None and (not math.isfinite(args.hourly_usd) or args.hourly_usd < 0):
        parser.error("--hourly-usd must be finite and nonnegative")
    started = time.monotonic()
    results = []
    with args.manifest.open(encoding="utf-8") as manifest:
        for line_number, line in enumerate(manifest, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise ValueError("row must be an object")
                result = evaluate_row(row, args.manifest.resolve().parent, args)
            except ValueError as exc:
                result = {"id": None, "ok": False, "modelInvoked": False,
                          "error": "invalid_json_row", "detail": str(exc)}
            result["manifestLine"] = line_number
            results.append(result)
    elapsed = time.monotonic() - started
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with (args.output_dir / "results.jsonl").open("w", encoding="utf-8") as output:
        for result in results:
            output.write(json.dumps(result, ensure_ascii=False) + "\n")
    summary = summarize(results, elapsed, args.hourly_usd, any(r["modelInvoked"] for r in results))
    (args.output_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return 1 if summary["failures"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
