package org.example.project.core.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Browser [PlatformNotificationService].
 *
 * Mirrors `DesktopNotificationService`: it only carries the pending-navigation handoff, because the
 * live connection itself is owned by `NotificationViewModel`, not by this service. `startService` /
 * `stopService` exist for Android's foreground service and have nothing to do in a tab.
 */
class WasmNotificationService : PlatformNotificationService {
    private val _pendingNavigation = MutableStateFlow<NotificationNavigation?>(null)
    override val pendingNavigation: StateFlow<NotificationNavigation?> = _pendingNavigation.asStateFlow()

    override fun setPendingNavigation(navigation: NotificationNavigation) {
        _pendingNavigation.value = navigation
    }

    override fun consumePendingNavigation() {
        _pendingNavigation.value = null
    }

    override fun startService() {
        // No-op — there is no background service in a browser tab.
    }

    override fun stopService() {
        // No-op — see startService.
    }

    override val backgroundExecutionPromptVisible: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override fun openBackgroundExecutionSettings() {
        // No-op — a browser tab has no battery optimisation to lift.
    }

    override fun postponeBackgroundExecutionPrompt() {
        // No-op — the prompt is never raised in a tab.
    }
}
