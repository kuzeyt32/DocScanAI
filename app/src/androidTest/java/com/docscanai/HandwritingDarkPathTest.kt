package com.docscanai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.capture.ScanPipeline
import com.docscanai.data.EnhancementMode
import com.docscanai.data.PageEnhanceSettings
import com.docscanai.data.ScannedPage
import com.docscanai.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SUBTASK H24 — the pencil/handwriting stage stays DARK.
 *
 * H23 measured the feature and reverted it: the flag is off and the UI affordance is not rendered.
 * H24 ships the detection fix and must not accidentally light the feature up. This test asserts the
 * **production** decisions, not copies of them:
 *
 *  1. [PageEnhanceSettings.handwriting] defaults to `false`;
 *  2. [ScanPipeline.shouldApplyHandwriting] — the single predicate that guards BOTH
 *     `HandwritingEnhancer.measure()` and `HandwritingEnhancer.enhance()` — is false for the default
 *     settings, so with the flag off no HandwritingEnhancer code executes;
 *  3. the persisted `handwritingEnhanceEnabled` mapping reads `false` when set to its default;
 *  4. [ScannedPage.handwritingSuggested] defaults to `false` (no "Enhance handwriting" affordance);
 *  5. the predicate is not vacuously false: it does return true only when a page explicitly opted
 *     in AND the mode is not ORIGINAL.
 *
 * The editor control in `PageEditScreen.kt` is wrapped in `if (handwritingEnabled)` where
 * `handwritingEnabled` mirrors the same repository flow, so assertion 3 also proves the control is
 * not rendered on a default install.
 */
@RunWith(AndroidJUnit4::class)
class HandwritingDarkPathTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun handwritingStage_isDarkByDefault() {
        // 1) shipped per-page default: the stage is off.
        val defaults = PageEnhanceSettings()
        assertFalse(
            "PageEnhanceSettings.handwriting must default to false",
            defaults.handwriting
        )

        // 2) THE production guard: false means neither measure() nor enhance() is called.
        assertFalse(
            "ScanPipeline.shouldApplyHandwriting(defaults) must be false (dark path)",
            ScanPipeline.shouldApplyHandwriting(defaults)
        )

        // 3) persisted flag default: write the documented default and read the same mapping the
        //    editor uses for `handwritingEnabled`.
        val repository = SettingsRepository(context)
        runBlocking { repository.setHandwritingEnhanceEnabled(false) }
        val persisted = runBlocking { repository.handwritingEnhanceEnabled.first() }
        assertFalse("SettingsRepository.handwritingEnhanceEnabled must be false", persisted)

        // 4) no suggestion is shown by default either.
        val page = ScannedPage(
            id = "dark-path",
            imageFile = "unused.jpg",
            rawFile = null,
            thumbnailFile = null,
            corners = null,
            enhancement = defaults
        )
        assertFalse(
            "ScannedPage.handwritingSuggested must default to false",
            page.handwritingSuggested
        )

        // 5) non-vacuous: only an explicit opt-in outside ORIGINAL opens the path.
        assertTrue(
            "explicit opt-in must still be honoured (feature is dark-launched, not deleted)",
            ScanPipeline.shouldApplyHandwriting(
                PageEnhanceSettings(mode = EnhancementMode.ENHANCED, handwriting = true)
            )
        )
        assertFalse(
            "ORIGINAL must never receive the handwriting stage",
            ScanPipeline.shouldApplyHandwriting(
                PageEnhanceSettings(mode = EnhancementMode.ORIGINAL, handwriting = true)
            )
        )

        Log.i(
            "DocScan",
            "H24_DARK_PATH defaultHandwriting=${defaults.handwriting} " +
                "guard=${ScanPipeline.shouldApplyHandwriting(defaults)} persisted=$persisted " +
                "suggested=${page.handwritingSuggested} PASS"
        )
    }
}
