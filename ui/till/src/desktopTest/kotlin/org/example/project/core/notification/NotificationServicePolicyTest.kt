package org.example.project.core.notification

import org.example.project.core.auth.SessionStatus
import org.example.project.core.notification.NotificationServicePolicy.StartAction
import org.example.project.testutils.fakes.FakePlatformNotificationService
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.ui.auth.SessionState
import org.example.project.ui.auth.StartupPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decisions of Android's `PosNotificationService`, which the desktop leg cannot start.
 *
 * NOTE-8 (emulator pass 5): after a crash Android revived the service alone, and it streamed with the stored
 * token — the previous person's — although the till was on its login screen. The old gate was "a token is stored";
 * the session of this process is what counts, and a fresh process starts `UNAUTHENTICATED` (startup WIPE).
 */
class NotificationServicePolicyTest {
    @Test
    fun `a service started with nobody logged in does not stream`() {
        assertEquals(StartAction.STOP_NO_SESSION, NotificationServicePolicy.onStartCommand(stopRequested = false, SessionStatus.UNAUTHENTICATED))
    }

    @Test
    fun `a session still being checked is not a logged-in session`() {
        assertEquals(StartAction.STOP_NO_SESSION, NotificationServicePolicy.onStartCommand(stopRequested = false, SessionStatus.CHECKING))
    }

    @Test
    fun `a service started for a logged-in session streams`() {
        assertEquals(StartAction.CONNECT, NotificationServicePolicy.onStartCommand(stopRequested = false, SessionStatus.AUTHENTICATED))
    }

    @Test
    fun `the logout's stop action stops, whatever the session`() {
        SessionStatus.entries.forEach { status ->
            assertEquals(StartAction.STOP, NotificationServicePolicy.onStartCommand(stopRequested = true, status), "$status")
        }
    }

    @Test
    fun `a service Android timed out comes back in the foreground while the session lives`() {
        assertTrue(NotificationServicePolicy.restartOnForeground(stoppedByTimeout = true, SessionStatus.AUTHENTICATED))
    }

    @Test
    fun `a timed-out service stays stopped once the session has ended`() {
        assertFalse(NotificationServicePolicy.restartOnForeground(stoppedByTimeout = true, SessionStatus.UNAUTHENTICATED))
    }

    /** A login starts the service itself, and a till that never ran one must not gain one by coming to the front. */
    @Test
    fun `coming to the foreground does not start a service that was not timed out`() {
        assertFalse(NotificationServicePolicy.restartOnForeground(stoppedByTimeout = false, SessionStatus.AUTHENTICATED))
    }

    @Test
    fun `opening a session starts the service only for a logged-in session`() {
        assertTrue(NotificationServicePolicy.startOnSessionOpened(SessionStatus.AUTHENTICATED))
        assertFalse(NotificationServicePolicy.startOnSessionOpened(SessionStatus.UNAUTHENTICATED))
        assertFalse(NotificationServicePolicy.startOnSessionOpened(SessionStatus.CHECKING))
    }

    /** One definition: a session opening starts the service exactly when the started service would stream. */
    @Test
    fun `a session opening starts the service exactly when the service would stream`() {
        SessionStatus.entries.forEach { status ->
            assertEquals(
                NotificationServicePolicy.onStartCommand(stopRequested = false, status) == StartAction.CONNECT,
                NotificationServicePolicy.startOnSessionOpened(status),
                "$status",
            )
        }
    }

    /**
     * The shared opening every login, pairing and station exchange goes through: the session is AUTHENTICATED by
     * the time the service starts, so the service streams instead of stopping (NOTE-8).
     */
    @Test
    fun `opening a session marks it logged in before it starts the service`() {
        val session = SessionState(FakeTokenStore.create(), StartupPolicy.WIPE)
        val notifications = FakePlatformNotificationService()
        var statusAtStart: SessionStatus? = null
        notifications.onStartService = { statusAtStart = session.status }

        session.markLoggedInAndStartNotifications(notifications)

        assertEquals(1, notifications.startServiceCallCount)
        assertEquals(SessionStatus.AUTHENTICATED, statusAtStart)
    }
}
