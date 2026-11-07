package org.example.project.features.notifications.presentation.state

import org.example.project.features.notifications.presentation.viewmodel.NotificationItem

/**
 * UI state for notification feature.
 *
 * Consolidates all notification-related state into a single data class
 * following the established project pattern for state management.
 *
 * @property connectionState Current WebSocket connection state
 * @property notifications List of all notifications (newest first)
 * @property unreadCount Number of unread notifications
 * @property selectedNotification Currently selected notification for detail view
 * @property showNotificationPanel Whether notification panel is visible
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
data class NotificationUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val tseConnectionState: TseConnectionState = TseConnectionState.Unknown,
    val notifications: List<NotificationItem> = emptyList(),
    val unreadCount: Int = 0,
    val selectedNotification: NotificationItem? = null,
    val showNotificationPanel: Boolean = false,
    val successMessage: String? = null,
)

/**
 * Connection state for UI display.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()

    data object Connecting : ConnectionState()

    data object Connected : ConnectionState()

    data object Failed : ConnectionState()
}

/**
 * TSE connection state for UI display.
 *
 * Distinct from [ConnectionState] which tracks the WebSocket/server connection.
 * This tracks the TSE hardware connection status.
 */
sealed class TseConnectionState {
    /** Initial state before any health check has been received. */
    data object Unknown : TseConnectionState()

    /** TSE is connected and healthy. */
    data object Connected : TseConnectionState()

    /** TSE is not reachable. */
    data object Disconnected : TseConnectionState()

    /** Configured NORMAL but the TSE is offline — selling continues, bookings are queued for Nachsignieren. */
    data object NormalOffline : TseConnectionState()

    /** TSE has been explicitly disabled. */
    data object Disabled : TseConnectionState()

    /** TSE is in SIMULATION mode — signing is mocked, NOT fiscally compliant. */
    data object Simulation : TseConnectionState()

    /** Transient: hardware is mid-maintenance (selftest, timesync, init, reconnect). Not a fault. */
    data object Maintenance : TseConnectionState()

    /** Real hardware fault: not in maintenance and not healthy. */
    data object Degraded : TseConnectionState()
}
