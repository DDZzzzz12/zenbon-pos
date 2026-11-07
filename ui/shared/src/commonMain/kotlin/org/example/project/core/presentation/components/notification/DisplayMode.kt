package org.example.project.core.presentation.components.notification

/** Display mode for notifications. */
enum class DisplayMode {
    /** Non-blocking snackbar-style notification with auto-dismiss */
    TOAST,

    /** Modal dialog requiring user action to dismiss */
    DIALOG,
}
