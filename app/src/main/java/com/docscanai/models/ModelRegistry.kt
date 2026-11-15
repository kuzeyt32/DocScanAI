package com.docscanai.models

/** Coarse tier classification from MODELS.md §1. */
enum class ModelTier { FAST, QUALITY, FALLBACK, OPTIONAL }

/**
 * One downloadable file that belongs to a [ModelSpec] (SUBTASK H7).
 *
 * A model can consist of MULTIPLE files: the grid UVDoc export is a tiny graph
 * (`UVDoc_grid.onnx`) that references a large external-data sidecar
 * (`UVDoc_grid.onnx.data`). ONNX Runtime resolves the sidecar **by relative path**, so every
 * file must land in the SAME `filesDir/models/<id>/` directory.
 *
 * @param name on-disk file name inside the model directory.
 * @param url pinned HTTPS URL.
 * @param sha256 lowercase hex SHA-256 of this file.
 * @param bytes exact byte count (0 = don't enforce).
 */
data class ModelFile(
    val name: String,
    val url: String,
    val sha256: String,
    val bytes: Long
)

/**
 * Immutable description of a downloadable model artifact (MODELS.md §0.1/§7).
 *
 * @param id registry id, e.g. `m2_dewarp`; also the on-disk folder name.
 * @param displayName human-readable name for the download UI.
 * @param fileName on-disk **graph** file name under `filesDir/models/<id>/`.
 * @param approxSizeBytes approximate total artifact size (used for progress/progress bars).
 * @param downloadUrl HTTPS URL of the graph file (kept for single-file compatibility).
 * @param sha256 lowercase hex SHA-256 of the graph file.
 * @param tier which pipeline tier this model belongs to.
 * @param license SPDX-ish license string preserved for the About screen.
 * @param sourceRepository canonical upstream project URL.
 * @param files SUBTASK H7: optional complete per-file list. Empty = legacy single file
 *   ([fileName]/[downloadUrl]/[sha256]/[approxSizeBytes]). When non-empty, [fileName] must
 *   name the graph file and every listed file is downloaded into the same model directory.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val approxSizeBytes: Long,
    val downloadUrl: String,
    val sha256: String,
    val tier: ModelTier,
    val license: String,
    val sourceRepository: String,
    val files: List<ModelFile> = emptyList()
) {
    /** Complete file list; falls back to the legacy single-file fields. */
    fun allFiles(): List<ModelFile> =
        if (files.isNotEmpty()) files
        else listOf(ModelFile(fileName, downloadUrl, sha256, approxSizeBytes))
}

/**
 * Static list of on-device models from MODELS.md.
 *
 * IMPORTANT: the [ModelSpec.downloadUrl] + [ModelSpec.sha256] values are explicitly
 * TODO placeholders. A later "model-prep" subtask (MODELS.md §8) must export/publish the
 * artifacts and replace these with the real pinned HTTPS URL + SHA-256 hex digest.
 * Until then the download worker reports NOT_CONFIGURED and the app runs in the Fallback tier.
 */
class ModelRegistry {

