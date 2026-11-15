package com.docscanai.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * Thin, lifecycle-safe wrapper around a single ONNX Runtime [OrtSession] (ARCHITECTURE.md §2
 * `ml.*`). It loads a model from disk, validates it, caches the session and exposes a small
 * float-tensor `run(...)` API plus a clean [close].
 *
 * ### Graceful degradation
 * Loading is best-effort: [load] returns `null` (never throws) when the file is missing/empty,
 * the ORT session cannot be created, or the graph exposes no tensor I/O. Callers therefore treat
 * a `null` model as "use the classical fallback".
 *
 * ### Threading
 * ORT sessions are **not** guaranteed to be thread-safe, so every [run] is serialised on an
 * internal lock (MODELS.md §5 "one session per model; serialize calls per session").
 *
 * @property file the on-disk `*.onnx` artifact this model was loaded from.
 * @property inputNames model input tensor names (non-empty when loaded).
 * @property outputNames model output tensor names (non-empty when loaded).
 */
class OnnxModel private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    val file: File
) : Closeable {

    /** Model input tensor names, in the order reported by the runtime. */
    val inputNames: List<String> = session.inputNames.toList()

    /** Model output tensor names, in the order reported by the runtime. */
    val outputNames: List<String> = session.outputNames.toList()

    /** Shape of the first input tensor (may contain `-1` for dynamic dims), or `null`. */
    val inputShape: LongArray? = firstTensorShape(input = true)

    /** Shape of the first output tensor (may contain `-1` for dynamic dims), or `null`. */
    val outputShape: LongArray? = firstTensorShape(input = false)

    private val lock = Any()
    @Volatile private var closed = false

    /**
     * Runs inference with an already-built input tensor map.
     *
     * The caller owns the returned [OrtSession.Result] and **must** [OrtSession.Result.close] it.
     * Returns `null` when the session has been closed or inference fails.
     */
    fun run(inputs: Map<String, OnnxTensor>): OrtSession.Result? {
        if (closed) return null
        return synchronized(lock) {
            try {
                session.run(inputs)
            } catch (t: Throwable) {
                Log.w(TAG, "Inference failed for ${file.name}: ${t.message}")
                null
            }
        }
    }

    /**
     * SUBTASK H30 — multi-output inference for graphs that emit several tensors (M-LSD emits
     * junctions, scores and a displacement map).
     *
     * Returns every output that could be read, keyed by its graph name, with the value as a flat
     * primitive array ([FloatArray], [IntArray] or [LongArray]). The caller must select by
     * **shape**, not by name or index: ORT's `outputNames` is an unordered set, and the PINTO
     * export's names are ordinal (`Identity`, `Identity_1`, `Identity_2`) which says nothing about
     * which is which. Everything (input tensor + result) is allocated and released internally.
     * Returns `null` when the session is closed or inference fails.
     */
    fun runAllOutputs(input: FloatBuffer, shape: LongArray): Map<String, Any>? {
        if (closed) return null
        val inputName = inputNames.firstOrNull() ?: return null
        synchronized(lock) {
            var tensor: OnnxTensor? = null
            var result: OrtSession.Result? = null
            return try {
                tensor = OnnxTensor.createTensor(environment, input, shape)
                result = session.run(mapOf(inputName to tensor))
                val out = LinkedHashMap<String, Any>()
                for (name in outputNames) {
                    val t = result.get(name).orElse(null) as? OnnxTensor ?: continue
                    val value: Any? = runCatching {
                        t.floatBuffer?.let { buf ->
                            buf.rewind()
                            FloatArray(buf.remaining()).also { buf.get(it) }
                        }
                    }.getOrNull() ?: runCatching {
                        t.intBuffer?.let { buf ->
                            buf.rewind()
                            IntArray(buf.remaining()).also { buf.get(it) }
                        }
                    }.getOrNull() ?: runCatching {
                        t.longBuffer?.let { buf ->
                            buf.rewind()
                            LongArray(buf.remaining()).also { buf.get(it) }
                        }
                    }.getOrNull()
                    if (value != null) out[name] = value
                }
                if (out.isEmpty()) null else out
            } catch (t: Throwable) {
                Log.w(TAG, "runAllOutputs failed for ${file.name}: ${t.message}")
                null
            } finally {
                runCatching { result?.close() }
                runCatching { tensor?.close() }
            }
        }
    }

    /**
     * Shapes of every declared output, keyed by graph name (`null` for a non-tensor output).
     * Used together with [runAllOutputs] to identify tensors by shape instead of by name.
     */
    val outputShapesByType: Map<String, LongArray?> by lazy {
        runCatching {
            session.outputInfo.mapValues { (_, node) -> (node.info as? TensorInfo)?.shape }
        }.getOrDefault(emptyMap())
    }

    /**
     * Convenience single-input / single-output float inference: feeds [input] (NCHW, already
     * normalised) with the given [shape], then copies one output tensor to a flat `FloatArray`.
     *
     * The output is selected by [outputName] when provided (name-based lookup is preferred
     * because ORT's `getOutputNames()` is an unordered set and therefore not a reliable index
     * order), otherwise by [outputIndex]. Everything (input tensor + result) is allocated and
     * released internally. Returns `null` on any error or when the output is not float.
     */
    fun runFloat(
        input: FloatBuffer,
        shape: LongArray,
        outputName: String? = null,
        outputIndex: Int = 0
    ): FloatArray? {
        if (closed) return null
        val inputName = inputNames.firstOrNull() ?: return null
        synchronized(lock) {
            var tensor: OnnxTensor? = null
            var result: OrtSession.Result? = null
            return try {
                tensor = OnnxTensor.createTensor(environment, input, shape)
                result = session.run(mapOf(inputName to tensor))
                val output = if (outputName != null) {
                    result.get(outputName).orElse(null) as? OnnxTensor
                } else {
                    result.get(outputIndex) as? OnnxTensor
                } ?: return null
                val buffer = output.floatBuffer ?: return null
                buffer.rewind()
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "runFloat failed for ${file.name}: ${t.message}")
                null
            } finally {
                runCatching { result?.close() }
                runCatching { tensor?.close() }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { session.close() }
                .onFailure { Log.w(TAG, "Error closing session for ${file.name}", it) }
        }
    }

    private fun firstTensorShape(input: Boolean): LongArray? = try {
        val infos = if (input) session.inputInfo else session.outputInfo
        infos.values.firstOrNull()?.let { node ->
            (node.info as? TensorInfo)?.shape
        }
    } catch (t: Throwable) {
        null
    }

    companion object {
        private const val TAG = "OnnxModel"

        /**
         * Loads [file] through [factory], returning a validated [OnnxModel] or `null`.
         * Never throws; safe to call off the main thread (does disk + native work).
         *
         * @param allowXnnpack forwarded to [OrtSessionFactory.createSession]; callers pass
         *   `false` for graphs that natively abort inside XNNPACK (SUBTASK D: dynamic-shape
         *   UVDoc dewarp) so the session is created on CPU EP instead.
         */
        fun load(
            factory: OrtSessionFactory,
            file: File,
            allowXnnpack: Boolean = true
        ): OnnxModel? {
            val env = factory.environment ?: return null
            if (!file.isFile || file.length() == 0L) return null
            val session = factory.createSession(file, allowXnnpack) ?: return null
            return try {
                val model = OnnxModel(env, session, file)
                if (model.inputNames.isEmpty() || model.outputNames.isEmpty()) {
                    Log.w(TAG, "Model ${file.name} exposes no tensor I/O; discarding")
                    model.close()
                    null
                } else {
                    model
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to wrap session for ${file.name}", t)
                runCatching { session.close() }
                null
            }
        }
    }
}
