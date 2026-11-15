package com.docscanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.core.di.ServiceLocator
import com.docscanai.data.EnhancementMode
import com.docscanai.data.PageEnhanceSettings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * ============================================================================================
 * SUBTASK H3a — corpus lane, now including a REAL document-rectification BENCHMARK group.
 * (Extends the H1/H3 measurement lane. The production fixes live in the app module.)
 * ============================================================================================
 *
 * The lane drives the app's REAL pipeline ([com.docscanai.capture.ScanPipeline.process]) for THREE
 * clearly separated groups and records, per image and per AI mode:
 *
 *  1. **benchmark** — REAL photographs of document pages taken for rectification, from the
 *     **DocUNet Benchmark** (Ma et al., CVPR 2018), each with a **ground-truth flatbed scan** of the
 *     same page. Packaged under `assets/corpus/benchmark/inputs/` with the GT scan of the same file
 *     name under `assets/corpus/benchmark/gt_scan/`. Optional corner annotations may be dropped into
 *     `assets/corpus/benchmark/gt_corners/<name>.json` (see [loadGtCorners]).
 *  2. **synthetic** — the seeded, deterministic set with known corner ground truth (unchanged).
 *  3. **negatives** — genuine not-a-document scene photos / textures whose only requirement is
 *     "must not be warped" (unchanged).
 *
 * Per image (and per AI mode) the lane records:
 *  - detection verdict, warp path (`neural` / `classical` / `rectified_skip` / `skipped_no_detection`
 *    / `perspective_only` / `none`), the `rectified-score`, per-stage timings, in/out geometry;
 *  - **quad sanity** (convex / self-intersecting / ordering), the near-full-frame flag and the
 *    **quad-placement audit** INSIDE / OUTSIDE / MIXED versus ground truth;
 *  - **corner error vs ground-truth corners** (px and % of the image diagonal) where corner GT
 *    exists;
 *  - **fidelity vs the ground-truth flat scan**: PSNR and SSIM (implemented with OpenCV here — no
 *    new dependencies);
 *  - **text-legibility proxies** for INPUT, AI-ON output and AI-OFF output: crispness (Laplacian
 *    variance) and text-line straightness (ink row-projection coefficient of variation);
 *  - the **delivered output dimensions** actually produced (the suspected sharpness loss);
 *  - the over-warp / distortion deviation (**MAD**) against a plain non-warped enhanced frame;
 *  - an **annotated detected-quad overlay PNG** of the pre-warp INPUT (`overlay_quad.png`).
 *
 * ## AI ON vs AI OFF
 * Two separate instrumentation processes, exactly as in H1 (see [`tooling/corpus_run.cmd`]); the
 * lane asserts AI-ON session creation and AI-OFF zero sessions.
 *
 * ## Diagnostic-only
 * The overlay and all metrics are read-only observations. This lane makes NO production change and
 * adds NO user-visible UI.
 *
 * ## Output
 * `corpus_report_<mode>.json|csv`, `benchmark_report_<mode>.json|csv` + a combined
 * `corpus_report.json` under `Android/data/com.docscanai/files/corpus_out/`, pulled by the runner.
 * Per-image artifacts: `input.jpg`, `raw.jpg`, `page.jpg`, `thumb.jpg`, `gt_scan.jpg`,
 * `overlay_input.jpg`, `overlay_quad.png`, `docscan.txt`.
 */
@RunWith(AndroidJUnit4::class)
class CorpusRealLifeTest {

    companion object {
        private const val TAG = "DocScan"

        /** Fixed seed ⇒ reproducible corpus across both AI-ON and AI-OFF processes. */
        private const val SEED = 0x5D0C5A1L

        /** Working cap matching the pipeline's own `MAX_PROCESS_LONG_EDGE` (2000 px). */
        private const val WORK_LONG_EDGE = 2000

        /** Synthetic canvas (portrait) — its long edge is < WORK_LONG_EDGE so no pipeline downscale. */
        private const val CANVAS_W = 1000
        private const val CANVAS_H = 1300

        /** Page render size (SUBTASK H3: raised from 700 to make the synthetic POSITIVES crisper). */
        private const val PAGE_W = 900

        /** Detection tolerance: fraction of the diagonal (task spec: ~5%). */
        private const val TOL_FRAC_DIAG = 0.05

        /** Distortion flag threshold on the normalized mean-abs-diff (0..1). */
        private const val DISTORTION_MAD_FLAG = 0.06

        /** Near-full-frame flag threshold on the effective quad area fraction. */
        private const val NEAR_FULL_FRAME_FRAC = 0.98

        /**
         * Real photographs that are NOT plausible scan inputs (scene / not-a-document). Classified
         * as the NEGATIVE set: their only requirement is "must not be warped". Everything else in
         * `assets/corpus/real/` is a positive scan input (receipt / invoice / form / book page).
         */
        private val REAL_NEGATIVES = setOf(
            "real_a_woman_fills_out_a_bear_information_management",
            "real_media_viewer_launch_retrospective_whiteboard"
        )

        private lateinit var context: Context

        @JvmStatic
        @BeforeClass
        fun setUp() {
            context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.init(context)
            ServiceLocator.ensureOpenCv()
            Log.i(TAG, "CORPUS_SETUP openCv=${ServiceLocator.ensureOpenCv()} ${com.docscanai.core.util.Diag.memString()}")
        }
    }

    /** AI-ON half of the lane. Run in its own instrumentation process. */
    @Test
    fun corpus_ai_on() = runLane(aiOn = true)

    /** AI-OFF half of the lane. Run in its own instrumentation process. */
    @Test
    fun corpus_ai_off() = runLane(aiOn = false)

    // ==========================================================================================
    // Lane driver
    // ==========================================================================================