    val models: List<ModelSpec> = listOf(
        ModelSpec(
            id = ID_M1C_DETECT,
            displayName = "DocAligner page-corner detector (LCNet100)",
            fileName = "model.onnx",
            // REAL pinned artifact: the DocAligner LCNet100 heatmap corner regressor (Apache-2.0),
            // re-hosted on Hugging Face so the upstream undocumented Google-Drive auto-downloader is
            // not required. SUBTASK H4: this is the PRIMARY on-device page detector.
            // Verified I/O (see MODEL_SOURCES.md): input `img` [1,3,256,256] float32 (FIXED; RGB,
            // 0..1 via /255, NCHW, no mean/std); output `heatmap` [1,4,128,128] float32 (already
            // sigmoid-activated), channel order = TL, TR, BR, BL corner heat maps.
            // SHA-256 computed with `certutil -hashfile <file> SHA256`; matches the HF LFS ETag.
            approxSizeBytes = 4_767_987L,
            downloadUrl = M1C_DETECT_URL,
            sha256 = M1C_DETECT_SHA256,
            tier = ModelTier.FAST,
            license = "Apache-2.0",
            sourceRepository = "https://huggingface.co/7rplus/pagescan-weights"
        ),
        ModelSpec(
            id = ID_M1_BOUNDARY,
            displayName = "DexiNed boundary detector",
            fileName = "model.onnx",
            // REAL pinned artifact: OpenCV Zoo DexiNed ONNX export (MIT).
            // Verified I/O: input "img" [1,3,480,640] (fixed), 7 outputs (fused = "block_cat").
            // Preprocessing: BGR, Caffe mean (103.5,116.2,123.6) on the 0-255 scale (no /255).
            // SHA-256 below was computed with `certutil -hashfile ... SHA256` and matches the
            // upstream Git-LFS object id. Source/license: see MODEL_SOURCES.md.
            approxSizeBytes = 47_235_563L,
            downloadUrl = M1_BOUNDARY_URL,
            sha256 = M1_BOUNDARY_SHA256,
            tier = ModelTier.FAST,
            license = "MIT",
            sourceRepository = "https://huggingface.co/opencv/edge_detection_dexined"
        ),
        ModelSpec(
            id = ID_M2_DEWARP,
            displayName = "UVDoc neural dewarp (image-to-image)",
            fileName = "model.onnx",
            // REAL pinned artifact: PaddlePaddle UVDoc ONNX export (Apache-2.0). Single file, no
            // external data. Verified I/O (see MODEL_SOURCES.md): input "image" float32
            // [N,3,H,W] (dynamic; RGB, [0,1]); output "fetch_name_0" float32 [N,3,H,W] (dynamic;
            // rectified RGB image, same spatial size as the input, values in [0,1]).
            // SHA-256 computed with `certutil -hashfile <file> SHA256`.
            approxSizeBytes = 31_684_150L,
            downloadUrl = M2_DEWARP_URL,
            sha256 = M2_DEWARP_SHA256,
            tier = ModelTier.FAST,
            license = "Apache-2.0",
            sourceRepository = "https://huggingface.co/PaddlePaddle/UVDoc_onnx"
        ),
        ModelSpec(
            id = ID_M2_DEWARP_GRID,
            displayName = "UVDoc neural dewarp (45x31 grid, single-sample remap)",
            fileName = M2_DEWARP_GRID_GRAPH_NAME,
            // REAL pinned artifact: community grid-output UVDoc ONNX re-host (Apache-2.0).
            // Two files in the SAME model directory: the graph references its external-data
            // sidecar by relative path. Contract VERIFIED with tooling/OnnxInspect.java
            // (JDK-only protobuf parser):
            //   input  "image"  float32 [1,3,720,496]  RGB, [0,1], NCHW
            //   output "grid_2d" float32 [1,2,45,31]   normalized sampling coords in [-1,1]
            // 92 initializers; the weights with data_location=EXTERNAL point at
            // "UVDoc_grid.onnx.data" (offset/length entries). SHA-256 values equal the HF LFS
            // object ids and were re-verified locally with certutil.
            approxSizeBytes = M2_DEWARP_GRID_TOTAL_BYTES,
            downloadUrl = M2_DEWARP_GRID_URL,
            sha256 = M2_DEWARP_GRID_SHA256,
            tier = ModelTier.FAST,
            license = "Apache-2.0",
            sourceRepository = "https://huggingface.co/fredcallagan/uvdoc-grid-onnx",
            files = listOf(
                ModelFile(
                    name = M2_DEWARP_GRID_GRAPH_NAME,
                    url = M2_DEWARP_GRID_URL,
                    sha256 = M2_DEWARP_GRID_SHA256,
                    bytes = M2_DEWARP_GRID_GRAPH_BYTES
                ),
                ModelFile(
                    name = M2_DEWARP_GRID_DATA_NAME,
                    url = M2_DEWARP_GRID_DATA_URL,
                    sha256 = M2_DEWARP_GRID_DATA_SHA256,
                    bytes = M2_DEWARP_GRID_DATA_BYTES
                )
            )
        ),
        ModelSpec(
            id = ID_M2P_DEWARP_PLUS,
            displayName = "DRCNet / PaperEdge dewarp (Quality)",
            fileName = "model.onnx",
            approxSizeBytes = 150L * MB,
            downloadUrl = "$PLACEHOLDER_BASE_URL/$ID_M2P_DEWARP_PLUS/model.onnx",
            sha256 = PLACEHOLDER_SHA256,
            tier = ModelTier.QUALITY,
            license = "MIT",
            sourceRepository = "https://github.com/cvlab-stonybrook/PaperEdge"
        ),
        ModelSpec(
            id = ID_M2F_DEWARP_FALLBACK,
            displayName = "DewarpNet WC (light fallback)",
            fileName = "model.onnx",
            approxSizeBytes = 8L * MB,
            downloadUrl = "$PLACEHOLDER_BASE_URL/$ID_M2F_DEWARP_FALLBACK/model.onnx",
            sha256 = PLACEHOLDER_SHA256,
            tier = ModelTier.FALLBACK,
            license = "MIT",
            sourceRepository = "https://github.com/cvlab-stonybrook/DewarpNet"
        ),
        ModelSpec(
            id = ID_M3_SHADOW,
            displayName = "DocShadow neural shadow removal (optional)",
            fileName = "model.onnx",
            approxSizeBytes = 60L * MB,
            downloadUrl = "$PLACEHOLDER_BASE_URL/$ID_M3_SHADOW/model.onnx",
            sha256 = PLACEHOLDER_SHA256,
            tier = ModelTier.OPTIONAL,
            license = "See repo (ShadowFormer MIT; verify others)",
            sourceRepository = "https://github.com/GuoLanqing/ShadowFormer"
        )
    )

