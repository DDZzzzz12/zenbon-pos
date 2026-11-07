package org.example.project.core.presentation.components.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Dedicated ViewModel for the global success / error / loading overlay queue.
 *
 * Consumes [OverlayEventBus.events] and exposes [currentNotification] for [PosNotificationHost].
 * Lives in `shared-ui` so both `composeApp` and `adminApp` can host the same overlay surface.
 */
class GlobalOverlayViewModel(
    private val eventBus: OverlayEventBus,
) : ViewModel(),
    OverlayEventBus by eventBus {
    private val logger = Logger.withTag("GlobalOverlayViewModel")

    private val _notificationQueue = MutableStateFlow<List<NotificationState>>(emptyList())

    /** The currently visible notification (head of queue), or null if empty. */
    val currentNotification: StateFlow<NotificationState?> =
        _notificationQueue
            .map { it.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is OverlayEvent.Enqueue -> enqueue(event.state)
                    is OverlayEvent.DismissById -> dismissById(event.id)
                    OverlayEvent.DismissCurrent -> dismissCurrent()
                    OverlayEvent.ClearAll -> clearAll()
                }
            }
        }
    }

    /**
     * Dismiss the front notification and advance the queue.
     *
     * Note: this overrides the [OverlayEventBus] member inherited by delegation.
     * Enqueue methods route through the bus so any observer sees the event;
     * dismiss/clear are local queue operations, so we bypass the bus and mutate state directly.
     */
    override fun dismissCurrent() {
        _notificationQueue.update { it.drop(1) }
    }

    /** Dismiss a specific notification by the ID returned from [showLoading]. */
    override fun dismissById(id: String) {
        _notificationQueue.update { queue -> queue.filter { it.id != id } }
    }

    /** Clear every enqueued overlay. Called on logout so session A cannot toast over session B. */
    override fun clearAll() {
        _notificationQueue.update { emptyList() }
    }

    private fun enqueue(notification: NotificationState) {
        _notificationQueue.update { queue ->
            // Skip duplicate content (equals ignores id), preserving existing behavior
            if (queue.any { it == notification }) queue else queue + notification
        }
    }
}
