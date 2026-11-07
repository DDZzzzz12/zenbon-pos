/**
 * Reusable loading state UI component.
 *
 * Shows a loading indicator with an optional message while data is being fetched.
 * Used to provide user feedback during asynchronous data operations.
 *
 * @param modifier Optional modifier for the component
 * @param message Optional loading message to display below the spinner
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reusable loading state UI.
 *
 * Shows a centered loading indicator with optional message.
 *
 * @param modifier Optional modifier for the component (defaults to fillMaxSize)
 * @param message Optional loading message to display below the spinner
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier.fillMaxSize(),
    message: String? = null,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
