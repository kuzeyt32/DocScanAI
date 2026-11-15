package com.docscanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.capture.AutoCaptureStability
import com.docscanai.capture.CaptureStateMachine
import com.docscanai.capture.PageDetector
import com.docscanai.capture.PagePlausibility
import com.docscanai.core.di.ServiceLocator
import com.docscanai.data.Point
import com.docscanai.data.Quad
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point as CvPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * SUBTASK H13 — the executable proof for capture gating.
 *
 * The lane feeds the SAME 25-image set the corpus uses through the real detector plus the new
 * [PagePlausibility] gate and the real [CaptureStateMachine]:
 *
 *  1. **5 negatives** (the corpus negative set: 2 texture renders, 2 clutter renders, and the real
 *     scene photo `a_woman_fills_out_a_bear_information_management`) — ZERO may pass the gate and
 *     ZERO auto-captures may be produced. Each logs `GATE_NEG id=<id> score=<n> pass=false`.
 *  2. **20 DocUNet benchmark positives** — at least 18 must pass the gate, and each passing one
 *     must auto-capture within 3 stable frames. Each logs `GATE_POS id=<id> score=<n> pass=<b>`.
 *  3. **30-frame desk-clutter sequence** (the clutter negative with small translation jitter) —
 *     the auto-capture trigger must never fire.
 *  4. The stability rule is unit-tested directly: 3 consistent quads fire; 2 pass + 1 fail does
 *     not; a quad jump with IoU 0.5 does not.
 *
 * The synthetic negatives are generated with the same constants/seed as
 * [`CorpusRealLifeTest.buildCorpus`] (`SEED`, 1000×1300 canvas, same background and clutter
 * drawing), because the corpus negatives are rendered in-process rather than shipped as assets.
 *
 * **Evidence scope: x86_64 emulator (`docscan_dbg_api35`) only.**
 */
@RunWith(AndroidJUnit4::class)
class AutoCaptureGatingTest {

    companion object {
        private const val TAG = "DocScan"

        /** Same seed as the corpus lane so the synthetic negatives match. */
        private const val SEED = 0x5D0C5A1L

        private const val CANVAS_W = 1000
        private const val CANVAS_H = 1300

        /** Corpus negative names (`buildCorpus` uses the same ids). */
        private val SYNTHETIC_NEGATIVES = listOf(
            "syn_neg_texture_0",
            "syn_neg_texture_1",
            "syn_neg_clutter_0",
            "syn_neg_clutter_1"
        )

        private const val REAL_NEGATIVE = "real_a_woman_fills_out_a_bear_information_management"

        private lateinit var context: Context

        @JvmStatic
        @BeforeClass
        fun setUp() {
            context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.init(context)
            ServiceLocator.ensureOpenCv()
            NeuralRepro.setUp(enableXnnpack = false, tag = "AutoCaptureGatingTest")
            NeuralRepro.requireModels()
            Log.i(TAG, "GATE_TEST_SETUP openCv=${ServiceLocator.ensureOpenCv()}")
        }
    }

    // ==========================================================================================
    // 1) Negatives: zero gate passes, zero auto-captures
    // ==========================================================================================

    @Test
    fun negatives_neverPassGate_andNeverAutoCapture() {
        val detector = ServiceLocator.pageDetector
        val harness = Harness()
        var passed = 0
        try {
            val negatives = buildNegativeBitmaps()
            assertEquals("expected the 5-image corpus negative set", 5, negatives.size)
            for ((id, bmp) in negatives) {
                try {
                    val outcome = evaluate(detector, bmp, id)
                    if (outcome.passed) passed++
                    assertFalse("GATE_NEG violated: id=$id score=${outcome.score}", outcome.passed)
                    // Auto-capture simulation: the gate-passing quad (or null) is fed 3 times.
                    repeat(3) { harness.machine.onQuadUpdate(outcome.quad, outcome.score) }
                    Log.i(
                        TAG,
                        "GATE_NEG id=$id score=${fmt(outcome.score)} pass=${outcome.passed} " +
                            "text=${fmt(outcome.text)} paper=${fmt(outcome.paper)} " +
                            "warpAllowed=${outcome.quad != null}"
                    )
                } finally {
                    bmp.recycle()
                }
            }
            assertEquals("negatives that passed the gate (must be 0)", 0, passed)
            assertEquals("auto-captures produced from negatives (must be 0)", 0, harness.fires)
        } finally {
            harness.close()
        }
    }

