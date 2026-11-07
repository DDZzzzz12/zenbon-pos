package org.example.project.core.notification

import org.example.project.ui.auth.SessionState

/**
 * Opens this till's session and starts the notification service for it — the one way every session opening does
 * both: a person's login (`LoginViewModel.login`), the device-pairing completion (`LoginViewModel.onDevicePaired`)
 * and the order station's session exchange (`LaunchCoordinator`).
 *
 * **The session first, then the service** (NOTE-8): Android's service streams only for an AUTHENTICATED session
 * and reads it when it starts, so a service started before the mark stops without a stream. Whether it starts at
 * all is [NotificationServicePolicy.startOnSessionOpened]'s call, asked of the session as it now is.
 */
fun SessionState.markLoggedInAndStartNotifications(notificationService: PlatformNotificationService) {
    markLoggedIn()
    if (NotificationServicePolicy.startOnSessionOpened(status)) notificationService.startService()
}
