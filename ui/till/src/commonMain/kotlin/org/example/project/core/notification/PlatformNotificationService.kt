package org.example.project.core.notification

import kotlinx.coroutines.flow.StateFlow

interface PlatformNotificationService {
    fun startService()

    fun stopService()

    val pendingNavigation: StateFlow<NotificationNavigation?>

    fun setPendingNavigation(navigation: NotificationNavigation)

    fun consumePendingNavigation()

    /**
     * True while the app should explain why it needs to run in the background, before the operator
     * is sent to the system's battery-optimisation settings.
     *
     * Only Android ever raises it: on a person login ([startService]) while the app is still
     * battery-optimised and the operator has not yet chosen [openBackgroundExecutionSettings].
     * Desktop and browser return a flow that stays false.
     */
    val backgroundExecutionPromptVisible: StateFlow<Boolean>

    /** "Set up now": hide the prompt, remember the choice for good, open the system settings. */
    fun openBackgroundExecutionSettings()

    /** "Later", or dismissing the dialog: hide the prompt; it returns on the next person login. */
    fun postponeBackgroundExecutionPrompt()
}
