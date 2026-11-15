package com.docscanai.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * SUBTASK H15 — adaptive residual policy for the 45×31 grid dewarp.
 *
 * ## Why this exists
 * The learned UVDoc grid is **near-identity on an already-flat page but not exactly identity**. A
 * 45×31 field bilinearly/cubically upsampled to page resolution injects low-frequency curvature:
 * applying it at 100 % deforms the page geometry and re-samples every glyph for no benefit. This
 * object measures the residual displacement of the grid-implied map relative to the pure 4-corner
 * homography and turns it into a **strength** in `[0,1]` plus a **per-vector clamp**.
 *
 * ## Policy (named constants, measured on the corpus — see `docs/RESULTS.md`)
 *  - [RESIDUAL_FLAT_MEAN] = 0.015 (1.5 % of the page diagonal): at or below this the page is
 *    effectively flat → **strength 0** (homography only).
 *  - linear ramp from 0 at 1.5 % to 1.0 at [RESIDUAL_FULL_MEAN] = 0.05 (5 %).
 *  - [RESIDUAL_MAX_CLAMP] = 0.06 (6 % of the diagonal): every residual vector is length-clamped
 *    **before** the strength is applied, so a diverged grid cannot fold or tear the page.
 *
 * The strength is applied by interpolating the **sampling map** toward the pure homography
 * (`final = homography + strength * clamp(residual)`), never by blending two output images. The
 * caller then performs one `Imgproc.remap` from the original still.
 *
 * This object is deliberately pure Kotlin (no OpenCV, no Android) so the clamp/strength math is
 * unit-testable without the model — see `DewarpFidelityTest`.
 *
 * SUBTASK H16 STEP 1 adds a **smooth residual reconstruction**. The raw path upsamples the
 * normalized 45×31 grid to page resolution and then evaluates `H⁻¹` per pixel; the nonlinear
 * homography applied AFTER interpolation can inject low-frequency wobble. The smoothed path
 * instead evaluates the residual at the 45×31 control points in ORIGINAL pixel space and fits a
 * low-order surface (`bicubic` Catmull-Rom, `poly2`, `poly3`) that is evaluated directly at output
 * resolution. Production chooses the winner from the benchmark table; `raw` keeps the 0.3.3 path
 * available for A/B and rollback.
 */
object WarpPolicy {

    /** Mean residual (fraction of diagonal) at/below which the page is treated as flat. */
    const val RESIDUAL_FLAT_MEAN = 0.015

    /** Mean residual (fraction of diagonal) at/above which the grid is applied at full strength. */
    const val RESIDUAL_FULL_MEAN = 0.05

    /** Maximum length of one residual displacement vector, as a fraction of the page diagonal. */
    const val RESIDUAL_MAX_CLAMP = 0.06

    // --- SUBTASK H16 STEP 1: residual surface reconstruction --------------------------------
    /** Keep the 0.3.3 behavior (grid upsampled first, then H⁻¹ per output pixel). */
    const val FIT_RAW = "raw"
    /** Catmull-Rom bicubic interpolation of the 45×31 residual control points. */
    const val FIT_BICUBIC = "bicubic"
    /** 2D quadratic least-squares fit of the residual control points. */
    const val FIT_POLY2 = "poly2"
    /** 2D cubic least-squares fit of the residual control points. */
    const val FIT_POLY3 = "poly3"
    /**
     * Production choice after the H16 benchmark A/B: **raw** is kept. None of the smoothed
     * reconstructions improved border + text straightness together without losing sharpness
     * (see docs/RESULTS.md §0.8); the alternatives remain available for future measurement.
     */
    const val RESIDUAL_FIT = FIT_RAW

    /**
     * Test-only override used by the H16 benchmark harness to run several residual fits from the
     * SAME build. Production code leaves this `null`, so [RESIDUAL_FIT] is used.
     */
    @Volatile var debugFitOverride: String? = null

    /** The fit actually used by the production grid path. */
    fun activeFit(): String = debugFitOverride ?: RESIDUAL_FIT

    /** Histogram resolution for the p95 estimate (0..0.5 of the diagonal, 4096 bins). */
    private const val HIST_BINS = 4096
    private const val HIST_MAX = 0.5

