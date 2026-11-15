package com.docscanai.ui.screens.viewer

import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docscanai.core.util.Diag
import com.docscanai.data.ScannedPage
import com.docscanai.ui.session.ScanSessionViewModel
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Stable test tags for the instrumented viewer UI test (kept here so test and UI stay in sync). */
object ViewerTags {
    const val IMAGE = "viewer_image"
    const val ERROR = "viewer_error"
    const val CLOSE = "viewer_close"
    const val RAW_TOGGLE = "viewer_raw_toggle"
    const val PAGE_INDICATOR = "viewer_page_indicator"
    const val PAGER = "viewer_pager"
}

/** Zoom bounds: 1× fit → 8×. Double-tap toggles fit ↔ [DOUBLE_TAP_SCALE]. */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOM_EPSILON = 0.01f

/**
 * SUBTASK H11 — zoom level at which the high-resolution detail tier is requested. At 1.8× a 2048 px
 * preview is already being upsampled on a 1080-wide class screen, so above this the viewer swaps in
 * a source-resolution decode (async, memory-bounded) without resetting the zoom.
 */
internal const val DETAIL_ZOOM_THRESHOLD = 1.8f

/**
 * Detail decode target: 4× the fitted long edge is enough to cover 3×+ zoom with real source pixels;
 * [PageImageDecoder] still clamps it to the source size, [PageImageDecoder.MAX_DETAIL_LONG_EDGE]
 * and the heap-derived pixel budget.
 */
private const val DETAIL_TARGET_MULTIPLIER = 4f

/**
 * Test/telemetry probe for the detail swap. Instrumented tests cannot read the bitmap a Compose
 * `Image` is drawing, so the viewer records the long edge it actually installed. Internal, no
 * behaviour change (single volatile write when a tier is displayed).
 */
internal object ViewerDetailProbe {
    @Volatile
    var lastPreviewLongEdge: Int = 0

    @Volatile
    var lastDetailLongEdge: Int = 0

    fun reset() {
        lastPreviewLongEdge = 0
        lastDetailLongEdge = 0
    }
}

/**
 * SUBTASK H10 — the single pan-clamp rule used by [ZoomableImageView]: at [atScale] the image
 * may translate at most `(display * scale - container) / 2` on each axis, so it can never be
 * panned entirely off-screen. Extracted to a top-level `internal` function so
 * `HighResViewerTest` can assert the EXACT maths the view applies at 4× (the composable state
 * itself is not readable from a semantics node).
 */
internal fun viewerClampOffset(
    candidate: Offset,
    atScale: Float,
    displayW: Float,
    displayH: Float,
    containerW: Float,
    containerH: Float
): Offset {
    val maxX = max(0f, (displayW * atScale - containerW) / 2f)
    val maxY = max(0f, (displayH * atScale - containerH) / 2f)
    return Offset(
        candidate.x.coerceIn(-maxX, maxX),
        candidate.y.coerceIn(-maxY, maxY)
    )
}

/**
 * Full-screen, zoomable captured-page viewer (SUBTASK F).
 *
 * Opened by tapping a page (thumbnail/image row) on the Review screen. It shows the **processed**
 * page by default and, when a raw capture exists, a labelled toggle switches to the original
 * capture. Multi-page sessions get a horizontal pager that keeps the current page index; back-press
 * and the close button return to the previous screen with the shared [ScanSessionViewModel] session
 * state intact (the viewer only reads it).
 */
@Composable
fun PageViewerScreen(
    viewModel: ScanSessionViewModel,
    startPageId: String,
    onClose: () -> Unit
) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val pages = document.pages

    // Resolve the tap target to a page index once; fall back to the first page.
    val startIndex = remember(pages.size, startPageId) {
        pages.indexOfFirst { it.id == startPageId }.coerceAtLeast(0)
    }

    // Diagnostics: the harness greps `DocScan` for viewer open/close (SUBTASK F requirement).
    LaunchedEffect(Unit) { Diag.log("Viewer", "open page=$startPageId pages=${pages.size}") }
    DisposableEffect(Unit) { onDispose { Diag.log("Viewer", "close") } }

    PageViewerContent(pages = pages, startIndex = startIndex, onClose = onClose)
}

