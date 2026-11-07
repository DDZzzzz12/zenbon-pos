package org.example.project.core.utils

import androidx.compose.runtime.compositionLocalOf
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel

/**
 * CompositionLocal for NotificationViewModel.
 *
 * Provides access to the notification ViewModel from anywhere in the composition tree
 * without needing to pass it through every composable parameter.
 *
 * Usage:
 * ```kotlin
 * // At top level (MainScreen):
 * CompositionLocalProvider(LocalNotificationViewModel provides notificationViewModel) {
 *     // Your content
 * }
 *
 * // In any child composable:
 * val notificationViewModel = LocalNotificationViewModel.current
 * ```
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
val LocalNotificationViewModel =
    compositionLocalOf<NotificationViewModel> {
        error("NotificationViewModel not provided. Make sure to provide it at the root level using CompositionLocalProvider.")
    }
