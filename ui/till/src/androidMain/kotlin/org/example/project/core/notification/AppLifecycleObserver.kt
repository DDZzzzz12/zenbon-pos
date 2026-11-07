package org.example.project.core.notification

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the app is in the foreground, and runs [onEnterForeground] each time it gets there —
 * `PosNotificationService` uses that to come back after Android timed it out in the background.
 */
class AppLifecycleObserver(
    private val onEnterForeground: () -> Unit = {},
) : DefaultLifecycleObserver {
    private val _isAppInForeground = MutableStateFlow(false)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        _isAppInForeground.value = true
        onEnterForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        _isAppInForeground.value = false
    }
}