/**
 * Stateless viewer body. Split out from [PageViewerScreen] so the instrumented UI test can drive it
 * with a synthetic fixture (no ViewModel / no DataStore needed).
 */
@Composable
fun PageViewerContent(
    pages: List<ScannedPage>,
    startIndex: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Hardware/gesture back returns to the previous screen (navigation pop), preserving session.
    BackHandler(onBack = onClose)

    Surface(color = Color.Black, modifier = modifier.fillMaxSize()) {
        if (pages.isEmpty()) {
            ViewerErrorState(
                message = "This page is no longer available.",
                onClose = onClose
            )
            return@Surface
        }

        val pagerState = rememberPagerState(
            initialPage = startIndex.coerceIn(0, pages.size - 1)
        ) { pages.size }

        // Processed-vs-original toggle (only meaningful when a raw capture exists).
        var showRaw by remember { mutableStateOf(false) }
        // True while the current page is zoomed in. Disables the pager so panning works unimpeded.
        var zoomed by remember { mutableStateOf(false) }

        // A new page always starts at fit; reset the pager-gating flag with it.
        LaunchedEffect(pagerState.currentPage) { zoomed = false }

        val currentPage = pages.getOrNull(pagerState.currentPage)
        val rawAvailable = remember(currentPage) {
            currentPage?.rawFile?.let { File(it).exists() } == true
        }

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                // Only allow swiping between pages when the current page is NOT zoomed in, so a
                // horizontal drag pans the image instead of changing page (standard photo-viewer UX).
                userScrollEnabled = pages.size > 1 && !zoomed,
                key = { index -> pages[index].id },
                modifier = Modifier.fillMaxSize().testTag(ViewerTags.PAGER)
            ) { index ->
                val page = pages[index]
                // Prefer the processed page; fall back to raw. The toggle swaps to raw when present.
                val path = if (showRaw && page.rawFile != null) page.rawFile else page.imageFile
                PageViewerPage(
                    imagePath = path,
                    rotationDegrees = page.rotationDegrees,
                    pageIndex = index,
                    onZoomChanged = { isZoomed ->
                        if (index == pagerState.currentPage) zoomed = isZoomed
                    }
                )
            }

            // --- Overlay chrome: close / page indicator / raw toggle ---------------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag(ViewerTags.CLOSE)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close viewer", tint = Color.White)
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${pages.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag(ViewerTags.PAGE_INDICATOR)
                )
                Spacer(Modifier.weight(1f))
                if (rawAvailable) {
                    TextButton(
                        onClick = { showRaw = !showRaw },
                        modifier = Modifier.testTag(ViewerTags.RAW_TOGGLE)
                    ) {
                        Text(
                            text = if (showRaw) "Original" else "Processed",
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * One page of the viewer: two decode tiers (SUBTASK H11).
 *
 *  1. **preview** — [PageImageDecoder.decode], hard-capped at 2048 px, decodes immediately and is
 *     the only thing the first frame waits for;
 *  2. **detail** — [PageImageDecoder.decodeForDetail], a source-resolution decode under a heap
 *     budget, launched ONCE (never per frame) when the user zooms past [DETAIL_ZOOM_THRESHOLD].
 *     When it arrives the displayed bitmap is swapped while pan/scale state is preserved, so the
 *     zoom does not jump; the stale preview is released only after the swap is on screen.
 *
 * Both bitmaps are owned here and recycled on page change / leaving composition. If the detail
 * decode fails (OOM/IO) the preview simply stays — zoom keeps working, just softer.
 */
@Composable
private fun PageViewerPage(
    imagePath: String?,
    rotationDegrees: Int,
    pageIndex: Int,
    onZoomChanged: (Boolean) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        // Size the preview decode to the larger view dimension (bounded further inside the decoder).
        val targetLongEdge = with(density) { max(maxWidth.toPx(), maxHeight.toPx()).toInt() }
        // Detail tier: 4× the fitted long edge; the decoder clamps by source size / 4096 / budget.
        val detailTargetLongEdge = (targetLongEdge * DETAIL_TARGET_MULTIPLIER).toInt()

        // Two ownership slots. These state objects are stable per imagePath, so disposal can read
        // the LATEST bitmap even though it runs after recomposition.
        val previewState = remember(imagePath) { mutableStateOf<Bitmap?>(null) }
        val detailState = remember(imagePath) { mutableStateOf<Bitmap?>(null) }
        var loadError by remember(imagePath) { mutableStateOf<String?>(null) }
        // Published by the gesture loop; read only inside snapshotFlow, so no per-frame recompose.
        val scaleState = remember(imagePath) { mutableFloatStateOf(MIN_SCALE) }

        // --- Tier 1: preview (unchanged contract, never waits on detail) --------------------
        LaunchedEffect(imagePath, targetLongEdge) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PageImageDecoder.decode(
                        file = imagePath?.let { File(it) },
                        rotationDegrees = rotationDegrees,
                        viewLongEdgePx = targetLongEdge
                    )
                }
            }
            val ok = result.getOrNull()
            if (ok != null) {
                previewState.value = ok
                loadError = null
                ViewerDetailProbe.lastPreviewLongEdge = max(ok.width, ok.height)
            } else {
                result.exceptionOrNull()?.let { Diag.logThrowable("Viewer.decode", it) }
                previewState.value = null
                loadError = "Could not load image"
            }
        }

        // --- Tier 2: zoom-detail (ONE async decode per page, after the threshold) -----------
        LaunchedEffect(imagePath, detailTargetLongEdge) {
            val path = imagePath ?: return@LaunchedEffect
            if (detailState.value != null) return@LaunchedEffect
            // Suspend until the user actually crosses the zoom threshold (fires once).
            snapshotFlow { scaleState.floatValue >= DETAIL_ZOOM_THRESHOLD }.first { it }
            if (detailState.value != null) return@LaunchedEffect

            val budget = PageImageDecoder.detailBudget()
            Diag.log(
                "Viewer",
                "detail target=$detailTargetLongEdge maxPixels=${budget.maxPixels} " +
                    "budgetMB=${budget.budgetMb}"
            )
            val heapBefore = Debug.getNativeHeapAllocatedSize()
            val startedAt = SystemClock.elapsedRealtime()
            val decoded = withContext(Dispatchers.IO) {
                PageImageDecoder.decodeForDetail(
                    file = File(path),
                    rotationDegrees = rotationDegrees,
                    targetLongEdgePx = detailTargetLongEdge,
                    maxPixels = budget.maxPixels
                )
            }
            // Page changed / left composition / failure: discard without touching the preview.
            if (!isActive) {
                decoded?.let { if (!it.isRecycled) it.recycle() }
                return@LaunchedEffect
            }
            if (decoded == null) {
                Diag.warn("Viewer", "detail decode failed; keeping preview tier")
                return@LaunchedEffect
            }
            val longEdge = max(decoded.width, decoded.height)
            detailState.value = decoded
            ViewerDetailProbe.lastDetailLongEdge = longEdge
            val heapDeltaMb = (Debug.getNativeHeapAllocatedSize() - heapBefore)
                .coerceAtLeast(0L) / PageImageDecoder.BYTES_PER_MB
            Diag.log(
                "Viewer",
                "detail ready longEdge=$longEdge " +
                    "decodeMs=${SystemClock.elapsedRealtime() - startedAt} " +
                    "heapDeltaMB=${String.format(Locale.US, "%.1f", heapDeltaMb)}"
            )
        }

        val detail = detailState.value

        // Release the preview only AFTER the detail frame has been composed and drawn, so a bitmap
        // that is still being drawn is never recycled.
        LaunchedEffect(detail) {
            if (detail == null) return@LaunchedEffect
            withFrameNanos { }
            withFrameNanos { }
            val stale = previewState.value
            previewState.value = null
            if (stale != null && !stale.isRecycled) stale.recycle()
        }

        // Both slots are recycled when the page (imagePath) leaves composition or is replaced.
        DisposableEffect(imagePath) {
            val previewSlot = previewState
            val detailSlot = detailState
            onDispose {
                runCatching {
                    previewSlot.value?.let { if (!it.isRecycled) it.recycle() }
                    detailSlot.value?.let { if (!it.isRecycled) it.recycle() }
                }
            }
        }

        val preview = previewState.value
        val bmp = detail ?: preview
        when {
            loadError != null -> ViewerErrorState(message = loadError!!, onClose = null)
            bmp == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
            else -> ZoomableImageView(
                bitmap = bmp,
                pageIndex = pageIndex,
                containerW = containerW,
                containerH = containerH,
                scaleState = scaleState,
                onZoomChanged = onZoomChanged
            )
        }
    }
}

