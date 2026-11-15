package com.docscanai.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SUBTASK H32 — unit tests for the merge-repair rule changes measured on the real book-spread set:
 *
 *  - the adaptive fold window (a spread quad may split up to 55 % of its width, a page still 45 %);
 *  - all-candidate fold evaluation (not just the nearest per side);
 *  - the paper-prior side choice with the pixel cut penalty ([MlsdMergeRepair.borderIsPaper]);
 *  - [MlsdMergeRepair.Fractional.keep] round-trips through [MlsdMergeRepair.applyFractional].
 *
 * Constants and behaviour here are the ones measured in `tooling/out/h32/policy_ab.json`
 * (variant `h32_v2_novalley`) — the shape chosen over `h32_v3`/`h32_full` because it is the only
 * variant with **0 wrong-page** picks on the real set, which is the owner's primary gate.
 */
class MlsdMergeRepairH32Test {

    private fun pt(x: Double, y: Double) = MlsdGeometry.Pt(x, y)

    /** A quad of width [w] and height [h], TL,TR,BR,BL. */
    private fun rect(w: Double, h: Double): List<MlsdGeometry.Pt> =
        listOf(pt(0.0, 0.0), pt(w, 0.0), pt(w, h), pt(0.0, h))

    private fun seg(x1: Double, y1: Double, x2: Double, y2: Double) = MlsdGeometry.Seg(x1, y1, x2, y2)

    // ==========================================================================================
    // Adaptive fold window (STEP 3(d))
    // ==========================================================================================

    @Test
    fun aPageSizedQuadKeepsTheNarrowWindow() {
        // w/h 0.709 (a single page): the window stays at the historical 0.45.
        val page = rect(709.0, 1000.0)
        assertEquals(0.709, MlsdMergeRepair.quadAspect(page), 1e-3)
        assertEquals(MlsdMergeRepair.MAX_STRIP_FRAC, MlsdMergeRepair.maxStripFor(page), 1e-9)
    }

    @Test
    fun aSpreadSizedQuadOpensTheWideWindow() {
        // w/h 1.42 (two A4 pages side by side) and the real-set cases at 1.85..2.15.
        for (asp in doubleArrayOf(1.42, 1.85, 2.15)) {
            val spread = rect(asp * 1000.0, 1000.0)
            assertEquals(asp, MlsdMergeRepair.quadAspect(spread), 1e-3)
            assertEquals(
                "aspect $asp must open the spread window",
                MlsdMergeRepair.SPREAD_MAX_STRIP_FRAC,
                MlsdMergeRepair.maxStripFor(spread),
                1e-9
            )
        }
    }

    @Test
    fun aBalancedSpreadIsNotSplitButAGenuineSliverIs() {
        // OWNER RULE (2026-11-15), which REVERSES what this test asserted before.
        //
        // "If the 2 pages are seen and at the centre it probably means I want to use the 2 pages
        // as 1. It is 1 page only when ... the other is maybe only seen < 25 %."
        //
        // So a gutter in the MIDDLE of a spread quad is no longer something to split at: the two
        // leaves are the target, and cutting one away produces exactly the half-page scans the
        // owner complained about. A fold may only remove a sliver smaller than a quarter of the
        // half it keeps.
        val spread = rect(1420.0, 1000.0)
        val balanced = MlsdMergeRepair.apply(spread, listOf(seg(710.0, -50.0, 710.0, 1050.0)))
        assertFalse(
            "a gutter at the middle of a spread must NOT be split: both leaves are the target",
            balanced.applied
        )

        // A real sliver (9 % of the width, i.e. 0.099 of the kept half) is still removed.
        val sliver = MlsdMergeRepair.apply(spread, listOf(seg(128.0, -50.0, 128.0, 1050.0)))
        assertTrue("a 9 % sliver must still be removed", sliver.applied)
        assertEquals(0.09, sliver.stripFrac, 0.02)

        // The 25 %-of-kept-half boundary, from both sides. stripFrac 0.20 is exactly
        // discard/keep = 0.25, so it must already be refused.
        assertTrue(
            "0.18 strip (discard/keep 0.22) is still a sliver",
            MlsdMergeRepair.apply(spread, listOf(seg(255.6, -50.0, 255.6, 1050.0))).applied
        )
        assertFalse(
            "0.20 strip (discard/keep 0.25) is a spread split",
            MlsdMergeRepair.apply(spread, listOf(seg(284.0, -50.0, 284.0, 1050.0))).applied
        )

        // And a page-sized quad (w/h 0.709) must still refuse a middle fold.
        val page = rect(709.0, 1000.0)
        val onPage = MlsdMergeRepair.apply(page, listOf(seg(354.0, -50.0, 354.0, 1050.0)))
        assertFalse("a page must not be split down its own middle", onPage.applied)

        // A fold beyond BOTH windows is not a candidate at all.
        val folds = MlsdMergeRepair.findAllMergeLines(
            spread, listOf(seg(852.0, -50.0, 852.0, 1050.0)) // 0.60 left / 0.40 right
        )
        assertTrue("only the in-window side is kept", folds.all { it.dSide <= 0.55 + 1e-9 })
    }

