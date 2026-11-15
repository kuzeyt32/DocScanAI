# H43 - corner-model input sweep (host, real ONNX graph)

Model `m1c_detect.onnx` on 116 owner photos. `strong` = channels >= 0.3
(the production gate). 4 strong channels are needed for the model path.

| variant | photos w/ 4 strong | photos w/ >=3 | photos blind (0) | mean max peak |
|---|---|---|---|---|
| baseline | **30** | 35 | 62 | 0.396 |
| letterbox | **64** | 72 | 32 | 0.631 |
| multiscale | **17** | 26 | 77 | 0.281 |
| gray | **27** | 38 | 60 | 0.407 |
| clahe | **30** | 41 | 60 | 0.427 |
| desat | **28** | 36 | 61 | 0.391 |
| zoomout | **29** | 35 | 63 | 0.379 |

## Does paper saturation explain blindness?

* blind photos (baseline, 0 strong): **62**, mean saturation **52.7**
* photos the model nails (baseline, 4 strong): **30**, mean saturation **61.9**

If those two numbers are close, colour is NOT the cause of blindness.

## Photos a variant rescues to a full 4-channel lock (17)

| photo | sat | best variant | peaks |
|---|---|---|---|
| `20261020_195404.jpg` | 73.9 | letterbox | [0.845, 0.605, 0.87, 0.858] |
| `20261024_185245.jpg` | 62.5 | letterbox | [0.891, 0.859, 0.321, 0.687] |
| `20261024_185341.jpg` | 63.5 | letterbox | [0.911, 0.896, 0.899, 0.892] |
| `20261024_185345.jpg` | 64.8 | letterbox | [0.845, 0.845, 0.925, 0.761] |
| `20261024_185351.jpg` | 60.3 | letterbox | [0.908, 0.897, 0.909, 0.607] |
| `20261024_185359.jpg` | 63.9 | letterbox | [0.934, 0.899, 0.91, 0.846] |
| `20261024_185405.jpg` | 62.8 | letterbox | [0.67, 0.886, 0.748, 0.895] |
| `20261024_185425.jpg` | 64.1 | letterbox | [0.907, 0.801, 0.929, 0.931] |
| `20261024_185518.jpg` | 17.6 | clahe | [0.306, 0.754, 0.615, 0.689] |
| `20261024_185608.jpg` | 52.1 | letterbox | [0.81, 0.593, 0.854, 0.899] |
| `20261024_185811.jpg` | 63.9 | letterbox | [0.87, 0.877, 0.875, 0.897] |
| `20261024_190138.jpg` | 66.5 | letterbox | [0.895, 0.879, 0.724, 0.859] |
| `20261024_190149.jpg` | 60.5 | letterbox | [0.384, 0.907, 0.776, 0.755] |
| `20261024_190151.jpg` | 77.1 | letterbox | [0.891, 0.894, 0.398, 0.857] |
| `20261024_190212.jpg` | 59.0 | letterbox | [0.679, 0.834, 0.919, 0.9] |
| `20261024_190304.jpg` | 60.4 | letterbox | [0.565, 0.904, 0.619, 0.614] |
| `20261025_131249.jpg` | 61.3 | clahe | [0.758, 0.806, 0.862, 0.917] |