    /** Residual sampling stride for pages larger than [STRIDE_MIN_PIXELS] (1 = every pixel). */
    private const val STRIDE_MIN_PIXELS = 250_000L
    private const val COARSE_STRIDE = 2

    /** Measured residual distribution of the grid-implied map vs the pure homography. */
    data class ResidualStats(
        val mean: Double,
        val max: Double,
        val p95: Double,
        val curvature: Double,
        /** Root-mean-square residual magnitude (fraction of the diagonal). */
        val rms: Double = 0.0
    )

    /** Result of applying the adaptive policy to a map. */
    data class Applied(
        val stats: ResidualStats,
        val strength: Double,
        val clampedVectors: Int,
        val maxAppliedFraction: Double
    )

    /**
     * Strength policy: `0` below [RESIDUAL_FLAT_MEAN], linear to `1` at [RESIDUAL_FULL_MEAN].
     * Non-finite means are treated as flat (0) so a NaN can never amplify a bad grid.
     */
    fun strengthFor(meanFraction: Double): Double {
        if (!meanFraction.isFinite()) return 0.0
        if (meanFraction <= RESIDUAL_FLAT_MEAN) return 0.0
        if (meanFraction >= RESIDUAL_FULL_MEAN) return 1.0
        return (meanFraction - RESIDUAL_FLAT_MEAN) /
            (RESIDUAL_FULL_MEAN - RESIDUAL_FLAT_MEAN)
    }

