package com.docscanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.core.di.ServiceLocator
import com.docscanai.data.ScanDocument
import com.docscanai.data.ScanSessionRepository
import com.docscanai.data.ScannedPage
import com.docscanai.data.SessionStore
import com.docscanai.ui.screens.review.PageReviewScreen
import com.docscanai.ui.screens.viewer.PageViewerContent
import com.docscanai.ui.screens.viewer.ViewerTags
import com.docscanai.ui.session.ScanSessionViewModel
import com.docscanai.ui.theme.DocScanTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================================
 * SUBTASK F — full-screen captured-page viewer UI tests.
 * ============================================================================================
 *
 * Drives the real Compose viewer ([PageViewerContent]) on the emulator with a synthetic session
 * fixture (no camera, no ViewModel needed) and asserts:
 *  - it renders the processed page image;
 *  - pinch-zoom / drag-pan / double-tap and the pager swipe do NOT crash;
 *  - the multi-page pager advances the index;
 *  - the processed ↔ original toggle works when a raw capture exists;
 *  - a missing/corrupt page file shows a clean error state (never a crash).
 *
 * It also writes PNG screenshots to the app's external files dir (`adb pull`-able) for the user:
 *   viewer_session.png, viewer_thumbnail.png, viewer_fullscreen.png, viewer_zoomed.png.
 */
