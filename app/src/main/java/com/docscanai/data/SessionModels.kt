package com.docscanai.data

import java.util.UUID

/** Enhancement / clean-up mode (MODELS.md §4.1, ARCHITECTURE.md §8). */
enum class EnhancementMode(val id: String) {
    ORIGINAL("original"),
    ENHANCED("enhanced"),
    BW("bw");

    companion object {
        fun fromId(id: String?): EnhancementMode = entries.firstOrNull { it.id == id } ?: ENHANCED
    }
}

/** Export page sizing (TECHNICAL_SPEC.md §9). */
enum class PageSize { AUTO, A4, LETTER }

/** A point in source-image pixel coordinates. */
data class Point(val x: Float, val y: Float)

/** The four document corners, in source-image coordinates (ARCHITECTURE.md §8). */
data class Quad(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point
) {
    val points: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

/** Per-page enhancement parameters (ARCHITECTURE.md §8). */
data class PageEnhanceSettings(
    val mode: EnhancementMode = EnhancementMode.ENHANCED,
    val gamma: Float = 1.0f,
    val contrast: Float = 1.0f,
    val shadowRemove: Boolean = true,
    val binarize: Boolean = false,
    /**
     * SUBTASK H23: opt-in faint pencil / light-handwriting enhancement, applied AFTER the selected
     * [mode] and BEFORE the page is saved. Default **false**; never applied in
     * [EnhancementMode.ORIGINAL] (that mode is the user's untouched page) and never applied to the
     * raw capture. One-tap opt-in lives on the page-edit screen.
     */
    val handwriting: Boolean = false
)

/**
 * A single captured page within the active scan session.
 *
 * @param id stable page id (used as the `edit/{pageId}` nav argument).
 * @param imageFile path to the processed page image (app-private storage).
 * @param rawFile path to the raw full-res capture, if retained.
 * @param thumbnailFile path to the ~1 s capture thumbnail.
 * @param corners refined document quad, if known.
 * @param order 0-based position in the document.
 */
data class ScannedPage(
    val id: String,
    val imageFile: String,
    val rawFile: String? = null,
    val thumbnailFile: String? = null,
    val corners: Quad? = null,
    val order: Int = 0,
    val rotationDegrees: Int = 0,
    val enhancement: PageEnhanceSettings = PageEnhanceSettings(),
    /**
     * SUBTASK H23: the pipeline measured this page as low-contrast handwriting (faint-mark
     * heuristic). Drives the one-tap "Enhance handwriting" affordance; it is never applied
     * automatically. A false value can also mean "not measured".
     */
    val handwritingSuggested: Boolean = false
)

/** The active scan document: an ordered list of pages + the session-level mode. */
data class ScanDocument(
    val sessionId: String = UUID.randomUUID().toString(),
    val pages: List<ScannedPage> = emptyList(),
    val enhancementMode: EnhancementMode = EnhancementMode.ENHANCED
) {
    val pageCount: Int get() = pages.size
}
