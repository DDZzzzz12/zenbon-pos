package org.example.project.features.notifications.presentation

import org.example.project.core.network.NotificationConnection
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.NotificationKeys

/**
 * Maps a transport-level failure reason to the operator-facing text that describes it.
 *
 * The connection layer reports *why* the stream failed as a typed reason; only here — in the
 * presentation layer, which is allowed to know about strings — does that become something a
 * person reads. Keeping the mapping in one exhaustive `when` per reason type means a new reason
 * fails the build instead of silently reaching a dialog as developer English, which is exactly
 * how "Authentication failed: Unable to refresh access token" ended up under a German title.
 */
object NotificationConnectionErrorKeys {
    /** Text for an authentication failure that ended the notification session. */
    fun messageKey(reason: NotificationConnection.AuthFailureReason): StringKey =
        when (reason) {
            NotificationConnection.AuthFailureReason.TOKEN_RENEWAL_FAILED ->
                NotificationKeys.authErrorSessionRenewalFailed

            NotificationConnection.AuthFailureReason.NO_ACCESS_TOKEN ->
                NotificationKeys.authErrorNoSession
        }

    /** Text for a transport failure on the notification stream. */
    fun messageKey(reason: NotificationConnection.ConnectionFailureReason): StringKey =
        when (reason) {
            NotificationConnection.ConnectionFailureReason.RETRIES_EXHAUSTED ->
                NotificationKeys.connectionErrorRetriesExhausted

            NotificationConnection.ConnectionFailureReason.STREAM_LOST ->
                NotificationKeys.connectionErrorStreamLost
        }
}
