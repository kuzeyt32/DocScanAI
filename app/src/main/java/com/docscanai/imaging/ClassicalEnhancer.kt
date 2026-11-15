package com.docscanai.imaging

import android.graphics.Bitmap
import android.util.Log
import com.docscanai.core.util.Diag
import com.docscanai.data.EnhancementMode
import com.docscanai.data.PageEnhanceSettings
import com.docscanai.ml.OnnxModel
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Classical, text-visibility-focused [Enhancer] (MODELS.md §4.1, TECHNICAL_SPEC.md §4.2).
 *
 * The locked default enhancement pipeline. It is deterministic, dependency-free and runs entirely
 * on the pipeline's background thread. Stages (all native OpenCV, allocation-conscious):
 *
 *  1. **Shadow / illumination removal** — estimate the low-frequency background with a large-kernel
 *     Gaussian blur of a downscaled copy, then `out = src · 255 / (bg + ε)`. This flattens lighting
 *     gradients and removes hand/phone shadows to a ~white paper. Optionally replaced by the // M3
 *     neural shadow model when present (guarded; falls back to classical).
 *  2. **White balance** — gray-world per-channel gain, clamped to `[0.85, 1.18]` to avoid casts.
 *  3. **Auto-levels** — per-channel 0.5 %/99.5 % percentile histogram stretch.
 *  4. **CLAHE** on the LAB **L** channel (clip 2.0, 8×8) — local text contrast without halos.
 *  5. **Contrast / gamma** from [PageEnhanceSettings].
 *  6. **Unsharp mask** — mild sharpening so letter edges stay crisp.
 *
 * ### Modes
 *  - [EnhancementMode.ORIGINAL] — returns an unmodified **copy** of the input.
 *  - [EnhancementMode.ENHANCED] — steps 1–6 (clean "magic colour", default).
 *  - [EnhancementMode.BW] — steps 1–3 then **Sauvola** local binarization (denoise → adaptive local
 *    threshold), yielding pure-white background with crisp retained strokes.
 *
 * Preserves image size/aspect. Never throws: on failure it returns a copy of the input.
 *
 * @param shadowModelProvider lazily supplies the optional // M3 shadow-removal [`OnnxModel`].
 */
