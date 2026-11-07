package org.example.project.core.websocket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.websocket.models.MessageTargeting
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Delivery + targeting for SSE notifications. Composes a [SseConnectionRegistry]
 * (state) and a [NotificationEventLog] (ids + replay). No global state — fully
 * constructor-injected and unit-testable.
 */
class NotificationPublisher(
    private val registry: SseConnectionRegistry,
    private val eventLog: NotificationEventLog,
) {
    private val logger = LoggerFactory.getLogger(NotificationPublisher::class.java)

    private val onConnectListeners = CopyOnWriteArrayList<suspend (String) -> Unit>()
    private val inProcessListeners = ConcurrentHashMap<String, suspend (NotificationDto.NotificationMessage) -> Unit>()

    private val _broadcastMessages = MutableSharedFlow<NotificationDto.NotificationMessage>(extraBufferCapacity = 64)
    val broadcastMessages: SharedFlow<NotificationDto.NotificationMessage> = _broadcastMessages.asSharedFlow()

    /** Guards [onConnectListeners] against a concurrent [replaceOnConnectListeners]; re-entrant. */
    private val onConnectLock = Any()

    /** What each keyed registration added, so the next registration under that key can take it back out. */
    private val keyedOnConnectListeners = HashMap<String, List<suspend (String) -> Unit>>()

    fun onConnect(listener: suspend (String) -> Unit) {
        synchronized(onConnectLock) { onConnectListeners.add(listener) }
    }

    /**
     * Run [register] and remember the onConnect listeners it adds under [key], first removing whatever
     * an earlier call with the same [key] added.
     *
     * For a registration that is repeated by design but must take effect once. `HttpClientModule`
     * registers the TSE status listener at startup and again after every provider hot-swap, and the
     * code that builds that listener (the protected TSE module) appends a fresh lambda each time — so
     * each TSE configuration change used to add one more duplicate status frame to every new stream.
     * The listeners are identified by what [register] added rather than by a caller-supplied lambda,
     * because the code that creates the lambda cannot pass one in.
     *
     * [register] runs under the lock [onConnect] takes; the lock is re-entrant, so [register] may call
     * [onConnect] on the same thread.
     */
    fun replaceOnConnectListeners(
        key: String,
        register: () -> Unit,
    ) {
        synchronized(onConnectLock) {
            keyedOnConnectListeners.remove(key)?.forEach { previous ->
                onConnectListeners.removeIf { it === previous }
            }
            val before = onConnectListeners.toList()
            register()
            keyedOnConnectListeners[key] = onConnectListeners.filter { added -> before.none { it === added } }
        }
    }

    fun registerInProcessListener(
        employeeId: String,
        l: suspend (NotificationDto.NotificationMessage) -> Unit,
    ) {
        inProcessListeners[employeeId] =
            l
    }

    fun unregisterInProcessListener(employeeId: String) {
        inProcessListeners.remove(employeeId)
    }

    fun hasInProcessListener(employeeId: String): Boolean = inProcessListeners.containsKey(employeeId)

    suspend fun addSseConnection(
        connectionId: String,
        employeeId: String,
        outgoing: SendChannel<OutgoingFrame>,
        deviceId: String? = null,
        employeeRole: EmployeeRole? = null,
        isStation: Boolean = false,
    ): String {
        streamsSupersededBy(employeeId, deviceId).forEach { existing ->
            existing.outgoing.close()
            registry.remove(existing.id)
        }
        registry.register(SseConnection(connectionId, employeeId, deviceId, outgoing))
        logger.info("SSE connection added: $connectionId for employee=$employeeId device=$deviceId station=$isStation")

        if (employeeRole != null) {
            AuthNotificationManager.registerEmployeeRole(employeeId, employeeRole)
            val managerRoles = setOf(EmployeeRole.MANAGER, EmployeeRole.ADMIN)
            if (employeeRole in managerRoles) {
                CoroutineScope(Dispatchers.IO).launch { AuthNotificationManager.deliverQueuedApprovals(employeeId) }
            }
        }
        onConnectListeners.forEach { l ->
            try {
                l(connectionId)
            } catch (e: Exception) {
                logger.warn("onConnect listener failed for $connectionId: ${e.message}")
            }
        }
        return connectionId
    }

    suspend fun broadcastToAll(
        message: NotificationDto.NotificationMessage,
        excludeEmployeeId: String? = null,
    ) {
        _broadcastMessages.tryEmit(message)
        // TSE status is current state, not history: every connection is pushed the live status on
        // connect (TseStatusBroadcaster.sendCurrentStatusTo). Buffering it would replay a mode the
        // device already moved past and raise a false mode-change warning (E2E-2026-09-15 N4).
        val event =
            if (message is NotificationDto.TseStatusUpdateDto) {
                SseEvent(eventLog.ephemeralId(), message, MessageTargeting.BroadcastAll)
            } else {
                eventLog.append(message, MessageTargeting.BroadcastAll)
            }
        registry
            .all()
            .filter { excludeEmployeeId == null || it.employeeId != excludeEmployeeId }
            .forEach { enqueue(it, OutgoingFrame.Event(event)) }
    }

    suspend fun sendToEmployee(
        employeeId: String,
        message: NotificationDto.NotificationMessage,
    ) {
        inProcessListeners[employeeId]?.let { l -> runCatching { l(message) } }
        val event = eventLog.append(message, MessageTargeting.TargetEmployee(employeeId))
        registry.forEmployee(employeeId).forEach { enqueue(it, OutgoingFrame.Event(event)) }
    }

    /** Connection-scoped initial state (e.g. TSE onConnect): id assigned but NOT buffered. */
    suspend fun sendToConnection(
        connectionId: String,
        message: NotificationDto.NotificationMessage,
    ) {
        val conn = registry.all().firstOrNull { it.id == connectionId } ?: return
        enqueue(conn, OutgoingFrame.Event(connectionScopedEvent(conn.employeeId, message)))
    }

    /**
     * A connection-scoped event for the stream itself to write: a fresh id, never buffered, never
     * replayed and not enqueued anywhere. For a frame that must precede everything else on the stream
     * (the `server_hello`), which [sendToConnection] cannot guarantee — its frame waits in the
     * connection's queue behind the replay and behind anything enqueued since the connection registered.
     */
    fun connectionScopedEvent(
        employeeId: String,
        message: NotificationDto.NotificationMessage,
    ): SseEvent = SseEvent(eventLog.ephemeralId(), message, MessageTargeting.TargetEmployee(employeeId))

    /** Device-scoped delivery for terminal lifecycle messages (revoke / deactivate). */
    suspend fun sendToDevice(
        deviceId: String,
        message: NotificationDto.NotificationMessage,
    ) {
        val event = eventLog.append(message, MessageTargeting.TargetDevice(deviceId))
        registry.forDevice(deviceId).forEach { enqueue(it, OutgoingFrame.Event(event)) }
    }

    fun replayFor(
        employeeId: String,
        lastEventId: Long,
    ): List<SseEvent> = eventLog.replay(lastEventId) { targetingMatches(it.targeting, employeeId) }

    fun removeConnection(connectionId: String) {
        registry.remove(connectionId)?.let {
            it.outgoing.close()
            if (registry.forEmployee(it.employeeId).isEmpty()) {
                AuthNotificationManager.unregisterEmployee(it.employeeId)
            }
            logger.info("SSE connection removed: $connectionId for employee ${it.employeeId}")
        }
    }

    fun disconnectEmployee(employeeId: String): Int {
        val toClose = registry.forEmployee(employeeId)
        toClose.forEach {
            it.outgoing.close()
            registry.remove(it.id)
        }
        return toClose.size
    }

    fun forEmployee(employeeId: String): List<SseConnection> = registry.forEmployee(employeeId)

    fun getEmployeesByActiveConnections(): List<String> = registry.all().map { it.employeeId }.distinct()

    fun getDevicesByActiveConnections(): List<String> = registry.all().mapNotNull { it.deviceId }.distinct()

    fun getActiveConnectionCount(): Int = registry.count()

    fun getConnectionStats(): Map<String, Any> =
        mapOf(
            "totalConnections" to registry.count(),
            "uniqueEmployees" to
                registry
                    .all()
                    .map { it.employeeId }
                    .distinct()
                    .size,
            "uniqueDevices" to
                registry
                    .all()
                    .mapNotNull { it.deviceId }
                    .distinct()
                    .size,
        )

    fun reset() {
        registry.all().forEach { it.outgoing.close() }
        registry.clear()
        eventLog.clear()
        synchronized(onConnectLock) {
            onConnectListeners.clear()
            keyedOnConnectListeners.clear()
        }
        inProcessListeners.clear()
    }

    /**
     * The live streams a new stream for [employeeId] supersedes.
     *
     * Superseding on connect exists to clean up THIS client's own stale stream: a network blip leaves
     * the previous stream registered server-side, so without the eviction one device would accumulate
     * streams it no longer reads. The key is therefore the physical device, never the person — one
     * device keeps one stream.
     *
     * Keying it on the employee (which this did until 2026-09-17, for every stream that was not a
     * station's) is far too broad. Two devices legitimately share one employee — a manager with the
     * till and the order station open at once, and every order station running as the one shared
     * `terminal-device` account — and each new stream then closed the other device's. The evicted
     * client reconnects a second later and evicts the stream that just evicted it: a livelock, measured
     * at 62 streams in 80 seconds strictly alternating between two tills logged in as one admin.
     *
     * It also enforced no session policy, so nothing is lost by narrowing it: closing a notification
     * stream does not end anyone's session — the JWT stays valid and the client simply reconnects.
     * Revocation is the mechanism that does end one, and it is deliberately NOT narrowed:
     * [disconnectEmployee] still closes every stream of the employee, because revoking the shared
     * device account (deactivation, a credential reset) is meant to stop every station.
     *
     * A stream with no usable device id (null or blank) cannot be narrowed to "the same physical
     * device" — there is nothing to key on — so it falls back to the employee-wide supersede.
     */
    private fun streamsSupersededBy(
        employeeId: String,
        deviceId: String?,
    ): List<SseConnection> {
        val sameEmployee = registry.forEmployee(employeeId)
        if (deviceId.isNullOrBlank()) return sameEmployee
        return sameEmployee.filter { it.deviceId == deviceId }
    }

    private fun enqueue(
        conn: SseConnection,
        frame: OutgoingFrame,
    ) {
        if (conn.outgoing.trySend(frame).isClosed) registry.remove(conn.id)
    }

    private fun targetingMatches(
        targeting: MessageTargeting,
        employeeId: String,
    ): Boolean =
        when (targeting) {
            is MessageTargeting.BroadcastAll -> true
            is MessageTargeting.TargetEmployee -> targeting.employeeId == employeeId
            is MessageTargeting.TargetEmployees -> employeeId in targeting.employeeIds
            is MessageTargeting.TargetRole -> false // replay is employee-scoped
            is MessageTargeting.TargetRoles -> false
            is MessageTargeting.TargetPredicate -> false
            is MessageTargeting.TargetDevice -> false // device-scoped events are not replayed by employee
        }
}
