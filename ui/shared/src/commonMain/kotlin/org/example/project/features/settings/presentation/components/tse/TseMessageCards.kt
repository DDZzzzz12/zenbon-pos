/**
 * TSE message card components for displaying success and error messages.
 *
 * @since 2.0.0
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardStatus

/**
 * Card displaying a success message with green theme.
 *
 * @param message The success message to display
 */
@Composable
fun TseSuccessCard(message: String) {
    PosCard(
        status = PosCardStatus.Neutral,
    ) {
        body {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * Card displaying an error message with red theme.
 *
 * @param message The error message to display
 */
@Composable
fun TseErrorCard(message: String) {
    PosCard(
        status = PosCardStatus.Error,
    ) {
        body {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
