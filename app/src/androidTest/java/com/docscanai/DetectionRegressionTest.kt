package com.docscanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.capture.OpenCvQuadPageDetector
import com.docscanai.capture.PageDetector
import com.docscanai.capture.ScanPipeline
import com.docscanai.core.di.ServiceLocator
import com.docscanai.core.util.BitmapUtils
import com.docscanai.data.EnhancementMode
import com.docscanai.data.PageEnhanceSettings
import com.docscanai.data.Quad
import com.docscanai.imaging.PerspectiveDewarpProcessor
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

/**
 * ============================================================================================
 * SUBTASK H3b — PERMANENT production regression gate for real-photo detection + AI sharpness.
 * ============================================================================================
 *
 * The H3a baseline shipped a detector that found a page in **0/20** DocUNet benchmark photos, so
 * the AI path was bit-identical to the classical path. This class pins the *fixed* behaviour so it
 * can never silently regress. It drives the app's REAL components (the production
 * [`com.docscanai.capture.OpenCvQuadPageDetector`] and
 * [`com.docscanai.capture.ScanPipeline`]) — no UI, no camera.
 *
 * Pinned invariants (with the H3b measured baselines the thresholds are derived from):
 *  1. [benchmarkRealPhotos_areDetected] — real benchmark photographs MUST be detected. H3b measured
 *     **15/20** (H3a: 0/20); the pin requires ≥ [BENCH_MIN_FRACTION].
 *  2. [noDetection_producesNoWarp] — with a detector that finds nothing, the pipeline must NOT warp
 *     and must carry the full-frame fallback quad through unchanged (the H3 F1 guard).
 *  3. [negativeScenes_areNotWarpedAsFullFramePages] — a not-a-document scene must never be warped as
 *     a (near-)full-frame page (this also covers the earlier `syn_neg_texture_1` false positive).
 *  4. [aiOnSharpness_notSofterThanAiOff] — the delivered AI-ON page must not be dramatically softer
 *     than the AI-OFF page. H3b measured AI-ON ≈ 0.71× AI-OFF after the resolution + unsharp fix
 *     (the pre-fix capped-resolution path was ≈ 0.55×); the pin requires ≥ [SHARPNESS_TOLERANCE].
 *
 * Models are NOT bundled in `assets/` — the harness provisions them first. If they are absent the
 * sharpness test fails loudly (INVALID) via [`NeuralRepro.requireModels`], never a false pass.
 */
@RunWith(AndroidJUnit4::class)
class DetectionRegressionTest {

    companion object {
        private const val TAG = "DocScan"

        private const val BENCH_DIR = "corpus/benchmark/inputs"

        /**
         * Minimum fraction of the real benchmark photos that must be detected. H3a was 0.0; H3b
         * measured 0.75 (15/20). Kept at 0.50: comfortably above a collapse while leaving headroom
         * for the few genuinely hostile DocUNet frames (white-on-white / heavy occlusion).
         */
        private const val BENCH_MIN_FRACTION = 0.50

        /**
         * AI-ON out-sharpness must be at least this fraction of AI-OFF's.
         *
         * History: H3b measured ≈ 0.71 after the resolution + unsharp fix; the pre-fix
         * capped-resolution path was ≈ 0.55; the H6 baseline was 0.754 (pin 0.8267).
         *
         * SUBTASK H7: with the 45×31 grid path composed with the homography and applied as ONE
         * remap from the original still, the measured ratio is **1.0067** (8 benchmark images,
         * aiOnSum=42863.34 / aiOffSum=42580.03). The pin is raised to the H7 acceptance target
         * (≥ 1.00) so a return to the softer image→image-at-2048 path fails immediately.
         */
        private const val SHARPNESS_TOLERANCE = 1.00

        /** A quad covering at least this fraction of the frame is treated as "near-full-frame". */
        private const val NEAR_FULL_FRAME_FRAC = 0.90

        /** The two genuine not-a-document negatives in the corpus. */
        private val NEGATIVES = listOf(
            "corpus/real/a_woman_fills_out_a_bear_information_management.jpg",
            "corpus/real/media_viewer_launch_retrospective_whiteboard_apr.jpg"
        )

        /**
         * SUBTASK H4: the two user-reported partially-catastrophic cases. Path -> max allowed page
         * area fraction. Baseline (H3b) pinned corners 2/3 of `docunet_45` and `docunet_48` to the
         * frame's right border, inflating the quad over the whole right half; the new DocAligner
         * corner detector must bound the page tightly.
         */
        private val FAILURE_MODE_CASES = listOf(
            "corpus/benchmark/inputs/docunet_45.jpg" to 0.35,
            "corpus/benchmark/inputs/docunet_48.jpg" to 0.55
        )

        /** A quad spanning this fraction of an axis while small is treated as the runaway mode. */
        private const val RUNAWAY_SPAN_FRAC = 0.95f

        /** A page covering at least this fraction of the frame is "not small" (invariant escape). */
        private const val RUNAWAY_MIN_PAGE_AREA = 0.85

        @JvmStatic
        @BeforeClass
        fun setUp() {
            NeuralRepro.setUp(enableXnnpack = false, tag = "DetectionRegressionTest/CPU-EP")
        }
    }

