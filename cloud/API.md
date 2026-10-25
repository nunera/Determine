# Describe loop — cloud contract v1 (Arch §7.3)

Optional, on-request scene description. Never in the hazard loop: the phone keeps warning while a request is in
flight, and any MID or NEAR hazard interrupts the answer.

```
phone ──HTTPS──▶ describe-api (AWS Lambda, arm64 / Graviton2) ──HTTP──▶ inference server (EC2 inf2.xlarge, Inferentia2)
                                                    └──(optional fallback)──▶ Amazon Bedrock Nova Lite (silicon not guaranteed)
```

## 1. Phone → describe-api

`POST /v1/describe` · `Authorization: Bearer <DESCRIBE_API_KEY>` · `Content-Type: application/json`

Request:

| Field | Type | Rule |
|---|---|---|
| `request_id` | string | 8–64 chars `[A-Za-z0-9_-]`, chosen by the phone |
| `image_jpeg_b64` | string | base64 JPEG, decoded size ≤ 512 KiB, must start `FFD8` |
| `width`, `height` | int | 16–2048 |
| `redacted` | bool | `true`: the phone blurred faces before upload. `false` is accepted only together with `unredacted_opt_in: true`, otherwise 400 |
| `unredacted_opt_in` | bool, optional | `true` only when the wearer has explicitly opted in to uploading frames with faces unblurred (default off in the app). Ignored when `redacted` is `true` |
| `locale` | string | `en` only in v1 |

Response 200:

```json
{"request_id":"…","text":"A doorway is open on your right. A person is farther ahead near the wall.",
 "sentences":["A doorway is open on your right.","A person is farther ahead near the wall."],
 "model":"HuggingFaceTB/SmolVLM-256M-Instruct","backend":"inf2","silicon":"Inferentia2",
 "timings_ms":{"inference":812,"api":43,"total":855}}
```

- `backend` ∈ `inf2` | `trn2` | `bedrock` | `cpu` (dev only). `silicon` ∈ `Inferentia2` | `Trainium2` | `unspecified` | `CPU`.
- `text` is at most 2 sentences, ≤ 240 characters, after the API's safety filter (§3). The phone speaks it as
  returned; neither the API nor the phone adds a prefix.
- Errors: JSON `{"request_id":…|null,"error":"<code>","retryable":bool}` with status 400 `bad_request`,
  401 `unauthorized`, 413 `too_large`, 502 `inference_failed`, 503 `unavailable`, 504 `timeout`.
- The API stores no images and logs no image bytes or text; it logs request_id, sizes, backend and timings.
- Phone timeout: 8 s end to end. API → inference timeout: 6 s.

## 2. describe-api → inference server

`POST /v1/generate` · `X-Determine-Key: <INFERENCE_KEY>` · JSON

Request: `{"request_id":str,"image_jpeg_b64":str,"prompt":str,"max_new_tokens":int (1–128),"temperature":float (0–1)}`

Response 200: `{"request_id":str,"text":str,"model":str,"device":"inf2"|"trn2"|"cpu","tokens":int,"latency_ms":{"preprocess":int,"generate":int,"total":int}}`

Errors: `{"error":str,"retryable":bool}` with 400 / 401 / 503 (`not_ready`, model still loading) / 500.

`GET /health` → `{"ready":bool,"model":str,"device":str,"compiled":{"seconds":float,"at":"ISO-8601"}|null,"neuron_cores":int|null}`

## 3. Prompt and safety filter (owned by describe-api)

Prompt (Arch §7.3: "two sentences, surroundings first, no guesses about people"):

> You are describing a photo taken from a blind pedestrian's eye level, facing forward. In at most two short
> sentences, describe the surroundings ahead: walkable path, doors, steps, obstacles and where they are (left,
> ahead, right). Mention people only as "a person" or "people" and where they are. Do not guess anyone's identity,
> age, gender, race, emotion or intent. Never say the path is clear, safe or empty.

Filter applied to model output, in order:
1. Keep the first 2 sentences; trim to 240 characters at a word boundary.
2. Drop any sentence asserting safety or absence (`clear`, `safe`, `nothing ahead`, `no obstacles`, `empty path`,
   `all clear`, `free of obstacles`) — Invariant 1: unknown is never clear.
3. Drop any sentence containing a person-attribute guess (gender, age, race/ethnicity, emotion, identity terms).
4. If nothing is left, return 502 `inference_failed` (retryable false); the phone says description failed.
