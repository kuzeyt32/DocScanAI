package com.docscanai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A named action rendered as a button on a [PlaceholderScreen]. */
data class ScreenAction(val label: String, val onClick: () -> Unit)

/**
 * Shared placeholder screen used by every screen while the real UI is built in later
 * subtasks. Shows the screen title, its locked route, a TODO description, and any
 * navigation actions wired into the nav graph.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    route: String,
    description: String,
    modifier: Modifier = Modifier,
    actions: List<ScreenAction> = emptyList()
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = "route: $route",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        actions.forEach { action ->
            Button(onClick = action.onClick, modifier = Modifier.fillMaxWidth()) {
                Text(action.label)
            }
        }
    }
}