    fun byId(id: String): ModelSpec? = models.firstOrNull { it.id == id }

    fun modelsForTier(tier: ModelTier): List<ModelSpec> = models.filter { it.tier == tier }

    /**
     * Model ids required for the default Fast tier pipeline.
     *
     * SUBTASK H4: [ID_M1C_DETECT] (the DocAligner corner detector) is now the primary page
     * detector, so it is a Fast-tier requirement. `m1_boundary` (DexiNed) is retained in the
     * registry and still provisioned/presence-checked, but is no longer wired into the detection
     * decision (see MODELS.md §2.1 for the measured justification).
     *
     * SUBTASK H7: [ID_M2_DEWARP_GRID] is the primary dewarp artifact (45x31 grid, applied as a
     * single remap from the original still). [ID_M2_DEWARP] stays a Fast-tier requirement because
     * it is the image→image fallback when the grid graph is missing/invalid.
     */
    val fastTierIds: List<String> =
        listOf(ID_M1C_DETECT, ID_M1_BOUNDARY, ID_M2_DEWARP, ID_M2_DEWARP_GRID)

    /**
     * Image→image dewarp resolution order: primary (UVDoc) -> quality (DRCNet/PaperEdge) -> light
     * (DewarpNet WC) -> `null` meaning "use the classical TPS fallback" (MODELS.md §3.4).
     *
     * NOTE (H7): the grid artifact is intentionally NOT in this list — it is consumed by the
     * dedicated grid path ([ID_M2_DEWARP_GRID]); this order is only for image→image exports.
     */
    val dewarpResolutionOrder: List<String> =
        listOf(ID_M2_DEWARP, ID_M2P_DEWARP_PLUS, ID_M2F_DEWARP_FALLBACK)

