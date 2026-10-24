#!/usr/bin/env python3
"""Compile SmolVLM for NeuronCores with optimum-neuron and record compile_meta.json.

Run inside the Neuron SDK 2.31 PyTorch 2.9 venv with optimum-neuron from git main (see setup_inf2.sh):

  python3 compile.py --out smolvlm256m-inf2                       # on the inf2.xlarge itself
  python3 compile.py --out smolvlm256m-inf2 --instance-type inf2   # cross-compile on a CPU-only box

Shapes are static. Defaults follow optimum-neuron's own CI fixture for this model (sequence_length 2048,
tensor_parallel_size = NeuronCores per device, 2 on inf2) with batch 1, because the Describe loop serves one
request at a time. The prompt plus one 512 px tile (64 image tokens) plus 128 new tokens fits easily.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backends import DEFAULT_MODEL  # noqa: E402


def versions() -> dict:
    out = {}
    for mod in ("optimum.neuron", "torch", "torch_neuronx", "neuronx_distributed", "transformers"):
        try:
            m = __import__(mod, fromlist=["__version__"])
            out[mod] = getattr(m, "__version__", "unknown")
        except ImportError:
            out[mod] = None
    return out


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--model", default=DEFAULT_MODEL)
    p.add_argument("--out", required=True)
    p.add_argument("--batch-size", type=int, default=1)
    p.add_argument("--sequence-length", type=int, default=2048)
    p.add_argument("--tensor-parallel-size", type=int, default=2, help="NeuronCores to shard over (inf2.xlarge: 2)")
    p.add_argument("--instance-type", choices=["inf2", "trn1", "trn2"],
                   help="cross-compile target when not running on that instance")
    args = p.parse_args(argv)

    from optimum.neuron import NeuronModelForImageTextToText

    cfg_kwargs = dict(batch_size=args.batch_size, sequence_length=args.sequence_length,
                      tensor_parallel_size=args.tensor_parallel_size)
    if args.instance_type:
        cfg_kwargs["instance_type"] = args.instance_type
    t0 = time.monotonic()
    cfg = NeuronModelForImageTextToText.get_neuron_config(args.model, **cfg_kwargs)
    model = NeuronModelForImageTextToText.export(args.model, neuron_config=cfg, load_weights=False)
    model.save_pretrained(args.out)
    seconds = round(time.monotonic() - t0, 1)

    meta = {
        "model": args.model,
        "batch_size": args.batch_size,
        "sequence_length": args.sequence_length,
        "tensor_parallel_size": args.tensor_parallel_size,
        "instance_type": args.instance_type or "native",
        "image_longest_edge": 512,
        "compile_seconds": seconds,
        "compiled_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "versions": versions(),
    }
    with open(os.path.join(args.out, "compile_meta.json"), "w") as f:
        json.dump(meta, f, indent=1)
    print(json.dumps({"event": "compiled", "out": args.out, "seconds": seconds}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
