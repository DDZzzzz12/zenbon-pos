package org.example.project.ui.screens.notifications

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.example.project.BuildKonfig
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton

/**
 * Explains why the till must run in the background before the system battery settings open.
 *
 * Renders nothing unless [PlatformNotificationService.backgroundExecutionPromptVisible] is true,
 * which only Android ever raises. Dismissing the dialog (back, outside tap) counts as "later".
 */
@Composable
fun BackgroundExecutionPromptDialog(platformNotificationService: PlatformNotificationService) {
    val visible by platformNotificationService.backgroundExecutionPromptVisible.collectAsState()
    if (!visible) return

    PosDialog(
        onDismiss = platformNotificationService::postponeBackgroundExecutionPrompt,
        title = str(NotificationKeys.backgroundExecutionTitle),
        titleIcon = Icons.Default.BatteryAlert,
        footer = {
            PosTextButton(
                text = str(NotificationKeys.backgroundExecutionLater),
                onClick = platformNotificationService::postponeBackgroundExecutionPrompt,
            )
            PosButton(
                text = str(NotificationKeys.backgroundExecutionSetUpNow),
                onClick = platformNotificationService::openBackgroundExecutionSettings,
            )
        },
    ) {
        Text(
            text = str(NotificationKeys.backgroundExecutionMessage, BuildKonfig.APP_NAME),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
