package com.docscanai.capture

import com.docscanai.data.Point
import kotlin.math.abs

/**
 * SUBTASK H33 — the on-screen placement of a live (analysis-frame) normalized point, matching the
 * "show what it sees" decision: the preview is `PreviewView.ScaleType.FIT_CENTER` and
 * [`CameraController`](../../../../../../../main/java/com/docscanai/capture/CameraController.kt)
 * pins **Preview + ImageCapture to the same 4:3 aspect**, so the preview shows the FULL field of
 * view of the still, letterboxed, never cropped.
 *
 * ## The two steps (and why)
 * The overlay quad is normalized to the **analysis** frame (16:9, `ImageAnalysis`'s 1280x720
 * target), while the preview surface renders the **preview stream** (4:3, same field of view as
 * the still). A point therefore travels:
 *
 *  1. analysis frame -> still/preview frame: the analysis frame is a *centred crop* of the same
 *     sensor field of view (identical math to
 *     [`SeedMapping.normalizedQuadToPixels`](../../../../../../../main/java/com/docscanai/capture/SeedMapping.kt),
 *     expressed in normalized units — `PreviewAlignmentTest` cross-checks the two agree);
 *  2. still/preview frame -> canvas: `FIT_CENTER` of [PREVIEW_ASPECT] on the view (letterbox; the
 *     inverse of the same transform is what `PreviewView` applies to the preview stream).
 *
 * Because preview and still share one aspect (the H33 fix), the preview frame IS the still frame:
 * an overlay corner lands exactly where the camera picture of that corner appears on screen.
 *
 * Pure function, no Android/Compose types: deterministic and unit-checkable without a camera.
 */
object PreviewFrameMapping {

    /**
     * Upright, on-screen aspect (w/h) of the shared Preview + ImageCapture stream. Both use cases
     * are pinned to 4:3 (SUBTASK H33), and the app is portrait-locked
     * (`AndroidManifest` -> `screenOrientation="portrait"`), so the upright frame of that stream is
     * **3:4** — this is what `PreviewView` letterboxes on the portrait canvas and what the upright
     * still bitmap looks like (e.g. 3024x4032). NOTE: this is w/h in UPRIGHT (post-rotation)
     * coordinates, i.e. 3/4 here, NOT the 4:3 of the landscape use-case resolution.
     */
    const val PREVIEW_ASPECT = 3f / 4f

    /** Relative aspect difference below which two frames are treated as identical (as [SeedMapping]). */
    const val ASPECT_TOLERANCE = 0.01

    /** FIT_CENTER placement of a [PREVIEW_ASPECT] frame on a `canvasWidth` x `canvasHeight` view. */
    data class Fit(val left: Float, val top: Float, val width: Float, val height: Float)

    /** `FIT_CENTER` placement of a frame of aspect [frameAspect] (w/h) on the canvas. */
    fun fitCenter(canvasWidth: Float, canvasHeight: Float, frameAspect: Float): Fit {
        val a = if (frameAspect > 0f) frameAspect else PREVIEW_ASPECT
        return if (a * canvasHeight >= canvasWidth) {
            // Frame is wider than the canvas -> fit width, letterbox top/bottom.
            val w = canvasWidth
            val h = canvasWidth / a
            Fit(0f, (canvasHeight - h) / 2f, w, h)
        } else {
            // Frame is taller than the canvas -> fit height, letterbox left/right.
            val h = canvasHeight
            val w = canvasHeight * a
            Fit((canvasWidth - w) / 2f, 0f, w, h)
        }
    }

    /**
     * Maps an analysis-frame normalized `[0,1]` point into the still/preview frame (normalized).
     * Mirrors [`SeedMapping.normalizedQuadToPixels`](../../../../../../../main/java/com/docscanai/capture/SeedMapping.kt)
     * in normalized units (both frames are centred crops of one sensor field of view):
     *  - equal aspect (within [ASPECT_TOLERANCE]) -> identity;
     *  - analysis wider than the still -> centred vertical crop (visible height `s/a`);
     *  - analysis taller (narrower) -> centred horizontal crop (visible width `a/s`).
     */
    fun analysisToStillNormalized(
        point: Point,
        analysisAspect: Float,
        stillAspect: Float = PREVIEW_ASPECT
    ): Pair<Float, Float> {
        val u = point.x.coerceIn(0f, 1f)
        val v = point.y.coerceIn(0f, 1f)
        val a = if (analysisAspect.isFinite() && analysisAspect > 0f) analysisAspect else stillAspect
        if (abs(a - stillAspect) / stillAspect <= ASPECT_TOLERANCE) return u to v
        return if (a > stillAspect) {
            val visible = stillAspect / a // visible height fraction of the still
            val top = (1f - visible) / 2f
            u to (top + v * visible)
        } else {
            val visible = a / stillAspect // visible width fraction of the still
            val left = (1f - visible) / 2f
            (left + u * visible) to v
        }
    }

    /**
     * Maps an analysis-frame normalized point onto the canvas exactly where the (letterboxed,
     * full-FOV) preview draws it. [analysisAspect] is the analysis frame's upright w/h
     * (`FrameAnalyzer.frameAspectRatio`); `<= 0` is treated as "same framing as the still".
     */
    fun toCanvas(
        point: Point,
        canvasWidth: Float,
        canvasHeight: Float,
        analysisAspect: Float
    ): Pair<Float, Float> {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return 0f to 0f
        val (u, v) = analysisToStillNormalized(point, analysisAspect)
        val fit = fitCenter(canvasWidth, canvasHeight, PREVIEW_ASPECT)
        return (fit.left + u * fit.width) to (fit.top + v * fit.height)
    }

    /**
     * Inverse of [toCanvas]'s second step: canvas pixels -> still-frame normalized coordinates.
     * Exact because the preview shows the still's field of view 1:1 (the H33 alignment).
     */
    fun canvasToStillNormalized(
        x: Float,
        y: Float,
        canvasWidth: Float,
        canvasHeight: Float
    ): Pair<Float, Float> {
        val fit = fitCenter(canvasWidth, canvasHeight, PREVIEW_ASPECT)
        if (fit.width <= 0f || fit.height <= 0f) return 0f to 0f
        return ((x - fit.left) / fit.width) to ((y - fit.top) / fit.height)
    }
}
