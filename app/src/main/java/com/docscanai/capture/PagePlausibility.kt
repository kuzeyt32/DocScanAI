package com.docscanai.capture

import android.graphics.Bitmap
import com.docscanai.core.util.Diag
import com.docscanai.core.util.TraceDump
import com.docscanai.data.Point
import com.docscanai.data.Quad
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * SUBTASK H13 — content-based **page-ness gate**.
 *
 * The detector only answers "is there a quadrilateral?"; it cannot tell a sheet of paper from a
 * cluttered desk, a wall, furniture or a person. That is the user-reported defect: on the corpus
 * **3 of 5 negatives** produced a quad *and* a neural dewarp, and because auto-capture is ON and
 * re-fires every 1,200 ms the camera produced a stream of bogus captures.
 *
 * This object scores the CONTENT inside the detected quad on the ORIGINAL frame. It deliberately
 * uses **no new model** — only cheap OpenCV operations on a ≤ [WORK_EDGE] px working copy — and
 * returns a breakdown so every verdict is auditable in logcat:
 *
 * ```
 * [Gate] id=still score=0.78 cov=0.52 asp=1.41 paper=0.83 text=0.71 edge=0.55 angle=0.96 ...
 * ```
 *
 * ### Signals (each normalised to 0..1)
 *  - **coverage**  — quad area / frame area. Full credit 0.20–0.85; zero below 0.10 or above 0.97.
 *  - **aspect**    — mean side ratio vs. the 1.41 paper aspect (soft Gaussian falloff).
 *  - **angle**     — all four interior corner angles inside 55°–125°.
 *  - **convexity** — convex and non-self-intersecting (hard requirement).
 *  - **paper**     — rectified interior DISTRIBUTION: a dominant flat mode (uniformity) + ink
 *                   contrast (dark-on-paper gap) + a sane ink fraction. SUBTASK H38: hue-agnostic
 *                   by construction — a DYED sheet (green/blue pads) is paper too, so nothing
 *                   here assumes "bright", "near-white" or "low saturation" (the old white-paper
 *                   tests were the measured root cause of dyed pages not being detected).
 *  - **text**      — horizontal projection profile of edges in the rectified interior: relative
 *                   variance, number of peaks, a *periodic* autocorrelation peak and the fraction
 *                   of rows in "text bands". This is the key discriminator against clutter.
 *  - **edge**      — edge-pixel density inside the quad must be in a sane band (not empty, not
 *                   chaotic noise).
 *
 * ### Decision
 * `passed = score >= THRESHOLD && text >= TEXT_FLOOR && convex && !selfIntersecting`, plus the
 * SUBTASK H21 rule below for quads that came from a heuristic fallback.
 *
 * The text floor is deliberate: clutter can be bright and low-saturation (a grey desk), but it does
 * not produce periodic horizontal text lines. A blank sheet of paper is the known cost of that
 * rule — manual shutter still works and is never gated.
 *
 * ### SUBTASK H21 — text-LINE coherence (the live-room fix)
 * The periodic-row signal above is necessary but not sufficient: the emulator's virtual room
 * (wood panelling, shelf boards, a checkerboard TV) is *also* periodic, and the real live chain
 * accepted it (`score≈0.75 text≈0.72 paper≈0.26 cov=1.000 pass=true`) because the classical Hough
 * fallback handed the gate a 46 %-area quad. Periodicity is not document text.
 *
 * Document text has **lines**: glyphs of near-uniform height sharing a baseline, spaced regularly,
 * spanning a large fraction of the page width. [lineCoherence] measures exactly that on the same
 * rectified ink mask the text signal already builds (no new image pass, no new model).
 *
 * [QuadProvenance.CLASSICAL_FALLBACK] quads are a *guess* (a Hough/contour construct), not a
 * detection, so they additionally require [FALLBACK_COHERENCE_MIN] line coherence to pass. A
 * corner-model quad keeps the original rule. Nothing here raises [THRESHOLD]: a room frame is
 * rejected on structure, not on a larger number.
 *
 * Threading: blocking, ~1–3 ms on a 320 px copy. Call from a worker thread (FrameAnalyzer's
 * analysis dispatcher / ScanPipeline's pipeline thread), never from the main thread.
 */
object PagePlausibility {

    /** Single acceptance threshold on the weighted score (tuned on the 25-image corpus set). */
    const val THRESHOLD: Double = 0.55

    /**
     * SUBTASK H21: minimum text-LINE coherence for a quad that came from the classical fallback
     * (or any other non-model source). Measured, not tuned to the fixture: real document text lines
     * score far above this, the emulator room far below (see `LiveRoomNegativeTest`).
     */
    const val FALLBACK_COHERENCE_MIN: Double = 0.38

    /** SUBTASK H21: same floor applied to auto-capture eligibility. */
    const val AUTO_CAPTURE_COHERENCE_MIN: Double = 0.38

    /**
     * Hard floor on the text-line signal. See the class KDoc: brightness/low saturation alone
     * cannot separate a grey desk from a page, periodic text rows can.
     */
    const val TEXT_FLOOR: Double = 0.30

    /** Working-copy long edge (px). Everything here runs on a copy at most this big. */
    private const val WORK_EDGE = 480

    /** Long edge of the rectified interior used for the paper / text analysis (px). */
    private const val RECT_LONG_EDGE = 300

    /** Fraction trimmed from each side of the rectified page to avoid warp-border artifacts. */
    private const val INTERIOR_MARGIN = 0.07

    /** Paper aspect ratio (A4/Letter ≈ 1.41). */
    private const val PAPER_ASPECT = 1.41

    /** Gaussian width of the aspect falloff. */
    private const val ASPECT_SIGMA = 0.55

    // --- signal weights (sum = 1.0) -------------------------------------------------------
    // The text-line signal carries the most weight: brightness/low saturation cannot separate a
    // grey cluttered desk (also bright and low-saturation) from a sheet of paper.
    private const val W_COVERAGE = 0.12
    private const val W_ASPECT = 0.08
    private const val W_ANGLE = 0.08
    private const val W_CONVEX = 0.04
    private const val W_PAPER = 0.14
    private const val W_TEXT = 0.48
    private const val W_EDGE = 0.06

    /** Immutable per-signal breakdown returned to callers/tests/logs. */
    data class Result(
        val passed: Boolean,
        val score: Double,
        val coverageScore: Double,
        val aspectScore: Double,
        val aspectRatio: Double,
        val angleScore: Double,
        val minAngleDeg: Double,
        val maxAngleDeg: Double,
        val convex: Boolean,
        val selfIntersecting: Boolean,
        val paperScore: Double,
        val textScore: Double,
        val edgeScore: Double,
        val coverageFrac: Double,
        val glyphCount: Int = 0,
        /** SUBTASK H21: structural text-line coherence (0..1). */
        val lineCoherence: Double = 0.0,
        /** SUBTASK H21 diagnostics: accepted text lines, median line span, glyph height/page height. */
        val lineCount: Int = 0,
        val lineSpan: Double = 0.0,
        val lineHeightFrac: Double = 0.0,
        /** SUBTASK H21: where the evaluated quad came from. */
        val provenance: QuadProvenance = QuadProvenance.UNKNOWN,
        /**
         * SUBTASK H21: may this frame be AUTO-captured? A full-frame substitute is never a
         * detection; everything else must first be `passed` (which already enforces line
         * coherence for fallback quads). The manual shutter ignores this flag by design.
         */
        val autoCaptureEligible: Boolean = false
    ) {
        /** The single `[Gate]` log line (also used verbatim by the gating tests). */
        fun logLine(id: String): String = String.format(
            Locale.US,
            "id=%s score=%.3f cov=%.3f asp=%.3f paper=%.3f text=%.3f edge=%.3f " +
                "glyphs=%d angle=%.3f minAng=%.0f maxAng=%.0f convex=%b selfInt=%b " +
                "coh=%.3f lines=%d span=%.2f hfrac=%.3f prov=%s auto=%b pass=%b",
            id, score, coverageScore, aspectScore, paperScore, textScore, edgeScore,
            glyphCount, angleScore, minAngleDeg, maxAngleDeg, convex, selfIntersecting,
            lineCoherence, lineCount, lineSpan, lineHeightFrac,
            provenance.name, autoCaptureEligible, passed
        )

        companion object {
            /** A hard-failure result (degenerate quad / bad geometry / non-convex). */
            fun hardFail(
                coverageScore: Double = 0.0,
                aspectScore: Double = 0.0,
                aspectRatio: Double = 0.0,
                angleScore: Double = 0.0,
                minAngleDeg: Double = 0.0,
                maxAngleDeg: Double = 0.0,
                convex: Boolean = false,
                selfIntersecting: Boolean = false,
                paperScore: Double = 0.0,
                textScore: Double = 0.0,
                edgeScore: Double = 0.0,
                coverageFrac: Double = 0.0
            ): Result = Result(
                passed = false,
                score = 0.0,
                coverageScore = coverageScore,
                aspectScore = aspectScore,
                aspectRatio = aspectRatio,
                angleScore = angleScore,
                minAngleDeg = minAngleDeg,
                maxAngleDeg = maxAngleDeg,
                convex = convex,
                selfIntersecting = selfIntersecting,
                paperScore = paperScore,
                textScore = textScore,
                edgeScore = edgeScore,
                coverageFrac = coverageFrac
            )
        }
    }

    /**
     * Evaluates page-ness for [quad] inside [source] (quad in [source]'s pixel space).
     *
     * @param id short label used in the `[Gate]` log line (e.g. image id / `live` / `still`).
     * @param provenance SUBTASK H21: where [quad] came from (`PageDetector.lastProvenance`). A
     *   fallback construct must additionally prove text-LINE coherence; the default keeps legacy
     *   callers behavioural-neutral.
     * @param requireLineProof SUBTASK H24: whether the text-line proof is part of the `passed`
     *   verdict. It is an **auto-capture** requirement — a guess may only be captured without a
     *   human in the loop when the content independently proves document text — so the LIVE gate
     *   (`FrameAnalyzer`, default `true`) enforces it. The POST-SHUTTER still gate
     *   (`ScanPipeline`) passes `false`: the user already pressed the shutter, and the line proof
     *   was never meant to veto warping of a detector quad whose score/content gate passed. The
     *   `autoCaptureEligible` flag keeps demanding coherence for guessed quads regardless.
     * @return the [Result]; never throws. A failure of the analysis itself returns `passed=false`
     *   (fail-closed: we never auto-capture what we could not analyse).
     */
    fun evaluate(
        source: Bitmap,
        quad: Quad,
        id: String = "frame",
        provenance: QuadProvenance = QuadProvenance.UNKNOWN,
        requireLineProof: Boolean = true
    ): Result {
        val result = try {
            evaluateInternal(source, quad, provenance, requireLineProof)
        } catch (t: Throwable) {
            Diag.logThrowable("Gate", t)
            Result.hardFail()
        }
        // Exactly one log line per evaluation (the corpus/test lanes parse it).
        Diag.log("Gate", result.logLine(id))
        // SUBTASK H39: record the verdict + quad in the open trace scene (no-op in production).
        TraceDump.current()?.let { trace ->
            runCatching {
                trace.put("gate_id", id)
                trace.put("gate_passed", result.passed)
                trace.put("gate_score", result.score)
                trace.put("auto_capture_eligible", result.autoCaptureEligible)
                trace.put("gate_provenance", provenance.name)
                trace.put("require_line_proof", requireLineProof)
                val q = org.json.JSONArray()
                quad.points.forEach { p ->
                    q.put(org.json.JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()))
                }
                trace.put("gate_quad", q)
                trace.put("gate_log", result.logLine(id))
            }
        }
        return result
    }

    private fun evaluateInternal(
        source: Bitmap,
        quad: Quad,
        provenance: QuadProvenance,
        requireLineProof: Boolean
    ): Result {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            return Result.hardFail()
        }
        val pts = quad.points
        if (pts.size != 4 || pts.any { !it.x.isFinite() || !it.y.isFinite() }) {
            return Result.hardFail()
        }

        // --- geometry signals (scale-independent) ------------------------------------------
        val convex = isConvex(pts)
        val selfInt = selfIntersects(pts)
        val angles = interiorAngles(pts)
        val minAng = angles.minOrNull() ?: 0.0
        val maxAng = angles.maxOrNull() ?: 0.0
        val angleScore = when {
            minAng >= MIN_CORNER_DEG && maxAng <= MAX_CORNER_DEG -> 1.0
            else -> {
                val dev = max(
                    max(0.0, MIN_CORNER_DEG - minAng),
                    max(0.0, maxAng - MAX_CORNER_DEG)
                )
                (1.0 - dev / ANGLE_FALLOFF_DEG).coerceIn(0.0, 1.0)
            }
        }

        val sideH = (dist(quad.topLeft, quad.topRight) + dist(quad.bottomLeft, quad.bottomRight)) / 2.0
        val sideV = (dist(quad.topLeft, quad.bottomLeft) + dist(quad.topRight, quad.bottomRight)) / 2.0
        val aspectRatio = if (min(sideH, sideV) <= 1e-6) 99.0 else max(sideH, sideV) / min(sideH, sideV)
        val aspectScore = exp(-0.5 * sq((aspectRatio - PAPER_ASPECT) / ASPECT_SIGMA))
            .coerceIn(0.0, 1.0)

        val coverageFrac = polygonArea(pts) / (source.width.toDouble() * source.height.toDouble())
        val coverageScore = when {
            coverageFrac < MIN_COV || coverageFrac > MAX_COV -> 0.0
            coverageFrac < FULL_COV_LO -> (coverageFrac - MIN_COV) / (FULL_COV_LO - MIN_COV)
            coverageFrac <= FULL_COV_HI -> 1.0
            else -> (MAX_COV - coverageFrac) / (MAX_COV - FULL_COV_HI)
        }.coerceIn(0.0, 1.0)

        // Hard geometry requirements: a non-convex / crossed "quad" is never a page, and neither is
        // a quad with an interior corner outside 55°–125° (the spec's angle-sanity rule). A page
        // seen at a sane angle cannot have a 41° corner; in the corpus this rule is what separates
        // the remaining scene photo from every one of the 71 page-like images.
        if (!convex || selfInt ||
            minAng < MIN_CORNER_DEG || maxAng > MAX_CORNER_DEG
        ) {
            return Result.hardFail(
                coverageScore = coverageScore, aspectScore = aspectScore, aspectRatio = aspectRatio,
                angleScore = angleScore, minAngleDeg = minAng, maxAngleDeg = maxAng,
                convex = convex, selfIntersecting = selfInt, coverageFrac = coverageFrac
            )
        }

        // --- content signals on a small working copy ----------------------------------------
        val longEdge = max(source.width, source.height)
        val scale = if (longEdge > WORK_EDGE) WORK_EDGE.toDouble() / longEdge else 1.0
        val ww = (source.width * scale).roundToInt().coerceAtLeast(1)
        val wh = (source.height * scale).roundToInt().coerceAtLeast(1)

        var workOwned: Bitmap? = null
        var argbOwned: Bitmap? = null
        try {
            val scaled: Bitmap = if (scale < 1.0) {
                Bitmap.createScaledBitmap(source, ww, wh, true).also { workOwned = it }
            } else {
                source
            }
            val argb: Bitmap = if (scaled.config == Bitmap.Config.ARGB_8888) {
                scaled
            } else {
                scaled.copy(Bitmap.Config.ARGB_8888, false)?.also { argbOwned = it } ?: scaled
            }
            val workQuad = Quad(
                topLeft = Point((quad.topLeft.x * scale).toFloat(), (quad.topLeft.y * scale).toFloat()),
                topRight = Point((quad.topRight.x * scale).toFloat(), (quad.topRight.y * scale).toFloat()),
                bottomRight = Point((quad.bottomRight.x * scale).toFloat(), (quad.bottomRight.y * scale).toFloat()),
                bottomLeft = Point((quad.bottomLeft.x * scale).toFloat(), (quad.bottomLeft.y * scale).toFloat())
            )

            val rgba = Mat()
            try {
                Utils.bitmapToMat(argb, rgba)
                val content = analyseRectifiedInterior(rgba, workQuad)
                val score = (
                    W_COVERAGE * coverageScore +
                        W_ASPECT * aspectScore +
                        W_ANGLE * angleScore +
                        W_CONVEX * 1.0 +
                        W_PAPER * content.paper +
                        W_TEXT * content.text +
                        W_EDGE * content.edge
                    ).coerceIn(0.0, 1.0)
                // SUBTASK H21: a fallback quad is a guess. It may only be treated as a page when the
                // CONTENT independently shows text lines (near-uniform glyph height, shared
                // baseline, regular spacing, wide lines). Model-detected quads keep the H13 rule.
                val isGuessedQuad = provenance == QuadProvenance.CLASSICAL_FALLBACK ||
                    provenance == QuadProvenance.FULL_FRAME
                // SUBTASK H24: the proof gates the LIVE/auto-capture verdict; the post-shutter
                // still path opts out (see `requireLineProof`). A CORNER_MODEL quad never needs it.
                val needsLineProof = requireLineProof && isGuessedQuad
                val lineProofOk =
                    !needsLineProof || content.coherence >= FALLBACK_COHERENCE_MIN
                val passed = score >= THRESHOLD && content.text >= TEXT_FLOOR && lineProofOk
                // A whole-frame substitute is never a detection, so it is never auto-captured
                // (the manual shutter still works: it does not consult this flag). Auto-capture on
                // a GUESS always needs the stronger coherence proof, even if `passed` skipped it.
                val autoCoherenceOk =
                    !isGuessedQuad || content.coherence >= AUTO_CAPTURE_COHERENCE_MIN
                val autoEligible = passed &&
                    provenance != QuadProvenance.FULL_FRAME &&
                    autoCoherenceOk
                return Result(
                    passed = passed,
                    score = score,
                    coverageScore = coverageScore,
                    aspectScore = aspectScore,
                    aspectRatio = aspectRatio,
                    angleScore = angleScore,
                    minAngleDeg = minAng,
                    maxAngleDeg = maxAng,
                    convex = convex,
                    selfIntersecting = selfInt,
                    paperScore = content.paper,
                    textScore = content.text,
                    edgeScore = content.edge,
                    coverageFrac = coverageFrac,
                    glyphCount = content.glyphs,
                    lineCoherence = content.coherence,
                    lineCount = content.lineCount,
                    lineSpan = content.lineSpan,
                    lineHeightFrac = content.lineHeightFrac,
                    provenance = provenance,
                    autoCaptureEligible = autoEligible
                )
            } finally {
                rgba.release()
            }
        } finally {
            argbOwned?.let { b -> runCatching { if (!b.isRecycled) b.recycle() } }
            workOwned?.let { b -> runCatching { if (!b.isRecycled) b.recycle() } }
        }
    }

    /** Paper / text / edge signals measured on the perspective-rectified quad interior. */
    private class Content(
        val paper: Double,
        val text: Double,
        val edge: Double,
        val glyphs: Int,
        /** SUBTASK H21: text-line coherence of the glyph components (0..1). */
        val coherence: Double,
        /** SUBTASK H21 diagnostics: accepted lines, median line span, glyph height / page height. */
        val lineCount: Int = 0,
        val lineSpan: Double = 0.0,
        val lineHeightFrac: Double = 0.0
    )

    /**
     * SUBTASK H21: the measured text-line structure behind [Content.coherence]. The extra fields
     * exist so the decision rule can be justified from data (`[Gate] ... lines= span= hfrac=`)
     * instead of a hard-coded feel.
     */
    private data class LineProof(
        val score: Double = 0.0,
        val lines: Int = 0,
        val span: Double = 0.0,
        val heightFrac: Double = 0.0
    )

    private fun analyseRectifiedInterior(rgba: Mat, quad: Quad): Content {
        val pageW = (dist(quad.topLeft, quad.topRight) + dist(quad.bottomLeft, quad.bottomRight)) / 2.0
        val pageH = (dist(quad.topLeft, quad.bottomLeft) + dist(quad.topRight, quad.bottomRight)) / 2.0
        if (pageW < 8.0 || pageH < 8.0) return Content(0.0, 0.0, 0.0, 0, 0.0)

        val long = max(pageW, pageH)
        val s = RECT_LONG_EDGE / long
        val pw = (pageW * s).roundToInt().coerceIn(16, RECT_LONG_EDGE)
        val ph = (pageH * s).roundToInt().coerceIn(16, RECT_LONG_EDGE)

        val srcPts = MatOfPoint2f(
            CvPoint(quad.topLeft.x.toDouble(), quad.topLeft.y.toDouble()),
            CvPoint(quad.topRight.x.toDouble(), quad.topRight.y.toDouble()),
            CvPoint(quad.bottomRight.x.toDouble(), quad.bottomRight.y.toDouble()),
            CvPoint(quad.bottomLeft.x.toDouble(), quad.bottomLeft.y.toDouble())
        )
        val dstPts = MatOfPoint2f(
            CvPoint(0.0, 0.0), CvPoint((pw - 1).toDouble(), 0.0),
            CvPoint((pw - 1).toDouble(), (ph - 1).toDouble()), CvPoint(0.0, (ph - 1).toDouble())
        )
        val rectColor = Mat()
        val rectGray = Mat()
        var transform: Mat? = null
        try {
            transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            Imgproc.warpPerspective(
                rgba, rectColor, transform, Size(pw.toDouble(), ph.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE
            )
            Imgproc.cvtColor(rectColor, rectGray, Imgproc.COLOR_RGBA2GRAY)

            val iy0 = (ph * INTERIOR_MARGIN).roundToInt().coerceIn(0, ph - 8)
            val ix0 = (pw * INTERIOR_MARGIN).roundToInt().coerceIn(0, pw - 8)
            val iy1 = (ph - (ph * INTERIOR_MARGIN).roundToInt()).coerceIn(iy0 + 8, ph)
            val ix1 = (pw - (pw * INTERIOR_MARGIN).roundToInt()).coerceIn(ix0 + 8, pw)
            val roiGray = rectGray.submat(iy0, iy1, ix0, ix1)

            val paper: Double
            val text: Double
            val edge: Double
            var glyphs = 0
            var proof = LineProof()
            try {
                // 32-bin histogram of the interior luminance (distribution SHAPE only).
                val bins = IntArray(HIST_BINS)
                val rows = roiGray.rows()
                val cols = roiGray.cols()
                val bytes = ByteArray(rows * cols)
                roiGray.get(0, 0, bytes)
                for (v in bytes) {
                    val g = v.toInt() and 0xFF
                    bins[(g * HIST_BINS / 256).coerceIn(0, HIST_BINS - 1)]++
                }
                var modeBin = 0
                var modeCount = 0
                for (i in bins.indices) if (bins[i] > modeCount) { modeCount = bins[i]; modeBin = i }
                var peakCount = 0
                for (i in max(0, modeBin - 1)..min(HIST_BINS - 1, modeBin + 1)) peakCount += bins[i]
                val peakFrac = peakCount.toDouble() / (rows * cols).toDouble()
                val modeCenter = (modeBin + 0.5) * 256.0 / HIST_BINS

                // SUBTASK H38 — hue-agnostic paper test (the root cause of "non-white pages are
                // not detected": every old term assumed near-white paper — absolute brightness,
                // low saturation, bright histogram mode — so a green sheet scored like clutter).
                // Paper is now judged ONLY by interior distribution shape and ink-vs-paper
                // contrast, all relative to the interior's own histogram:
                //  1. uniformity  — a dominant flat mode (the paper cluster) inside a sane peak
                //                   band (not empty gloss, not noise);
                //  2. ink contrast — the mode sits clearly above the dark ink tail;
                //  3. ink band     — a modest dark-on-paper fraction (text/tables; the blank and
                //                   chaos tails lose credit).
                fun pct(frac: Double): Double {
                    var acc = 0
                    val target = (rows * cols * frac).toInt()
                    for (i in bins.indices) {
                        acc += bins[i]
                        if (acc >= target) return (i + 0.5) * 256.0 / HIST_BINS
                    }
                    return 255.0
                }
                val p10 = pct(0.10)
                val p50 = pct(0.50)
                val p90 = pct(0.90)
                val spread = (p90 - p10).coerceAtLeast(6.0)
                val modeScore = ((peakFrac - PEAK_LO) / (PEAK_HI - PEAK_LO)).coerceIn(0.0, 1.0)
                val contrastScore = (
                    ((modeCenter - p10) / spread - INK_CONTRAST_LO) /
                        (INK_CONTRAST_HI - INK_CONTRAST_LO)
                    ).coerceIn(0.0, 1.0)
                var inkCount = 0
                val inkCut = p50 - 0.45 * spread
                for (i in bins.indices) {
                    val v = (i + 0.5) * 256.0 / HIST_BINS
                    if (v < inkCut) inkCount += bins[i]
                }
                val inkFrac = inkCount.toDouble() / (rows * cols).toDouble()
                val inkBand = (1.0 - abs(inkFrac - INK_FRAC_MID) / INK_FRAC_TOL).coerceIn(0.0, 1.0)
                paper = (
                    0.45 * modeScore +
                        0.35 * contrastScore +
                        0.20 * inkBand
                    ).coerceIn(0.0, 1.0)

                // Edge density (Canny) for the "sane band" signal, plus an INK mask for the text
                // structure. The ink mask (adaptive threshold, inverted) is the key discriminator:
                // glyphs on a sheet break into MANY small connected components, while clutter
                // lines / filled rectangles produce a few very large ones.
                val edges = Mat()
                val ink = Mat()
                val labels = Mat()
                val stats = Mat()
                val centroids = Mat()
                var profileMat: Mat? = null
                try {
                    Imgproc.Canny(roiGray, edges, CANNY_LO, CANNY_HI)
                    val edgePixels = Core.countNonZero(edges).toDouble()
                    val edgeFrac = edgePixels / (rows * cols).toDouble()
                    edge = edgeBandScore(edgeFrac)

                    Imgproc.adaptiveThreshold(
                        roiGray, ink, 255.0,
                        Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV,
                        ADAPT_BLOCK, ADAPT_C
                    )
                    profileMat = Mat()
                    Core.reduce(ink, profileMat, 1, Core.REDUCE_SUM, CvType.CV_32S)
                    val sums = IntArray(rows)
                    profileMat.get(0, 0, sums)
                    val profile = DoubleArray(rows) { sums[it] / 255.0 }

                    // SUBTASK H21: the SAME connected components now double as the text-line
                    // evidence; no extra image pass is added to the per-frame budget.
                    val boxes = collectGlyphs(ink, labels, stats, centroids)
                    glyphs = boxes.size
                    text = textStructureScore(profile, glyphs)
                    proof = lineCoherence(boxes, pw, pageH)

                    // SUBTASK H39 — the INK EVIDENCE, before any pass/fail decision: the
                    // rectified page, the interior ROI it is scored on, the adaptive-threshold
                    // ink mask, the Canny edges, and the glyph components that feed the text /
                    // line-coherence scores. This is the map to inspect when a page "looks like a
                    // page" but the gate disagrees.
                    val trace = TraceDump.current()
                    if (trace != null) {
                        runCatching {
                            trace.mat("page_rectified", rectColor)
                            trace.mat("page_gray", rectGray)
                            trace.mat("page_interior", roiGray)
                            trace.mat("page_ink", ink)
                            trace.mat("page_edges", edges)
                            val glyphRgba: Mat = Mat()
                            try {
                                Imgproc.cvtColor(ink, glyphRgba, Imgproc.COLOR_GRAY2RGBA)
                                for (b in boxes) {
                                    Imgproc.rectangle(
                                        glyphRgba,
                                        CvPoint(b.x.toDouble(), b.y.toDouble()),
                                        CvPoint((b.x + b.w).toDouble(), (b.y + b.h).toDouble()),
                                        Scalar(255.0, 40.0, 40.0, 255.0),
                                        1
                                    )
                                }
                                trace.mat("page_glyphs", glyphRgba)
                            } finally {
                                glyphRgba.release()
                            }
                            val section = trace.section("gate")
                            section.put("rect_w", pw)
                            section.put("rect_h", ph)
                            section.put("roi_x0", ix0)
                            section.put("roi_y0", iy0)
                            section.put("paper", paper)
                            section.put("text", text)
                            section.put("edge", edge)
                            section.put("glyphs", glyphs)
                            section.put("line_coherence", proof.score)
                            section.put("line_count", proof.lines)
                            section.put("line_span", proof.span)
                            section.put("line_height_frac", proof.heightFrac)
                            section.put("mode_center", modeCenter)
                            section.put("p10", p10)
                            section.put("p50", p50)
                            section.put("p90", p90)
                            section.put("ink_frac", inkFrac)
                            section.put("edge_frac", edgeFrac)
                        }.onFailure {
                            Diag.warn("Trace", "gate trace dump failed: ${it.message}")
                        }
                    }
                } finally {
                    profileMat?.release()
                    edges.release()
                    ink.release()
                    labels.release()
                    stats.release()
                    centroids.release()
                }
            } finally {
                roiGray.release()
            }
            return Content(
                paper, text, edge, glyphs, proof.score, proof.lines, proof.span, proof.heightFrac
            )
        } finally {
            transform?.release()
            srcPts.release()
            dstPts.release()
            rectColor.release()
            rectGray.release()
        }
    }

    /** One glyph-like ink component at the rectified page scale. */
    private data class GlyphBox(val x: Int, val y: Int, val w: Int, val h: Int) {
        val cy: Double get() = y + h / 2.0
    }

    /**
     * **Glyph-like** ink components: small, compact blobs. Text produces tens to hundreds of these;
     * clutter lines/rectangle fills produce a handful of very large components (filtered out here),
     * which is what separates a page from a desk. The surviving boxes also feed [lineCoherence], so
     * the H21 signal costs no extra pass over the image.
     */
    private fun collectGlyphs(ink: Mat, labels: Mat, stats: Mat, centroids: Mat): List<GlyphBox> {
        val count = Imgproc.connectedComponentsWithStats(
            ink, labels, stats, centroids, 8, CvType.CV_32S
        )
        if (count <= 1) return emptyList()
        val maxWidth = (ink.cols() * 0.5).toInt()
        val out = ArrayList<GlyphBox>()
        val row = IntArray(5)
        for (i in 1 until count) {
            stats.get(i, 0, row)
            val x = row[0]
            val y = row[1]
            val w = row[2]
            val h = row[3]
            val area = row[4]
            if (w < 2 || h < 2 || w > maxWidth) continue
            if (area < GLYPH_MIN_AREA || area > GLYPH_MAX_AREA) continue
            if (w > GLYPH_MAX_W || h > GLYPH_MAX_H) continue
            out.add(GlyphBox(x, y, w, h))
        }
        return out
    }

    /**
     * SUBTASK H21 — **text-line coherence** (0..1).
     *
     * Document text is not merely periodic: its glyphs form LINES. This groups glyph boxes into
     * lines by vertical centre, then scores each line on
     *  - **height uniformity** (glyphs on a printed line share one x-height / cap-height),
     *  - **baseline tightness** (their centres sit on one baseline), and
     *  - **span** (a text line crosses a large fraction of the page width),
     * and finally rewards **regular line spacing** across the page.
     *
     * Room clutter (wood panelling, shelf boards, checkerboards) is periodic too, but its
     * connected components differ wildly in height and do not form several wide, evenly spaced
     * lines — it scores near zero. Pure Kotlin over at most a few hundred boxes: < 0.2 ms.
     */
    private fun lineCoherence(boxes: List<GlyphBox>, pageW: Int, pageH: Double): LineProof {
        if (boxes.size < LINE_MIN_GLYPHS || pageW <= 0) return LineProof()

        val medianH = median(boxes.map { it.h.toDouble() }).coerceAtLeast(1.0)
        val sorted = boxes.sortedBy { it.cy }

        // Group into lines: a box joins the current line while its centre stays within half a
        // typical glyph height of that line's running centre.
        val lines = ArrayList<MutableList<GlyphBox>>()
        var current = ArrayList<GlyphBox>()
        var sumCy = 0.0
        for (box in sorted) {
            val centre = if (current.isEmpty()) box.cy else sumCy / current.size
            if (current.isNotEmpty() && abs(box.cy - centre) > LINE_CENTRE_TOL * medianH) {
                lines.add(current)
                current = ArrayList()
                sumCy = 0.0
            }
            current.add(box)
            sumCy += box.cy
        }
        if (current.isNotEmpty()) lines.add(current)

        // Score each line that carries enough glyphs.
        val scored = ArrayList<DoubleArray>() // score, mean centre-y, span, mean glyph height
        for (line in lines) {
            if (line.size < LINE_MIN_GLYPHS_PER_LINE) continue
            val heights = line.map { it.h.toDouble() }
            val meanH = heights.average()
            if (meanH < 1.0) continue
            val hCv = stdDev(heights, meanH) / meanH
            val heightUniformity = (1.0 - hCv / LINE_HEIGHT_CV_MAX).coerceIn(0.0, 1.0)

            val centres = line.map { it.cy }
            val meanCy = centres.average()
            val baseSpread = stdDev(centres, meanCy) / meanH
            val baselineScore = (1.0 - baseSpread / LINE_BASELINE_SPREAD_MAX).coerceIn(0.0, 1.0)

            val left = line.minOf { it.x.toDouble() }
            val right = line.maxOf { (it.x + it.w).toDouble() }
            val span = (right - left) / pageW
            val spanScore = ((span - LINE_SPAN_LO) / (LINE_SPAN_HI - LINE_SPAN_LO)).coerceIn(0.0, 1.0)

            val score = 0.45 * heightUniformity + 0.30 * baselineScore + 0.25 * spanScore
            if (score >= LINE_MIN_SCORE) scored.add(doubleArrayOf(score, meanCy, span, meanH))
        }
        if (scored.isEmpty()) return LineProof()

        val best = scored.sortedByDescending { it[0] }.take(LINE_COUNT_TARGET)
        val support = best.size / LINE_COUNT_TARGET.toDouble()

        // Regular line spacing needs at least three accepted lines.
        val spacingScore = if (best.size >= 3) {
            val cents = best.map { it[1] }.sorted()
            val gaps = (1 until cents.size).map { cents[it] - cents[it - 1] }
            val meanGap = gaps.average()
            if (meanGap <= 1.0) {
                0.0
            } else {
                (1.0 - stdDev(gaps, meanGap) / meanGap / LINE_SPACING_CV_MAX).coerceIn(0.0, 1.0)
            }
        } else {
            0.2
        }

        // A page's text block is MANY lines. Four accidentally aligned shelf edges / panel rows
        // are not a text block, so before any of this counts as proof the fallback path must see
        // a minimum number of accepted lines AND a wide median span. (Measured on the H21 live
        // fixture: the eight room frames that reach this code produce 0–4 lines, the best being
        // lines=4 span=0.73 — i.e. it stopped exactly at the LINE_COUNT_TARGET cap.)
        val spanMed = median(best.map { it[2] })
        val heightFrac = median(best.map { it[3] }) / pageH.coerceAtLeast(1.0)
        if (best.size < LINE_MIN_ACCEPTED || spanMed < LINE_SPAN_PROOF_MIN) {
            return LineProof(0.0, best.size, spanMed, heightFrac)
        }

        val meanLine = best.map { it[0] }.average()
        val score = (support * (0.72 * meanLine + 0.28 * spacingScore)).coerceIn(0.0, 1.0)
        return LineProof(score = score, lines = best.size, span = spanMed, heightFrac = heightFrac)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun stdDev(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        for (v in values) sum += sq(v - mean)
        return sqrt(sum / values.size)
    }

    /**
     * Text-line structure: (a) many glyph-like ink components, (b) periodic peaks in the row
     * projection, (c) a high relative variance, and (d) many rows inside "bands". The projection
     * terms MODULATE the glyph term instead of standing alone — random clutter lines can form
     * peaks, but they cannot form hundreds of small glyph components.
     */
    private fun textStructureScore(profile: DoubleArray, glyphs: Int): Double {
        val n = profile.size
        if (n < 16 || glyphs <= 0) return 0.0
        val mean = profile.average()
        if (mean <= 0.5) return 0.0
        var varSum = 0.0
        for (v in profile) varSum += sq(v - mean)
        val sd = sqrt(varSum / n)
        val cv = sd / mean

        val cvScore = ((cv - CV_LO) / (CV_HI - CV_LO)).coerceIn(0.0, 1.0)

        // Peaks: local maxima above 1.6×mean, at least PEAK_SEPARATION rows apart.
        var peaks = 0
        var lastPeak = -PEAK_SEPARATION
        for (i in 2 until n - 2) {
            val v = profile[i]
            if (v <= mean * PEAK_RATIO) continue
            if (v >= profile[i - 1] && v >= profile[i + 1] &&
                v >= profile[i - 2] && v >= profile[i + 2] && i - lastPeak >= PEAK_SEPARATION
            ) {
                peaks++
                lastPeak = i
            }
        }
        val peakScore = (peaks / PEAK_TARGET.toDouble()).coerceIn(0.0, 1.0)

        val bandRows = profile.count { it > mean * BAND_RATIO }
        val bandScore = ((bandRows.toDouble() / n - BAND_LO) / (BAND_HI - BAND_LO)).coerceIn(0.0, 1.0)

        val corr = maxAutocorrelation(profile, mean)
        val corrScore = ((corr - CORR_LO) / (CORR_HI - CORR_LO)).coerceIn(0.0, 1.0)

        val projection = (
            0.30 * cvScore +
                0.25 * peakScore +
                0.25 * bandScore +
                0.20 * corrScore
            ).coerceIn(0.0, 1.0)
        val glyphScore = (glyphs / GLYPH_TARGET.toDouble()).coerceIn(0.0, 1.0)
        return (glyphScore * (0.55 + 0.45 * projection)).coerceIn(0.0, 1.0)
    }

    /** Strongest normalised autocorrelation of the row profile for lags 3..48. */
    private fun maxAutocorrelation(profile: DoubleArray, mean: Double): Double {
        val n = profile.size
        val maxLag = min(MAX_LAG, n / 3)
        if (maxLag < 3) return 0.0
        var best = 0.0
        for (lag in 3..maxLag) {
            var sxy = 0.0
            var sxx = 0.0
            var syy = 0.0
            for (i in 0 until n - lag) {
                val x = profile[i] - mean
                val y = profile[i + lag] - mean
                sxy += x * y
                sxx += x * x
                syy += y * y
            }
            if (sxx > 1e-9 && syy > 1e-9) {
                val c = sxy / sqrt(sxx * syy)
                if (c > best) best = c
            }
        }
        return best
    }

    /** Edge-density band: 1.0 inside the sane range, falling off to 0 for empty / chaotic. */
    private fun edgeBandScore(frac: Double): Double = when {
        frac < EDGE_MIN_LO || frac > EDGE_MAX_HI -> 0.0
        frac < EDGE_MIN_HI -> (frac - EDGE_MIN_LO) / (EDGE_MIN_HI - EDGE_MIN_LO)
        frac <= EDGE_MAX_LO -> 1.0
        else -> (EDGE_MAX_HI - frac) / (EDGE_MAX_HI - EDGE_MAX_LO)
    }.coerceIn(0.0, 1.0)

    // ==========================================================================================
    // Geometry helpers (pure Kotlin)
    // ==========================================================================================

    private fun dist(a: Point, b: Point): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun sq(v: Double): Double = v * v

    private fun polygonArea(pts: List<Point>): Double {
        var sum = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
        }
        return abs(sum) / 2.0
    }

    /** All four interior angles in degrees (TL, TR, BR, BL). */
    private fun interiorAngles(pts: List<Point>): List<Double> =
        pts.indices.map { i ->
            val prev = pts[(i + pts.size - 1) % pts.size]
            val cur = pts[i]
            val next = pts[(i + 1) % pts.size]
            val v1x = (prev.x - cur.x).toDouble()
            val v1y = (prev.y - cur.y).toDouble()
            val v2x = (next.x - cur.x).toDouble()
            val v2y = (next.y - cur.y).toDouble()
            val n1 = sqrt(v1x * v1x + v1y * v1y)
            val n2 = sqrt(v2x * v2x + v2y * v2y)
            if (n1 <= 1e-9 || n2 <= 1e-9) 0.0
            else Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1.0, 1.0)))
        }

    /** True when the quad is consistently wound (all cross products share a sign). */
    private fun isConvex(pts: List<Point>): Boolean {
        if (pts.size != 4) return false
        var positive = 0
        var negative = 0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x).toDouble() * (c.y - b.y).toDouble() -
                (b.y - a.y).toDouble() * (c.x - b.x).toDouble()
            if (cross > 1e-6) positive++ else if (cross < -1e-6) negative++
        }
        return positive == 4 || negative == 4
    }

    /** True when the two non-adjacent edge pairs cross. */
    private fun selfIntersects(pts: List<Point>): Boolean {
        if (pts.size != 4) return true
        return segmentsIntersect(pts[0], pts[1], pts[2], pts[3]) ||
            segmentsIntersect(pts[1], pts[2], pts[3], pts[0])
    }

    private fun segmentsIntersect(a: Point, b: Point, c: Point, d: Point): Boolean {
        fun orient(p: Point, q: Point, r: Point): Double =
            (q.x - p.x).toDouble() * (r.y - p.y).toDouble() -
                (q.y - p.y).toDouble() * (r.x - p.x).toDouble()

        val o1 = orient(a, b, c)
        val o2 = orient(a, b, d)
        val o3 = orient(c, d, a)
        val o4 = orient(c, d, b)
        return (o1 > 1e-9 && o2 < -1e-9 || o1 < -1e-9 && o2 > 1e-9) &&
            (o3 > 1e-9 && o4 < -1e-9 || o3 < -1e-9 && o4 > 1e-9)
    }

    // --- geometry / content tunables -----------------------------------------------------
    private const val MIN_CORNER_DEG = 55.0
    private const val MAX_CORNER_DEG = 125.0
    private const val ANGLE_FALLOFF_DEG = 35.0

    private const val MIN_COV = 0.10
    private const val FULL_COV_LO = 0.20
    private const val FULL_COV_HI = 0.85
    private const val MAX_COV = 0.97

    private const val HIST_BINS = 32
    private const val PEAK_LO = 0.12
    private const val PEAK_HI = 0.45

    /** SUBTASK H38: hue-agnostic ink-contrast band (mode-vs-ink-tail gap / interior spread). */
    private const val INK_CONTRAST_LO = 0.35
    private const val INK_CONTRAST_HI = 0.75

    /** SUBTASK H38: ink-fraction band (dark-on-paper share of the interior) around [INK_FRAC_MID]. */
    private const val INK_FRAC_MID = 0.08
    private const val INK_FRAC_TOL = 0.16

    private const val CANNY_LO = 50.0
    private const val CANNY_HI = 150.0

    /** Adaptive-threshold block (odd) and constant for the ink mask. */
    private const val ADAPT_BLOCK = 25
    private const val ADAPT_C = 10.0

    /** Glyph-component acceptance band (px / px²) at the rectified page scale. */
    private const val GLYPH_MIN_AREA = 4
    private const val GLYPH_MAX_AREA = 600
    private const val GLYPH_MAX_W = 60
    private const val GLYPH_MAX_H = 45

    /** Glyph count that earns full text credit. */
    private const val GLYPH_TARGET = 50.0

    // --- SUBTASK H21: text-LINE coherence tunables -------------------------------------------
    /** Minimum glyph boxes before line structure is even considered. */
    private const val LINE_MIN_GLYPHS = 8

    /** Minimum glyphs on one line for it to count as a text line. */
    private const val LINE_MIN_GLYPHS_PER_LINE = 3

    /** Maximum glyph-height coefficient of variation for full height-uniformity credit. */
    private const val LINE_HEIGHT_CV_MAX = 0.55

    /** Maximum baseline spread (in units of mean glyph height) for full baseline credit. */
    private const val LINE_BASELINE_SPREAD_MAX = 0.45

    /** Fraction of page width where line span starts / finishes earning credit. */
    private const val LINE_SPAN_LO = 0.20
    private const val LINE_SPAN_HI = 0.75

    /** A line must reach this combined score to count. */
    private const val LINE_MIN_SCORE = 0.55

    /**
     * SUBTASK H21: a fallback quad must show at least this many accepted lines before the
     * structure counts as a text block. A real page has many; periodic room clutter tops out at
     * the handful of rows it happens to align.
     */
    private const val LINE_MIN_ACCEPTED = 5

    /** SUBTASK H21: median line span (fraction of page width) a text block must reach. */
    private const val LINE_SPAN_PROOF_MIN = 0.45

    /** Lines needed for full support (a page of text has many; 4 already proves structure). */
    private const val LINE_COUNT_TARGET = 4

    /** Centre tolerance when grouping boxes into a line (multiples of median glyph height). */
    private const val LINE_CENTRE_TOL = 0.55

    /** Gap coefficient of variation tolerated before spacing regularity reaches zero. */
    private const val LINE_SPACING_CV_MAX = 0.90

    private const val CV_LO = 0.35
    private const val CV_HI = 0.95
    private const val PEAK_RATIO = 1.6
    private const val PEAK_SEPARATION = 3
    private const val PEAK_TARGET = 8.0
    private const val BAND_RATIO = 1.25
    private const val BAND_LO = 0.12
    private const val BAND_HI = 0.37
    private const val MAX_LAG = 48
    private const val CORR_LO = 0.25
    private const val CORR_HI = 0.60

    private const val EDGE_MIN_LO = 0.005
    private const val EDGE_MIN_HI = 0.030
    private const val EDGE_MAX_LO = 0.160
    private const val EDGE_MAX_HI = 0.350
}