    // ==========================================================================================
    // All-candidate evaluation + paper-prior selection
    // ==========================================================================================

    @Test
    fun theBestScoringSplitWinsNotTheNearest() {
        // Two folds: a NEAR margin line (8 %) and a further one (18 %). Both are slivers under the
        // owner's 25 % rule, so both qualify; the paper prior must choose the one whose kept half
        // looks more like a page, not simply the nearest to the edge.
        val spread = rect(1420.0, 1000.0)
        val margin = seg(113.6, -50.0, 113.6, 1050.0)   // 8 % strip
        val fold = seg(255.6, -50.0, 255.6, 1050.0)     // 18 % strip
        val outcome = MlsdMergeRepair.apply(spread, listOf(margin, fold))
        assertTrue(outcome.applied)
        // The kept half after the 18 % split (1164 x 1000, w/h 1.16) scores higher than after the
        // 8 % split (1306 x 1000, w/h 1.31), which is still wider than a page.
        assertEquals(0.18, outcome.stripFrac, 0.02)
    }

    @Test
    fun everyQualifyingFoldIsEvaluated() {
        val spread = rect(1420.0, 1000.0)
        val all = MlsdMergeRepair.findAllMergeLines(spread, listOf(
            seg(170.0, -50.0, 170.0, 1050.0),
            seg(540.0, -50.0, 540.0, 1050.0),
            seg(880.0, -50.0, 880.0, 1050.0)
        ))
        assertTrue("all three qualify on a spread window", all.size >= 3)
        // nearest-first per side
        for (i in 1 until all.size) assertTrue(all[i - 1].dSide <= all[i].dSide + 1e-9)
    }

    // ==========================================================================================
    // Pixel partial-page term (STEP 3 b/c)
    // ==========================================================================================

    @Test
    fun aHalfWhoseBorderIsPaperIsAPartialPage() {
        // Frame 200x200. Both halves are PAGES (bright interior, luma 0.8) filling the frame
        // vertically (shared top/bottom borders). The distinguishing border is the OUTER side:
        // the LEFT half runs off the frame at x=0 (paper at the border, luma 0.9 -> cut), the
        // RIGHT half ends before the frame (background at x>=196, luma 0.05 -> interior). The
        // sibling must be passed exactly as production does — without it the shared top/bottom
        // borders would read as "paper" and mark both halves as cut.
        val left = listOf(pt(0.0, 0.0), pt(100.0, 0.0), pt(100.0, 200.0), pt(0.0, 200.0))
        val right = listOf(pt(100.0, 0.0), pt(200.0, 0.0), pt(200.0, 200.0), pt(100.0, 200.0))
        val luma: (Int, Int) -> Double = { x, _ ->
            when {
                x <= 4 -> 0.9     // paper continues past the left border
                x >= 196 -> 0.05  // background at the right border
                else -> 0.8       // page interior
            }
        }
        assertTrue(
            "left border is paper -> cut",
            MlsdMergeRepair.borderIsPaper(left, 200, 200, luma, sibling = right)
        )
        assertFalse(
            "right border is background -> interior",
            MlsdMergeRepair.borderIsPaper(right, 200, 200, luma, sibling = left)
        )
    }

