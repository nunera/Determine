# Describe loop — cloud contract v1 (Arch §7.3)

Optional, on-request scene description. Never in the hazard loop: the phone keeps warning while a request is in
flight, and any MID or NEAR hazard interrupts the answer.

```
phone ──HTTPS──▶ describe-api (AWS Lambda, arm64 / Graviton2) ──HTTP──▶ inference server (EC2 inf2.xlarge, Inferentia2)
                                                    └──(optional fallback)──▶ Amazon Bedrock Nova Lite (silicon not guaranteed)
                                                    └──(configured primary)─▶ OpenRouter → configured vision model
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
| `question` | string, optional | A specific question about the photo (1–300 characters of plain text; double quotes become single). It is added to the §3 prompt as data, and the answer still passes the §3 filter. Never logged |
| `locale` | string | `en` only in v1 |

Response 200:

```json
{"request_id":"…","text":"A doorway is open on your right. A person is farther ahead near the wall.",
 "sentences":["A doorway is open on your right.","A person is farther ahead near the wall."],
 "model":"HuggingFaceTB/SmolVLM-256M-Instruct","backend":"inf2","silicon":"Inferentia2",
 "timings_ms":{"inference":812,"api":43,"total":855}}
```

- `backend` ∈ `inf2` | `trn2` | `bedrock` | `openrouter` | `cpu` (dev only). `silicon` ∈ `Inferentia2` | `Trainium2` | `unspecified` | `CPU`. With `openrouter` the `model` is the model OpenRouter actually served.
- `text` is at most 2 sentences, ≤ 240 characters, after the API's safety filter (§3). The phone speaks it as
  returned; neither the API nor the phone adds a prefix.
- Errors: JSON `{"request_id":…|null,"error":"<code>","retryable":bool}` with status 400 `bad_request`,
  401 `unauthorized`, 413 `too_large`, 502 `inference_failed`, 503 `unavailable`, 504 `timeout`.
- The API stores no images and logs no image bytes or text; it logs request_id, sizes, backend and timings.
- Phone timeout: 8 s end to end. API → inference timeout: 6 s.

### 1b. Voice routing: `POST /v1/voice`

Same bearer key and error format. What the wearer said after the wake phrase goes to Jev, TypeSafe's decision model
(`typesafe/jev-1.14`, set with `OPENROUTER_ROUTER_MODEL`), through OpenRouter's Decisions API
(`POST https://openrouter.ai/api/alpha/decisions`). Jev is not a chat model: it answers one typed Choice question,
"which of these routes does the wearer want", with the chosen route and a probability for every route, in about 0.3 s
(measured from the home server). Only the transcript text is sent: no audio and no image. The phone tries its own
fixed phrases first and calls this only when none matched.

Request: `{"request_id":str,"transcript":str (1–300 chars),"locale":"en"}`

Response 200:

```json
{"request_id":"…","action":"command","command":"pauseWarnings","model":"typesafe/jev-1.14-20261014","backend":"openrouter",
 "confidence":0.99,"timings_ms":{"inference":270,"api":3,"total":273}}
```

- Routes: `whatsAhead`, `readText`, `pauseWarnings`, `resumeWarnings`, `stopSpeaking`, `askAboutScene`, `none`.
- `action` is `command` (the first five routes), `ask` (`askAboutScene`: the phone then calls `/v1/describe` with one
  frame and `question`, which is what the wearer said as cleaned plain text), or `none`. `confidence` is Jev's
  probability for the chosen route.
- "Is this path safe / clear / okay to cross?" routes to `whatsAhead`, not `askAboutScene`: a photo cannot show that
  something is safe, and the §3 filter drops any sentence that says so, so a yes-or-no question would come back empty
  or missing the hazard. The wearer gets the same description as for "what's ahead", which never says safe or clear.
- The route is acted on only when `confidence` ≥ `VOICE_MIN_CONFIDENCE` (default 0.6); otherwise `action` is `none`.
  Anything else unusable (a route that is not one of the seven, a missing or non-numeric probability, a malformed
  answer) is also `none`: the phone does nothing.
- The transcript is untrusted but cannot instruct anything: it is Jev's state, and the only possible output is one of
  the seven routes. It is never logged; only its length is. The Decisions API has no data-collection option, so the
  transcript goes to TypeSafe under OpenRouter's default policy.
