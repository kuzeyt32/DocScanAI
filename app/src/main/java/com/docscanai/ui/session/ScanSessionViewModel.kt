package com.docscanai.ui.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.docscanai.capture.ScanPipeline
import com.docscanai.core.di.ServiceLocator
import com.docscanai.core.util.Diag
import com.docscanai.data.EnhancementMode
import com.docscanai.data.PageEnhanceSettings
import com.docscanai.data.Quad
import com.docscanai.data.ScanDocument
import com.docscanai.data.ScanSessionRepository
import com.docscanai.data.ScannedPage
import com.docscanai.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nav-graph/Activity-scoped ViewModel owning the active [ScanDocument] (TECHNICAL_SPEC.md §6.2,
 * ARCHITECTURE.md §4). Shared between the Camera and Review destinations so captured pages persist
 * across navigation, and mirrored to [SessionStore] via [ScanSessionRepository] so they also
 * survive rotation and process death.
 *
 * Responsibilities:
 *  - expose the current document/pages as Compose state;
 *  - run the capture [ScanPipeline] off the main thread and append the resulting page;
 *  - edit actions: remove, reorder, set enhancement mode, clear, re-run enhancement.
 */
@OptIn(FlowPreview::class)
class ScanSessionViewModel(
    private val repository: ScanSessionRepository,
    private val settingsRepository: SettingsRepository,
    private val pipeline: ScanPipeline
) : ViewModel() {

    /** Current document (ordered pages + session-level enhancement mode). */
    val document: StateFlow<ScanDocument> = repository.document

    private val _isProcessing = MutableStateFlow(false)

    /** True while a captured page is being detected/dewarped/enhanced/persisted. */
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    /**
     * SUBTASK H23: persisted default for the opt-in handwriting stage. Mirrors
     * [SettingsRepository.handwritingEnhanceEnabled] (default `false`) so a capture made while the
     * user has opted in gets the stage, without touching the pipeline's synchronous signature.
     */
    private val _handwritingDefault = MutableStateFlow(false)
    val handwritingDefault: StateFlow<Boolean> = _handwritingDefault.asStateFlow()

    private val _processingError = MutableStateFlow<String?>(null)

    /**
     * Human-readable reason the last processing attempt failed, or `null`. The camera UI shows this
     * ("Processing failed — page kept as raw. [reason]") instead of crashing (SUBTASK 9a).
     */
    val processingError: StateFlow<String?> = _processingError.asStateFlow()

    /** Clears the last [processingError] (after the UI has surfaced it). */
    fun clearProcessingError() {
        _processingError.value = null
    }

    init {
        viewModelScope.launch {
            // Restore any surviving session, then keep the store in sync (debounced).
            repository.restore()
            // Storage hygiene: drop captured files for sessions that are no longer the active one
            // (TECHNICAL_SPEC.md §8.3). Runs off the main thread and never touches the live session.
            withContext(Dispatchers.IO) {
                runCatching { pipeline.pruneOrphanSessions(repository.document.value.sessionId) }
            }
            repository.document
                .debounce(PERSIST_DEBOUNCE_MS)
                .collect { repository.persist() }
        }
        viewModelScope.launch {
            // Seed the session mode from persisted settings on first observation.
            settingsRepository.enhancementMode.collect { mode ->
                if (mode != repository.document.value.enhancementMode) {
                    repository.setEnhancementMode(mode)
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.handwritingEnhanceEnabled.collect { enabled ->
                _handwritingDefault.value = enabled
            }
        }
    }

    /**
     * Processes a freshly captured full-resolution [source] through the pipeline and appends the
     * resulting page to the session. [source] is recycled by the caller (the camera layer).
     *
     * @return the appended [ScannedPage], or null on failure.
     */
    suspend fun processCapture(source: Bitmap, quad: Quad?): ScannedPage? {
        _isProcessing.value = true
        _processingError.value = null
        val start = System.currentTimeMillis()
        Diag.log(
            "ViewModel",
            "processCapture start quad=${quad != null} ${Diag.describe(source)}"
        )
        // Log which optional neural models are present so a crash right after capture is traceable.
        runCatching { ServiceLocator.logModelPresence() }
        return try {
            val session = repository.document.value
            val settings = PageEnhanceSettings(
                mode = session.enhancementMode,
                handwriting = _handwritingDefault.value
            )
            val page = pipeline.process(
                sessionId = session.sessionId,
                source = source,
                quad = quad,
                enhancement = settings
            )
            repository.addPage(page)
            Diag.log("ViewModel", "processCapture done page=${page.id} in ${System.currentTimeMillis() - start}ms")
            page
        } catch (t: Throwable) {
            // Catch Throwable (incl. OutOfMemoryError): surface an error instead of crashing.
            Diag.logThrowable("ViewModel", t)
            _processingError.value =
                "Processing failed — page kept as raw. [${t.message ?: t.javaClass.simpleName}]"
            null
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Removes a page from the session and deletes its captured files (raw + processed +
     * thumbnail) off the main thread so the `filesDir/scans/<sessionId>/` folders don't grow
     * unbounded (TECHNICAL_SPEC.md §8.3).
     */
    fun removePage(pageId: String) {
        val page = repository.document.value.pages.firstOrNull { it.id == pageId }
        repository.removePage(pageId)
        if (page != null) {
            viewModelScope.launch(Dispatchers.IO) { deletePageFiles(page) }
        }
    }

    /** Reorders pages to match [orderedIds]. */
    fun reorderPages(orderedIds: List<String>) = repository.reorderPages(orderedIds)

    /** Sets the session enhancement mode (persisted to settings too). */
    fun setEnhancementMode(mode: EnhancementMode) {
        repository.setEnhancementMode(mode)
        viewModelScope.launch { settingsRepository.setEnhancementMode(mode) }
    }

    /**
     * SUBTASK H23: per-page one-tap "Enhance handwriting". Flips the page's opt-in flag and re-runs
     * the pipeline from its raw capture (so the result is deterministic, not stacked on the previous
     * output). Does NOT change the persisted default; that stays OFF unless the user opted in.
     */
    suspend fun setHandwritingEnhance(pageId: String, enabled: Boolean): ScannedPage? {
        repository.updatePage(pageId) { page ->
            page.copy(enhancement = page.enhancement.copy(handwriting = enabled))
        }
        return reEnhance(pageId)
    }

    /** Persists the default for future captures (SUBTASK H23); default `false`. */
    fun setHandwritingDefault(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHandwritingEnhanceEnabled(enabled) }
    }

    /**
     * Rotates a page by [deltaDegrees] (typically ±90), normalised to `[0,360)`.
     *
     * The rotation is stored on [ScannedPage.rotationDegrees], rendered by the Review/Edit screens
     * and baked into the exported PDF (TECHNICAL_SPEC.md §9). It is intentionally NOT applied to
     * the on-disk processed image so it can be toggled non-destructively.
     */
    fun rotatePage(pageId: String, deltaDegrees: Int) = repository.updatePage(pageId) { page ->
        page.copy(rotationDegrees = ((page.rotationDegrees + deltaDegrees) % 360 + 360) % 360)
    }

    /**
     * Clears the current session: resets the in-memory document, drops the persisted snapshot and
     * deletes the session's captured files under `filesDir/scans/<sessionId>/` off the main thread
     * (TECHNICAL_SPEC.md §8.3). Used by Export's "New scan".
     */
    fun clear() {
        // Capture the id before clearing — `repository.clear()` installs a fresh random session id.
        val sessionId = repository.document.value.sessionId
        viewModelScope.launch {
            repository.clear()
            repository.persist()
            withContext(Dispatchers.IO) { pipeline.deleteSession(sessionId) }
        }
    }

    /** Deletes the on-disk files backing a removed page (best-effort; ignores missing files). */
    private fun deletePageFiles(page: ScannedPage) {
        listOfNotNull(page.imageFile, page.rawFile, page.thumbnailFile).forEach { path ->
            runCatching { File(path).delete() }
        }
    }

    /**
     * Re-runs the pipeline (dewarp + enhance) for an existing page using its raw capture and the
     * current session enhancement mode. Used by the page-edit screen.
     *
     * **SUBTASK H26 — geometry is re-derived, never re-seeded.** This used to pass
     * `page.corners` back in as the seed of `refine()`. If the first pass had already latched an
     * inner rectangle, every edit searched a crop drawn around *that* rectangle and could latch a
     * still tighter one, which is why the owner saw the corruption reappear (and get worse) on
     * Edit → ORIGINAL. Passing `seed = null` makes the reprocess re-detect exactly like the import
     * path, which the owner confirmed is correct. The `refine()` seed guard is the second line of
     * defence for the genuine capture path, which must stay seeded.
     */
    suspend fun reEnhance(pageId: String): ScannedPage? {
        val page = repository.document.value.pages.firstOrNull { it.id == pageId } ?: return null
        val rawPath = page.rawFile ?: page.imageFile
        // Decode on the IO dispatcher: the raw capture can be a full-resolution JPEG and callers
        // (the Edit screen) invoke this from the main-thread composition scope.
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(rawPath) }.getOrNull()
        } ?: return null
        return try {
            val session = repository.document.value
            val settings = page.enhancement.copy(mode = session.enhancementMode)
            // SUBTASK H26: seed = null -> full-frame detect() on the raw capture, exactly like an
            // import. Never re-seed with a previously latched quad (see the KDoc above).
            val reprocessed = pipeline.process(session.sessionId, bitmap, null, settings)
            val updated = page.copy(
                imageFile = reprocessed.imageFile,
                thumbnailFile = reprocessed.thumbnailFile,
                enhancement = reprocessed.enhancement,
                // SUBTASK H26: keep the stored geometry in sync with what was actually warped.
                corners = reprocessed.corners
            )
            repository.updatePage(pageId) { updated }
            updated
        } catch (t: Throwable) {
            Diag.logThrowable("ViewModel", t)
            _processingError.value =
                "Processing failed — page kept as raw. [${t.message ?: t.javaClass.simpleName}]"
            null
        } finally {
            runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }

    companion object {
        private const val TAG = "ScanSessionViewModel"
        private const val PERSIST_DEBOUNCE_MS = 400L

        /** Factory wiring the ServiceLocator singletons into the ViewModel. */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ScanSessionViewModel(
                    repository = ServiceLocator.scanSessionRepository,
                    settingsRepository = ServiceLocator.settingsRepository,
                    pipeline = ServiceLocator.pipeline
                ) as T
            }
        }
    }
}
