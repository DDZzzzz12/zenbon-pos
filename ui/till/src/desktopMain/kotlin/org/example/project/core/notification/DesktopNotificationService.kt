package org.example.project.core.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DesktopNotificationService : PlatformNotificationService {
    private val _pendingNavigation = MutableStateFlow<NotificationNavigation?>(null)
    override val pendingNavigation: StateFlow<NotificationNavigation?> = _pendingNavigation.asStateFlow()

    override fun setPendingNavigation(navigation: NotificationNavigation) {
        _pendingNavigation.value = navigation
    }

    override fun consumePendingNavigation() {
        _pendingNavigation.value = null
    }

    override fun startService() {
        // No-op on desktop — WebSocket is managed by NotificationViewModel
    }

    override fun stopService() {
        // No-op on desktop
    }

    override val backgroundExecutionPromptVisible: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override fun openBackgroundExecutionSettings() {
        // No-op on desktop — there is no battery optimisation to lift.
    }

    override fun postponeBackgroundExecutionPrompt() {
        // No-op on desktop — the prompt is never raised.
    }
}