    @Test
    fun thePixelPenaltyIsAppliedWhenSelecting() {
        // The area prior keeps the WIDER half (right, 130 wide) but that half is cut by the frame
        // (paper at the right border); the left half (70 wide) is interior. With a luma sampler
        // supplied the cut penalty lowers this candidate's score; the outcome must still be a
        // valid split (the exact kept side stays the area prior — the penalty only scores).
        val quad = rect(200.0, 200.0)
        val fold = seg(30.0, -20.0, 30.0, 220.0) // 15 % strip (a sliver, not a spread split)
        val luma: (Int, Int) -> Double = { x, _ ->
            when {
                x >= 196 -> 0.9   // paper runs off the right border
                x <= 4 -> 0.05    // background at the left border
                else -> 0.8       // page interior
            }
        }
        val withPixels = MlsdMergeRepair.apply(quad, listOf(fold), 200, 200, luma)
        assertTrue(withPixels.applied)
        assertNotNull(withPixels.keepSide)
    }

    @Test
    fun theCutPenaltyScoresTheCandidateWithoutFlippingTheSide() {
        // The measured H32 design (policy_ab.json h32_v2_novalley vs h32_nopen): the pixel cut
        // penalty adjusts the SCORE of a candidate; the kept side stays the area prior. This
        // isolation was measured NEUTRAL on the real set (identical rows) — the penalty is kept
        // because it is free and physically right, but it is NOT claimed as a measured win.
        val quad = rect(200.0, 200.0)
        val fold = seg(30.0, -20.0, 30.0, 220.0) // 15 % strip -> area keeps the right half
        val luma: (Int, Int) -> Double = { x, _ ->
            when {
                x >= 196 -> 0.9
                x <= 4 -> 0.05
                else -> 0.8
            }
        }
        val out = MlsdMergeRepair.apply(quad, listOf(fold), 200, 200, luma)
        assertTrue(out.applied)
        // area prior keeps the WIDER half (right): the kept side label must be "right"
        assertEquals("right", out.keepSide)
    }

    // ==========================================================================================
    // Fractional round-trip with the recorded keep side
    // ==========================================================================================

    @Test
    fun theCachedKeepSideRoundTrips() {
        val spread = rect(1420.0, 1000.0)
        val fold = seg(255.6, -50.0, 255.6, 1050.0) // 18 % sliver, inside the owner rule
        val outcome = MlsdMergeRepair.apply(spread, listOf(fold))
        assertTrue(outcome.applied)
        val cached = MlsdMergeRepair.fractionalOf(outcome)
        assertNotNull(cached)
        assertEquals(outcome.keepSide, cached!!.keep)
        val projected = MlsdMergeRepair.applyFractional(spread, cached)
        assertNotNull(projected)
        // the projected quad is the same half the rule chose (up to corner order)
        val chosen = MlsdMergeRepair.orderCorners(outcome.quad!!)
        val got = MlsdMergeRepair.orderCorners(projected!!)
        for (i in 0 until 4) {
            assertEquals("corner $i", chosen[i].x, got[i].x, 1.0)
            assertEquals("corner $i", chosen[i].y, got[i].y, 1.0)
        }
    }

    @Test
    fun aPreH32CacheEntryWithoutKeepStillWorks() {
        // Old cache entries carry only (side, t); the historical behaviour (keep the side the fold
        // did NOT come from) must stand so a warm overlay never flips after an app update.
        val spread = rect(1420.0, 1000.0)
        val old = MlsdMergeRepair.Fractional("left", 0.20)
        assertNull(old.keep)
        val projected = MlsdMergeRepair.applyFractional(spread, old)
        assertNotNull(projected)
        val q = MlsdMergeRepair.orderCorners(projected!!)
        // fold came from the left at 20 % -> the kept side is the RIGHT 80 %: width ~1136
        val width = kotlin.math.hypot(q[1].x - q[0].x, q[1].y - q[0].y)
        assertEquals(1136.0, width, 2.0)
    }

    @Test
    fun theAdaptiveWindowIsRecordedInTheDescribeLine() {
        val text = MlsdMergeRepair.describe()
        assertTrue(text, text.contains("strip="))
        assertTrue(text, text.contains("chord>="))
    }
}
