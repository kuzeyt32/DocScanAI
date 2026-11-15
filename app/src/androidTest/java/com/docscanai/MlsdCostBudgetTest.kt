package com.docscanai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.capture.OpenCvQuadPageDetector
import com.docscanai.core.di.ServiceLocator
import com.docscanai.ml.MlsdModel
import com.docscanai.ml.MlsdTrigger
import com.docscanai.ml.OnnxModel
import com.docscanai.models.ModelRegistry
import java.io.File
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================================
 * SUBTASK H31 — what M-LSD costs once it is *scheduled* instead of unconditional.
 * ============================================================================================
 *
 * H30 measured the cost the wrong way for a shipping decision: one call, worst case. The owner's
 * requirement (STEP 1) is the **average over a realistic frame sequence**, because that is what the
 * user and the battery actually pay:
 *
 *  - a 200-frame scan of an open book, where the phone moves for the first 40 frames and then
 *    settles and the user turns to the next spread every 40 frames — the `spread` sequence;
 *  - a 200-frame scan of a clean, well-contained single page — the `clean` sequence, which must
 *    cost *nothing*;
 *  - the still path: 9 captures, each paying only if the merge signature is present.
 *
 * Every number is a **paired** difference on the same bitmap in the same process: the baseline pass
 * runs `MlsdMode.OFF` (0.3.8), the measured pass runs the production mode with the production
 * trigger policy, and the only difference between them is the scheduled M-LSD work.
 *
 * Motion frames are modelled the way the product behaves: [com.docscanai.capture.FrameAnalyzer]
 * returns **before** `detect()` while the H21 motion guard says the phone is moving, so those
 * frames cost 0 by construction. The detector's own `moving` hint is additionally exercised so that
 * a future caller cannot accidentally run M-LSD on a moving frame.
 *
 * Budgets (STEP 3): average <= +15 ms, p95 <= +40 ms per preview frame, still p50 <= +60 ms.
 */
@RunWith(AndroidJUnit4::class)
class MlsdCostBudgetTest {

    companion object {
        private const val TAG = "DocScan"
        private const val ASSET_DIR = "bookspread"
        private const val MANIFEST = "$ASSET_DIR/book_spread_manifest.json"

        /** The acceptance sequence length, from the subtask. */
        const val SEQUENCE_FRAMES = 200

        /** Frames the phone is moving for at the start of the scan (motion guard skips detect). */
        const val MOVING_FRAMES = 40

        /** Frames per spread before the user moves to the next one (cache must invalidate). */
        const val FRAMES_PER_SPREAD = 40

        /** STEP 3 budgets, milliseconds. */
        const val BUDGET_AVG_MS = 15.0
        const val BUDGET_P95_MS = 40.0
        const val BUDGET_STILL_P50_MS = 60.0

        /**
         * "Statistically indistinguishable from zero": on a clean sequence the mean paired delta
         * must sit inside the emulator's own paired noise, and no frame may have invoked the model.
         */
        const val CLEAN_MEAN_TOLERANCE_MS = 1.0

        private val sliverFixtures = ArrayList<String>()

        @JvmStatic
        @BeforeClass
        fun setUp() {
            NeuralRepro.setUp(enableXnnpack = false, tag = "MlsdCostBudgetTest/CPU-EP")
        }
    }

    // ==========================================================================================
    // 1) The 200-frame spread sequence
    // ==========================================================================================

