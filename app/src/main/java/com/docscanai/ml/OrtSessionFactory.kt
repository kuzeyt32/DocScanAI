package com.docscanai.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.os.Build
import com.docscanai.core.util.Diag
import java.io.File

/**
 * Creates ONNX Runtime sessions with a **crash-proof, CPU-first** provider policy (SUBTASK 9c).
 *
 * ### Why CPU is now the default
 * Prior investigation (SUBTASK 9c) found that enabling **XNNPACK for every session** was the most
 * likely source of an *uncatchable* native crash (SIGSEGV / SIGABRT): XNNPACK is a native graph
 * transform/executor that can abort the process on unsupported ops or shapes, and such a fault
 * cannot be caught from Kotlin. Therefore:
 *
 *  1. [createSession] now uses the **plain CPU execution provider by default** (implicit ORT CPU
 *     EP, no extra native backend) — the maximally compatible, least surprising path.
 *  2. **XNNPACK is opt-in** via [enableXnnpack] (default `false`, sourced from
 *     `SettingsRepository.useXnnpack`). When enabled and the XNNPACK session cannot be created, the
 *     factory **transparently recreates the session with CPU EP only** (catch + log, tag `DocScan`).
 *  3. **NNAPI** stays a separate opt-in ([enableNnapi], default `false`) and is only attempted on
 *     API 29+ when the provider is actually available; any failure falls through to the CPU path.
 *  4. The execution provider **actually used** for each session is logged under the stable
 *     `DocScan` tag so the crash investigation can see exactly what ran.
 *  5. If the ORT native library is missing / cannot load, [environment] is `null` and every call
 *     returns `null` — callers degrade to the classical pipeline (they do).
 *
 * The instance is intended to be shared (see `ServiceLocator.ortSessionFactory`); it is cheap to
 * create additional sessions and thread-safe.
 *
 * @param enableNnapi opt in to the experimental NNAPI provider (default OFF).
 * @param intraOpThreads intra-op thread count; 2–4 is the locked sweet spot for phones.
 * @param enableXnnpack opt in to XNNPACK (default OFF — CPU EP is the default, SUBTASK 9c).
 * @param deterministicCompute SUBTASK H27: request ORT's deterministic-compute mode. CPU EP
 *   parallel reductions (and some fused kernels) may otherwise accumulate in a thread-count /
 *   scheduling dependent order. This is a per-session numerics guarantee, not a provider change.
 * @param interOpThreads SUBTASK H27: inter-op thread count. `0` keeps the ORT default.
 */