class ClassicalEnhancer(
    private val shadowModelProvider: (() -> OnnxModel?)? = null
) : Enhancer {

    @Volatile private var shadowClosed = false
    @Volatile private var shadowModel: OnnxModel? = null
    private val shadowLock = Any()

    override suspend fun enhance(source: Bitmap, settings: PageEnhanceSettings): Bitmap {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return source

        val start = System.currentTimeMillis()
        Diag.stageEnter("Enhance", "mode=${settings.mode.id} ${Diag.describe(source)}")

        // ORIGINAL: an unmodified COPY (the caller recycles both safely).
        if (settings.mode == EnhancementMode.ORIGINAL) {
            val out = source.copy(Bitmap.Config.ARGB_8888, false) ?: source
            Diag.stageExit("Enhance", System.currentTimeMillis() - start, "mode=original")
            return out
        }

        val rgba = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            val initial = Mat()
            Imgproc.cvtColor(rgba, initial, Imgproc.COLOR_RGBA2BGR)
            var img = initial
            try {
                // 1) illumination / shadow removal
                if (settings.shadowRemove) {
                    val removed = neuralIllumination(img) ?: classicalIllumination(img)
                    if (removed != null && removed !== img) {
                        img.release()
                        img = removed
                    }
                }
                // 2 + 3) white balance + auto-levels
                grayWorld(img)
                autoLevels(img)

                if (settings.mode == EnhancementMode.BW) {
                    // B&W: Sauvola local binarization on the normalised grayscale.
                    val bw = binarize(img)
                    if (bw != null && bw !== img) {
                        img.release()
                        img = bw
                    }
                } else {
                    // 4) CLAHE, 5) contrast/gamma, 6) sharpen
                    applyClahe(img)
                    if (settings.contrast != 1f) {
                        img.convertTo(
                            img, -1, settings.contrast.toDouble(),
                            128.0 * (1.0 - settings.contrast.toDouble())
                        )
                    }
                    if (settings.gamma != 1f) applyGamma(img, settings.gamma)
                    unsharpMask(img)
                }

                val outRgba = Mat()
                val out = Bitmap.createBitmap(img.cols(), img.rows(), Bitmap.Config.ARGB_8888)
                Imgproc.cvtColor(img, outRgba, Imgproc.COLOR_BGR2RGBA)
                Utils.matToBitmap(outRgba, out)
                outRgba.release()
                Diag.stageExit(
                    "Enhance", System.currentTimeMillis() - start,
                    "mode=${settings.mode.id} ${Diag.describe(out)}"
                )
                return out
            } finally {
                img.release()
            }
        } catch (t: Throwable) {
            // Catch Throwable (incl. OutOfMemoryError); degrade to an unmodified copy.
            Diag.logThrowable("Enhance", t)
            val fallback = source.copy(Bitmap.Config.ARGB_8888, false) ?: source
            Diag.stageExit("Enhance", System.currentTimeMillis() - start, "fallback=copy")
            return fallback
        } finally {
            rgba.release()
        }
    }

    override fun release() {
        synchronized(shadowLock) {
            runCatching { shadowModel?.close() }
            shadowModel = null
            shadowClosed = true
        }
    }

    // --- illumination -----------------------------------------------------------------------

    /**
     * Classical illumination flattening: divide by a heavily blurred background estimate.
     * Returns a NEW `CV_8UC3` BGR [Mat], or `null` on failure.
     */
    private fun classicalIllumination(bgr: Mat): Mat? {
        val gray = Mat()
        val small = Mat()
        val bgSmall = Mat()
        val bg = Mat()
        val bg3 = Mat()
        val srcF = Mat()
        val bgF = Mat()
        val outF = Mat()
        val out = Mat()
        try {
            val w = bgr.cols()
            val h = bgr.rows()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)

            val scale = 512.0 / max(w, h).coerceAtLeast(1)
            val sw = max(16, (w * scale).toInt())
            val sh = max(16, (h * scale).toInt())
            Imgproc.resize(gray, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

            val k = oddKernel(max(15, min(sw, sh) / 6))
            Imgproc.GaussianBlur(small, bgSmall, Size(k.toDouble(), k.toDouble()), 0.0)
            Imgproc.resize(bgSmall, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

            val channels = ArrayList<Mat>(3).apply { add(bg); add(bg); add(bg) }
            Core.merge(channels, bg3)

            bgr.convertTo(srcF, CvType.CV_32FC3)
            bg3.convertTo(bgF, CvType.CV_32FC3)
            // Floor the divisor to avoid blow-ups in near-black regions.
            Core.add(bgF, Scalar(8.0, 8.0, 8.0), bgF)
            Core.divide(srcF, bgF, outF, 255.0)
            outF.convertTo(out, CvType.CV_8UC3)
            return out
        } catch (t: Throwable) {
            Log.w(TAG, "illumination removal failed", t)
            return null
        } finally {
            gray.release(); small.release(); bgSmall.release(); bg.release()
            bg3.release(); srcF.release(); bgF.release(); outF.release()
            if (out.empty()) out.release()
        }
    }

    /**
     * // M3 — optional neural shadow removal (DocShadow/ShadowFormer). Only runs when a model is
     * present; returns a NEW BGR [Mat] or `null` (caller uses the classical path). Guarded.
     */
    private fun neuralIllumination(bgr: Mat): Mat? {
        val model = resolveShadowModel() ?: return null
        val small = Mat()
        val rgb = Mat()
        val full = Mat()
        val outBgr = Mat()
        var bitmap: Bitmap? = null
        return try {
            val (inW, inH) = shadowInputSize(model)
            Imgproc.resize(bgr, small, Size(inW.toDouble(), inH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            if (!small.isContinuous) return null

            val plane = inW * inH
            val bytes = ByteArray(plane * 3)
            small.get(0, 0, bytes)
            val buffer = FloatBuffer.allocate(3 * plane)
            for (i in 0 until plane) {
                val b = i * 3
                buffer.put(i, (bytes[b + 2].toInt() and 0xFF) / 255f)          // R
                buffer.put(plane + i, (bytes[b + 1].toInt() and 0xFF) / 255f)  // G
                buffer.put(2 * plane + i, (bytes[b].toInt() and 0xFF) / 255f)  // B
            }
            buffer.rewind()

            val out = model.runFloat(
                buffer, longArrayOf(1L, 3L, inH.toLong(), inW.toLong()),
                outputName = model.outputNames.firstOrNull()
            ) ?: return null
            if (out.size < 3 * plane) return null

            var maxV = 0f
            for (v in out) if (v > maxV) maxV = v
            val gain = if (maxV <= 1.5f) 255f else 1f

            val pixels = IntArray(plane)
            for (i in 0 until plane) {
                val r = (out[i] * gain).roundToInt().coerceIn(0, 255)
                val g = (out[plane + i] * gain).roundToInt().coerceIn(0, 255)
                val b = (out[2 * plane + i] * gain).roundToInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, inW, 0, 0, inW, inH)
            Utils.bitmapToMat(bitmap, rgb)
            Imgproc.resize(
                rgb, full, Size(bgr.cols().toDouble(), bgr.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC
            )
            Imgproc.cvtColor(full, outBgr, Imgproc.COLOR_RGBA2BGR)
            outBgr
        } catch (t: Throwable) {
            Log.w(TAG, "M3 shadow removal failed; using classical", t)
            null
        } finally {
            bitmap?.recycle()
            small.release(); rgb.release(); full.release()
            if (outBgr.empty()) outBgr.release()
        }
    }

    private fun shadowInputSize(model: OnnxModel): Pair<Int, Int> {
        val shape = model.inputShape
        if (shape != null && shape.size == 4 && shape[2] > 0 && shape[3] > 0) {
            return shape[3].toInt() to shape[2].toInt()
        }
        return 256 to 256
    }

    // --- colour / tone ----------------------------------------------------------------------

    /** Gray-world white balance, in place. */
    private fun grayWorld(bgr: Mat) {
        val m = Core.mean(bgr)
        val b = m.`val`[0]
        val g = m.`val`[1]
        val r = m.`val`[2]
        if (b <= 1e-3 || g <= 1e-3 || r <= 1e-3) return
        val target = (b + g + r) / 3.0
        val gains = doubleArrayOf(
            (target / b).coerceIn(0.85, 1.18),
            (target / g).coerceIn(0.85, 1.18),
            (target / r).coerceIn(0.85, 1.18)
        )
        val mv = ArrayList<Mat>()
        Core.split(bgr, mv)
        try {
            for (i in mv.indices) mv[i].convertTo(mv[i], -1, gains[i], 0.0)
            Core.merge(mv, bgr)
        } finally {
            mv.forEach { runCatching { it.release() } }
        }
    }

    /** Per-channel percentile auto-levels, in place. */
    private fun autoLevels(bgr: Mat) {
        val total = bgr.rows() * bgr.cols()
        if (total <= 0) return
        val mv = ArrayList<Mat>()
        Core.split(bgr, mv)
        try {
            for (ch in mv) {
                val hist = Mat()
                try {
                    Imgproc.calcHist(
                        listOf(ch), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f)
                    )
                    val (lo, hi) = percentileBounds(hist, total)
                    if (hi > lo) {
                        val alpha = 255.0 / (hi - lo).toDouble()
                        val beta = -lo * alpha
                        ch.convertTo(ch, -1, alpha, beta)
                    }
                } finally {
                    hist.release()
                }
            }
            Core.merge(mv, bgr)
        } finally {
            mv.forEach { runCatching { it.release() } }
        }
    }

    /** CLAHE on the L channel of LAB, in place. */
    private fun applyClahe(bgr: Mat) {
        val lab = Mat()
        val mv = ArrayList<Mat>()
        try {
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, mv)
            if (mv.isNotEmpty()) {
                val clahe = Imgproc.createCLAHE(CLAHE_CLIP, Size(8.0, 8.0))
                clahe.apply(mv[0], mv[0])
            }
            Core.merge(mv, lab)
            Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR)
        } finally {
            mv.forEach { runCatching { it.release() } }
            lab.release()
        }
    }

    /** Gamma correction via a 256-entry LUT, in place. */
    private fun applyGamma(bgr: Mat, gamma: Float) {
        val inv = 1.0 / gamma.toDouble().coerceAtLeast(1e-3)
        val data = ByteArray(256)
        for (i in 0..255) {
            data[i] = (255.0 * (i / 255.0).pow(inv)).roundToInt().coerceIn(0, 255).toByte()
        }
        val lut = Mat(1, 256, CvType.CV_8UC1)
        try {
            lut.put(0, 0, data)
            Core.LUT(bgr, lut, bgr)
        } finally {
            lut.release()
        }
    }

    /** Mild unsharp mask, in place. */
    private fun unsharpMask(bgr: Mat) {
        val blur = Mat()
        try {
            Imgproc.GaussianBlur(bgr, blur, Size(0.0, 0.0), 1.2)
            Core.addWeighted(bgr, 1.0 + SHARPEN_AMOUNT, blur, -SHARPEN_AMOUNT, 0.0, bgr)
        } finally {
            blur.release()
        }
    }

    // --- binarization -----------------------------------------------------------------------

    /**
     * **Sauvola** local binarization of [bgr]: light denoise → local mean/std via box filters →
     * `T = mean·(1 + k·(std/R − 1))` → white background, black strokes. Returns a NEW `CV_8UC3`
     * BGR [Mat], or `null` on failure.
     */
    private fun binarize(bgr: Mat): Mat? {
        val gray = Mat()
        val soft = Mat()
        val f = Mat()
        val mean = Mat()
        val sq = Mat()
        val sqMean = Mat()
        val mean2 = Mat()
        val variance = Mat()
        val std = Mat()
        val thresh = Mat()
        val bin = Mat()
        val out = Mat()
        return try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            // Light denoise so high-res sensor noise does not become speckle.
            Imgproc.medianBlur(gray, gray, 3)

            val w = gray.cols()
            val h = gray.rows()
            val k = oddKernel((min(w, h) / 40).coerceIn(15, 101))

            gray.convertTo(f, CvType.CV_32F)
            Imgproc.boxFilter(f, mean, CvType.CV_32F, Size(k.toDouble(), k.toDouble()))
            Core.multiply(f, f, sq)
            Imgproc.boxFilter(sq, sqMean, CvType.CV_32F, Size(k.toDouble(), k.toDouble()))
            Core.multiply(mean, mean, mean2)
            Core.subtract(sqMean, mean2, variance)
            Imgproc.threshold(variance, variance, 0.0, 0.0, Imgproc.THRESH_TOZERO) // clamp var >= 0
            Core.sqrt(variance, std)

            // t = 1 + k·(std/R − 1)
            Core.multiply(std, Scalar(1.0 / SAUVOLA_R), std)
            Core.multiply(std, Scalar(SAUVOLA_K), std)
            Core.add(std, Scalar(1.0 - SAUVOLA_K), std)
            Core.multiply(mean, std, thresh)

            // Background (gray > T) → 255, strokes → 0.
            Core.compare(f, thresh, bin, Core.CMP_GT)
            Imgproc.cvtColor(bin, out, Imgproc.COLOR_GRAY2BGR)
            // SUBTASK H27 — owner: "B&W is too sharp, you can decrease it a lil bit".
            // The hard CMP_GT decision above is the only edge-hardening the B&W path has: the
            // ENHANCED path's unsharpMask (SHARPEN_AMOUNT) is deliberately NOT applied to B&W, so
            // every edge was a 0↔255 cliff and the whole page read as clipped (measured 100 % of
            // pixels at 0 or 255 on docunet_01/18/35, stroke Laplacian 156-277 vs 23-47 for
            // ORIGINAL). One modest step: anti-alias those edges with a [BW_SOFTEN_SIGMA] Gaussian,
            // which is a convex mix of the two existing values, so it cannot over/undershoot,
            // cannot tint the paper (it stays 255) and cannot lift a stroke interior off black
            // except for sub-pixel-wide strokes. Numbers before/after with the contact sheet:
            // tooling/out/h27/BW_BEFORE|BW_AFTER/.
            Imgproc.GaussianBlur(out, soft, Size(0.0, 0.0), BW_SOFTEN_SIGMA)
            soft.copyTo(out)
            out
        } catch (t: Throwable) {
            Log.w(TAG, "binarization failed", t)
            null
        } finally {
            gray.release(); soft.release()
            f.release(); mean.release(); sq.release(); sqMean.release()
            mean2.release(); variance.release(); std.release(); thresh.release(); bin.release()
            if (out.empty()) out.release()
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * Resolves the optional // M3 shadow model. A `null` result is **not** latched so the runtime
     * `neuralProcessingEnabled` switch can be toggled without a restart (the provider returns
     * `null` while disabled and the lazily-loaded model again afterwards). Never throws.
     */
    private fun resolveShadowModel(): OnnxModel? {
        shadowModel?.let { return it }
        if (shadowClosed) return null
        synchronized(shadowLock) {
            shadowModel?.let { return it }
            if (shadowClosed) return null
            val loaded = try {
                shadowModelProvider?.invoke()
            } catch (t: Throwable) {
                Diag.logThrowable("Enhance", t)
                null
            }
            if (loaded != null) shadowModel = loaded
            return loaded
        }
    }

    private fun oddKernel(value: Int): Int {
        val v = value.coerceAtLeast(3)
        return if (v % 2 == 0) v + 1 else v
    }

    private fun percentileBounds(hist: Mat, total: Int): Pair<Int, Int> {
        val lowTarget = total * LOW_PERCENTILE
        val highTarget = total * HIGH_PERCENTILE
        var cumulative = 0.0
        var lo = 0
        var hi = 255
        var loSet = false
        for (i in 0..255) {
            cumulative += hist.get(i, 0)[0]
            if (!loSet && cumulative >= lowTarget) {
                lo = i
                loSet = true
            }
            if (cumulative >= highTarget) {
                hi = i
                break
            }
        }
        if (hi <= lo) {
            lo = 0
            hi = 255
        }
        return lo to hi
    }

    companion object {
        private const val TAG = "ClassicalEnhancer"
        private const val CLAHE_CLIP = 2.0
        private const val SHARPEN_AMOUNT = 0.5
        private const val SAUVOLA_K = 0.2
        private const val SAUVOLA_R = 128.0
        private const val LOW_PERCENTILE = 0.005
        private const val HIGH_PERCENTILE = 0.995

        /**
         * SUBTASK H27, ITEM B — "B&W is too sharp" (owner, verbatim: "you can decrease it a lil
         * bit"). Gaussian sigma used to anti-alias the 1-bit Sauvola result. This is the only
         * sharpness knob the B&W path has: the ENHANCED path's [SHARPEN_AMOUNT] unsharp mask is
         * not applied to B&W, so the hardness came entirely from the 0↔255 threshold cliff.
         * 0.4 px = one modest step, measured on real pages (tooling/out/h27/BW_BEFORE|BW_AFTER,
         * docs/RESULTS.md §H27). Calibration anchors actually measured: sigma 0 left the stroke
         * Laplacian at 156/277/219 (cliff-hard, clipping 100 %), sigma 0.6 took it to 80/133/111 —
         * i.e. softer than ENHANCED, which is more than "a lil bit" — so 0.4 is the shipped value.
         */
        private const val BW_SOFTEN_SIGMA = 0.4
    }
}