@RunWith(AndroidJUnit4::class)
class PageViewerUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var workDir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        ServiceLocator.init(context)
        workDir = File(context.cacheDir, "viewer_ui_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        runCatching { workDir.deleteRecursively() }
    }

    // --- tests ------------------------------------------------------------------------------

    @Test
    fun viewer_rendersPageAndGesturesDoNotCrash() {
        val image = writeImage("page1.png", Color.RED)
        val pages = listOf(page(id = "p1", image = image))

        rule.setContent { DocScanTheme { PageViewerContent(pages, startIndex = 0, onClose = {}) } }
        waitForImage()
        rule.onNodeWithTag(ViewerTags.IMAGE).assertExists()
        rule.onNodeWithTag(ViewerTags.PAGE_INDICATOR).assertTextEquals("1 / 1")
        saveScreenshot("viewer_fullscreen.png")

        // Double-tap toggles fit <-> zoomed-in. Must not crash.
        rule.onNodeWithTag(ViewerTags.IMAGE).performTouchInput { doubleClick() }
        rule.waitForIdle()
        saveScreenshot("viewer_zoomed.png")

        // Pinch + drag (pan) while zoomed. Must not crash.
        rule.onNodeWithTag(ViewerTags.IMAGE).performTouchInput {
            pinch(
                start0 = Offset(centerX, centerY),
                end0 = Offset(centerX + 200f, centerY),
                start1 = Offset(centerX, centerY),
                end1 = Offset(centerX - 200f, centerY)
            )
        }
        rule.onNodeWithTag(ViewerTags.IMAGE).performTouchInput { swipeLeft() }
        rule.waitForIdle()

        // Still alive and rendering.
        rule.onNodeWithTag(ViewerTags.IMAGE).assertExists()
        Log.i(TAG, "VIEWER_UI renders+gestures ok")
    }

    @Test
    fun viewer_multiPageSwipeAdvancesIndex() {
        val pages = listOf(
            page(id = "a", image = writeImage("a.png", Color.RED)),
            page(id = "b", image = writeImage("b.png", Color.BLUE))
        )
        rule.setContent { DocScanTheme { PageViewerContent(pages, startIndex = 0, onClose = {}) } }
        waitForImage()
        rule.onNodeWithTag(ViewerTags.PAGE_INDICATOR).assertTextEquals("1 / 2")

        // At fit scale the pager owns the horizontal swipe.
        rule.onNodeWithTag(ViewerTags.PAGER).performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(ViewerTags.PAGE_INDICATOR)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ViewerTags.PAGE_INDICATOR).assertTextEquals("2 / 2")
        saveScreenshot("viewer_page2.png")
        Log.i(TAG, "VIEWER_UI pager advanced to 2/2")
    }

    @Test
    fun viewer_rawToggleSwitchesToOriginal() {
        val processed = writeImage("proc.png", Color.RED)
        val raw = writeImage("raw.png", Color.GREEN)
        val pages = listOf(page(id = "p", image = processed, raw = raw))

        rule.setContent { DocScanTheme { PageViewerContent(pages, startIndex = 0, onClose = {}) } }
        waitForImage()

        // Toggle is only present when a raw capture exists.
        rule.onNodeWithTag(ViewerTags.RAW_TOGGLE).assertExists()
        rule.onNodeWithText("Processed").assertExists()

        rule.onNodeWithTag(ViewerTags.RAW_TOGGLE).performClick()
        rule.waitForIdle()
        // Now showing the original capture; rendering must not crash.
        rule.onNodeWithText("Original").assertExists()
        waitForImage()
        rule.onNodeWithTag(ViewerTags.IMAGE).assertExists()
        Log.i(TAG, "VIEWER_UI raw toggle ok")
    }

    @Test
    fun viewer_missingOrCorruptFileShowsCleanErrorState() {
        val missing = File(workDir, "does_not_exist.png")
        val corrupt = File(workDir, "corrupt.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
        val pages = listOf(page(id = "missing", image = missing), page(id = "corrupt", image = corrupt))

        rule.setContent { DocScanTheme { PageViewerContent(pages, startIndex = 0, onClose = {}) } }
        // First page (missing file) -> clean error state, no crash.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag(ViewerTags.ERROR).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(ViewerTags.ERROR).assertExists()
        saveScreenshot("viewer_error.png")

        // Swipe to the corrupt file page; must also show the error state (not crash).
        rule.onNodeWithTag(ViewerTags.PAGER).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag(ViewerTags.ERROR).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(ViewerTags.ERROR).assertExists()
        Log.i(TAG, "VIEWER_UI corrupt/missing error state ok")
    }

    @Test
    fun reviewScreen_sessionThumbnailState() {
        // Renders the real Review screen (the "session screen") with a one-page fixture so the
        // captured-page thumbnail state can be screenshotted for the user.
        val image = writeImage("session_page.png", Color.LTGRAY)
        val repository = ScanSessionRepository(SessionStore(context))
        repository.setDocument(
            ScanDocument(sessionId = "viewer-shot-session", pages = listOf(page(id = "s1", image = image)))
        )
        val viewModel = ScanSessionViewModel(
            repository = repository,
            settingsRepository = ServiceLocator.settingsRepository,
            pipeline = ServiceLocator.pipeline
        )

        rule.setContent {
            DocScanTheme {
                PageReviewScreen(
                    viewModel = viewModel,
                    onOpenPage = {},
                    onEditPage = {},
                    onExport = {},
                    onAddMore = {}
                )
            }
        }
        rule.waitForIdle()
        // Give Coil a moment to decode the on-disk thumbnail before the screenshot.
        Thread.sleep(1_500)
        rule.waitForIdle()
        saveScreenshot("viewer_session.png")
        saveScreenshot("viewer_thumbnail.png")
        Log.i(TAG, "VIEWER_UI session screen captured")
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun waitForImage() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag(ViewerTags.IMAGE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Writes a solid-colour PNG the viewer can decode (no Canvas dependency needed). */
    private fun writeImage(name: String, color: Int, w: Int = 600, h: Int = 900): File {
        val file = File(workDir, name)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun page(id: String, image: File, raw: File? = null) = ScannedPage(
        id = id,
        imageFile = image.absolutePath,
        rawFile = raw?.absolutePath,
        thumbnailFile = image.absolutePath
    )

    /** Captures the current UI to `getExternalFilesDir` so it can be `adb pull`-ed as an artifact. */
    private fun saveScreenshot(name: String) {
        runCatching {
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            val outDir = context.getExternalFilesDir(null) ?: workDir
            val out = File(outDir, name)
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "VIEWER_SHOT $name -> ${out.absolutePath} (${out.length()} bytes)")
        }.onFailure { Log.w(TAG, "screenshot $name failed", it) }
    }

    companion object {
        private const val TAG = "DocScan"
    }
}