class OrtSessionFactory(
    private val enableNnapi: Boolean = false,
    private val intraOpThreads: Int = DEFAULT_THREADS,
    private val enableXnnpack: Boolean = false,
    private val deterministicCompute: Boolean = false,
    private val interOpThreads: Int = 0
) {

    /**
     * The single process-wide [OrtEnvironment], or `null` when the ORT native library failed to
     * load (unsupported ABI, missing `.so`, …). Cached — the initialisation runs at most once.
     */
    val environment: OrtEnvironment? by lazy { createEnvironment() }

    /**
     * Creates (or fails to create) an [OrtSession] for [modelFile].
     *
     * Provider order: optional NNAPI (opt-in) → optional XNNPACK (opt-in) → **plain CPU EP**
     * (default). Any XNNPACK/NNAPI failure falls back to CPU EP only.
     *
     * @param allowXnnpack when `false`, XNNPACK is never attempted, even if [enableXnnpack] is
     *   set. Required for graphs that natively abort inside XNNPACK (uncatchable SIGABRT) —
     *   SUBTASK D pinned the dynamic-shape UVDoc dewarp session to CPU EP this way.
     * @return a ready-to-use session, or `null` when the file is missing/empty or no provider
     *   could load it. Never throws.
     */
    fun createSession(modelFile: File, allowXnnpack: Boolean = true): OrtSession? {
        val env = environment ?: return null
        if (!modelFile.isFile || modelFile.length() == 0L) {
            Diag.warn(TAG, "model file missing/empty: ${modelFile.absolutePath}")
            return null
        }

        // 1) Experimental NNAPI (opt-in only) ------------------------------------------------
        if (enableNnapi && isNnapiAvailable()) {
            sessionFor(env, modelFile, Provider.NNAPI)?.let {
                Diag.log(TAG, "session EP=NNAPI file=${modelFile.name}")
                return it
            }
            Diag.warn(TAG, "NNAPI session failed for ${modelFile.name}; falling back to CPU EP")
        }

        // 2) XNNPACK (opt-in only; skippable per session via [allowXnnpack]). A *thrown* failure
        //    is handled below (recreate with CPU EP), but a native XNNPACK abort (SIGABRT inside
        //    libonnxruntime during graph partitioning) is UNCATCHABLE — so a graph known to abort
        //    must NOT be offered to XNNPACK at all. SUBTASK D evidence: the pinned UVDoc dewarp
        //    graph (dynamic [N,3,H,W] input) aborts; the fixed-shape DexiNed graph works.
        if (enableXnnpack && allowXnnpack) {
            sessionFor(env, modelFile, Provider.XNNPACK)?.let {
                Diag.log(TAG, "session EP=XNNPACK file=${modelFile.name}")
                return it
            }
            Diag.warn(
                TAG,
                "XNNPACK session failed for ${modelFile.name}; recreating with CPU EP only"
            )
        }

        // 3) Safe default: plain CPU EP (implicit ORT CPU provider, no extra native backend) ---
        val cpu = sessionFor(env, modelFile, Provider.CPU)
        if (cpu != null) {
            Diag.log(TAG, "session EP=CPU file=${modelFile.name}")
        } else {
            Diag.warn(TAG, "session creation failed for ${modelFile.name} (CPU EP)")
        }
        return cpu
    }

    // --- internals -------------------------------------------------------------------------

    private fun createEnvironment(): OrtEnvironment? = try {
        OrtEnvironment.getEnvironment()
    } catch (t: Throwable) {
        // Missing native lib / unsupported ABI must never crash the app.
        Diag.error(TAG, "OrtEnvironment unavailable; ONNX models disabled", t)
        null
    }

    private fun isNnapiAvailable(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            OrtEnvironment.getAvailableProviders().contains(OrtProvider.NNAPI)
    } catch (t: Throwable) {
        false
    }

    private fun sessionFor(env: OrtEnvironment, file: File, provider: Provider): OrtSession? {
        var options: OrtSession.SessionOptions? = null
        return try {
            options = buildOptions(provider)
            env.createSession(file.absolutePath, options)
        } catch (t: Throwable) {
            // A native XNNPACK/NNAPI failure surfaces here; the caller then retries with CPU EP.
            Diag.warn(TAG, "createSession failed (provider=$provider, file=${file.name}): ${t.message}")
            null
        } finally {
            // SessionOptions are only consumed while the session is being built; releasing them
            // afterwards avoids leaking the native OrtSessionOptions handle.
            runCatching { options?.close() }
        }
    }

    private fun buildOptions(provider: Provider): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setMemoryPatternOptimization(true)
        if (intraOpThreads > 0) options.setIntraOpNumThreads(intraOpThreads)
        if (interOpThreads > 0) options.setInterOpNumThreads(interOpThreads)
        // SUBTASK H27: opt-in numerics guarantee. Silently ignored by EPs that do not support it.
        if (deterministicCompute) {
            try {
                options.setDeterministicCompute(true)
            } catch (t: Throwable) {
                Diag.warn(TAG, "setDeterministicCompute unavailable: ${t.message}")
            }
        }
        when (provider) {
            Provider.NNAPI -> options.addNnapi()
            Provider.XNNPACK -> options.addXnnpack(emptyMap<String, String>())
            Provider.CPU -> Unit // CPU is the implicit default execution provider.
        }
        return options
    }

    /** Execution-provider choices, in the order the factory tries them. */
    private enum class Provider { NNAPI, XNNPACK, CPU }

    companion object {
        /** Log stage label; [`Diag`] prefixes every line with the stable `DocScan` tag. */
        private const val TAG = "OrtSessionFactory"

        /** 2–4 intra-op threads; clamped to the device's core count. */
        private val DEFAULT_THREADS: Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
