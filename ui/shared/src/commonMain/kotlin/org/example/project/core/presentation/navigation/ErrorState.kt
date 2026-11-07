/**
 * Reusable error state UI component.
 *
 * Displays an error message with an optional retry button for failed operations.
 * Provides user feedback when data loading fails and allows retry.
 *
 * @param message The error message to display
 * @param modifier Optional modifier for the component
 * @param title Optional title shown above the error message
 * @param onRetry Optional callback invoked when retry button is clicked (button hidden if null)
 * @param retryButtonText Optional custom text for the retry button
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.presentation.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.PosButton

/**
 * Reusable error state UI.
 *
 * Shows an error icon, optional title, error message, and optional retry button.
 *
 * @param message The error message to display
 * @param modifier Optional modifier for the component (defaults to fillMaxSize)
 * @param title Optional title shown above the error message
 * @param onRetry Optional callback invoked when retry button is clicked (button hidden if null)
 * @param retryButtonText Optional custom text for the retry button (defaults to "Retry")
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    title: String? = null,
    onRetry: (() -> Unit)? = null,
    retryButtonText: String = "Retry",
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                PosButton(text = retryButtonText, onClick = onRetry)
            }
        }
    }
}