    // ==========================================================================================
    // 2) Benchmark positives: >= 18/20 pass, each fires within 3 stable frames
    // ==========================================================================================

    @Test
    fun benchmarkPositives_passGate_andAutoCaptureWithinThreeFrames() {
        val detector = ServiceLocator.pageDetector
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val files = runCatching { assets.list("corpus/benchmark/inputs")?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
            .filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
            .sorted()
        assertEquals("benchmark positives present", 20, files.size)

        var passCount = 0
        var firedCount = 0
        for (file in files) {
            val id = "bench_" + file.substringBeforeLast('.')
            val bmp = assets.open("corpus/benchmark/inputs/$file").use { BitmapFactory.decodeStream(it) }
            if (bmp == null) {
                Log.w(TAG, "GATE_POS id=$id score=0.000 pass=false (decode failed)")
                continue
            }
            try {
                val outcome = evaluate(detector, bmp, id)
                if (outcome.passed) passCount++
                var fired = false
                if (outcome.quad != null) {
                    val harness = Harness()
                    try {
                        repeat(3) { harness.machine.onQuadUpdate(outcome.quad, outcome.score) }
                        fired = harness.fires > 0
                        if (fired) firedCount++
                    } finally {
                        harness.close()
                    }
                }
                Log.i(
                    TAG,
                    "GATE_POS id=$id score=${fmt(outcome.score)} pass=${outcome.passed} " +
                        "text=${fmt(outcome.text)} paper=${fmt(outcome.paper)} " +
                        "autoCaptureWithin3=${fired}"
                )
            } finally {
                bmp.recycle()
            }
        }
        Log.i(TAG, "GATE_POS_SUMMARY passed=$passCount/20 autoCaptured=$firedCount")
        assertTrue("gate must pass at least 18/20 benchmark positives (got $passCount)", passCount >= 18)
        assertEquals("every gate-passing benchmark image must auto-capture within 3 frames", passCount, firedCount)
    }

    // ==========================================================================================
    // 3) 30-frame clutter sequence must never trigger
    // ==========================================================================================

    @Test
    fun clutterSequence_30Frames_neverTriggers() {
        val detector = ServiceLocator.pageDetector
        val base = renderSyntheticNegative("syn_neg_clutter_0")
        val harness = Harness()
        try {
            val rng = Random(0xC1A77E2L)
            repeat(30) { frame ->
                val jittered = jitter(base, rng.nextInt(21) - 10, rng.nextInt(21) - 10)
                try {
                    val outcome = evaluate(detector, jittered, "clutter_seq_$frame")
                    harness.machine.onQuadUpdate(outcome.quad, outcome.score)
                    assertFalse(
                        "auto-capture fired on clutter frame $frame (score=${outcome.score})",
                        harness.fires > 0
                    )
                } finally {
                    jittered.recycle()
                }
            }
            assertEquals("clutter must never auto-capture across 30 frames", 0, harness.fires)
        } finally {
            harness.close()
            base.recycle()
        }
    }

    // ==========================================================================================
    // 4) Stability logic, directly
    // ==========================================================================================

    private fun quad(left: Float, top: Float, right: Float, bottom: Float) = Quad(
        topLeft = Point(left, top),
        topRight = Point(right, top),
        bottomRight = Point(right, bottom),
        bottomLeft = Point(left, bottom)
    )

    @Test
    fun stability_threeConsistentPassingQuads_fires() {
        val q = quad(100f, 100f, 400f, 600f)
        val stability = AutoCaptureStability(requiredFrames = 3, minIou = 0.85)
        assertFalse(stability.update(q).fire)
        assertFalse(stability.update(q).fire)
        val third = stability.update(q)
        assertTrue("3 consistent quads must fire", third.fire)
        assertEquals(3, third.stableCount)
        assertEquals(1.0, third.iou, 1e-9)
    }

    @Test
    fun stability_twoPassOneFail_doesNotFire() {
        val q = quad(100f, 100f, 400f, 600f)
        val stability = AutoCaptureStability(requiredFrames = 3, minIou = 0.85)
        assertFalse(stability.update(q).fire)
        assertFalse(stability.update(q).fire)
        val failed = stability.update(null)
        assertEquals(0, failed.stableCount)
        assertFalse(failed.fire)
        assertEquals(1, stability.update(q).stableCount)
    }

    @Test
    fun stability_lowIou_doesNotFire() {
        val a = quad(100f, 100f, 400f, 600f)
        val b = quad(200f, 100f, 500f, 600f) // 300×500 shifted 100 px ⇒ IoU 0.5
        assertEquals(0.5, AutoCaptureStability.iouOf(a, b), 0.02)
        val stability = AutoCaptureStability(requiredFrames = 3, minIou = 0.85)
        stability.update(a)
        stability.update(a)
        val jumped = stability.update(b)
        assertFalse("IoU 0.5 must not fire", jumped.fire)
        assertEquals(1, jumped.stableCount)
    }

    // ==========================================================================================
    // Harness helpers
    // ==========================================================================================

    private class Outcome(
        val quad: Quad?,
        val score: Double,
        val passed: Boolean,
        val paper: Double,
        val text: Double
    )

    private fun evaluate(detector: PageDetector, bitmap: Bitmap, id: String): Outcome {
        val raw = runBlocking { runCatching { detector.detect(bitmap) }.getOrNull() }
        val gate = raw?.let { PagePlausibility.evaluate(bitmap, it, id) }
        // EXACTLY the production rule: a failed gate is treated as no detection.
        val gatedQuad = if (gate?.passed == true) raw else null
        return Outcome(
            quad = gatedQuad,
            score = gate?.score ?: 0.0,
            passed = gate?.passed == true,
            paper = gate?.paperScore ?: 0.0,
            text = gate?.textScore ?: 0.0
        )
    }

    /** Mirrors [CaptureStateMachine]'s production wiring (collector subscribed up-front). */
    private class Harness {
        private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        private val fireCount = AtomicInteger(0)
        val machine = CaptureStateMachine(scope)

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                machine.captureRequests.collect { fireCount.incrementAndGet() }
            }
            machine.onCameraBound()
        }

