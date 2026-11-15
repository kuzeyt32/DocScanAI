package com.docscanai.capture

import com.docscanai.data.Point
import com.docscanai.data.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * SUBTASK H33 — "show what it sees" alignment: the on-screen overlay (analysis-frame quad) and the
 * captured still must coincide in frame-fraction coordinates within ~1 %.
 *
 * Two independently written paths are cross-checked (no camera needed — both are pure functions):
 *
 *  1. [`SeedMapping.normalizedQuadToPixels`] — analysis-normalized → still PIXELS (the seed path);
 *  2. [`PreviewFrameMapping`] — analysis-normalized → canvas (the overlay path) → back to
 *     still-normalized via [PreviewFrameMapping.canvasToStillNormalized].
 *
 * After the H33 fix (Preview + ImageCapture pinned to one 4:3 stream, `FIT_CENTER` letterbox) the
 * preview frame IS the still frame, so the residual must be at the float-rounding level (<< 1 % of
 * the frame). The letterbox geometry is also pinned to the portrait numbers the real `PreviewView`
 * produces (1080x2400 view, upright 3:4 stream -> 1080x1440 centred frame).
 */
class PreviewAlignmentTest {

    /** Portrait phone canvas (upright), as on the target devices. */
    private val canvasW = 1080f
    private val canvasH = 2400f

    /** Upright still bitmap of the 4:3 stream (portrait-locked app), e.g. 3024x4032. */
    private val stillW = 3024
    private val stillH = 4032

    /** Upright analysis frame of the 1280x720 (16:9) ImageAnalysis target: 720/1280. */
    private val analysisAspect = 720f / 1280f

    private fun quadOf(pts: List<Point>): Quad = Quad(pts[0], pts[1], pts[2], pts[3])

    /** The 4 corners + a grid of interior points, all inside the analysis frame. */
    private fun samplePoints(): List<Point> {
        val pts = ArrayList<Point>()
        for (u in floatArrayOf(0f, 0.13f, 0.37f, 0.5f, 0.72f, 0.91f, 1f)) {
            for (v in floatArrayOf(0f, 0.19f, 0.44f, 0.62f, 0.87f, 1f)) {
                pts.add(Point(u, v))
            }
        }
        return pts
    }

    /** SeedMapping path: analysis-normalized point -> still-normalized point. */
    private fun seedPathStillNormalized(p: Point): Pair<Float, Float> {
        val q = quadOf(listOf(p, p, p, p))
        val px = SeedMapping.normalizedQuadToPixels(q, stillW, stillH, analysisAspect)
        return px.topLeft.x / stillW to px.topLeft.y / stillH
    }

    @Test
    fun seedMappingAndPreviewMappingAgree() {
        var maxResidual = 0f
        for (p in samplePoints()) {
            val (sx, sy) = seedPathStillNormalized(p)
            val (px, py) = PreviewFrameMapping.analysisToStillNormalized(
                p, analysisAspect, PreviewFrameMapping.PREVIEW_ASPECT
            )
            maxResidual = maxOf(maxResidual, hypot(px - sx, py - sy))
        }
        assertTrue(
            "analysis->still residual $maxResidual exceeds 1% frame fraction",
            maxResidual <= 0.01f
        )
    }

    @Test
    fun overlayRoundTripIsWithinOnePercentOfStill() {
        // Overlay drawn at toCanvas(p); the same world point in the still is the SeedMapping path
        // normalized to the frame. Reading the overlay position back off the canvas must recover
        // the still position to within 1% of frame fraction (measured: ~1e-7).
        var maxResidual = 0f
        for (p in samplePoints()) {
            val (cx, cy) = PreviewFrameMapping.toCanvas(
                p, canvasW, canvasH, analysisAspect
            )
            val (ux, uy) = PreviewFrameMapping.canvasToStillNormalized(cx, cy, canvasW, canvasH)
            val (sx, sy) = seedPathStillNormalized(p)
            maxResidual = maxOf(maxResidual, hypot(ux - sx, uy - sy))
        }
        assertTrue(
            "overlay vs still residual $maxResidual exceeds 1% frame fraction",
            maxResidual <= 0.01f
        )
    }

    @Test
    fun equalAspectIsIdentity() {
        // A 4:3-upright analysis frame (same framing as the still) must map 1:1 (SeedMapping's
        // "equal aspect" branch) and PreviewFrameMapping must agree.
        val stillAspect = stillW.toFloat() / stillH
        for (p in samplePoints()) {
            val (x, y) = PreviewFrameMapping.analysisToStillNormalized(
                p, stillAspect, PreviewFrameMapping.PREVIEW_ASPECT
            )
            assertEquals(p.x, x, 1e-5f)
            assertEquals(p.y, y, 1e-5f)
        }
    }

    @Test
    fun portraitLetterboxGeometry() {
        // What PreviewView FIT_CENTER renders for an upright 3:4 stream on a 1080x2400 view:
        // a 1080x1440 frame centred vertically (480 px letterbox top and bottom). This is the
        // geometry the overlay must use (the H33 fix); the pre-fix 4/3 constant produced an
        // 1080x810 frame and put overlay corners up to 22% of frame height off.
        val fit = PreviewFrameMapping.fitCenter(canvasW, canvasH, PreviewFrameMapping.PREVIEW_ASPECT)
        assertEquals(0f, fit.left, 1e-3f)
        assertEquals(480f, fit.top, 1e-3f)
        assertEquals(1080f, fit.width, 1e-3f)
        assertEquals(1440f, fit.height, 1e-3f)
    }
}