- Budget 4 s; failures use the same codes (`unavailable` for a missing key, rate limit or no credits, `timeout`).

### 1c. Streamed descriptions: `POST /v1/describe/stream`

Implemented by `describe-api/local_server.py` with `DESCRIBE_BACKEND=openrouter`. The Lambda handler and its
existing JSON endpoint remain buffered; deploying the SAM template alone does not enable this streaming route.
Same bearer key, image limits, locale and privacy validation as §1. Additional request fields:

| Field | Choices | Default |
|---|---|---|
| `length` | `brief` (1 sentence / 180 characters), `standard` (2 / 360), `detailed` (5 / 1000) | `standard` |
| `style` | `direct`, `conversational`, `spatial` (image positions, not walking directions) | `direct` |

The prompt applies these preferences. Before emission, every complete sentence passes the existing reassurance
and person-attribute heuristics. The first increment therefore waits for a complete sentence, not just a token.
These heuristics are conservative checks, not safety certification. No unfiltered token is emitted first.

Response: `Content-Type: application/x-ndjson`, `Cache-Control: no-store`; each line is one JSON object:

```json
{"type":"delta","text":"A chair is on the left."}
{"type":"delta","text":" A door is ahead."}
{"type":"done","text":"A chair is on the left. A door is ahead.","request_id":"example_123"}
```

Concatenate deltas exactly (including leading spaces); `done.text` must equal that concatenation.
Failures before the first safe sentence use the ordinary JSON/status contract. A later failure sends
`{"type":"error","error":"inference_failed","retryable":true}` and closes the stream, with no `done`.
An EOF without `done` is a failure, not a complete answer. Cancelling the client closes its connection;
the server closes upstream on its next attempted write. The upstream request has a 20 s socket timeout and
checks elapsed time between events; the client has a 25 s idle / 45 s resource timeout. No automatic retries.
OpenRouter cancellation/billing depends on its selected upstream provider.

### 1d. Optional cloud speech

Both routes are implemented by the HTTP wrapper, not the Lambda handler, and use the same bearer authorization:

- `GET /v1/speech/voices` → `{"providers":[{"id":"openrouter","name":"OpenRouter","available":true,
  "reason":null,"voices":[],"models":[{"id":"elevenlabs/eleven-flash-v2.5","name":"ElevenLabs: Eleven Flash v2.5",
  "voices":[{"id":"george","name":"George"}]}]}, ...]}`. Direct providers use the top-level `voices` list.
  OpenRouter models/voices come from its live speech-model catalog, cached for five minutes. Only the
  four PCM-compatible models below are offered. An unavailable catalog disables that provider explicitly.
- `POST /v1/speech` → progressively forwarded `audio/pcm`: signed 16-bit little-endian, mono, 24 kHz.
  Example: `{"provider":"openrouter","model":"elevenlabs/eleven-flash-v2.5","voice":"george",
  "text":"A chair is on the left.","speed":1.0}`. Provider is `openrouter`, `openai` or `elevenlabs`;
  text is 1–2000 characters, speed 0.7–1.2. OpenRouter validates both model and model-specific voice against
  its catalog, including colon/dot-containing Microsoft voice IDs. Other providers retain their existing limits.

OpenRouter uses the existing `OPENROUTER_API_KEY` (or `OPENROUTER_API_KEY_PARAM`) and its `/audio/speech`
endpoint. No direct-provider account is required. Supported models:

| Model | Default voice |
|---|---|
| `elevenlabs/eleven-flash-v2.5` (default model) | `george` |
| `elevenlabs/eleven-multilingual-v2` | Choose from its live voice list |
| `microsoft/mai-voice-2.1-flash` | Choose from its live voice list |
| `microsoft/mai-voice-2.1` | Choose from its live voice list |

All four were independently probed on 25 Oct with the live key and returned
`audio/pcm;rate=24000;channels=1`. The proxy refuses mismatched rate/channel metadata rather than playing
audio at the wrong pitch/speed. Model availability and voice lists remain provider-dependent.
The iPhone applies cloud playback speed locally using `AVAudioUnitTimePitch`, bypassed at 1×; it sends
speed 1 to the server. Thus model-specific speed support cannot cause silent inconsistencies.

