package org.example.project.core.websocket

import java.util.concurrent.ConcurrentHashMap

/** Thread-safe registry of live SSE connections. State only — no delivery logic. */
class SseConnectionRegistry {
    private val connections = ConcurrentHashMap<String, SseConnection>()

    fun register(conn: SseConnection) {
        connections[conn.id] = conn
    }

    fun remove(id: String): SseConnection? = connections.remove(id)

    fun forEmployee(employeeId: String): List<SseConnection> = connections.values.filter { it.employeeId == employeeId }

    fun forDevice(deviceId: String): List<SseConnection> = connections.values.filter { it.deviceId == deviceId }

    fun all(): List<SseConnection> = connections.values.toList()

    fun count(): Int = connections.size

    fun clear() = connections.clear()
}
