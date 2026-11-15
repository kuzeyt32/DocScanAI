#!/usr/bin/env python
"""SUBTASK H29 - record the I/O contract of every detector candidate BEFORE any integration.

The subtask is explicit that licence, size and shapes are recorded before integration code is
written. This script is that record: it prints, for each graph, the file size, SHA-256, the exact
input tensor(s) the runtime will bind (name/shape/type) and the output tensor(s). Nothing here
touches the app; it only reads the artifacts.
"""
from __future__ import annotations

import hashlib
import os
import sys

import onnxruntime as ort

CANDIDATES = [
    ("LCNet100 (shipped baseline)", os.path.join("..", "dsmodels", "m1c_detect.onnx")),
    ("FastViT-SA24 DocAligner", os.path.join("tooling", "out", "h29", "candidates", "fastvit_sa24.onnx")),
    ("M-LSD 320", os.path.join("tooling", "out", "h29", "candidates", "mlsd",
                               "saved_model_320x320_large")),
    ("M-LSD 512", os.path.join("tooling", "out", "h29", "candidates", "mlsd",
                               "saved_model_512x512_large")),
]


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def describe(label: str, path: str) -> None:
    if not os.path.exists(path):
        print(f"== {label}: MISSING ({path})")
        return
    if os.path.isdir(path):
        inner = [f for f in os.listdir(path) if f.endswith(".onnx")]
        if not inner:
            print(f"== {label}: no .onnx inside {path}")
            return
        path = os.path.join(path, inner[0])
    size = os.path.getsize(path)
    print(f"== {label}")
    print(f"   file : {path}")
    print(f"   bytes: {size} ({size / 1e6:.2f} MB)")
    print(f"   sha256: {sha256_of(path)}")
    try:
        sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    except Exception as exc:  # noqa: BLE001 - report, do not crash the survey
        print(f"   LOAD FAILED: {type(exc).__name__}: {exc}")
        return
    for i in sess.get_inputs():
        print(f"   IN   : {i.name} {i.shape} {i.type}")
    for o in sess.get_outputs():
        print(f"   OUT  : {o.name} {o.shape} {o.type}")
    print()


def main() -> int:
    print(f"onnxruntime {ort.__version__}")
    print()
    for label, path in CANDIDATES:
        describe(label, path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
