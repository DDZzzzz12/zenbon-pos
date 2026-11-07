package org.example.project.ui.screens.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalNotificationViewModel
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.notifications.presentation.viewmodel.NotificationItem
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosIconButton
import org.koin.compose.koinInject

/**
 * Full-screen notification center.
 *
 * Phone: Full-screen list of notification cards with filter chips.
 * Tablet/Desktop: List-detail layout with notification list on the left
 * and selected notification detail on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosNotificationScreen(
    modifier: Modifier = Modifier,
    viewModel: NotificationViewModel = LocalNotificationViewModel.current,
    platformNotificationService: PlatformNotificationService = koinInject(),
) {
    val windowSize = LocalWindowSizeClass.current
    val uiState by viewModel.uiState.collectAsState()
    var selectedFilter by remember { mutableStateOf(NotificationFilter.ALL) }
    var selectedNotification by remember { mutableStateOf<NotificationItem?>(null) }

    val filteredNotifications =
        remember(uiState.notifications, selectedFilter) {
            filterNotifications(uiState.notifications, selectedFilter)
        }

    // Clear selected notification if it was removed from the list
    if (selectedNotification != null && uiState.notifications.none { it.id == selectedNotification?.id }) {
        selectedNotification = null
    }

    val isCompact = windowSize.isCompact()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement =
                            androidx.compose.foundation.layout.Arrangement
                                .spacedBy(8.dp),
                    ) {
                        PosAutoSizeText(
                            text = str(NotificationKeys.panelTitle),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            minFontSize = 14.sp,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (uiState.unreadCount > 0) {
                            Badge { Text(uiState.unreadCount.toString()) }
                        }
                    }
                },
                actions = {
                    ConnectionStatusDot(uiState.connectionState, showLabel = !isCompact)

                    if (uiState.unreadCount > 0) {
                        PosIconButton(
                            icon = Icons.Default.DoneAll,
                            contentDescription = str(NotificationKeys.markAllAsRead),
                            onClick = { viewModel.markAllAsRead() },
                        )
                    }
                    if (isCompact) {
                        // An icon on a phone: the text button took half the bar and cut the title off.
                        PosDangerIconButton(
                            icon = Icons.Default.DeleteSweep,
                            contentDescription = str(NotificationKeys.clearAll),
                            onClick = { viewModel.clearAllNotifications() },
                        )
                    } else {
                        // raw Material 3 by design: error-colored TextButton — PosDangerButton has different visual weight; PosTextButton lacks danger color param
                        androidx.compose.material3.TextButton(onClick = { viewModel.clearAllNotifications() }) {
                            Text(
                                text = str(NotificationKeys.clearAll),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            NotificationFilterRow(
                selectedFilter = selectedFilter,
                unreadCount = uiState.unreadCount,
                onFilterSelected = { selectedFilter = it },
            )

            HorizontalDivider()

            if (filteredNotifications.isEmpty()) {
                EmptyNotificationsView(modifier = Modifier.fillMaxSize())
            } else if (isCompact) {
                NotificationList(
                    notifications = filteredNotifications,
                    selectedId = null,
                    onNotificationClick = { notification ->
                        viewModel.markAsRead(notification.id)
                        handleNotificationNavigation(notification, platformNotificationService)
                        viewModel.hideNotificationPanel()
                    },
                    onDismiss = { viewModel.removeNotification(it.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    NotificationList(
                        notifications = filteredNotifications,
                        selectedId = selectedNotification?.id,
                        onNotificationClick = { notification ->
                            viewModel.markAsRead(notification.id)
                            selectedNotification = notification
                        },
                        onDismiss = { viewModel.removeNotification(it.id) },
                        modifier =
                            Modifier
                                .width(360.dp)
                                .fillMaxHeight(),
                    )

                    VerticalDivider()

                    NotificationDetailPanel(
                        notification = selectedNotification,
                        notificationViewModel = viewModel,
                        onAction = { notification ->
                            handleNotificationNavigation(notification, platformNotificationService)
                            viewModel.hideNotificationPanel()
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                    )
                }
            }
        }
    }
}
