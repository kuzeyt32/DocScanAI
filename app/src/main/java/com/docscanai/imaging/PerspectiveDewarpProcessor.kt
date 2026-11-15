package com.docscanai.imaging

import android.graphics.Bitmap
import android.util.Log
import com.docscanai.core.util.Diag
import com.docscanai.data.Point
import com.docscanai.data.Quad
import com.docscanai.ml.OnnxModel
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Real perspective + dewarp / flatten processor (TECHNICAL_SPEC.md §4.2, ARCHITECTURE.md §3).
 *
 * ## Stage order (best-available, always leaves a working result)
 *  1. **Perspective correction** (ALWAYS) — `getPerspectiveTransform` + `warpPerspective` from the
 *     ordered quad (TL, TR, BR, BL) to an axis-aligned rectangle sized from the mean top/bottom and
 *     left/right edge lengths, `INTER_CUBIC`, `BORDER_REPLICATE`, output long edge capped at
 *     [maxLongEdge] (memory safety). This alone deskews flat pages and is the guaranteed path.
 *  2. M2 UVDoc neural dewarp ([`NeuralGridDewarp`], when a model is available) — the pinned
 *     UVDoc export or any registry dewarp model; image→image / grid / WC layouts are auto-detected.
 *     SUBTASK H15: the grid path is **adaptive** — [`WarpPolicy`] measures the residual versus the
 *     pure homography and applies strength 0 on flat pages (≤ 1.5 % mean residual), ramps to 1.0 at
 *     5 %, and clamps each residual vector to 6 % of the diagonal. The strength is applied to the
 *     sampling map, so the grid deforms geometry only where the page is actually curved. On success
 *     the flattened page is used and stage 3 is skipped.
 *  3. **Classical curvature un-flatten** ([`ClassicalCurvatureCorrector`]) — only when no neural
 *     model produced a result; straightens residual text-line curl with a safe no-op when it is not
 *     confident.
 *
 * The whole method is wrapped so that **any** failure degrades to the previous stage (ultimately the
 * un-warped source), and all intermediate `Mat`s are released. It never throws and never returns a
 * recycled bitmap for the caller-owned input.
 *
 * @param dewarpModelProvider lazily supplies the optional dewarp [`OnnxModel`] (or `null`).
 * @param curvature classical curvature corrector (stage 3).
 * @param maxLongEdge output cap on the long edge (default 3000 px, MODELS.md §6).
 */
