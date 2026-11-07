package org.example.project.core.presentation.components.notification

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.str

@Composable
fun NotificationBadge(
    onNotificationClicked: () -> Unit,
    notificationCount: Int = 0,
) {
    BadgedBox(
        modifier =
            Modifier
                .padding(end = 16.dp)
                .clickable { onNotificationClicked() },
        badge = {
            if (notificationCount == 0) return@BadgedBox
            Badge {
                Text(
                    text = if (notificationCount > 99) "99+" else notificationCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    ) {
        Icon(
            imageVector = Icons.Default.Notifications,
            contentDescription = str(AccessibilityKeys.notifications),
        )
    }
}
