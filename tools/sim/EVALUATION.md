# Describe endpoint batch evaluation

`eval_describe.py` is a stdlib-only caller of the existing [Describe API](../../cloud/API.md), not an inference server or a safety filter. It does not change serving behavior. It can evaluate a real configured Annapurna-connected endpoint or compare configured model endpoints, including Meta models if a compatible server actually serves one. No provider, hardware deployment, credentials, or measurements are bundled or assumed. Run separate output directories for each endpoint/model and use the same manifest; reported backend, silicon, and model are server declarations, not independently verified hardware measurements.

## Input and invocation

Each nonblank JSONL line is one object with exactly these required evaluation fields:

```json
{"id":"scene-001","image":"images/scene-001.jpg","truth":{"objects":["door","wall"],"salient":["door"]}}
```

`image` is the exact local JPEG path field (not `path`), resolved relative to the manifest directory; absolute paths also work. `id` is a string carried into the results. `objects` and `salient` are explicit human-supplied lists of object labels for the full image being evaluated. Do not replace these with sensor observations. A single Modulino ToF zone with an 18° cone and a white-target maximum of 1.2 m does not establish full-camera truth, object identity, or a clear path. Negative truth for a person is represented by omitting `person` from `objects`, not by the label `no person`.

Images must already be synthetic or face-redacted: the CLI performs no face detection or blurring. Live mode requires an explicit attestation and endpoint, plus a bearer key read from the environment (never embedded in the manifest or output):

```sh
python3 tools/sim/eval_describe.py --help
export DESCRIBE_API_KEY='your-configured-key'
python3 tools/sim/eval_describe.py dataset/manifest.jsonl \
  --url https://your-configured-host/v1/describe --redacted-inputs \
  --output-dir evaluation/run-a
```

A base URL is also accepted; `/v1/describe` is appended. `--api-key-env NAME` selects a different environment variable. There is no default network endpoint, mock response, fallback model, or retry. Each live request sends the API's bearer authentication, a generated request ID, base64 JPEG, parsed width/height, `locale: en`, and `redacted: true`. Images over 512 KiB or outside the 16–2048 dimension bounds fail locally. Timeout defaults to eight seconds and is configurable with `--timeout`.

For offline auditing add a supplied API-shaped `response` object to each manifest row:

```json
{"id":"scene-001","image":"images/scene-001.jpg","truth":{"objects":["door"],"salient":["door"]},"response":{"text":"A doorway ahead.","model":"recorded-model"}}
```

```sh
python3 tools/sim/eval_describe.py dataset/recorded.jsonl \
  --audit-only --output-dir evaluation/offline
```

Audit-only never opens the image, requires no key, makes no network calls, and records `modelInvoked: false`. `image` remains a required provenance field. A missing/empty `response.text` is a failed row, not a fabricated answer. Live `modelInvoked: true` means a real request was attempted; rejection or transport failure may occur before inference. `inferenceConfirmed` indicates a successful text response, not independent observation of model execution.

## Outputs and denominators

`results.jsonl` records per-row truth, errors, successful filtered text, complete parsed API response, raw response body text, response/request body byte counts, JPEG bytes, HTTP status, and observed round-trip latency where a request was attempted. Byte counts exclude HTTP headers, TLS, and network overhead. The full response preserves any additional server fields. If the server supplies `raw_text`, it is also exposed as `rawText` with a separate `rawAudit`; the normal API only promises filtered `text`, so raw generation is otherwise unavailable and is never reconstructed. Backend, silicon, and model are copied only from the response; missing values remain null.

`summary.json` reports success/failure counts by error, elapsed batch wall time, request/response body bytes, and nearest-rank p50/p95 round-trip milliseconds across attempted requests, including failed requests. These are client-observed round trips, not isolated inference benchmarks. Offline runs have no round-trip samples. Server timings remain in each preserved response.

The audit is a limited English lexicon heuristic, not a judge model, semantic verifier, accessibility validation, or safety certification. Vocabulary and aliases are inspectable in `LEXICON` in the script: person, door, steps, chair, table, wall, car, bicycle, tree, pole, dog, curb, obstacle. Unmatched words are not automatically hallucinations. Unknown truth labels are reported but excluded from scoring. Each canonical category counts at most once per row, regardless of repetitions or aliases.

- Known-object hallucination numerator: affirmative mentioned known categories absent from explicit `objects`. Denominator: all affirmative mentioned known categories on successfully scored rows.
- Salient recall numerator: known categories in `salient` affirmatively mentioned. Denominator: all known categories supplied in `salient` on successfully scored rows.
- Zero denominators yield JSON null rates, never artificial zero/perfect scores. Failed rows are counted separately and excluded from both audits; inspect failures before comparing models.
- Basic negation (`no`, `not`, `without`, `absent`, `absence of`, `neither`) suppresses mentions through a punctuation or `but`/`however` boundary, except `not only`. This intentionally simple scope can misread complex sentences, double negatives, or coordination. It does not score the accuracy of negative assertions.
- False-reassurance and distance-claim flags are conservative lexical review cues, not verified unsafe claims. Negated reassurance can still flag; qualitative near/far wording also flags. Human review is required. Nothing in the audit licenses a clear-path claim.

Optional `--hourly-usd RATE` adds a **configured estimate**: rate × elapsed batch wall seconds / 3600. This is not an observed cloud bill, GPU utilization measure, per-token price, or evidence of a deployed instance. It excludes warm-up outside the batch, idle lifetime, network charges, storage, and other services; applying it to offline mode estimates only the local batch wall interval at the supplied hypothetical rate.

Exit status is 1 if any row failed and 0 otherwise. Malformed JSON rows are recorded and do not stop later rows; unreadable manifest/output files are process errors. Outputs contain model text and ground truth; choose their storage and handling deliberately. The evaluator supplies no privacy or retention guarantee.

Regression checks for the integration owner (not a model evaluation):

```sh
python3 -m unittest discover -s tools/sim/tests -p test_eval_describe.py
```
