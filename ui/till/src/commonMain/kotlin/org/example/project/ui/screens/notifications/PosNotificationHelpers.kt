package org.example.project.ui.screens.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.example.project.core.notification.NotificationNavigation
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.notifications.presentation.state.ConnectionState
import org.example.project.features.notifications.presentation.viewmodel.NotificationItem
import org.example.project.features.notifications.presentation.viewmodel.NotificationType
import org.example.project.ui.theme.PosTheme

@Composable
fun getColorForNotificationType(type: NotificationType): Color =
    when (type) {
        NotificationType.BILLING_REQUEST -> PosTheme.colors.posBilling
        NotificationType.TABLE_STATUS -> PosTheme.colors.posAvailable
        NotificationType.SESSION_STARTED -> PosTheme.colors.posAvailable
        NotificationType.SESSION_ENDED -> PosTheme.colors.posError
        NotificationType.ORDER_UPDATE -> PosTheme.colors.posReserved
        NotificationType.APPROVAL_REQUEST -> MaterialTheme.colorScheme.primary
        NotificationType.SYSTEM -> PosTheme.colors.posOccupied
    }

fun getIconForNotificationType(type: NotificationType): ImageVector =
    when (type) {
        NotificationType.BILLING_REQUEST -> Icons.Default.Payment
        NotificationType.TABLE_STATUS -> Icons.Default.TableRestaurant
        NotificationType.SESSION_STARTED -> Icons.Default.Restaurant
        NotificationType.SESSION_ENDED -> Icons.Default.Restaurant
        NotificationType.ORDER_UPDATE -> Icons.Default.ShoppingCart
        NotificationType.APPROVAL_REQUEST -> Icons.Default.GppGood
        NotificationType.SYSTEM -> Icons.Default.Settings
    }

fun handleNotificationNavigation(
    notification: NotificationItem,
    platformNotificationService: PlatformNotificationService,
) {
    if (notification.type == NotificationType.BILLING_REQUEST) {
        val tableId = notification.data["tableId"]
        if (tableId != null) {
            platformNotificationService.setPendingNavigation(
                NotificationNavigation("billing_request", tableId),
            )
        }
    }
}

@Composable
fun ConnectionStatusDot(
    connectionState: ConnectionState,
    /** The word next to the dot; a phone-width title bar has no room for it, the colour still says it. */
    showLabel: Boolean = true,
) {
    val statusColor =
        when (connectionState) {
            ConnectionState.Connected -> PosTheme.colors.posAvailable
            ConnectionState.Connecting -> PosTheme.colors.posOccupied
            ConnectionState.Disconnected -> PosTheme.colors.posError
            ConnectionState.Failed -> PosTheme.colors.posError
        }
    val statusKey =
        when (connectionState) {
            ConnectionState.Connected -> NotificationKeys.connected
            ConnectionState.Connecting -> NotificationKeys.connecting
            ConnectionState.Disconnected -> NotificationKeys.disconnected
            ConnectionState.Failed -> NotificationKeys.connectionFailed
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor),
        )
        if (showLabel) {
            Text(
                text = str(statusKey),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
fun EmptyNotificationsView(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.NotificationsOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Text(
                text = str(NotificationKeys.allCaughtUp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}
