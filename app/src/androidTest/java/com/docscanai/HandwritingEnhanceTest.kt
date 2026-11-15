package com.docscanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.core.di.ServiceLocator
import com.docscanai.imaging.HandwritingEnhancer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * ============================================================================================
 * SUBTASK H23 (harness) / H25 (candidate) — measurement lane for the faint pencil enhancement.
 * ============================================================================================
 *
 * Inputs are **real pencil pages**, not generated ones:
 * `app/src/androidTest/assets/pencil/pencil_real_XX.jpg` are downscaled Wikimedia Commons scans of
 * Kate Chopin's original pencil manuscripts (public domain, DPLA). Provenance, licence URL and the
 * original SHA-256 travel inside the test APK in `pencil_manifest.json`.
 *
 * H25 measures **one** candidate — [HandwritingEnhancer.Variant.RIDGE], the Frangi/Hessian ridge
 * (shape) gate — on exactly the same 12 pages, with exactly the same fixed input masks and the same
 * metric definitions as the H23 run, so the two attempts are directly comparable. Nothing in the
 * harness changed; only the variant under test did.
 *
 * ### Metric definitions (fixed on the input, never recomputed on the output)
 * Archival scans carry a black mount, torn edges and paper stains. If those enter a statistic, the
 * "stroke contrast" is dominated by mount edges (measured: 114 levels on a page whose pencil is
 * ~20 levels deep) and no enhancement can show a gain. The masks therefore are:
 *
 *  - `paper` region: `background >= 0.70 · paperP92`, eroded by 9×9 twice (≈8 px) so mount
 *    boundaries and torn edges are outside every statistic;
 *  - `stroke` mask: inside `paper`, `6 <= background − grey <= 70` — a *mark band*, deliberately
 *    excluding black ink (deeper than 70) and paper grain (shallower than 6);
 *  - `blank` (non-stroke) mask: inside `paper`, the stroke mask dilated by 15×15 and removed. No
 *    amplitude threshold is applied here, so grain is measured over a fixed, unbiased population.
 *
 * Contrast is reported as **stroke depth in levels** measured against the *input* background:
 * `depth = mean(background_input − grey | stroke)`. Using the input background as the fixed
 * reference means the number answers exactly one question — did the marks get deeper? — and cannot
 * be inflated by the enhancement moving its own zero point.
 *
 * Gates: contrast ≥ +30 % relative, blank noise ≤ +10 %, non-stroke PSNR ≥ 30 dB, stroke
 * sharpness not lower, connected components not higher, latency median and max ≤ 120 ms at 2000 px.
 */
@RunWith(AndroidJUnit4::class)
class HandwritingEnhanceTest {

    companion object {
        private const val TAG = "DocScan"
        private const val PENCIL_DIR = "pencil"
        private const val CORPUS_DIR = "corpus/real"
        private const val OUT_DIR = "h25"

        private const val BG_SIGMA = 12.0
        private const val PAPER_PERCENTILE = 0.92
        private const val PAPER_REGION_FRAC = 0.70

        /** Shallowest mark accepted as a stroke candidate (paper grain sits below this). */
        private const val STROKE_MIN_LEVELS = 6.0

        /** Deepest mark accepted: black ink / mount interiors are not the faint-handwriting band. */
        private const val STROKE_MAX_LEVELS = 70.0
        private const val MIN_COMPONENT_AREA = 12.0
        private const val LATENCY_LONG_EDGE = 2000
        private const val LATENCY_RUNS = 3

        @JvmStatic
        @BeforeClass
        fun setUp() {
            ServiceLocator.ensureOpenCv()
        }
    }

