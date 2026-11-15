package com.docscanai.imaging

import android.graphics.Bitmap
import com.docscanai.data.Quad

/**
 * Perspective-corrects and flat-page-rectifies a captured page.
 *
 * Contract (MODELS.md §3, ARCHITECTURE.md §3):
 *  - Input: the full-resolution page [source] bitmap plus the refined [corners] quad
 *    (in the source image's coordinate space).
 *  - Output: a NEW flat, rectangular [Bitmap]; the caller owns and recycles the input.
 *  - The implementation should cap the output at 3000 px on the long edge and process
 *    one page at a time on a single background queue.
 *
 * TODO(subtask: dewarp): implement OpenCV `getPerspectiveTransform` + `warpPerspective`
 *   followed by the UVDoc (`m2_dewarp`) grid unwarp; fall back to DewarpNet WC (`m2f`)
 *   and finally to classical TPS when no model is available.
 */
interface DewarpProcessor {

    /** Returns the flattened page bitmap for [source] restricted to [corners]. */
    suspend fun dewarp(source: Bitmap, corners: Quad): Bitmap

    /** Releases native/ORT resources held by the processor. */
    fun release()
}