    /**
     * Applies the adaptive policy **in place** to a page-resolution sampling map.
     *
     * @param gridPageX row-major `pageW*pageH` grid-requested page X coordinates. For an identity
     *   grid these equal the output pixel X (0..pageW-1).
     * @param gridPageY row-major grid-requested page Y coordinates (0..pageH-1 for identity).
     * @param pageW/pageH delivered page size.
     * @param hInv row-major 3×3 **page→original** homography (`H⁻¹`).
     * @param curvature second-difference proxy measured on the small 45×31 grid (logging only).
     * @return [Applied] with the residual stats, chosen strength and clamp count. The arrays are
     *   rewritten with the final **original-pixel** sampling coordinates.
     */
    fun applyAdaptive(
        gridPageX: FloatArray,
        gridPageY: FloatArray,
        pageW: Int,
        pageH: Int,
        hInv: DoubleArray,
        curvature: Double = 0.0
    ): Applied {
        require(pageW >= 2 && pageH >= 2) { "page must be at least 2x2" }
        require(hInv.size == 9) { "hInv must be a 3x3 row-major matrix" }
        val count = pageW * pageH
        require(gridPageX.size >= count && gridPageY.size >= count) {
            "map arrays must hold at least $count samples"
        }

        val diag = max(1.0, hypot(pageW.toDouble(), pageH.toDouble()))
        val xMax = (pageW - 1).toDouble()
        val yMax = (pageH - 1).toDouble()

        // --- pass 1: residual statistics (sampled; the field is low-frequency) ---------------
        val stride = if (count.toLong() > STRIDE_MIN_PIXELS) COARSE_STRIDE else 1
        var sum = 0.0
        var sumSq = 0.0
        var maxFrac = 0.0
        var samples = 0L
        val hist = IntArray(HIST_BINS)
        for (y in 0 until pageH) {
            if (y % stride != 0) continue
            // Incremental pure-homography evaluation along the row (H⁻¹ · (x, y, 1)).
            var w = hInv[7] * y + hInv[8]
            var nx = hInv[1] * y + hInv[2]
            var ny = hInv[4] * y + hInv[5]
            for (x in 0 until pageW) {
                if (x % stride == 0) {
                    val i = y * pageW + x
                    if (w.isFinite() && abs(w) > 1e-9) {
                        val pureX = nx / w
                        val pureY = ny / w
                        val gx = gridPageX[i].toDouble()
                        val gy = gridPageY[i].toDouble()
                        if (gx.isFinite() && gy.isFinite()) {
                            val gw = hInv[6] * gx + hInv[7] * gy + hInv[8]
                            if (gw.isFinite() && abs(gw) > 1e-9) {
                                val gOrigX = (hInv[0] * gx + hInv[1] * gy + hInv[2]) / gw
                                val gOrigY = (hInv[3] * gx + hInv[4] * gy + hInv[5]) / gw
                                val frac = hypot(gOrigX - pureX, gOrigY - pureY) / diag
                                if (frac.isFinite()) {
                                    sum += frac
                                    sumSq += frac * frac
                                    if (frac > maxFrac) maxFrac = frac
                                    val bin = min(HIST_BINS - 1,
                                        max(0, (frac / HIST_MAX * (HIST_BINS - 1)).toInt()))
                                    hist[bin]++
                                    samples++
                                }
                            }
                        }
                    }
                }
                w += hInv[6]
                nx += hInv[0]
                ny += hInv[3]
            }
        }

        val mean = if (samples == 0L) 0.0 else sum / samples.toDouble()
        val rms = if (samples == 0L) 0.0 else kotlin.math.sqrt(sumSq / samples.toDouble())
        val p95 = percentile(hist, samples)
        val stats = ResidualStats(mean, maxFrac, p95, curvature, rms)
        val strength = strengthFor(mean)

        // --- pass 2: write the final map ------------------------------------------------------
        val maxDisp = RESIDUAL_MAX_CLAMP * diag
        var clamped = 0
        var maxApplied = 0.0
        for (row in 0 until pageH) {
            val yd = row.toDouble()
            var w = hInv[7] * yd + hInv[8]
            var nx = hInv[1] * yd + hInv[2]
            var ny = hInv[4] * yd + hInv[5]
            val dw = hInv[6]
            val dnx = hInv[0]
            val dny = hInv[3]
            var col = 0
            while (col < pageW) {
                val i = row * pageW + col
                var pureX = 0.0
                var pureY = 0.0
                if (w.isFinite() && abs(w) > 1e-9) {
                    pureX = nx / w
                    pureY = ny / w
                }
                var finalX = pureX
                var finalY = pureY
                if (strength > 0.0 && pureX.isFinite() && pureY.isFinite()) {
                    val gx = gridPageX[i].toDouble()
                    val gy = gridPageY[i].toDouble()
                    if (gx.isFinite() && gy.isFinite()) {
                        val gw = hInv[6] * gx + hInv[7] * gy + hInv[8]
                        if (gw.isFinite() && abs(gw) > 1e-9) {
                            val gOrigX = (hInv[0] * gx + hInv[1] * gy + hInv[2]) / gw
                            val gOrigY = (hInv[3] * gx + hInv[4] * gy + hInv[5]) / gw
                            var dx = gOrigX - pureX
                            var dy = gOrigY - pureY
                            val mag = hypot(dx, dy)
                            if (mag.isFinite() && mag > maxDisp) {
                                val s = maxDisp / mag
                                dx *= s
                                dy *= s
                                clamped++
                            }
                            finalX = pureX + strength * dx
                            finalY = pureY + strength * dy
                        }
                    }
                }
                if (finalX.isFinite() && finalY.isFinite()) {
                    gridPageX[i] = finalX.toFloat()
                    gridPageY[i] = finalY.toFloat()
                    val applied = hypot(finalX - pureX, finalY - pureY) / diag
                    if (applied.isFinite() && applied > maxApplied) maxApplied = applied
                } else {
                    // Degenerate homography point: keep the grid sample (validated finite above).
                    gridPageX[i] = pureX.toFloat()
                    gridPageY[i] = pureY.toFloat()
                }
                w += dw
                nx += dnx
                ny += dny
                col++
            }
        }

        return Applied(stats, strength, clamped, maxApplied)
    }

    // ==========================================================================================
    // SUBTASK H16 STEP 1: smooth residual reconstruction
    // ==========================================================================================

