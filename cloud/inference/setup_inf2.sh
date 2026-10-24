#!/usr/bin/env bash
# Idempotent setup of the Describe inference server on an inf2.xlarge.
#
# AMI: "Deep Learning AMI Neuron PyTorch 2.9 (Ubuntu 24.04)" from Neuron SDK 2.31
#   aws ssm get-parameter --name /aws/service/neuron/dlami/pytorch-2.9/ubuntu-24.04/latest/image_id
# SDK 2.31 matters: optimum-neuron main pins neuronx_distributed 0.19.28492 (SDK 2.31); AMIs from 2.32 drop NxD.
#
# Usage (as ubuntu, from a checkout of this repo):
#   INFERENCE_KEY=$(openssl rand -hex 32) ./cloud/inference/setup_inf2.sh
# Then put the same key in the describe-api SSM parameter. Security group: allow TCP 8080 only from the
# describe-api Lambda's security group; no public ingress.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
VENV=/opt/aws_neuronx_venv_pytorch_2_9
ART=/opt/determine/smolvlm256m-inf2
ENV_FILE=/etc/determine-inference.env
MODEL=HuggingFaceTB/SmolVLM-256M-Instruct

[ -d "$VENV" ] || { echo "missing $VENV: wrong AMI (need Neuron SDK 2.31 PyTorch 2.9)"; exit 1; }
ls /dev/neuron0 >/dev/null 2>&1 || { echo "no /dev/neuron0: not an Inf2/Trn instance"; exit 1; }
# shellcheck disable=SC1091
source "$VENV/bin/activate"

pip install -q -r "$HERE/requirements-neuron.txt" --extra-index-url https://pip.repos.neuron.amazonaws.com

sudo mkdir -p /opt/determine && sudo chown "$USER" /opt/determine
if [ ! -f "$ART/compile_meta.json" ]; then
  echo "compiling $MODEL (can take tens of minutes)…"
  python3 "$HERE/compile.py" --model "$MODEL" --out "$ART" --tensor-parallel-size 2
fi

if [ ! -f "$ENV_FILE" ]; then
  : "${INFERENCE_KEY:?set INFERENCE_KEY for the first run}"
  printf 'INFERENCE_KEY=%s\n' "$INFERENCE_KEY" | sudo tee "$ENV_FILE" >/dev/null
  sudo chmod 600 "$ENV_FILE"
fi

PRIVATE_IP=$(TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60'); \
  curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/local-ipv4)

sudo tee /etc/systemd/system/determine-inference.service >/dev/null <<EOF
[Unit]
Description=Determine Describe inference server (SmolVLM on Inferentia2)
After=network-online.target

[Service]
User=$USER
EnvironmentFile=$ENV_FILE
ExecStart=$VENV/bin/python3 $HERE/server.py --backend neuron --artefact $ART --device inf2 --bind $PRIVATE_IP --port 8080
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now determine-inference
echo "started; health: curl http://$PRIVATE_IP:8080/health"
