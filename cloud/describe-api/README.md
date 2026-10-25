# cloud/describe-api — Graviton2 Lambda

Implements `cloud/API.md` §1 and §3: `POST /v1/describe` with bearer auth, request validation (`redacted` must be
true unless the request also carries `unredacted_opt_in: true`), the fixed prompt, a 6 s inference budget, and the safety filter (≤ 2 sentences, ≤ 240 chars, no reassurance,
no guesses about people). Python 3.12 on `arm64` (Graviton2). Logs one JSON line per request: id, sizes, backend,
status, timings — never image bytes or answer text.

| File | Role |
|---|---|
| `app/handler.py` | Lambda handler (Function URL / HTTP API v2 events) |
| `app/safety.py` | §3 filter: sentence split (abbreviations, decimals), deny lists |
| `app/backends.py`, `app/openrouter.py` | Existing inf2/trn2, Nova Lite and OpenRouter scene adapters; the handler owns safety |
| `template.yaml` | SAM: arm64 function, Function URL, optional VPC, SSM + conditional Bedrock IAM, 7-day logs |
| `local_server.py` | HTTP wrapper: legacy JSON routes plus streaming descriptions and speech |
| `app/streaming.py` | OpenRouter SSE ingestion, preference validation, sentence gating and NDJSON |
| `app/speech.py` | Authenticated OpenRouter/direct-provider voice catalogs and streamed PCM proxy |

Backend selection: `DESCRIBE_BACKEND=inf2|trn2|bedrock|openrouter`; `DESCRIBE_FALLBACK=bedrock` is used only if the
primary is unavailable or times out, and the response's `backend`/`silicon` say which one answered.
OpenRouter reuses the current Determine scene provider (`OPENROUTER_API_KEY` or `OPENROUTER_API_KEY_PARAM`,
`OPENROUTER_MODEL`, default `openai/gpt-6-luna`). It is not a direct OpenAI endpoint or a browser SDK.
See `cloud/API.md` for provider privacy, optional reasoning/ZDR settings and live-launcher configuration.

## Deploy

```sh
aws ssm put-parameter --name /determine/describe/api-key       --type SecureString --value "$(openssl rand -hex 32)"
aws ssm put-parameter --name /determine/describe/inference-key --type SecureString --value "$INFERENCE_KEY"
cd cloud/describe-api
sam build && sam deploy --guided \
  --parameter-overrides InferenceUrl=http://<inf2-private-ip>:8080 SubnetIds=<subnet-a>,<subnet-b> SecurityGroupId=<sg>
```

The output `DescribeUrl` plus the api-key value go into the app (Settings → Describe).
`sam validate --lint -t template.yaml` passes.

## Local

```sh
python3 -m unittest discover -s cloud/describe-api/tests
DESCRIBE_API_KEY=apikey INFERENCE_URL=http://127.0.0.1:8080 INFERENCE_KEY=devkey \
  python3 cloud/describe-api/local_server.py --port 8787
```

Point the app at `http://<laptop-ip>:8787` with key `apikey`; plain HTTP is allowed only for local-network addresses.

For the browser professional workspace, prefer the already-running Describe service: set private
`DESCRIBE_URL=https://mansfield.tail06a975.ts.net/describe` and `DESCRIBE_KEY` in `web/.env`, with tailnet access,
then run `npm run live` from `web/`. Both SIM and HITL bridges use the same service. No key goes in browser
storage, a `VITE_` variable, a URL or command arguments.

Alternatively, set `OPENROUTER_API_KEY` server-side and optionally `OPENROUTER_MODEL`: the launcher starts
`local_server.py --host 127.0.0.1 --port 8771` with a private per-launch bearer, `DESCRIBE_BACKEND=openrouter` and
`DESCRIBE_FALLBACK=none`. It does not spawn the Describe server without a provider key. With neither complete
external service settings nor a local provider key, Describe stays explicitly off and the live runtime works.
Browser uploads are rendered World frames only, even in HITL; this does not upload physical camera frames.

## Streaming deployment on Mansfield

The phone-facing base URL is `https://mansfield.tail06a975.ts.net/describe` (tailnet access).
`determine-describe-api.service` runs the HTTP wrapper on `127.0.0.1:8792`; the existing HTTPS proxy preserves
the `/describe` prefix. See `cloud/API.md` §1c–1d for streaming contracts. The Lambda/SAM entry point remains
buffered and does not serve the streaming or speech routes.

The OpenRouter speech release lives at `/var/lib/determine-describe/releases/20261024-openrouter`;
`/var/lib/determine-describe/current` selects it. A persistent drop-in at
`/usr/local/lib/systemd/system/determine-describe-api.service.d/20-streaming.conf` replaces only `ExecStart`;
the original NixOS unit's sandbox, user, port, environment and restart policy remain in force.
`/var/lib/determine-describe/python` is an indirect Nix GC root for the service interpreter.
This administrative drop-in is outside the NixOS source configuration and should be accounted for when
changing that unit declaratively. Removing this drop-in and reloading/restarting systemd restores the
original Nix-store launch path.

Credentials stay in `/etc/determine/describe.env`. The existing `OPENROUTER_API_KEY` now supplies both
description generation and speech; no separate account/key is needed for OpenRouter's routed speech models.
The catalog offers four live-verified mono 24 kHz PCM models: ElevenLabs Flash/Multilingual and
Microsoft MAI Voice/Flash. Voice lists come from OpenRouter and refresh server-side every five minutes.
The speech proxy validates the chosen model/voice and audio format before forwarding bytes.

Direct providers remain optional: add `OPENAI_API_KEY` and/or `ELEVENLABS_API_KEY`, restart the service,
then refresh voices in the app. No direct-provider keys were configured during verification.
Never put provider keys in the app. OpenRouter usage is billed to the existing OpenRouter account.

Verification: 121 Python tests passed; all four routed models returned compatible PCM in live probes.
The staged HTTP proxy streamed ElevenLabs Flash and Microsoft MAI Voice Flash. The deployed HTTPS
catalog and OpenRouter speech endpoint were exercised, and the authenticated sideload download matched
the local IPA's SHA-256. Native arm64 IPA packaging passed, including five Siri intents/shortcuts.
Actual iPhone listening remains a device-side check.
