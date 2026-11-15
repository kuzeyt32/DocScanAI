package com.docscanai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docscanai.capture.CaptureIndicator
import com.docscanai.capture.CaptureState
import com.docscanai.capture.CaptureStateMachine
import com.docscanai.data.Point
import com.docscanai.data.Quad
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================================
 * SUBTASK H21 — duplicate-capture safety (independent of the page-ness fix).
 * ============================================================================================
 *
 * The live-room defect produced **18 auto-captures in ~100 s** of one static scene. Even with a
 * correct gate, a held page must not be re-photographed every cooldown. The auto path therefore
 * skips a capture when the new quad sits where the last accepted one was (IoU ≥ 0.95) AND the
 * frame content is near-identical (16×16 luminance MAD ≤ 0.02).
 *
 * The manual shutter is deliberately NOT covered by this rule: explicit user intent always wins.
 *
 * This test drives the real [CaptureStateMachine] with a zero cooldown and zero thumbnail window,
 * so the ONLY thing that can stop 30 identical frames from capturing repeatedly is the duplicate
 * guard itself.
 */
@RunWith(AndroidJUnit4::class)
class AutoCaptureDuplicateTest {

    companion object {
        private const val TAG = "DocScan"
        private const val EVID = "DUP_CAPTURE"
    }

    /** A stable document quad (normalized-ish pixel space; only geometry matters here). */
    private val quad = Quad(
        topLeft = Point(120f, 160f),
        topRight = Point(880f, 150f),
        bottomRight = Point(890f, 1180f),
        bottomLeft = Point(130f, 1190f)
    )

    private fun signature(fill: Int): ByteArray = ByteArray(16 * 16) { fill.toByte() }

    @Test
    fun thirtyIdenticalStableFrames_yieldExactlyOneCapture() {
        val harness = Harness()
        val sig = signature(100)
        try {
            var fed = 0
            repeat(30) {
                harness.machine.onQuadUpdate(quad, 0.90, sig)
                fed++
                // Finish the capture lifecycle immediately so the machine returns to DETECTING
                // and a second shot would be possible if the duplicate guard were absent.
                if (harness.machine.state.value == CaptureState.CAPTURING) {
                    harness.machine.onStillCaptured()
                    harness.machine.onProcessingComplete()
                }
            }
            Log.i(TAG, "$EVID identical fed=$fed fires=${harness.fires} state=${harness.machine.state.value}")
            assertEquals("30 identical stable frames must yield exactly 1 capture", 1, harness.fires)
        } finally {
            harness.close()
        }
    }

    @Test
    fun changedContent_isNotSuppressed() {
        val harness = Harness()
        val sig = signature(100)
        try {
            // First capture, then let the machine settle.
            repeat(3) { harness.machine.onQuadUpdate(quad, 0.90, sig) }
            assertEquals("baseline capture", 1, harness.fires)
            harness.machine.onStillCaptured()
            harness.machine.onProcessingComplete()

            // Same position, DIFFERENT pixels (the user turned a page): must capture again.
            val changed = signature(220)
            repeat(3) { harness.machine.onQuadUpdate(quad, 0.90, changed) }
            Log.i(TAG, "$EVID changed fires=${harness.fires}")
            assertEquals("a changed page at the same quad must capture again", 2, harness.fires)
        } finally {
            harness.close()
        }
    }

    // ==========================================================================================
    // H21 field feedback: "it captures too fast" / "it still fires while the pages are moving"
    // ==========================================================================================

    /**
     * The quad can be perfectly still while the CONTENT moves (a page being turned inside the
     * held frame, a book being closed, someone walking past). Quad IoU alone cannot see that, so
     * any frame whose fingerprint differs from the previous one by more than the motion threshold
     * restarts the hold. 30 such frames must never capture.
     */
    @Test
    fun movingContent_neverAutoCaptures() {
        val harness = Harness()
        try {
            var fed = 0
            repeat(30) { i ->
                val sig = signature(if (i % 2 == 0) 100 else 220) // MAD = 120/255 = 0.47
                harness.machine.onQuadUpdate(quad, 0.90, sig)
                fed++
                if (harness.machine.state.value == CaptureState.CAPTURING) {
                    harness.machine.onStillCaptured()
                    harness.machine.onProcessingComplete()
                }
            }
            Log.i(TAG, "$EVID moving fed=$fed fires=${harness.fires}")
            assertEquals("a moving scene must never auto-capture", 0, harness.fires)
        } finally {
            harness.close()
        }
    }

