package org.example.project.testutils.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.project.core.notification.NotificationNavigation
import org.example.project.core.notification.PlatformNotificationService

class FakePlatformNotificationService : PlatformNotificationService {
    var startServiceCallCount = 0
    var stopServiceCallCount = 0

    /** Runs inside [startService], so a test can capture state at the moment the stream would open. */
    var onStartService: () -> Unit = {}

    private val _pendingNavigation = MutableStateFlow<NotificationNavigation?>(null)
    override val pendingNavigation: StateFlow<NotificationNavigation?> = _pendingNavigation.asStateFlow()

    override fun startService() {
        startServiceCallCount++
        onStartService()
    }

    override fun stopService() {
        stopServiceCallCount++
    }

    override fun setPendingNavigation(navigation: NotificationNavigation) {
        _pendingNavigation.value = navigation
    }

    override fun consumePendingNavigation() {
        _pendingNavigation.value = null
    }

    /** Test-only: raise or clear the prompt the way AndroidNotificationService.startService does. */
    val backgroundExecutionPrompt = MutableStateFlow(false)
    override val backgroundExecutionPromptVisible: StateFlow<Boolean> = backgroundExecutionPrompt.asStateFlow()

    var openBackgroundExecutionSettingsCallCount = 0
    var postponeBackgroundExecutionPromptCallCount = 0

    override fun openBackgroundExecutionSettings() {
        openBackgroundExecutionSettingsCallCount++
        backgroundExecutionPrompt.value = false
    }

    override fun postponeBackgroundExecutionPrompt() {
        postponeBackgroundExecutionPromptCallCount++
        backgroundExecutionPrompt.value = false
    }
}