    /**
     * Applies the adaptive policy using a **smooth residual reconstruction**. The residual is
     * evaluated at the native `gw × gh` control points in ORIGINAL pixel space, then a low-order
     * surface ([fit] = `bicubic` / `poly2` / `poly3`) is evaluated directly at output resolution.
     * `raw` delegates to [applyAdaptive] and keeps the 0.3.3 behavior available for A/B.
     */
    fun applyAdaptiveSmoothed(
        gridControlX: FloatArray,
        gridControlY: FloatArray,
        mapX: FloatArray,
        mapY: FloatArray,
        pageW: Int,
        pageH: Int,
        gw: Int,
        gh: Int,
        hInv: DoubleArray,
        curvature: Double = 0.0,
        fit: String = activeFit()
    ): Applied {
        if (fit == FIT_RAW) {
            return applyAdaptive(mapX, mapY, pageW, pageH, hInv, curvature)
        }
        require(pageW >= 2 && pageH >= 2) { "page must be at least 2x2" }
        require(hInv.size == 9) { "hInv must be a 3x3 row-major matrix" }
        require(gw >= 2 && gh >= 2) { "grid must be at least 2x2" }
        val count = pageW * pageH
        require(mapX.size >= count && mapY.size >= count) {
            "map arrays must hold at least $count samples"
        }
        require(gridControlX.size >= gw * gh && gridControlY.size >= gh * gw) {
            "grid arrays must hold at least ${gw * gh} samples"
        }

        val diag = max(1.0, hypot(pageW.toDouble(), pageH.toDouble()))
        val xMax = (pageW - 1).toDouble()
        val yMax = (pageH - 1).toDouble()
        val n = gw * gh
        val cdx = DoubleArray(n)
        val cdy = DoubleArray(n)

        // --- control residuals in ORIGINAL pixel space ---------------------------------------
        for (row in 0 until gh) {
            val v = row.toDouble() / (gh - 1)
            val nominalY = v * yMax
            for (col in 0 until gw) {
                val u = col.toDouble() / (gw - 1)
                val nominalX = u * xMax
                val k = row * gw + col
                val gxNorm = gridControlX[k].toDouble()
                val gyNorm = gridControlY[k].toDouble()
                if (!gxNorm.isFinite() || !gyNorm.isFinite()) continue
                val gxPage = (gxNorm + 1.0) * 0.5 * xMax
                val gyPage = (gyNorm + 1.0) * 0.5 * yMax
                val pw = hInv[6] * nominalX + hInv[7] * nominalY + hInv[8]
                val gww = hInv[6] * gxPage + hInv[7] * gyPage + hInv[8]
                if (!pw.isFinite() || !gww.isFinite() || abs(pw) < 1e-9 || abs(gww) < 1e-9) continue
                val pOrigX = (hInv[0] * nominalX + hInv[1] * nominalY + hInv[2]) / pw
                val pOrigY = (hInv[3] * nominalX + hInv[4] * nominalY + hInv[5]) / pw
                val gOrigX = (hInv[0] * gxPage + hInv[1] * gyPage + hInv[2]) / gww
                val gOrigY = (hInv[3] * gxPage + hInv[4] * gyPage + hInv[5]) / gww
                if (!pOrigX.isFinite() || !pOrigY.isFinite() ||
                    !gOrigX.isFinite() || !gOrigY.isFinite()
                ) continue
                cdx[k] = gOrigX - pOrigX
                cdy[k] = gOrigY - pOrigY
            }
        }

        val surface: ResidualSurface = when (fit) {
            FIT_POLY2 -> PolySurface.fit(cdx, cdy, gw, gh, 2)
            FIT_POLY3 -> PolySurface.fit(cdx, cdy, gw, gh, 3)
            else -> BicubicSurface(cdx, cdy, gw, gh)
        } ?: BicubicSurface(cdx, cdy, gw, gh)

        // --- statistics on a 65×65 lattice of the SMOOTH field --------------------------------
        val stats = surfaceStats(surface, diag, curvature)
        val strength = strengthFor(stats.mean)
        val maxDisp = RESIDUAL_MAX_CLAMP * diag

        var clamped = 0
        var maxApplied = 0.0
        for (row in 0 until pageH) {
            val yd = row.toDouble()
            val vNorm = if (yMax > 0) (yd / yMax) * 2.0 - 1.0 else 0.0
            var w = hInv[7] * yd + hInv[8]
            var nx = hInv[1] * yd + hInv[2]
            var ny = hInv[4] * yd + hInv[5]
            for (col in 0 until pageW) {
                val i = row * pageW + col
                var pureX = 0.0
                var pureY = 0.0
                if (w.isFinite() && abs(w) > 1e-9) {
                    pureX = nx / w
                    pureY = ny / w
                }
                var finalX = pureX
                var finalY = pureY
                if (strength > 0.0 && pureX.isFinite() && pureY.isFinite()) {
                    val uNorm = if (xMax > 0) (col.toDouble() / xMax) * 2.0 - 1.0 else 0.0
                    var dx = surface.evalX(uNorm, vNorm)
                    var dy = surface.evalY(uNorm, vNorm)
                    if (dx.isFinite() && dy.isFinite()) {
                        val mag = hypot(dx, dy)
                        if (mag > maxDisp) {
                            val s = maxDisp / mag
                            dx *= s
                            dy *= s
                            clamped++
                        }
                        finalX = pureX + strength * dx
                        finalY = pureY + strength * dy
                    }
                }
                if (finalX.isFinite() && finalY.isFinite()) {
                    mapX[i] = finalX.toFloat()
                    mapY[i] = finalY.toFloat()
                    val applied = hypot(finalX - pureX, finalY - pureY) / diag
                    if (applied.isFinite() && applied > maxApplied) maxApplied = applied
                } else {
                    mapX[i] = pureX.toFloat()
                    mapY[i] = pureY.toFloat()
                }
                w += hInv[6]
                nx += hInv[0]
                ny += hInv[3]
            }
        }
        return Applied(stats, strength, clamped, maxApplied)
    }

