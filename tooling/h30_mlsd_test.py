#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H30 — unit tests for the numpy M-LSD port ([`h30_mlsd.py`](h30_mlsd.py)).

The point of these tests is that the expected answer is known **by
construction**, not "it produced something":

  * a rectangle whose four sides are written into the junction/displacement
    maps one per side -> exactly four merged segments and exactly one square,
    with the square's corners at the rectangle's corners;
  * a single diagonal -> one segment, zero squares;
  * a T-junction -> three segments, zero squares (a T does not close);
  * empty maps -> empty results, no exception;
  * a noisy map -> bounded, finite, deterministic output and no exception.

Run:
  python tooling\\h30_mlsd_test.py            (unittest, exit code 0 on pass)
  python tooling\\h30_mlsd_test.py --verbose
"""

import os
import sys
import time
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import h30_mlsd as M  # noqa: E402


# ==========================================================================================
# Map builders — every synthetic case is expressed in *map* space, half the model edge
# ==========================================================================================

MAP_EDGE = 256  # 512 model input -> 256x256 maps


def maps_from_segments(segments, map_edge=MAP_EDGE, score=0.9):
    """Write one junction per segment (at its midpoint) into M-LSD's three maps.

    A junction is `(y, x)`; the displacement map holds *offsets* from that cell
    to the two endpoints, which is exactly what `decode_junctions` reads back.
    """
    vmap = np.zeros((map_edge, map_edge, 4), dtype=np.float32)
    junctions = np.zeros((M_ACC_JUNCTIONS, 2), dtype=np.int32)
    scores = np.zeros((M_ACC_JUNCTIONS,), dtype=np.float32)
    for i, (x1, y1, x2, y2) in enumerate(segments):
        if i >= M_ACC_JUNCTIONS:
            break
        ix = int(round((x1 + x2) / 2.0))
        iy = int(round((y1 + y2) / 2.0))
        ix = max(1, min(map_edge - 2, ix))
        iy = max(1, min(map_edge - 2, iy))
        vmap[iy, ix, :] = [x1 - ix, y1 - iy, x2 - ix, y2 - iy]
        junctions[i] = [iy, ix]  # model order: (y, x)
        scores[i] = score
    return junctions, scores, vmap


M_ACC_JUNCTIONS = 200  # the graph always emits its top 200 junctions


def run_pipeline(junctions, scores, vmap, topk=None):
    """decode -> merge -> enumerate, the three steps the Kotlin port performs."""
    junc, raw = M.decode_junctions(junctions, scores, vmap)
    merged, theta = M.merge_segments(raw, len(junctions) if topk is None else topk)
    found = M.find_squares(merged)
    return junc, raw, merged, theta, found


# ==========================================================================================
# 1) Resize / input tensor
# ==========================================================================================

class TestInputPreparation(unittest.TestCase):

    def test_area_resize_matches_block_mean(self):
        src = np.arange(16, dtype=np.float32).reshape(4, 4)
        out = M.resize_area(src, (2, 2))
        expected = np.asarray([
            [(0 + 1 + 4 + 5) / 4.0, (2 + 3 + 6 + 7) / 4.0],
            [(8 + 9 + 12 + 13) / 4.0, (10 + 11 + 14 + 15) / 4.0],
        ], dtype=np.float32)
        np.testing.assert_allclose(out, expected, rtol=1e-6, atol=1e-6)

    def test_area_resize_identity(self):
        src = np.random.RandomState(7).rand(13, 9, 3).astype(np.float32)
        out = M.resize_area(src, (9, 13))
        np.testing.assert_allclose(out, src, rtol=0, atol=0)

    def test_area_resize_preserves_mean_of_flat_field(self):
        src = np.full((37, 53, 3), 127.0, dtype=np.float32)
        out = M.resize_area(src, (32, 32))
        self.assertEqual(out.shape, (32, 32, 3))
        np.testing.assert_allclose(out, 127.0, rtol=1e-5, atol=1e-4)

    def test_build_input_nhwc_alpha_one(self):
        img = np.zeros((720, 1280, 3), dtype=np.uint8)
        batch = M.build_input(img, (320, 320))
        self.assertEqual(batch.shape, (1, 320, 320, 4))
        self.assertEqual(batch.dtype, np.float32)
        np.testing.assert_allclose(batch[..., :3], 0.0, atol=1e-6)
        np.testing.assert_allclose(batch[..., 3], 1.0, atol=0)


# ==========================================================================================
# 2) Frame mapping — a segment has TWO endpoints and both must be scaled
# ==========================================================================================

class TestFrameMapping(unittest.TestCase):
    """`map_to_frame` must scale **both** endpoints of a 4-column segment.

    This is a regression test for a real defect found by the H30 calibration:
    the first version scaled column 0 by `sx` and column 1 by `sy` only — i.e.
    the first endpoint — and left `(x2, y2)` in map space. Every straight line
    then became a diagonal, the merge rule found no gutter, and M-LSD looked
    useless for a reason that had nothing to do with M-LSD.
    """

    def test_segment_endpoints_both_scaled(self):
        segs = np.asarray([[10.0, 20.0, 30.0, 40.0]])
        out = M.map_to_frame(segs, (720, 1280), (320, 320))
        # 320 input -> 160x160 maps, 1280x720 frame: x * 8.0, y * 4.5
        np.testing.assert_allclose(out, [[80.0, 90.0, 240.0, 180.0]])

    def test_point_array_scaled(self):
        pts = np.asarray([[10.0, 20.0], [30.0, 40.0]])
        out = M.map_to_frame(pts, (720, 1280), (320, 320))
        np.testing.assert_allclose(out, [[80.0, 90.0], [240.0, 180.0]])

    def test_square_array_scaled(self):
        sq = np.asarray([[[10.0, 20.0], [30.0, 20.0], [30.0, 40.0], [10.0, 40.0]]])
        out = M.map_to_frame(sq, (720, 1280), (320, 320))
        np.testing.assert_allclose(out[0, 2], [240.0, 180.0])

    def test_horizontal_segment_stays_horizontal(self):
        """The invariant the defect broke: a horizontal line must stay horizontal."""
        segs = np.asarray([[10.0, 40.0, 120.0, 40.0]])
        out = M.map_to_frame(segs, (4032, 3024), (512, 512))
        self.assertAlmostEqual(out[0, 1], out[0, 3], places=9)


# ==========================================================================================
# 3) Rectangle — the case whose answer is known exactly
# ==========================================================================================

RECT = [(30.0, 60.0, 230.0, 60.0),    # top
        (230.0, 60.0, 230.0, 160.0),  # right
        (230.0, 160.0, 30.0, 160.0),  # bottom
        (30.0, 160.0, 30.0, 60.0)]    # left

RECT_CORNERS = np.asarray([[30.0, 60.0], [230.0, 60.0], [230.0, 160.0], [30.0, 160.0]])


class TestRectangle(unittest.TestCase):

    def setUp(self):
        self.junctions, self.scores, self.vmap = maps_from_segments(RECT)

    def test_decodes_four_segments(self):
        _, raw = M.decode_junctions(self.junctions, self.scores, self.vmap)
        self.assertEqual(raw.shape, (4, 4))
        # Endpoints come back exactly (displacements are exact by construction).
        got = sorted(map(tuple, np.round(raw, 3).tolist()))
        want = sorted(map(tuple, RECT))
        self.assertEqual(got, want)

    def test_merges_to_four_distinct_lines(self):
        _, raw, merged, theta, _ = run_pipeline(self.junctions, self.scores, self.vmap)
        self.assertEqual(merged.shape[0], 4, "one merged line per rectangle side")
        self.assertEqual(theta.shape[0], 4)
        # Two sides are 200 long (top/bottom) and two are 100 (left/right).
        lengths = sorted(np.abs(merged[:, 2] - merged[:, 0]) + np.abs(merged[:, 3] - merged[:, 1]))
        np.testing.assert_allclose(lengths, [100.0, 100.0, 200.0, 200.0], atol=1e-6)

    def test_yields_exactly_one_square_at_the_rectangle_corners(self):
        _, _, merged, _, found = run_pipeline(self.junctions, self.scores, self.vmap)
        self.assertFalse(found["truncated"])
        self.assertEqual(found["squares"].shape[0], 1,
                         "a closed rectangle must give exactly one square, got %r"
                         % (found["squares"],))
        got = found["squares"][0]
        # NAVER's order is TL, TR, BR, BL; the fixture is axis-aligned so the
        # only freedom is a rotation, which is what the scorer handles anyway.
        best = None
        for shift in range(4):
            cand = np.roll(got, -shift, axis=0)
            err = float(np.max(np.abs(cand - RECT_CORNERS)))
            if best is None or err < best:
                best = err
        self.assertLessEqual(best, 1.5, "square corners off by %r px: %r" % (best, got))
        self.assertAlmostEqual(M.polygon_area(got), 200.0 * 100.0, delta=2.0)

    def test_square_score_is_finite_and_top_ranked(self):
        _, _, _, _, found = run_pipeline(self.junctions, self.scores, self.vmap)
        self.assertTrue(np.all(np.isfinite(found["scores"])))
        self.assertGreater(float(found["scores"][0]), 0.0)

    def test_deterministic(self):
        a = run_pipeline(self.junctions, self.scores, self.vmap)[4]["squares"]
        b = run_pipeline(self.junctions, self.scores, self.vmap)[4]["squares"]
        np.testing.assert_array_equal(a, b)


# ==========================================================================================
# 3) Diagonal — one line, no square
# ==========================================================================================

class TestDiagonal(unittest.TestCase):

    def test_single_diagonal(self):
        junctions, scores, vmap = maps_from_segments([(40.0, 40.0, 220.0, 200.0)])
        _, raw, merged, _, found = run_pipeline(junctions, scores, vmap)
        self.assertEqual(raw.shape[0], 1)
        self.assertEqual(merged.shape[0], 1, "one diagonal collapses to one line")
        self.assertEqual(found["squares"].shape[0], 0, "one line cannot close a square")
        x1, y1, x2, y2 = merged[0]
        self.assertAlmostEqual(abs(x2 - x1), 180.0, places=6)
        self.assertAlmostEqual(abs(y2 - y1), 160.0, places=6)


# ==========================================================================================
# 4) T-junction — three lines, still no square
# ==========================================================================================

class TestTJunction(unittest.TestCase):

    def test_t_junction_does_not_close(self):
        segs = [(30.0, 60.0, 230.0, 60.0),   # horizontal
                (130.0, 60.0, 130.0, 200.0),  # vertical down from its middle
                (60.0, 60.0, 60.0, 140.0)]    # second T
        junctions, scores, vmap = maps_from_segments(segs)
        _, _, merged, _, found = run_pipeline(junctions, scores, vmap)
        self.assertEqual(merged.shape[0], 3)
        self.assertEqual(found["squares"].shape[0], 0,
                         "a T-junction has no fourth side, so no square may be emitted")


# ==========================================================================================
# 5) Empty — no junctions, no displacement
# ==========================================================================================

class TestEmpty(unittest.TestCase):

    def test_zero_maps(self):
        vmap = np.zeros((MAP_EDGE, MAP_EDGE, 4), dtype=np.float32)
        junctions = np.zeros((M_ACC_JUNCTIONS, 2), dtype=np.int32)
        scores = np.zeros((M_ACC_JUNCTIONS,), dtype=np.float32)
        junc, raw, merged, theta, found = run_pipeline(junctions, scores, vmap)
        self.assertEqual(junc.shape, (0, 2))
        self.assertEqual(raw.shape, (0, 4))
        self.assertEqual(merged.shape, (0, 4))
        self.assertEqual(theta.shape, (0,))
        self.assertEqual(found["squares"].shape, (0, 4, 2))
        self.assertEqual(found["connect"].shape, (0, 4))

    def test_empty_arrays(self):
        junc, raw = M.decode_junctions(np.zeros((0, 2)), np.zeros((0,)), np.zeros((0, 0, 4)))
        self.assertEqual(junc.shape, (0, 2))
        self.assertEqual(raw.shape, (0, 4))
        found = M.find_squares(np.zeros((0, 4)))
        self.assertEqual(found["squares"].shape, (0, 4, 2))

    def test_short_displacement_is_not_a_line(self):
        """A junction whose displacements are shorter than `dist_thr` is dropped."""
        junctions, scores, vmap = maps_from_segments([(100.0, 100.0, 112.0, 100.0)])
        _, raw = M.decode_junctions(junctions, scores, vmap)
        self.assertEqual(raw.shape, (0, 4))

    def test_low_score_is_not_a_line(self):
        junctions, scores, vmap = maps_from_segments([(30.0, 60.0, 230.0, 60.0)], score=0.01)
        _, raw = M.decode_junctions(junctions, scores, vmap, score_thr=0.06)
        self.assertEqual(raw.shape, (0, 4))


# ==========================================================================================
# 6) Noisy maps — must stay bounded, finite, deterministic, and not throw
# ==========================================================================================

class TestNoise(unittest.TestCase):

    def _noisy(self, seed, n_junctions=200, scale=40.0):
        rng = np.random.RandomState(seed)
        vmap = rng.uniform(-scale, scale, size=(MAP_EDGE, MAP_EDGE, 4)).astype(np.float32)
        pts = rng.randint(2, MAP_EDGE - 2, size=(n_junctions, 2)).astype(np.int32)
        scores = rng.uniform(0.0, 1.0, size=(n_junctions,)).astype(np.float32)
        return pts, scores, vmap

    def test_noise_does_not_raise_and_is_bounded(self):
        for seed in (1, 2, 3, 4, 5):
            junctions, scores, vmap = self._noisy(seed)
            t0 = time.time()
            _, raw, merged, theta, found = run_pipeline(junctions, scores, vmap)
            elapsed = time.time() - t0
            self.assertLess(elapsed, 10.0, "noisy map took %.2fs (seed %d)" % (elapsed, seed))
            self.assertLessEqual(merged.shape[0], 200, "merge cannot exceed the junction count")
            self.assertEqual(found["squares"].shape[1:], (4, 2))
            if found["squares"].size:
                self.assertTrue(np.all(np.isfinite(found["squares"])))
                self.assertTrue(np.all(np.isfinite(found["scores"])))
            self.assertFalse(found["truncated"], "candidate cap fired on random noise (seed %d)" % seed)

    def test_noise_is_deterministic(self):
        junctions, scores, vmap = self._noisy(11, n_junctions=60)
        a = run_pipeline(junctions, scores, vmap)[4]["squares"]
        b = run_pipeline(junctions, scores, vmap)[4]["squares"]
        np.testing.assert_array_equal(a, b)

    def test_planted_rectangle_survives_short_clutter(self):
        """A page edge voted by two junctions survives 25 short clutter segments.

        The rectangle's sides are each written twice (a real page edge supports
        several junctions), the clutter segments are deliberately short, which
        is what text/layout lines look like at this scale.
        """
        sides = []
        for (x1, y1, x2, y2) in RECT:
            mx = (x1 + x2) / 2.0
            my = (y1 + y2) / 2.0
            sides.append((x1, y1, mx, my))
            sides.append((mx, my, x2, y2))
        junctions, scores, vmap = maps_from_segments(sides)
        rng = np.random.RandomState(99)
        for i in range(len(sides), len(sides) + 25):
            y = int(rng.randint(2, MAP_EDGE - 2))
            x = int(rng.randint(2, MAP_EDGE - 2))
            sx, sy = rng.uniform(-18, 18, size=2)
            ex, ey = rng.uniform(-18, 18, size=2)
            vmap[y, x, :] = [sx, sy, ex, ey]
            junctions[i] = [y, x]
            scores[i] = float(rng.uniform(0.0, 0.5))
        _, _, merged, _, found = run_pipeline(junctions, scores, vmap)
        self.assertTrue(found["squares"].size > 0, "no square found at all")
        areas = [M.polygon_area(s) for s in found["squares"]]
        self.assertLessEqual(abs(max(areas) - 20000.0), 400.0,
                             "largest square area %r is not the planted rectangle" % max(areas))

    def test_single_vote_edge_can_lose_to_a_stronger_neighbour(self):
        """Documents NAVER's NMS: a line voted once loses to a neighbour voted twice.

        This is not a defect of the port — it is why H29 §7 flagged "a page edge
        that lost to text lines simply will not be present" as a risk, and why
        the H30 merge rule must treat *missing* support as "no evidence", never
        as "not a page".
        """
        junctions, scores, vmap = maps_from_segments([
            (30.0, 60.0, 230.0, 60.0),   # voted once, 1 px above a stronger line
            (60.0, 61.0, 140.0, 61.0),   # same Hough cell as ...
            (160.0, 61.0, 230.0, 61.0),  # ... this one -> count 2
        ])
        _, _, merged, _, _ = run_pipeline(junctions, scores, vmap)
        self.assertEqual(merged.shape[0], 1, "the 5x5 (d, theta) NMS keeps only the peak")


# ==========================================================================================
# 7) The merge rule — split on a fold, keep the page, and do no harm
# ==========================================================================================

import h30_merge as MG  # noqa: E402


def _gray_with_text(w=400, h=400, text_x0=60, text_x1=340, text_y0=60, text_y1=340):
    """White page with a dense text block: rows of dark strokes inside the box."""
    g = np.full((h, w), 235.0, dtype=np.float32)
    for y in range(text_y0, text_y1, 12):
        g[y:y + 4, text_x0:text_x1] = 25.0
    return g


class TestMergeRule(unittest.TestCase):
    """The rule is the whole point of H30, so it is tested on known geometry."""

    QUAD = np.asarray([[0.0, 0.0], [400.0, 0.0], [400.0, 400.0], [0.0, 400.0]])

    def test_fold_at_ten_percent_splits_and_keeps_the_page(self):
        gray = _gray_with_text()
        # A spine-parallel line 40 px (10 %) in from the left edge.
        segs = np.asarray([[40.0, -50.0, 40.0, 450.0]])
        out, info = MG.apply(self.QUAD, segs, gray)
        self.assertTrue(info["applied"], info)
        self.assertEqual(info["side"], "left")
        self.assertAlmostEqual(info["strip_frac"], 0.10, delta=0.02)
        # The kept half must be the page (right side), i.e. x from 40 to 400.
        self.assertAlmostEqual(M.polygon_area(out), 360.0 * 400.0, delta=200.0)
        self.assertGreater(out[:, 0].min(), 30.0)

    def test_fold_at_three_percent_is_an_edge_shadow_not_a_page(self):
        """bs06's gutter shadow sits ~3.5 % inside the page: it must NOT be cut."""
        gray = _gray_with_text()
        segs = np.asarray([[12.0, -50.0, 12.0, 450.0]])
        out, info = MG.apply(self.QUAD, segs, gray)
        self.assertFalse(info["applied"])
        self.assertIn(info["reason"], ("no_interior_line", "strip_size"))
        np.testing.assert_allclose(out, self.QUAD)

    def test_fold_beyond_45_percent_is_read_symmetrically(self):
        """A line at 62.5 % from the left is a 37.5 % strip from the right.

        **Known limitation, stated rather than hidden.** A purely geometric rule
        cannot tell "spread with a wide sliver on the right" from "page with a
        long vertical rule at 62 %". It therefore treats the line as a fold on
        whichever side gives the thinner strip, and the *corpus* gate (20 real
        photographs, mean corner error and border RMS) is what catches a rule
        line masquerading as a gutter. `max_strip_frac` bounds the damage at
        45 % of the quad in the worst case.
        """
        gray = _gray_with_text()
        segs = np.asarray([[250.0, -50.0, 250.0, 450.0]])
        _out, info = MG.apply(self.QUAD, segs, gray)
        self.assertTrue(info["applied"])
        self.assertEqual(info["side"], "right")
        self.assertAlmostEqual(info["strip_frac"], 0.375, delta=0.02)
        # The kept half is always the larger one, whatever the side label says.
        self.assertEqual(info["choice"]["area_fracs"].index(max(info["choice"]["area_fracs"])), 0)

    def test_no_segments_returns_the_input(self):
        out, info = MG.apply(self.QUAD, np.zeros((0, 4)), _gray_with_text())
        self.assertFalse(info["applied"])
        self.assertEqual(info["reason"], "no_segments")

    def test_horizontal_line_is_not_a_fold(self):
        gray = _gray_with_text()
        segs = np.asarray([[-50.0, 40.0, 450.0, 40.0]])  # spine-perpendicular
        _out, info = MG.apply(self.QUAD, segs, gray)
        self.assertFalse(info["applied"])
        self.assertEqual(info["reason"], "no_interior_line")

    def test_short_line_spanning_only_part_of_the_page_is_not_a_fold(self):
        gray = _gray_with_text()
        segs = np.asarray([[40.0, 100.0, 40.0, 300.0]])  # half the height
        _out, info = MG.apply(self.QUAD, segs, gray)
        self.assertFalse(info["applied"])

    def test_text_selector_is_reported_even_when_area_wins(self):
        gray = _gray_with_text()
        segs = np.asarray([[40.0, -50.0, 40.0, 450.0]])
        _out, info = MG.apply(self.QUAD, segs, gray)
        self.assertEqual(info["choice"]["selector"], "area")
        self.assertEqual(len(info["choice"]["text_scores"]), 2)

    def test_text_selector_would_pick_the_richer_half(self):
        """With `selector="text"` the choice follows the text score, not area."""
        halves = [np.asarray([[0.0, 0.0], [40.0, 0.0], [40.0, 400.0], [0.0, 400.0]]),
                  np.asarray([[40.0, 0.0], [400.0, 0.0], [400.0, 400.0], [40.0, 400.0]])]
        gray = _gray_with_text()  # text lives in the wide (right) half
        idx_area, ev_area = MG.choose_page_side(halves, gray, {"selector": "area"})
        idx_text, ev_text = MG.choose_page_side(halves, gray, {"selector": "text"})
        self.assertEqual(idx_area, 1)
        self.assertEqual(idx_text, 1)
        self.assertGreater(ev_text["text_scores"][1], ev_text["text_scores"][0])
        self.assertAlmostEqual(ev_area["area_fracs"][1], 0.90, delta=0.01)

    def test_choose_side_by_area_only(self):
        halves = [np.asarray([[0.0, 0.0], [100.0, 0.0], [100.0, 100.0], [0.0, 100.0]]),
                  np.asarray([[100.0, 0.0], [300.0, 0.0], [300.0, 100.0], [100.0, 100.0]])]
        self.assertEqual(MG.choose_side_by_area_only(halves), 1)