    /**
     * Three frames at 30 fps is ~100 ms: the shutter opened before the user could see the lock.
     * Driven with REAL frame timestamps, the machine must wait out the minimum hold time, and the
     * hold hint must be visible before the shutter.
     */
    @Test
    fun minimumHoldTime_isEnforced_withRealTimestamps() {
        val harness = Harness()
        val sig = signature(100)
        try {
            val t0 = 1_000L
            listOf(0L, 50L, 100L).forEach { dt ->
                harness.machine.onQuadUpdate(quad, 0.90, sig, t0 + dt)
            }
            Log.i(
                TAG,
                "$EVID dwell100ms fires=${harness.fires} indicator=${harness.machine.indicator.value}"
            )
            assertEquals("100 ms of hold is too short to capture", 0, harness.fires)
            assertTrue(
                "the user must see the hold hint before the shutter",
                harness.machine.indicator.value != CaptureIndicator.NONE
            )

            // Same still hold, now past the minimum hold time: exactly one capture.
            harness.machine.onQuadUpdate(quad, 0.90, sig, t0 + 400L)
            Log.i(TAG, "$EVID dwell400ms fires=${harness.fires}")
            assertEquals("400 ms of hold must capture exactly once", 1, harness.fires)
        } finally {
            harness.close()
        }
    }

    /**
     * Field feedback: "it sometimes shoots the same thing twice … a debounce would be pleasing".
     * A genuinely DIFFERENT page arriving immediately after a shot must also be held back by the
     * post-capture cooldown (the duplicate guard cannot help here: the content really did change).
     * The block must be time-bounded, not permanent.
     */
    @Test
    fun postCaptureDebounce_blocksAnImmediateSecondPage_thenReleasesIt() {
        val harness = Harness(cooldownMs = 1_500L)
        try {
            repeat(3) { harness.machine.onQuadUpdate(quad, 0.90, signature(60)) }
            assertEquals("first page captured", 1, harness.fires)
            harness.machine.onStillCaptured()
            harness.machine.onProcessingComplete()

            // Different pixels at the same quad: NOT a duplicate, only the debounce may stop it.
            repeat(3) { harness.machine.onQuadUpdate(quad, 0.90, signature(240)) }
            Log.i(TAG, "$EVID debounce fires=${harness.fires}")
            assertEquals("the debounce must block an immediate second capture", 1, harness.fires)

            // Once the window has passed the next page must capture normally.
            Thread.sleep(1_700L)
            repeat(3) { harness.machine.onQuadUpdate(quad, 0.90, signature(240)) }
            Log.i(TAG, "$EVID debounceReleased fires=${harness.fires}")
            assertEquals("after the debounce the next page must capture", 2, harness.fires)
        } finally {
            harness.close()
        }
    }

    @Test
    fun manualShutter_isNeverSuppressedByTheDuplicateGuard() {
        val harness = Harness()
        val sig = signature(100)
        try {
            repeat(3) { harness.machine.onQuadUpdate(quad, 0.90, sig) }
            assertEquals("first auto capture", 1, harness.fires)
            harness.machine.onStillCaptured()
            harness.machine.onProcessingComplete()

            // Identical scene: auto is suppressed, but an explicit shutter press must still fire.
            repeat(3) { harness.machine.onQuadUpdate(quad, 0.90, sig) }
            assertEquals("auto must stay suppressed on the identical scene", 1, harness.fires)

            harness.machine.requestManualCapture()
            Log.i(TAG, "$EVID manual fires=${harness.fires}")
            assertEquals("manual shutter must fire despite the duplicate guard", 2, harness.fires)
        } finally {
            harness.close()
        }
    }

    /** Default zero cooldown + zero thumbnail: only the duplicate guard suppresses a re-capture. */
    private class Harness(cooldownMs: Long = 0L, thumbnailMs: Long = 0L) {
        private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        private val fireCount = AtomicInteger(0)
        val machine = CaptureStateMachine(
            scope = scope,
            autoCaptureCooldownMs = cooldownMs,
            thumbnailDurationMs = thumbnailMs
        )

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                machine.captureRequests.collect { fireCount.incrementAndGet() }
            }
            machine.onCameraBound()
            assertTrue("machine must leave IDLE", machine.state.value != CaptureState.IDLE)
        }

        val fires: Int get() = fireCount.get()

        fun close() {
            machine.onCameraReleased()
            scope.cancel()
        }
    }
}