    companion object {
        const val ID_M1_BOUNDARY = "m1_boundary"

        /** SUBTASK H4: DocAligner LCNet100 page-corner detector (primary AI detector). */
        const val ID_M1C_DETECT = "m1c_detect"

        /**
         * SUBTASK H30: the optional M-LSD line-evidence model.
         *
         * **Deliberately NOT in [models].** Upstream (PINTO model zoo `119_M-LSD`) publishes the
         * four graphs inside a single `resources.tar.gz` archive, not as per-file URLs, so there is
         * no single-file HTTPS URL whose SHA-256 could be pinned and verified by the downloader —
         * and registering an archive URL as if it were a graph would hand
         * `ModelDownloadManager` a file that can never load. The model is therefore *optional and
         * tooling-provisioned*: `MlsdModel` loads `files/models/m2_mlsd/model.onnx` when it is
         * present (pushed by `tooling/_provision_models.cmd` from a local artifact) and every
         * caller treats its absence as "no line evidence". See MODELS.md §2.2.
         */
        const val ID_M2_MLSD = "m2_mlsd"
        const val ID_M1B_BOUNDARY_SEG = "m1b_boundary_seg"
        const val ID_M2_DEWARP = "m2_dewarp"

        /** SUBTASK H7: grid-output UVDoc (45x31) — primary dewarp path. */
        const val ID_M2_DEWARP_GRID = "m2_dewarp_grid"
        const val ID_M2P_DEWARP_PLUS = "m2p_dewarp_plus"
        const val ID_M2F_DEWARP_FALLBACK = "m2f_dewarp_fallback"
        const val ID_M3_SHADOW = "m3_shadow"

        private const val MB = 1024L * 1024L

        /**
         * REAL pinned download URL for the DocAligner LCNet100 corner detector (`m1c_detect`,
         * Apache-2.0). Resolves to the raw single-file ONNX artifact on Hugging Face (re-host of
         * DocsaidLab/DocAligner's `lcnet100_h_e_bifpn_256_fp32.onnx`).
         */
        const val M1C_DETECT_URL =
            "https://huggingface.co/7rplus/pagescan-weights/resolve/main/lcnet100_h_e_bifpn_256_fp32.onnx"

        /**
         * REAL pinned SHA-256 of [M1C_DETECT_URL]. Verified independently with
         * `certutil -hashfile <file> SHA256`; equals the Hugging Face LFS ETag
         * (X-Linked-ETag) for the file.
         */
        const val M1C_DETECT_SHA256 =
            "f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e"

        /**
         * REAL pinned download URL for the boundary model (`m1_boundary`) — the OpenCV Zoo
         * DexiNed ONNX export (MIT). Resolves to the raw Git-LFS artifact on Hugging Face.
         */
        const val M1_BOUNDARY_URL =
            "https://huggingface.co/opencv/edge_detection_dexined/resolve/main/edge_detection_dexined_2024sep.onnx"

        /**
         * REAL pinned SHA-256 of [M1_BOUNDARY_URL]. Verified independently with
         * `certutil -hashfile <file> SHA256`; equals the upstream Git-LFS object id.
         */
        const val M1_BOUNDARY_SHA256 =
            "a50d01dc8481549c7dedb9eb3e0123b810a016520df75e4669a504609982cdd0"

        /**
         * REAL pinned download URL for the primary dewarp model (`m2_dewarp`) — the PaddlePaddle
         * UVDoc ONNX export (Apache-2.0; UVDoc reference implementation is MIT). Resolves to the
         * raw single-file artifact on Hugging Face (no external data sidecar).
         */
        const val M2_DEWARP_URL =
            "https://huggingface.co/PaddlePaddle/UVDoc_onnx/resolve/main/inference.onnx"

        /**
         * REAL pinned SHA-256 of [M2_DEWARP_URL], verified with `certutil -hashfile <file> SHA256`
         * and runtime-checked with onnxruntime (dynamic [N,3,H,W] input/output, output in [0,1]).
         */
        const val M2_DEWARP_SHA256 =
            "54cab30dc2cf347b4f3d6971c833b1c3d84f5ef17280f1f6d15796bff20e63d6"

        // --- SUBTASK H7: UVDoc 45x31 grid export (graph + external-data sidecar) --------------
        /** Graph file name — the path returned by `ModelDownloadManager.modelFile()`. */
        const val M2_DEWARP_GRID_GRAPH_NAME = "UVDoc_grid.onnx"

        /** External-data sidecar name referenced by the graph (relative to the model dir). */
        const val M2_DEWARP_GRID_DATA_NAME = "UVDoc_grid.onnx.data"

        const val M2_DEWARP_GRID_URL =
            "https://huggingface.co/fredcallagan/uvdoc-grid-onnx/resolve/main/UVDoc_grid.onnx"

        const val M2_DEWARP_GRID_SHA256 =
            "044b406398e8accf2b8c896043f22d318221e443bb095c55d184e97f3eb003f7"

        const val M2_DEWARP_GRID_DATA_URL =
            "https://huggingface.co/fredcallagan/uvdoc-grid-onnx/resolve/main/UVDoc_grid.onnx.data"

        const val M2_DEWARP_GRID_DATA_SHA256 =
            "3a58a04944e59578200d7b0ed02ccb3ade934e9d88efa21454fbb8b53fa40f02"

        const val M2_DEWARP_GRID_GRAPH_BYTES = 242_890L
        const val M2_DEWARP_GRID_DATA_BYTES = 31_588_352L
        const val M2_DEWARP_GRID_TOTAL_BYTES =
            M2_DEWARP_GRID_GRAPH_BYTES + M2_DEWARP_GRID_DATA_BYTES

        /**
         * TODO(model-prep): real pinned base URL for the remaining (dewarp/enhance) models.
         * No canonical ONNX artifact is published for UVDoc/DRCNet/DewarpNet WC/DocShadow yet,
         * so these stay guarded placeholders and the app runs with their classical fallbacks.
         */
        const val PLACEHOLDER_BASE_URL = "https://REPLACE_ME.invalid/docscanai-models"

        /**
         * TODO(model-prep): replace with the real pinned SHA-256 hex digest at fetch time.
         */
        const val PLACEHOLDER_SHA256 = "REPLACE_WITH_SHA256"
    }
}
