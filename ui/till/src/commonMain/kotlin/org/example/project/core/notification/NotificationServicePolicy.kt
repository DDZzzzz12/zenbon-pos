package org.example.project.core.notification

import org.example.project.core.auth.SessionStatus

/**
 * The decisions Android's `PosNotificationService` makes, kept free of Android types so they are unit-tested
 * on the desktop leg.
 *
 * The service holds the till's notification stream while the app is in the background. It must hold it
 * **only for a person logged in on this till in this process**: the stored token is not that. After a crash
 * Android revives the service alone in a new process, and the stored token then belongs to whoever was
 * logged in before — the startup WIPE is about to discard it, and the till is on its login screen. Streaming
 * with it bound the till to that previous person until the next login, and their billing request arrived on
 * a till where nobody saw it (NOTE-8, emulator pass 5).
 */
object NotificationServicePolicy {
    /** What the service does with a start command. */
    enum class StartAction {
        /** Logout asked the service to stop: close the stream and stop. */
        STOP,

        /** No person is logged in on this till: stop without opening a stream. */
        STOP_NO_SESSION,

        /** Open (or keep) the stream for the logged-in person. */
        CONNECT,
    }

    /**
     * @param stopRequested the command is the logout's stop action.
     * @param sessionStatus this process's session. `AUTHENTICATED` only after a login in this process —
     *   a fresh process starts with the WIPE, whatever token is stored.
     */
    fun onStartCommand(
        stopRequested: Boolean,
        sessionStatus: SessionStatus,
    ): StartAction =
        when {
            stopRequested -> StartAction.STOP
            !streamsFor(sessionStatus) -> StartAction.STOP_NO_SESSION
            else -> StartAction.CONNECT
        }

    /**
     * Whether opening a session starts the service: a person's login, the order station's session exchange (a
     * station has no login — the exchange is its login) and the device-pairing completion all ask this, through
     * `SessionState.markLoggedInAndStartNotifications`, so none of them can decide differently.
     *
     * A freshly paired order station used to get no stream at all on Android: only the login started the service,
     * a station never logs in, and on Android nothing else opens the stream — so bill requests, TSE status and the
     * server's session-terminated push never reached it.
     *
     * @param sessionStatus the session *after* it was marked logged in. Asked after, not before: the service
     *   reads the same session in [onStartCommand], and one started ahead of the mark stops at once (NOTE-8).
     */
    fun startOnSessionOpened(sessionStatus: SessionStatus): Boolean = streamsFor(sessionStatus)

    /**
     * Whether coming to the foreground restarts the service.
     *
     * Only a service Android timed out ([stoppedByTimeout]) is restarted this way — and only while the session it
     * served is still live: opening a session starts the service itself ([startOnSessionOpened]), and a till
     * whose service was never started must not gain one by being brought to the front.
     */
    fun restartOnForeground(
        stoppedByTimeout: Boolean,
        sessionStatus: SessionStatus,
    ): Boolean = stoppedByTimeout && streamsFor(sessionStatus)

    /** The one definition of "this session may hold the stream": a session logged in in this process. */
    private fun streamsFor(sessionStatus: SessionStatus): Boolean = sessionStatus == SessionStatus.AUTHENTICATED
}
