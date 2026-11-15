package com.docscanai.imaging

import android.graphics.Bitmap
import com.docscanai.core.util.Diag
import com.docscanai.ml.OnnxModel
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * // M2 UVDoc — optional neural dewarp stage (MODELS.md §3.1).
 *
 * Runs a downloaded dewarp model (UVDoc `m2_dewarp` by default; any of the registry's dewarp
 * models via the injected provider) and returns a flattened `CV_8UC4` [`Mat`], or `null` when no
 * model is available / inference fails (the caller then keeps the perspective + classical result).
 *
 * ### Crash-hardening (SUBTASK 9c)
 * The previous implementation could feed a too-large / mis-sized tensor to the ORT graph and blindly
 * reshape whatever came back — a recipe for native (uncatchable) faults and `reshape` exceptions.
 * This class is now **fail-safe by construction**:
 *  - the working input long edge is capped at [MAX_INPUT_LONG_EDGE] (≤ 1024) and — for dynamic
 *    graphs — the dimensions are forced to a **multiple of 32**, then the result is upscaled back;
 *  - a fixed-shape graph is matched **exactly** (resize to its declared `H×W`), and a fixed input
 *    larger than the cap is skipped in favour of the classical fallback;
 *  - the input buffer is allocated with the **exact** `N*3*H*W` element count (no over-allocation);
 *  - the declared output shape is validated against the **exact** flat result length; on any
 *    mismatch the method returns `null` (classical fallback) instead of reshaping blindly;
 *  - the graph input **and** output shapes/dtypes are logged under the stable `DocScan` tag;
 *  - OpenCV remap maps are built as [`Mat`]s and released in `finally`;
 *  - **every** ONNX/OpenCV call is wrapped in `try/catch (Throwable)` (incl. `OutOfMemoryError`)
 *    and degrades to `null`.
 *
 * ### Supported output layouts (auto-detected from the *validated* declared shape)
 *  - **`[1,3,H,W]` image→image** rectified page → used directly (resized to the page). This is the
 *    pinned **UVDoc** export (see `MODEL_SOURCES.md`).
 *  - **`[1,Gh,Gw,2]` coordinate grid** → upsampled and applied with [`Imgproc.remap`].
 *  - **`[1,2,H,W]` source-coordinate maps** (e.g. DewarpNet WC) → applied with [`Imgproc.remap`].
 *
 * @param modelProvider lazily supplies the image→image dewarp [`OnnxModel`], or `null`.
 * @param gridModelProvider SUBTASK H7: lazily supplies the 45×31 grid [`OnnxModel`]
 *   (`m2_dewarp_grid`), or `null` when unavailable. When present and valid it is the PRIMARY
 *   path — see [dewarpGrid].
 */
