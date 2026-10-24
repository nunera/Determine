# cloud/inference — SmolVLM on Inferentia2

Implements `cloud/API.md` §2 (`POST /v1/generate`, `GET /health`). Files:

| File | Role |
|---|---|
| `server.py` | Stdlib HTTP server, one model, single-flight (second request waits 1 s → 503 `busy`), 503 `not_ready` while loading, `X-Determine-Key` auth, JSON logs without image bytes or text |
| `backends.py` | `NeuronBackend` (optimum-neuron `NeuronModelForImageTextToText`) and `TransformersCpuBackend` (same model on CPU) |
| `compile.py` | Static-shape Neuron compile (batch 1, seq 2048, TP 2) → artefact dir + `compile_meta.json` |
| `bench.py` | p50/p95/max, tokens/s, $ per request against a running server |
| `setup_inf2.sh` | Idempotent instance setup: deps, compile, systemd unit on the private IP |

## Neuron facts this relies on (checked 2026-10-24)

- SmolVLM image-text-to-text is only on optimum-neuron **git main** (not 0.4.x on PyPI); main pins neuronx_distributed
  0.19.28492 = Neuron SDK **2.31**, so use the "Deep Learning AMI Neuron PyTorch 2.9 (Ubuntu 24.04)" from SDK 2.31
  (`/opt/aws_neuronx_venv_pytorch_2_9`). AMIs from SDK 2.32 no longer ship NxD.
- inf2.xlarge: 1 Inferentia2 chip, 2 NeuronCores, 32 GB accelerator memory, $0.76/h on-demand.
- Images are processed at `longest_edge=512` → one tile of 64 tokens for SmolVLM-256M.
- Decoding is greedy on Neuron (on-device sampling with temperature > 0 is unverified).
- Compile time for this model is unpublished ("sometimes more than one hour" for LLMs); `compile_meta.json` records ours.

## Run

```sh
# unit tests (no model needed)
python3 -m unittest discover -s cloud/inference/tests

# CPU smoke test
uv venv /tmp/infvenv && uv pip install --python /tmp/infvenv/bin/python --index-url https://download.pytorch.org/whl/cpu torch
uv pip install --python /tmp/infvenv/bin/python -r cloud/inference/requirements-cpu.txt
INFERENCE_KEY=devkey /tmp/infvenv/bin/python cloud/inference/server.py --backend cpu
curl -s 127.0.0.1:8080/health
INFERENCE_KEY=devkey /tmp/infvenv/bin/python cloud/inference/bench.py -n 5 --hourly-usd 0

# Inferentia2 (on the instance)
INFERENCE_KEY=$(openssl rand -hex 32) ./cloud/inference/setup_inf2.sh
python3 cloud/inference/bench.py --url http://<private-ip>:8080 --key "$INFERENCE_KEY" -n 50 --hourly-usd 0.76 --out inf2.json
```

Security group: TCP 8080 only from the describe-api Lambda's security group. No public ingress.
