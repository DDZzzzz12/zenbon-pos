package org.example.project.features.notifications.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.core.notification.NotificationNavigation
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.formatNotificationTime
import org.example.project.features.notifications.presentation.state.ConnectionState
import org.example.project.features.notifications.presentation.viewmodel.NotificationItem
import org.example.project.features.notifications.presentation.viewmodel.NotificationType
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme
import org.koin.compose.koinInject

/**
 * Notification bottom sheet that slides up when the notification bell is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPanel(
    viewModel: NotificationViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val languageManager = LocalLanguageManager.current
    val currentLanguage = languageManager.currentLanguage.value
    val platformNotificationService = koinInject<PlatformNotificationService>()

    if (!uiState.showNotificationPanel) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { viewModel.hideNotificationPanel() },
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
        ) {
            // Header
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null)
                    Text(
                        text = str(NotificationKeys.panelTitle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (uiState.unreadCount > 0) {
                        Badge { Text(uiState.unreadCount.toString()) }
                    }
                }
                PosIconButton(
                    icon = Icons.Default.DoneAll,
                    contentDescription = str(NotificationKeys.markAllAsRead),
                    onClick = { viewModel.markAllAsRead() },
                )
            }

            HorizontalDivider()

            // Notification list
            if (uiState.notifications.isEmpty()) {
                EmptyNotificationsView()
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(
                        items = uiState.notifications,
                        key = { it.id },
                    ) { notification ->
                        if (notification.type == NotificationType.APPROVAL_REQUEST) {
                        } else {
                            NotificationListItem(
                                notification = notification,
                                onClick = {
                                    viewModel.markAsRead(notification.id)
                                    if (notification.type == NotificationType.BILLING_REQUEST) {
                                        val tableId = notification.data["tableId"]
                                        if (tableId != null) {
                                            platformNotificationService.setPendingNavigation(
                                                NotificationNavigation("billing_request", tableId),
                                            )
                                        }
                                    }
                                    viewModel.hideNotificationPanel()
                                },
                                onDismiss = { viewModel.removeNotification(notification.id) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Footer
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Connection status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm),
                ) {
                    val (statusColor, statusText) =
                        when (uiState.connectionState) {
                            ConnectionState.Connected -> PosTheme.colors.posAvailable to str(NotificationKeys.connected)
                            ConnectionState.Connecting -> PosTheme.colors.posOccupied to str(NotificationKeys.connecting)
                            ConnectionState.Disconnected -> PosTheme.colors.posError to str(NotificationKeys.disconnected)
                            ConnectionState.Failed -> PosTheme.colors.posError to str(NotificationKeys.connectionFailed)
                        }
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    if (uiState.connectionState == ConnectionState.Disconnected ||
                        uiState.connectionState == ConnectionState.Failed
                    ) {
                        TextButton( // raw Material 3 by design: dynamic text style (labelSmall) and compact height cannot be expressed by PosTextButton
                            onClick = { viewModel.connectToNotifications(true) },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text(str(NotificationKeys.reconnect), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Clear all
                TextButton( // raw Material 3 by design: error color on text and custom text style (labelSmall) cannot be expressed by PosTextButton
                    onClick = { viewModel.clearAllNotifications() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = str(NotificationKeys.clearAll),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationListItem(
    notification: NotificationItem,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isRead = notification.isRead
    val dotColor = getColorForNotificationType(notification.type)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(
                    if (!isRead) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    } else {
                        Color.Transparent
                    },
                ).padding(horizontal = 16.dp, vertical = PosSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Status dot
        Box(
            modifier =
                Modifier
                    .padding(top = PosSpacing.sm)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRead) {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        } else {
                            dotColor
                        },
                    ),
        )

        // Content
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PosAutoSizeText(
                    text = notification.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    color =
                        if (isRead) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
                Text(
                    text = formatNotificationTime(notification.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = notification.message,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (isRead) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // Dismiss
        PosIconButton(
            icon = Icons.Default.Close,
            contentDescription = str(NotificationKeys.dismiss),
            onClick = onDismiss,
            modifier = Modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            size = 20.dp,
            iconSize = 14.dp,
        )
    }
}

@Composable
private fun EmptyNotificationsView() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Text(
                text = str(NotificationKeys.allCaughtUp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun getColorForNotificationType(type: NotificationType): Color =
    when (type) {
        NotificationType.BILLING_REQUEST -> PosTheme.colors.posBilling
        NotificationType.TABLE_STATUS -> PosTheme.colors.posAvailable
        NotificationType.SESSION_STARTED -> PosTheme.colors.posAvailable
        NotificationType.SESSION_ENDED -> PosTheme.colors.posError
        NotificationType.ORDER_UPDATE -> PosTheme.colors.posReserved
        NotificationType.APPROVAL_REQUEST -> MaterialTheme.colorScheme.primary
        NotificationType.SYSTEM -> PosTheme.colors.posOccupied
    }
