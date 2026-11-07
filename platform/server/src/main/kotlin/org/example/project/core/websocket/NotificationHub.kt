package org.example.project.core.websocket

import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.SharedFlow
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.utils.TestOnlyApi

/**
 * Static facade over [NotificationPublisher]. Stays an `object` because
 * `TseStatusBroadcaster` is a Kotlin `object` that calls it statically. All real
 * logic lives in [NotificationPublisher] / [SseConnectionRegistry] / [NotificationEventLog].
 */
object NotificationHub {
    @Volatile
    private var publisher = NotificationPublisher(SseConnectionRegistry(), NotificationEventLog())

    val broadcastMessages: SharedFlow<NotificationDto.NotificationMessage> get() = publisher.broadcastMessages

    suspend fun addSseConnection(
        connectionId: String,
        employeeId: String,
        outgoing: SendChannel<OutgoingFrame>,
        employeeRole: EmployeeRole? = null,
    ): String = publisher.addSseConnection(connectionId, employeeId, outgoing, deviceId = null, employeeRole = employeeRole)

    suspend fun addSseConnection(
        connectionId: String,
        employeeId: String,
        deviceId: String,
        outgoing: SendChannel<OutgoingFrame>,
        employeeRole: EmployeeRole? = null,
        isStation: Boolean = false,
    ): String =
        publisher.addSseConnection(
            connectionId,
            employeeId,
            outgoing,
            deviceId = deviceId,
            employeeRole = employeeRole,
            isStation = isStation,
        )

    suspend fun broadcastToAll(
        message: NotificationDto.NotificationMessage,
        excludeEmployeeId: String? = null,
    ) = publisher.broadcastToAll(message, excludeEmployeeId)

    suspend fun sendToEmployee(
        employeeId: String,
        message: NotificationDto.NotificationMessage,
    ) = publisher.sendToEmployee(employeeId, message)

    suspend fun sendToDevice(
        deviceId: String,
        message: NotificationDto.NotificationMessage,
    ) = publisher.sendToDevice(deviceId, message)

    suspend fun sendToConnection(
        connectionId: String,
        message: NotificationDto.NotificationMessage,
    ) = publisher.sendToConnection(connectionId, message)

    fun connectionScopedEvent(
        employeeId: String,
        message: NotificationDto.NotificationMessage,
    ): SseEvent = publisher.connectionScopedEvent(employeeId, message)

    fun replayFor(
        employeeId: String,
        lastEventId: Long,
    ) = publisher.replayFor(employeeId, lastEventId)

    fun removeConnection(connectionId: String) = publisher.removeConnection(connectionId)

    fun disconnectEmployee(employeeId: String): Int = publisher.disconnectEmployee(employeeId)

    fun onConnect(listener: suspend (String) -> Unit) = publisher.onConnect(listener)

    fun replaceOnConnectListeners(
        key: String,
        register: () -> Unit,
    ) = publisher.replaceOnConnectListeners(key, register)

    fun getConnectionsForEmployee(employeeId: String) = publisher.forEmployee(employeeId)

    fun getEmployeesByActiveConnections(): List<String> = publisher.getEmployeesByActiveConnections()

    fun getDevicesByActiveConnections(): List<String> = publisher.getDevicesByActiveConnections()

    fun getActiveConnectionCount(): Int = publisher.getActiveConnectionCount()

    fun getConnectionStats(): Map<String, Any> = publisher.getConnectionStats()

    fun registerInProcessListener(
        employeeId: String,
        l: suspend (NotificationDto.NotificationMessage) -> Unit,
    ) = publisher.registerInProcessListener(employeeId, l)

    fun unregisterInProcessListener(employeeId: String) = publisher.unregisterInProcessListener(employeeId)

    fun hasInProcessListener(employeeId: String): Boolean = publisher.hasInProcessListener(employeeId)

    @TestOnlyApi
    fun resetForTest() {
        publisher.reset()
    }
}
