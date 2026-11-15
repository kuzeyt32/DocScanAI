package com.docscanai.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Session-scoped holder for the current [ScanDocument].
 *
 * NOTE (scaffold): this is intentionally in-memory. A later subtask should mirror it to
 * `SessionStore` (DataStore + files) so captured pages survive process death
 * (ARCHITECTURE.md §6). The public API here is designed to be the stable surface the
 * UI/ViewModels read and mutate.
 */
class ScanSessionRepository(
    private val sessionStore: SessionStore? = null
) {

    private val _document = MutableStateFlow(ScanDocument())
    val document: StateFlow<ScanDocument> = _document.asStateFlow()

    fun setDocument(document: ScanDocument) {
        _document.value = document
    }

    fun addPage(page: ScannedPage) {
        _document.update { doc ->
            doc.copy(pages = (doc.pages + page).reindex())
        }
    }

    fun updatePage(pageId: String, transform: (ScannedPage) -> ScannedPage) {
        _document.update { doc ->
            doc.copy(pages = doc.pages.map { if (it.id == pageId) transform(it) else it })
        }
    }

    fun removePage(pageId: String) {
        _document.update { doc ->
            doc.copy(pages = doc.pages.filterNot { it.id == pageId }.reindex())
        }
    }

    fun reorderPages(orderedIds: List<String>) {
        _document.update { doc ->
            val byId = doc.pages.associateBy { it.id }
            doc.copy(pages = orderedIds.mapNotNull { byId[it] }.reindex())
        }
    }

    fun setEnhancementMode(mode: EnhancementMode) {
        _document.update { it.copy(enhancementMode = mode) }
    }

    fun clear() {
        _document.value = ScanDocument()
    }

    /**
     * Restores the last persisted [ScanDocument] from [SessionStore] (process-death recovery).
     * A no-op when no store is configured or nothing has been persisted yet.
     */
    suspend fun restore() {
        val store = sessionStore ?: return
        store.load()?.let { _document.value = it }
    }

    /** Persists the current [ScanDocument] metadata via [SessionStore]. */
    suspend fun persist() {
        sessionStore?.save(_document.value)
    }

    private fun List<ScannedPage>.reindex(): List<ScannedPage> =
        mapIndexed { index, page -> page.copy(order = index) }
}
