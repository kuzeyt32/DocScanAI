# Run records

Text output of the measurement lanes, published so results can be read without a phone or
the private photo corpus. Nothing here contains image data.

| file | what it is | source lane |
|---|---|---|
| [`detector-116.md`](detector-116.md) | Detector verdict over all 116 owner photos: 0.3.13 vs 0.3.15, per-photo quad measurements, corner-model blind spots | `h38_lane.cmd` + `h42_publish_runs.py` |
| [`ship-0.3.15.md`](ship-0.3.15.md) | Build identity, certificate continuity, SHA-256, install/launch smoke for the published APK | `h41_ship.cmd` |

Regenerate everything:

```bat
tooling\h38_lane.cmd h40relax Owner110RealPathTest
python tooling\h38_gate.py --tag h40relax
tooling\h41_ship.cmd v0315
python tooling\h42_publish_runs.py
```