    private fun runLane(aiOn: Boolean) {
        val mode = if (aiOn) "ai_on" else "ai_off"
        val settings = PageEnhanceSettings(mode = EnhancementMode.ENHANCED)

        // --- deterministic gate state for THIS process ---------------------------------------
        runBlocking {
            runCatching { ServiceLocator.settingsRepository.setNeuralProcessingEnabled(aiOn) }
            runCatching { ServiceLocator.settingsRepository.setUseXnnpack(false) }
        }
        ServiceLocator.neuralProcessingEnabled = aiOn
        ServiceLocator.useXnnpack = false
        NeuralRepro.requireModels()
        if (aiOn) {
            ServiceLocator.ortSessionFactory
        } else {
            Log.i(TAG, "CORPUS_GATE ai=OFF neuralProcessingEnabled=false (models present, gate closed)")
        }

        val pipeline = ServiceLocator.pipeline
        val enhancer = ServiceLocator.enhancer
        val sessionId = "corpus-$mode-${System.currentTimeMillis()}"

        val outRoot = File(context.getExternalFilesDir(null), "corpus_out/$mode")
        if (!outRoot.exists()) outRoot.mkdirs()
        val imgRoot = File(outRoot, "images")
        imgRoot.mkdirs()

        val records = ArrayList<Rec>()
        var sessionLines = 0
        var nativeSignal = false
        val startedAll = System.currentTimeMillis()

        try {
            val items = buildCorpus()
            Log.i(TAG, "CORPUS_LANE mode=$mode images=${items.size} out=${outRoot.absolutePath}")
            items.forEachIndexed { idx, item ->
                val t0 = System.currentTimeMillis()
                val input: Bitmap? = item.load()
                if (input == null) {
                    Log.w(TAG, "CORPUS_IMG mode=$mode name=${item.name} SKIP (decode failed)")
                    return@forEachIndexed
                }
                val src = capLongEdge(input, WORK_LONG_EDGE)
                val dir = File(imgRoot, item.name).also { it.mkdirs() }
                try {
                    saveJpeg(src, File(dir, "input.jpg"), 90)

                    // --- capture logcat for THIS image only -------------------------------------
                    shell("logcat -c")
                    val stages = ArrayList<Pair<String, Long>>()
                    // F4 (H3) + H3a: capture the pre-warp detected-quad overlay (DEBUG builds).
                    // The sink receives a SEPARATE bitmap; we HOLD it (no recycle) so it can be
                    // annotated with the final verdict + GT error once the metrics are known.
                    var overlayBmp: Bitmap? = null
                    val page = runBlocking {
                        pipeline.process(
                            sessionId = sessionId,
                            source = src,
                            quad = null, // detector runs on the still (exercises the AI gate/path)
                            enhancement = settings,
                            onStage = { st -> synchronized(stages) { stages.add(st.name to (System.currentTimeMillis() - t0)) } },
                            debugOverlaySink = { ov ->
                                runCatching { saveJpeg(ov, File(dir, "overlay_input.jpg"), 90) }
                                overlayBmp = ov
                            }
                        )
                    }
                    val dump = shell("logcat -d -v brief -s DocScan")
                    val docLines = dump.lines().filter { it.contains("DocScan") && it.contains("[") }
                    val found = docLines.any { it.contains("[Detect]") && it.contains("found=true") }
                    val skippedNoDet = docLines.any { it.contains("skipped (no detection)") }
                    val rectSkip = docLines.any { it.contains("stage2 skipped (already rectified") }
                    // SUBTASK H7: the primary path logs "stage2 grid dewarp applied
                    // (single-sample remap)" and the fallback logs "stage2 image dewarp applied".
                    val neural = docLines.any {
                        it.contains("stage2 neural dewarp applied") ||
                            it.contains("stage2 grid dewarp applied") ||
                            it.contains("stage2 image dewarp applied")
                    }
                    val classical = docLines.any { it.contains("stage3 classical curvature applied") }
                    val perspOnly = docLines.any { it.contains("perspective only") }
                    val warpPath = when {
                        skippedNoDet -> "skipped_no_detection"
                        neural -> "neural"
                        rectSkip -> "rectified_skip"
                        classical -> "classical"
                        perspOnly -> "perspective_only"
                        docLines.any { it.contains("[Warp]") } -> "warped_unclassified"
                        else -> "none"
                    }
                    val rectScore = docLines.firstOrNull { it.contains("rectified-score=") }
                        ?.substringAfter("rectified-score=")?.trim()?.substringBefore(" ")
                        ?.toDoubleOrNull() ?: -1.0
                    val sessNow = docLines.count { it.contains("session EP=") }
                    sessionLines += sessNow
                    if (dump.contains("Fatal signal") || dump.contains("SIGSEGV") ||
                        dump.contains("SIGABRT") || dump.contains("signal 11") || dump.contains("signal 6")
                    ) nativeSignal = true
                    File(dir, "docscan.txt").writeText(docLines.joinToString("\n"))

                    // --- artifacts --------------------------------------------------------------
                    val pageBmp = decodeFile(page.imageFile)
                    if (pageBmp != null) saveJpeg(pageBmp, File(dir, "page.jpg"), 92)
                    page.rawFile?.let { decodeFile(it)?.let { r -> saveJpeg(r, File(dir, "raw.jpg"), 92); r.recycle() } }
                    page.thumbnailFile?.let { copyFile(File(it), File(dir, "thumb.jpg")) }

                    // --- ground truth --------------------------------------------------------
                    // Corner GT: the synthetic set carries it in-process; the benchmark drop-folder
                    // can add `gt_corners/<name>.json`. Flat-scan GT: synthetic -> the bundled page,
                    // benchmark -> the packaged DocUNet flatbed scan of the same page.
                    val gtCorners: List<CvPoint>? = item.gt ?: loadGtCorners(item.gtCornerKey, src)
                    val flatGt: Bitmap? = item.flat()
                    if (flatGt != null) saveJpeg(flatGt, File(dir, "gt_scan.jpg"), 90)

                    // --- detection metrics ------------------------------------------------------
                    val diag = hypot(src.width.toDouble(), src.height.toDouble())
                    val detPts = page.corners?.points?.map { CvPoint(it.x.toDouble(), it.y.toDouble()) }
                    var meanErr = -1.0; var errPct = -1.0; var withinTol = false
                    if (gtCorners != null && detPts != null && detPts.size == 4) {
                        val (e, rot) = bestCornerError(gtCorners, detPts)
                        meanErr = e; errPct = e / diag
                        withinTol = errPct <= TOL_FRAC_DIAG && rot in 0..3
                    }
                    val quadAreaFrac = if (detPts != null && detPts.size == 4) {
                        abs(polygonArea(detPts)) / (src.width.toDouble() * src.height.toDouble())
                    } else -1.0
                    val convex = detPts != null && detPts.size == 4 && isConvex(detPts)
                    val selfInt = detPts != null && detPts.size == 4 && selfIntersects(detPts)
                    val orderOk = detPts != null && detPts.size == 4 && convex && !selfInt
                    val nearFull = quadAreaFrac >= NEAR_FULL_FRAME_FRAC
                    val placement = if (gtCorners != null && detPts != null && detPts.size == 4) {
                        placementVerdict(detPts, gtCorners)
                    } else "-"

                    // --- legibility proxies for INPUT / AI-ON output / AI-OFF output -------------
                    val inSharp = lapVariance(src)
                    val inStraight = straightness(src)
                    val inContrast = grayStd(src)
                    val outSharp = if (pageBmp != null) lapVariance(pageBmp) else -1.0
                    val outStraight = if (pageBmp != null) straightness(pageBmp) else -1.0
                    val outContrast = if (pageBmp != null) grayStd(pageBmp) else -1.0
                    val (outFill, _) = if (pageBmp != null) outputRectFill(pageBmp) else Pair(-1.0, 0)
                    val aspect = if (pageBmp != null && pageBmp.height > 0) pageBmp.width.toDouble() / pageBmp.height else -1.0
                    val aspectPlausible = aspect in 0.30..3.0

                    // --- fidelity vs the GT flat scan (PSNR / SSIM) ------------------------------
                    var gtPsnr = -1.0; var gtSsim = -1.0; var gtSharp = -1.0; var sharpRatioGt = -1.0
                    var gtStraight = -1.0
                    if (flatGt != null) {
                        if (pageBmp != null) {
                            val ga = grayResized(flatGt)
                            val go = grayResized(pageBmp)
                            try {
                                gtPsnr = psnr(ga, go)
                                gtSsim = ssim(ga, go)
                            } finally {
                                ga.release(); go.release()
                            }
                        }
                        gtSharp = lapVariance(flatGt)
                        if (gtSharp > 1e-6) sharpRatioGt = outSharp / gtSharp
                        gtStraight = straightness(flatGt)
                        flatGt.recycle()
                    }

                    // Over-warp reference: a plain (non-warped) enhanced frame.
                    var mad = -1.0; var sharpRatio = -1.0
                    if (pageBmp != null) {
                        val srcCopy = src.copy(Bitmap.Config.ARGB_8888, false)
                        val ref = runBlocking { runCatching { enhancer.enhance(srcCopy, settings) }.getOrDefault(srcCopy) }
                        val dev = deviation(pageBmp, ref)
                        mad = dev.first; sharpRatio = dev.second
                        if (ref !== srcCopy) ref.recycle()
                        srcCopy.recycle()
                    }

                    // A distorted-suspect can only occur when the neural grid ran on a page that was
                    // NOT a real detection, or was near-full-frame.
                    val distortedSuspect = aiOn && neural &&
                        (!found || nearFull) && mad >= 0 && mad >= DISTORTION_MAD_FLAG

                    // --- SUBTASK H3a: annotated detected-quad overlay PNG -----------------------
                    // Diagnostic only: annotated copy of the pre-warp INPUT with the 4 detected
                    // corners (numbered in pipeline order, drawn by QuadOverlayRenderer) plus a
                    // header carrying the name, mode, detection verdict, full-frame-fallback flag,
                    // quad area fraction and the corner error versus ground truth.
                    val overlayPng: String? = try {
                        val ov = overlayBmp
                        if (ov != null && !ov.isRecycled) {
                            val hdr = buildString {
                                append(mode).append("  ").append(item.name)
                                append("   [").append(item.group).append("]\n")
                                append("detected=").append(found)
                                append("   fullFrameFallback=").append(!found)
                                append("   quadAreaFrac=").append(fmt(quadAreaFrac))
                                append("   quad=").append(if (orderOk) "convex+ordered" else "IRREGULAR")
                                append("   nearFullFrame=").append(nearFull).append('\n')
                                append("cornerErrVsGT=")
                                append(if (meanErr >= 0) "${fmt(meanErr)} px / ${fmt(errPct * 100.0)} %diag" else "n/a")
                                append("   placement=").append(placement)
                                append("   warpPath=").append(warpPath)
                                append("   out=").append(pageBmp?.width ?: -1).append('x').append(pageBmp?.height ?: -1)
                            }
                            val f = File(dir, "overlay_quad.png")
                            annotateAndSavePng(ov, f, hdr)
                            if (!ov.isRecycled) ov.recycle()
                            f.name
                        } else null
                    } catch (t: Throwable) {
                        Log.w(TAG, "CORPUS_OVERLAY mode=$mode name=${item.name} failed: ${t.message}")
                        null
                    }
                    overlayBmp = null

                    records.add(
                        Rec(
                            name = item.name, category = item.category, kind = item.kind,
                            group = item.group, aiMode = mode,
                            hasGt = flatGt != null || gtCorners != null,
                            hasCornerGt = gtCorners != null, hasScanGt = flatGt != null,
                            detectedFound = found, fullFrameFallback = !found, warpPath = warpPath,
                            cornerMeanErrPx = meanErr, cornerErrPctDiag = errPct, withinTol = withinTol,
                            quadConvex = convex, quadSelfIntersect = selfInt, quadAreaFrac = quadAreaFrac,
                            quadOrderOk = orderOk, nearFullFrame = nearFull,
                            quadRectScore = rectScore, quadPlacement = placement,
                            outW = pageBmp?.width ?: -1, outH = pageBmp?.height ?: -1,
                            outAspect = aspect, outRectFill = outFill, outAspectPlausible = aspectPlausible,
                            gtPsnr = gtPsnr, gtSsim = gtSsim, gtSharp = gtSharp, outSharp = outSharp,
                            sharpRatioGt = sharpRatioGt, gtStraight = gtStraight,
                            inSharp = inSharp, inStraight = inStraight, outStraight = outStraight,
                            inContrast = inContrast, outContrast = outContrast,
                            devMad = mad, devSharpRatio = sharpRatio,
                            totalMs = System.currentTimeMillis() - t0, detectMs = stageMs(stages, "DETECTING", "WARPING"),
                            warpMs = stageMs(stages, "WARPING", "ENHANCING"),
                            enhanceMs = stageMs(stages, "ENHANCING", "STORING"),
                            storeMs = stageMs(stages, "STORING", "DONE"),
                            distortedSuspect = distortedSuspect, sessionLines = sessNow,
                            nativeSignal = nativeSignal, overlayPng = overlayPng,
                            note = when (item.kind) {
                                "negative" -> "not-a-document negative"
                                "benchmark" -> "DocUNet benchmark; GT = flatbed scan" +
                                    (if (gtCorners != null) " + corners" else " (no corner GT)")
                                else -> ""
                            }
                        )
                    )

                    Log.i(
                        TAG,
                        "CORPUS_IMG mode=$mode [${idx + 1}/${items.size}] name=${item.name} " +
                            "group=${item.group} found=$found warp=$warpPath rectScore=${fmt(rectScore)} " +
                            "nearFull=$nearFull mad=${fmt(mad)} psnr=${fmt(gtPsnr)} ssim=${fmt(gtSsim)} " +
                            "place=$placement errPct=${fmt(errPct)} out=${pageBmp?.width ?: -1}x${pageBmp?.height ?: -1} " +
                            "inSharp=${fmt(inSharp)} outSharp=${fmt(outSharp)} " +
                            "inStraight=${fmt(inStraight)} outStraight=${fmt(outStraight)} " +
                            "totalMs=${System.currentTimeMillis() - t0} sessions=$sessionLines"
                    )
                    pageBmp?.recycle()
                } catch (t: Throwable) {
                    Log.e(TAG, "CORPUS_IMG mode=$mode name=${item.name} ERROR: ${t.message}", t)
                    records.add(Rec.error(item.name, item.category, item.kind, item.group, mode, t.toString()))
                } finally {
                    if (src !== input) input.recycle()
                    src.recycle()
                }
            }
        } finally {
            runCatching { pipeline.deleteSession(sessionId) }
        }

        val full = shell("logcat -d -v brief")
        if (full.contains("Fatal signal") || full.contains("SIGSEGV") || full.contains("SIGABRT")) nativeSignal = true

        val elapsed = System.currentTimeMillis() - startedAll
        val summary = summarize(records, aiOn, sessionLines, nativeSignal, elapsed)
        writeReports(outRoot, mode, records, summary)

        Log.i(TAG, "CORPUS_RUN mode=${mode.uppercase()} images=${records.size} sessions=$sessionLines " +
            "native=${if (nativeSignal) 1 else 0} elapsedMs=$elapsed " +
            "notDetectedFallback=${summary["not_detected_fallback_warp"]} " +
            "distortedSuspect=${summary["distorted_suspect"]} " +
            "neuralCount=${summary["warp_neural"]} classicalCount=${summary["warp_classical"]} " +
            "skipNoDetection=${summary["warp_skipped_no_detection"]} rectifiedSkip=${summary["warp_rectified_skip"]} " +
            "benchDetected=${summary["group_benchmark_detected"]}/${summary["group_benchmark_count"]} " +
            "benchPsnr=${summary["group_benchmark_mean_psnr"]} benchSsim=${summary["group_benchmark_mean_ssim"]} " +
            "benchPlaceInside=${summary["group_benchmark_placement_inside"]} " +
            "benchPlaceOutside=${summary["group_benchmark_placement_outside"]} " +
            "benchPlaceMixed=${summary["group_benchmark_placement_mixed"]}")
        Log.i(TAG, "CORPUS_SESSIONS_${mode.uppercase()}=$sessionLines")

        // --- validity gates (INVALID, never a false pass) ------------------------------------
        if (aiOn && sessionLines == 0) {
            val msg = "${NeuralRepro.SENTINEL_INVALID_NO_SESSION} :: AI-ON corpus run created ZERO " +
                "ONNX sessions ([OrtSessionFactory] session EP= was never logged) — the neural path " +
                "was not exercised; the result is INVALID."
            Log.e(TAG, msg); throw AssertionError(msg)
        }
        if (!aiOn && sessionLines > 0) {
            val msg = "CORPUS_INVALID ai=OFF but $sessionLines 'session EP=' line(s) observed — the " +
                "AI gate leaked and created ONNX sessions; INVALID."
            Log.e(TAG, msg); throw AssertionError(msg)
        }
        if (nativeSignal) {
            val msg = "CORPUS_INVALID native signal observed during the $mode run"
            Log.e(TAG, msg); throw AssertionError(msg)
        }
    }