        val fires: Int get() = fireCount.get()

        fun close() {
            machine.onCameraReleased()
            scope.cancel()
        }
    }

    // --- synthetic negatives (same rendering as CorpusRealLifeTest) --------------------------

    private fun buildNegativeBitmaps(): List<Pair<String, Bitmap>> {
        val out = ArrayList<Pair<String, Bitmap>>()
        for (id in SYNTHETIC_NEGATIVES) out.add(id to renderSyntheticNegative(id))
        val real = loadRealNegative()
        if (real != null) out.add(REAL_NEGATIVE to real)
        return out
    }

    private fun loadRealNegative(): Bitmap? {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return runCatching {
            assets.open("corpus/real/${REAL_NEGATIVE.removePrefix("real_")}.jpg")
                .use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    private fun renderSyntheticNegative(name: String): Bitmap {
        val r = Random(SEED + name.hashCode())
        val canvas = Mat(CANVAS_H, CANVAS_W, CvType.CV_8UC4)
        try {
            drawBackground(canvas, r)
            if (name.startsWith("syn_neg_clutter")) drawClutter(canvas, r)
            val out = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(canvas, out)
            return out
        } finally {
            canvas.release()
        }
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
            Imgproc.line(
                canvas,
                CvPoint(0.0, y.toDouble()), CvPoint(CANVAS_W.toDouble(), y.toDouble()),
                Scalar(180.0, 184.0, 188.0, 255.0), 1
            )
        }
    }

    private fun drawClutter(canvas: Mat, r: Random) {
        repeat(9) {
            val x1 = r.nextInt(CANVAS_W); val y1 = r.nextInt(CANVAS_H)
            val x2 = (x1 + 80 + r.nextInt(300)).coerceAtMost(CANVAS_W)
            val y2 = (y1 + 60 + r.nextInt(260)).coerceAtMost(CANVAS_H)
            val c = Scalar(
                (80 + r.nextInt(140)).toDouble(),
                (80 + r.nextInt(140)).toDouble(),
                (80 + r.nextInt(140)).toDouble(), 255.0
            )
            Imgproc.rectangle(
                canvas, CvPoint(x1.toDouble(), y1.toDouble()),
                CvPoint(x2.toDouble(), y2.toDouble()), c, -1
            )
        }
        repeat(14) {
            Imgproc.line(
                canvas,
                CvPoint(r.nextInt(CANVAS_W).toDouble(), r.nextInt(CANVAS_H).toDouble()),
                CvPoint(r.nextInt(CANVAS_W).toDouble(), r.nextInt(CANVAS_H).toDouble()),
                Scalar(40.0, 40.0, 40.0, 255.0), 2
            )
        }
    }

    /** Small translation jitter of a bitmap (content-preserving, like a shaky handheld frame). */
    private fun jitter(src: Bitmap, dx: Int, dy: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(src, dx.toFloat(), dy.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
}
