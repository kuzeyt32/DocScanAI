package com.docscanai.capture

import android.graphics.Bitmap
import com.docscanai.data.Quad

/**
 * SUBTASK H21 — provenance of a returned quad.
 *
 * `CORNER_MODEL`     the DocAligner corner-heatmap model produced all four corners (a detection).
 * `CLASSICAL_FALLBACK` a classical OpenCV cue (edges / thresholds / Hough) constructed the quad
 *                    after the model missed (a structural guess, not a detection).
 * `FULL_FRAME`       not a detection at all: the caller substituted the whole frame
 *                    (`BitmapUtils.fullFrameQuad`) after detection failed.
 * `UNKNOWN`          no provenance available (test doubles, legacy callers).
 */
enum class QuadProvenance {
    CORNER_MODEL,
    CLASSICAL_FALLBACK,
    FULL_FRAME,
    UNKNOWN
}

/**
 * Detects the document boundary (a 4-corner [Quad]) in a single image.
 *
 * Contract (MODELS.md §2, ARCHITECTURE.md §3):
 *  - Runs OFF the main thread (the real implementation uses a dedicated analysis executor).
 *  - Input: a downscaled / letterboxed ARGB_8888 [bitmap].
 *  - Output: the detected [Quad] expressed in the INPUT image's coordinate space,
 *    or `null` when no confident boundary is found.
 *  - Implementations must be safe to reuse across frames and to [release] on teardown.
 *
 * TODO(subtask: detection): implement with the DexiNed ONNX boundary model (`m1_boundary`)
 *   plus a classical Canny/Sobel + contour fallback, and add temporal (EMA) smoothing for
 *   the live preview.
 */
interface PageDetector {

    /**
     * SUBTASK H21 — where the LAST quad returned by [detect] came from.
     *
     * The detector has a primary model path and classical fallback paths. They are not equal
     * evidence: a model peak is a *detection*, a Hough/contour construct is a *guess*. Auto-capture
     * is only allowed to fire on a guess when the CONTENT independently proves document text.
     * Callers read this immediately after [detect] (the detector is used single-flight per frame).
     */
    val lastProvenance: QuadProvenance get() = QuadProvenance.UNKNOWN

    /** Returns the detected document quad, or null when no confident boundary exists. */
    suspend fun detect(bitmap: Bitmap): Quad?

    /**
     * Optional **full-resolution refinement** for the capture path (TECHNICAL_SPEC.md §4.2 step 2):
     * re-runs detection on the still, restricted to a region around [seed] (a quad in the bitmap's
     * pixel space), and returns a refined quad in the same space. Returning [seed] unchanged is a
     * valid no-op result. The default implementation is that no-op, so detectors without a refine
     * step (and tests) keep working.
     */
    fun refine(bitmap: Bitmap, seed: Quad): Quad = seed

    /** Releases native/ORT resources held by the detector. */
    fun release()
}
