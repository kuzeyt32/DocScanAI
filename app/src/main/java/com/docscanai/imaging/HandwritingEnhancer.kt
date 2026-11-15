package com.docscanai.imaging

import android.graphics.Bitmap
import com.docscanai.core.util.Diag
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SUBTASK H23 — opt-in faint pencil / light-handwriting enhancement.
 *
 * Runs AFTER the existing [Enhancer] (demux + warp + the user's chosen mode) and immediately before
 * the page is saved. Pure OpenCV/Kotlin: no new dependency, no model weights, and it never touches
 * the raw capture.
 *
 * ### What the first real-data run proved (docs/HANDWRITING_ENHANCEMENT.md §6.2)
 * The first shipped-candidate chain (mean-preserving flatten + paper-anchored tone LUT + cored
 * unsharp) failed every gate on the 12 real pages: contrast −30 %, non-stroke PSNR 15 dB, stroke
 * sharpness 0.16×. The cause was measurable, not mysterious:
 *  * the flatten normalised the paper to `mean(gray)`, and on these archival scans a 20–30 % black
 *    mount drags the mean ~25 % below the paper level, so the entire page was darkened;
 *  * the tone LUT took `mean(L)` as the "paper" level, i.e. 128 while the paper was at ~200, so
 *    every real paper tone fell into the identity branch and the pencil was never touched.
 * Both anchors are now the **paper percentile**, and the tone stage is replaced by a detail boost
 * whose weight is gated on *coherence* rather than only on amplitude.
 *
 * ### The chain that is measured now
 *  1. **Flat-field, paper-anchored** — divide by a large-radius background estimate normalised to
 *     the paper level `P` (92nd percentile of grey), with the divisor clamped to
 *     `[P / FLAT_GAIN_MAX, min(255, P · FLAT_DARKEN_MAX)]`. Result: gain is exactly 1.0 on paper,
 *     it can lift a badly lit corner by at most [FLAT_GAIN_MAX] and can never darken the page by
 *     more than [FLAT_DARKEN_MAX]. This is why the page's paper level — and therefore the
 *     non-stroke PSNR — survives the stage.
 *  2. **Stroke-band boost on Lab L** — a difference-of-Gaussians band-pass at the stroke scale
 *     (`band = blur(L, σ_fine) − blur(L, σ_coarse)`, positive on thin dark structures), then
 *     `L' = L − BOOST · max(band,0) · w · f` with
 *       `w = 0` below [KNEE_LOW] and `1` above [KNEE_HIGH]  (grain / paper-texture protection),
 *       `f = 1` below [INK_LOW] and `0` above [INK_HIGH]      (already-dark ink / mount edges safe).
 *     A DoG rather than a single Gaussian matters on real paper: the texture of a 19th-century
 *     scan is a *low-frequency* 7-level signal, and a one-sided high-pass lets it through (measured
 *     failure, §6.2). Blank paper stays bit-identical, so the noise and PSNR gates are not spent,
 *     while stroke cores deepen.
 *  3. **Gentle grey-world white balance** — clamped to `[WB_MIN_GAIN, WB_MAX_GAIN]`, skipped when
 *     the channels already agree within [WB_SKIP_FRACTION]; removes a page cast without inventing
 *     colour.
 *
 * ## SUBTASK H25 — attempt 2: a *shape* discriminant (the production default is `RIDGE`)
 *
 * H23 proved that no amplitude/scale operator can separate a 20.2-level pencil mark from the
 * 13.5-level texture of the same paper: whatever gain reaches the mark also reaches the texture.
 * The H25 candidate therefore gates the *same* stroke band with a **Frangi/Hessian ridge response**
 * — an elongation test, not an amplitude test. On the scaled Hessian of the smoothed L channel
 *
 * ```
 * λ1, λ2 = eigenvalues of σ²·∇²(G_σ * L),  |λ1| ≤ |λ2|
 * R_B    = |λ1| / (|λ2| + ε)                 0 for a line, ≈ 1 for a blob
 * S      = |λ2|                              σ²-normalised structureness (level units)
 * w      = ramp(R_B; RATIO_MIN..RATIO_MAX) · ramp(S; STRUCT_LOW..STRUCT_HIGH) · [S > 0]
 * ```
 *
 * A pencil stroke is long and thin (R_B ≈ 0.1–0.3, S ≈ 10–40 levels); aged-paper mottling is a
 * blob (R_B ≈ 0.7–1.0) and a JPEG-rounded grain is weak (S < 3). That is the one discriminator
 * amplitude and scale cannot provide. `w` then multiplies the existing knee + ink guard, and the
 * band is applied as an 8-bit saturating subtract so the stage needs no full-resolution float
 * buffer (the H23 DoG chain cost 205 ms; see `docs/HANDWRITING_ENHANCEMENT.md` §10).
 *
 * ### Variants (mechanical keep/revert, at most one alternative)
 *  - [Variant.FULL] — the **production default**, and the H23 DoG baseline.
 *  - [Variant.RIDGE] — the H25 candidate. It is **measurement-only**: it failed the H25 gate
 *    (mean contrast +5.27 % vs the +30 % target, non-stroke PSNR 27.75 dB vs 30 dB, connected
 *    components +128 %, 142.5 ms median at 2000 px vs the 120 ms budget), so the default was
 *    reverted to [Variant.FULL] and the shipped path is byte-identical to 0.3.6 / vc 9.
 *    See `docs/HANDWRITING_ENHANCEMENT.md` "Attempt 2 — reuse-first" and
 *    `tooling/out/h25/SUMMARY.txt`.
 *  - [Variant.CLAHE_ONLY] — flat-field + CLAHE on Lab L. The textbook local-contrast alternative;
 *    it is measured, not assumed, and it is expected to lift blank noise because adaptive
 *    equalisation has no stroke bins to spend its range on.
 *
 * Never throws: on any failure the source is copied unchanged.
 */
class HandwritingEnhancer {

    /** The measured variants. Exactly one may ship. */
    enum class Variant(val id: String) {
        RIDGE("ridge"),
        FULL("full"),
        CLAHE_ONLY("clahe")
    }

    /**
     * Result of the cheap "is this page faint writing?" measurement (UI suggestion only).
     *
     * @param contrastLevels mean depth of the faint marks below the local paper level (0..255 scale)
     * @param strokeFraction fraction of the paper region covered by stroke-like marks
     * @param paperLevel 92nd percentile of grey (the paper level), 0..255
     */
    data class Suggestion(
        val suggested: Boolean,
        val contrastLevels: Double,
        val strokeFraction: Double,
        val paperLevel: Double
    )

    fun enhance(source: Bitmap, variant: Variant = Variant.FULL): Bitmap {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return source
        val start = System.currentTimeMillis()
        Diag.stageEnter("Handwriting", "variant=${variant.id} ${Diag.describe(source)}")
        val rgba = Mat()
        val bgr = Mat()
        val lab = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            flattenIllumination(bgr)

            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            val channels = ArrayList<Mat>(3)
            Core.split(lab, channels)
            try {
                val l = channels[0]
                when (variant) {
                    Variant.CLAHE_ONLY -> {
                        val clahe = Imgproc.createCLAHE(
                            CLAHE_CLIP, Size(CLAHE_TILES.toDouble(), CLAHE_TILES.toDouble())
                        )
                        clahe.apply(l, l)
                    }
                    Variant.FULL -> strokeDetailBoost(l, max(bgr.cols(), bgr.rows()))
                    Variant.RIDGE -> ridgeStrokeBoost(l, max(bgr.cols(), bgr.rows()))
                }
                Core.merge(channels, lab)
            } finally {
                channels.forEach { runCatching { it.release() } }
            }
            Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR)

            grayWorld(bgr)

            val outRgba = Mat()
            val out = Bitmap.createBitmap(bgr.cols(), bgr.rows(), Bitmap.Config.ARGB_8888)
            try {
                Imgproc.cvtColor(bgr, outRgba, Imgproc.COLOR_BGR2RGBA)
                Utils.matToBitmap(outRgba, out)
            } finally {
                outRgba.release()
            }
            Diag.stageExit(
                "Handwriting", System.currentTimeMillis() - start,
                "variant=${variant.id} ${Diag.describe(out)}"
            )
            return out
        } catch (t: Throwable) {
            // Catch Throwable (incl. OutOfMemoryError); degrade to an unmodified copy.
            Diag.logThrowable("Handwriting", t)
            Diag.stageExit("Handwriting", System.currentTimeMillis() - start, "fallback=copy")
            return source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        } finally {
            rgba.release()
            bgr.release()
            lab.release()
        }
    }

    // --- 0. suggestion measurement -----------------------------------------------------------

    /**
     * Cheap measurement on a 512 px copy. A page is *suggested* when it has a paper-like region that
     * carries a small, coherent, shallow set of marks: marks are counted only after a 3×3 erosion,
     * so isolated paper grain cannot fake a stroke, and the mean mark depth must be shallow
     * ([SUGGEST_MIN_DEPTH]..[SUGGEST_MAX_DEPTH]) — printed text is far deeper and is correctly
     * rejected. This is a **faint-mark detector**, not a handwriting classifier (documented limit).
     */
    fun measure(source: Bitmap): Suggestion {
        val rgba = Mat()
        val small = Mat()
        val bg = Mat()
        val diff = Mat()
        val region = Mat()
        val faint = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, small, Imgproc.COLOR_RGBA2GRAY)
            val scale = SUGGEST_LONG_EDGE.toDouble() / max(small.cols(), small.rows()).coerceAtLeast(1)
            if (scale < 1.0) {
                Imgproc.resize(
                    small, small,
                    Size(max(16.0, small.cols() * scale), max(16.0, small.rows() * scale)),
                    0.0, 0.0, Imgproc.INTER_AREA
                )
            }
            val k = oddKernel(max(7, min(small.cols(), small.rows()) / 32))
            Imgproc.GaussianBlur(small, bg, Size(k.toDouble(), k.toDouble()), 0.0)
            Core.subtract(bg, small, diff)

            val grayBytes = ByteArray(small.cols() * small.rows())
            small.get(0, 0, grayBytes)
            val paper = paperLevel(grayBytes).coerceIn(0.0, 255.0)

            // Paper region: background still paper-like. Keeps archival mounts/black borders out.
            Imgproc.threshold(bg, region, paper * PAPER_REGION_FRAC, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(diff, faint, SUGGEST_FAINT_LEVELS, 255.0, Imgproc.THRESH_BINARY)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            Imgproc.erode(faint, faint, kernel)
            kernel.release()
            Core.bitwise_and(faint, region, faint)

            val regionPixels = Core.countNonZero(region).toDouble()
            val faintPixels = Core.countNonZero(faint).toDouble()
            val strokeFraction = if (regionPixels > 0.0) faintPixels / regionPixels else 0.0
            val depth = if (faintPixels > 0.0) Core.mean(diff, faint).`val`[0] else 0.0

            val suggested = paper >= SUGGEST_MIN_PAPER &&
                strokeFraction in SUGGEST_MIN_STROKE_FRACTION..SUGGEST_MAX_STROKE_FRACTION &&
                depth >= SUGGEST_MIN_DEPTH && depth <= SUGGEST_MAX_DEPTH
            Diag.log(
                "Handwriting",
                "measure paper=${fmt(paper)} faint=${fmt(strokeFraction * 100.0)}% " +
                    "depth=${fmt(depth)} suggested=$suggested"
            )
            return Suggestion(suggested, depth, strokeFraction, paper)
        } catch (t: Throwable) {
            Diag.logThrowable("Handwriting.measure", t)
            return Suggestion(false, -1.0, -1.0, -1.0)
        } finally {
            rgba.release(); small.release(); bg.release(); diff.release()
            region.release(); faint.release()
        }
    }

    // --- 1. flat-field ------------------------------------------------------------------------

    /**
     * Paper-anchored divide-by-background on each BGR channel:
     * `out = ch · P / clamp(B, P/FLAT_GAIN_MAX, min(255, P·FLAT_DARKEN_MAX))` where `P` is the
     * 92nd grey percentile (paper). Gain is exactly 1 where the background equals the paper level,
     * so a flat page passes through nearly untouched; only genuinely darker illumination is lifted,
     * and by at most [FLAT_GAIN_MAX].
     */
    private fun flattenIllumination(bgr: Mat) {
        val gray = Mat()
        val small = Mat()
        val bgSmall = Mat()
        val bg = Mat()
        val scaled = Mat()
        val channels = ArrayList<Mat>(3)
        try {
            val w = bgr.cols()
            val h = bgr.rows()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val scale = BACKGROUND_LONG_EDGE.toDouble() / max(w, h).coerceAtLeast(1)
            val sw = max(16, (w * scale).roundToInt())
            val sh = max(16, (h * scale).roundToInt())
            Imgproc.resize(gray, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val k = oddKernel(max(15, min(sw, sh) / BACKGROUND_KERNEL_DIVISOR))
            Imgproc.GaussianBlur(small, bgSmall, Size(k.toDouble(), k.toDouble()), 0.0)
            Imgproc.resize(bgSmall, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

            val bytes = ByteArray(sw * sh)
            small.get(0, 0, bytes)
            // Anchor on the *typical* paper level, not the brightest paper: anchoring on p92 lifts
            // the whole page by ~4 % (measured: paper 203 -> 211) and lightens the marks with it.
            val paper = paperLevel(bytes).coerceIn(48.0, 250.0)
            val floor = paper / FLAT_GAIN_MAX
            val ceiling = min(255.0, paper * FLAT_DARKEN_MAX)
            Core.max(bg, Scalar(floor), bg)
            Core.min(bg, Scalar(ceiling), bg)

            Core.split(bgr, channels)
            for (channel in channels) {
                Core.divide(channel, bg, scaled, paper)
                scaled.copyTo(channel)
            }
            Core.merge(channels, bgr)
        } finally {
            gray.release(); small.release(); bgSmall.release(); bg.release()
            scaled.release()
            channels.forEach { runCatching { it.release() } }
        }
    }

    // --- 2. stroke detail boost ---------------------------------------------------------------

    /**
     * Stroke-scale detail amplification on Lab L, using a difference-of-Gaussians band-pass.
     *
     * ```
     * band  = blur(L, σ_fine) − blur(L, σ_coarse)   positive on thin dark structures
     * depth = blur(L, σ_coarse) − L                 local darkness (for ink protection)
     * w     = clamp((band − KNEE_LOW) / (KNEE_HIGH − KNEE_LOW), 0, 1)
     * f     = clamp((INK_HIGH − depth) / (INK_HIGH − INK_LOW), 0, 1)
     * L'    = L − BOOST · max(band,0) · w · f
     * ```
     *
     * The first version of this stage used a single large Gaussian (`D = blur(L,σ) − L`) and failed
     * on real data: this paper's *texture* is a 7.4-level low-frequency signal that survives a
     * σ≈1.3 blur, so the stage subtracted ~6 levels of "detail" from every pixel — contrast −3 %,
     * non-stroke PSNR 22.8 dB, stroke sharpness 0.26×. A DoG band-pass is the standard fix (a
     * centre–surround / Marr–Hildreth band): slow paper blobs and pixel grain are attenuated by the
     * two-sided filter, while 2–4 px pencil strokes sit inside the pass band.
     *
     * Paper below the knee is left **bit-identical**; deep ink and the archival mount (depth above
     * [INK_HIGH]) are left alone, so nothing can be pushed into clipping.
     */
    private fun strokeDetailBoost(l: Mat, longEdge: Int) {
        val lf = Mat()
        val band = Mat()
        val depth = Mat()
        val weight = Mat()
        val ink = Mat()
        val inc = Mat()
        try {
            l.convertTo(lf, CvType.CV_32F)
            val sigmaFine = max(0.8, longEdge * DETAIL_SIGMA_FRAC)
            val sigmaCoarse = max(3.0, longEdge * CONTEXT_SIGMA_FRAC)
            Imgproc.GaussianBlur(lf, band, Size(0.0, 0.0), sigmaFine)
            Imgproc.GaussianBlur(lf, depth, Size(0.0, 0.0), sigmaCoarse)
            // band = fine − coarse, depth = coarse − L  (both in place, no extra buffers)
            Core.subtract(band, depth, band)
            Core.subtract(depth, lf, depth)

            // weight: linear knee on the pass-band response
            Core.subtract(band, Scalar(KNEE_LOW), weight)
            weight.convertTo(weight, -1, 1.0 / (KNEE_HIGH - KNEE_LOW), 0.0)
            Core.max(weight, Scalar(0.0), weight)
            Core.min(weight, Scalar(1.0), weight)

            // ink: fade out as the local depth approaches already-black territory
            val fill = Mat(l.size(), CvType.CV_32F, Scalar(INK_HIGH))
            try {
                Core.subtract(fill, depth, ink)
            } finally {
                fill.release()
            }
            ink.convertTo(ink, -1, 1.0 / (INK_HIGH - INK_LOW), 0.0)
            Core.max(ink, Scalar(0.0), ink)
            Core.min(ink, Scalar(1.0), ink)

            // only dark band response is amplified: inc = max(band,0) · w · f · BOOST
            Core.max(band, Scalar(0.0), inc)
            Core.multiply(inc, weight, inc)
            Core.multiply(inc, ink, inc, STROKE_BOOST)

            Core.subtract(lf, inc, lf)
            Core.max(lf, Scalar(0.0), lf)
            Core.min(lf, Scalar(255.0), lf)
            lf.convertTo(l, CvType.CV_8U)
        } finally {
            lf.release(); band.release(); depth.release()
            weight.release(); ink.release(); inc.release()
        }
    }

    // --- 2b. H25 ridge (shape) gate -------------------------------------------------------------

    /**
     * H25 attempt 2 — the same stroke band as [strokeDetailBoost], but gated by a **Hessian ridge
     * response** instead of by amplitude alone.
     *
     * ```text
     * Hessian at σ  →  λ1, λ2            (|λ1| ≤ |λ2|, σ²·L'' normalised to "levels")
     * R_B = |λ1| / (|λ2| + ε)             elongation: ≈0 on a line, ≈1 on a blob
     * S   = |λ2|                          structureness, in levels
     * w   = ramp(R_B) · ramp(S) · [λ2 > 0] · knee(band) · inkGuard(depth)
     * L'  = L − BOOST · knee(band) · w
     * ```
     *
     * Why this is the right second attempt: H23 showed the pencil mark is 20.2 levels deep and the
     * paper's own texture 13.5 levels, so *amplitude* cannot separate them. Elongation can. A
     * graphite stroke is a 2–4 px wide line tens of pixels long, so at the stroke scale the smaller
     * Hessian eigenvalue is ~an order of magnitude below the larger one. Paper mottling and JPEG
     * blocking are blobs: their two eigenvalues are comparable. `[LIT]` This is the Frangi vesselness
     * construction (Frangi et al., MICCAI 1998) with the vessel-specific terms removed; it is
     * arithmetic on published operators, so it carries no licence and no model weight.
     *
     * Cost control: the Hessian runs on a bounded [RIDGE_EDGE] copy (the gate is a smooth mask, so
     * it survives bilinear upsampling), and the band/guard stage is performed entirely in 8-bit
     * saturating arithmetic, which is why this variant fits the 120 ms budget where the H23
     * full-resolution 32-bit DoG chain did not (205 ms).
     */
    private fun ridgeStrokeBoost(l: Mat, longEdge: Int) {
        val work = Mat()
        val g = Mat()
        val lxx = Mat()
        val lyy = Mat()
        val lxy = Mat()
        val cross = Mat()
        val t = Mat()
        val half = Mat()
        val spread = Mat()
        val lambda1 = Mat()
        val lambda2 = Mat()
        val abs1 = Mat()
        val abs2 = Mat()
        val ratio = Mat()
        var ratioCeiling = Mat()
        val ridge = Mat()
        val structure = Mat()
        val dark = Mat()
        val gate8 = Mat()
        val gateFull = Mat()
        val blurFine = Mat()
        val band = Mat()
        val knee = Mat()
        val weight = Mat()
        val blurCoarse = Mat()
        val guard = Mat()
        val inc = Mat()
        try {
            // --- Hessian at the stroke scale, on a bounded working copy ----------------------
            val scale = min(1.0, RIDGE_EDGE.toDouble() / longEdge.coerceAtLeast(1))
            val ww = max(RIDGE_MIN_EDGE, (l.cols() * scale).roundToInt())
            val hh = max(RIDGE_MIN_EDGE, (l.rows() * scale).roundToInt())
            Imgproc.resize(l, work, Size(ww.toDouble(), hh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            work.convertTo(g, CvType.CV_32F)

            val sigmaPx = max(RIDGE_SIGMA_MIN, longEdge * RIDGE_SIGMA_FRAC)
            val sigma = (sigmaPx * scale).coerceAtLeast(0.6)
            Imgproc.GaussianBlur(g, g, Size(0.0, 0.0), sigma)
            // The 3-tap second-derivative stencil [1,0,-2,0,1] has gain 4 relative to L''; divide it
            // out so σ²·λ is expressed in the same "levels" as the stroke depth the gates measure.
            val norm = sigma * sigma / 4.0
            Imgproc.Sobel(g, lxx, CvType.CV_32F, 2, 0, 3, norm, 0.0)
            Imgproc.Sobel(g, lyy, CvType.CV_32F, 0, 2, 3, norm, 0.0)
            Imgproc.Sobel(g, lxy, CvType.CV_32F, 1, 1, 3, norm, 0.0)

            Core.addWeighted(lxx, 0.5, lyy, 0.5, 0.0, t) // (Lxx + Lyy) / 2
            Core.addWeighted(lxx, 0.5, lyy, -0.5, 0.0, half) // (Lxx − Lyy) / 2
            Core.multiply(half, half, spread)
            Core.multiply(lxy, lxy, cross)
            Core.add(spread, cross, spread)
            Core.sqrt(spread, spread) // sqrt(d² + Lxy²)
            Core.subtract(t, spread, lambda1) // smaller |eigenvalue|
            Core.add(t, spread, lambda2) // larger  |eigenvalue|
            Core.absdiff(lambda1, Scalar(0.0), abs1)
            Core.absdiff(lambda2, Scalar(0.0), abs2)

            // R_B = |λ1| / (|λ2| + ε), then ramp to 0..255 with RATIO_MIN ⇒ 255, RATIO_MAX ⇒ 0
            Core.add(abs2, Scalar(RIDGE_EIGEN_EPS), abs2)
            Core.divide(abs1, abs2, ratio)
            ratioCeiling = Mat(ratio.size(), CvType.CV_32F, Scalar(RIDGE_RATIO_MAX))
            Core.subtract(ratioCeiling, ratio, ratio)
            ratio.convertTo(
                ridge, CvType.CV_8U,
                255.0 / (RIDGE_RATIO_MAX - RIDGE_RATIO_MIN), 0.0
            )

            // structureness ramp on |λ2| (saturating convert = clamp), then dark polarity | gate
            abs2.convertTo(
                structure, CvType.CV_8U,
                255.0 / (RIDGE_STRUCT_HIGH - RIDGE_STRUCT_LOW),
                -RIDGE_STRUCT_LOW * 255.0 / (RIDGE_STRUCT_HIGH - RIDGE_STRUCT_LOW)
            )
            Core.compare(lambda2, Scalar(0.0), dark, Core.CMP_GT)
            Core.multiply(ridge, structure, gate8, 1.0 / 255.0)
            Core.multiply(gate8, dark, gate8, 1.0 / 255.0)
            Imgproc.resize(gate8, gateFull, l.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)

            // --- 8-bit band + knee + ink guard at full resolution ---------------------------
            // `band` stays in LEVELS: the knee and the ridge gate are applied as *weights*, so the
            // increment is always proportional to the mark's own depth. (The first implementation
            // folded the knee into `band`, which clamped every gated pixel to a 255-level
            // increment; that is what the first H25 run measured and why it was fixed.)
            val sigmaFine = max(1.0, longEdge * DETAIL_SIGMA_FRAC)
            Imgproc.GaussianBlur(l, blurFine, Size(0.0, 0.0), sigmaFine)
            Core.subtract(blurFine, l, band) // saturating 8-bit: max(blur − L, 0), in levels
            band.convertTo(
                knee, CvType.CV_8U,
                255.0 / (KNEE_HIGH - KNEE_LOW), -KNEE_LOW * 255.0 / (KNEE_HIGH - KNEE_LOW)
            )
            Core.multiply(gateFull, knee, weight, 1.0 / 255.0)

            val sigmaCoarse = max(3.0, longEdge * CONTEXT_SIGMA_FRAC)
            Imgproc.GaussianBlur(l, blurCoarse, Size(0.0, 0.0), sigmaCoarse)
            Core.subtract(blurCoarse, l, guard) // local depth, saturating at 0
            guard.convertTo(
                guard, CvType.CV_8U,
                -255.0 / (INK_HIGH - INK_LOW), INK_HIGH * 255.0 / (INK_HIGH - INK_LOW)
            )
            Core.multiply(weight, guard, weight, 1.0 / 255.0)
            Core.multiply(band, weight, inc, STROKE_BOOST_RIDGE / 255.0)
            Core.subtract(l, inc, l) // saturating: never below 0
        } finally {
            work.release(); g.release(); lxx.release(); lyy.release(); lxy.release()
            cross.release(); t.release(); half.release(); spread.release()
            lambda1.release(); lambda2.release(); abs1.release(); abs2.release()
            ratio.release(); ratioCeiling.release(); ridge.release(); structure.release()
            dark.release(); gate8.release(); gateFull.release(); blurFine.release()
            band.release(); knee.release(); weight.release()
            blurCoarse.release(); guard.release(); inc.release()
        }
    }

    // --- 3. white balance ---------------------------------------------------------------------

    private fun grayWorld(bgr: Mat) {
        val mean = Core.mean(bgr)
        val b = mean.`val`[0]
        val g = mean.`val`[1]
        val r = mean.`val`[2]
        if (b <= 1e-3 || g <= 1e-3 || r <= 1e-3) return
        val target = (b + g + r) / 3.0
        val dev = maxOf(kotlin.math.abs(target - b), kotlin.math.abs(target - g), kotlin.math.abs(target - r))
        if (dev / target < WB_SKIP_FRACTION) return // already neutral: do nothing at all
        val gains = doubleArrayOf(
            (target / b).coerceIn(WB_MIN_GAIN, WB_MAX_GAIN),
            (target / g).coerceIn(WB_MIN_GAIN, WB_MAX_GAIN),
            (target / r).coerceIn(WB_MIN_GAIN, WB_MAX_GAIN)
        )
        val channels = ArrayList<Mat>(3)
        Core.split(bgr, channels)
        try {
            for (i in channels.indices) channels[i].convertTo(channels[i], -1, gains[i], 0.0)
            Core.merge(channels, bgr)
        } finally {
            channels.forEach { runCatching { it.release() } }
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun oddKernel(value: Int): Int {
        val v = value.coerceAtLeast(3)
        return if (v % 2 == 0) v + 1 else v
    }

    /** Histogram percentile of an 8-bit buffer (0..1). */
    private fun percentile(bytes: ByteArray, fraction: Double): Double {
        val hist = IntArray(256)
        for (b in bytes) hist[b.toInt() and 0xFF]++
        val target = bytes.size * fraction
        var cumulative = 0.0
        for (i in 0..255) {
            cumulative += hist[i]
            if (cumulative >= target) return i.toDouble()
        }
        return 255.0
    }

    /**
     * The page's paper level: the **median of the paper population**, where the paper population is
     * every pixel at or above [PAPER_FLOOR_FRAC] of the 92nd percentile. Taking the median of that
     * population instead of the percentile itself is what keeps the page's brightness unchanged
     * (a p92 anchor lifted a real page from 203 to 211 and made every mark shallower).
     */
    private fun paperLevel(bytes: ByteArray): Double {
        val hi = percentile(bytes, PAPER_PERCENTILE)
        val cut = (hi * PAPER_FLOOR_FRAC).toInt().coerceIn(0, 255)
        val hist = IntArray(256)
        var count = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v >= cut) {
                hist[v]++
                count++
            }
        }
        if (count == 0) return hi
        val target = count / 2.0
        var cumulative = 0.0
        for (i in cut..255) {
            cumulative += hist[i]
            if (cumulative >= target) return i.toDouble()
        }
        return hi
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    companion object {
        // 1) flat-field
        private const val BACKGROUND_LONG_EDGE = 512
        private const val BACKGROUND_KERNEL_DIVISOR = 6
        private const val PAPER_PERCENTILE = 0.92
        private const val PAPER_FLOOR_FRAC = 0.80
        private const val PAPER_REGION_FRAC = 0.70
        private const val FLAT_GAIN_MAX = 1.22
        private const val FLAT_DARKEN_MAX = 1.06

        // 2) stroke detail boost (difference-of-Gaussians pass band at the stroke scale)
        private const val DETAIL_SIGMA_FRAC = 0.0009 // σ_fine ≈ 1.3 px at 1400 px
        private const val CONTEXT_SIGMA_FRAC = 0.0032 // σ_coarse ≈ 4.5 px at 1400 px
        private const val KNEE_LOW = 3.0
        private const val KNEE_HIGH = 7.0
        private const val STROKE_BOOST = 1.00
        private const val INK_LOW = 45.0
        private const val INK_HIGH = 85.0

        // 2b) H25 ridge (shape) gate — Frangi/Hessian response at the stroke scale
        private const val RIDGE_EDGE = 1000 // Hessian evaluated on this long edge
        private const val RIDGE_MIN_EDGE = 64
        private const val RIDGE_SIGMA_FRAC = 0.0011 // σ ≈ 1.5 px at 1400, 2.2 px at 2000
        private const val RIDGE_SIGMA_MIN = 1.2
        private const val RIDGE_EIGEN_EPS = 1e-3
        private const val RIDGE_RATIO_MIN = 0.15 // R_B at/below this is a clean line
        private const val RIDGE_RATIO_MAX = 0.55 // R_B at/above this is a blob
        private const val RIDGE_STRUCT_LOW = 6.0 // σ²·|λ2| in levels: weak / texture
        private const val RIDGE_STRUCT_HIGH = 14.0 // σ²·|λ2| in levels: full stroke
        private const val STROKE_BOOST_RIDGE = 0.65

        // 3) grey-world white balance
        private const val WB_MIN_GAIN = 0.97
        private const val WB_MAX_GAIN = 1.03
        private const val WB_SKIP_FRACTION = 0.02

        // CLAHE alternative
        private const val CLAHE_CLIP = 2.5
        private const val CLAHE_TILES = 12

        // suggestion measurement
        private const val SUGGEST_LONG_EDGE = 512
        private const val SUGGEST_FAINT_LEVELS = 8.0
        private const val SUGGEST_MIN_PAPER = 60.0
        private const val SUGGEST_MIN_STROKE_FRACTION = 0.0005
        private const val SUGGEST_MAX_STROKE_FRACTION = 0.30
        private const val SUGGEST_MIN_DEPTH = 9.0
        private const val SUGGEST_MAX_DEPTH = 70.0
    }
}
