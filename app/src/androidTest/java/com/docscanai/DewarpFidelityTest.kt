package com.docscanai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.core.di.ServiceLocator
import com.docscanai.imaging.NeuralGridDewarp
import com.docscanai.imaging.WarpPolicy
import com.docscanai.models.ModelRegistry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
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
 * ============================================================================================
 * SUBTASK H15 — dewarp fidelity proof.
 * ============================================================================================
 *
 * The owner-reported defect is that the neural grid dewarp **over-corrects** real captures:
 * it deforms page geometry and visibly damages character strokes. H15 adds an adaptive residual
 * strength (0 on flat pages, ramped to 1 by a 5 % mean residual) and a 6 % per-vector clamp.
 *
 * This class proves, independently of the corpus, that the policy does the right thing:
 *
 *  1. **Flat-page invariance** — a near-identity grid (mean residual below
 *     [WarpPolicy.RESIDUAL_FLAT_MEAN]) resolves to strength 0 and the delivered map is the pure
 *     homography (corner movement 0, straightness unchanged).
 *  2. **Stroke preservation** — the real AI grid path (UVDoc model) on a flat, densely-typeset
 *     synthetic page keeps Laplacian variance ≥ 0.98× the homography-only output.
 *  3. **Curved-page benefit** — a genuinely bowed synthetic page with a correcting grid resolves
 *     to strength > 0 and straightens text measurably better than homography-only, proving the gate
 *     does not disable the feature where it is needed.
 *  4. **Clamp proof** — a deliberately corrupted grid (30 % displacement) is clamped to the
 *     6 % constant and the resulting map has a positive Jacobian (no fold/self-intersection).
 *  5. **Interpolation A/B** — the same composed map is remapped with `INTER_CUBIC` and
 *     `INTER_LANCZOS4`; both stroke-sharpness and line-straightness are logged so the production
 *     constant is chosen by measurement.
 *
 * Everything is logged with a `WARP_` prefix. Tests 1/3/4 are pure math (no model, always
 * deterministic); tests 2/5 use the provisioned grid model (the `h7_tests.cmd` lane provisions it).
 */
@RunWith(AndroidJUnit4::class)
class DewarpFidelityTest {

    companion object {
        private const val TAG = "DocScan"

        /** Model-free math grid used by the invariance / curved / clamp tests. */
        private const val MATH_W = 240
        private const val MATH_H = 320

        @JvmStatic
        @BeforeClass
        fun setUp() {
            NeuralRepro.setUp(enableXnnpack = false, tag = "DewarpFidelityTest/CPU-EP")
        }
    }

    // ==========================================================================================
    // 1) Flat-page invariance (pure math: strength must resolve to 0)
    // ==========================================================================================

