"""Model backends for the Describe inference server (cloud/API.md §2).

NeuronBackend runs a SmolVLM artefact compiled by compile.py on AWS Inferentia2 (or Trainium) through Hugging Face
optimum-neuron's NeuronModelForImageTextToText. TransformersCpuBackend runs the same Hub model with transformers on
CPU, for development and for smoke tests on machines without Neuron devices. Both use the model's own processor and
chat template and the same preprocessing, so answers are comparable.

Heavy imports (torch, transformers, optimum.neuron, PIL) happen inside load(), so the server module and its tests
import without them.
"""
from __future__ import annotations

import io
import json
import os
import time
from dataclasses import dataclass
from typing import Protocol

DEFAULT_MODEL = "HuggingFaceTB/SmolVLM-256M-Instruct"
# One 512 px tile = 64 image tokens for SmolVLM-256M; the compiled shapes assume this (research note: optimum-neuron
# pads the vision encoder to max_num_images tiles, so a single tile keeps vision compute and latency small).
LONGEST_EDGE = 512


@dataclass
class Timings:
    preprocess_ms: int
    generate_ms: int

    @property
    def total_ms(self) -> int:
        return self.preprocess_ms + self.generate_ms


class Backend(Protocol):
    device: str          # "inf2" | "trn2" | "trn1" | "cpu" (cloud/API.md §2 "device")
    model_id: str

    def load(self) -> None: ...
    def generate(self, jpeg: bytes, prompt: str, max_new_tokens: int, temperature: float) -> tuple[str, int, Timings]: ...
    def info(self) -> dict: ...


def _messages(prompt: str) -> list[dict]:
    return [{"role": "user", "content": [{"type": "image"}, {"type": "text", "text": prompt}]}]


def _open_image(jpeg: bytes):
    from PIL import Image
    img = Image.open(io.BytesIO(jpeg))
    img.load()
    return img.convert("RGB")


def _decode_new_tokens(processor, output_ids, prompt_len: int) -> tuple[str, int]:
    new = output_ids[0, prompt_len:]
    text = processor.batch_decode(new.unsqueeze(0), skip_special_tokens=True)[0].strip()
    return text, int(new.shape[-1])


class TransformersCpuBackend:
    """SmolVLM with transformers on CPU. Real model, real output; slow (seconds per answer)."""

    device = "cpu"

    def __init__(self, model_id: str = DEFAULT_MODEL, threads: int | None = None):
        self.model_id = model_id
        self.threads = threads
        self._model = None
        self._processor = None
        self._loaded_at: float | None = None
        self._load_s: float | None = None

    def load(self) -> None:
        t0 = time.monotonic()
        import torch
        from transformers import AutoModelForImageTextToText, AutoProcessor
        if self.threads:
            torch.set_num_threads(self.threads)
        self._processor = AutoProcessor.from_pretrained(self.model_id)
        self._model = AutoModelForImageTextToText.from_pretrained(self.model_id, torch_dtype=torch.float32)
        self._model.eval()
        self._load_s = time.monotonic() - t0
        self._loaded_at = time.time()

    def generate(self, jpeg: bytes, prompt: str, max_new_tokens: int, temperature: float):
        import torch
        t0 = time.monotonic()
        img = _open_image(jpeg)
        text = self._processor.apply_chat_template(_messages(prompt), add_generation_prompt=True)
        inputs = self._processor(text=text, images=[img], return_tensors="pt", size={"longest_edge": LONGEST_EDGE})
        t1 = time.monotonic()
        kwargs = dict(max_new_tokens=max_new_tokens, do_sample=temperature > 0)
        if temperature > 0:
            kwargs["temperature"] = temperature
        with torch.inference_mode():
            out = self._model.generate(**inputs, **kwargs)
        t2 = time.monotonic()
        answer, n = _decode_new_tokens(self._processor, out, inputs["input_ids"].shape[-1])
        return answer, n, Timings(int((t1 - t0) * 1000), int((t2 - t1) * 1000))

    def info(self) -> dict:
        return {"model": self.model_id, "device": self.device, "compiled": None, "neuron_cores": None,
                "load_seconds": self._load_s}


class NeuronBackend:
    """SmolVLM compiled for NeuronCores (Inferentia2 / Trainium) via optimum-neuron.

    Needs optimum-neuron from git main (image-text-to-text for SmolVLM/idefics3 is not in the 0.4.x releases) inside
    the Neuron SDK 2.31 PyTorch 2.9 venv; see setup_inf2.sh. The processor is loaded from the Hub id recorded in
    compile_meta.json because the exporter saves only the tokenizer.
    """

    def __init__(self, artefact_dir: str, device: str = "inf2"):
        self.artefact_dir = artefact_dir
        self.device = device
        self.meta = read_compile_meta(artefact_dir)
        self.model_id = self.meta["model"]
        self._model = None
        self._processor = None

    def load(self) -> None:
        from optimum.neuron import NeuronModelForImageTextToText
        from transformers import AutoProcessor
        self._processor = AutoProcessor.from_pretrained(self.model_id)
        self._model = NeuronModelForImageTextToText.from_pretrained(self.artefact_dir)

    def generate(self, jpeg: bytes, prompt: str, max_new_tokens: int, temperature: float):
        t0 = time.monotonic()
        img = _open_image(jpeg)
        text = self._processor.apply_chat_template(_messages(prompt), add_generation_prompt=True)
        inputs = self._processor(text=text, images=[img], return_tensors="pt", size={"longest_edge": LONGEST_EDGE})
        t1 = time.monotonic()
        # Greedy decoding: on-device sampling with temperature > 0 is unverified for this model (research report).
        out = self._model.generate(inputs["input_ids"], pixel_values=inputs["pixel_values"],
                                   do_sample=False, max_new_tokens=max_new_tokens)
        t2 = time.monotonic()
        answer, n = _decode_new_tokens(self._processor, out, inputs["input_ids"].shape[-1])
        return answer, n, Timings(int((t1 - t0) * 1000), int((t2 - t1) * 1000))

    def info(self) -> dict:
        return {"model": self.model_id, "device": self.device,
                "compiled": {"seconds": self.meta.get("compile_seconds"), "at": self.meta.get("compiled_at")},
                "neuron_cores": self.meta.get("tensor_parallel_size")}


def read_compile_meta(artefact_dir: str) -> dict:
    """compile_meta.json written by compile.py; raises ValueError if missing fields."""
    path = os.path.join(artefact_dir, "compile_meta.json")
    with open(path) as f:
        meta = json.load(f)
    for key in ("model", "batch_size", "sequence_length", "tensor_parallel_size", "compile_seconds", "compiled_at"):
        if key not in meta:
            raise ValueError(f"{path}: missing {key}")
    return meta