class NeuralGridDewarp(
    private val modelProvider: (() -> OnnxModel?)? = null,
    private val gridModelProvider: (() -> OnnxModel?)? = null
) {

    @Volatile private var closed = false
    @Volatile private var model: OnnxModel? = null
    @Volatile private var gridModel: OnnxModel? = null
    private val lock = Any()

    /**
     * Image→image FALLBACK (SUBTASK H7 STEP 4): runs the pinned `m2_dewarp` UVDoc export on the
     * already perspective-corrected [src] at the UVDoc reference working size (~712 px long edge),
     * NOT at 2048 px. Used only when the grid model is missing/invalid or its inference failed.
     */
    fun dewarp(src: Mat): Mat? {
        if (src.empty()) return null
        val m = resolve() ?: return null
        val grid = try {
            resolveGrid()
        } catch (t: Throwable) {
            null
        }
        Diag.log(
            "Dewarp",
            "uv=image model=m2_dewarp edge=$UV_DOC_LONG_EDGE " +
                (if (grid == null) "(grid unavailable)" else "(grid failed)")
        )
        val start = System.currentTimeMillis()
        Diag.stageEnter("NeuralDewarp", "src=${src.cols()}x${src.rows()} model=${m.file.name}")
        return try {
            val out = run(m, src)
            Diag.stageExit(
                "NeuralDewarp", System.currentTimeMillis() - start,
                "out=" + (out?.let { "${it.cols()}x${it.rows()}" } ?: "null")
            )
            out
        } catch (t: Throwable) {
            // Catch Throwable (incl. OutOfMemoryError) so a neural failure never kills the app.
            Diag.logThrowable("NeuralDewarp", t)
            null
        }
    }

    /** Closes both ORT sessions (idempotent). */
    fun release() {
        synchronized(lock) {
            runCatching { model?.close() }
            runCatching { gridModel?.close() }
            model = null
            gridModel = null
            closed = true
        }
    }

    /**
     * Resolves the dewarp model via [modelProvider]. A `null` result is **not** latched so that the
     * runtime `neuralProcessingEnabled` switch can take effect later (the provider returns `null`
     * while disabled, and yields the lazily-loaded model again once re-enabled). Never throws.
     */
    private fun resolve(): OnnxModel? {
        model?.let { return it }
        if (closed) return null
        synchronized(lock) {
            model?.let { return it }
            if (closed) return null
            val loaded = try {
                modelProvider?.invoke()
            } catch (t: Throwable) {
                Diag.logThrowable("NeuralDewarp", t)
                null
            }
            if (loaded != null) model = loaded
            return loaded
        }
    }

    /** Lazy grid model resolution (SUBTASK H7); `null` keeps the latch open like [resolve]. */
    private fun resolveGrid(): OnnxModel? {
        gridModel?.let { return it }
        if (closed) return null
        synchronized(lock) {
            gridModel?.let { return it }
            if (closed) return null
            val loaded = try {
                gridModelProvider?.invoke()
            } catch (t: Throwable) {
                Diag.logThrowable("NeuralDewarp", t)
                null
            }
            if (loaded != null) gridModel = loaded
            return loaded
        }
    }

    // --- inference -------------------------------------------------------------------------

    private fun run(model: OnnxModel, src: Mat): Mat? {
        return try {
            // Log the exact graph contract once per call so the crash investigation sees everything.
            Diag.log(
                TAG,
                "graph inputShape=${shapeString(model.inputShape)} inputType=float32 " +
                    "outputShape=${shapeString(model.outputShape)} outputType=float32"
            )

            // Bound the model input size (memory safety + graph-shape match).
            val size = inputSize(model, src) ?: return null
            val (inW, inH) = size
            if (inW <= 0 || inH <= 0) {
                Diag.warn(TAG, "non-positive input size ${inW}x${inH}; skipping neural")
                return null
            }
            // Exact N*3*H*W element count (N = 1). Long math guards against int overflow.
            val elements = 3L * inW.toLong() * inH.toLong()
            if (elements <= 0L || elements > MAX_INPUT_ELEMENTS) {
                Diag.warn(TAG, "unreasonable input ${inW}x${inH} ($elements elements); skipping neural")
                return null
            }
            Diag.log(
                TAG,
                "run input=${inW}x${inH} elements=$elements src=${src.cols()}x${src.rows()} " +
                    Diag.memString()
            )

            val resized = Mat()
            try {
                Imgproc.resize(src, resized, Size(inW.toDouble(), inH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                if (!resized.isContinuous) {
                    Diag.warn(TAG, "resized Mat not continuous; skipping neural")
                    return null
                }

                val plane = inW * inH
                val bytes = ByteArray(plane * 4)
                resized.get(0, 0, bytes)

                // NCHW float32, RGB normalised to [0,1] (UVDoc / PaddleOCR convention). Allocate the
                // EXACT element count (3 * H * W) so the tensor matches the declared shape 1:1.
                val buffer = FloatBuffer.allocate(elements.toInt())
                for (i in 0 until plane) {
                    val b = i * 4
                    buffer.put(i, (bytes[b].toInt() and 0xFF) / 255f)
                    buffer.put(plane + i, (bytes[b + 1].toInt() and 0xFF) / 255f)
                    buffer.put(2 * plane + i, (bytes[b + 2].toInt() and 0xFF) / 255f)
                }
                buffer.rewind()

                val out = model.runFloat(
                    buffer,
                    longArrayOf(1L, 3L, inH.toLong(), inW.toLong()),
                    outputName = model.outputNames.firstOrNull()
                ) ?: return null

                Diag.log(TAG, "inference returned ${out.size} floats")
                interpret(model, out, src, inW, inH)
            } finally {
                resized.release()
            }
        } catch (t: Throwable) {
            Diag.logThrowable(TAG, t)
            null
        }
    }

    // --- SUBTASK H7: grid dewarp (ONE sample from the original still) -----------------------

    /**
     * PRIMARY H7 dewarp. Runs the 45×31 grid model on the perspective-corrected page rendered at
     * the model's fixed input size, then COMPOSES the grid with the 4-corner homography
     * ([sourceToPage]) and applies **one** `Imgproc.remap` directly to [original].
     *
     * Coordinate chain for every delivered page pixel:
     * `page(x,y) → grid normalized sample (u,v) → page pixel → H⁻¹ → original pixel`.
     * The grid is the model's output→input sampling field, so it composes with `H⁻¹` without any
     * intermediate page bitmap being delivered.
     *
     * @param original full-resolution original still (`CV_8UC4`).
     * @param sourceToPage 3×3 homography original→page pixels (`getPerspectiveTransform(src,rect)`).
     * @param pageW/pageH delivered page size at the original image scale.
     * @return flattened page, or `null` for ANY failure (caller then uses the image→image path).
     */
    fun dewarpGrid(
        original: Mat,
        sourceToPage: Mat,
        pageW: Int,
        pageH: Int
    ): Mat? = dewarpGridInternal(original, sourceToPage, pageW, pageH, GRID_REMAP_INTERP)

    /**
     * SUBTASK H15 STEP 3: interpolation A/B hook. Production always uses [GRID_REMAP_INTERP];
     * `DewarpFidelityTest` calls this with `INTER_CUBIC` and `INTER_LANCZOS4` on the SAME composed
     * map so the stroke-fidelity decision is measured, not assumed. No model/behaviour change.
     */
    internal fun dewarpGridForTest(
        original: Mat,
        sourceToPage: Mat,
        pageW: Int,
        pageH: Int,
        interp: Int
    ): Mat? = dewarpGridInternal(original, sourceToPage, pageW, pageH, interp)

    private fun dewarpGridInternal(
        original: Mat,
        sourceToPage: Mat,
        pageW: Int,
        pageH: Int,
        interp: Int
    ): Mat? {
        if (original.empty() || sourceToPage.empty()) return null
        if (pageW < 2 || pageH < 2) return null
        val m = resolveGrid() ?: return null
        val start = System.currentTimeMillis()
        Diag.stageEnter(
            "DewarpGrid",
            "src=${original.cols()}x${original.rows()} page=${pageW}x${pageH} model=${m.file.name}"
        )
        return try {
            val out = runGrid(m, original, sourceToPage, pageW, pageH, interp)
            Diag.stageExit(
                "DewarpGrid", System.currentTimeMillis() - start,
                "out=" + (out?.let { "${it.cols()}x${it.rows()}" } ?: "null")
            )
            out
        } catch (t: Throwable) {
            Diag.logThrowable("DewarpGrid", t)
            null
        }
    }

    private fun runGrid(
        model: OnnxModel,
        original: Mat,
        sourceToPage: Mat,
        pageW: Int,
        pageH: Int,
        interp: Int
    ): Mat? {
        val inShape = model.inputShape
        val outShape = model.outputShape
        if (inShape == null || inShape.size != 4 || inShape[1] != 3L ||
            inShape[2] <= 0L || inShape[3] <= 0L
        ) {
            Diag.warn("Dewarp", "grid input shape ${shapeString(inShape)} unsupported; fallback")
            return null
        }
        val inH = inShape[2].toInt()
        val inW = inShape[3].toInt()
        if (outShape == null || outShape.size != 4 || outShape[1] != 2L ||
            outShape[2] <= 0L || outShape[3] <= 0L
        ) {
            Diag.warn("Dewarp", "grid output shape ${shapeString(outShape)} unsupported; fallback")
            return null
        }
        val gh = outShape[2].toInt()
        val gw = outShape[3].toInt()
        if (gh < 2 || gw < 2) return null

        val scaleM = Mat(3, 3, CvType.CV_64F)
        val modelM = Mat()
        val pageSmall = Mat()
        val smallX = Mat()
        val smallY = Mat()
        val normX = Mat()
        val normY = Mat()
        val hInv = Mat()
        val dst = Mat()
        var ok = false
        return try {
            // 1) Render the perspective-corrected page at the model's fixed input size DIRECTLY
            //    from the original still: M' = S · H, S = diag(inW/pageW, inH/pageH, 1).
            val sx = inW.toDouble() / pageW.toDouble()
            val sy = inH.toDouble() / pageH.toDouble()
            scaleM.put(
                0, 0,
                *doubleArrayOf(
                    sx, 0.0, 0.0,
                    0.0, sy, 0.0,
                    0.0, 0.0, 1.0
                )
            )
            Core.gemm(scaleM, sourceToPage, 1.0, Mat(), 0.0, modelM)
            Imgproc.warpPerspective(
                original, pageSmall, modelM,
                Size(inW.toDouble(), inH.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE
            )
            if (!pageSmall.isContinuous) {
                Diag.warn("Dewarp", "grid page render not continuous; fallback")
                return null
            }

            // 2) RGB [0,1] NCHW, exact 3·H·W elements.
            val plane = inW * inH
            val bytes = ByteArray(plane * 4)
            pageSmall.get(0, 0, bytes)
            val buffer = FloatBuffer.allocate(3 * plane)
            for (i in 0 until plane) {
                val b = i * 4
                buffer.put(i, (bytes[b].toInt() and 0xFF) / 255f)
                buffer.put(plane + i, (bytes[b + 1].toInt() and 0xFF) / 255f)
                buffer.put(2 * plane + i, (bytes[b + 2].toInt() and 0xFF) / 255f)
            }
            buffer.rewind()

            val out = model.runFloat(
                buffer,
                longArrayOf(1L, 3L, inH.toLong(), inW.toLong()),
                outputName = model.outputNames.firstOrNull()
            ) ?: return null

            val expected = 2L * gh.toLong() * gw.toLong()
            if (out.size.toLong() != expected) {
                Diag.warn("Dewarp", "grid output size ${out.size} != $expected; fallback")
                return null
            }

            // 3) Split NCHW [1,2,Gh,Gw]: channel 0 = x, channel 1 = y, both in [-1,1].
            val count = gh * gw
            val gx = FloatArray(count)
            val gy = FloatArray(count)
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (k in 0 until count) {
                val x = out[k]
                val y = out[count + k]
                if (!x.isFinite() || !y.isFinite()) {
                    Diag.warn("Dewarp", "grid contains non-finite values; fallback")
                    return null
                }
                // Contract is [-1,1]; anything wildly outside means the graph diverged.
                if (abs(x) > 4f || abs(y) > 4f) {
                    Diag.warn("Dewarp", "grid out of range (x=$x y=$y); fallback")
                    return null
                }
                gx[k] = x
                gy[k] = y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            val spanX = maxX - minX
            val spanY = maxY - minY
            // A near-constant grid would deliver a flat page — reject it.
            if (spanX < MIN_GRID_SPAN || spanY < MIN_GRID_SPAN) {
                Diag.warn("Dewarp", "degenerate grid spanX=$spanX spanY=$spanY; fallback")
                return null
            }

            smallX.create(gh, gw, CvType.CV_32FC1)
            smallY.create(gh, gw, CvType.CV_32FC1)
            smallX.put(0, 0, gx)
            smallY.put(0, 0, gy)

            val pagePixels = pageW.toLong() * pageH.toLong()
            if (pagePixels <= 0L || pagePixels > MAX_PAGE_PIXELS) {
                Diag.warn("Dewarp", "page ${pageW}x$pageH exceeds map budget; fallback")
                return null
            }
            // Invert the homography once: both the raw and the smoothed path need H⁻¹.
            // OpenCV's Java binding returns the determinant (double), not a boolean.
            val det = Core.invert(sourceToPage, hInv)
            if (!det.isFinite() || det == 0.0) {
                Diag.warn("Dewarp", "singular homography inversion (det=$det); fallback")
                return null
            }
            val hInvArr = DoubleArray(9)
            hInv.get(0, 0, hInvArr)
            if (hInvArr.any { !it.isFinite() }) {
                Diag.warn("Dewarp", "inverted homography non-finite; fallback")
                return null
            }
            // Measure curvature on the small 45×31 grid (logging only), then apply the adaptive
            // policy. SUBTASK H16 STEP 1: `activeFit()` selects the smooth residual reconstruction;
            // `raw` keeps the 0.3.3 upsampled-grid behavior available for A/B and rollback.
            val curvature = WarpPolicy.curvature(gx, gy, gw, gh, hInvArr, pageW, pageH)
            val fit = WarpPolicy.activeFit()
            val mapX = FloatArray(pagePixels.toInt())
            val mapY = FloatArray(pagePixels.toInt())
            val applied: WarpPolicy.Applied
            if (fit == WarpPolicy.FIT_RAW) {
                // 4) Upsample 45×31 → delivered page size, then [-1,1] → [0,1].
                Imgproc.resize(
                    smallX, normX, Size(pageW.toDouble(), pageH.toDouble()),
                    0.0, 0.0, GRID_EDGE_INTERP
                )
                Imgproc.resize(
                    smallY, normY, Size(pageW.toDouble(), pageH.toDouble()),
                    0.0, 0.0, GRID_EDGE_INTERP
                )
                Core.add(normX, Scalar(1.0), normX)
                Core.multiply(normX, Scalar(0.5), normX)
                Core.add(normY, Scalar(1.0), normY)
                Core.multiply(normY, Scalar(0.5), normY)
                normX.get(0, 0, mapX)
                normY.get(0, 0, mapY)
                val xMax = (pageW - 1).toDouble()
                val yMax = (pageH - 1).toDouble()
                // [0,1] normalized grid sample positions → page pixels (grid-requested samples).
                for (i in mapX.indices) {
                    val px = mapX[i].toDouble() * xMax
                    val py = mapY[i].toDouble() * yMax
                    if (!px.isFinite() || !py.isFinite()) {
                        Diag.warn("Dewarp", "grid sample coordinate non-finite; fallback")
                        return null
                    }
                    mapX[i] = px.toFloat()
                    mapY[i] = py.toFloat()
                }
                applied = WarpPolicy.applyAdaptive(
                    mapX, mapY, pageW, pageH, hInvArr, curvature
                )
            } else {
                // 5) Smooth residual reconstruction at output resolution. The residual is measured
                //    at the native gw×gh control points and fitted before it is applied, so the
                //    nonlinear H⁻¹ is evaluated on the smooth field, not after raw interpolation.
                applied = WarpPolicy.applyAdaptiveSmoothed(
                    gx, gy, mapX, mapY, pageW, pageH, gw, gh, hInvArr, curvature, fit
                )
            }
            val st = applied.stats
            Diag.log(
                "Warp",
                "residual fit=$fit rms=${fmt4(st.rms)} mean=${fmt4(st.mean)} " +
                    "max=${fmt4(st.max)} p95=${fmt4(st.p95)} curv=${fmt4(st.curvature)}"
            )
            if (applied.strength <= 0.0) {
                Diag.log(
                    "Warp",
                    "residual suppressed (flat) mean=${fmt4(st.mean)} max=${fmt4(st.max)}"
                )
                Diag.log(
                    "Warp",
                    "strength=0.00 (flat) mean=${fmt4(st.mean)} max=${fmt4(st.max)}"
                )
            } else {
                Diag.log(
                    "Warp",
                    "strength=${fmt2(applied.strength)} mean=${fmt4(st.mean)} " +
                        "max=${fmt4(st.max)}"
                )
            }
            if (applied.clampedVectors > 0) {
                Diag.log(
                    "Warp",
                    "clamp vectors=${applied.clampedVectors} " +
                        "limit=${fmt4(WarpPolicy.RESIDUAL_MAX_CLAMP)}"
                )
            }
            // SUBTASK H16 STEP 3: output geometry sanity. The page size comes from the projected
            // source edge lengths, so the delivered long edge must never exceed the source long
            // edge (no invented pixels), and the composed sampling map must be fold-free.
            val outLong = max(pageW, pageH)
            val srcLong = max(original.cols(), original.rows())
            val minJac = WarpPolicy.minJacobian(mapX, mapY, pageW, pageH)
            Diag.log(
                "Warp",
                "out=${pageW}x${pageH} srcDensity=1.000 px/srcPx " +
                    "upsampled=${outLong > srcLong} minJacobian=${fmt4(minJac)}"
            )
            if (outLong > srcLong) {
                Diag.warn("Dewarp", "output $outLong > source $srcLong; refusing invented pixels")
                return null
            }
            if (!minJac.isFinite() || minJac <= 0.0) {
                Diag.warn("Dewarp", "composed map folds (minJacobian=$minJac); fallback")
                return null
            }
            // The raw branch already sized these via Imgproc.resize; the smoothed branch writes
            // the final sampling coordinates directly, so size them here for both paths.
            normX.create(pageH, pageW, CvType.CV_32FC1)
            normY.create(pageH, pageW, CvType.CV_32FC1)
            normX.put(0, 0, mapX)
            normY.put(0, 0, mapY)

            // 6) ONE remap straight from the original still. No intermediate page bitmap is
            //    delivered — this is the core fix over the image→image path.
            Diag.log(
                "Dewarp",
                "uv=grid model=m2_dewarp_grid out=[1,2,$gh,$gw] " +
                    "map=${pageW}x${pageH} from=${original.cols()}x${original.rows()}"
            )
            Imgproc.remap(
                original, dst, normX, normY,
                interp, Core.BORDER_REPLICATE
            )
            Diag.log("Dewarp", "single-sample remap ok")
            ok = true
            dst
        } catch (t: Throwable) {
            Diag.logThrowable("Dewarp", t)
            null
        } finally {
            scaleM.release(); modelM.release(); pageSmall.release()
            smallX.release(); smallY.release(); normX.release(); normY.release()
            hInv.release()
            if (!ok) dst.release()
        }
    }

    /**
     * Resolves the model input size.
     *  - **Fixed graph** (`[1,3,H,W]` with positive spatial dims): use those dims **exactly**; if
     *    the long edge exceeds [MAX_INPUT_LONG_EDGE] skip the neural path (a large fixed input would
     *    allocate a huge native tensor).
     *  - **Dynamic graph** (e.g. the pinned UVDoc export): keep the aspect ratio, cap the long edge
     *    at [UV_DOC_LONG_EDGE] and force both dimensions to a **multiple of 8**; the result is
     *    upscaled back to the page afterwards. H7: the cap is the UVDoc reference size
     *    (712×488) instead of 2048 px.
     */
    private fun inputSize(model: OnnxModel, src: Mat): Pair<Int, Int>? {
        val shape = model.inputShape
        if (shape != null && shape.size == 4 && shape[2] > 0 && shape[3] > 0) {
            val w = shape[3].toInt()
            val h = shape[2].toInt()
            if (max(w, h) > MAX_INPUT_LONG_EDGE) {
                Diag.warn(
                    TAG,
                    "neural dewarp fixed input ${w}x${h} > cap $MAX_INPUT_LONG_EDGE; skipping neural"
                )
                return null
            }
            return w to h
        }
        // Dynamic graph: keep aspect, cap the long edge, force a multiple of 8.
        val srcLong = max(src.cols(), src.rows()).coerceAtLeast(1)
        val targetLong = min(min(UV_DOC_LONG_EDGE, srcLong), MAX_INPUT_LONG_EDGE).coerceAtLeast(8)
        val scale = targetLong.toDouble() / srcLong.toDouble()
        var w = roundToMultipleOf8(src.cols() * scale)
        var h = roundToMultipleOf8(src.rows() * scale)
        // Defensive: rounding could nudge the long edge over the cap — clamp and re-round.
        val longEdge = max(w, h)
        if (longEdge > MAX_INPUT_LONG_EDGE) {
            val s = MAX_INPUT_LONG_EDGE.toDouble() / longEdge.toDouble()
            w = roundToMultipleOf8(w * s)
            h = roundToMultipleOf8(h * s)
        }
        return w to h
    }

    /**
     * Chooses the post-processing from the declared output shape. When the shape is fully known the
     * **flat result length is validated against the exact product of the declared dims**; any
     * mismatch returns `null` (classical fallback) instead of reshaping blindly.
     */
    private fun interpret(model: OnnxModel, out: FloatArray, src: Mat, inW: Int, inH: Int): Mat? {
        return try {
            val shape = model.outputShape
            if (shape != null && shape.isNotEmpty() && shape.all { it > 0 }) {
                val expected = shape.fold(1L) { acc, d -> acc * d }
                if (expected != out.size.toLong()) {
                    Diag.warn(
                        TAG,
                        "output element mismatch: declared=$expected actual=${out.size}; skipping neural"
                    )
                    return null
                }
                if (shape.size != 4) {
                    Diag.warn(TAG, "unsupported output rank ${shape.size}; skipping neural")
                    return null
                }
                val channels = shape[1].toInt()
                when {
                    shape[3] == 2L -> remapFromGrid(out, shape[1].toInt(), shape[2].toInt(), src)
                    channels == 2 -> remapFromMaps(out, shape[2].toInt(), shape[3].toInt(), src)
                    channels == 3 -> imageFromOutput(out, shape[2].toInt(), shape[3].toInt(), src)
                    else -> {
                        Diag.warn(TAG, "unrecognised output channels=$channels; skipping neural")
                        null
                    }
                }
            } else {
                // Dynamic output: infer the layout from the flat length using EXACT matches only.
                val n = out.size
                when {
                    n == 3 * inW * inH -> imageFromOutput(out, inH, inW, src)
                    n == 2 * inW * inH -> remapFromMaps(out, inH, inW, src)
                    n % 2 == 0 -> {
                        val g = isqrt(n / 2)
                        if (g > 1 && g * g == n / 2) remapFromGrid(out, g, g, src) else null
                    }
                    else -> null
                }
            }
        } catch (t: Throwable) {
            Diag.logThrowable(TAG, t)
            null
        }
    }

    /** Image→image output: build a bitmap from the rectified RGB planes and resize to the page. */
    private fun imageFromOutput(out: FloatArray, mh: Int, mw: Int, src: Mat): Mat? {
        return try {
            if (mw <= 0 || mh <= 0) return null
            val plane = mh.toLong() * mw.toLong()
            if (plane <= 0L || plane > MAX_OUTPUT_PLANE) {
                Diag.warn(TAG, "image output plane $plane out of range; skipping neural")
                return null
            }
            val planeI = plane.toInt()
            // Exact 3-channel requirement (validated again here for local safety).
            if (out.size != 3 * planeI) {
                Diag.warn(TAG, "image output size ${out.size} != 3*$planeI; skipping neural")
                return null
            }

            var maxV = 0f
            for (v in out) if (v > maxV) maxV = v
            val gain = if (maxV <= 1.5f) 255f else 1f

            val pixels = IntArray(planeI)
            for (i in 0 until planeI) {
                val r = (out[i] * gain).roundToInt().coerceIn(0, 255)
                val g = (out[planeI + i] * gain).roundToInt().coerceIn(0, 255)
                val b = (out[2 * planeI + i] * gain).roundToInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            var bitmap: Bitmap? = null
            val rgb = Mat()
            val dst = Mat()
            var ok = false
            try {
                bitmap = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixels, 0, mw, 0, 0, mw, mh)
                Utils.bitmapToMat(bitmap, rgb)
                // INTER_LANCZOS4 keeps the most high-frequency energy for the fallback upscale
                // from the 712 px reference model output back to the page size.
                Imgproc.resize(rgb, dst, Size(src.cols().toDouble(), src.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_LANCZOS4)

                // SUBTASK H7 STEP 3: detail restoration is OFF by default (DETAIL_RESTORE_AMOUNT =
                // 0.0). Unsharp masking cannot recreate the image decoder's lost frequencies and can
                // thin strokes; it is only enabled after a measured sharpness ratio below 1.0.
                if (DETAIL_RESTORE_AMOUNT > 0f) {
                    val blurred = Mat()
                    try {
                        Imgproc.GaussianBlur(dst, blurred, Size(0.0, 0.0), DETAIL_RESTORE_SIGMA)
                        Core.addWeighted(
                            dst, 1.0 + DETAIL_RESTORE_AMOUNT,
                            blurred, -DETAIL_RESTORE_AMOUNT.toDouble(), 0.0, dst
                        )
                    } finally {
                        blurred.release()
                    }
                }

                ok = true
                // Deliverable proof: delivered page resolution vs the model's working resolution.
                Diag.log(
                    TAG,
                    "delivered page ${dst.cols()}x${dst.rows()} from model ${mw}x${mh} " +
                        "upscaleX=${String.format(java.util.Locale.US, "%.2f", src.cols().toDouble() / mw)} " +
                        "upscaleY=${String.format(java.util.Locale.US, "%.2f", src.rows().toDouble() / mh)} " +
                        "detail=" + (if (DETAIL_RESTORE_AMOUNT > 0f) "+$DETAIL_RESTORE_AMOUNT" else "off") +
                        " " + Diag.memString()
                )
                dst
            } finally {
                bitmap?.recycle()
                rgb.release()
                if (!ok) dst.release()
            }
        } catch (t: Throwable) {
            Diag.logThrowable(TAG, t)
            null
        }
    }

    /** `[1,Gh,Gw,2]` coordinate grid → normalised maps → `remap`. */
    private fun remapFromGrid(out: FloatArray, gh: Int, gw: Int, src: Mat): Mat? {
        return try {
            if (gh < 2 || gw < 2) return null
            val count = gh * gw
            if (count <= 0 || out.size != 2 * count) {
                Diag.warn(TAG, "grid output size ${out.size} != 2*$count; skipping neural")
                return null
            }
            val gx = FloatArray(count)
            val gy = FloatArray(count)
            var minV = Float.MAX_VALUE
            for (k in 0 until count) {
                val x = out[k * 2]
                val y = out[k * 2 + 1]
                gx[k] = x
                gy[k] = y
                if (x < minV) minV = x
                if (y < minV) minV = y
            }
            // Detect the coordinate convention: [-1,1] vs [0,1].
            val signed = minV < -0.5f
            if (signed) {
                for (k in 0 until count) {
                    gx[k] = (gx[k] + 1f) * 0.5f
                    gy[k] = (gy[k] + 1f) * 0.5f
                }
            }
            remapWithNormalizedFields(gx, gy, gw, gh, src)
        } catch (t: Throwable) {
            Diag.logThrowable(TAG, t)
            null
        }
    }

    /** `[1,2,H,W]` source-coordinate maps → `remap`. */
    private fun remapFromMaps(out: FloatArray, mh: Int, mw: Int, src: Mat): Mat? {
        return try {
            if (mh < 2 || mw < 2) return null
            val plane = mh * mw
            if (plane <= 0 || out.size != 2 * plane) {
                Diag.warn(TAG, "map output size ${out.size} != 2*$plane; skipping neural")
                return null
            }
            val gx = FloatArray(plane)
            val gy = FloatArray(plane)
            var minV = Float.MAX_VALUE
            for (i in 0 until plane) {
                gx[i] = out[i]
                gy[i] = out[plane + i]
                if (gx[i] < minV) minV = gx[i]
                if (gy[i] < minV) minV = gy[i]
            }
            val signed = minV < -0.5f
            if (signed) {
                for (i in 0 until plane) {
                    gx[i] = (gx[i] + 1f) * 0.5f
                    gy[i] = (gy[i] + 1f) * 0.5f
                }
            }
            remapWithNormalizedFields(gx, gy, mw, mh, src)
        } catch (t: Throwable) {
            Diag.logThrowable(TAG, t)
            null
        }
    }

    /**
     * Shared: normalised (0..1) coordinate fields → full-res pixel [`Mat`] maps → `remap` of [src].
     * Every intermediate `Mat` is released in `finally`; `dst` is released when the body fails.
     */
    private fun remapWithNormalizedFields(gx: FloatArray, gy: FloatArray, gw: Int, gh: Int, src: Mat): Mat? {
        if (gw <= 0 || gh <= 0) return null
        val count = gw * gh
        if (gx.size < count || gy.size < count) {
            Diag.warn(TAG, "remap field too small (need $count); skipping neural")
            return null
        }
        val smallX = Mat()
        val smallY = Mat()
        val mapX = Mat()
        val mapY = Mat()
        val dst = Mat()
        var ok = false
        return try {
            smallX.create(gh, gw, CvType.CV_32FC1)
            smallY.create(gh, gw, CvType.CV_32FC1)
            smallX.put(0, 0, gx)
            smallY.put(0, 0, gy)
            val w = src.cols()
            val h = src.rows()
            Imgproc.resize(smallX, mapX, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
            Imgproc.resize(smallY, mapY, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
            Core.multiply(mapX, Scalar(w - 1.0), mapX)
            Core.multiply(mapY, Scalar(h - 1.0), mapY)
            Imgproc.remap(src, dst, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            ok = true
            dst
        } catch (t: Throwable) {
            Diag.logThrowable(TAG, t)
            null
        } finally {
            smallX.release(); smallY.release(); mapX.release(); mapY.release()
            if (!ok) dst.release()
        }
    }

    /** Rounds to the nearest multiple of 8 (minimum 8). */
    private fun roundToMultipleOf8(value: Double): Int {
        val v = max(8.0, value)
        val rounded = (v / 8.0).roundToInt() * 8
        return rounded.coerceAtLeast(8)
    }

    private fun isqrt(n: Int): Int {
        if (n < 0) return -1
        var r = n.toDouble().let { if (it <= 0) 0 else kotlin.math.sqrt(it) }.toInt()
        while (r > 0 && r.toLong() * r > n) r--
        while ((r + 1).toLong() * (r + 1) <= n) r++
        return r
    }

    /** Compact, allocation-light shape string for logging (e.g. `[1,3,-1,-1]`). */
    private fun shapeString(shape: LongArray?): String =
        shape?.joinToString(prefix = "[", postfix = "]", separator = ",") ?: "null"

    /** Fixed 4-decimal formatting for the `[Warp] residual/strength` audit line. */
    private fun fmt4(v: Double): String =
        if (!v.isFinite()) "-1" else String.format(java.util.Locale.US, "%.4f", v)

    /** Fixed 2-decimal formatting for the chosen strength. */
    private fun fmt2(v: Double): String =
        if (!v.isFinite()) "0.00" else String.format(java.util.Locale.US, "%.2f", v)

    companion object {
        private const val TAG = "NeuralDewarp"

        /**
         * SUBTASK H7 STEP 4: working long edge fed to the **image→image fallback** graph.
         *
         * This is the UVDoc reference working size (712×488), NOT 2048 px. The H7 primary path is
         * the grid model, which never goes through this cap. Running the image decoder at 712 px
         * cuts the pixel count ~8.6× versus the old 2048 px deployment (638 MB native heap peak)
         * while keeping the fallback functional. Dimensions are rounded to a multiple of 8, so a
         * 712×488 page maps to exactly 712×488.
         */
        private const val UV_DOC_LONG_EDGE = 712

        /**
         * Hard cap (px) on the image→image model input long edge, regardless of graph/`src` size.
         * A fixed graph whose declared input exceeds this is skipped in favour of the classical
         * fallback; dynamic graphs are always resolved at/below the cap. (The 720×496 grid graph
         * is handled by the dedicated [dewarpGrid] path and is not subject to this cap.)
         */
        private const val MAX_INPUT_LONG_EDGE = 712

        /** Upper bound on the input element count (3 × 720 × 720) to reject absurd allocations. */
        private const val MAX_INPUT_ELEMENTS = 3L * 720L * 720L

        /** Upper bound on a single output channel plane (720 × 720). */
        private const val MAX_OUTPUT_PLANE = 720L * 720L

        /** Upper bound on the delivered page pixel count for the composed grid map. */
        private const val MAX_PAGE_PIXELS = 2001L * 2001L

        /** Minimum normalized span a valid UVDoc grid must have (rejects a degenerate/flat grid). */
        private const val MIN_GRID_SPAN = 0.20f

        /** Grid upsampling interpolation (45×31 → page). */
        private val GRID_EDGE_INTERP = Imgproc.INTER_CUBIC

        /**
         * SUBTASK H7 STEP 3 / H15 STEP 3: interpolation for the single composed remap from the
         * original still. H15 re-measured the same composed map on 4 benchmark captures:
         *
         *   INTER_CUBIC    sharp=2190.63 straight=21.63517
         *   INTER_LANCZOS4 sharp=2130.39 straight=21.63551
         *
         * CUBIC is both sharper (+2.8 %) and gentler on high-contrast strokes (LANCZOS4 can ring),
         * while straightness is identical to 4 decimal places. One constant, chosen by
         * measurement — `DewarpFidelityTest#interpolationComparison_benchmarkStrokes` logs both.
         */
        private val GRID_REMAP_INTERP = Imgproc.INTER_CUBIC

        /** H15: the production interpolation, exposed so the fidelity test can pin the choice. */
        internal fun productionRemapInterp(): Int = GRID_REMAP_INTERP

        /**
         * SUBTASK H7 STEP 3: detail restoration after the single remap. **OFF by default** (0.0).
         * The grid path samples the original pixels exactly, so no unsharp is needed; only a
         * measured sharpness ratio below 1.0 would justify a mild (>0, ≤0.2) amount.
         */
        private const val DETAIL_RESTORE_AMOUNT = 0.0f

        /** Gaussian sigma of the optional detail-restoration mask. */
        private const val DETAIL_RESTORE_SIGMA = 1.0
    }
}
