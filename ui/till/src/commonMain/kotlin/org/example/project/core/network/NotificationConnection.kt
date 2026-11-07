/**
 * Interface for real-time notification delivery to the client.
 *
 * Abstracts the transport mechanism so that [NotificationViewModel] works
 * identically regardless of the underlying delivery mechanism.
 *
 * Implementations:
 * - [SseNotificationConnection]: HTTP Server-Sent Events client
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.network

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.example.project.core.domain.dto.websocket.NotificationDto

/**
 * Contract for real-time notification delivery to the client layer.
 *
 * Provides connection lifecycle management, message observation,
 * and error reporting regardless of the underlying transport.
 */
interface NotificationConnection {
    /** Current connection state. */
    val connectionState: StateFlow<ConnectionState>

    /** Incoming server messages (hot flow, no replay). */
    val messages: SharedFlow<NotificationDto.NotificationMessage>

    /** Errors encountered during connection or message processing. */
    val errors: SharedFlow<NotificationConnectionError>

    /**
     * The most recent TSE status the server sent, or null before the first one.
     *
     * TSE status is server-wide state the server pushes only on change and once per new stream, while
     * [messages] replays nothing. A consumer that subscribes after that push — a NotificationViewModel
     * re-created on a MainScreen re-mount while this process-wide connection stays open — would
     * otherwise never learn the current mode. Read `.value` to seed state; react to changes through
     * [messages]. Deliberately not cleared by [disconnect]: the next stream's on-connect push
     * overwrites it. It IS cleared to null when [connect] opens a stream against a different server
     * (the base URL changed — pairing screen, or a `LaunchCoordinator` server-address change) so the
     * previous server's status cannot linger after the switch.
     */
    val latestTseStatus: StateFlow<NotificationDto.TseStatusUpdateDto?>

    /**
     * Establish the notification connection.
     *
     * @param endpoint Transport-specific endpoint identifier
     *   (e.g. "api/notifications/stream" for SSE, ignored by in-process bridge).
     */
    suspend fun connect(endpoint: String)

    /** Gracefully close the notification connection. */
    suspend fun disconnect()

    /**
     * Closes the stream, and cancels a connect in flight, when it no longer belongs to the stored session:
     * no access token is stored any more, or the stored one names another principal than the stream was
     * opened for. A stream of the current session is left alone. Like [disconnect] it stops the reconnect
     * loop until the next [connect].
     *
     * Not suspending, so a synchronous session boundary — the startup WIPE runs inside a Koin factory —
     * can call it. Implementations may finish the close asynchronously; the ownership check runs at that
     * point, so a login that already stored its own token in between keeps the stream it opens.
     */
    fun closeIfSessionEnded()

    /** Reset reconnect attempt counter (relevant for the SSE transport). */
    fun resetReconnectAttempts()

    /**
     * Resets the backoff and attempts a connect immediately, reusing the endpoint of the last
     * [connect]. A no-op before the first connect, after [disconnect], and after [dispose]. Android
     * calls this from its connectivity callback so a station recovers in seconds instead of waiting
     * out the slow retry interval (E2E-2026-09-15 N2).
     */
    fun requestImmediateReconnect()

    /**
     * Drop any retained/replayed error so a new subscriber starts clean.
     *
     * Errors are session-scoped. Implementations that replay their last error
     * (e.g. the SSE transport) must clear it here so a session-ending
     * failure — such as an expired-token AuthenticationFailed — is never delivered
     * to the next session's consumer.
     */
    fun clearErrors()

    /** Release all resources held by this connection. */
    fun dispose()

    /**
     * Connection state shared across all [NotificationConnection] implementations.
     */
    sealed class ConnectionState {
        /** Not connected. */
        data object Disconnected : ConnectionState()

        /** Connection in progress. */
        data object Connecting : ConnectionState()

        /** Successfully connected. */
        data object Connected : ConnectionState()

        /** Connection failed after max retry attempts. */
        data object Failed : ConnectionState()
    }

    /**
     * Why authentication prevented the notification stream from opening.
     *
     * A reason, not a sentence: this layer must not decide wording, and the connection layer has
     * neither a language nor access to a resolved string. The presentation layer maps each value
     * to a [org.example.project.core.presentation.strings.StringKey].
     */
    enum class AuthFailureReason {
        /** A token existed but could not be renewed (refresh call failed or was rejected). */
        TOKEN_RENEWAL_FAILED,

        /** No access token was stored at all — there is no session to renew. */
        NO_ACCESS_TOKEN,
    }

    /** Why the transport could not keep or establish the stream. See [AuthFailureReason]. */
    enum class ConnectionFailureReason {
        /** The reconnect policy gave up after its last attempt. */
        RETRIES_EXHAUSTED,

        /** An established stream dropped; a reconnect is scheduled. */
        STREAM_LOST,
    }

    /**
     * Errors that can occur within a [NotificationConnection].
     *
     * [diagnostic] is developer text for the log line only. It is deliberately **not** named
     * `message`: rendering it was the defect that put an English literal inside a German dialog.
     * Anything the operator reads is derived from the typed reason at the presentation layer.
     */
    sealed class NotificationConnectionError(
        val diagnostic: String,
    ) {
        /** Authentication/token issue prevented connection. */
        data class AuthenticationFailed(
            val reason: AuthFailureReason,
        ) : NotificationConnectionError(reason.name)

        /** Network-level connection failure. */
        data class ConnectionFailed(
            val reason: ConnectionFailureReason,
        ) : NotificationConnectionError(reason.name)

        /** Error while processing an incoming message. */
        data class MessageError(
            val detail: String,
        ) : NotificationConnectionError(detail)

        /** Failed to deserialize an incoming message. */
        data class DeserializationFailed(
            val detail: String,
        ) : NotificationConnectionError(detail)

        /** Failed to send an outgoing message. */
        data class SendFailed(
            val detail: String,
        ) : NotificationConnectionError(detail)
    }
}
