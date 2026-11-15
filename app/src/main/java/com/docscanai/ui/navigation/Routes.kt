package com.docscanai.ui.navigation

/** Locked navigation route constants (TECHNICAL_SPEC.md §6.2, ARCHITECTURE.md §4). */
object Routes {
    const val SPLASH = "splash"
    const val MODEL_DOWNLOAD = "onboarding/download"
    const val CAMERA = "camera"
    const val REVIEW = "review"
    const val EDIT = "edit/{pageId}"
    const val EXPORT = "export"
    const val LIBRARY = "library"

    /**
     * Full-screen zoomable page viewer (SUBTASK F). Opened by tapping a captured page on Review.
     * Reuses the [ARG_PAGE_ID] argument (the tapped page's id) to select the starting page.
     */
    const val VIEWER = "viewer/{pageId}"

    /** Argument name used by the [EDIT] and [VIEWER] routes. */
    const val ARG_PAGE_ID = "pageId"

    /** Builds a concrete `edit/{pageId}` route for a page. */
    fun editPage(pageId: String): String = "edit/$pageId"

    /** Builds a concrete `viewer/{pageId}` route opening the viewer on the given page. */
    fun viewerPage(pageId: String): String = "viewer/$pageId"
}