    private fun surfaceStats(
        surface: ResidualSurface,
        diag: Double,
        curvature: Double
    ): ResidualStats {
        val steps = 64
        var sum = 0.0
        var sumSq = 0.0
        var maxFrac = 0.0
        var samples = 0L
        val hist = IntArray(HIST_BINS)
        for (j in 0..steps) {
            val v = j.toDouble() / steps * 2.0 - 1.0
            for (i in 0..steps) {
                val u = i.toDouble() / steps * 2.0 - 1.0
                val dx = surface.evalX(u, v)
                val dy = surface.evalY(u, v)
                if (!dx.isFinite() || !dy.isFinite()) continue
                val frac = hypot(dx, dy) / diag
                if (!frac.isFinite()) continue
                sum += frac
                sumSq += frac * frac
                if (frac > maxFrac) maxFrac = frac
                val bin = min(HIST_BINS - 1,
                    max(0, (frac / HIST_MAX * (HIST_BINS - 1)).toInt()))
                hist[bin]++
                samples++
            }
        }
        val mean = if (samples == 0L) 0.0 else sum / samples.toDouble()
        val rms = if (samples == 0L) 0.0 else kotlin.math.sqrt(sumSq / samples.toDouble())
        return ResidualStats(mean, maxFrac, percentile(hist, samples), curvature, rms)
    }

    /** Smallest Jacobian determinant of a page-resolution sampling map on a coarse lattice. */
    fun minJacobian(mapX: FloatArray, mapY: FloatArray, pageW: Int, pageH: Int): Double {
        if (pageW < 4 || pageH < 4 || mapX.size < pageW * pageH || mapY.size < pageW * pageH) {
            return 1.0
        }
        val step = max(1, min(pageW, pageH) / 80)
        var minJac = Double.MAX_VALUE
        var y = step
        while (y < pageH - step) {
            var x = step
            while (x < pageW - step) {
                val i = y * pageW + x
                val j11 = (mapX[i + step].toDouble() - mapX[i - step]) / (2.0 * step)
                val j21 = (mapY[i + step].toDouble() - mapY[i - step]) / (2.0 * step)
                val j12 = (mapX[i + step * pageW].toDouble() - mapX[i - step * pageW]) / (2.0 * step)
                val j22 = (mapY[i + step * pageW].toDouble() - mapY[i - step * pageW]) / (2.0 * step)
                val det = j11 * j22 - j12 * j21
                if (det.isFinite() && det < minJac) minJac = det
                x += step
            }
            y += step
        }
        return if (minJac == Double.MAX_VALUE) 1.0 else minJac
    }

    private abstract class ResidualSurface {
        abstract fun evalX(u: Double, v: Double): Double
        abstract fun evalY(u: Double, v: Double): Double
    }