Optional direct connections still use `OPENAI_API_KEY` / `ELEVENLABS_API_KEY`. Missing credentials disable
the relevant provider and yield 503 if requested. Keys never go into the IPA or catalog.
Direct OpenAI uses `gpt-4o-mini-tts`; direct ElevenLabs uses `eleven_flash_v2_5`.
Provider charges and retention policies apply. OpenRouter receives the same data-collection/ZDR routing
preferences as descriptions. Only speech text leaves this route, never the camera image.
The app labels cloud voices as AI-generated. PCM is played incrementally and cancelled for urgent warnings;
warning speech always stays local. Failed reads are reported without silently switching providers.

Sources: [OpenRouter streaming](https://openrouter.ai/docs/api/reference/streaming),
[OpenRouter TTS](https://openrouter.ai/docs/guides/overview/multimodal/tts),
[OpenAI speech](https://platform.openai.com/docs/api-reference/audio/createSpeech),
[ElevenLabs streaming speech](https://elevenlabs.io/docs/api-reference/text-to-speech/stream).

### OpenRouter / OpenAI-model configuration

The scene service and browser bridge use the existing OpenRouter vision backend, not a separate direct-OpenAI
browser API or provider implementation. Its default vision model is `openai/gpt-6-luna`. Configure
`DESCRIBE_BACKEND=openrouter`, `OPENROUTER_API_KEY` (or `OPENROUTER_API_KEY_PARAM` for a server-side
SSM SecureString) and optionally `OPENROUTER_MODEL`.
The account must have credits and access to the selected vision model; model availability is a provider concern.
`OPENROUTER_REASONING` defaults to `low`; `OPENROUTER_ZDR=1` restricts routing to zero-data-retention endpoints.
`OPENROUTER_BASE_URL` is the existing server-only endpoint override (default `https://openrouter.ai/api/v1`).

The buffered JSON endpoint POSTs the §3 prompt (plus the optional question as data) and a JPEG data URL to
`/chat/completions` with the existing 400-token budget (including room for reasoning), temperature 0.2, and
`provider.data_collection=deny`. It then applies the **same**
§3 postfilter. Metadata reports `backend: "openrouter"`, the provider's served model, and
`silicon: "unspecified"`; it does not imply local inference or known hardware. Provider responses are capped at
256 KiB; HTTP/provider/malformed-response errors use the normal generic contract errors without exposing error
bodies or keys. There is no invented answer, retry loop, or new fallback backend.

Determine itself stores no frame or answer and logs neither. OpenRouter and the routed provider receive the
image and prompt; their retention/policies are separate from Determine's no-storage guarantee. The privacy
routing flags are requests to the provider, not proof of upstream deletion.
Official contracts: [OpenRouter API](https://openrouter.ai/docs/api_reference/overview) and
[provider privacy routing](https://openrouter.ai/docs/guides/routing/provider-selection).

### Browser live launcher

`npm run live` in `web/` reads private `web/.env` or exported server variables:

- `DESCRIBE_URL` **and** `DESCRIBE_KEY`: use the existing Describe service, taking precedence over local provider
  configuration. The deployed tailnet service base is `https://mansfield.tail06a975.ts.net/describe`; access
  requires the tailnet and that service's Describe bearer, not an OpenRouter key in the browser.
- Otherwise, `OPENROUTER_API_KEY` starts the same local handler on `127.0.0.1:8771`, selecting `openrouter` and no
  fallback. `OPENROUTER_MODEL`, `OPENROUTER_REASONING`, `OPENROUTER_ZDR` and `OPENROUTER_BASE_URL` apply server-side.
- Without complete external configuration or a local provider key, Describe is explicitly **OFF**; simulation
  and hardware operation remain available.

Both SIM and HITL bridges receive the same Describe service. The launcher generates a random per-launch bearer
for the local service; bearer/provider secrets travel in child environments, not argv, logs or browser storage.
Never use `VITE_` variables for keys. The browser sends only a **rendered World camera JPEG**, including in HITL,
through its bridge's same-origin `POST /api/describe`. This is not a physical-camera upload. Virtual scenes
contain no real people/faces, so the bridge truthfully uses `redacted:true`; this does not claim real-frame face
blurring or silently opt anyone into an unredacted physical upload.

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