class PerspectiveDewarpProcessor(
    dewarpModelProvider: (() -> OnnxModel?)? = null,
    dewarpGridModelProvider: (() -> OnnxModel?)? = null,
    private val curvature: ClassicalCurvatureCorrector = ClassicalCurvatureCorrector(),
    private val maxLongEdge: Int = MAX_LONG_EDGE
) : DewarpProcessor {

    private val neural = NeuralGridDewarp(dewarpModelProvider, dewarpGridModelProvider)

    /**
     * Geometry of the page rectangle: the 3×3 homography mapping ORIGINAL pixels → page pixels
     * (OpenCV `getPerspectiveTransform(srcQuad, rect)` convention) plus the delivered page size at
     * the original image scale.
     */
    private class PerspectiveGeometry(
        val transform: Mat,
        val outW: Int,
        val outH: Int
    )

    override suspend fun dewarp(source: Bitmap, corners: Quad): Bitmap {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return source

        val start = System.currentTimeMillis()
        Diag.stageEnter("Warp", "${Diag.describe(source)} corners=${corners.points.size}")

        val rgba = Mat()
        var geometry: PerspectiveGeometry? = null
        var perspective: Mat? = null
        var stage: Mat? = null
        try {
            Utils.bitmapToMat(source, rgba)

            // --- Geometry (stage 1 baseline, computed once) -----------------------------------
            geometry = computePerspective(rgba, corners)
            if (geometry == null) {
                Diag.warn("Warp", "perspective geometry invalid; keeping un-warped page")
                Diag.stageExit("Warp", System.currentTimeMillis() - start, "fallback=source")
                return source
            }
            perspective = warpWithGeometry(rgba, geometry)
            if (perspective == null) {
                Diag.warn("Warp", "perspectiveWarp returned null; keeping un-warped page")
                Diag.stageExit("Warp", System.currentTimeMillis() - start, "fallback=source")
                return source
            }
            stage = perspective

            // --- F2 (SUBTASK H3): "already rectified" gate -------------------------------------
            // A page that is already a near-perfect rectangle needs NO learned unwarp (running
            // UVDoc on it is what bowed the text in the user's proof case). Threshold + sub-score
            // scales are justified by the corpus measurements.
            val rectScore = try {
                alreadyRectifiedScore(corners, rgba.cols(), rgba.rows())
            } catch (t: Throwable) {
                Diag.logThrowable("Warp", t)
                0.0
            }
            val alreadyRectified = rectScore >= RECTIFIED_SKIP_THRESHOLD
            Diag.log("Warp", "rectified-score=${fmt5(rectScore)} skipStage2=$alreadyRectified")
            if (alreadyRectified) {
                Diag.log("Warp", "stage2 skipped (already rectified, score=${fmt5(rectScore)})")
            }

            // --- Stage 2: neural dewarp --------------------------------------------------------
            // H7 primary: the 45×31 grid is composed with the homography and applied as ONE remap
            // from the ORIGINAL still. Only if that fails do we use the image→image fallback on
            // the already-warped page.
            var neuralOut: Mat? = null
            if (!alreadyRectified) {
                val gridOut = neural.dewarpGrid(rgba, geometry.transform, geometry.outW, geometry.outH)
                if (gridOut != null) {
                    neuralOut = gridOut
                    Diag.log("Warp", "stage2 grid dewarp applied (single-sample remap)")
                } else {
                    neuralOut = neural.dewarp(perspective)
                    if (neuralOut != null) {
                        Diag.log("Warp", "stage2 image dewarp applied (grid fallback)")
                    }
                }
            }

            if (neuralOut != null && neuralOut !== perspective) {
                perspective.release()
                perspective = null
                stage = neuralOut
            } else {
                // --- Stage 3: classical curvature un-flatten (safe no-op when unsure) ----------
                val curved = curvature.correct(perspective)
                if (curved != null && curved !== perspective) {
                    perspective.release()
                    perspective = null
                    stage = curved
                    Diag.log("Warp", "stage3 classical curvature applied")
                } else {
                    Diag.log("Warp", "neural unavailable + curvature no-op; perspective only")
                }
            }

            val out = Bitmap.createBitmap(stage.cols(), stage.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(stage, out)
            Diag.stageExit("Warp", System.currentTimeMillis() - start, Diag.describe(out))
            return out
        } catch (t: Throwable) {
            // Catch Throwable (incl. OutOfMemoryError) — the caller keeps the un-warped page.
            Diag.logThrowable("Warp", t)
            return source
        } finally {
            // Mat.release() is idempotent; `perspective` may be the same object as `stage`.
            stage?.release()
            perspective?.release()
            geometry?.transform?.release()
            rgba.release()
        }
    }

    override fun release() {
        neural.release()
    }

    // --- perspective ------------------------------------------------------------------------

    /**
     * Computes the page rectangle + homography for [quad] in [src]'s pixel space. Returns `null`
     * for a degenerate/invalid quad. The caller owns the returned transform and must release it.
     */
    private fun computePerspective(src: Mat, quad: Quad): PerspectiveGeometry? {
        val srcW = src.cols()
        val srcH = src.rows()
        val pts = quad.points
        if (pts.size != 4) return null
        if (pts.any { !it.x.isFinite() || !it.y.isFinite() }) return null

        val maxX = (srcW - 1).toDouble()
        val maxY = (srcH - 1).toDouble()
        fun clamp(p: Point) = CvPoint(
            p.x.toDouble().coerceIn(0.0, maxX),
            p.y.toDouble().coerceIn(0.0, maxY)
        )
        val tl = clamp(pts[0])
        val tr = clamp(pts[1])
        val br = clamp(pts[2])
        val bl = clamp(pts[3])

        // Reject slivers / near-degenerate quads (fall back to the un-warped page).
        val areaPoly = MatOfPoint2f(tl, tr, br, bl)
        val area = try {
            abs(Imgproc.contourArea(areaPoly))
        } catch (t: Throwable) {
            0.0
        } finally {
            areaPoly.release()
        }
        if (area < MIN_AREA_FRACTION * srcW.toDouble() * srcH.toDouble()) return null

        val topLen = dist(tl, tr)
        val bottomLen = dist(bl, br)
        val leftLen = dist(tl, bl)
        val rightLen = dist(tr, br)

        // SUBTASK H16 STEP 3: the output aspect comes from the projected PHYSICAL page edges
        // (median of the opposite edge pair), never from the frame bounding box. This is robust
        // to one noisy edge while staying the true page aspect.
        var outW = medianPair(topLen, bottomLen).roundToInt()
        var outH = medianPair(leftLen, rightLen).roundToInt()
        outW = outW.coerceAtLeast(MIN_EDGE)
        outH = outH.coerceAtLeast(MIN_EDGE)

        // No invented pixels: the delivered long edge must not exceed the longest projected
        // source page edge. Rounding is clamped, not upsampled.
        val srcPageLong = max(max(topLen, bottomLen), max(leftLen, rightLen))
        val legacyGeometry = debugLegacyGeometryOverride == true
        var longEdge = max(outW, outH).toDouble()
        if (longEdge > maxLongEdge) {
            val s = maxLongEdge.toDouble() / longEdge
            outW = (outW * s).roundToInt().coerceAtLeast(MIN_EDGE)
            outH = (outH * s).roundToInt().coerceAtLeast(MIN_EDGE)
            longEdge = max(outW, outH).toDouble()
        }
        if (!legacyGeometry && longEdge > srcPageLong) {
            val s = srcPageLong / longEdge
            outW = (outW * s).roundToInt().coerceAtLeast(MIN_EDGE)
            outH = (outH * s).roundToInt().coerceAtLeast(MIN_EDGE)
        }
        Diag.log(
            "Warp",
            "out=${outW}x${outH} srcDensity=1.000 px/srcPx " +
                "upsampled=${max(outW, outH) > srcPageLong + 0.5} legacy=$legacyGeometry"
        )

        val srcM = MatOfPoint2f(tl, tr, br, bl)
        val dstM = MatOfPoint2f(
            CvPoint(0.0, 0.0),
            CvPoint(outW - 1.0, 0.0),
            CvPoint(outW - 1.0, outH - 1.0),
            CvPoint(0.0, outH - 1.0)
        )
        return try {
            // getPerspectiveTransform returns the 3x3 homography (src -> dst) for warpPerspective.
            PerspectiveGeometry(
                Imgproc.getPerspectiveTransform(srcM, dstM),
                outW,
                outH
            )
        } catch (t: Throwable) {
            Log.w(TAG, "computePerspective failed", t)
            null
        } finally {
            srcM.release(); dstM.release()
        }
    }

    /** Renders the page rectangle from [src] using an already-computed [geometry]. */
    private fun warpWithGeometry(src: Mat, geometry: PerspectiveGeometry): Mat? {
        val out = Mat()
        return try {
            Imgproc.warpPerspective(
                src, out, geometry.transform,
                Size(geometry.outW.toDouble(), geometry.outH.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
            )
            out
        } catch (t: Throwable) {
            Log.w(TAG, "perspectiveWarp failed", t)
            out.release()
            null
        }
    }

    private fun dist(a: CvPoint, b: CvPoint): Double = hypot(a.x - b.x, a.y - b.y)

    /** Median of two opposite page edges (H16 STEP 3: physical aspect, not the bounding box). */
    private fun medianPair(a: Double, b: Double): Double = (a + b) / 2.0

    private fun abs(v: Double) = if (v < 0) -v else v

    /**
     * F2 (SUBTASK H3): rectangularity score in [0,1] for the ORDERED quad (1 == a perfect,
     * axis-aligned rectangle that needs no unwarp). Four geometry cues are combined:
     *  - **corner angles** near 90 deg — catches perspective / keystone,
     *  - **opposite-side length ratios** near 1 — catches foreshortening / trapezoids,
     *  - **high polygon area fraction** — the page fills the frame,
     *  - the angle + ratio terms together capture "low perspective/keystone".
     *
     * The angle and ratio sub-scores reach 0 at [ANGLE_DEV_FULL_DEG] of angular deviation and
     * [RATIO_DEV_FULL] of side-ratio deviation respectively; the weighted sum favours geometry and
     * uses the area term as a tie-breaker. Returns 0.0 for a degenerate/unknown quad so the neural
     * stage still runs (fail-open toward the richer model). The whole call is additionally wrapped
     * by the caller.
     */
    private fun alreadyRectifiedScore(quad: Quad, w: Int, h: Int): Double {
        if (w <= 0 || h <= 0) return 0.0
        val p: List<Point> = quad.points
        if (p.size != 4) return 0.0
        if (p.any { !it.x.isFinite() || !it.y.isFinite() }) return 0.0

        fun d(a: Point, b: Point): Double = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        val top = d(p[0], p[1])
        val right = d(p[1], p[2])
        val bottom = d(p[2], p[3])
        val left = d(p[3], p[0])
        val meanV = ((top + bottom) / 2.0).coerceAtLeast(1e-6)
        val meanH = ((left + right) / 2.0).coerceAtLeast(1e-6)
        val ratioDev = max(abs(top - bottom) / meanV, abs(left - right) / meanH)

        fun angleDeg(a: Point, b: Point, c: Point): Double {
            val v1x = (a.x - b.x).toDouble(); val v1y = (a.y - b.y).toDouble()
            val v2x = (c.x - b.x).toDouble(); val v2y = (c.y - b.y).toDouble()
            val cos = ((v1x * v2x + v1y * v2y) /
                (hypot(v1x, v1y).coerceAtLeast(1e-6) * hypot(v2x, v2y).coerceAtLeast(1e-6)))
                .coerceIn(-1.0, 1.0)
            return Math.toDegrees(kotlin.math.acos(cos))
        }
        val angles = doubleArrayOf(
            angleDeg(p[3], p[0], p[1]),
            angleDeg(p[0], p[1], p[2]),
            angleDeg(p[1], p[2], p[3]),
            angleDeg(p[2], p[3], p[0])
        )
        var maxAngleDev = 0.0
        angles.forEach { val dv = abs(it - 90.0); if (dv > maxAngleDev) maxAngleDev = dv }

        var area2 = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            area2 += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
        }
        val areaFrac = (abs(area2) / 2.0) / (w.toDouble() * h.toDouble())

        val angleScore = 1.0 - (maxAngleDev / ANGLE_DEV_FULL_DEG).coerceIn(0.0, 1.0)
        val ratioScore = 1.0 - (ratioDev / RATIO_DEV_FULL).coerceIn(0.0, 1.0)
        val areaScore = areaFrac.coerceIn(0.0, 1.0)
        return (0.45 * angleScore + 0.40 * ratioScore + 0.15 * areaScore).coerceIn(0.0, 1.0)
    }

    private fun fmt5(v: Double): String =
        if (v < 0) "-1" else String.format(java.util.Locale.US, "%.4f", v)

    companion object {
        private const val TAG = "PerspectiveDewarp"

        /**
         * Output long edge cap (SUBTASK 9a memory safety). Reduced from the spec's 3000 px to
         * ~2000 px for the dewarp/enhancement working image to bound Java+native allocation and
         * avoid OOM/native blowups on large captures. Algorithms are unchanged — only the working
         * resolution is smaller.
         */
        private const val MAX_LONG_EDGE = 2000
        private const val MIN_EDGE = 64
        private const val MIN_AREA_FRACTION = 0.02

        /**
         * F2 (SUBTASK H3): rectangularity at or above this score ⇒ the page is already flat enough
         * that the learned UVDoc unwarp must NOT run (it was bowing text on already-rectified
         * pages). Chosen from the corpus distribution: `rectified_full` scores ~0.99 (skip) while
         * every `perspective_*` category scores well below 0.5 (run). See the H3 report.
         */
        private const val RECTIFIED_SKIP_THRESHOLD = 0.90

        /** Angle deviation (deg) at which the F2 angle sub-score reaches 0. */
        private const val ANGLE_DEV_FULL_DEG = 12.0

        /** H16 STEP 3 A/B hook: `true` restores the 0.3.3 output-sizing behavior. */
        @Volatile var debugLegacyGeometryOverride: Boolean? = null

        /** Opposite-side length ratio deviation at which the F2 ratio sub-score reaches 0. */
        private const val RATIO_DEV_FULL = 0.20
    }
}