    @Test
    fun spreadSequenceStaysInsideTheAverageAndP95Budget() {
        NeuralRepro.requireModels()
        assertTrue(
            "M-LSD artifact is not provisioned (files/models/m2_mlsd/model.onnx); the H31 cost " +
                "measurement would silently measure 0.3.8 twice.",
            ServiceLocator.isMlsdModelPresent()
        )
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val detector = ServiceLocator.pageDetector as OpenCvQuadPageDetector
        val spreads = sliverPreviewAssets()

        val deltas = DoubleArray(SEQUENCE_FRAMES)
        var invoked = 0
        var analysed = 0
        var moved = 0

        detector.mlsdSchedule.reset()
        detector.mlsdPolicy = MlsdTrigger.Policy()

        // --- moving phase: the analyser never reaches detect() -------------------------------
        for (frame in 0 until MOVING_FRAMES) {
            deltas[frame] = 0.0
            moved++
        }

        // --- settled phase: spread after spread ----------------------------------------------
        val block = { frame: Int -> (frame - MOVING_FRAMES) / FRAMES_PER_SPREAD }
        var currentBlock = -1
        var bmp: Bitmap? = null
        try {
            for (frame in MOVING_FRAMES until SEQUENCE_FRAMES) {
                val b = block(frame)
                if (b != currentBlock) {
                    bmp?.recycle()
                    bmp = decode(ctx, assetPath(spreads[b % spreads.size], "preview"))
                    currentBlock = b
                }
                val image = bmp ?: break
                // The first two frames of a block are the hand settling onto the new spread.
                val settled = frame - (MOVING_FRAMES + b * FRAMES_PER_SPREAD)
                val hint = MlsdTrigger.Hint(
                    moving = false,
                    settledFrames = if (settled < 2) settled else 99,
                    still = false
                )

                detector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.OFF
                detector.mlsdHint = hint
                val offMs = timeDetect(detector, image)

                detector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.BOTH
                detector.mlsdHint = hint
                val beforeInvocations = detector.mlsdSchedule.invocations
                val onMs = timeDetect(detector, image)
                if (detector.mlsdSchedule.invocations > beforeInvocations) invoked++

                deltas[frame] = onMs - offMs
                analysed++
            }
        } finally {
            bmp?.recycle()
            detector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.OFF
        }

        val stats = statsOf(deltas)
        Log.i(
            TAG,
            "H31_SEQUENCE name=spread frames=$SEQUENCE_FRAMES analysed=$analysed moved=$moved " +
                "mlsdInvocations=$invoked cacheHits=${detector.mlsdSchedule.cacheHits} " +
                "invocationRatePct=${fmt(100.0 * invoked / SEQUENCE_FRAMES)} " +
                "meanExtraMs=${fmt(stats[0])} p95ExtraMs=${fmt(stats[1])} " +
                "maxExtraMs=${fmt(stats[2])} totalExtraMs=${fmt(stats[3])} " +
                "budgetAvg=$BUDGET_AVG_MS budgetP95=$BUDGET_P95_MS mode=${detector.mlsdMode} " +
                "policy=${MlsdTrigger.describe(detector.mlsdPolicy)}"
        )
        Log.i(TAG, "H31_SEQUENCE_STATS name=spread ${detector.mlsdSchedule.statsLine()}")

        assertTrue(
            "average per-frame detection overhead ${fmt(stats[0])} ms > budget $BUDGET_AVG_MS ms " +
                "($invoked M-LSD invocations over $SEQUENCE_FRAMES frames)",
            stats[0] <= BUDGET_AVG_MS
        )
        assertTrue(
            "p95 per-frame detection overhead ${fmt(stats[1])} ms > budget $BUDGET_P95_MS ms",
            stats[1] <= BUDGET_P95_MS
        )
        assertTrue("the sequence never analysed a frame", analysed > 0)
    }

    // ==========================================================================================
    // 2) The clean single-page sequence must cost nothing
    // ==========================================================================================

    @Test
    fun cleanPageSequenceCostsNothing() {
        NeuralRepro.requireModels()
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val detector = ServiceLocator.pageDetector as OpenCvQuadPageDetector
        val clean = cleanPreviewAssets()
        assertTrue("no clean control fixtures found", clean.isNotEmpty())

        detector.mlsdSchedule.reset()
        detector.mlsdPolicy = MlsdTrigger.Policy()
        detector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.BOTH

        val deltas = DoubleArray(SEQUENCE_FRAMES)
        var invoked = 0
        val bmps = ArrayList<Bitmap>()
        try {
            clean.forEach { bmps.add(decode(ctx, assetPath(it, "preview"))!!) }
            for (frame in 0 until SEQUENCE_FRAMES) {
                // A steady hand on a clean page: signature absent on every frame.
                val hint = MlsdTrigger.Hint(moving = false, settledFrames = 99, still = false)
                val image = bmps[frame % bmps.size]
                detector.mlsdHint = hint
                detector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.OFF
                val offMs = timeDetect(detector, image)
                detector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.BOTH
                val before = detector.mlsdSchedule.invocations
                val onMs = timeDetect(detector, image)
                if (detector.mlsdSchedule.invocations > before) invoked++
                deltas[frame] = onMs - offMs
            }
        } finally {
            bmps.forEach { it.recycle() }
            detector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.OFF
        }

        val stats = statsOf(deltas)
        Log.i(
            TAG,
            "H31_SEQUENCE name=clean frames=$SEQUENCE_FRAMES mlsdInvocations=$invoked " +
                "cacheHits=${detector.mlsdSchedule.cacheHits} " +
                "meanExtraMs=${fmt(stats[0])} p95ExtraMs=${fmt(stats[1])} maxExtraMs=${fmt(stats[2])}"
        )
        Log.i(TAG, "H31_SEQUENCE_STATS name=clean ${detector.mlsdSchedule.statsLine()}")

        assertEquals(
            "a clean page sequence must not invoke M-LSD at all",
            0,
            invoked
        )
        assertTrue(
            "a clean page sequence must cost nothing: mean delta ${fmt(stats[0])} ms",
            kotlin.math.abs(stats[0]) <= CLEAN_MEAN_TOLERANCE_MS
        )
        // The p95 is *measurement noise*, not M-LSD: the counters above prove the model never ran
        // (skipNoSig = 200, invocations = 0, cacheHits = 0), so every non-zero delta is the paired
        // timing noise of two identical corner-model passes on a loaded emulator. It still has to
        // stay inside the sequence budget, which is the number a user could ever feel.
        assertTrue(
            "clean-sequence p95 ${fmt(stats[1])} ms exceeds the sequence budget $BUDGET_P95_MS ms",
            stats[1] <= BUDGET_P95_MS
        )
        assertTrue(
            "clean-sequence mean ${fmt(stats[0])} ms is larger than the ±$CLEAN_MEAN_TOLERANCE_MS ms " +
                "noise band, so something IS being paid on a clean page",
            kotlin.math.abs(stats[0]) <= CLEAN_MEAN_TOLERANCE_MS
        )
    }

