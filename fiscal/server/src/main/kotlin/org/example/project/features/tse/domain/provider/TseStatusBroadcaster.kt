/**
 * Shared utility for broadcasting TSE status updates over WebSocket.
 *
 * Shared so the TSE providers can broadcast status changes to connected clients.
 *
 * Tracks previous state to only broadcast on actual state transitions,
 * reducing unnecessary WebSocket traffic.
 *
 * @since 1.0.0
 */
package org.example.project.features.tse.domain.provider

import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.tse.TseHealthState
import org.example.project.core.websocket.NotificationHub
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Broadcasts TSE status updates to all connected WebSocket clients.
 *
 * Maintains state to detect transitions and avoid redundant broadcasts.
 * Also sends current status to newly connected clients.
 *
 * Diff key is (connected, health, mode, ops). The mode field strictly
 * carries the configured mode; health carries observed hardware state.
 */
@OptIn(ExperimentalTime::class)
object TseStatusBroadcaster {
    private val logger = LoggerFactory.getLogger(TseStatusBroadcaster::class.java)

    @Volatile private var lastConnected: Boolean? = null

    @Volatile private var lastHealth: TseHealthState? = null

    @Volatile private var lastMode: String? = null

    @Volatile private var lastOps: Set<String> = emptySet()

    // Cached for sendCurrentStatusTo
    @Volatile private var cachedCertExpirationDate: Long = 0L

    @Volatile private var cachedDaysUntilCertExpiry: Long? = null

    /**
     * Whether a bundled firmware update is waiting, established once per start.
     *
     * Not part of the diff key: it is an advisory condition that rides along with whatever
     * status change is being sent, exactly as certificate expiry does. Checked once because
     * the bundled firmware ships inside the application — its availability changes when the
     * application is updated, which means a restart, not spontaneously at runtime.
     */
    @Volatile private var firmwareUpdateAvailable: Boolean = false

    /** Records the startup firmware check so every later broadcast can carry it. */
    fun recordFirmwareUpdateAvailable(available: Boolean) {
        firmwareUpdateAvailable = available
        if (available) {
            logger.info("A bundled TSE firmware update is available; operators will be notified")
        }
    }

    // Test seam — production code path is unchanged when null.
    @Volatile internal var broadcastSink: (suspend (NotificationDto.TseStatusUpdateDto) -> Unit)? = null

    /** Test-only: redirect broadcasts to a sink instead of NotificationHub. Pass null to restore. */
    fun setBroadcastSinkForTest(sink: (suspend (NotificationDto.TseStatusUpdateDto) -> Unit)?) {
        broadcastSink = sink
    }

    /**
     * Broadcasts TSE status update to all connected clients if state changed.
     *
     * @param connected Current connection state
     * @param health Observed hardware health state
     * @param mode Current TSE mode (NORMAL, DISABLED, SIMULATION)
     * @param ops Active maintenance operation names, if any
     * @param message Optional status message
     * @param certificateExpirationDate Unix timestamp (seconds) of certificate expiry, 0 if unknown
     * @param daysUntilCertExpiry Pre-computed days until certificate expiry, or null if unknown
     */
    suspend fun broadcastIfStateChanged(
        connected: Boolean,
        health: TseHealthState,
        mode: String,
        ops: Set<String> = emptySet(),
        message: String? = null,
        certificateExpirationDate: Long = 0L,
        daysUntilCertExpiry: Long? = null,
    ) {
        val changed =
            connected != lastConnected ||
                health != lastHealth ||
                mode != lastMode ||
                ops != lastOps
        if (!changed) return

        lastConnected = connected
        lastHealth = health
        lastMode = mode
        lastOps = ops

        // Compute days until certificate expiry if not provided
        val computedDaysUntilExpiry =
            daysUntilCertExpiry ?: if (certificateExpirationDate > 0L) {
                val nowSeconds = System.currentTimeMillis() / 1000L
                ((certificateExpirationDate - nowSeconds) / (24 * 60 * 60)).coerceAtLeast(0)
            } else {
                null
            }

        // Cache for sendCurrentStatusTo
        cachedCertExpirationDate = certificateExpirationDate
        cachedDaysUntilCertExpiry = computedDaysUntilExpiry

        val update =
            NotificationDto.TseStatusUpdateDto(
                timestamp = Clock.System.now().toString(),
                connected = connected,
                healthy = (health == TseHealthState.HEALTHY),
                health = health,
                mode = mode,
                maintenanceOps = ops,
                message = message,
                certificateExpirationDate = certificateExpirationDate,
                daysUntilCertExpiry = computedDaysUntilExpiry,
                firmwareUpdateAvailable = firmwareUpdateAvailable,
            )

        val sink = broadcastSink
        if (sink != null) {
            sink(update)
        } else {
            try {
                NotificationHub.broadcastToAll(update)
            } catch (e: Exception) {
                logger.warn("Failed to broadcast TSE status update: ${e.message}")
            }
        }

        logger.info(
            "Broadcast TSE status: connected={} mode={} health={} ops={}",
            connected,
            mode,
            health,
            ops,
        )
    }

    /**
     * Sends the current cached TSE status to a specific WebSocket connection.
     *
     * Called when a new client connects so it immediately knows the TSE state
     * without waiting for the next state-change broadcast.
     *
     * @param connectionId The WebSocket connection to send the status to
     */
    suspend fun sendCurrentStatusTo(connectionId: String) {
        val connected = lastConnected ?: return
        val health = lastHealth ?: return
        val mode = lastMode ?: return

        val update =
            NotificationDto.TseStatusUpdateDto(
                timestamp = Clock.System.now().toString(),
                connected = connected,
                healthy = (health == TseHealthState.HEALTHY),
                health = health,
                mode = mode,
                maintenanceOps = lastOps,
                message = null,
                certificateExpirationDate = cachedCertExpirationDate,
                daysUntilCertExpiry = cachedDaysUntilCertExpiry,
                firmwareUpdateAvailable = firmwareUpdateAvailable,
            )

        try {
            NotificationHub.sendToConnection(connectionId, update)
            logger.debug(
                "Sent initial TSE status to connection {}: connected={} mode={} health={}",
                connectionId,
                connected,
                mode,
                health,
            )
        } catch (e: Exception) {
            logger.warn("Failed to send initial TSE status to $connectionId: ${e.message}")
        }
    }

    /**
     * Registers a WebSocket connection listener that pushes the current TSE status
     * to every newly connected client.
     *
     * Should be called once during application startup.
     */
    fun registerConnectionListener() {
        NotificationHub.onConnect { connectionId ->
            sendCurrentStatusTo(connectionId)
        }
        logger.info("TSE status connection listener registered")
    }

    /**
     * Resets broadcast state to force next status update to be broadcast.
     *
     * Useful when restarting TSE connection.
     */
    fun resetState() {
        lastConnected = null
        lastHealth = null
        lastMode = null
        lastOps = emptySet()
    }
}
