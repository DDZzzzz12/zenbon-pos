package org.example.project.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel

/**
 * Observes a nullable success [message] from ViewModel state and raises a success
 * toast on the global notification queue, then invokes [onClear] so the VM resets
 * its state. The watcher half of the success pattern — [PosNotificationHost] renders.
 *
 * Usage:
 * ```kotlin
 * PosSuccessWatcher(
 *     message = uiState.successMessage,
 *     title = str(SomeKeys.title),
 *     onClear = { viewModel.clearSuccessMessage() },
 * )
 * ```
 *
 * Mounted unconditionally; fed `null` when there is nothing to show. The
 * [LaunchedEffect] is keyed on [message], so a new non-null value re-fires.
 */
@Composable
fun PosSuccessWatcher(
    message: String?,
    title: String,
    onClear: () -> Unit,
    displayMode: DisplayMode = DisplayMode.TOAST,
) {
    val overlay = LocalGlobalOverlayViewModel.current
    LaunchedEffect(message) {
        message?.let {
            overlay.showSuccess(title, it, displayMode)
            onClear()
        }
    }
}
