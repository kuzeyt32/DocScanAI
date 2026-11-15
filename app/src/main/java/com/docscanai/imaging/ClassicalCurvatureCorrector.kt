package com.docscanai.imaging

import android.util.Log
import com.docscanai.core.util.Diag
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Classical **curvature "un-flatten"** stage (MODELS.md §3.4 fallback, TECHNICAL_SPEC.md §4.2).
 *
 * Removes the residual page curl / bend left *after* the perspective warp by straightening curved
 * text baselines into flat horizontal lines. It is the always-available companion to the neural
 * [`NeuralGridDewarp`] and never depends on a downloaded model.
 *
 * ## Algorithm
 *  1. Downscale to a bounded working width ([profileLongEdge]) for speed/memory safety.
 *  2. Build an **ink mask**: gray → small blur → `THRESH_BINARY_INV | OTSU` (dark text on light
 *     paper), then a **wide horizontal morphological close** so per-character strokes merge into
 *     text *lines*.
 *  3. For every column compute the **ink centroid** (weighted mean row) → a profile `y(x)` of where
 *     the text sits vertically. Columns with too little ink are discarded.
 *  4. Fit a **quadratic** `p(x) = a·x² + b·x + c` (least squares, `x` normalised to `[0,1]`) to the
 *     profile — a smooth low-order model of the curl.
 *  5. Build a **per-column vertical displacement field** `dy(x) = p(x) - mean(p)` and `remap` the
 *     image with `srcY = y + dy(x)` so the fitted curve becomes a horizontal baseline.
 *
 * ## Safety (never make the image worse)
 * The correction is applied **only** when the fit is confidently beneficial; otherwise `null` is
 * returned and the caller keeps the perspective-corrected image unchanged. Gates:
 *  - enough valid columns ([minValidColumnRatio]);
 *  - the correction magnitude is inside `[minCorrectionNorm, maxCorrectionNorm]` (a flat page ⇒ no
 *    correction; an implausibly large "curl" ⇒ bad fit ⇒ rejected);
 *  - the quadratic residual RMS is below [maxResidualNorm] (the profile really follows a smooth
 *    curve, i.e. it is text lines, not a mix of photos/figures).
 *
 * All displacements are expressed **normalised** (`[0,1]`) so the field can be evaluated at any
 * output resolution cheaply.
 */
