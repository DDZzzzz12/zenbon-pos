package org.example.project.core.presentation.components.notification

/**
 * Notification severity levels.
 *
 * Constants only: the rendering components ([org.example.project.ui.components.PosToast],
 * [org.example.project.ui.components.PosNotificationPopup]) own their own icon and colour
 * mapping via `PosToastType` / `PosNotificationSeverity` and the notification palette, so
 * this type carries no Compose values into shared-ui.
 */
enum class NotificationSeverity { SUCCESS, WARNING, ERROR, LOADING }
