package org.example.project.core.presentation.components.notification

/** Action that can be attached to a notification. */
data class NotificationAction(
    val label: String,
    val action: () -> Unit,
    val isPrimary: Boolean = false,
)
