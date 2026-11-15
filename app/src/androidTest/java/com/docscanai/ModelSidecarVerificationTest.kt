package com.docscanai.models

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================================
 * SUBTASK H10 — corrupted-sidecar rejection on the REAL downloader verification contract.
 * ============================================================================================
 *
 * Uses the production verifier ([ModelDownloadWorker.ModelFileVerifier]) — the same function the
 * download worker calls for every file — rather than a re-implementation:
 *  - a truncated COPY (1 MB) of the provisioned `UVDoc_grid.onnx.data` sidecar, placed in a
 *    scratch dir, is rejected with the typed [ModelFailure.SIZE_MISMATCH];
 *  - a same-size copy with one flipped byte is rejected with [ModelFailure.SHA_MISMATCH];
 *  - the failure-cleanup path deletes the rejected `*.part` AND the model dir, leaving NO
 *    partial file behind;
 *  - the PROVISIONED files are never touched (exact byte count re-checked afterwards).
 */
@RunWith(AndroidJUnit4::class)
class ModelSidecarVerificationTest {

    private lateinit var context: Context
    private lateinit var scratch: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        scratch = File(context.cacheDir, "sidecar_verify_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        runCatching { scratch.deleteRecursively() }
    }

    @Test
    fun truncatedAndCorruptedSidecar_areRejected_typed_andLeaveNoPartial() {
        val spec = ModelRegistry().byId(ModelRegistry.ID_M2_DEWARP_GRID)
        assertTrue("m2_dewarp_grid missing from the registry", spec != null)
        val dataFile = spec!!.allFiles()
            .first { it.name == ModelRegistry.M2_DEWARP_GRID_DATA_NAME }

        val provisionedDir = File(File(context.filesDir, ModelDownloadManager.MODELS_DIR), spec.id)
        val provisionedSidecar = File(provisionedDir, dataFile.name)
        assertTrue(
            "provisioned sidecar not found at ${provisionedSidecar.absolutePath}; run " +
                "tooling/_provision_models.cmd first",
            provisionedSidecar.isFile
        )
        val provisionedLength = provisionedSidecar.length()
        assertEquals(
            "provisioned sidecar size differs from the pinned byte count",
            ModelRegistry.M2_DEWARP_GRID_DATA_BYTES,
            provisionedLength
        )

        // Positive control: the real artifact passes verification.
        val control = ModelDownloadWorker.ModelFileVerifier.verify(provisionedSidecar, dataFile)
        assertNull("provisioned sidecar unexpectedly failed verification: $control", control)
        Log.i(TAG, "SIDECAR_CHECK control=PASS file=${dataFile.name} bytes=$provisionedLength")

        // --- 1) truncated copy (scratch only) ---------------------------------------------
        val truncatedDir = File(scratch, "truncated/${spec.id}").apply { mkdirs() }
        val truncated = File(truncatedDir, dataFile.name + ".part")
        copyPrefix(provisionedSidecar, truncated, TRUNCATED_BYTES)
        assertEquals(TRUNCATED_BYTES, truncated.length())
        val sizeFailure = ModelDownloadWorker.ModelFileVerifier.verify(truncated, dataFile)
        assertEquals(
            "truncated sidecar must be SIZE_MISMATCH",
            ModelFailure.SIZE_MISMATCH,
            sizeFailure
        )
        Log.i(
            TAG,
            "SIDECAR_CHECK truncated bytes=${truncated.length()} expected=${dataFile.bytes} " +
                "failure=${sizeFailure!!.name}"
        )
        // The worker's failure branch: reject the .part and delete the model dir.
        ModelDownloadWorker.ModelFileVerifier.rejectFailedPartial(truncated, truncatedDir)
        assertFalse("truncated .part still exists after rejection", truncated.exists())
        assertFalse("truncated model dir still exists after rejection", truncatedDir.exists())

        // --- 2) same-size copy with one flipped byte --------------------------------------
        val corruptDir = File(scratch, "corrupt/${spec.id}").apply { mkdirs() }
        val corrupt = File(corruptDir, dataFile.name + ".part")
        provisionedSidecar.copyTo(corrupt, overwrite = true)
        assertEquals(dataFile.bytes, corrupt.length())
        RandomAccessFile(corrupt, "rw").use { raf ->
            raf.seek(4096)
            val original = raf.read()
            raf.seek(4096)
            raf.write(original xor 0xFF)
        }
        val shaFailure = ModelDownloadWorker.ModelFileVerifier.verify(corrupt, dataFile)
        assertEquals(
            "same-size corrupted sidecar must be SHA_MISMATCH",
            ModelFailure.SHA_MISMATCH,
            shaFailure
        )
        Log.i(
            TAG,
            "SIDECAR_CHECK corrupted bytes=${corrupt.length()} failure=${shaFailure!!.name}"
        )

        // --- 3) failure cleanup leaves no partial file behind -----------------------------
        ModelDownloadWorker.ModelFileVerifier.rejectFailedPartial(corrupt, corruptDir)
        assertFalse("rejected .part still exists", corrupt.exists())
        assertFalse("model dir still exists after a typed failure", corruptDir.exists())
        val leftovers = scratch.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".tmp")) }
            .toList()
        assertTrue("partial/tmp files left behind: $leftovers", leftovers.isEmpty())
        Log.i(
            TAG,
            "SIDECAR_CHECK cleanup=PASS truncatedPartialExists=${truncated.exists()} " +
                "truncatedDirExists=${truncatedDir.exists()} " +
                "corruptPartialExists=${corrupt.exists()} corruptDirExists=${corruptDir.exists()} " +
                "leftovers=${leftovers.size}"
        )

        // Provisioned files are untouched.
        assertTrue("provisioned sidecar disappeared", provisionedSidecar.isFile)
        assertEquals(provisionedLength, provisionedSidecar.length())
        assertTrue("provisioned graph disappeared", File(provisionedDir, spec.fileName).isFile)
        Log.i(TAG, "SIDECAR_CHECK provisioned=UNTOUCHED bytes=${provisionedSidecar.length()}")
    }

    private fun copyPrefix(src: File, dst: File, bytes: Long) {
        src.inputStream().use { input ->
            FileOutputStream(dst).use { out ->
                val buffer = ByteArray(64 * 1024)
                var remaining = bytes
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    companion object {
        private const val TAG = "DocScan"
        private const val TRUNCATED_BYTES = 1_000_000L
    }
}