class ClassicalCurvatureCorrector(
    /** Working long edge (px) used to build the text profile. */
    private val profileLongEdge: Int = 640,
    /** Minimum fraction of columns that must contain ink for the fit to be trusted. */
    private val minValidColumnRatio: Double = 0.25,
    /** Minimum peak correction (fraction of height) worth applying; below this it is a no-op. */
    private val minCorrectionNorm: Double = 0.005,
    /** Maximum plausible peak correction (fraction of height); above this the fit is rejected. */
    private val maxCorrectionNorm: Double = 0.12,
    /** Maximum residual RMS of the quadratic fit (fraction of height). */
    private val maxResidualNorm: Double = 0.05
) {

    /**
     * Returns a NEW curvature-corrected `CV_8UC4` [Mat], or `null` when the correction is not
     * confidently beneficial (caller must keep its current image). Never throws.
     */
    fun correct(src: Mat): Mat? {
        if (src.empty() || src.cols() < 96 || src.rows() < 96) return null
        val width = src.cols()
        val height = src.rows()
        val start = System.currentTimeMillis()
        Diag.log("Curvature", "enter ${width}x$height")

        val scale = if (max(width, height) > profileLongEdge) {
            profileLongEdge.toDouble() / max(width, height).toDouble()
        } else {
            1.0
        }
        val ww = max(48, (width * scale).toInt())
        val wh = max(48, (height * scale).toInt())

        val gray = Mat()
        val small = Mat()
        val blur = Mat()
        val bin = Mat()
        val closed = Mat()
        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(gray, small, Size(ww.toDouble(), wh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(small, blur, Size(3.0, 3.0), 0.0)
            Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

            // Wide horizontal close: merge characters/words into solid text lines.
            val kernelWidth = max(3, ww / 30)
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(kernelWidth.toDouble(), 1.0)
            )
            try {
                Imgproc.morphologyEx(bin, closed, Imgproc.MORPH_CLOSE, kernel)
            } finally {
                kernel.release()
            }

            if (!closed.isContinuous) return null
            val pixels = ByteArray(ww * wh)
            closed.get(0, 0, pixels)

            val minCoverage = max(2, (0.02 * wh).toInt())
            val xs = ArrayList<Double>(ww)
            val ys = ArrayList<Double>(ww)
            val xDen = (ww - 1).toDouble().coerceAtLeast(1.0)
            val yDen = (wh - 1).toDouble().coerceAtLeast(1.0)
            for (j in 0 until ww) {
                var sum = 0L
                var cnt = 0
                var idx = j
                for (i in 0 until wh) {
                    if (pixels[idx].toInt() != 0) {
                        sum += i.toLong()
                        cnt++
                    }
                    idx += ww
                }
                if (cnt >= minCoverage) {
                    xs.add(j.toDouble() / xDen)
                    ys.add((sum.toDouble() / cnt.toDouble()) / yDen)
                }
            }

            val minColumns = max(8, (minValidColumnRatio * ww).toInt())
            if (xs.size < minColumns) return null

            val fit = fitQuadratic(xs, ys) ?: return null
            val (a, b, c, residualRms) = fit
            if (residualRms > maxResidualNorm) return null

            // Mean of p(x) over x in [0,1] (definite integral of a·x²+b·x+c).
            val mean = a / 3.0 + b / 2.0 + c

            // Peak correction over x in [0,1].
            var peak = 0.0
            for (t in 0..STEPS) {
                val x = t.toDouble() / STEPS.toDouble()
                val shift = a * x * x + b * x + c - mean
                val mag = abs(shift)
                if (mag > peak) peak = mag
            }
            if (peak < minCorrectionNorm || peak > maxCorrectionNorm) return null

            val out = buildRemapped(src, width, height, ww, wh, a, b, c, mean)
            Diag.log("Curvature", "applied | ${System.currentTimeMillis() - start}ms")
            return out
        } catch (t: Throwable) {
            Diag.logThrowable("Curvature", t)
            return null
        } finally {
            gray.release(); small.release(); blur.release(); bin.release(); closed.release()
        }
    }

    /**
     * Least-squares quadratic fit `y = a·x² + b·x + c` over normalised `x`, returning
     * `(a, b, c, residualRms)` or `null` when the system is singular.
     */
    private fun fitQuadratic(xs: List<Double>, ys: List<Double>): QuadFit? {
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (k in xs.indices) {
            val x = xs[k]
            val y = ys[k]
            val x2 = x * x
            s0 += 1.0
            s1 += x
            s2 += x2
            s3 += x2 * x
            s4 += x2 * x2
            t0 += y
            t1 += x * y
            t2 += x2 * y
        }
        val det = det3(s4, s3, s2, s3, s2, s1, s2, s1, s0)
        if (abs(det) < 1e-12) return null
        val a = det3(t2, s3, s2, t1, s2, s1, t0, s1, s0) / det
        val b = det3(s4, t2, s2, s3, t1, s1, s2, t0, s0) / det
        val c = det3(s4, s3, t2, s3, s2, t1, s2, s1, t0) / det

        var acc = 0.0
        for (k in xs.indices) {
            val x = xs[k]
            val r = ys[k] - (a * x * x + b * x + c)
            acc += r * r
        }
        val rms = sqrt(acc / xs.size.toDouble())
        return QuadFit(a, b, c, rms)
    }

    /**
     * Builds the normalised displacement field at working resolution, upsamples it to the full
     * image size and applies it with [`Imgproc.remap`].
     */
    private fun buildRemapped(
        src: Mat,
        width: Int,
        height: Int,
        ww: Int,
        wh: Int,
        a: Double,
        b: Double,
        c: Double,
        mean: Double
    ): Mat {
        val fieldX = FloatArray(ww * wh)
        val fieldY = FloatArray(ww * wh)
        val xDen = (ww - 1).toDouble().coerceAtLeast(1.0)
        val yDen = (wh - 1).toDouble().coerceAtLeast(1.0)
        for (i in 0 until wh) {
            val yN = i.toDouble() / yDen
            for (j in 0 until ww) {
                val xN = j.toDouble() / xDen
                val shift = a * xN * xN + b * xN + c - mean
                var srcY = yN + shift
                if (srcY < 0.0) srcY = 0.0 else if (srcY > 1.0) srcY = 1.0
                val k = i * ww + j
                fieldX[k] = xN.toFloat()
                fieldY[k] = srcY.toFloat()
            }
        }

        val smallX = Mat(wh, ww, CvType.CV_32FC1).apply { put(0, 0, fieldX) }
        val smallY = Mat(wh, ww, CvType.CV_32FC1).apply { put(0, 0, fieldY) }
        val mapX = Mat()
        val mapY = Mat()
        val out = Mat()
        try {
            Imgproc.resize(smallX, mapX, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.resize(smallY, mapY, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.multiply(mapX, Scalar(width - 1.0), mapX)
            Core.multiply(mapY, Scalar(height - 1.0), mapY)
            Imgproc.remap(src, out, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            return out
        } finally {
            smallX.release(); smallY.release(); mapX.release(); mapY.release()
        }
    }

    /** 3×3 determinant (rule of Sarrus). */
    private fun det3(
        a1: Double, b1: Double, c1: Double,
        a2: Double, b2: Double, c2: Double,
        a3: Double, b3: Double, c3: Double
    ): Double = a1 * (b2 * c3 - b3 * c2) -
        b1 * (a2 * c3 - a3 * c2) +
        c1 * (a2 * b3 - a3 * b2)

    private data class QuadFit(val a: Double, val b: Double, val c: Double, val rms: Double)

    companion object {
        private const val TAG = "CurvatureCorrector"
        private const val STEPS = 64
    }
}
