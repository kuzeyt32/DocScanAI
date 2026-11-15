# H43 - corner model A/B: LCNet100 (shipped) vs FastViT-SA24 (upgrade)

Same 116 owner photos, same production input (squash 256, RGB/255),
same gate (4 channels >= 0.3).

| model | photos w/ 4 strong | >=3 strong | blind (0) | mean max peak | median ms |
|---|---|---|---|---|---|
| **lcnet100** | **10** | 15 | 87 | 0.214 | 37 |
| **fastvit_sa24** | **8** | 12 | 101 | 0.118 | 58 |

* FastViT gives a full lock where LCNet100 does not: **6 photos**
* FastViT LOSES a full lock LCNet100 had: **8 photos**

| rescued photo | lcnet peaks | fastvit peaks |
|---|---|---|
| `20261024_185351.jpg` | [0.001, 0.001, 0.097, 0.007] | [0.9, 0.904, 0.886, 0.849] |
| `20261024_185353.jpg` | [0.865, 0.594, 0.017, 0.182] | [0.913, 0.86, 0.922, 0.891] |
| `20261024_185355.jpg` | [0.404, 0.04, 0.007, 0.013] | [0.847, 0.831, 0.607, 0.841] |
| `20261024_185450.jpg` | [0.001, 0.004, 0.01, 0.002] | [0.399, 0.358, 0.736, 0.824] |
| `20261024_185453.jpg` | [0.002, 0.001, 0.001, 0.001] | [0.814, 0.813, 0.801, 0.802] |
| `20261024_185456.jpg` | [0.001, 0.001, 0.002, 0.002] | [0.906, 0.916, 0.929, 0.879] |