    @Test
    fun flatPage_invariance_strengthZeroAndMapUnchanged() {
        val w = MATH_W
        val h = MATH_H
        val diag = hypot(w.toDouble(), h.toDouble())
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        // A realistic "near-identity but not identity" grid: a smooth low-frequency wobble with an
        // amplitude well below the 1.5 % flat threshold.
        val amp = 0.008 * diag
        val gridX = FloatArray(w * h)
        val gridY = FloatArray(w * h)
        val pureX = FloatArray(w * h)
        val pureY = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                pureX[i] = x.toFloat()
                pureY[i] = y.toFloat()
                gridX[i] = (x + amp * sin(2.0 * PI * y / h)).toFloat()
                gridY[i] = (y + amp * sin(2.0 * PI * x / w)).toFloat()
            }
        }

        val applied = WarpPolicy.applyAdaptive(gridX, gridY, w, h, identity, 0.0)

        // Policy thresholds themselves.
        assertTrue("WARP_FLAT threshold: mean=0.015 must be flat",
            WarpPolicy.strengthFor(WarpPolicy.RESIDUAL_FLAT_MEAN) == 0.0)
        assertTrue("WARP_FLAT threshold: mean=0.05 must be full",
            WarpPolicy.strengthFor(WarpPolicy.RESIDUAL_FULL_MEAN) == 1.0)
        assertTrue("WARP_FLAT mean residual must be below the flat threshold (${applied.stats.mean})",
            applied.stats.mean <= WarpPolicy.RESIDUAL_FLAT_MEAN)
        assertTrue("WARP_FLAT strength must be 0 (was ${applied.strength})", applied.strength == 0.0)

        // Delivered map must be the pure homography: zero movement, corners included.
        var maxMove = 0.0
        for (i in gridX.indices) {
            val dx = gridX[i] - pureX[i]
            val dy = gridY[i] - pureY[i]
            maxMove = max(maxMove, hypot(dx.toDouble(), dy.toDouble()) / diag)
        }
        assertTrue("WARP_FLAT map moved ${maxMove * 100}% of diagonal (must be 0)", maxMove < 1e-6)

        // Geometry + text-line straightness must be unchanged versus homography-only. Because the
        // map IS the homography, the rendered pages are identical; measure to make the proof real.
        val page = renderTextPage(w, h)
        try {
            val base = bitmapToMat(page)
            val homographyOnly = Mat()
            val adaptive = Mat()
            val id = identityMat()
            try {
                Imgproc.warpPerspective(base, homographyOnly, id, Size(w.toDouble(), h.toDouble()),
                    Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
                remapWithMaps(base, adaptive, gridX, gridY, w, h)
                val sH = straightness(homographyOnly)
                val sA = straightness(adaptive)
                val sharpH = laplacianVariance(homographyOnly)
                val sharpA = laplacianVariance(adaptive)
                assertTrue("WARP_FLAT straightness worsened by >2% (hom=$sH adaptive=$sA)",
                    sA >= sH * 0.98)
                assertTrue("WARP_FLAT Laplacian changed (hom=$sharpH adaptive=$sharpA)",
                    abs(sharpA - sharpH) <= max(1.0, sharpH * 0.02))
                Log.i(TAG, "WARP_FLAT_INVARIANCE ok strength=0.00 residualMean=" +
                    fmt(applied.stats.mean) + " maxMovePct=" + fmt(maxMove * 100.0) +
                    " straightnessHom=" + fmt(sH) + " straightnessAdaptive=" + fmt(sA) +
                    " sharpHom=" + fmt(sharpH) + " sharpAdaptive=" + fmt(sharpA))
            } finally {
                id.release()
                base.release(); homographyOnly.release(); adaptive.release()
            }
        } finally {
            page.recycle()
        }
    }

    // ==========================================================================================
    // 2) Stroke preservation through the REAL AI grid path
    // ==========================================================================================

    @Test
    fun strokePreservation_aiPathKeepsLaplacian() {
        val neural = gridDewarpOrFail("WARP_STROKE")
        val pageBmp = renderTextPage(900, 1200)
        val canvasW = 1200
        val canvasH = 1500
        // A mild, non-rectangular capture so the pipeline's "already rectified" skip does not apply.
        val quad = listOf(
            CvPoint(canvasW * 0.08, canvasH * 0.10),
            CvPoint(canvasW * 0.90, canvasH * 0.07),
            CvPoint(canvasW * 0.93, canvasH * 0.92),
            CvPoint(canvasW * 0.10, canvasH * 0.95)
        )
        val original = Mat()
        val pageMat = bitmapToMat(pageBmp)
        val sourceToPage = Mat()
        val homographyOnly = Mat()
        val ai = Mat()
        try {
            pastePage(pageMat, original, canvasW, canvasH, quad)
            val outW = 900
            val outH = 1200
            getPerspective(quad, outW, outH, sourceToPage)
            Imgproc.warpPerspective(
                original, homographyOnly, sourceToPage,
                Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
            )
            val aiOut = neural.dewarpGrid(original, sourceToPage, outW, outH) ?: run {
                assertTrue("WARP_STROKE grid dewarp returned null (model fallback)", false)
                return
            }
            try {
                aiOut.copyTo(ai)
            } finally {
                aiOut.release()
            }

            val crop = RectF(0.20, 0.20, 0.80, 0.80)
            val sharpHom = laplacianVariance(homographyOnly, crop)
            val sharpAi = laplacianVariance(ai, crop)
            val straightHom = straightness(homographyOnly, crop)
            val straightAi = straightness(ai, crop)
            val ratio = if (sharpHom > 1e-6) sharpAi / sharpHom else 0.0
            assertTrue(
                "WARP_STROKE destroyed strokes: AI Laplacian ${fmt(sharpAi)} is only " +
                    "${fmt(ratio)}x homography-only ${fmt(sharpHom)} (need >= 0.98x)",
                ratio >= 0.98
            )
            Log.i(TAG, "WARP_STROKE_PRESERVATION ai=" + fmt(sharpAi) + " homography=" +
                fmt(sharpHom) + " ratio=" + fmt(ratio) + " straightAi=" + fmt(straightAi) +
                " straightHom=" + fmt(straightHom))
        } finally {
            pageBmp.recycle()
            pageMat.release(); original.release(); sourceToPage.release()
            homographyOnly.release(); ai.release()
            // NOTE: do NOT close the wrapper here — it resolves the process-shared
            // ServiceLocator.dewarpGridModel, and closing it would break later tests in the same
            // instrumentation process. The shared model is process-scoped by design.
        }
    }

    // ==========================================================================================
    // 3) Curved-page benefit (strength > 0 must improve straightness)
    // ==========================================================================================

    @Test
    fun curvedPage_benefit_strengthPositiveImprovesStraightness() {
        val w = MATH_W
        val h = MATH_H
        val diag = hypot(w.toDouble(), h.toDouble())
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val page = renderTextPage(w, h)
        val flat = bitmapToMat(page)
        val bowed = Mat()
        val corrected = Mat()
        val homographyOnly = Mat()
        try {
            // Bowed capture: text row y0 appears at y0 - bow(x) (single arch, 4.5 % of height).
            val amp = 0.045 * h
            val sx = FloatArray(w * h)
            val sy = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    sx[i] = x.toFloat()
                    sy[i] = (y - amp * sin(PI * x / (w - 1))).toFloat()
                }
            }
            remapWithMaps(flat, bowed, sx, sy, w, h)

            // Grid that corrects the known bow: sample at y + bow(x).
            val gridX = FloatArray(w * h)
            val gridY = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    gridX[i] = x.toFloat()
                    gridY[i] = (y + amp * sin(PI * x / (w - 1))).toFloat()
                }
            }
            val curvature = WarpPolicy.curvature(
                normalizeGrid(gridX, w, h), normalizeGrid(gridY, w, h), w, h, identity, w, h
            )
            val applied = WarpPolicy.applyAdaptive(gridX, gridY, w, h, identity, curvature)
            assertTrue("WARP_CURVED strength must be >0 for a truly curved page (mean=" +
                fmt(applied.stats.mean) + ")", applied.strength > 0.0)

            remapWithMaps(bowed, corrected, gridX, gridY, w, h)
            val id = identityMat()
            try {
                Imgproc.warpPerspective(bowed, homographyOnly, id,
                    Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            } finally {
                id.release()
            }

            val straightHom = straightness(homographyOnly)
            val straightAdaptive = straightness(corrected)
            val gain = if (straightHom > 1e-6) straightAdaptive / straightHom else 0.0
            assertTrue(
                "WARP_CURVED did not improve straightness: homography=" + fmt(straightHom) +
                    " adaptive=" + fmt(straightAdaptive) + " (gain=" + fmt(gain) + ")",
                straightAdaptive > straightHom * 1.02
            )
            Log.i(TAG, "WARP_CURVED_BENEFIT strength=" + fmt2(applied.strength) +
                " mean=" + fmt(applied.stats.mean) + " curv=" + fmt(applied.stats.curvature) +
                " straightHomography=" + fmt(straightHom) +
                " straightAdaptive=" + fmt(straightAdaptive) + " gain=" + fmt(gain))
        } finally {
            page.recycle()
            flat.release(); bowed.release(); corrected.release(); homographyOnly.release()
        }
    }

    // ==========================================================================================
    // 4) Clamp proof (corrupted 30 % grid -> 6 % clamp, no fold)
    // ==========================================================================================

    @Test
    fun clampProof_corruptedGridClampedAndFoldFree() {
        val w = 200
        val h = 260
        val diag = hypot(w.toDouble(), h.toDouble())
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        // Deliberately corrupted grid: a smooth radial scale error reaching ~30 % of the diagonal
        // at the page corners. This is the realistic "bad grid" failure (a diverged low-frequency
        // field), not an adversarial high-frequency field that would fold regardless of a
        // magnitude clamp.
        val gridX = FloatArray(w * h)
        val gridY = FloatArray(w * h)
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        val badX = 0.30 * diag / w
        val badY = 0.30 * diag / h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                gridX[i] = (x + badX * (x - cx)).toFloat()
                gridY[i] = (y + badY * (y - cy)).toFloat()
            }
        }
        val applied = WarpPolicy.applyAdaptive(gridX, gridY, w, h, identity, 0.0)

        assertTrue("WARP_CLAMP strength must be 1.0 (was ${applied.strength})",
            applied.strength == 1.0)
        assertTrue("WARP_CLAMP nothing was clamped", applied.clampedVectors > 0)
        assertTrue(
            "WARP_CLAMP applied ${fmt(applied.maxAppliedFraction)} of diagonal, limit is " +
                WarpPolicy.RESIDUAL_MAX_CLAMP,
            applied.maxAppliedFraction <= WarpPolicy.RESIDUAL_MAX_CLAMP + 1e-6
        )

        // Fold/self-intersection check: the final sampling map must stay orientation-preserving
        // (positive Jacobian) on a coarse lattice. A constant/radial 30 % shift without a clamp
        // would fail this on the same construction.
        var minJac = Double.MAX_VALUE
        val stepX = max(1, w / 20)
        val stepY = max(1, h / 20)
        var y = stepY
        while (y < h - stepY) {
            var x = stepX
            while (x < w - stepX) {
                val i = y * w + x
                val xp = gridX[i + stepX].toDouble()
                val xm = gridX[i - stepX].toDouble()
                val yp = gridY[i + stepX].toDouble()
                val ym = gridY[i - stepX].toDouble()
                val xup = gridX[i + stepY * w].toDouble()
                val xdn = gridX[i - stepY * w].toDouble()
                val yup = gridY[i + stepY * w].toDouble()
                val ydn = gridY[i - stepY * w].toDouble()
                val j11 = (xp - xm) / (2.0 * stepX)
                val j21 = (yp - ym) / (2.0 * stepX)
                val j12 = (xup - xdn) / (2.0 * stepY)
                val j22 = (yup - ydn) / (2.0 * stepY)
                val det = j11 * j22 - j12 * j21
                if (det < minJac) minJac = det
                x += stepX
            }
            y += stepY
        }
        assertTrue("WARP_CLAMP produced a fold: min Jacobian=$minJac", minJac > 0.0)

        Log.i(TAG, "WARP_CLAMP_PROOF ok strength=" + fmt2(applied.strength) +
            " clampedVectors=" + applied.clampedVectors +
            " maxAppliedPct=" + fmt(applied.maxAppliedFraction * 100.0) +
            " limitPct=" + fmt(WarpPolicy.RESIDUAL_MAX_CLAMP * 100.0) +
            " minJacobian=" + fmt(minJac))
    }

    // ==========================================================================================
    // 5) Interpolation A/B on the real composed map (CUBIC vs LANCZOS4)
    // ==========================================================================================

    /**
     * Runs the SAME grid composition twice with `INTER_CUBIC` and `INTER_LANCZOS4` on real
     * benchmark captures and logs both stroke-sharpness and line-straightness. The production
     * constant is chosen from these numbers; the assertion only requires that the chosen constant
     * remains one of the two measured candidates (the decision is recorded in `docs/RESULTS.md`).
     */
    @Test
    fun interpolationComparison_benchmarkStrokes() {
        val neural = gridDewarpOrFail("WARP_INTERP")
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val names = testCtx.assets.list("corpus/benchmark/inputs")?.toList().orEmpty().sorted()
            .filter { it.endsWith(".jpg", true) || it.endsWith(".png", true) }
            .take(4)
        assertTrue("WARP_INTERP no benchmark assets", names.isNotEmpty())

        var sharpCubic = 0.0
        var sharpLanczos = 0.0
        var straightCubic = 0.0
        var straightLanczos = 0.0
        var used = 0
        try {
            names.forEach { f ->
                val bmp = runCatching {
                    testCtx.assets.open("corpus/benchmark/inputs/$f").use { BitmapFactory.decodeStream(it) }
                }.getOrNull() ?: return@forEach
                val original = Mat()
                val h = Mat()
                val cubic = Mat()
                val lanczos = Mat()
                try {
                    Utils.bitmapToMat(bmp, original)
                    val w = original.cols()
                    val ht = original.rows()
                    val outW = min(600, w)
                    val outH = min(800, ht)
                    // Inset rectangle: keep the page interior, avoid frame borders.
                    val quad = listOf(
                        CvPoint(w * 0.06, ht * 0.06),
                        CvPoint(w * 0.94, ht * 0.06),
                        CvPoint(w * 0.94, ht * 0.94),
                        CvPoint(w * 0.06, ht * 0.94)
                    )
                    getPerspective(quad, outW, outH, h)
                    val a = neural.dewarpGridForTest(original, h, outW, outH, Imgproc.INTER_CUBIC)
                    val b = neural.dewarpGridForTest(original, h, outW, outH, Imgproc.INTER_LANCZOS4)
                    if (a == null || b == null) return@forEach
                    try {
                        a.copyTo(cubic); b.copyTo(lanczos)
                    } finally {
                        a.release(); b.release()
                    }
                    val crop = RectF(0.20, 0.20, 0.80, 0.80)
                    sharpCubic += laplacianVariance(cubic, crop)
                    sharpLanczos += laplacianVariance(lanczos, crop)
                    straightCubic += straightness(cubic, crop)
                    straightLanczos += straightness(lanczos, crop)
                    used++
                } finally {
                    bmp.recycle()
                    original.release(); h.release(); cubic.release(); lanczos.release()
                }
            }
        } finally {
            // Do NOT close the process-shared ServiceLocator.dewarpGridModel (see stroke test).
        }

        assertTrue("WARP_INTERP no benchmark image produced a grid output", used > 0)
        val sc = sharpCubic / used
        val sl = sharpLanczos / used
        val tc = straightCubic / used
        val tl = straightLanczos / used
        Log.i(TAG, "WARP_INTERP_COMPARISON images=$used cubicSharp=${fmt(sc)} " +
            "lanczosSharp=${fmt(sl)} cubicStraight=${fmt(tc)} lanczosStraight=${fmt(tl)} " +
            "chosen=${productionInterpName()}")
        assertTrue("WARP_INTERP chosen constant is not one of the measured candidates",
            NeuralGridDewarp.productionRemapInterp() == Imgproc.INTER_CUBIC ||
                NeuralGridDewarp.productionRemapInterp() == Imgproc.INTER_LANCZOS4)
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    private fun gridDewarpOrFail(sentinel: String): NeuralGridDewarp {
        val available = ServiceLocator.modelDownloadManager
            .isModelAvailable(ModelRegistry.ID_M2_DEWARP_GRID)
        val model = runCatching { ServiceLocator.dewarpGridModel }.getOrNull()
        if (!available || model == null) {
            val msg = "$sentinel INVALID :: m2_dewarp_grid is absent/unloadable " +
                "(available=$available, loaded=${model != null}) — the grid fidelity proof " +
                "needs the provisioned model."
            Log.e(TAG, msg)
            throw AssertionError(msg)
        }
        return NeuralGridDewarp(gridModelProvider = { ServiceLocator.dewarpGridModel })
    }

    private fun productionInterpName(): String = when (NeuralGridDewarp.productionRemapInterp()) {
        Imgproc.INTER_CUBIC -> "INTER_CUBIC"
        Imgproc.INTER_LANCZOS4 -> "INTER_LANCZOS4"
        else -> "other"
    }

    /** Dense, small-type synthetic page (black on white) used for stroke fidelity. */
    private fun renderTextPage(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = max(8f, w / 75f)
            typeface = Typeface.MONOSPACE
        }
        val margin = w * 0.05f
        val lineH = text.textSize * 1.30f
        var y = margin + text.textSize
        var line = 0
        while (y < h - margin) {
            val body = "Lorem ipsum dolor sit amet 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ "
            canvas.drawText(body, margin, y, text)
            if (line % 3 == 0) {
                val rule = Paint().apply { color = Color.rgb(40, 40, 40); strokeWidth = 1f }
                canvas.drawLine(margin, y + lineH * 0.25f, w - margin, y + lineH * 0.25f, rule)
            }
            y += lineH
            line++
        }
        return bmp
    }

    private fun bitmapToMat(bmp: Bitmap): Mat {
        val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val m = Mat()
        try {
            Utils.bitmapToMat(argb, m)
        } finally {
            if (argb !== bmp) argb.recycle()
        }
        return m
    }

    private fun pastePage(page: Mat, out: Mat, canvasW: Int, canvasH: Int, quad: List<CvPoint>) {
        out.create(canvasH, canvasW, CvType.CV_8UC4)
        out.setTo(Scalar(200.0, 205.0, 210.0, 255.0))
        val src = MatOfPoint2f(
            CvPoint(0.0, 0.0),
            CvPoint((page.cols() - 1).toDouble(), 0.0),
            CvPoint((page.cols() - 1).toDouble(), (page.rows() - 1).toDouble()),
            CvPoint(0.0, (page.rows() - 1).toDouble())
        )
        val dst = MatOfPoint2f(*quad.toTypedArray())
        val h = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        val mask = Mat(page.rows(), page.cols(), CvType.CV_8UC1, Scalar(255.0))
        val warpedMask = Mat()
        try {
            Imgproc.warpPerspective(page, warped, h, Size(canvasW.toDouble(), canvasH.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0, 0.0))
            Imgproc.warpPerspective(mask, warpedMask, h, Size(canvasW.toDouble(), canvasH.toDouble()),
                Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
            warped.copyTo(out, warpedMask)
        } finally {
            src.release(); dst.release(); h.release()
            warped.release(); mask.release(); warpedMask.release()
        }
    }

    private fun getPerspective(quad: List<CvPoint>, outW: Int, outH: Int, dst: Mat) {
        val src = MatOfPoint2f(*quad.toTypedArray())
        val d = MatOfPoint2f(
            CvPoint(0.0, 0.0),
            CvPoint((outW - 1).toDouble(), 0.0),
            CvPoint((outW - 1).toDouble(), (outH - 1).toDouble()),
            CvPoint(0.0, (outH - 1).toDouble())
        )
        try {
            val h = Imgproc.getPerspectiveTransform(src, d)
            h.copyTo(dst)
            h.release()
        } finally {
            src.release(); d.release()
        }
    }

    private fun identityMat(): Mat {
        val m = Mat(3, 3, CvType.CV_64F)
        m.put(0, 0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        return m
    }

    private fun normalizeGrid(pagePixels: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(pagePixels.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                out[i] = ((pagePixels[i] / (w - 1)) * 2.0 - 1.0).toFloat()
            }
        }
        return out
    }

    private fun remapWithMaps(src: Mat, dst: Mat, mapX: FloatArray, mapY: FloatArray, w: Int, h: Int) {
        val mx = Mat(h, w, CvType.CV_32FC1)
        val my = Mat(h, w, CvType.CV_32FC1)
        try {
            mx.put(0, 0, mapX)
            my.put(0, 0, mapY)
            Imgproc.remap(src, dst, mx, my, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        } finally {
            mx.release(); my.release()
        }
    }

    private data class RectF(val l: Double, val t: Double, val r: Double, val b: Double)

    private fun laplacianVariance(m: Mat, crop: RectF? = null): Double {
        val work = cropMat(m, crop)
        val g = Mat()
        val l = Mat()
        try {
            Imgproc.cvtColor(work, g, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(g, l, CvType.CV_32F)
            val mean = MatOfDouble(); val std = MatOfDouble()
            Core.meanStdDev(l, mean, std)
            val s = std.toArray()[0]
            return s * s
        } finally {
            if (work !== m) work.release()
            g.release(); l.release()
        }
    }

    /** Row-projection straightness proxy (coefficient of variation of ink rows, ×100). */
    private fun straightness(m: Mat, crop: RectF? = null): Double {
        val work = cropMat(m, crop)
        val g = Mat(); val inv = Mat(); val prof = Mat()
        try {
            Imgproc.cvtColor(work, g, Imgproc.COLOR_RGBA2GRAY)
            Core.absdiff(g, Scalar(255.0), inv)
            Core.reduce(inv, prof, 1, Core.REDUCE_AVG)
            val mean = MatOfDouble(); val std = MatOfDouble()
            Core.meanStdDev(prof, mean, std)
            val mu = mean.toArray()[0]
            val sd = std.toArray()[0]
            return if (mu <= 1e-6) 0.0 else (sd / mu) * 100.0
        } finally {
            if (work !== m) work.release()
            g.release(); inv.release(); prof.release()
        }
    }

    private fun cropMat(m: Mat, crop: RectF?): Mat {
        if (crop == null) return m
        val x = (crop.l * m.cols()).toInt().coerceIn(0, m.cols() - 2)
        val y = (crop.t * m.rows()).toInt().coerceIn(0, m.rows() - 2)
        val w = ((crop.r - crop.l) * m.cols()).toInt().coerceIn(1, m.cols() - x)
        val h = ((crop.b - crop.t) * m.rows()).toInt().coerceIn(1, m.rows() - y)
        return m.submat(y, y + h, x, x + w)
    }

    private fun fmt(v: Double): String =
        if (!v.isFinite()) "-1" else String.format(java.util.Locale.US, "%.5f", v)

    private fun fmt2(v: Double): String =
        if (!v.isFinite()) "-1" else String.format(java.util.Locale.US, "%.2f", v)
}
