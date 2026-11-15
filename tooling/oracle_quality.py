#!/usr/bin/env python3
"""
Oracle annotation validity.

Some entries in `app/src/androidTest/assets/owner110/oracle_manifest.json` cannot be scored
against: four corners collapsed onto the frame's bottom-right (a placeholder that was never
annotated), or a corner clicked twice so the quad has zero width on one side.

Against those, `IoU` is 0 by construction, `covers_oracle` is meaningless, and because
`wrong_page` needs `IoU < 0.35` and `merged` needs `area_ratio >= 1.5` (which is infinite when the
oracle area is 0), they produce **false** wrong-page/merged flags. That inflated the failure counts
in every H38/H40/H42/H43 report until this module existed.

Usage:
    from oracle_quality import reason
    if reason(item):  # unusable annotation
        ...
"""
from __future__ import annotations

import numpy as np

# A real page in this corpus never occupies less than ~10 % of the frame. 1 % is a generous floor
# that still catches every collapsed/sliver annotation without rejecting a steep perspective.
MIN_AREA_FRACTION = 0.01
# Two adjacent corners closer than this fraction of the page side means a duplicated click.
MIN_SIDE_FRACTION = 0.05


def _corners(item) -> np.ndarray:
    px = item["corners_px"]
    return np.array([px["tl"], px["tr"], px["br"], px["bl"]], dtype=np.float64)


def _area(q: np.ndarray) -> float:
    x, y = q[:, 0], q[:, 1]
    return abs(float(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1)))) / 2.0


def reason(item) -> str | None:
    """Return why this oracle entry cannot be scored, or None when it is usable."""
    if not item:
        return "no oracle entry"
    q = _corners(item)
    w = float(item.get("width") or 0)
    h = float(item.get("height") or 0)
    area = _area(q)
    side = area ** 0.5 if area > 0 else 0.0

    unique = {tuple(np.round(p, 1)) for p in q}
    if len(unique) <= 2:
        return "placeholder annotation (corners collapsed)"

    if w > 0 and h > 0 and area < MIN_AREA_FRACTION * w * h:
        return f"degenerate annotation (area {area:.0f} px = {100 * area / (w * h):.2f} % of frame)"

    if side > 0:
        gaps = [float(np.linalg.norm(q[i] - q[(i + 1) % 4])) for i in range(4)]
        if min(gaps) < MIN_SIDE_FRACTION * side:
            return (f"degenerate annotation (duplicated corner, shortest side "
                    f"{100 * min(gaps) / side:.1f} % of page side)")
    return None


def invalid_images(items) -> dict[str, str]:
    """Map image name -> reason for every unusable oracle entry."""
    out = {}
    for it in items:
        r = reason(it)
        if r:
            out[it["image"]] = r
    return out