    // ==========================================================================================
    // 1) Real benchmark photos must be detected
    // ==========================================================================================

    @Test
    fun benchmarkRealPhotos_areDetected() {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val detector = ServiceLocator.pageDetector
        val names = testCtx.assets.list(BENCH_DIR)?.toList().orEmpty().sorted()
        assertTrue("no benchmark assets under assets/$BENCH_DIR", names.isNotEmpty())

        var detected = 0
        names.forEach { f ->
            val bmp = decode(testCtx, "$BENCH_DIR/$f")
            if (bmp == null) {
                Log.w(TAG, "REGTEST_BENCH name=$f SKIP (decode failed)")
                return@forEach
            }
            try {
                val q = runBlocking { runCatching { detector.detect(bmp) }.getOrNull() }
                val ok = q != null
                if (ok) detected++
                val frac = if (q != null) areaFraction(q, bmp.width, bmp.height) else -1.0
                Log.i(TAG, "REGTEST_BENCH name=$f detected=$ok areaFrac=" + fmt(frac))
                // SUBTASK H4 invariant: a detected page no longer runs away onto the frame border
                // while being substantially smaller than the frame.
                if (q != null) {
                    assertTrue(
                        "runaway quad on $f: it spans the frame border while the page is small " +
                            "(areaFrac=${fmt(frac)}) — the SUBTASK H4 failure mode is back",
                        !looksLikeRunaway(q, bmp.width, bmp.height, frac)
                    )
                }
            } finally {
                bmp.recycle()
            }
        }

        val need = ceil(names.size * BENCH_MIN_FRACTION).toInt().coerceAtLeast(1)
        Log.i(TAG, "REGTEST_BENCH_RESULT detected=$detected/${names.size} need>=$need")
        assertTrue(
            "real-photo detection regressed: detected $detected/${names.size} (need >= $need). " +
                "The H3b detector must find real pages (the H3a baseline was 0/${names.size}).",
            detected >= need
        )
    }

    // ==========================================================================================
    // 2) No detection -> no warp (deterministic, injected null detector)
    // ==========================================================================================