    /** Catmull-Rom bicubic interpolation of a `gw × gh` residual control lattice. */
    private class BicubicSurface(
        private val fx: DoubleArray,
        private val fy: DoubleArray,
        private val gw: Int,
        private val gh: Int
    ) : ResidualSurface() {
        override fun evalX(u: Double, v: Double): Double = sample(fx, u, v)
        override fun evalY(u: Double, v: Double): Double = sample(fy, u, v)

        private fun sample(f: DoubleArray, u: Double, v: Double): Double {
            val x = ((u + 1.0) * 0.5) * (gw - 1)
            val y = ((v + 1.0) * 0.5) * (gh - 1)
            val x0 = kotlin.math.floor(x).toInt()
            val y0 = kotlin.math.floor(y).toInt()
            val tx = x - x0
            val ty = y - y0
            var acc = 0.0
            for (j in 0 until 4) {
                val yy = (y0 - 1 + j).coerceIn(0, gh - 1)
                val wy = catmullWeight(j, ty)
                var rowAcc = 0.0
                for (i in 0 until 4) {
                    val xx = (x0 - 1 + i).coerceIn(0, gw - 1)
                    rowAcc += f[yy * gw + xx] * catmullWeight(i, tx)
                }
                acc += rowAcc * wy
            }
            return acc
        }

        private fun catmullWeight(i: Int, t: Double): Double = when (i) {
            0 -> -0.5 * t * t * t + t * t - 0.5 * t
            1 -> 1.5 * t * t * t - 2.5 * t * t + 1.0
            2 -> -1.5 * t * t * t + 2.0 * t * t + 0.5 * t
            else -> 0.5 * t * t * t - 0.5 * t * t
        }
    }