/**
 * A single zoomable/pannable page image.
 *
 * Gestures (SUBTASK F):
 *  - **pinch** to zoom 1×–8×, anchored at the gesture centroid;
 *  - **drag** to pan when zoomed, with clamping so the image can never be panned entirely off-screen;
 *  - **double-tap** toggles fit ↔ [DOUBLE_TAP_SCALE].
 *
 * The custom pointer loop only consumes a gesture once it actually zooms or pans the (zoomable)
 * image; a plain horizontal drag at fit scale is left for the pager so swiping between pages works.
 */
@Composable
private fun ZoomableImageView(
    bitmap: Bitmap,
    pageIndex: Int,
    containerW: Float,
    containerH: Float,
    scaleState: MutableFloatState,
    onZoomChanged: (Boolean) -> Unit
) {
    val imgW = bitmap.width.toFloat()
    val imgH = bitmap.height.toFloat()
    if (imgW <= 0f || imgH <= 0f || containerW <= 0f || containerH <= 0f) return

    // Size of the image when fitted into the container (ContentScale.Fit), and its centre.
    val fit = min(containerW / imgW, containerH / imgH)
    val displayW = imgW * fit
    val displayH = imgH * fit
    val containerCenter = Offset(containerW / 2f, containerH / 2f)

    // SUBTASK H11: keyed on the PAGE, not the bitmap, so swapping the preview for the detail
    // bitmap keeps the exact pan/scale state (a detail swap must not reset the user's zoom).
    var scale by remember(pageIndex) { mutableStateOf(MIN_SCALE) }
    var offset by remember(pageIndex) { mutableStateOf(Offset.Zero) }

    /** Clamps a translation so the (scaled) image always overlaps the viewport. */
    fun clampOffset(candidate: Offset, atScale: Float): Offset =
        viewerClampOffset(candidate, atScale, displayW, displayH, containerW, containerH)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                // Zoom + pan. Consumes the gesture ONLY when it zooms or when already zoomed, so a
                // horizontal swipe at fit scale is left to the pager.
                .pointerInput(bitmap) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val shouldHandle = zoomChange != 1f || scale > MIN_SCALE + ZOOM_EPSILON
                            if (shouldHandle && centroid != Offset.Unspecified) {
                                val newScale = (scale * zoomChange)
                                    .coerceIn(MIN_SCALE, MAX_SCALE)
                                val k = newScale / scale
                                // Keep the point under the centroid fixed, then apply the pan.
                                val proposed =
                                    (centroid - containerCenter) * (1f - k) + offset * k + panChange
                                offset = clampOffset(proposed, newScale)
                                scale = newScale
                                scaleState.floatValue = newScale
                                onZoomChanged(newScale > MIN_SCALE + ZOOM_EPSILON)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                // Double-tap toggles fit ↔ zoomed-in.
                .pointerInput(bitmap) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > MIN_SCALE + ZOOM_EPSILON) {
                                scale = MIN_SCALE
                                offset = Offset.Zero
                                scaleState.floatValue = MIN_SCALE
                                onZoomChanged(false)
                            } else {
                                scale = DOUBLE_TAP_SCALE
                                offset = clampOffset(offset, scale)
                                scaleState.floatValue = DOUBLE_TAP_SCALE
                                onZoomChanged(true)
                            }
                        }
                    )
                }
                .testTag(ViewerTags.IMAGE)
        )
    }
}

/** Clean, non-crashing error state for a missing/corrupt page (optionally with a Close button). */
@Composable
private fun ViewerErrorState(message: String, onClose: (() -> Unit)?) {
    Box(
        modifier = Modifier.fillMaxSize().testTag(ViewerTags.ERROR),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Cannot display this page",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (onClose != null) {
                TextButton(onClick = onClose) { Text("Close", color = Color.White) }
            }
        }
    }
}