# ==========================================================================================
# 8) Geometry helpers used by the merge rule
# ==========================================================================================

class TestGeometry(unittest.TestCase):

    def test_area_and_containment(self):
        quad = np.asarray([[0.0, 0.0], [10.0, 0.0], [10.0, 10.0], [0.0, 10.0]])
        self.assertAlmostEqual(M.polygon_area(quad), 100.0)
        self.assertTrue(M.point_in_convex_quad((5.0, 5.0), quad))
        self.assertFalse(M.point_in_convex_quad((15.0, 5.0), quad))

    def test_split_by_interior_line_halves_the_rectangle(self):
        quad = np.asarray([[0.0, 0.0], [200.0, 0.0], [200.0, 100.0], [0.0, 100.0]])
        a, b = M.split_quad_by_line(quad, (100.0, -10.0, 100.0, 110.0))
        self.assertIsNotNone(a)
        self.assertIsNotNone(b)
        self.assertAlmostEqual(M.polygon_area(a), 10000.0, delta=1e-6)
        self.assertAlmostEqual(M.polygon_area(b), 10000.0, delta=1e-6)

    def test_split_by_outside_line_returns_nothing(self):
        quad = np.asarray([[0.0, 0.0], [200.0, 0.0], [200.0, 100.0], [0.0, 100.0]])
        a, b = M.split_quad_by_line(quad, (-50.0, -10.0, -50.0, 110.0))
        self.assertIsNone(a)
        self.assertIsNone(b)

    def test_line_support_of_its_own_edges_is_one(self):
        quad = np.asarray([[30.0, 60.0], [230.0, 60.0], [230.0, 160.0], [30.0, 160.0]])
        support, per_edge = M.quad_line_support(quad, RECT)
        self.assertAlmostEqual(support, 1.0, places=6)
        for v in per_edge:
            self.assertAlmostEqual(v, 1.0, places=6)

    def test_line_support_without_edges_is_zero(self):
        quad = np.asarray([[30.0, 60.0], [230.0, 60.0], [230.0, 160.0], [30.0, 160.0]])
        support, _ = M.quad_line_support(quad, [(40.0, 40.0, 220.0, 200.0)])
        self.assertEqual(support, 0.0)

    def test_interior_line_detection(self):
        quad = np.asarray([[30.0, 60.0], [230.0, 60.0], [230.0, 160.0], [30.0, 160.0]])
        self.assertTrue(M.line_through_quad(quad, (120.0, 20.0, 120.0, 200.0)))
        self.assertFalse(M.line_through_quad(quad, (280.0, 20.0, 280.0, 200.0)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