    // ==========================================================================================
    // Corpus construction
    // ==========================================================================================

    private class Item(
        val name: String,
        val category: String,
        val kind: String,
        val gt: List<CvPoint>?,
        private val flatLoader: (() -> Bitmap?)?,
        /** Asset file name (relative to the group folder) used to look up `gt_corners/<x>.json`. */
        val gtCornerKey: String? = null,
        private val loader: () -> Bitmap?
    ) {
        fun load(): Bitmap? = loader()
        /** The KNOWN FLAT page (the ideal, un-warped output), or null. */
        fun flat(): Bitmap? = flatLoader?.invoke()

        /** Coarse reporting group: benchmark real / synthetic controlled / negatives. */
        val group: String get() = when {
            kind == "negative" -> "negatives"
            kind == "benchmark" -> "benchmark"
            else -> "synthetic"
        }
    }

    private fun buildCorpus(): List<Item> {
        val items = ArrayList<Item>()
        val rng = Random(SEED)

        // --- synthetic (seeded, with ground truth) -------------------------------------------
        var n = 0
        fun add(name: String, category: String, gt: List<CvPoint>?, postFx: String, kind: String = "positive") {
            val flatLoader: (() -> Bitmap?)? = if (gt != null) ({ flatGtBitmap() }) else null
            items.add(
                Item(name, category, kind, gt, flatLoader, null) { renderSynthetic(name, gt, postFx) }
            )
            n++
        }

        // rotations ±small
        repeat(5) { i ->
            val ang = (-30.0 + rng.nextDouble() * 60.0).coerceAtLeast(12.0) * (if (i % 2 == 0) 1 else -1)
            add("syn_rot_small_$i", "rotation_small", rotatedQuad(ang, 0.10), "none")
        }
        // near-90° rotations
        add("syn_rot_90_0", "rotation_90", rotatedQuad(88.0, 0.10), "none")
        add("syn_rot_90_1", "rotation_90", rotatedQuad(92.0, 0.10), "none")
        add("syn_rot_90_2", "rotation_90", rotatedQuad(90.0, 0.06), "none")
        // perspective warps
        repeat(5) { add("syn_persp_mild_$it", "perspective_mild", jitterQuad(0.10, 0.04, rng), "none") }
        repeat(4) { add("syn_persp_mod_$it", "perspective_moderate", jitterQuad(0.12, 0.09, rng), "none") }
        repeat(4) { add("syn_persp_strong_$it", "perspective_strong", jitterQuad(0.14, 0.15, rng), "none") }
        repeat(3) { add("syn_persp_offcentre_$it", "perspective_offcentre", shiftQuad(jitterQuad(0.10, 0.06, rng), 0.14, -0.10), "none") }
        // partially out of frame
        repeat(3) { add("syn_partial_oof_$it", "partial_out_of_frame", shiftQuad(jitterQuad(0.10, 0.05, rng), 0.30, 0.24), "none") }
        // already-rectified page filling the frame (the user's proof case lives here)
        repeat(3) { add("syn_rectified_full_$it", "rectified_full", fullFrameQuad(), "none") }
        // lighting (labelled, not a detection stress)
        add("syn_light_brightness", "lighting_brightness", fullFrameQuad(), "brightness_ramp")
        add("syn_light_vignette", "lighting_vignette", fullFrameQuad(), "vignette")
        add("syn_light_shadow_band", "lighting_shadow_band", fullFrameQuad(), "shadow_band")
        add("syn_light_lowcontrast", "lighting_low_contrast", jitterQuad(0.10, 0.05, rng), "low_contrast")
        // degradation — strictly labelled stress cases
        repeat(2) { add("syn_deg_blur_$it", "degradation_blur", jitterQuad(0.12, 0.06, rng), "blur") }
        repeat(2) { add("syn_deg_noise_$it", "degradation_noise", jitterQuad(0.12, 0.06, rng), "noise") }
        repeat(2) { add("syn_deg_small_$it", "degradation_small_scale", jitterQuad(0.12, 0.05, rng), "small_scale") }
        // negatives (NO document) — not-a-document set
        repeat(2) { add("syn_neg_texture_$it", "negative_texture", null, "none", kind = "negative") }
        repeat(2) { add("syn_neg_clutter_$it", "negative_clutter", null, "none", kind = "negative") }

        // --- real photos (packaged assets, no network) ---------------------------------------
        // NOTE: androidTest assets live in the TEST APK, so they must be read from the
        // instrumentation (test) context - NOT the target/app context (which has no corpus/).
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val real = runCatching { testAssets.list("corpus/real")?.toList() ?: emptyList() }.getOrDefault(emptyList())
        real.filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
            .sorted()
            .forEach { f ->
                val nm = "real_" + f.substringBeforeLast('.')
                val isNeg = REAL_NEGATIVES.contains(nm)
                val cat = if (isNeg) "real_scene_not_a_document" else "real_photo"
                val kind = if (isNeg) "negative" else "positive"
                items.add(Item(nm, cat, kind, null, null, null) {
                    runCatching {
                        testAssets.open("corpus/real/$f").use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                })
            }

        // --- SUBTASK H3a: benchmark group — REAL photographs with a GT flat scan --------------
        // Packaged under `assets/corpus/benchmark/inputs/`; the ground-truth flatbed scan of the
        // SAME page is the sibling file of the same name under `corpus/benchmark/gt_scan/`.
        val bench = runCatching {
            testAssets.list("corpus/benchmark/inputs")?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
        var nb = 0
        bench.filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
            .sorted()
            .forEach { f ->
                val nm = "bench_" + f.substringBeforeLast('.')
                val gtLoader: (() -> Bitmap?)? = {
                    runCatching {
                        testAssets.open("corpus/benchmark/gt_scan/$f").use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
                items.add(Item(nm, "benchmark_real", "benchmark", null, gtLoader, f) {
                    runCatching {
                        testAssets.open("corpus/benchmark/inputs/$f").use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                })
                nb++
            }

        Log.i(TAG, "CORPUS_BUILT synthetic=$n real=${real.size} benchmark=$nb total=${items.size} " +
            "negatives=${items.count { it.kind == "negative" }} seed=$SEED")
        return items
    }

    /**
     * Optional corner ground truth for a dropped-in benchmark image.
     *
     * Expected file: `assets/corpus/benchmark/gt_corners/<assetFileName>.json` containing
     * `{"width":W,"height":H,"points":[[x,y],[x,y],[x,y],[x,y]]}` in the ORIGINAL image's pixel
     * space (order TL, TR, BR, BL). The points are scaled into the lane's working (`src`) space so
     * the corner error is comparable. Returns null when absent (DocUNet ships scan GT, not corners).
     */
    private fun loadGtCorners(assetFileName: String?, src: Bitmap): List<CvPoint>? = runCatching {
        if (assetFileName == null) return null
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val txt = testAssets.open("corpus/benchmark/gt_corners/$assetFileName.json")
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val w = Regex("\"width\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(txt)?.groupValues?.get(1)?.toDouble()
            ?: src.width.toDouble()
        val h = Regex("\"height\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(txt)?.groupValues?.get(1)?.toDouble()
            ?: src.height.toDouble()
        val body = txt.substringAfter("\"points\"", "")
        val nums = Regex("-?[0-9]+(?:\\.[0-9]+)?").findAll(body).map { it.value.toDouble() }.toList()
        if (nums.size < 8) return null
        val sx = src.width.toDouble() / w
        val sy = src.height.toDouble() / h
        (0 until 4).map { i -> CvPoint(nums[i * 2] * sx, nums[i * 2 + 1] * sy) }
    }.getOrNull()

    /** Renders a synthetic scene. `gt` is in FINAL canvas pixel space (TL,TR,BR,BL), or null. */
    private fun renderSynthetic(name: String, gt: List<CvPoint>?, postFx: String): Bitmap? {
        val r = Random(SEED + name.hashCode())
        val canvas = Mat(CANVAS_H, CANVAS_W, CvType.CV_8UC4)
        drawBackground(canvas, r)
        val page = loadPageMat() ?: return null
        try {
            if (gt != null) pastePage(canvas, page, gt)
            else if (name.startsWith("syn_neg_clutter")) drawClutter(canvas, r)
            applyPostFx(canvas, postFx, r)
            val out = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(canvas, out)
            return out
        } finally {
            page.release()
            canvas.release()
        }
    }

    /** The KNOWN FLAT page (ideal un-warped output) as a bitmap, for GT fidelity metrics. */
    private fun flatGtBitmap(): Bitmap? {
        val m = loadPageMat() ?: return null
        return try {
            val b = Bitmap.createBitmap(m.cols(), m.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(m, b)
            b
        } finally {
            m.release()
        }
    }

    private fun loadPageMat(): Mat? {
        val bmp = runCatching {
            context.assets.open("testdocs/test_page.jpg").use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        val scaled = capLongEdge(bmp, PAGE_W * 2)
        val m = Mat()
        Utils.bitmapToMat(ensureArgb(scaled), m)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return m
    }

    private fun drawBackground(canvas: Mat, r: Random) {
        val bg = Scalar(200.0, 205.0, 210.0, 255.0)
        canvas.setTo(bg)
        val noise = Mat(canvas.size(), CvType.CV_8UC4)
        Core.randn(noise, 0.0, 5.0)
        Core.add(canvas, noise, canvas)
        noise.release()
        repeat(6) {
            val y = r.nextInt(CANVAS_H)
            Imgproc.line(canvas, CvPoint(0.0, y.toDouble()), CvPoint(CANVAS_W.toDouble(), y.toDouble()),
                Scalar(180.0, 184.0, 188.0, 255.0), 1)
        }
    }

    private fun drawClutter(canvas: Mat, r: Random) {
        repeat(9) {
            val x1 = r.nextInt(CANVAS_W); val y1 = r.nextInt(CANVAS_H)
            val x2 = (x1 + 80 + r.nextInt(300)).coerceAtMost(CANVAS_W)
            val y2 = (y1 + 60 + r.nextInt(260)).coerceAtMost(CANVAS_H)
            val c = Scalar((80 + r.nextInt(140)).toDouble(), (80 + r.nextInt(140)).toDouble(), (80 + r.nextInt(140)).toDouble(), 255.0)
            Imgproc.rectangle(canvas, CvPoint(x1.toDouble(), y1.toDouble()), CvPoint(x2.toDouble(), y2.toDouble()), c, -1)
        }
        repeat(14) {
            Imgproc.line(canvas, CvPoint(r.nextInt(CANVAS_W).toDouble(), r.nextInt(CANVAS_H).toDouble()),
                CvPoint(r.nextInt(CANVAS_W).toDouble(), r.nextInt(CANVAS_H).toDouble()),
                Scalar(40.0, 40.0, 40.0, 255.0), 2)
        }
    }

    /** Warps [page] into [canvas] at destination quad [gt] (canvas pixels). */
    private fun pastePage(canvas: Mat, page: Mat, gt: List<CvPoint>) {
        val pw = page.cols(); val ph = page.rows()
        val srcPts = MatOfPoint2f(
            CvPoint(0.0, 0.0),
            CvPoint((pw - 1).toDouble(), 0.0),
            CvPoint((pw - 1).toDouble(), (ph - 1).toDouble()),
            CvPoint(0.0, (ph - 1).toDouble())
        )
        val dstPts = MatOfPoint2f(*gt.toTypedArray())
        var h: Mat? = null
        val warped = Mat(); val mask = Mat(); val warpedMask = Mat()
        try {
            h = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            Imgproc.warpPerspective(page, warped, h, Size(CANVAS_W.toDouble(), CANVAS_H.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0, 0.0))
            val pageMask = Mat(ph, pw, CvType.CV_8UC1, Scalar(255.0))
            Imgproc.warpPerspective(pageMask, warpedMask, h, Size(CANVAS_W.toDouble(), CANVAS_H.toDouble()),
                Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
            pageMask.release()
            warped.copyTo(canvas, warpedMask)
        } finally {
            srcPts.release(); dstPts.release(); h?.release()
            warped.release(); mask.release(); warpedMask.release()
        }
    }

    private fun applyPostFx(canvas: Mat, fx: String, r: Random) {
        when (fx) {
            "brightness_ramp" -> {
                val ramp = Mat(canvas.size(), CvType.CV_8UC4)
                for (y in 0 until canvas.rows()) {
                    val v = (y.toDouble() / canvas.rows()) * 120.0 - 40.0
                    ramp.row(y).setTo(Scalar(v, v, v, 0.0))
                }
                Core.add(canvas, ramp, canvas)
                ramp.release()
            }
            "vignette" -> {
                val v = Mat(canvas.size(), CvType.CV_8UC4)
                for (y in 0 until canvas.rows()) {
                    for (x in 0 until canvas.cols()) {
                        val dx = (x - canvas.cols() / 2.0) / (canvas.cols() / 2.0)
                        val dy = (y - canvas.rows() / 2.0) / (canvas.rows() / 2.0)
                        val d = min(1.0, hypot(dx, dy))
                        val f = 1.0 - 0.55 * d * d
                        v.put(y, x, 255.0 * f, 255.0 * f, 255.0 * f, 255.0)
                    }
                }
                Core.multiply(canvas, v, canvas, 1.0 / 255.0)
                v.release()
            }
            "shadow_band" -> {
                val x0 = canvas.cols() * 0.30
                val x1 = canvas.cols() * 0.55
                Imgproc.rectangle(canvas, CvPoint(x0, 0.0), CvPoint(x1, canvas.rows().toDouble()),
                    Scalar(60.0, 66.0, 72.0, 255.0), -1)
            }
            "low_contrast" -> canvas.convertTo(canvas, -1, 0.55, 90.0)
            "blur" -> Imgproc.GaussianBlur(canvas, canvas, Size(17.0, 17.0), 0.0)
            "noise" -> {
                val noise = Mat(canvas.size(), CvType.CV_8UC4)
                Core.randn(noise, 0.0, 26.0)
                Core.add(canvas, noise, canvas)
                noise.release()
            }
            "small_scale" -> {
                val small = Mat()
                Imgproc.resize(canvas, small, Size(canvas.cols() / 3.5, canvas.rows() / 3.5), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.resize(small, canvas, Size(CANVAS_W.toDouble(), CANVAS_H.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                small.release()
            }
        }
    }

    // --- quad builders (canvas space) ----------------------------------------------------

    private fun fullFrameQuad(): List<CvPoint> = listOf(
        CvPoint(0.0, 0.0),
        CvPoint((CANVAS_W - 1).toDouble(), 0.0),
        CvPoint((CANVAS_W - 1).toDouble(), (CANVAS_H - 1).toDouble()),
        CvPoint(0.0, (CANVAS_H - 1).toDouble())
    )

    private fun baseRect(margin: Double): List<CvPoint> {
        val mx = CANVAS_W * margin
        val my = CANVAS_H * margin
        return listOf(
            CvPoint(mx, my),
            CvPoint(CANVAS_W - mx, my),
            CvPoint(CANVAS_W - mx, CANVAS_H - my),
            CvPoint(mx, CANVAS_H - my)
        )
    }

    private fun rotatedQuad(angleDeg: Double, margin: Double): List<CvPoint> {
        val rect = baseRect(margin)
        val cx = CANVAS_W / 2.0; val cy = CANVAS_H / 2.0
        val a = Math.toRadians(angleDeg); val ca = Math.cos(a); val sa = Math.sin(a)
        return rect.map { p ->
            val dx = p.x - cx; val dy = p.y - cy
            CvPoint(cx + dx * ca - dy * sa, cy + dx * sa + dy * ca)
        }.let { orderTlTrBrBl(it) }
    }

    private fun jitterQuad(margin: Double, strength: Double, r: Random): List<CvPoint> {
        val rect = baseRect(margin)
        val jx = CANVAS_W * strength; val jy = CANVAS_H * strength
        val q = rect.map { p ->
            CvPoint(
                (p.x + (r.nextDouble() - 0.5) * 2 * jx).coerceIn(0.0, (CANVAS_W - 1).toDouble()),
                (p.y + (r.nextDouble() - 0.5) * 2 * jy).coerceIn(0.0, (CANVAS_H - 1).toDouble())
            )
        }
        return ensureConvex(orderTlTrBrBl(q), margin)
    }

    private fun shiftQuad(q: List<CvPoint>, dx: Double, dy: Double): List<CvPoint> =
        q.map { CvPoint(it.x + CANVAS_W * dx, it.y + CANVAS_H * dy) }

    private fun ensureConvex(q: List<CvPoint>, margin: Double): List<CvPoint> =
        if (isConvex(q) && !selfIntersects(q)) q else baseRect(margin)

    private fun orderTlTrBrBl(pts: List<CvPoint>): List<CvPoint> {
        var tl = pts[0]; var br = pts[0]; var tr = pts[0]; var bl = pts[0]
        var minSum = Double.MAX_VALUE; var maxSum = -Double.MAX_VALUE
        var minDiff = Double.MAX_VALUE; var maxDiff = -Double.MAX_VALUE
        for (p in pts) {
            val s = p.x + p.y; val d = p.x - p.y
            if (s < minSum) { minSum = s; tl = p }
            if (s > maxSum) { maxSum = s; br = p }
            if (d < minDiff) { minDiff = d; bl = p }
            if (d > maxDiff) { maxDiff = d; tr = p }
        }
        return listOf(tl, tr, br, bl)
    }

    // ==========================================================================================
    // Metrics
    // ==========================================================================================

    private fun bestCornerError(gt: List<CvPoint>, det: List<CvPoint>): Pair<Double, Int> {
        var best = Double.MAX_VALUE; var bestRot = 0
        for (rot in 0..3) {
            var sum = 0.0
            for (i in 0..3) {
                val d = det[(i + rot) % 4]
                sum += hypot(d.x - gt[i].x, d.y - gt[i].y)
            }
            val m = sum / 4.0
            if (m < best) { best = m; bestRot = rot }
        }
        return best to bestRot
    }

    private fun polygonArea(pts: List<CvPoint>): Double {
        var sum = 0.0
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }

    private fun isConvex(pts: List<CvPoint>): Boolean {
        if (pts.size != 4) return false
        var pos = 0; var neg = 0
        for (i in 0..3) {
            val a = pts[i]; val b = pts[(i + 1) % 4]; val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross > 1e-6) pos++ else if (cross < -1e-6) neg++
        }
        return pos == 4 || neg == 4
    }

    private fun selfIntersects(pts: List<CvPoint>): Boolean {
        if (pts.size != 4) return true
        fun seg(p1: CvPoint, p2: CvPoint, p3: CvPoint, p4: CvPoint): Boolean {
            val d1 = cross(p3, p4, p1); val d2 = cross(p3, p4, p2)
            val d3 = cross(p1, p2, p3); val d4 = cross(p1, p2, p4)
            return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        }
        return seg(pts[0], pts[1], pts[2], pts[3]) || seg(pts[1], pts[2], pts[3], pts[0])
    }

    private fun cross(a: CvPoint, b: CvPoint, c: CvPoint): Double =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    /** Quad-placement audit vs ground truth: INSIDE / OUTSIDE / MIXED (ray-casting per corner). */
    private fun placementVerdict(det: List<CvPoint>, gt: List<CvPoint>): String {
        if (det.size != 4 || gt.size != 4) return "-"
        var inside = 0
        for (p in det) if (pointInPolygon(p, gt)) inside++
        return when (inside) {
            4 -> "INSIDE"
            0 -> "OUTSIDE"
            else -> "MIXED"
        }
    }

    private fun pointInPolygon(p: CvPoint, poly: List<CvPoint>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i].x; val yi = poly[i].y
            val xj = poly[j].x; val yj = poly[j].y
            val intersect = ((yi > p.y) != (yj > p.y)) &&
                (p.x < (xj - xi) * (p.y - yi) / ((yj - yi) + 1e-12) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    /** Laplacian variance of the (gray) image — a standard sharpness/legibility proxy. */
    private fun lapVariance(bmp: Bitmap): Double = runCatching {
        val m = Mat(); val g = Mat(); val l = Mat()
        try {
            Utils.bitmapToMat(ensureArgb(bmp), m)
            Imgproc.cvtColor(m, g, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(g, l, CvType.CV_32F)
            val mean = MatOfDouble(); val std = MatOfDouble()
            Core.meanStdDev(l, mean, std)
            val s = std.toArray()[0]; s * s
        } finally { m.release(); g.release(); l.release() }
    }.getOrDefault(-1.0)

    /**
     * Text-line **straightness** proxy: the coefficient of variation (×100) of the row-projection
     * profile of the page's "ink" (255 − gray). Crisp, straight text lines give strong periodic
     * row peaks ⇒ high CV; bowed/blurred lines smear the peaks ⇒ lower CV.
     */
    private fun straightness(bmp: Bitmap): Double = runCatching {
        val m = Mat(); val g = Mat(); val inv = Mat(); val prof = Mat()
        try {
            Utils.bitmapToMat(ensureArgb(bmp), m)
            Imgproc.cvtColor(m, g, Imgproc.COLOR_RGBA2GRAY)
            Core.absdiff(g, Scalar(255.0), inv)
            Core.reduce(inv, prof, 1, Core.REDUCE_AVG)
            val mean = MatOfDouble(); val std = MatOfDouble()
            Core.meanStdDev(prof, mean, std)
            val mu = mean.toArray()[0]
            val sd = std.toArray()[0]
            if (mu <= 1e-6) 0.0 else (sd / mu) * 100.0
        } finally { m.release(); g.release(); inv.release(); prof.release() }
    }.getOrDefault(-1.0)

    private fun grayStd(bmp: Bitmap): Double = runCatching {
        val m = Mat(); val g = Mat()
        try {
            Utils.bitmapToMat(ensureArgb(bmp), m)
            Imgproc.cvtColor(m, g, Imgproc.COLOR_RGBA2GRAY)
            val mean = MatOfDouble(); val std = MatOfDouble()
            Core.meanStdDev(g, mean, std)
            std.toArray()[0]
        } finally { m.release(); g.release() }
    }.getOrDefault(-1.0)

    /** Two bitmaps → gray Mat resized to a common [size]×[size] square (caller releases). */
    private fun grayResized(bmp: Bitmap, size: Int = 384): Mat {
        val argb = ensureArgb(bmp)
        val m = Mat(); val g = Mat(); val r = Mat()
        try {
            Utils.bitmapToMat(argb, m)
            Imgproc.cvtColor(m, g, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(g, r, Size(size.toDouble(), size.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        } finally {
            m.release(); g.release()
            if (argb !== bmp) argb.recycle()
        }
        return r
    }

    /** Peak signal-to-noise ratio (dB) of two same-size 8-bit gray Mats; high == identical. */
    private fun psnr(a: Mat, b: Mat): Double = runCatching {
        val d = Mat(); val f = Mat()
        try {
            Core.absdiff(a, b, d)
            d.convertTo(f, CvType.CV_32F)
            Core.multiply(f, f, f)
            val mse = Core.mean(f).`val`[0]
            if (mse <= 1e-9) 99.0 else 10.0 * kotlin.math.log10(255.0 * 255.0 / mse)
        } finally { d.release(); f.release() }
    }.getOrDefault(-1.0)

    /**
     * GLOBAL (non-windowed) mean SSIM of two same-size 8-bit gray Mats — a robust structural
     * similarity proxy for aligning/resizing the ideal flat page against the output. Returns -1 on
     * failure. (A windowed SSIM would be preferable but the global form needs no extra code/deps.)
     */
    private fun ssim(a: Mat, b: Mat): Double = runCatching {
        val fa = Mat(); val fb = Mat(); val t = Mat()
        try {
            a.convertTo(fa, CvType.CV_32F)
            b.convertTo(fb, CvType.CV_32F)
            val muA = Core.mean(fa).`val`[0]
            val muB = Core.mean(fb).`val`[0]
            Core.multiply(fa, fa, t); val eAA = Core.mean(t).`val`[0]
            val varA = (eAA - muA * muA).coerceAtLeast(0.0)
            Core.multiply(fb, fb, t); val eBB = Core.mean(t).`val`[0]
            val varB = (eBB - muB * muB).coerceAtLeast(0.0)
            Core.multiply(fa, fb, t); val eAB = Core.mean(t).`val`[0]
            val cov = eAB - muA * muB
            val c1 = (0.01 * 255.0) * (0.01 * 255.0)
            val c2 = (0.03 * 255.0) * (0.03 * 255.0)
            val num = (2.0 * muA * muB + c1) * (2.0 * cov + c2)
            val den = (muA * muA + muB * muB + c1) * (varA + varB + c2)
            if (den == 0.0) 0.0 else num / den
        } finally { fa.release(); fb.release(); t.release() }
    }.getOrDefault(-1.0)

    /** Rectangularity (contourArea / minAreaRect area) of the largest content contour. */
    private fun outputRectFill(bmp: Bitmap): Pair<Double, Int> = runCatching {
        val m = Mat(); val g = Mat(); val e = Mat(); val d = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Utils.bitmapToMat(ensureArgb(bmp), m)
            Imgproc.cvtColor(m, g, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(g, g, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(g, e, 60.0, 160.0)
            Imgproc.dilate(e, d, Mat())
            Imgproc.findContours(d, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val largest = contours.maxByOrNull { abs(Imgproc.contourArea(it)) }
            if (largest == null) return Pair(0.0, 0)
            val area = abs(Imgproc.contourArea(largest))
            val c2f = MatOfPoint2f(*largest.toArray())
            val rr = Imgproc.minAreaRect(c2f)
            c2f.release()
            val rectArea = (rr.size.width * rr.size.height).coerceAtLeast(1.0)
            val fill = (area / rectArea).coerceIn(0.0, 1.0)
            val c2 = MatOfPoint2f(*largest.toArray())
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2, approx, 0.02 * Imgproc.arcLength(c2, true), true)
            val verts = approx.total().toInt()
            c2.release(); approx.release()
            fill to verts
        } finally {
            contours.forEach { runCatching { it.release() } }
            m.release(); g.release(); e.release(); d.release()
        }
    }.getOrDefault(Pair(-1.0, 0))

    private fun deviation(output: Bitmap, reference: Bitmap): Pair<Double, Double> = runCatching {
        val a = Mat(); val b = Mat(); val ga = Mat(); val gb = Mat(); val ra = Mat(); val rb = Mat(); val diff = Mat()
        try {
            Utils.bitmapToMat(ensureArgb(output), a)
            Utils.bitmapToMat(ensureArgb(reference), b)
            Imgproc.cvtColor(a, ga, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(b, gb, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(ga, ra, Size(384.0, 384.0), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.resize(gb, rb, Size(384.0, 384.0), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(ra, ra, Size(3.0, 3.0), 0.0)
            Imgproc.GaussianBlur(rb, rb, Size(3.0, 3.0), 0.0)
            Core.absdiff(ra, rb, diff)
            val mad = Core.mean(diff).`val`[0] / 255.0
            val sa = lapVarMat(ra); val sb = lapVarMat(rb)
            val ratio = if (sb > 1e-6) sa / sb else 0.0
            mad to ratio
        } finally {
            a.release(); b.release(); ga.release(); gb.release(); ra.release(); rb.release(); diff.release()
        }
    }.getOrDefault(Pair(-1.0, -1.0))

    private fun lapVarMat(gray: Mat): Double {
        val l = Mat()
        try {
            Imgproc.Laplacian(gray, l, CvType.CV_32F)
            val mean = MatOfDouble(); val std = MatOfDouble()
            Core.meanStdDev(l, mean, std)
            val s = std.toArray()[0]; return s * s
        } finally { l.release() }
    }

    // ==========================================================================================
    // Reporting
    // ==========================================================================================

    private fun mean(rs: List<Rec>, pick: (Rec) -> Double): Double {
        val v = rs.map(pick).filter { it >= 0 }
        return if (v.isEmpty()) -1.0 else v.average()
    }

    private fun summarize(
        records: List<Rec>, aiOn: Boolean, sessions: Int, native: Boolean, elapsed: Long
    ): LinkedHashMap<String, Any> {
        val s = LinkedHashMap<String, Any>()
        // detection is scored over every page-like image (synthetic + benchmark + real positives),
        // i.e. everything that SHOULD be detected; negatives are excluded.
        val pageLike = records.filter { it.kind != "negative" }
        val detected = pageLike.count { it.detectedFound }
        val notDetectedFallback = records.count { !it.detectedFound && it.warpPath != "none" && it.warpPath != "error" }
        val cornerScored = records.filter { it.hasCornerGt }
        val withinTol = cornerScored.count { it.withinTol }
        val validErr = cornerScored.filter { it.cornerErrPctDiag >= 0 }
        val meanErrPct = if (validErr.isEmpty()) -1.0 else validErr.map { it.cornerErrPctDiag }.average()
        val madFlagged = records.filter { it.devMad >= 0 }
        val meanMad = if (madFlagged.isEmpty()) -1.0 else madFlagged.map { it.devMad }.average()
        val negImages = records.filter { it.kind == "negative" }
        val negMad = negImages.filter { it.devMad >= 0 }.map { it.devMad }

        s["ai_mode"] = if (aiOn) "ON" else "OFF"
        s["images_total"] = records.size
        s["images_negative"] = negImages.size
        s["images_page_like"] = pageLike.size
        s["images_with_corner_gt"] = cornerScored.size
        s["images_with_scan_gt"] = records.count { it.hasScanGt }
        s["detected_found"] = detected
        s["detection_rate_pct"] = if (pageLike.isEmpty()) 0.0 else 100.0 * detected / pageLike.size
        s["within_tol_5pct_diag"] = withinTol
        s["mean_corner_err_pct_diag"] = meanErrPct
        s["not_detected_fallback_warp"] = notDetectedFallback
        s["warp_neural"] = records.count { it.warpPath == "neural" }
        s["warp_classical"] = records.count { it.warpPath == "classical" }
        s["warp_perspective_only"] = records.count { it.warpPath == "perspective_only" }
        s["warp_skipped_no_detection"] = records.count { it.warpPath == "skipped_no_detection" }
        s["warp_rectified_skip"] = records.count { it.warpPath == "rectified_skip" }
        s["distorted_suspect"] = records.count { it.distortedSuspect }
        s["distorted_suspect_no_detection"] =
            records.count { it.distortedSuspect && it.warpPath == "skipped_no_detection" }
        s["distorted_suspect_rectified"] =
            records.count { it.distortedSuspect && (it.category == "rectified_full" || it.quadRectScore >= 0.90) }
        s["mean_distortion_mad"] = meanMad
        s["mean_distortion_mad_negatives"] = if (negMad.isEmpty()) -1.0 else negMad.average()
        s["mean_distortion_mad_rectified"] = records.filter { it.category == "rectified_full" && it.devMad >= 0 }
            .map { it.devMad }.let { if (it.isEmpty()) -1.0 else it.average() }
        // ---- fidelity / crispness / straightness (images that have a GT flat page) ----------
        val gtFlat = records.filter { it.hasScanGt }
        s["mean_gt_psnr"] = mean(gtFlat) { it.gtPsnr }
        s["mean_gt_ssim"] = mean(gtFlat) { it.gtSsim }
        s["mean_sharp_ratio_gt"] = mean(gtFlat) { it.sharpRatioGt }
        s["mean_gt_sharpness"] = mean(gtFlat) { it.gtSharp }
        s["mean_out_sharpness"] = mean(gtFlat) { it.outSharp }
        s["mean_in_sharpness"] = mean(gtFlat) { it.inSharp }
        s["mean_gt_straightness"] = mean(gtFlat) { it.gtStraight }
        s["mean_out_straightness"] = mean(gtFlat) { it.outStraight }
        s["mean_in_straightness"] = mean(gtFlat) { it.inStraight }
        // ---- quad-placement audit tally -----------------------------------------------------
        s["quad_placement_inside"] = cornerScored.count { it.quadPlacement == "INSIDE" }
        s["quad_placement_outside"] = cornerScored.count { it.quadPlacement == "OUTSIDE" }
        s["quad_placement_mixed"] = cornerScored.count { it.quadPlacement == "MIXED" }
        s["quad_irregular"] = records.count { !it.quadOrderOk }
        s["near_full_frame"] = records.count { it.nearFullFrame }
        s["sessions_created"] = sessions
        s["native_signal"] = native
        s["elapsed_ms"] = elapsed

        // ---- per-group aggregates (benchmark real / synthetic controlled / negatives) --------
        for (g in listOf("benchmark", "synthetic", "negatives")) {
            val rs = records.filter { it.group == g }
            if (rs.isEmpty()) continue
            val pageRs = rs.filter { it.kind != "negative" }
            s["group_${g}_count"] = rs.size
            s["group_${g}_detected"] = pageRs.count { it.detectedFound }
            s["group_${g}_detection_rate_pct"] =
                if (pageRs.isEmpty()) 0.0 else 100.0 * pageRs.count { it.detectedFound } / pageRs.size
            s["group_${g}_full_frame_fallback"] = rs.count { it.fullFrameFallback }
            s["group_${g}_mean_psnr"] = mean(rs) { it.gtPsnr }
            s["group_${g}_mean_ssim"] = mean(rs) { it.gtSsim }
            s["group_${g}_mean_out_w"] = mean(rs) { it.outW.toDouble() }
            s["group_${g}_mean_out_h"] = mean(rs) { it.outH.toDouble() }
            s["group_${g}_mean_in_sharpness"] = mean(rs) { it.inSharp }
            s["group_${g}_mean_out_sharpness"] = mean(rs) { it.outSharp }
            s["group_${g}_mean_sharp_ratio_gt"] = mean(rs) { it.sharpRatioGt }
            s["group_${g}_mean_in_straightness"] = mean(rs) { it.inStraight }
            s["group_${g}_mean_out_straightness"] = mean(rs) { it.outStraight }
            s["group_${g}_mean_distortion_mad"] = mean(rs) { it.devMad }
            s["group_${g}_mean_out_rect_fill"] = mean(rs) { it.outRectFill }
            s["group_${g}_distorted_suspect"] = rs.count { it.distortedSuspect }
            s["group_${g}_near_full_frame"] = rs.count { it.nearFullFrame }
            s["group_${g}_quad_irregular"] = rs.count { !it.quadOrderOk }
            s["group_${g}_warp_neural"] = rs.count { it.warpPath == "neural" }
            s["group_${g}_warp_classical"] = rs.count { it.warpPath == "classical" }
            s["group_${g}_warp_skipped_no_detection"] = rs.count { it.warpPath == "skipped_no_detection" }
            s["group_${g}_warp_rectified_skip"] = rs.count { it.warpPath == "rectified_skip" }
        }
        // per-category detection rate + mean fidelity
        records.groupBy { it.category }.toSortedMap().forEach { (cat, rs) ->
            s["cat_${cat}_detected"] = "${rs.count { it.detectedFound }}/${rs.size}"
            s["cat_${cat}_mean_psnr"] = mean(rs) { it.gtPsnr }
            s["cat_${cat}_mean_ssim"] = mean(rs) { it.gtSsim }
            s["cat_${cat}_mean_sharp_ratio_gt"] = mean(rs) { it.sharpRatioGt }
        }
        return s
    }

    private fun writeReports(outRoot: File, mode: String, records: List<Rec>, summary: LinkedHashMap<String, Any>) {
        val csv = StringBuilder()
        csv.append(Rec.CSV_HEADER).append('\n')
        records.forEach { csv.append(it.toCsv()).append('\n') }
        File(outRoot, "corpus_report_$mode.csv").writeText(csv.toString())

        val json = StringBuilder()
        json.append("{\n")
        json.append("  \"ai_mode\": \"").append(summary["ai_mode"]).append("\",\n")
        json.append("  \"summary\": { ")
        json.append(summary.entries.joinToString(", ") { (k, v) -> "\"$k\": ${jsonVal(v)}" })
        json.append(" },\n")
        json.append("  \"images\": [\n")
        json.append(records.joinToString(",\n") { "    " + it.toJson() })
        json.append("\n  ]\n}\n")
        File(outRoot, "corpus_report_$mode.json").writeText(json.toString())

        // ---- SUBTASK H3a: benchmark-only report (the real-photo + GT-scan group) -------------
        val bench = records.filter { it.group == "benchmark" }
        val bCsv = StringBuilder()
        bCsv.append(Rec.CSV_HEADER).append('\n')
        bench.forEach { bCsv.append(it.toCsv()).append('\n') }
        File(outRoot, "benchmark_report_$mode.csv").writeText(bCsv.toString())

        val bJson = StringBuilder()
        bJson.append("{\n  \"ai_mode\": \"").append(summary["ai_mode"]).append("\",\n")
        bJson.append("  \"group\": \"benchmark\",\n")
        bJson.append("  \"summary\": { ")
        val bKeys = summary.filterKeys { it.startsWith("group_benchmark_") }
        bJson.append(bKeys.entries.joinToString(", ") { (k, v) -> "\"$k\": ${jsonVal(v)}" })
        bJson.append(" },\n  \"images\": [\n")
        bJson.append(bench.joinToString(",\n") { "    " + it.toJson() })
        bJson.append("\n  ]\n}\n")
        File(outRoot, "benchmark_report_$mode.json").writeText(bJson.toString())

        val onJ = File(outRoot.parentFile, "ai_on/corpus_report_ai_on.json")
        val offJ = File(outRoot.parentFile, "ai_off/corpus_report_ai_off.json")
        val parts = listOfNotNull(onJ.takeIf { it.exists() }?.readText(), offJ.takeIf { it.exists() }?.readText())
        val combined = "{\n  \"generated_by\": \"CorpusRealLifeTest\",\n  \"reports\": [\n" +
            parts.joinToString(",\n") { it.replace("\n", "\n  ") } + "\n  ]\n}\n"
        File(outRoot.parentFile, "corpus_report.json").writeText(combined)

        val bOnJ = File(outRoot.parentFile, "ai_on/benchmark_report_ai_on.json")
        val bOffJ = File(outRoot.parentFile, "ai_off/benchmark_report_ai_off.json")
        val bParts = listOfNotNull(bOnJ.takeIf { it.exists() }?.readText(), bOffJ.takeIf { it.exists() }?.readText())
        File(outRoot.parentFile, "benchmark_report.json").writeText(
            "{\n  \"generated_by\": \"CorpusRealLifeTest\",\n  \"group\": \"benchmark\",\n  \"reports\": [\n" +
                bParts.joinToString(",\n") { it.replace("\n", "\n  ") } + "\n  ]\n}\n"
        )
    }

    private fun jsonVal(v: Any): String = when (v) {
        is String -> "\"${v.replace("\"", "\\\"")}\""
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Double -> fmt(v)
        else -> "\"${v.toString().replace("\"", "\\\"")}\""
    }

    private fun fmt(d: Double): String = if (d < 0) "-1" else String.format(java.util.Locale.US, "%.5f", d)

    // ==========================================================================================
    // Device helpers
    // ==========================================================================================

    private fun shell(cmd: String): String {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            val text = FileInputStream(pfd.fileDescriptor).use { it.readBytes() }.toString(Charsets.UTF_8)
            text
        } catch (t: Throwable) {
            Log.w(TAG, "shell failed: $cmd :: ${t.message}")
            ""
        } finally {
            runCatching { pfd?.close() }
        }
    }

    private fun stageMs(stages: List<Pair<String, Long>>, from: String, to: String): Long {
        val a = stages.firstOrNull { it.first == from }?.second ?: return -1
        val b = stages.firstOrNull { it.first == to }?.second ?: return -1
        return (b - a).coerceAtLeast(0)
    }

    private fun capLongEdge(src: Bitmap, longEdge: Int): Bitmap {
        val maxDim = max(src.width, src.height)
        if (maxDim <= longEdge || maxDim == 0) return src
        val s = longEdge.toFloat() / maxDim.toFloat()
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    private fun ensureArgb(bmp: Bitmap): Bitmap =
        if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)

    private fun decodeFile(path: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()

    private fun saveJpeg(bmp: Bitmap, f: File, q: Int) {
        f.parentFile?.mkdirs()
        runCatching { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) } }
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        runCatching { src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } } }
    }

    /**
     * SUBTASK H3a — writes the detected-quad overlay as a PNG with an appended header band.
     *
     * Purely diagnostic: it copies [src] (never mutating the pipeline's bitmap), draws a black band
     * at the bottom carrying [header] (one line per '\n'), then writes PNG. Failures are swallowed
     * by the caller.
     */
    private fun annotateAndSavePng(src: Bitmap, out: File, header: String) {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true) ?: return
        try {
            val canvas = Canvas(bmp)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = max(15f, bmp.width / 62f)
                typeface = Typeface.MONOSPACE
            }
            val lines = header.split('\n')
            val lineH = text.textSize * 1.30f
            val bandH = lineH * lines.size + 10f
            val band = Paint().apply { color = Color.argb(205, 0, 0, 0) }
            canvas.drawRect(0f, bmp.height - bandH, bmp.width.toFloat(), bmp.height.toFloat(), band)
            lines.forEachIndexed { i, line ->
                canvas.drawText(line, 8f, bmp.height - bandH + lineH * (i + 1) - 4f, text)
            }
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    // ==========================================================================================
    // Report record
    // ==========================================================================================

    private data class Rec(
        val name: String, val category: String, val kind: String, val group: String, val aiMode: String,
        val hasGt: Boolean, val hasCornerGt: Boolean, val hasScanGt: Boolean,
        val detectedFound: Boolean, val fullFrameFallback: Boolean, val warpPath: String,
        val cornerMeanErrPx: Double, val cornerErrPctDiag: Double, val withinTol: Boolean,
        val quadConvex: Boolean, val quadSelfIntersect: Boolean, val quadAreaFrac: Double,
        val quadOrderOk: Boolean, val nearFullFrame: Boolean,
        val quadRectScore: Double, val quadPlacement: String,
        val outW: Int, val outH: Int, val outAspect: Double, val outRectFill: Double, val outAspectPlausible: Boolean,
        val gtPsnr: Double, val gtSsim: Double, val gtSharp: Double, val outSharp: Double,
        val sharpRatioGt: Double, val gtStraight: Double,
        val inSharp: Double, val inStraight: Double, val outStraight: Double,
        val inContrast: Double, val outContrast: Double,
        val devMad: Double, val devSharpRatio: Double,
        val totalMs: Long, val detectMs: Long, val warpMs: Long, val enhanceMs: Long, val storeMs: Long,
        val distortedSuspect: Boolean, val sessionLines: Int, val nativeSignal: Boolean,
        val overlayPng: String?, val note: String
    ) {
        fun toCsv(): String = listOf(
            name, group, category, kind, aiMode, hasGt.toString(), hasCornerGt.toString(), hasScanGt.toString(),
            detectedFound.toString(), fullFrameFallback.toString(), warpPath,
            b5(cornerMeanErrPx), b5(cornerErrPctDiag), withinTol.toString(),
            quadConvex.toString(), quadSelfIntersect.toString(), b5(quadAreaFrac),
            quadOrderOk.toString(), nearFullFrame.toString(), b5(quadRectScore), quadPlacement,
            outW.toString(), outH.toString(), b5(outAspect), b5(outRectFill), outAspectPlausible.toString(),
            b5(gtPsnr), b5(gtSsim), b5(gtSharp), b5(outSharp), b5(sharpRatioGt), b5(gtStraight),
            b5(inSharp), b5(inStraight), b5(outStraight), b5(inContrast), b5(outContrast),
            b5(devMad), b5(devSharpRatio),
            totalMs.toString(), detectMs.toString(), warpMs.toString(), enhanceMs.toString(), storeMs.toString(),
            distortedSuspect.toString(), sessionLines.toString(), nativeSignal.toString(),
            overlayPng ?: "", note
        ).joinToString(",")

        fun toJson(): String = buildString {
            fun f(k: String, v: String) { append("\"").append(k).append("\": ").append(v).append(", ") }
            fun q(k: String, v: String?) { append("\"").append(k).append("\": \"").append(v ?: "").append("\", ") }
            // NOTE: a record is a JSON OBJECT and must be wrapped in braces - without this the
            // `images` array held bare key/value pairs and the report was not parseable JSON.
            append("{ ")
            q("name", name); q("group", group); q("category", category); q("kind", kind); q("ai_mode", aiMode)
            f("has_gt", hasGt.toString()); f("has_corner_gt", hasCornerGt.toString()); f("has_scan_gt", hasScanGt.toString())
            f("detected_found", detectedFound.toString()); f("full_frame_fallback", fullFrameFallback.toString())
            q("warp_path", warpPath)
            f("corner_mean_err_px", b5(cornerMeanErrPx)); f("corner_err_pct_diag", b5(cornerErrPctDiag))
            f("within_tol", withinTol.toString()); f("quad_convex", quadConvex.toString())
            f("quad_self_intersect", quadSelfIntersect.toString()); f("quad_area_frac", b5(quadAreaFrac))
            f("quad_order_ok", quadOrderOk.toString()); f("near_full_frame", nearFullFrame.toString())
            f("quad_rect_score", b5(quadRectScore)); q("quad_placement", quadPlacement)
            f("out_w", outW.toString()); f("out_h", outH.toString()); f("out_aspect", b5(outAspect))
            f("out_rect_fill", b5(outRectFill)); f("out_aspect_plausible", outAspectPlausible.toString())
            f("gt_psnr", b5(gtPsnr)); f("gt_ssim", b5(gtSsim)); f("gt_sharpness", b5(gtSharp))
            f("out_sharpness", b5(outSharp)); f("sharp_ratio_gt", b5(sharpRatioGt))
            f("gt_straightness", b5(gtStraight))
            f("in_sharpness", b5(inSharp)); f("in_straightness", b5(inStraight)); f("out_straightness", b5(outStraight))
            f("in_contrast", b5(inContrast)); f("out_contrast", b5(outContrast))
            f("distortion_mad", b5(devMad)); f("distortion_grad_ratio", b5(devSharpRatio))
            f("total_ms", totalMs.toString()); f("detect_ms", detectMs.toString()); f("warp_ms", warpMs.toString())
            f("enhance_ms", enhanceMs.toString()); f("store_ms", storeMs.toString())
            f("distorted_suspect", distortedSuspect.toString()); f("session_lines", sessionLines.toString())
            f("native_signal", nativeSignal.toString()); q("overlay_png", overlayPng); q("note", note)
            setLength(length - 2)
            append(" }")
        }
        companion object {
            val CSV_HEADER =
                "image,group,category,kind,ai_mode,has_gt,has_corner_gt,has_scan_gt," +
                    "detected_found,full_frame_fallback,warp_path,corner_mean_err_px," +
                    "corner_err_pct_diag,within_tol,quad_convex,quad_self_intersect,quad_area_frac," +
                    "quad_order_ok,near_full_frame,quad_rect_score,quad_placement,out_w,out_h,out_aspect," +
                    "out_rect_fill,out_aspect_plausible,gt_psnr,gt_ssim,gt_sharpness,out_sharpness," +
                    "sharp_ratio_gt,gt_straightness,in_sharpness,in_straightness,out_straightness," +
                    "in_contrast,out_contrast,distortion_mad,distortion_grad_ratio," +
                    "total_ms,detect_ms,warp_ms,enhance_ms,store_ms," +
                    "distorted_suspect,session_lines,native_signal,overlay_png,note"
            private fun b5(d: Double): String = if (d < 0) "-1" else String.format(java.util.Locale.US, "%.5f", d)
            fun error(name: String, cat: String, kind: String, group: String, mode: String, msg: String) = Rec(
                name, cat, kind, group, mode, false, false, false, false, false, "error",
                -1.0, -1.0, false, false, false, -1.0, false, false, -1.0, "-",
                -1, -1, -1.0, -1.0, false,
                -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0,
                -1.0, -1.0, -1, -1, -1, -1, -1, false, 0, false, null, msg.take(180)
            )
        }
    }
}
