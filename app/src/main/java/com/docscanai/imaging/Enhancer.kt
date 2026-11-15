package com.docscanai.imaging

import android.graphics.Bitmap
import com.docscanai.data.PageEnhanceSettings

/**
 * Applies visual enhancement to a dewarped page: illumination / shadow removal, contrast,
 * white balance, gamma, and optional binarization.
 *
 * Contract (MODELS.md §4):
 *  - Locked default is the classical OpenCV pipeline (blur-divide + CLAHE + gamma +
 *    gray-world white balance); no OCR is performed.
 *  - [PageEnhanceSettings.mode] selects Original / Enhanced / B&W (Sauvola binarization).
 *  - Returns a NEW [Bitmap]; the caller recycles the input.
 *
 * TODO(subtask: enhancement): implement the classical pipeline; optionally add the
 *   DocShadow (`m3_shadow`) neural path behind a setting (off by default).
 */
interface Enhancer {

    /** Returns the enhanced page bitmap for [source] under [settings]. */
    suspend fun enhance(source: Bitmap, settings: PageEnhanceSettings): Bitmap

    /** Releases native/ORT resources held by the enhancer. */
    fun release()
}