    private fun outputDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), OUT_DIR)
        dir.mkdirs()
        File(dir, "pairs").mkdirs()
        return dir
    }

    private fun assetNames(context: Context, dir: String): List<String> =
        context.assets.list(dir)?.toList().orEmpty()
            .filter { it.endsWith(".jpg", ignoreCase = true) }
            .sorted()

    private fun decode(context: Context, dir: String, name: String): Bitmap? = runCatching {
        context.assets.open("$dir/$name").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun gray(bmp: Bitmap): Mat {
        val rgba = Mat()
        val g = Mat()
        Utils.bitmapToMat(bmp, rgba)
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return g
    }

    private fun percentileOf(m: Mat, fraction: Double): Double {
        val bytes = ByteArray(m.cols() * m.rows())
        m.get(0, 0, bytes)
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

    /** Fixed masks: computed once on the input and reused for every output. */
    private class Masks(val stroke: Mat, val blank: Mat, val region: Mat, val bg: Mat)

    private fun masks(g: Mat): Masks {
        val bg = Mat()
        Imgproc.GaussianBlur(g, bg, Size(0.0, 0.0), BG_SIGMA)
        val diff = Mat()
        Core.subtract(bg, g, diff) // 8U, saturates at 0

        val paper = percentileOf(g, PAPER_PERCENTILE)
        val region = Mat()
        Imgproc.threshold(bg, region, paper * PAPER_REGION_FRAC, 255.0, Imgproc.THRESH_BINARY)
        val erodeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.erode(region, region, erodeKernel, Point(-1.0, -1.0), 2)
        erodeKernel.release()

        val stroke = Mat()
        Imgproc.threshold(diff, stroke, STROKE_MIN_LEVELS, 255.0, Imgproc.THRESH_BINARY)
        val notTooDeep = Mat()
        Imgproc.threshold(diff, notTooDeep, STROKE_MAX_LEVELS, 255.0, Imgproc.THRESH_BINARY_INV)
        Core.bitwise_and(stroke, notTooDeep, stroke)
        notTooDeep.release()
        Core.bitwise_and(stroke, region, stroke)

        // Stroke-free paper: fixed population for the noise and PSNR statistics.
        val dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
        val dilated = Mat()
        Imgproc.dilate(stroke, dilated, dilateKernel)
        dilateKernel.release()
        val blank = Mat()
        Core.bitwise_not(dilated, blank)
        dilated.release()
        Core.bitwise_and(blank, region, blank)

        diff.release()
        return Masks(stroke, blank, region, bg)
    }

    /** `background − grey` with saturation at 0: how much darker than the local paper a pixel is. */
    private fun depthMap(g: Mat, bg: Mat): Mat {
        val d = Mat()
        Core.subtract(bg, g, d)
        return d
    }

    /** Mean mark depth in levels over [mask], using the input background as fixed reference. */
    private fun depth(g: Mat, bg: Mat, mask: Mat): Double {
        val d = depthMap(g, bg)
        return try {
            Core.mean(d, mask).`val`[0]
        } finally {
            d.release()
        }
    }

    private fun meanOf(m: Mat, mask: Mat): Double = Core.mean(m, mask).`val`[0]

    private fun stdOf(m: Mat, mask: Mat): Double {
        val mean = MatOfDouble()
        val std = MatOfDouble()
        try {
            Core.meanStdDev(m, mean, std, mask)
            return std.toArray().firstOrNull() ?: 0.0
        } finally {
            mean.release(); std.release()
        }
    }

    private fun components(mask: Mat): Int {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
            var kept = 0
            for (i in 1 until n) {
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                if (area >= MIN_COMPONENT_AREA) kept++
            }
            return kept
        } finally {
            labels.release(); stats.release(); centroids.release()
        }
    }

    private fun psnrMasked(a: Mat, b: Mat, mask: Mat): Double {
        val d = Mat()
        val df = Mat()
        try {
            Core.absdiff(a, b, d)
            d.convertTo(df, CvType.CV_32F)
            Core.multiply(df, df, df)
            val mse = Core.mean(df, mask).`val`[0]
            if (mse <= 1e-6) return 99.0
            return 10.0 * Math.log10((255.0 * 255.0) / mse)
        } finally {
            d.release(); df.release()
        }
    }

    private fun sharpness(g: Mat, mask: Mat): Double {
        val lap = Mat()
        try {
            Imgproc.Laplacian(g, lap, CvType.CV_32F)
            val s = stdOf(lap, mask)
            return s * s
        } finally {
            lap.release()
        }
    }

    /** Stroke map of an image under the *fixed* input masks (no mask drift between in and out). */
    private fun strokeMap(g: Mat, bg: Mat, region: Mat): Mat {
        val d = depthMap(g, bg)
        val s = Mat()
        Imgproc.threshold(d, s, STROKE_MIN_LEVELS, 255.0, Imgproc.THRESH_BINARY)
        val notTooDeep = Mat()
        Imgproc.threshold(d, notTooDeep, STROKE_MAX_LEVELS, 255.0, Imgproc.THRESH_BINARY_INV)
        Core.bitwise_and(s, notTooDeep, s)
        notTooDeep.release()
        Core.bitwise_and(s, region, s)
        d.release()
        return s
    }

    private fun upscaleForLatency(bmp: Bitmap): Bitmap {
        val longEdge = max(bmp.width, bmp.height)
        if (longEdge >= LATENCY_LONG_EDGE) return bmp
        val scale = LATENCY_LONG_EDGE.toFloat() / longEdge.toFloat()
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    private fun median(values: List<Long>): Double {
        if (values.isEmpty()) return -1.0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2].toDouble()
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun percentileOf(values: List<Long>, p: Double): Double {
        if (values.isEmpty()) return -1.0
        val s = values.sorted()
        val idx = ((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)
        return s[idx].toDouble()
    }

    private fun saveJpeg(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
    }

    /** All per-image numbers the keep/revert rule needs. */
    private fun metricsFor(
        out: Mat,
        input: Mat,
        m: Masks,
        depthIn: Double,
        noiseIn: Double,
        sharpIn: Double,
        compsIn: Int
    ): JSONObject {
        val outDepth = depth(out, m.bg, m.stroke)
        val noise = stdOf(out, m.blank)
        val psnr = psnrMasked(input, out, m.blank)
        val sharpOut = sharpness(out, m.stroke)
        val sm = strokeMap(out, m.bg, m.region)
        val comps = try {
            components(sm)
        } finally {
            sm.release()
        }
        return JSONObject().apply {
            put("contrast_gain_pct", round2(if (depthIn > 1e-3) (outDepth / depthIn - 1.0) * 100.0 else -999.0))
            put("noise_gain_pct", round2(if (noiseIn > 1e-3) (noise / noiseIn - 1.0) * 100.0 else -999.0))
            put("psnr_nonstroke_db", round2(psnr))
            put("sharpness_ratio", round2(if (sharpIn > 1e-6) sharpOut / sharpIn else -1.0))
            put("components_in", compsIn)
            put("components_out", comps)
            put("components_delta_pct", round2(if (compsIn > 0) (comps.toDouble() / compsIn - 1.0) * 100.0 else -999.0))
            put("paper_level", round2(meanOf(out, m.blank)))
            put("stroke_depth_levels", round2(outDepth))
        }
    }

    @Test
    fun realPencilPages_measured_forKeepRevert() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testCtx = instrumentation.context
        val targetCtx = instrumentation.targetContext
        val dir = outputDir(targetCtx)
        val enhancer = HandwritingEnhancer()
        val variant = HandwritingEnhancer.Variant.RIDGE

        val names = assetNames(testCtx, PENCIL_DIR).filter { it.startsWith("pencil_real_") }
        assertTrue("no real pencil assets under assets/$PENCIL_DIR", names.size >= 10)
        Log.i(TAG, "H25_DATASET real_pages=${names.size} variant=${variant.id}")

        val results = JSONObject()
        val pagesJson = JSONArray()
        val latency = ArrayList<Long>()

        names.forEach { name ->
            val bmp = decode(testCtx, PENCIL_DIR, name) ?: return@forEach
            val input = gray(bmp)
            val m = masks(input)
            val inDepth = depth(input, m.bg, m.stroke)
            val inNoise = stdOf(input, m.blank)
            val inSharp = sharpness(input, m.stroke)
            val inComponents = components(m.stroke)
            val paper = meanOf(m.bg, m.blank)
            val suggestion = enhancer.measure(bmp)

            val outBmp = enhancer.enhance(bmp, variant)
            val outGray = gray(outBmp)
            val outJson = metricsFor(outGray, input, m, inDepth, inNoise, inSharp, inComponents)

            val pageJson = JSONObject().apply {
                put("asset", name)
                put("input_paper", round2(paper))
                put("input_depth_levels", round2(inDepth))
                put("input_noise", round2(inNoise))
                put("input_stroke_fraction", round4(
                    Core.countNonZero(m.stroke).toDouble() /
                        max(1.0, Core.countNonZero(m.region).toDouble())
                ))
                put("suggested", suggestion.suggested)
                put("suggested_depth", round2(suggestion.contrastLevels))
                put("ridge", outJson)
            }
            pagesJson.put(pageJson)
            Log.i(
                TAG,
                "H25_PAGE asset=$name paper=${fmt(paper)} depth=${fmt(inDepth)} " +
                    "ridge=${outJson} suggested=${suggestion.suggested}"
            )

            saveJpeg(bmp, File(dir, "pairs/${name}_in.jpg"))
            saveJpeg(outBmp, File(dir, "pairs/${name}_ridge.jpg"))

            // --- latency at the pipeline's own 2000 px working cap ---
            val big = upscaleForLatency(bmp)
            val bigOwned = big !== bmp
            enhancer.enhance(big, variant).recycle() // warm-up
            repeat(LATENCY_RUNS) {
                val t0 = System.nanoTime()
                val out = enhancer.enhance(big, variant)
                latency.add((System.nanoTime() - t0) / 1_000_000)
                out.recycle()
            }
            if (bigOwned) big.recycle()

            outGray.release()
            m.stroke.release(); m.blank.release(); m.region.release(); m.bg.release()
            input.release(); outBmp.recycle()
        }

        // --- normal corpus pages: prove the stage does not damage what already works ---
        val normalJson = JSONArray()
        val normalNames = assetNames(testCtx, CORPUS_DIR).take(20)
        normalNames.forEach { name ->
            val bmp = decode(testCtx, CORPUS_DIR, name) ?: return@forEach
            val input = gray(bmp)
            val m = masks(input)
            val inDepth = depth(input, m.bg, m.stroke)
            val inNoise = stdOf(input, m.blank)
            val inSharp = sharpness(input, m.stroke)
            val inComponents = components(m.stroke)
            val out = enhancer.enhance(bmp, variant)
            val outGray = gray(out)
            val json = metricsFor(outGray, input, m, inDepth, inNoise, inSharp, inComponents)
            json.put("asset", name)
            normalJson.put(json)
            Log.i(TAG, "H25_NORMAL $json")
            outGray.release(); out.recycle()
            m.stroke.release(); m.blank.release(); m.region.release(); m.bg.release(); input.release()
        }

        results.put("dataset", "real-pencil-handwriting-v1 (Wikimedia Commons, public domain)")
        results.put("variant", variant.id)
        results.put("pages", pagesJson)
        results.put("normal_pages", normalJson)
        results.put("latency_ms", JSONObject().apply {
            put("median", round2(median(latency)))
            put("p95", round2(percentileOf(latency, 0.95)))
            put("max", latency.maxOrNull() ?: -1L)
            put("samples", JSONArray(latency))
        })
        results.put(
            "gates",
            JSONObject().apply {
                put("contrast_target_pct", 30.0)
                put("noise_limit_pct", 10.0)
                put("psnr_min_db", 30.0)
                put("sharpness_not_down", true)
                put("continuity_must_not_worsen", true)
                put("latency_limit_ms", 120.0)
                put("stroke_band_levels", JSONArray().put(STROKE_MIN_LEVELS).put(STROKE_MAX_LEVELS))
                put("paper_region_frac", PAPER_REGION_FRAC)
            }
        )

        val file = File(dir, "h25_results.json")
        file.writeText(results.toString(2))
        Log.i(
            TAG,
            "H25_RESULTS file=${file.absolutePath} pages=${pagesJson.length()} " +
                "normals=${normalJson.length()} " +
                "latencyMedian=${results.getJSONObject("latency_ms").getDouble("median")}ms " +
                "latencyMax=${results.getJSONObject("latency_ms").getLong("max")}ms"
        )
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun round4(v: Double): Double = Math.round(v * 10000.0) / 10000.0
    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