    /** 2D least-squares polynomial `Σ c_ij · u^i · v^j`, `i+j <= order`. */
    private class PolySurface(
        private val cx: DoubleArray,
        private val cy: DoubleArray,
        private val side: Int
    ) : ResidualSurface() {
        override fun evalX(u: Double, v: Double): Double = eval(cx, u, v)
        override fun evalY(u: Double, v: Double): Double = eval(cy, u, v)

        private fun eval(c: DoubleArray, u: Double, v: Double): Double {
            var acc = 0.0
            var vi = 1.0
            var idx = 0
            for (j in 0 until side) {
                var ui = 1.0
                for (i in 0 until side) {
                    val ci = c[idx]
                    if (ci != 0.0) acc += ci * ui * vi
                    idx++
                    ui *= u
                }
                vi *= v
            }
            return acc
        }

        companion object {
            fun fit(
                dx: DoubleArray,
                dy: DoubleArray,
                gw: Int,
                gh: Int,
                order: Int
            ): PolySurface? {
                if (gw < 2 || gh < 2) return null
                val side = order + 1
                val n = side * side
                val ata = Array(n) { DoubleArray(n) }
                val bx = DoubleArray(n)
                val by = DoubleArray(n)
                val phi = DoubleArray(n)
                for (row in 0 until gh) {
                    val v = row.toDouble() / (gh - 1) * 2.0 - 1.0
                    for (col in 0 until gw) {
                        val u = col.toDouble() / (gw - 1) * 2.0 - 1.0
                        var vi = 1.0
                        var idx = 0
                        for (j in 0 until side) {
                            var ui = 1.0
                            for (i in 0 until side) {
                                phi[idx++] = ui * vi
                                ui *= u
                            }
                            vi *= v
                        }
                        val k = row * gw + col
                        for (a in 0 until n) {
                            val pa = phi[a]
                            if (pa == 0.0) continue
                            bx[a] += pa * dx[k]
                            by[a] += pa * dy[k]
                            for (b in a until n) ata[a][b] += pa * phi[b]
                        }
                    }
                }
                for (a in 0 until n) {
                    for (b in 0 until a) ata[a][b] = ata[b][a]
                    ata[a][a] += 1e-9
                }
                val cx = solve(ata, bx) ?: return null
                val cy = solve(ata, by) ?: return null
                return PolySurface(cx, cy, side)
            }

            /** Gaussian elimination with partial pivoting; `null` when singular. */
            private fun solve(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray? {
                val n = bIn.size
                val a = Array(n) { aIn[it].copyOf() }
                val b = bIn.copyOf()
                for (col in 0 until n) {
                    var pivot = col
                    for (r in col + 1 until n) {
                        if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                    }
                    if (abs(a[pivot][col]) < 1e-12) return null
                    if (pivot != col) {
                        val tr = a[pivot]; a[pivot] = a[col]; a[col] = tr
                        val tb = b[pivot]; b[pivot] = b[col]; b[col] = tb
                    }
                    val diag = a[col][col]
                    for (r in col + 1 until n) {
                        val f = a[r][col] / diag
                        if (f == 0.0) continue
                        for (c in col until n) a[r][c] -= f * a[col][c]
                        b[r] -= f * b[col]
                    }
                }
                val x = DoubleArray(n)
                for (r in n - 1 downTo 0) {
                    var acc = b[r]
                    for (c in r + 1 until n) acc -= a[r][c] * x[c]
                    x[r] = acc / a[r][r]
                }
                return if (x.all { it.isFinite() }) x else null
            }
        }
    }

    /**
     * Curvature proxy for the small grid: mean absolute second difference of the normalized
     * residual-magnitude field over the interior nodes. It is CPU-cheap (45×31) and is logged as
     * `curv=`; it is **not** part of the strength decision.
     */
    fun curvature(
        gridX: FloatArray,
        gridY: FloatArray,
        gw: Int,
        gh: Int,
        hInv: DoubleArray,
        pageW: Int,
        pageH: Int
    ): Double {
        if (gw < 3 || gh < 3 || gridX.size < gw * gh || gridY.size < gw * gh) return 0.0
        val diag = max(1.0, hypot(pageW.toDouble(), pageH.toDouble()))
        val xMax = (pageW - 1).toDouble()
        val yMax = (pageH - 1).toDouble()
        val field = DoubleArray(gw * gh)
        for (row in 0 until gh) {
            val nominalY = row.toDouble() / (gh - 1) * yMax
            for (col in 0 until gw) {
                val k = row * gw + col
                val nominalX = col.toDouble() / (gw - 1) * xMax
                val gx = ((gridX[k].toDouble() + 1.0) * 0.5 * xMax)
                val gy = ((gridY[k].toDouble() + 1.0) * 0.5 * yMax)
                val pw = hInv[6] * nominalX + hInv[7] * nominalY + hInv[8]
                val gww = hInv[6] * gx + hInv[7] * gy + hInv[8]
                if (abs(pw) < 1e-9 || abs(gww) < 1e-9) {
                    field[k] = 0.0
                    continue
                }
                val pOrigX = (hInv[0] * nominalX + hInv[1] * nominalY + hInv[2]) / pw
                val pOrigY = (hInv[3] * nominalX + hInv[4] * nominalY + hInv[5]) / pw
                val gOrigX = (hInv[0] * gx + hInv[1] * gy + hInv[2]) / gww
                val gOrigY = (hInv[3] * gx + hInv[4] * gy + hInv[5]) / gww
                field[k] = hypot(gOrigX - pOrigX, gOrigY - pOrigY) / diag
            }
        }
        var sum = 0.0
        var n = 0
        for (row in 1 until gh - 1) {
            for (col in 1 until gw - 1) {
                val k = row * gw + col
                val dxx = abs(field[k - 1] - 2.0 * field[k] + field[k + 1])
                val dyy = abs(field[k - gw] - 2.0 * field[k] + field[k + gw])
                sum += dxx + dyy
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** Row-major 3×3 inverse, or `null` when the matrix is singular/non-finite. */
    fun invert3x3(m: DoubleArray): DoubleArray? {
        if (m.size != 9 || m.any { !it.isFinite() }) return null
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (!det.isFinite() || abs(det) < 1e-12) return null
        val inv = 1.0 / det
        return doubleArrayOf(
            (e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv,
            (f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
            (d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv
        )
    }

    private fun percentile(hist: IntArray, samples: Long): Double {
        if (samples <= 0L) return 0.0
        val target = samples * 0.95
        var cumulative = 0.0
        for (bin in hist.indices) {
            cumulative += hist[bin]
            if (cumulative >= target) {
                // Report the bin centre.
                return (bin + 0.5) / (HIST_BINS - 1) * HIST_MAX
            }
        }
        return HIST_MAX
    }
}
