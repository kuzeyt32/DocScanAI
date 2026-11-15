# H40 — ink trim sweep

`iou0` = the shipped quad vs oracle. Each column = the same quad after the ink trim
at that minimum-cut fraction. `touched` = how many quads the guard actually changed.

| cut | mean IoU (all) | mean IoU (bad) | bad improved | bad worsened | good improved | good worsened | touched |
|---|---|---|---|---|---|---|---|
| 0.12 | 0.425 | 0.291 | 1 | 1 | 14 | 3 | 54 |
| 0.18 | 0.426 | 0.291 | 1 | 1 | 14 | 3 | 54 |
| 0.25 | 0.426 | 0.291 | 1 | 1 | 14 | 3 | 54 |

baseline mean IoU: all 0.422, bad 0.291, good 0.493
