package com.docscanai.capture

import com.docscanai.data.Point
import com.docscanai.data.Quad
import kotlin.math.abs

/**
 * SUBTASK H26 — maps the live, normalized `[0,1]` quad (analysis frame) into the pixel space of the
 * full-resolution still that seeds [`OpenCvQuadPageDetector.refine()`].
 *
 * ## Why this is not plain per-axis scaling
 * The capture path used to scale each axis independently and a code comment asserted that this is
 * safe because "the analysis frame and the still share the same upright orientation". Orientation
 * is not the only requirement: the two frames must also share the **same aspect ratio**. They
 * frequently do not — [`CameraController`](../../../../../../../main/java/com/docscanai/capture/CameraController.kt)
 * pins `ImageAnalysis` to `1280x720` (16:9) while `ImageCapture` resolves the sensor's native
 * (usually 4:3) maximum, so per-axis scaling placed the seed several percent away from the real
 * page and `refine()` then searched the wrong region. That is the only asymmetry on the capture
 * path that does not exist on import, i.e. the one H25 could not rule out without a device run.
 *
 * ## The transform
 * CameraX resolves each use case to its own aspect ratio by taking a **centred crop of the same
 * sensor field of view**. This object therefore maps the analysis point through that crop:
 *
 *  - equal aspect (within [ASPECT_TOLERANCE]) → plain per-axis scaling (exact);
 *  - analysis **wider** than the still → the analysis frame is a vertically centred crop: the
 *    still height seen by the analysis frame is `stillWidth / analysisAspect`;
 *  - analysis **narrower** (taller) than the still → a horizontally centred crop: the still width
 *    seen by the analysis frame is `stillHeight * analysisAspect`.
 *
 * Pure function, no Android types, no state: the mapping is deterministic in code and unit-testable
 * without a camera. Callers log the two ratios next to the mapped seed (`[Seed]` line).
 */
object SeedMapping {

    /** Relative aspect difference below which the two frames are treated as identical. */
    const val ASPECT_TOLERANCE = 0.01

    /**
     * Maps [quad] (normalized `[0,1]`, analysis frame) to the pixel space of a still of size
     * [stillWidth] × [stillHeight].
     *
     * @param analysisAspectRatio `analysisWidth / analysisHeight` of the frame the quad came from,
     *   or `null`/non-positive when unknown — in which case the still's own aspect is assumed
     *   (legacy behaviour, exact when the two frames really do share an aspect).
     */
    fun normalizedQuadToPixels(
        quad: Quad,
        stillWidth: Int,
        stillHeight: Int,
        analysisAspectRatio: Float?
    ): Quad {
        if (stillWidth <= 0 || stillHeight <= 0) return quad
        val w = stillWidth.toDouble()
        val h = stillHeight.toDouble()
        val stillAspect = w / h
        val analysis = analysisAspectRatio?.toDouble()
            ?.takeIf { it.isFinite() && it > 0.0 } ?: stillAspect

        if (abs(analysis - stillAspect) / stillAspect <= ASPECT_TOLERANCE) {
            // Identical framing: per-axis scaling IS the correct transform.
            return map(quad) { u, v -> u * w to v * h }
        }
        if (analysis > stillAspect) {
            // Analysis is wider: it covers the full still width and a centred vertical slice.
            val cropH = w / analysis
            val top = (h - cropH) / 2.0
            return map(quad) { u, v -> u * w to top + v * cropH }
        }
        // Analysis is taller: it covers the full still height and a centred horizontal slice.
        val cropW = h * analysis
        val left = (w - cropW) / 2.0
        return map(quad) { u, v -> left + u * cropW to v * h }
    }

    /** Applies a normalized `(u,v) -> (x,y)` mapping to all four corners, preserving order. */
    private fun map(quad: Quad, transform: (Double, Double) -> Pair<Double, Double>): Quad {
        fun pt(p: Point): Point {
            val (x, y) = transform(p.x.toDouble().coerceIn(0.0, 1.0), p.y.toDouble().coerceIn(0.0, 1.0))
            return Point(x.toFloat(), y.toFloat())
        }
        return Quad(
            topLeft = pt(quad.topLeft),
            topRight = pt(quad.topRight),
            bottomRight = pt(quad.bottomRight),
            bottomLeft = pt(quad.bottomLeft)
        )
    }
}
