package com.docscanai.ml

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * ============================================================================================
 * SUBTASK H30 — merge rejection / repair: stop the facing page being absorbed.
 * ============================================================================================
 *
 * The owner's complaint is that the scan sometimes keeps *more than the requested page*: on an
 * open book the corner model puts four corners on the **spread**, because that is the sheet of
 * paper it sees (H29 §5). This rule uses M-LSD's line evidence to refuse that:
 *
 * 1. **Find the fold.** A segment qualifies when it is spine-parallel (within
 *    [ANGLE_TOL_DEG] of the quad's own left/right edge direction), at least [MIN_LEN_FRAC] of the
 *    quad's height long, and its *line* spans the quad's full extent along that direction
 *    ([CHORD_FRAC]). The measurement is on the infinite line, not the segment, because M-LSD
 *    segments routinely stop a few pixels short of the border they mark.
 * 2. **Only near a side.** The fold's distance from the quad's left (or right) edge must be at
 *    least [MIN_STRIP_FRAC] of the quad's width — otherwise it is the page's own edge shadow
 *    (bs06's gutter shadow sits 3.5 % inside the page and is *not* a facing page) — and at most
 *    [MAX_STRIP_FRAC], or the "sliver" would be the page itself.
 * 3. **Split and choose.** The split yields two halves; the page is the **larger** half. The area
 *    prior is measured, not assumed: on the 18 fixtures the text-block selector chose the
 *    facing-page sliver on bs02/bs03/bs07 preview (3/12 merged, 3.8984 % corner) while the area
 *    prior scored 0/12 merged and 3.6923 %. The text score was therefore *rejected* by
 *    measurement — see `tooling/out/h30/scores.md` and `h30_merge.py`.
 * 4. **Do no harm.** Any failed condition returns "not applied" with a reason; the detector then
 *    keeps the original quad. The detector additionally re-runs its own `isPlausiblePageQuad`
 *    gate on the repaired quad, so a bad split can never reach the warp.
 *
 * This mirrors `tooling/h30_merge.py::apply` constant for constant, so the offline JSON and the
 * on-device `[MLSD]` log line can be compared directly.
 */
object MlsdMergeRepair {

    const val ANGLE_TOL_DEG = 25.0
    const val MIN_LEN_FRAC = 0.45

    /**
     * SUBTASK H32 — the fold-line chord window, 0.80 -> 0.70. MEASURED on the real book-spread
     * set (tooling/out/h32/policy_ab.json): the true gutter segments on the residual spreads
     * (rb011/rb012/rb022/rb034) cover 0.74..0.83 of the quad along their own direction and were
     * rejected at 0.80, so the spread was never split and the facing page stayed absorbed.
     * At 0.70 those folds qualify; the synthetic set's numbers are unchanged (0/12 merged,
     * corner 3.5028 % offline, identical to the 0.80 behaviour) — the extra chords that pass
     * are then refused by the strip/paper-prior guards.
     */
    const val CHORD_FRAC = 0.70
    const val MIN_STRIP_FRAC = 0.05
    const val MAX_STRIP_FRAC = 0.45

    /**
     * SUBTASK H32 STEP 3(d) — a spread-signature quad may be split up to [SPREAD_MAX_STRIP_FRAC]
     * of its width. The 0.45 cap existed to keep a "sliver" from being the page itself, but a
     * REAL two-page spread puts the gutter at ~50 % of the width (measured: the real-set gutters
     * sit at d=0.45..0.52), so at 0.45 the fold search cannot see them at all. The adaptive
     * window only opens for quads whose w/h is at least [SPREAD_ASPECT_MIN] (a single page is
     * ~0.709 wide per unit height; two pages ~1.42), so thin-sliver handling is unchanged.
     *
     * SUBTASK H36 fix c — a window floor of 0.74 (the merge-signature floor of
     * `MlsdTrigger.Policy.aspectMin`) was MEASURED on owner-110 (tooling/out/h36/report_fixc.md
     * vs report_fixab.md): identical 91 detected / 18 wrong-page / 23 merged — the extra folds it
     * exposes are then refused downstream (`no_interior_line` stays dominant), so the wider
     * window does not earn its keep and 1.10 STANDS (neutral change dropped per the no-ship rule).
     */
    const val SPREAD_ASPECT_MIN = 1.10
    const val SPREAD_MAX_STRIP_FRAC = 0.55

    /**
     * SUBTASK H32: the candidate-acceptance floor for a repair split. Mirrors
     * `MlsdTrigger.REPAIR_MIN_STRIP` (0.075) so [apply] selects among exactly the splits the
     * detector will later accept — measured: every fold that HELPED cut 0.076..0.259 of the width,
     * every fold that DAMAGED a page cut 0.061..0.067 (H31 SUMMARY). Selection and acceptance at
     * the same floor avoids choosing a 0.06 strip by prior and then having it thrown away, losing a
     * valid split that was also in the candidate list.
     */
    const val REPAIR_MIN_STRIP_FRAC = 0.075

    /** Quad w/h in the quad's own axes (a spread is wide, a single page is tall). */
    fun quadAspect(quad: List<MlsdGeometry.Pt>): Double {
        if (quad.size != 4) return 0.0
        val (wAxis, hAxis) = MlsdGeometry.quadAxes(quad)
        val w = hypot(wAxis.x, wAxis.y)
        val h = hypot(hAxis.x, hAxis.y)
        return if (h > 1e-9) w / h else 0.0
    }

    /** The fold window for this quad: [SPREAD_MAX_STRIP_FRAC] on a spread, else [MAX_STRIP_FRAC]. */
    fun maxStripFor(quad: List<MlsdGeometry.Pt>): Double =
        if (quadAspect(quad) >= SPREAD_ASPECT_MIN) SPREAD_MAX_STRIP_FRAC else MAX_STRIP_FRAC

    /** One fold candidate on one side of the quad. */
    data class Fold(val line: MlsdGeometry.Seg, val side: String, val dSide: Double, val cosSpine: Double)

    /** What the rule decided, in the shape the `[MLSD]` log line wants. */
    data class Outcome(
        val quad: List<MlsdGeometry.Pt>?,
        val applied: Boolean,
        val reason: String,
        val side: String? = null,
        val stripFrac: Double = 0.0,
        val fold: Fold? = null,
        /**
         * SUBTASK H32: WHICH side of the fold is the PAGE ("left"/"right", relative to the quad's
         * own axes). Required because the page is no longer always the larger half: a spread can
         * return a 50/50 split and the partial-page rule may prefer the smaller interior side.
         */
        val keepSide: String? = null
    )

    /**
     * SUBTASK H31 — a repair expressed as **geometry only**: which side the fold came from and how
     * far it sits from that side, as a fraction of the quad's width ([t] = `Fold.dSide`).
     *
     * This is what the cadence cache stores. Re-applying it to a later frame's quad is two lerps
     * ([applyFractional]) instead of a 74 ms inference, and because it is a continuous function of
     * the current quad the overlay cannot flicker when the model does not run.
     */
    data class Fractional(val side: String, val t: Double, val keep: String? = null)

    /** The cacheable form of an applied [Outcome], or `null` when nothing was applied. */
    fun fractionalOf(outcome: Outcome): Fractional? {
        if (!outcome.applied) return null
        val side = outcome.side ?: return null
        val d = outcome.fold?.dSide ?: return null
        if (d < MIN_STRIP_FRAC || d > SPREAD_MAX_STRIP_FRAC) return null
        return Fractional(side, d, outcome.keepSide)
    }

    /**
     * The inverse of "split by a spine-parallel line": cuts [quad] at fraction [Fractional.t] of
     * its width measured from [Fractional.side] and keeps the **larger** side, which is the page
     * (H30 §3: the area prior beat the text selector on this dataset).
     *
     * Corner order is preserved: the result is TL, TR, BR, BL. Returns `null` for a malformed quad.
     */
    fun applyFractional(quad: List<MlsdGeometry.Pt>, repair: Fractional): List<MlsdGeometry.Pt>? {
        if (quad.size != 4) return null
        val t = repair.t.coerceIn(MIN_STRIP_FRAC, maxStripFor(quad))
        fun lerp(p: MlsdGeometry.Pt, q: MlsdGeometry.Pt, u: Double) = MlsdGeometry.Pt(
            p.x + (q.x - p.x) * u,
            p.y + (q.y - p.y) * u
        )
        val tl = quad[0]
        val tr = quad[1]
        val br = quad[2]
        val bl = quad[3]
        // H32: the cached form records WHICH side is the page. When absent (pre-H32 cache
        // entries) the historical behaviour stands: keep the side the fold did not come from.
        val keep = repair.keep ?: if (repair.side == "left") "right" else "left"
        return if (repair.side == "left") {
            if (keep == "left") {
                listOf(tl, lerp(tl, tr, t), lerp(bl, br, t), bl)
            } else {
                listOf(lerp(tl, tr, t), tr, br, lerp(bl, br, t))
            }
        } else {
            if (keep == "right") {
                listOf(lerp(tl, tr, t), tr, br, lerp(bl, br, t))
            } else {
                listOf(tl, lerp(tr, tl, t), lerp(br, bl, t), bl)
            }
        }
    }

    /**
     * SUBTASK H32 — EVERY qualifying fold, both sides included, nearest-first per side.
     *
     * The H30 rule looked at only the nearest candidate per side ("a page's boundary is the first
     * long spine-parallel line inside the quad measured from the outer edge"), which is right for
     * a sliver but wrong for a balanced spread: the gutter sits at ~50 % of the width and a text
     * margin line nearer the edge always beat it (measured: rb015 kept the wrong page because a
     * margin line at 8.8 % won over the gutter at 42.8 %). [apply] therefore evaluates every
     * candidate and keeps the best-scoring split; the nearest-per-side form is kept in
     * [findMergeLines] for callers that only want the cheap view.
     */
    fun findAllMergeLines(
        quad: List<MlsdGeometry.Pt>,
        segments: List<MlsdGeometry.Seg>
    ): List<Fold> {
        if (quad.size != 4 || segments.isEmpty()) return emptyList()
        val (wAxis, hAxis) = MlsdGeometry.quadAxes(quad)
        val width = hypot(wAxis.x, wAxis.y)
        val height = hypot(hAxis.x, hAxis.y)
        if (width < 1e-6 || height < 1e-6) return emptyList()
        // SUBTASK H32 STEP 3(d): the fold window widens to 55 % of the width on a spread quad.
        val maxStrip = maxStripFor(quad)
        val hux = hAxis.x / height
        val huy = hAxis.y / height
        val cosTol = cos(Math.toRadians(ANGLE_TOL_DEG))

        val out = ArrayList<Fold>(8)
        for (seg in segments) {
            val dx = seg.x2 - seg.x1
            val dy = seg.y2 - seg.y1
            val len = hypot(dx, dy)
            if (len < 1e-6) continue
            val cosSpine = abs((dx * hux + dy * huy) / len)
            if (cosSpine < cosTol) continue
            if (len < MIN_LEN_FRAC * height) continue
            if (!MlsdGeometry.lineThroughQuad(quad, seg, CHORD_FRAC)) continue

            val crossings = lineCrossingFractions(quad, seg) ?: continue
            if (crossings.any { it < 0.05 || it > 0.95 }) continue

            val dLeft = pointSegDist(seg.midX, seg.midY, quad[0], quad[3]) / width
            val dRight = pointSegDist(seg.midX, seg.midY, quad[1], quad[2]) / width
            for ((side, dist) in listOf("left" to dLeft, "right" to dRight)) {
                if (dist < MIN_STRIP_FRAC || dist > maxStrip) continue
                out.add(Fold(seg, side, dist, cosSpine))
            }
        }
        out.sortBy { it.dSide }
        return out
    }

    /**
     * The fold candidates, one per side (nearest that side wins, because a page's boundary is the
     * first long spine-parallel line inside the quad measured from the outer edge while a text
     * margin sits further in). Thin view over [findAllMergeLines].
     */
    fun findMergeLines(
        quad: List<MlsdGeometry.Pt>,
        segments: List<MlsdGeometry.Seg>
    ): Map<String, Fold> {
        val best = HashMap<String, Fold>(2)
        for (fold in findAllMergeLines(quad, segments)) {
            val cur = best[fold.side]
            if (cur == null || fold.dSide < cur.dSide) best[fold.side] = fold
        }
        return best
    }

    /**
     * SUBTASK H32 STEP 3(b,c) — does the PAGE run off the frame at any border this half reaches?
     * For each FRAME border the half's bounding box reaches (within [FRAME_TOL_FRAC] of the frame's
     * short side), sample luma ON that border within the half's extent: paper-like there (>=
     * [PAPER_BORDER_RATIO] of the half's own paper luma) means the page material continues past the
     * frame => this half is a PARTIAL page. Background at the border means the page ends before the
     * frame => interior page.
     *
     * (An earlier "walk to the farthest edge" formulation sampled the wrong border for portrait
     * halves — it walked to the top/bottom instead of the cut side — and was replaced after the
     * H32 unit tests caught it.)
     *
     * [luma] is `(x, y) -> brightness in 0..1`; the module stays pure-Kotlin because the caller
     * supplies it (see `OpenCvQuadPageDetector.applyMlsdEvidence`).
     */
    fun borderIsPaper(
        half: List<MlsdGeometry.Pt>,
        frameW: Int,
        frameH: Int,
        luma: (Int, Int) -> Double,
        sibling: List<MlsdGeometry.Pt>? = null
    ): Boolean {
        if (half.size != 4 || frameW <= 0 || frameH <= 0) return false
        // paper reference = 75th percentile of luma inside the half's bounding box
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in half) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val samples = ArrayList<Double>(256)
        for (i in 0 until 24) {
            for (j in 0 until 24) {
                val fx = minX + (maxX - minX) * (i + 0.5) / 24.0
                val fy = minY + (maxY - minY) * (j + 0.5) / 24.0
                if (!pointInHalf(fx, fy, half)) continue
                val x = fx.toInt().coerceIn(0, frameW - 1)
                val y = fy.toInt().coerceIn(0, frameH - 1)
                samples.add(luma(x, y))
            }
        }
        samples.sort()
        val paper = if (samples.isEmpty()) 0.5 else samples[(samples.size * 3) / 4]
        val tol = FRAME_TOL_FRAC * min(frameW, frameH)

        fun patchMean(x0: Double, x1: Double, y0: Double, y1: Double): Double {
            val xi0 = x0.toInt().coerceIn(0, frameW - 1)
            val xi1 = x1.toInt().coerceIn(0, frameW - 1)
            val yi0 = y0.toInt().coerceIn(0, frameH - 1)
            val yi1 = y1.toInt().coerceIn(0, frameH - 1)
            if (xi1 <= xi0 || yi1 <= yi0) return 0.0
            var sum = 0.0
            var cnt = 0
            for (y in yi0..yi1) {
                for (x in xi0..xi1) {
                    sum += luma(x, y)
                    cnt++
                }
            }
            return if (cnt > 0) sum / cnt else 0.0
        }

        // Only the borders that are THIS half's alone distinguish it from the sibling: a fold
        // split separates halves along the width direction, so top/bottom are typically shared
        // and say nothing about which side is the partial page (the H32 unit test caught the
        // "every reached border" reading marking a page as cut for filling the frame vertically).
        val mine = reachedBorders(half, frameW, frameH, tol)
        if (sibling != null) mine -= reachedBorders(sibling, frameW, frameH, tol)

        var worst = 0.0
        if (0 in mine) { // left border, this half alone
            worst = max(worst, patchMean(0.0, 4.0, minY + 0.25 * (maxY - minY), minY + 0.75 * (maxY - minY)))
        }
        if (1 in mine) { // right border, this half alone
            worst = max(worst, patchMean(frameW - 5.0, frameW - 1.0, minY + 0.25 * (maxY - minY), minY + 0.75 * (maxY - minY)))
        }
        if (2 in mine) { // top border, this half alone
            worst = max(worst, patchMean(minX + 0.25 * (maxX - minX), minX + 0.75 * (maxX - minX), 0.0, 4.0))
        }
        if (3 in mine) { // bottom border, this half alone
            worst = max(worst, patchMean(minX + 0.25 * (maxX - minX), minX + 0.75 * (maxX - minX), frameH - 5.0, frameH - 1.0))
        }
        return worst >= PAPER_BORDER_RATIO * max(paper, 1e-6)
    }

    /** Which frame borders the polygon's bounding box reaches (0=left, 1=right, 2=top, 3=bottom). */
    private fun reachedBorders(
        pts: List<MlsdGeometry.Pt>,
        frameW: Int,
        frameH: Int,
        tol: Double
    ): MutableSet<Int> {
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in pts) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val out = HashSet<Int>(4)
        if (minX <= tol) out.add(0)
        if (maxX >= frameW - 1 - tol) out.add(1)
        if (minY <= tol) out.add(2)
        if (maxY >= frameH - 1 - tol) out.add(3)
        return out
    }

    /** Convex-containment test for the half polygon (same convention as the offline replica). */
    private fun pointInHalf(x: Double, y: Double, half: List<MlsdGeometry.Pt>): Boolean {
        var sign = 0
        for (i in 0 until half.size) {
            val a = half[i]
            val b = half[(i + 1) % half.size]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            if (kotlin.math.abs(cross) < 1e-9) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Fraction of the frame's short side within which a quad edge "touches" the frame. */
    const val FRAME_TOL_FRAC = 0.02

    /** Border sample is "paper" when its luma is at least this fraction of the half's paper luma. */
    const val PAPER_BORDER_RATIO = 0.70

    /**
     * Applies the rule. SUBTASK H32: evaluates EVERY qualifying fold and keeps the split whose
     * retained half scores highest on the paper-aspect prior (the shape the app's page gate
     * already prefers), with a penalty when the retained half is a PARTIAL page (its outward frame
     * border is still paper — STEP 3 b/c). Returns [Outcome.quad] = `null` when nothing was
     * repaired, so a caller can keep its original quad without a second branch.
     *
     * [luma] is optional; without it the pixel cut-penalty is skipped (pure-geometry path, used by
     * JVM unit tests). The frame size is only needed for the pixel path.
     */
    fun apply(
        quad: List<MlsdGeometry.Pt>,
        segments: List<MlsdGeometry.Seg>,
        frameW: Int = 0,
        frameH: Int = 0,
        luma: ((Int, Int) -> Double)? = null
    ): Outcome {
        if (quad.size != 4) return Outcome(null, false, "bad_quad")
        if (segments.isEmpty()) return Outcome(null, false, "no_segments")

        val folds = findAllMergeLines(quad, segments)
        if (folds.isEmpty()) return Outcome(null, false, "no_interior_line")
        val maxStrip = maxStripFor(quad)
        val (wAxis, hAxis) = MlsdGeometry.quadAxes(quad)
        val width = hypot(wAxis.x, wAxis.y)
        if (width < 1e-6) return Outcome(null, false, "bad_quad")

        data class Cand(
            val score: Double,
            val keep: List<MlsdGeometry.Pt>,
            val keepSide: String,
            val fold: Fold,
            val stripFrac: Double
        )
        val accepted = ArrayList<Cand>(4)

        for (fold in folds) {
            val (halfA, halfB) = MlsdGeometry.splitQuadByLine(quad, fold.line)
            if (halfA == null || halfB == null) continue
            val areaA = MlsdGeometry.polygonArea(halfA)
            val areaB = MlsdGeometry.polygonArea(halfB)
            val total = areaA + areaB
            if (total <= 1e-9) continue
            val stripFrac = min(areaA, areaB) / total
            if (stripFrac < REPAIR_MIN_STRIP_FRAC || stripFrac > maxStrip) continue

            // STEP 3(b,c): the page is the half that is NOT a partial page. MEASURED design
            // (tooling/out/h32/policy_ab.json, variant h32_v2_novalley): the area prior chooses
            // the kept half, and a pixel penalty (-0.25 on the paper-aspect prior) is applied when
            // that half is cut by the frame while the other is interior. A side-FLIPPING rule was
            // measured and rejected: it traded a merge for a wrong-page pick (1 merged / 1 wrong
            // vs 2 merged / 0 wrong), and wrong-page is the owner's primary gate.
            val keepIdx = if (areaA >= areaB) 0 else 1
            val halves = listOf(halfA, halfB)
            val score: Double
            if (luma != null && frameW > 0 && frameH > 0) {
                val cut = BooleanArray(2) { i ->
                    borderIsPaper(halves[i], frameW, frameH, luma, halves[1 - i])
                }
                score = paperAspectPrior(halves[keepIdx]) -
                    (if (cut[keepIdx] && !cut[1 - keepIdx]) 0.25 else 0.0)
            } else {
                score = paperAspectPrior(halves[keepIdx])
            }
            // The detector's "do no harm" acceptance (MlsdTrigger.acceptsRepair) requires the
            // split to move the quad TOWARDS the paper aspect by more than 0.005. Applying that
            // here (not only after selection) lets a lower-scored valid split win when the best
            // one would have been rejected downstream — the exact behaviour measured offline.
            if (paperAspectPrior(halves[keepIdx]) <= paperAspectPrior(quad) + 0.005) continue
            // Which side of the fold is the kept half on? Derive it geometrically so the
            // `Fractional.keep` label matches `applyFractional`'s "left"/"right" cut semantics
            // (relative to the QUAD's axes, independent of the split's positive/negative order).
            val kept = halves[keepIdx]
            var kx = 0.0
            var ky = 0.0
            for (p in kept) {
                kx += p.x
                ky += p.y
            }
            kx /= 4.0
            ky /= 4.0
            val dKLeft = pointSegDist(kx, ky, quad[0], quad[3]) / width
            val dKRight = pointSegDist(kx, ky, quad[1], quad[2]) / width
            val keepSide = if (dKLeft <= dKRight) "left" else "right"
            accepted.add(Cand(score, kept, keepSide, fold, stripFrac))
        }
        if (accepted.isEmpty()) return Outcome(null, false, "no_accepted_split")
        val best = accepted.maxByOrNull { it.score }!!
        return Outcome(
            best.keep, true, "split", best.fold.side, best.stripFrac, best.fold,
            best.keepSide
        )
    }

    /** How well a quad matches the paper aspect prior (0..1) — the same prior the trigger uses. */
    fun paperAspectPrior(quad: List<MlsdGeometry.Pt>): Double {
        val w = quadAspect(quad)
        if (w <= 1e-9) return 0.0
        val hw = 1.0 / w
        val z = (hw - PAPER_ASPECT_HW) / PAPER_ASPECT_SIGMA
        return kotlin.math.exp(-z * z)
    }

    /** Mirrors `MlsdTrigger.PAPER_ASPECT_HW/SIGMA` (the gate's Gaussian page-shape prior). */
    const val PAPER_ASPECT_HW = 1.41
    const val PAPER_ASPECT_SIGMA = 0.55

    /** Where the **infinite line** of [seg] crosses the quad's two long edges, as fractions. */
    private fun lineCrossingFractions(
        quad: List<MlsdGeometry.Pt>,
        seg: MlsdGeometry.Seg
    ): DoubleArray? {
        val dx = seg.x2 - seg.x1
        val dy = seg.y2 - seg.y1
        val out = DoubleArray(2)
        val edges = arrayOf(quad[0] to quad[1], quad[3] to quad[2])
        for (k in 0 until 2) {
            val (a, b) = edges[k]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val denom = dx * ey - dy * ex
            if (abs(denom) < 1e-12) return null
            val u = ((seg.x1 - a.x) * dy - (seg.y1 - a.y) * dx) / -denom
            out[k] = u
        }
        return out
    }

    private fun pointSegDist(
        px: Double,
        py: Double,
        a: MlsdGeometry.Pt,
        b: MlsdGeometry.Pt
    ): Double {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val denom = abx * abx + aby * aby
        if (denom < 1e-12) return hypot(px - a.x, py - a.y)
        val t = (((px - a.x) * abx + (py - a.y) * aby) / denom).coerceIn(0.0, 1.0)
        return hypot(px - (a.x + t * abx), py - (a.y + t * aby))
    }

    /**
     * Converts a repaired half into the app's `Quad` (TL, TR, BR, BL) without touching
     * `android.graphics` types, so this module stays JVM-testable.
     */
    fun orderCorners(pts: List<MlsdGeometry.Pt>): List<MlsdGeometry.Pt> {
        if (pts.size != 4) return pts
        fun sum(p: MlsdGeometry.Pt) = p.x + p.y
        fun diff(p: MlsdGeometry.Pt) = p.x - p.y
        val tl = pts.minByOrNull { sum(it) } ?: return pts
        val br = pts.maxByOrNull { sum(it) } ?: return pts
        val bl = pts.minByOrNull { diff(it) } ?: return pts
        val tr = pts.maxByOrNull { diff(it) } ?: return pts
        return listOf(tl, tr, br, bl)
    }

    /** Bounding-box area fraction of the frame (cheap guard used in diagnostics only). */
    fun bboxAreaFraction(pts: List<MlsdGeometry.Pt>, frameW: Int, frameH: Int): Double {
        if (pts.isEmpty() || frameW <= 0 || frameH <= 0) return 0.0
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        return (max(0.0, maxX - minX) * max(0.0, maxY - minY)) / (frameW.toDouble() * frameH)
    }

    /** True when [a] is at most [tolerance] of the diagonal away from [b] on every corner. */
    fun cornersAgree(
        a: List<MlsdGeometry.Pt>,
        b: List<MlsdGeometry.Pt>,
        tolerance: Double
    ): Boolean {
        if (a.size != 4 || b.size != 4) return false
        for (i in 0 until 4) {
            if (hypot(a[i].x - b[i].x, a[i].y - b[i].y) > tolerance) return false
        }
        return true
    }

    /** IoU of two convex quads via clipping (diagnostics + unit tests). */
    fun iou(a: List<MlsdGeometry.Pt>, b: List<MlsdGeometry.Pt>): Double {
        val inter = intersectionArea(a, b)
        val union = MlsdGeometry.polygonArea(a) + MlsdGeometry.polygonArea(b) - inter
        return if (union > 1e-9) inter / union else 0.0
    }

    private fun intersectionArea(a: List<MlsdGeometry.Pt>, b: List<MlsdGeometry.Pt>): Double {
        if (a.size < 3 || b.size < 3) return 0.0
        var subject = a.toMutableList()
        val clip = if (signedArea(b) < 0) b.reversed() else b
        for (i in clip.indices) {
            val p1 = clip[i]
            val p2 = clip[(i + 1) % clip.size]
            val ex = p2.x - p1.x
            val ey = p2.y - p1.y
            val out = ArrayList<MlsdGeometry.Pt>(subject.size + 2)
            for (k in subject.indices) {
                val cur = subject[k]
                val prev = subject[(k + subject.size - 1) % subject.size]
                fun inside(p: MlsdGeometry.Pt) =
                    ex * (p.y - p1.y) - ey * (p.x - p1.x) >= 0.0
                val curIn = inside(cur)
                val prevIn = inside(prev)
                if (curIn) {
                    if (!prevIn) out.add(intersect(prev, cur, p1, ex, ey))
                    out.add(cur)
                } else if (prevIn) {
                    out.add(intersect(prev, cur, p1, ex, ey))
                }
            }
            subject = out
            if (subject.isEmpty()) return 0.0
        }
        return MlsdGeometry.polygonArea(subject)
    }

    private fun intersect(
        p: MlsdGeometry.Pt,
        q: MlsdGeometry.Pt,
        a: MlsdGeometry.Pt,
        ex: Double,
        ey: Double
    ): MlsdGeometry.Pt {
        val dx = q.x - p.x
        val dy = q.y - p.y
        val denom = ex * dy - ey * dx
        if (abs(denom) < 1e-12) return p
        val t = ((a.x - p.x) * ey - (a.y - p.y) * ex) / -denom
        return MlsdGeometry.Pt(p.x + t * dx, p.y + t * dy)
    }

    private fun signedArea(pts: List<MlsdGeometry.Pt>): Double {
        var s = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2.0
    }

    /** Clamp helper used by the detector when mapping the log line. */
    fun pct(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)

    /** One-line description of the active constants, emitted once per session in the log. */
    fun describe(): String = "merge(angle<=${ANGLE_TOL_DEG}deg len>=${MIN_LEN_FRAC}H " +
        "chord>=${CHORD_FRAC} strip=${MIN_STRIP_FRAC}..${MAX_STRIP_FRAC}" +
        "/spread<=${SPREAD_MAX_STRIP_FRAC}@asp>=${SPREAD_ASPECT_MIN} " +
        "floor=${REPAIR_MIN_STRIP_FRAC} select=prior+cutPenalty)"
}
