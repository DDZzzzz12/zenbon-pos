package org.example.project.testutils.fakes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.network.NotificationConnection

/**
 * In-memory [NotificationConnection] whose `errors` flow replays its last value
 * (`replay = 1`), mirroring the real SSE transport.
 *
 * Used to verify that a freshly constructed NotificationViewModel does not inherit
 * a previous session's error — [clearErrors] must drop the seeded error.
 *
 * [emitMessage] records a TSE status into [latestTseStatus] before emitting it, as the real transport
 * does, so a test can deliver a status to "an earlier consumer" and then construct the next one.
 *
 * @param seedError optional error preloaded into the replay cache, simulating a
 *   session-ending failure left behind by the previous session.
 */
class FakeNotificationConnection(
    seedError: NotificationConnection.NotificationConnectionError? = null,
) : NotificationConnection {
    private val _connectionState =
        MutableStateFlow<NotificationConnection.ConnectionState>(NotificationConnection.ConnectionState.Disconnected)
    override val connectionState: StateFlow<NotificationConnection.ConnectionState> = _connectionState

    /** Test-only: move the transport to [state] so collectors observe it. */
    fun emitConnectionState(state: NotificationConnection.ConnectionState) {
        _connectionState.value = state
    }

    private val _messages = MutableSharedFlow<NotificationDto.NotificationMessage>(replay = 0, extraBufferCapacity = 64)
    override val messages: SharedFlow<NotificationDto.NotificationMessage> = _messages

    private val _latestTseStatus = MutableStateFlow<NotificationDto.TseStatusUpdateDto?>(null)
    override val latestTseStatus: StateFlow<NotificationDto.TseStatusUpdateDto?> = _latestTseStatus

    /** Test-only: push a message into the [messages] flow so collectors observe it. */
    suspend fun emitMessage(message: NotificationDto.NotificationMessage) {
        if (message is NotificationDto.TseStatusUpdateDto) _latestTseStatus.value = message
        _messages.emit(message)
    }

    private val _errors =
        MutableSharedFlow<NotificationConnection.NotificationConnectionError>(replay = 1)
    override val errors: SharedFlow<NotificationConnection.NotificationConnectionError> = _errors

    /** Test-only: push a transport error into the [errors] flow so collectors observe it. */
    suspend fun emitError(error: NotificationConnection.NotificationConnectionError) {
        _errors.emit(error)
    }

    var clearErrorsCount: Int = 0
        private set

    init {
        if (seedError != null) _errors.tryEmit(seedError)
    }

    override suspend fun connect(endpoint: String) {}

    /** Test-only: how many times the stream was deliberately closed. */
    var disconnectCount: Int = 0
        private set

    /** Test-only: runs inside [disconnect], so a test can record what else had happened by then. */
    var onDisconnect: () -> Unit = {}

    override suspend fun disconnect() {
        disconnectCount++
        onDisconnect()
    }

    /** Test-only: how many times a session boundary asked to close a stream of an ended session. */
    var closeIfSessionEndedCount: Int = 0
        private set

    override fun closeIfSessionEnded() {
        closeIfSessionEndedCount++
    }

    override fun resetReconnectAttempts() {}

    /** Test-only: how many times a platform asked for an immediate reconnect. */
    var immediateReconnectCount: Int = 0
        private set

    override fun requestImmediateReconnect() {
        immediateReconnectCount++
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun clearErrors() {
        clearErrorsCount++
        _errors.resetReplayCache()
    }

    override fun dispose() {}
}
