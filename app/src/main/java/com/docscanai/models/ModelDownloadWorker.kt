package com.docscanai.models

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.docscanai.core.util.Diag
import com.docscanai.core.util.Hashing
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * WorkManager worker that downloads model artifacts resumably (HTTP `Range`), verifies
 * SHA-256, and writes a `meta.json` alongside each artifact (MODELS.md §7, ARCHITECTURE.md §9).
 *
 * Mechanics are fully implemented; the actual URLs/checksums are TODO placeholders in
 * [ModelRegistry] (model-prep subtask). Until those are pinned, [isConfigured] is false and
 * the worker reports [ModelState.FAILED] without throwing, so the app degrades to the
 * Fallback tier instead of crashing.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val registry = ModelRegistry()
        val ids = inputData.getStringArray(KEY_MODEL_IDS)?.toList()
            ?: registry.models.map { it.id }

        var retryRequested = false
        for (id in ids) {
            val spec = registry.byId(id) ?: continue
            if (downloadModel(spec) == Outcome.RETRY) retryRequested = true
        }

        return if (retryRequested && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    /**
     * SUBTASK H7 — multi-file aware, resumable, atomic-ish download.
     *
     * Every file from [ModelSpec.allFiles] is downloaded into the SAME `filesDir/models/<id>/`
     * directory (so ONNX Runtime resolves a sidecar such as `UVDoc_grid.onnx.data` by relative
     * path). Each file is written to `<name>.part`, checked for exact byte count + SHA-256, and
     * only then renamed to its final name. On ANY mismatch the whole model directory is deleted
     * and a typed [ModelFailure] is reported — a partial file can never be picked up by ORT.
     */
    private suspend fun downloadModel(spec: ModelSpec): Outcome {
        val modelDir = File(File(applicationContext.filesDir, ModelDownloadManager.MODELS_DIR), spec.id)
        if (!modelDir.exists()) modelDir.mkdirs()
        val files = spec.allFiles()

        // Fast path: every file already present, exact size, verified hash.
        val allCached = files.all { f ->
            val dest = File(modelDir, f.name)
            dest.exists() && dest.length() > 0L && ModelFileVerifier.verify(dest, f) == null
        }
        if (allCached) {
            val total = files.sumOf { File(modelDir, it.name).length() }
            writeMetaJson(modelDir, spec, files)
            Diag.log(
                "Model",
                "${spec.id} files=${files.size} cached ok ${sizeSummary(spec, modelDir)}"
            )
            publish(ModelStatus(spec.id, ModelState.READY, total, total))
            return Outcome.SUCCESS
        }

        // Placeholder guard: any file URL/checksum not pinned yet (TODO: model-prep subtask).
        val unconfigured = files.firstOrNull { !isConfigured(it) }
        if (unconfigured != null) {
            publish(
                ModelStatus(
                    modelId = spec.id,
                    state = ModelState.FAILED,
                    bytesDownloaded = 0L,
                    totalBytes = spec.approxSizeBytes,
                    error = "URL/SHA-256 not pinned yet for ${unconfigured.name} " +
                        "(TODO: model-prep subtask).",
                    failure = ModelFailure.NOT_CONFIGURED
                )
            )
            return Outcome.NOT_CONFIGURED
        }

        var doneBytes = 0L
        return try {
            for (f in files) {
                val dest = File(modelDir, f.name)
                val partial = File(modelDir, f.name + ".part")

                // Per-file fast path.
                if (dest.exists() && dest.length() > 0L && ModelFileVerifier.verify(dest, f) == null) {
                    doneBytes += dest.length()
                    continue
                }

                // A .part longer than the expected artifact is corrupt/stale: restart that file.
                if (f.bytes > 0L && partial.exists() && partial.length() > f.bytes) partial.delete()
                val resumeFrom = if (partial.exists()) partial.length() else 0L
                publish(
                    ModelStatus(
                        modelId = spec.id,
                        state = ModelState.DOWNLOADING,
                        bytesDownloaded = doneBytes + resumeFrom,
                        totalBytes = spec.approxSizeBytes
                    )
                )

                withContext(Dispatchers.IO) {
                    OkHttpModelDownloader.download(f.url, partial, resumeFrom) { downloaded, _ ->
                        publish(
                            ModelStatus(
                                modelId = spec.id,
                                state = ModelState.DOWNLOADING,
                                bytesDownloaded = doneBytes + downloaded,
                                totalBytes = spec.approxSizeBytes
                            )
                        )
                    }
                }

                publish(
                    ModelStatus(
                        modelId = spec.id,
                        state = ModelState.VERIFYING,
                        bytesDownloaded = doneBytes + partial.length(),
                        totalBytes = spec.approxSizeBytes
                    )
                )

                val failure = ModelFileVerifier.verify(partial, f)
                if (failure != null) {
                    ModelFileVerifier.rejectFailedPartial(partial, modelDir)
                    publish(
                        ModelStatus(
                            modelId = spec.id,
                            state = ModelState.FAILED,
                            bytesDownloaded = 0L,
                            totalBytes = spec.approxSizeBytes,
                            error = "${f.name}: ${failure.name.lowercase()} " +
                                "(expected ${f.bytes}B sha=${f.sha256}); model dir deleted.",
                            failure = failure
                        )
                    )
                    return Outcome.RETRY
                }

                // Atomic-ish promotion: only a fully verified file gets its final name.
                if (dest.exists()) dest.delete()
                if (!partial.renameTo(dest)) {
                    partial.copyTo(dest, overwrite = true)
                    partial.delete()
                }
                doneBytes += dest.length()
            }

            writeMetaJson(modelDir, spec, files)
            val total = files.sumOf { File(modelDir, it.name).length() }
            Diag.log(
                "Model",
                "${spec.id} files=${files.size} ok ${sizeSummary(spec, modelDir)}"
            )
            publish(ModelStatus(spec.id, ModelState.READY, total, total))
            Outcome.SUCCESS
        } catch (io: IOException) {
            publish(
                ModelStatus(
                    modelId = spec.id,
                    state = ModelState.DOWNLOADING,
                    bytesDownloaded = doneBytes,
                    totalBytes = spec.approxSizeBytes,
                    error = io.message,
                    failure = ModelFailure.IO
                )
            )
            Outcome.RETRY
        } catch (t: Throwable) {
            publish(
                ModelStatus(
                    modelId = spec.id,
                    state = ModelState.FAILED,
                    bytesDownloaded = doneBytes,
                    totalBytes = spec.approxSizeBytes,
                    error = t.javaClass.simpleName + ": " + t.message,
                    failure = ModelFailure.IO
                )
            )
            Outcome.RETRY
        }
    }

    private fun isConfigured(f: ModelFile): Boolean =
        f.url.isNotBlank() &&
            !f.url.contains("REPLACE_ME") &&
            f.sha256.isNotBlank() &&
            f.sha256 != ModelRegistry.PLACEHOLDER_SHA256

    /** Human-readable per-file summary, e.g. `graph=243KB data=31.6MB`. */
    private fun sizeSummary(spec: ModelSpec, dir: File): String =
        spec.allFiles().joinToString(" ") { f ->
            val label = if (f.name == spec.fileName) "graph" else {
                f.name.substringAfterLast('.').ifBlank { "file" }
            }
            "$label=${humanBytes(File(dir, f.name).length())}"
        }

    private fun humanBytes(bytes: Long): String {
        if (bytes >= 1_000_000L) {
            return String.format(java.util.Locale.US, "%.1fMB", bytes / 1_000_000.0)
        }
        return "${(bytes + 500L) / 1000L}KB"
    }

    /**
     * SUBTASK H10 — extraction of the download-time verification contract so it can be asserted
     * directly by `ModelSidecarVerificationTest` without constructing a [WorkerParameters].
     *
     * Semantics are UNCHANGED from the original private helpers: exact byte count first, then
     * SHA-256; a failed `*.part` is deleted together with the whole model directory so a
     * partially valid artifact can never be picked up by ONNX Runtime.
     */
    internal object ModelFileVerifier {

        /** Returns `null` when [file] matches [f] exactly, else the typed failure. */
        fun verify(file: File, f: ModelFile): ModelFailure? {
            if (!file.isFile || file.length() == 0L) return ModelFailure.IO
            if (f.bytes > 0L && file.length() != f.bytes) return ModelFailure.SIZE_MISMATCH
            if (f.sha256.isBlank() || f.sha256 == ModelRegistry.PLACEHOLDER_SHA256) {
                return ModelFailure.SHA_MISMATCH
            }
            return if (Hashing.sha256Hex(file).equals(f.sha256.trim(), ignoreCase = true)) {
                null
            } else {
                ModelFailure.SHA_MISMATCH
            }
        }

        /** Removes the rejected partial AND its model dir (typed-failure cleanup path). */
        fun rejectFailedPartial(partial: File, modelDir: File) {
            partial.delete()
            deleteModelDir(modelDir)
        }

        /** Deletes a model directory whose contents failed verification (typed failure path). */
        private fun deleteModelDir(modelDir: File) {
            runCatching { modelDir.deleteRecursively() }
                .onFailure { Diag.warn("Model", "failed to delete ${modelDir.path}: ${it.message}") }
        }
    }

    private fun writeMetaJson(dir: File, spec: ModelSpec, files: List<ModelFile>) {
        val json = JSONObject().apply {
            put("id", spec.id)
            put("version", "2")
            put("sha256", spec.sha256)
            put("source", spec.downloadUrl)
            put("license", spec.license)
            val arr = org.json.JSONArray()
            files.forEach { f ->
                val onDisk = File(dir, f.name)
                arr.put(
                    JSONObject().apply {
                        put("name", f.name)
                        put("sha256", f.sha256)
                        put("bytes", onDisk.length())
                        put("url", f.url)
                    }
                )
            }
            put("files", arr)
        }
        File(dir, "meta.json").writeText(json.toString(2))
    }

    private fun publish(status: ModelStatus) = ModelDownloadProgressBus.update(status)

    private enum class Outcome { SUCCESS, RETRY, NOT_CONFIGURED }

    companion object {
        const val KEY_MODEL_IDS = "model_ids"
        private const val MAX_ATTEMPTS = 3
    }
}

/**
 * Minimal OkHttp downloader with `Range`-based resume. The client is created once and reused.
 */
internal object OkHttpModelDownloader {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Downloads [url] into [target], resuming from [resumeFrom] bytes when the server
     * supports it (HTTP 206). [onProgress] receives `(bytesWritten, totalBytes)` where
     * `totalBytes` is `-1` when the server does not advertise a length.
     *
     * @return total bytes written into [target].
     */
    fun download(
        url: String,
        target: File,
        resumeFrom: Long,
        onProgress: (Long, Long) -> Unit
    ): Long {
        val requestBuilder = Request.Builder().url(url).get()
        if (resumeFrom > 0L && target.exists()) {
            requestBuilder.header("Range", "bytes=$resumeFrom-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty response body for $url")

            val serverSupportsResume = response.code == 206
            val startOffset = if (serverSupportsResume) resumeFrom else 0L
            if (!serverSupportsResume && target.exists()) target.delete()

            val contentLength = body.contentLength()
            val total = if (contentLength >= 0L) startOffset + contentLength else -1L

            var written = startOffset
            FileOutputStream(target, serverSupportsResume).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                    output.flush()
                }
            }
            return written
        }
    }
}
