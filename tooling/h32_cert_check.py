#!/usr/bin/env python
"""Deterministic cert-continuity check for the H32 ship lane.

Reads the apksigner certs dump and a file holding the pinned SHA-256; exits 0 when the pinned
digest is present, 1 otherwise. Kept as a standalone script (not a `python -c` one-liner) because
batch does not expand variables reliably inside a `-c` command string, and the ship lane's cert
assertion must never read a MATCHING cert as a mismatch.

Usage: python tooling\\h32_cert_check.py <certs_release.txt> <expected_cert.txt>
"""
import sys


def main():
    if len(sys.argv) < 3:
        print("usage: h32_cert_check.py <certs_file> <expected_cert_file>")
        return 2
    try:
        text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
        expected = open(sys.argv[2], encoding="utf-8", errors="replace").read().strip()
    except OSError as exc:
        print("read error:", exc)
        return 2
    if not expected:
        print("empty expected cert")
        return 1
    ok = expected in text
    print("expected:", expected)
    print("present :", ok)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