    @Test
    fun noDetection_producesNoWarp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nullDetector = object : PageDetector {
            override suspend fun detect(bitmap: Bitmap): Quad? = null
            override fun release() {}
        }
        val pipeline = ScanPipeline(
            ctx, nullDetector, ServiceLocator.dewarpProcessor, ServiceLocator.enhancer
        )
        val src = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        src.eraseColor(0xFFB0B0B0.toInt())
        val sessionId = "regtest-nodetect-${System.currentTimeMillis()}"
        try {
            val page = runBlocking {
                pipeline.process(
                    sessionId = sessionId,
                    source = src,
                    quad = null, // detector returns null -> nothing to warp
                    enhancement = PageEnhanceSettings(mode = EnhancementMode.ENHANCED)
                )
            }
            // The pipeline must surface the full-frame fallback quad and must NOT warp (the delivered
            // page stays at the source resolution).
            val full = BitmapUtils.fullFrameQuad(src.width, src.height)
            assertTrue(
                "a detection miss must fall back to the full-frame quad, got ${page.corners}",
                sameQuad(page.corners, full, 1.5f)
            )
            val out = decodeFile(page.imageFile)
            assertTrue("no-detection run must still deliver a page", out != null)
            val o = out!!
            try {
                Log.i(
                    TAG,
                    "REGTEST_NODETECT corners=${page.corners} out=${o.width}x${o.height} " +
                        "src=${src.width}x${src.height}"
                )
                assertTrue(
                    "no warp must occur without a detection: delivered ${o.width}x${o.height} " +
                        "!= source ${src.width}x${src.height}",
                    o.width == src.width && o.height == src.height
                )
            } finally {
                o.recycle()
            }
        } finally {
            pipeline.deleteSession(sessionId)
            src.recycle()
        }
    }

    // ==========================================================================================
    // 3) Negatives must never be warped as (near-)full-frame pages
    // ==========================================================================================

    @Test
    fun negativeScenes_areNotWarpedAsFullFramePages() {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val detector = ServiceLocator.pageDetector
        NEGATIVES.forEach { path ->
            val bmp = decode(testCtx, path)
            assertTrue("negative asset missing: $path", bmp != null)
            try {
                val q = runBlocking { runCatching { detector.detect(bmp!!) }.getOrNull() }
                val frac = if (q != null) areaFraction(q, bmp!!.width, bmp!!.height) else -1.0
                Log.i(TAG, "REGTEST_NEG name=$path detected=${q != null} areaFrac=${fmt(frac)}")
                // A not-a-document scene must never be warped as a (near-)full-frame page — this is
                // exactly the failure the H3a `syn_neg_texture_1` false positive exhibited.
                assertTrue(
                    "negative '$path' produced a near-full-frame page quad " +
                        "(areaFrac=${fmt(frac)}) — a not-a-document scene must not be warped",
                    q == null || frac < NEAR_FULL_FRAME_FRAC
                )
            } finally {
                bmp?.recycle()
            }
        }
    }

    // ==========================================================================================
    // 4b) SUBTASK H4 — the specific "2 corners right, 2 run to the border" mode must be gone
    // ==========================================================================================

    @Test
    fun specificFailureMode_isFixed() {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val detector = ServiceLocator.pageDetector
        Log.i(TAG, "REGTEST_H4 presence=${ServiceLocator.modelPresenceSummary()}")
        FAILURE_MODE_CASES.forEach { (path, maxArea) ->
            val bmp = decode(testCtx, path)
            assertTrue("failure-mode asset missing: $path", bmp != null)
            try {
                val q = runBlocking { runCatching { detector.detect(bmp!!) }.getOrNull() }
                assertTrue("SUBTASK H4: '$path' must be detected (got null)", q != null)
                val w = bmp!!.width
                val h = bmp!!.height
                val frac = areaFraction(q!!, w, h)
                Log.i(
                    TAG,
                    "REGTEST_H4 case=$path " +
                        "TL=(${q.topLeft.x},${q.topLeft.y}) TR=(${q.topRight.x},${q.topRight.y}) " +
                        "BR=(${q.bottomRight.x},${q.bottomRight.y}) BL=(${q.bottomLeft.x},${q.bottomLeft.y}) " +
                        "areaFrac=${fmt(frac)}"
                )
                assertTrue(
                    "SUBTASK H4: '$path' quad is too large (areaFrac=${fmt(frac)} > $maxArea) — " +
                        "the page is no longer tightly bounded",
                    frac <= maxArea
                )
                assertTrue(
                    "SUBTASK H4: '$path' still shows the runaway mode (bbox hugs the frame with a " +
                        "small page, areaFrac=${fmt(frac)})",
                    !looksLikeRunaway(q, w, h, frac)
                )
            } finally {
                bmp?.recycle()
            }
        }
    }

    // ==========================================================================================
    // 4) AI-ON must not be dramatically softer than AI-OFF
    // ==========================================================================================

    @Test
    fun aiOnSharpness_notSofterThanAiOff() {
        NeuralRepro.requireModels()
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val names = testCtx.assets.list(BENCH_DIR)?.toList().orEmpty().sorted()
        assertTrue("no benchmark assets to measure sharpness on", names.isNotEmpty())

        val subset = names.take(8)

        // AI-OFF MUST be measured first: the ORT model caches are latching (a loaded model cannot
        // be "un-loaded"), so AI-OFF has to run while the neural dewarp is still untouched.
        val offSum = pipelineSharpness(subset, aiOn = false)
        val onSum = pipelineSharpness(subset, aiOn = true)

        val ratio = if (offSum > 1e-6) onSum / offSum else 1.0
        Log.i(
            TAG,
            "REGTEST_SHARP images=${subset.size} aiOnSum=$onSum aiOffSum=$offSum ratio=" + fmt(ratio)
        )
        assertTrue(
            "AI-ON delivered pages are dramatically softer than AI-OFF ($onSum < " +
                "${SHARPNESS_TOLERANCE} x $offSum, ratio=${fmt(ratio)}): the capped-resolution " +
                "regression is back.",
            onSum >= offSum * SHARPNESS_TOLERANCE
        )
    }

    /**
     * Sums delivered out-sharpness over [names] for one AI mode, using DEDICATED detector/dewarp
     * instances so the two modes are not contaminated by the production singletons' latching model
     * caches. Detection is pinned classical for both modes so only the dewarp stage varies.
     */
    private fun pipelineSharpness(names: List<String>, aiOn: Boolean): Double {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val detector = OpenCvQuadPageDetector(modelProvider = { null })
        val dewarp = PerspectiveDewarpProcessor(
            // SUBTASK H7: AI-ON must exercise the PRIMARY grid path (45x31 grid composed with
            // the homography, one remap from the original still); the image→image model is only
            // the fallback when the grid artifact is missing/invalid.
            dewarpModelProvider = { if (aiOn) ServiceLocator.dewarpModel else null },
            dewarpGridModelProvider = { if (aiOn) ServiceLocator.dewarpGridModel else null }
        )
        val pipeline = ScanPipeline(ctx, detector, dewarp, ServiceLocator.enhancer)
        var sum = 0.0
        try {
            names.forEach { f ->
                val src = decode(testCtx, "$BENCH_DIR/$f") ?: return@forEach
                val sessionId = "regtest-sharp-${if (aiOn) "on" else "off"}-${System.currentTimeMillis()}"
                try {
                    val page = runBlocking {
                        pipeline.process(
                            sessionId = sessionId,
                            source = src,
                            quad = null,
                            enhancement = PageEnhanceSettings(mode = EnhancementMode.ENHANCED)
                        )
                    }
                    val out = decodeFile(page.imageFile)
                    if (out != null) {
                        try {
                            sum += lapVariance(out)
                        } finally {
                            out.recycle()
                        }
                    }
                } finally {
                    pipeline.deleteSession(sessionId)
                    src.recycle()
                }
            }
        } finally {
            detector.release()
            dewarp.release()
        }
        Log.i(TAG, "REGTEST_SHARP_PASS aiOn=$aiOn images=${names.size} sum=$sum")
        return sum
    }

    // ==========================================================================================
    // helpers (OpenCV-based; no new dependencies)
    // ==========================================================================================

    private fun decode(ctx: Context, path: String): Bitmap? = try {
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (t: Throwable) {
        null
    }

    private fun decodeFile(path: String): Bitmap? = try {
        BitmapFactory.decodeFile(path)
    } catch (t: Throwable) {
        null
    }

    /** Laplacian variance (crispness proxy) of a bitmap's grayscale image. */
    private fun lapVariance(bmp: Bitmap): Double {
        val rgba = Mat()
        val gray = Mat()
        val lap = Mat()
        val m = MatOfDouble()
        val s = MatOfDouble()
        try {
            Utils.bitmapToMat(bmp, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(gray, lap, CvType.CV_64F)
            Core.meanStdDev(lap, m, s)
            val sd = s.toArray()[0]
            return sd * sd
        } finally {
            rgba.release(); gray.release(); lap.release(); m.release(); s.release()
        }
    }

    /**
     * SUBTASK H4 failure-mode invariant. A quad whose bounding box spans (nearly) the whole frame in
     * either axis while the page area is small is the "corners pinned to the frame border" runaway
     * mode. A page that genuinely fills the frame is excluded by [RUNAWAY_MIN_PAGE_AREA].
     */
    private fun looksLikeRunaway(q: Quad, w: Int, h: Int, areaFrac: Double): Boolean {
        if (w <= 0 || h <= 0) return false
        val p = q.points
        val minX = p.minOf { it.x }
        val maxX = p.maxOf { it.x }
        val minY = p.minOf { it.y }
        val maxY = p.maxOf { it.y }
        val spansW = (maxX - minX) >= RUNAWAY_SPAN_FRAC * w
        val spansH = (maxY - minY) >= RUNAWAY_SPAN_FRAC * h
        return (spansW || spansH) && areaFrac < RUNAWAY_MIN_PAGE_AREA
    }

    private fun areaFraction(q: Quad, w: Int, h: Int): Double {
        if (w <= 0 || h <= 0) return -1.0
        val p = q.points
        var sum = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            sum += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
        }
        val area = kotlin.math.abs(sum) / 2.0
        return area / (w.toDouble() * h.toDouble())
    }

    private fun sameQuad(a: Quad?, b: Quad, tol: Float): Boolean {
        if (a == null) return false
        val pa = a.points
        val pb = b.points
        if (pa.size != pb.size) return false
        for (i in pa.indices) {
            if (kotlin.math.abs(pa[i].x - pb[i].x) > tol) return false
            if (kotlin.math.abs(pa[i].y - pb[i].y) > tol) return false
        }
        return true
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.4f", v)
}
