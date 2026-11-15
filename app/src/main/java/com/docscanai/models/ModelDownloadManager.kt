package com.docscanai.models

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Lifecycle state of a single model artifact (ARCHITECTURE.md §9). */
enum class ModelState { MISSING, DOWNLOADING, VERIFYING, READY, FAILED }

/**
 * SUBTASK H7: typed download/verification failure. `error` still carries the human-readable
 * detail; this enum is the machine-readable classification callers can branch on.
 */
enum class ModelFailure { NONE, NOT_CONFIGURED, SIZE_MISMATCH, SHA_MISMATCH, IO }

/** Snapshot of one model's download/availability status. */
data class ModelStatus(
    val modelId: String,
    val state: ModelState,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null,
    val failure: ModelFailure = ModelFailure.NONE
) {
    val progress: Float
        get() = if (totalBytes > 0L) {
            (bytesDownloaded.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Process-wide progress sink shared between [ModelDownloadManager] (observed by the UI)
 * and [ModelDownloadWorker] (which drives the download). Kept `internal` to the module.
 */
internal object ModelDownloadProgressBus {
    private val _statuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, ModelStatus>> = _statuses.asStateFlow()

    fun update(status: ModelStatus) {
        _statuses.update { it + (status.modelId to status) }
    }

    fun reset() {
        _statuses.value = emptyMap()
    }
}

/**
 * High-level API for the model-download lifecycle (MODELS.md §7, ARCHITECTURE.md §9).
 *
 * Responsibilities:
 *  - enqueue a resumable, retryable background download ([enqueueDownload]);
 *  - expose per-model + overall progress via `StateFlow`/`Flow`;
 *  - answer "is model X available?" ([isModelAvailable]) and resolve the dewarp fallback
 *    order ([resolveDewarpModelId]) for the processing pipeline;
 *  - own the on-disk layout `filesDir/models/<modelId>/<file>` (plus `meta.json`).
 *
 * It never blocks the app: missing models simply mean the caller uses the classical
 * Fallback tier.
 */
class ModelDownloadManager(
    private val context: Context,
    private val registry: ModelRegistry
) {

    /** Per-model status map (drives both the download screen and overall progress). */
    val statuses: StateFlow<Map<String, ModelStatus>> get() = ModelDownloadProgressBus.statuses

    fun observeStatus(modelId: String): Flow<ModelStatus?> = statuses.map { it[modelId] }

    /** Weighted-by-count overall download progress across all known models in [0,1]. */
    fun observeOverallProgress(): Flow<Float> = statuses.map { snapshot ->
        val specs = registry.models
        if (specs.isEmpty()) {
            0f
        } else {
            specs.map { spec ->
                snapshot[spec.id]?.progress ?: if (isModelAvailable(spec.id)) 1f else 0f
            }.average().toFloat()
        }
    }

    /**
     * True when **every** declared file of the model exists on disk and is non-empty
     * (SUBTASK H7: a multi-file model is only usable when its graph AND sidecar are present;
     * a graph without its external-data sidecar would fail to load in ORT).
     */
    fun isModelAvailable(modelId: String): Boolean {
        val spec = registry.byId(modelId) ?: return false
        val dir = modelDir(spec)
        return spec.allFiles().all { f ->
            val file = File(dir, f.name)
            file.exists() && file.length() > 0L
        }
    }

    /** True when every Fast-tier model is present (app can run the default pipeline). */
    fun isFastTierAvailable(): Boolean = registry.fastTierIds.all { isModelAvailable(it) }

    /**
     * First available dewarp model in the registry fallback order, or null when none is
     * present (=> the caller must use the classical TPS fallback, MODELS.md §3.4).
     */
    fun resolveDewarpModelId(): String? =
        registry.dewarpResolutionOrder.firstOrNull { isModelAvailable(it) }

    /** Schedules a (resumable, network-constrained, backoff-retried) background download. */
    fun enqueueDownload(modelIds: List<String> = registry.models.map { it.id }) {
        if (modelIds.isEmpty()) return
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_IDS to modelIds.toTypedArray()))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownloads() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Absolute path of a model's on-disk directory `filesDir/models/<id>/`. */
    fun modelDir(spec: ModelSpec): File =
        File(File(context.filesDir, MODELS_DIR), spec.id)

    /**
     * Absolute path of a model's **graph** artifact under `filesDir/models/<id>/`.
     * SUBTASK H7: this is deliberately unchanged — the graph is always [ModelSpec.fileName];
     * external-data sidecars live next to it in the same directory.
     */
    fun modelFile(spec: ModelSpec): File = File(modelDir(spec), spec.fileName)

    companion object {
        const val MODELS_DIR = "models"
        const val WORK_NAME = "model_download"
        const val WORK_TAG = "model_download"
    }
}
