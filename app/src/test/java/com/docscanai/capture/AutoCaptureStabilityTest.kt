package com.docscanai.capture

import com.docscanai.data.Point
import com.docscanai.data.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SUBTASK H13 — direct unit proof of the auto-capture stability rule.
 *
 * Auto-capture must require [AutoCaptureStability.requiredFrames] consecutive gate-passing frames
 * whose quads agree by IoU ≥ [AutoCaptureStability.minIou]. This class is the executable spec:
 *
 *  - 3 consistent passing quads  => fire on the third
 *  - 2 pass + 1 fail             => NO fire (and the counter restarts)
 *  - a quad jump (IoU 0.5)       => NO fire
 */
class AutoCaptureStabilityTest {

    private fun quad(left: Float, top: Float, right: Float, bottom: Float) = Quad(
        topLeft = Point(left, top),
        topRight = Point(right, top),
        bottomRight = Point(right, bottom),
        bottomLeft = Point(left, bottom)
    )

    /** 300×500 rectangle at (100,100). */
    private val q = quad(100f, 100f, 400f, 600f)

    /** Same 300×500 rectangle shifted 100 px right ⇒ IoU = (300-100)/(300+100) = 0.5. */
    private val qHalfOverlap = quad(200f, 100f, 500f, 600f)

    /** Fully disjoint rectangle. */
    private val qFar = quad(700f, 900f, 1000f, 1400f)

    @Test
    fun threeConsistentPassingQuads_fireOnThirdOnly() {
        val stability = AutoCaptureStability(requiredFrames = 3, minIou = 0.85)

        val d1 = stability.update(q)
        assertEquals(1, d1.stableCount)
        assertFalse("must not fire on frame 1", d1.fire)

        val d2 = stability.update(q)
        assertEquals(2, d2.stableCount)
        assertFalse("must not fire on frame 2", d2.fire)

        val d3 = stability.update(q)
        assertEquals(3, d3.stableCount)
        assertTrue("must fire on the third consistent frame", d3.fire)
        assertEquals(1.0, d3.iou, 1e-9)
    }

    @Test
    fun twoPassingThenOneFailing_neverFires() {
        val stability = AutoCaptureStability(requiredFrames = 3, minIou = 0.85)

        assertFalse(stability.update(q).fire)
        assertFalse(stability.update(q).fire)

        // A gate-failing frame is fed as null (the analyzer emits null, not a low score).
        val failed = stability.update(null)
        assertEquals(0, failed.stableCount)
        assertFalse(failed.fire)

        // The counter restarts: one more frame is only 1/3.
        val restart = stability.update(q)
        assertEquals(1, restart.stableCount)
        assertFalse(restart.fire)
    }

    @Test
    fun quadJumpWithIouHalf_neverFires() {
        assertEquals(0.5, AutoCaptureStability.iouOf(q, qHalfOverlap), 0.02)

        val stability = AutoCaptureStability(requiredFrames = 3, minIou = 0.85)
        assertFalse(stability.update(q).fire)
        assertFalse(stability.update(q).fire)

        val jumped = stability.update(qHalfOverlap)
        assertEquals("a jump restarts the hold from the new quad", 1, jumped.stableCount)
        assertFalse("IoU 0.5 < 0.85 must not fire", jumped.fire)
        assertEquals(0.5, jumped.iou, 0.02)
    }

    @Test
    fun iouOf_identicalIsOne_andDisjointIsZero() {
        assertEquals(1.0, AutoCaptureStability.iouOf(q, q), 1e-9)
        assertEquals(0.0, AutoCaptureStability.iouOf(q, qFar), 1e-9)
    }

    @Test
    fun reset_clearsTheWindow() {
        val stability = AutoCaptureStability(requiredFrames = 3, minIou = 0.85)
        stability.update(q)
        stability.update(q)
        assertEquals(2, stability.stableCount)
        stability.reset()
        assertEquals(0, stability.stableCount)
        assertFalse(stability.update(q).fire)
    }
}
