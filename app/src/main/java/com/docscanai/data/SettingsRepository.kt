package com.docscanai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Single DataStore instance for the whole process (one per file name).
private val Context.docScanDataStore: DataStore<Preferences> by preferencesDataStore(name = "docscan_settings")

/**
 * DataStore(Preferences)-backed app settings (first-launch flag, enhancement mode, tiers).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val FIRST_LAUNCH_COMPLETED = booleanPreferencesKey("first_launch_completed")
        val ENHANCEMENT_MODE = stringPreferencesKey("enhancement_mode")
        val AUTO_CAPTURE_ENABLED = booleanPreferencesKey("auto_capture_enabled")
        val QUALITY_TIER_ENABLED = booleanPreferencesKey("quality_tier_enabled")
        val MODELS_SKIPPED = booleanPreferencesKey("models_skipped")

        /**
         * Debug switch (SUBTASK 9a): when `false` the neural dewarp (UVDoc) and neural shadow
         * (DocShadow) paths are skipped entirely and the classical OpenCV paths are used instead.
         * Lets us isolate whether a neural/ONNX stage is responsible for a crash without a rebuild.
         * Defaults to `true` (neural paths enabled when a model is present).
         */
        val NEURAL_PROCESSING_ENABLED = booleanPreferencesKey("neural_processing_enabled")

        /**
         * Execution-provider opt-in (SUBTASK 9c): XNNPACK is a native graph backend that can
         * hard-abort the process on some models (SIGSEGV/SIGABRT) and such a crash is
         * uncatchable. It is therefore **OFF by default**; the plain CPU execution provider is
         * used unless the user explicitly opts in here.
         */
        val USE_XNNPACK = booleanPreferencesKey("use_xnnpack")

        /**
         * SUBTASK H23: default opt-in for faint pencil / handwriting enhancement. **OFF by
         * default** — the stage changes how the user's page looks, so it is offered, never applied
         * silently. A page can still be enhanced once without changing this default (the edit
         * screen's one-tap affordance).
         */
        val HANDWRITING_ENHANCE_ENABLED = booleanPreferencesKey("handwriting_enhance_enabled")
    }

    val firstLaunchCompleted: Flow<Boolean> =
        context.docScanDataStore.data.map { it[Keys.FIRST_LAUNCH_COMPLETED] ?: false }

    val enhancementMode: Flow<EnhancementMode> =
        context.docScanDataStore.data.map { EnhancementMode.fromId(it[Keys.ENHANCEMENT_MODE]) }

    /** Auto-capture defaults ON (TECHNICAL_SPEC.md §6.3). */
    val autoCaptureEnabled: Flow<Boolean> =
        context.docScanDataStore.data.map { it[Keys.AUTO_CAPTURE_ENABLED] ?: true }

    val qualityTierEnabled: Flow<Boolean> =
        context.docScanDataStore.data.map { it[Keys.QUALITY_TIER_ENABLED] ?: false }

    val modelsSkipped: Flow<Boolean> =
        context.docScanDataStore.data.map { it[Keys.MODELS_SKIPPED] ?: false }

    /** Neural dewarp / shadow processing switch (default `true`). See [Keys.NEURAL_PROCESSING_ENABLED]. */
    val neuralProcessingEnabled: Flow<Boolean> =
        context.docScanDataStore.data.map { it[Keys.NEURAL_PROCESSING_ENABLED] ?: true }

    /**
     * XNNPACK execution-provider switch (default `false` = plain CPU EP). See [Keys.USE_XNNPACK].
     * Only when `true` does `OrtSessionFactory` attempt an XNNPACK session (with a CPU fallback).
     */
    val useXnnpack: Flow<Boolean> =
        context.docScanDataStore.data.map { it[Keys.USE_XNNPACK] ?: false }

    /** Default for the handwriting stage (SUBTASK H23); `false` unless the user opts in. */
    val handwritingEnhanceEnabled: Flow<Boolean> =
        context.docScanDataStore.data.map { it[Keys.HANDWRITING_ENHANCE_ENABLED] ?: false }

    suspend fun setFirstLaunchCompleted(completed: Boolean) {
        context.docScanDataStore.edit { it[Keys.FIRST_LAUNCH_COMPLETED] = completed }
    }

    suspend fun setEnhancementMode(mode: EnhancementMode) {
        context.docScanDataStore.edit { it[Keys.ENHANCEMENT_MODE] = mode.id }
    }

    suspend fun setAutoCaptureEnabled(enabled: Boolean) {
        context.docScanDataStore.edit { it[Keys.AUTO_CAPTURE_ENABLED] = enabled }
    }

    suspend fun setQualityTierEnabled(enabled: Boolean) {
        context.docScanDataStore.edit { it[Keys.QUALITY_TIER_ENABLED] = enabled }
    }

    suspend fun setModelsSkipped(skipped: Boolean) {
        context.docScanDataStore.edit { it[Keys.MODELS_SKIPPED] = skipped }
    }

    /** Toggles the neural processing paths (dewarp + shadow). Persisted; default `true`. */
    suspend fun setNeuralProcessingEnabled(enabled: Boolean) {
        context.docScanDataStore.edit { it[Keys.NEURAL_PROCESSING_ENABLED] = enabled }
    }

    /** Toggles the opt-in XNNPACK execution provider. Persisted; default `false` (CPU EP). */
    suspend fun setUseXnnpack(enabled: Boolean) {
        context.docScanDataStore.edit { it[Keys.USE_XNNPACK] = enabled }
    }

    /** Persists the handwriting-enhancement default (SUBTASK H23); default `false`. */
    suspend fun setHandwritingEnhanceEnabled(enabled: Boolean) {
        context.docScanDataStore.edit { it[Keys.HANDWRITING_ENHANCE_ENABLED] = enabled }
    }
}
