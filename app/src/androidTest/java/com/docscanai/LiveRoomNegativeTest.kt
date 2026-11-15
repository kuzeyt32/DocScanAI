package com.docscanai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.capture.CaptureStateMachine
import com.docscanai.capture.PagePlausibility
import com.docscanai.core.di.ServiceLocator
import com.docscanai.core.util.BitmapUtils
import java.util.Locale
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================================
 * SUBTASK H21 — the REAL live-room negative fixture (acceptance gate for the auto-capture fix).
 * ============================================================================================
 *
 * The frames under `assets/live_room/` are **raw captures the shipped app took by itself** on the
 * real CameraX chain: emulator `docscan_dbg_api35`, `-camera-back virtualscene`, headless, a room
 * with no document in it (bookshelf, wood panelling, TV showing the stock checkerboard poster).
 * They are not synthesised and not selected: during the H20/H21 room lane the page-ness gate
 * reported `pass=true` and the auto-capture state machine fired 16 times in ~100 s, saving these
 * very frames. That is the owner's complaint reproduced with product pixels.
 *
 * Each frame is replayed exactly as `FrameAnalyzer` sees it (downscaled to a 480 px working copy),
 * through the **real** [`com.docscanai.capture.OpenCvQuadPageDetector`] and the real
 * [PagePlausibility] gate, and then through the **real** [CaptureStateMachine] (3 feeds = a full
 * stability window).
 *
 * The acceptance rule is intentionally blunt:
 *   - `pass == false` on EVERY frame (no room frame may be considered a page), and
 *   - zero auto-capture requests across the whole fixture.
 *
 * A frame for which the detector returns nothing trivially satisfies `pass == false` (a null
 * detection is never page-like); the failure mode being guarded is a quad that *is* returned and
 * *is* accepted, which is what happened on the room.
 */
@RunWith(AndroidJUnit4::class)
class LiveRoomNegativeTest {

    companion object {
        private const val TAG = "DocScan"
        private const val EVID = "LIVE_ROOM"
        private const val ASSET_DIR = "live_room"

        /** Same working long edge `FrameAnalyzer` uses for the live analysis frame. */
        private const val WORK_EDGE = 480

        /** Minimum number of distinct captured frames the fixture must contain. */
        private const val MIN_FRAMES = 10

        private lateinit var context: android.content.Context

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.init(context)
            ServiceLocator.ensureOpenCv()
            NeuralRepro.setUp(enableXnnpack = false, tag = "LiveRoomNegativeTest/CPU-EP")
            NeuralRepro.requireModels()
            Log.i(TAG, "$EVID setup openCv=${ServiceLocator.ensureOpenCv()}")
        }
    }

    @Before
    fun setUp() {
        Log.i(TAG, "$EVID section=start")
    }

    /** Every harvested real room frame must be rejected by the gate. */
    @Test
    fun realRoomFrames_neverPassTheGate() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val names = runCatching { assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
            .filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
            .sorted()

        assertTrue(
            "live-room fixture must contain at least $MIN_FRAMES real frames (found ${names.size})",
            names.size >= MIN_FRAMES
        )

        val detector = ServiceLocator.pageDetector
        val harness = Harness()
        var passed = 0
        var detected = 0
        try {
            for (name in names) {
                val full = assets.open("$ASSET_DIR/$name").use { BitmapFactory.decodeStream(it) }
                assertNotNull("$ASSET_DIR/$name must decode", full)
                val frame = BitmapUtils.downscaleToLongEdge(full!!, WORK_EDGE)
                try {
                    // Exactly FrameAnalyzer.analyze(): detect on the downscaled frame, then gate on
                    // the same frame the detector saw (no coordinate rescale, no second copy).
                    val raw = runBlocking { runCatching { detector.detect(frame) }.getOrNull() }
                    val provenance = detector.lastProvenance
                    val gate = raw?.let {
                        PagePlausibility.evaluate(frame, it, "live_room", provenance)
                    }
                    if (raw != null) detected++
                    val pass = gate?.passed == true
                    if (pass) passed++

                    Log.i(
                        TAG,
                        "$EVID frame=$name detected=${raw != null} pass=$pass " +
                            "prov=${provenance.name} score=${fmt(gate?.score ?: 0.0)} " +
                            "text=${fmt(gate?.textScore ?: 0.0)} " +
                            "paper=${fmt(gate?.paperScore ?: 0.0)} glyphs=${gate?.glyphCount ?: 0} " +
                            "coh=${fmt(gate?.lineCoherence ?: 0.0)} " +
                            "cov=${fmt(gate?.coverageFrac ?: 0.0)} " +
                            "auto=${gate?.autoCaptureEligible == true}"
                    )

                    // A gate-passing quad would be the one the analyzer hands to the state machine.
                    // Feed it a full stability window; it must never fire.
                    val gatedQuad = if (pass) raw else null
                    repeat(3) { harness.machine.onQuadUpdate(gatedQuad, gate?.score ?: 0.0) }

                    assertFalse("live-room frame $name must NOT pass the page-ness gate", pass)
                    assertFalse(
                        "live-room frame $name must not be auto-capture eligible",
                        gate?.autoCaptureEligible == true
                    )
                } finally {
                    if (frame !== full) frame.recycle()
                    full.recycle()
                }
            }

            assertEquals("live-room frames that passed the gate (must be 0)", 0, passed)
            assertEquals(
                "auto-capture requests produced across the room fixture (must be 0)",
                0,
                harness.fires
            )
            Log.i(
                TAG,
                "$EVID RESULT frames=${names.size} detected=$detected gatePass=$passed fires=${harness.fires}"
            )
        } finally {
            harness.close()
        }
    }

    /** The whole fixture held steady, frame after frame, must still produce zero captures. */
    @Test
    fun realRoomHold_neverAutoCaptures() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val names = runCatching { assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
            .filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
            .sorted()
        assertTrue("fixture present", names.isNotEmpty())

        val detector = ServiceLocator.pageDetector
        val harness = Harness()
        var fed = 0
        try {
            // Repeat the SAME frame 3x per fixture image: a perfect hold, the worst case for a
            // false positive (this is what the room lane did, 16 times).
            for (name in names) {
                val full = assets.open("$ASSET_DIR/$name").use { BitmapFactory.decodeStream(it) } ?: continue
                val frame = BitmapUtils.downscaleToLongEdge(full, WORK_EDGE)
                try {
                    val raw = runBlocking { runCatching { detector.detect(frame) }.getOrNull() }
                    val provenance = detector.lastProvenance
                    val gate = raw?.let {
                        PagePlausibility.evaluate(frame, it, "live_hold", provenance)
                    }
                    val gated = if (gate?.passed == true) raw else null
                    repeat(3) {
                        harness.machine.onQuadUpdate(gated, gate?.score ?: 0.0)
                        fed++
                    }
                } finally {
                    if (frame !== full) frame.recycle()
                    full.recycle()
                }
            }
            assertEquals("auto-captures on a perfect room hold (must be 0)", 0, harness.fires)
            Log.i(TAG, "$EVID HOLD fed=$fed fires=${harness.fires}")
        } finally {
            harness.close()
        }
    }

    /** Mirrors the production wiring in `CameraScreen` (collector subscribed before any frame). */
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

    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)
}
