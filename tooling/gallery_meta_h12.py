#!/usr/bin/env python3
"""Insert the 0.3.1 final-verification banner into gallery/index.html.

The gallery is generator output (BenchmarkGallery.java writes the H3A section and
CreateGallery.java the corpus section) and its historical sections still carry
older run numbers. Regenerating 700 MB of PNGs is explicitly out of scope, so
this script only inserts a marked banner after <body> and leaves everything
else byte-for-byte untouched.

Idempotent: running it twice does not duplicate the banner.
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GALLERY = os.path.join(ROOT, "gallery", "index.html")

BEGIN = "<!-- H12_FINAL:031:BEGIN -->"
END = "<!-- H12_FINAL:031:END -->"

BANNER = """<!-- H12_FINAL:031:BEGIN -->
<h1 id="final-031">DocScanAI 0.3.1 &mdash; FINAL release verification</h1>
<div class="note">
<b>Version:</b> versionCode 4 / versionName 0.3.1 &nbsp;&middot;&nbsp;
<b>Release APK:</b> 125,495,778 bytes &nbsp;&middot;&nbsp;
<b>Cert SHA-256:</b> <code>7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc</code>
(same as 0.3.0) &nbsp;&middot;&nbsp; <b>16 KB:</b> 16/16 libs, debug and release.
</div>
<p class="summary">Benchmark 19/20 (95%) &middot; synthetic 44/51 detected &middot; negatives false positives 3/5
&middot; mean corner error 3.44% of diagonal (40 GT images) &middot; INSIDE 24 / OUTSIDE 0 / MIXED 16
&middot; distorted_suspect 0 &middot; AI-ON/AI-OFF sharpness 14,725.6 / 13,172.4 = 1.12&times;
&middot; detection 59.2 ms &middot; dewarp 175.5 ms &middot; 1,299 ms per page.</p>
<div class="note">
<b>Full per-image results, worst-10 diagnoses, known gaps and the S25 FE checklist:</b>
<a href="../docs/RESULTS.md">docs/RESULTS.md</a><br>
<b>Source artifacts:</b> <code>tooling/out/h11/corpus/corpus_out/corpus_report.json</code> and
<code>tooling/out/h11/corpus/corpus_out/ai_off/corpus_report_ai_off.json</code>.<br>
<b>All measurements are x86_64-emulator-only.</b>
</div>
<div class="note">
The sections below are <b>historical run snapshots</b> (their own H3a / H7 / H11 lanes) kept for
visual before/after inspection. Their headers and metric rows do <b>not</b> describe 0.3.1;
use the banner above and <a href="../docs/RESULTS.md">docs/RESULTS.md</a> for the final numbers.
</div>
<!-- H12_FINAL:031:END -->
"""


def main():
    with open(GALLERY, "rb") as fh:
        raw = fh.read()
    if BEGIN.encode("ascii") in raw:
        print("banner already present, nothing to do")
        return 0
    anchor = b"<body>"
    idx = raw.find(anchor)
    if idx < 0:
        print("ERROR: <body> not found")
        return 1
    cut = idx + len(anchor)
    patched = raw[:cut] + b"\n" + BANNER.encode("utf-8") + raw[cut:]
    with open(GALLERY, "wb") as fh:
        fh.write(patched)
    print("banner inserted after <body>; file grew by %d bytes"
          % (len(patched) - len(raw)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
