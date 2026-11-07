package org.example.project.ui.screens.notifications

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.notifications.presentation.viewmodel.NotificationType

enum class NotificationFilter {
    ALL,
    UNREAD,
    BILLING,
    TABLE,
    ORDER,
    SYSTEM,
}

@Composable
fun NotificationFilterRow(
    selectedFilter: NotificationFilter,
    unreadCount: Int,
    onFilterSelected: (NotificationFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NotificationFilter.entries.forEach { filter ->
            val label =
                when (filter) {
                    NotificationFilter.ALL -> str(NotificationKeys.filterAll)
                    NotificationFilter.UNREAD -> str(NotificationKeys.filterUnread, unreadCount)
                    NotificationFilter.BILLING -> str(NotificationKeys.filterBilling)
                    NotificationFilter.TABLE -> str(NotificationKeys.filterTables)
                    NotificationFilter.ORDER -> str(NotificationKeys.filterOrders)
                    NotificationFilter.SYSTEM -> str(NotificationKeys.filterSystem)
                }
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            )
        }
    }
}

fun filterNotifications(
    notifications: List<org.example.project.features.notifications.presentation.viewmodel.NotificationItem>,
    filter: NotificationFilter,
): List<org.example.project.features.notifications.presentation.viewmodel.NotificationItem> =
    when (filter) {
        NotificationFilter.ALL -> notifications
        NotificationFilter.UNREAD -> notifications.filter { !it.isRead }
        NotificationFilter.BILLING -> notifications.filter { it.type == NotificationType.BILLING_REQUEST }
        NotificationFilter.TABLE ->
            notifications.filter {
                it.type == NotificationType.TABLE_STATUS ||
                    it.type == NotificationType.SESSION_STARTED ||
                    it.type == NotificationType.SESSION_ENDED
            }
        NotificationFilter.ORDER -> notifications.filter { it.type == NotificationType.ORDER_UPDATE }
        NotificationFilter.SYSTEM -> notifications.filter { it.type == NotificationType.SYSTEM }
    }
