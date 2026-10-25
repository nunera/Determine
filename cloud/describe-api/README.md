# cloud/describe-api — Graviton2 Lambda

Implements `cloud/API.md` §1 and §3: `POST /v1/describe` with bearer auth, request validation (`redacted` must be
true unless the request also carries `unredacted_opt_in: true`), the fixed prompt, a 6 s inference budget, and the safety filter (≤ 2 sentences, ≤ 240 chars, no reassurance,
no guesses about people). Python 3.12 on `arm64` (Graviton2). Logs one JSON line per request: id, sizes, backend,
status, timings — never image bytes or answer text.

| File | Role |
|---|---|
| `app/handler.py` | Lambda handler (Function URL / HTTP API v2 events) |
| `app/safety.py` | §3 filter: sentence split (abbreviations, decimals), deny lists |
| `app/backends.py` | `InferenceBackend` (inf2/trn2 server, urllib) and `BedrockBackend` (Nova Lite Converse, lazy boto3) |
| `template.yaml` | SAM: arm64 function, Function URL, optional VPC, SSM + conditional Bedrock IAM, 7-day logs |
| `local_server.py` | Same handler behind a local HTTP server for phone development |

Backend selection: `DESCRIBE_BACKEND=inf2|trn2|bedrock`; `DESCRIBE_FALLBACK=bedrock` is used only if the primary is
unavailable or times out, and the response's `backend`/`silicon` say which one answered.

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
python3 -m unittest discover -s cloud/describe-api/tests        # 84 tests
DESCRIBE_API_KEY=apikey INFERENCE_URL=http://127.0.0.1:8080 INFERENCE_KEY=devkey \
  python3 cloud/describe-api/local_server.py --port 8787
```

Point the app at `http://<laptop-ip>:8787` with key `apikey` (debug builds allow http; release builds are HTTPS-only).
