import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// SUBTASK H10 — release signing.
// `keystore.properties` (repo root, git-ignored) holds the store/key passwords. When it is
// absent (fresh clone / CI without secrets) the release variant is still buildable: it simply
// degrades to an UNSIGNED release APK and prints a clear warning. The password is never
// hard-coded here and never printed.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
} else {
    logger.warn(
        "WARNING: keystore.properties not found at ${keystorePropertiesFile.absolutePath}. " +
            "The `release` variant will be built UNSIGNED. Create keystore.properties with " +
            "storeFile/storePassword/keyAlias/keyPassword to produce a signed release APK."
    )
}

android {
    namespace = "com.docscanai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.docscanai"
        minSdk = 26
        targetSdk = 35
        // SUBTASK H7: user-visible behavior change (grid single-sample dewarp + sharper
        // image fallback + detection refinement).
        // SUBTASK H11: user-visible change (zoom-detail high-resolution re-decode in the viewer).
        // SUBTASK H15: user-visible dewarp-quality change (adaptive residual strength + 6% clamp;
        // flat pages use the homography only, so glyphs are no longer over-warped).
        // SUBTASK H18: no user-visible change — sub-pixel corner refinement was measured behind
        // REFINE_ENABLED and gated off (corner error regressed), so 0.3.4 keeps the 0.3.3 corners.
        // SUBTASK H21: user-visible change — auto-capture no longer fires on a room with no
        // document (quad provenance + text-line proof), it waits for a real minimum hold time,
        // it refuses to fire while the content is still moving, it debounces after every shot,
        // and page detection itself is paused while the phone is being moved.
        // SUBTASK H24: fixes the H21 detection regression — the text-line proof stays on the LIVE
        // auto-capture gate but no longer vetoes the post-shutter still warp, so classical-fallback
        // detections are warped again (benchmark 19/20 AI-ON, 14/20 AI-OFF). No threshold change.
        // SUBTASK H26: user-visible geometry fix — the seeded refine() can no longer replace a
        // correct seed with an inner text block / table frame, Edit -> ORIGINAL re-detects like an
        // import instead of re-seeding, and the live quad is mapped onto the still through the
        // analysis/still aspect transform. Pencil/handwriting remains dark-launched and OFF.
        // SUBTASK H27: the second-stage UVDoc grid dewarp is bit-deterministic (deterministic
        // compute on its ORT session; same bitmap+quad -> identical output bytes, asserted by
        // WarpDeterminismTest and by the RefineSeedGuardTest determinism checks), and B&W mode is
        // one modest step softer (a 0.4 px anti-alias on the 1-bit output: stroke Laplacian
        // -13 %, clipping 100 % -> 90-97 %, overshoot still 0, still 5-7x ORIGINAL).
        // SUBTASK H33: user-visible fix ("show what it sees") — the preview no longer crops the
        // capture field of view: Preview + ImageCapture are pinned to one 4:3 stream and the
        // PreviewView letterboxes it (FIT_CENTER), so what is on screen is what gets captured.
        versionCode = 21
        versionName = "0.3.18"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // APK-size + reproduction: ONNX Runtime + OpenCV ship native libs for every ABI. Package
        // ONLY the ABIs we actually need so the APK stays reasonable:
        //   - arm64-v8a : the Samsung Galaxy S25 FE (target device).
        //   - x86_64    : the Android emulator — required to reproduce the post-capture crash and to
        //                 run the instrumented pipeline test (PipelineInstrumentedTest) without a
        //                 physical phone (SUBTASK 9a).
        // NOTE: `ndk { }`'s DSL accessor has failed to resolve in this AGP 8.7.3 / Gradle 8.10.2
        // combo before; see the `splits` fallback below if this ever regresses.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Fallback ABI restriction (independently buildable): if `defaultConfig.ndk` ever fails to
    // resolve, enable this `splits` block instead and disable the `ndk` block above.
    // splits {
    //     abi {
    //         isEnable = true
    //         reset()
    //         include("arm64-v8a", "x86_64")
    //         isUniversalApk = false
    //     }
    // }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Graceful degradation: with keystore.properties present the release APK is signed
            // with the H10 keystore; without it this stays an unsigned release build (warning
            // emitted above), so a fresh clone still builds.
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // SUBTASK H3b build fix: production code (ScanPipeline.kt, QuadOverlayRenderer.kt)
        // reads BuildConfig.DEBUG to gate the DEBUG-only detected-quad overlay, so the
        // generated BuildConfig class MUST exist. AGP 8.x defaults this to false; enabling
        // it here (per-module) is what makes a plain `gradlew.bat assembleDebug` build,
        // with no `-Pandroid.defaults.buildfeatures.buildconfig=true` override needed.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
    }

}

kotlin {
    compilerOptions {
        // JDK 17 bytecode. NOTE: the spec mentions jvmToolchain(17), but no JDK 17 is
        // installed on this machine (JDK 21/25 only). Targeting JVM 17 explicitly avoids
        // requiring a toolchain download while producing identical JVM 17 bytecode.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Core / platform
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Lifecycle + Compose host
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM-managed)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)

    // ML / CV
    implementation(libs.onnxruntime.android)
    implementation(libs.opencv)

    // Networking / async
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // Storage / background work
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // Misc
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.exifinterface)

    // Debug tooling
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Unit tests
    testImplementation(libs.junit)

    // Instrumented tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
