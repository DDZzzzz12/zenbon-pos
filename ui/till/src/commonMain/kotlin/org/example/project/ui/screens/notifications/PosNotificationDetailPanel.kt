package org.example.project.ui.screens.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.presentation.strings.keys.ApprovalKeys
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatNotificationTime
import org.example.project.features.notifications.presentation.viewmodel.NotificationItem
import org.example.project.features.notifications.presentation.viewmodel.NotificationType
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel
import org.example.project.ui.theme.PosTheme

@Composable
fun NotificationDetailPanel(
    notification: NotificationItem?,
    notificationViewModel: NotificationViewModel,
    onAction: (NotificationItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notification == null) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
                Text(
                    text = str(NotificationKeys.selectNotification),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        return
    }

    val typeColor = getColorForNotificationType(notification.type)
    val typeIcon = getIconForNotificationType(notification.type)

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Type badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(typeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = typeIcon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = typeColor,
                )
            }
            Column {
                Text(
                    text = notification.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = formatNotificationTime(notification.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        // Message
        Text(
            text = notification.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Read status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (notification.isRead) Icons.Default.CheckCircle else Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint =
                    if (notification.isRead) {
                        PosTheme.colors.posAvailable
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
            Text(
                text = if (notification.isRead) str(NotificationKeys.statusRead) else str(NotificationKeys.statusUnread),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Action button for billing requests
        if (notification.type == NotificationType.BILLING_REQUEST) {
            NotificationActionButton(
                icon = Icons.Default.Payment,
                label = str(NotificationKeys.goToBilling),
                onClick = { onAction(notification) },
            )
        }

        // Action button for approval requests
        if (notification.type == NotificationType.APPROVAL_REQUEST) {
            val requestJson = notification.data["requestJson"]
            if (requestJson != null) {
                NotificationActionButton(
                    icon = Icons.Default.GppGood,
                    label = str(ApprovalKeys.approvalRequestTitle),
                    onClick = {
                        try {
                            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                            val request = json.decodeFromString<AuthDto.ApprovalRequestDto>(requestJson)
                            notificationViewModel.emitApprovalRequestEvent(notification.id, request)
                        } catch (_: Exception) {
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NotificationActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