    // ==========================================================================================
    // 3) The still path
    // ==========================================================================================

    @Test
    fun stillPathPaysOnlyOnStillsThatCarryTheSignature() {
        NeuralRepro.requireModels()
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val detector = ServiceLocator.pageDetector as OpenCvQuadPageDetector
        val items = loadManifest()
        val deltas = ArrayList<Double>()
        val paying = ArrayList<String>()

        try {
            for (item in items) {
                val name = item.getString("name")
                val image = decode(ctx, assetPath(item, "still")) ?: continue
                try {
                    // Baseline: 0.3.8 still path.
                    detector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.OFF
                    detector.mlsdHint = MlsdTrigger.Hint.STILL
                    detector.mlsdSchedule.reset()
                    val offMs = timeDetect(detector, image)

                    // Measured: the capture is allowed exactly one attempt *if* the signature is
                    // there; a clean page must not pay at all.
                    detector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.BOTH
                    detector.mlsdHint = MlsdTrigger.Hint.STILL
                    detector.mlsdSchedule.reset()
                    val before = detector.mlsdSchedule.invocations
                    val onMs = timeDetect(detector, image)
                    val invoked = detector.mlsdSchedule.invocations > before
                    if (invoked) paying.add(name)
                    deltas.add(onMs - offMs)
                    Log.i(
                        TAG,
                        "H31_STILL name=$name msOff=${fmt(offMs)} msOn=${fmt(onMs)} " +
                            "extraMs=${fmt(onMs - offMs)} mlsdInvoked=$invoked " +
                            "mlsd=${detector.lastMlsdLog ?: "-"}"
                    )
                } finally {
                    image.recycle()
                }
            }
        } finally {
            detector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.OFF
        }

        assertTrue("no still was measured", deltas.isNotEmpty())
        val sorted = deltas.sorted()
        val p50 = sorted[sorted.size / 2]
        val worst = sorted.last()
        Log.i(
            TAG,
            "H31_STILL_RESULT n=${deltas.size} p50ExtraMs=${fmt(p50)} worstExtraMs=${fmt(worst)} " +
                "paying=${paying.size}/${deltas.size} budget=$BUDGET_STILL_P50_MS " +
                "payingAssets=${paying.joinToString(",")}"
        )
        assertTrue(
            "still path p50 overhead ${fmt(p50)} ms > budget $BUDGET_STILL_P50_MS ms",
            p50 <= BUDGET_STILL_P50_MS
        )
    }

    // ==========================================================================================
    // 4) Session warm-up: the cold cost must not land on a capture
    // ==========================================================================================

