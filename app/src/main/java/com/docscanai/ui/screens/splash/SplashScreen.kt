package com.docscanai.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docscanai.core.di.ServiceLocator
import kotlinx.coroutines.delay

/**
 * Branded splash / bootstrap screen (TECHNICAL_SPEC.md §6.1 step 1, ARCHITECTURE.md §4).
 *
 * Shows the app identity briefly and then **auto-routes** exactly once — there are no manual
 * buttons. Routing is driven by [SettingsRepository.firstLaunchCompleted] (TECHNICAL_SPEC.md §10
 * "Bootstrap order"):
 *
 *  - first launch not yet completed -> [onModelsMissing] (the model-download onboarding screen);
 *  - returning user                -> [onModelsReady]   (the camera).
 *
 * Double-navigation is guarded by a `rememberSaveable` flag so a recomposition / configuration
 * change can never fire the route twice (the nav graph also pops the splash off the back stack).
 */
@Composable
fun SplashScreen(
    onModelsReady: () -> Unit,
    onModelsMissing: () -> Unit
) {
    // `null` until DataStore has produced its first value; this avoids a wrong routing decision
    // (the flow defaults to `false`) flashing the onboarding screen for returning users.
    val firstLaunchCompleted by ServiceLocator.settingsRepository.firstLaunchCompleted
        .collectAsStateWithLifecycle(initialValue = null)

    // Guards against navigating more than once, including across process/rotation restarts.
    var navigated by rememberSaveable { mutableStateOf(false) }

    val completed = firstLaunchCompleted
    LaunchedEffect(completed) {
        if (completed != null && !navigated) {
            // Keep the branded splash visible at least this long so it doesn't flicker.
            delay(SPLASH_MIN_DURATION_MS)
            navigated = true
            if (completed) onModelsReady() else onModelsMissing()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Simple logo: the app icon in a tinted circle.
            Column(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.DocumentScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "DocScan AI",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Scan, flatten and clean up documents on-device",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
    }
}

/** Minimum time the branded splash stays visible before auto-routing. */
private const val SPLASH_MIN_DURATION_MS = 900L
