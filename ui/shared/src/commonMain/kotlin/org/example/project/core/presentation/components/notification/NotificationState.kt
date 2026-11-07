package org.example.project.core.presentation.components.notification

import org.example.project.core.presentation.error.UiError
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Data class representing the state of a notification popup.
 *
 * @since 1.0.0
 */
data class NotificationState
    @OptIn(ExperimentalUuidApi::class)
    constructor(
        val id: String = Uuid.random().toString(),
        val title: String,
        val message: String,
        val type: NotificationSeverity,
        val displayMode: DisplayMode = DisplayMode.DIALOG,
        val isVisible: Boolean = true,
        val autoDismissMs: Long? = null,
        val actions: List<NotificationAction> = emptyList(),
        val isRetryable: Boolean = false,
        val dismissEnable: Boolean = true,
        /**
         * The typed error this notification represents, when it represents one.
         *
         * Carrying it lets the host render the detail dialog — traceId, error code, field errors,
         * copy-to-clipboard — from the global overlay, instead of that detail being reachable only
         * on screens that happen to mount their own watcher. Null for ordinary info/success/loading
         * notifications, which have no error behind them.
         */
        val error: UiError? = null,
    ) {
        /** Compares notifications by content, ignoring the unique [id]. */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NotificationState) return false
            return title == other.title &&
                message == other.message &&
                type == other.type &&
                displayMode == other.displayMode
        }

        override fun hashCode(): Int {
            var result = title.hashCode()
            result = 31 * result + message.hashCode()
            result = 31 * result + type.hashCode()
            result = 31 * result + displayMode.hashCode()
            return result
        }
    }