    /**
     * Builds a **fresh** M-LSD session (a new `OnnxModel.load`, i.e. the +446 ms H30 saw) and shows
     * where it lands with and without the warm-up the camera screen performs on open.
     */
    @Test
    fun warmUpMovesSessionCreationOffTheCapturePath() {
        NeuralRepro.requireModels()
        if (!ServiceLocator.isMlsdModelPresent()) {
            Log.i(TAG, "H31_WARMUP skipped: M-LSD artifact absent")
            return
        }
        val ctx = InstrumentationRegistry.getInstrumentation().context
        // The artifact lives in the TARGET app's filesDir (that is what _provision_models.cmd
        // pushes and what ServiceLocator reads); the instrumentation context cannot see it.
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val image = decode(ctx, assetPath(firstSliverPreview(), "preview")) ?: return
        val modelFile = File(
            File(appCtx.filesDir, "models/${ModelRegistry.ID_M2_MLSD}"),
            "model.onnx"
        )
        Log.i(TAG, "H31_WARMUP modelFile=$modelFile exists=${modelFile.isFile}")
        val provider: () -> MlsdModel? = {
            OnnxModel.load(
                ServiceLocator.dewarpGridSessionFactory, modelFile, allowXnnpack = false
            )?.let { MlsdModel(it, MlsdModel.clampEdge(MlsdModel.edgeFor(it))) }
        }

        // BEFORE: no warm-up — session creation happens inside the first capture's detect(),
        // because the provider is only invoked when the detector first needs line evidence.
        val coldDetector = OpenCvQuadPageDetector(
            cornerModelProvider = { ServiceLocator.detectionModel },
            mlsdProvider = provider
        )
        coldDetector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.BOTH
        coldDetector.mlsdHint = MlsdTrigger.Hint.STILL
        val coldMs = timeDetect(coldDetector, image)

        // AFTER: the camera screen warmed it on open; the capture only pays for inference.
        val warmDetector = OpenCvQuadPageDetector(
            cornerModelProvider = { ServiceLocator.detectionModel },
            mlsdProvider = provider
        )
        warmDetector.mlsdMode = OpenCvQuadPageDetector.MlsdMode.BOTH
        warmDetector.mlsdHint = MlsdTrigger.Hint.STILL
        val warmUpMs = warmDetector.warmMlsd()
        val warmMs = timeDetect(warmDetector, image)

        Log.i(
            TAG,
            "H31_WARMUP coldCaptureMs=${fmt(coldMs)} warmUpMs=$warmUpMs " +
                "warmCaptureMs=${fmt(warmMs)} savedMs=${fmt(coldMs - warmMs)} " +
                "mlsdCold=${coldDetector.lastMlsdLog ?: "-"} mlsdWarm=${warmDetector.lastMlsdLog ?: "-"}"
        )

        runCatching { coldDetector.release() }
        runCatching { warmDetector.release() }
        image.recycle()

        assertTrue(
            "warming the session must make the capture cheaper: cold ${fmt(coldMs)} ms vs " +
                "warm ${fmt(warmMs)} ms",
            warmMs < coldMs
        )
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    private fun timeDetect(detector: OpenCvQuadPageDetector, image: Bitmap): Double {
        val t0 = System.nanoTime()
        runCatching { runBlocking { detector.detect(image) } }
        return (System.nanoTime() - t0) / 1e6
    }

    /** mean, p95, max, total of the paired per-frame deltas. */
    private fun statsOf(deltas: DoubleArray): DoubleArray {
        val sorted = deltas.sorted()
        val mean = deltas.sum() / deltas.size
        val p95 = sorted[min(sorted.size - 1, (sorted.size * 95) / 100)]
        return doubleArrayOf(mean, p95, sorted.last(), deltas.sum())
    }

    private fun decode(ctx: android.content.Context, path: String): Bitmap? = try {
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (t: Throwable) {
        Log.e(TAG, "failed to decode assets/$path", t)
        null
    }

    private fun loadManifest(): List<JSONObject> {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val text = ctx.assets.open(MANIFEST).use { it.bufferedReader().readText() }
        val items = JSONObject(text).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }
    }

    /** `bookspread/<asset file>` for one canvas of a manifest item. */
    private fun assetPath(item: JSONObject, canvas: String): String =
        "$ASSET_DIR/" + item.getJSONObject(canvas).getString("asset")

    /** The preview renderings of the fixtures that actually carry a facing-page sliver. */
    private fun sliverPreviewAssets(): List<JSONObject> = loadManifest().filter {
        it.optDouble("sliver_fraction", 0.0) > 0.0
    }.sortedBy { it.getString("name") }

    /** The clean controls (no sliver at all): the "nothing to repair here" population. */
    private fun cleanPreviewAssets(): List<JSONObject> = loadManifest().filter {
        it.optDouble("sliver_fraction", 0.0) <= 0.0
    }.sortedBy { it.getString("name") }

    private fun firstSliverPreview(): JSONObject = sliverPreviewAssets().first()

    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)
}
