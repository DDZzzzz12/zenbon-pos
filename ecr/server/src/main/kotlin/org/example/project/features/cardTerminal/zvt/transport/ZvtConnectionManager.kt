/**
 * Connection lifecycle manager for ZVT terminal communication.
 *
 * Manages connection establishment, health checking, and automatic
 * reconnection with exponential backoff. Provides thread-safe access
 * to the underlying transport via Mutex synchronization.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.project.features.cardTerminal.zvt.models.ZvtConfig
import org.slf4j.LoggerFactory

/**
 * Manages the ZVT transport connection lifecycle.
 *
 * Provides connect/disconnect/reconnect with exponential backoff,
 * health checking via Status-Enquiry, and thread-safe transport access.
 *
 * @property transport The underlying transport implementation
 * @property config Terminal connection configuration
 */
class ZvtConnectionManager(
    private val transport: ZvtTransport,
    private val config: ZvtConfig,
) {
    private val logger = LoggerFactory.getLogger(ZvtConnectionManager::class.java)
    private val mutex = Mutex()

    /** Whether the connection is currently active. */
    val isConnected: Boolean get() = transport.isConnected

    /**
     * Establishes a connection to the payment terminal.
     *
     * Thread-safe — only one connection attempt runs at a time.
     *
     * @throws java.io.IOException if the connection fails
     */
    suspend fun connect() {
        mutex.withLock {
            if (transport.isConnected) {
                logger.debug("Already connected, skipping connect")
                return
            }
            logger.info("Connecting to terminal at ${config.host}:${config.port}")
            transport.connect(config.host, config.port)
        }
    }

    /**
     * Disconnects from the payment terminal.
     *
     * Safe to call multiple times.
     */
    suspend fun disconnect() {
        mutex.withLock {
            logger.info("Disconnecting from terminal")
            transport.disconnect()
        }
    }

    /**
     * Sends data to the terminal with connection safety.
     *
     * @param data The bytes to send
     * @throws IllegalStateException if not connected
     */
    suspend fun send(data: ByteArray) {
        mutex.withLock {
            transport.send(data)
        }
    }

    /**
     * Receives data from the terminal with the configured read timeout.
     *
     * @param timeoutMs Override timeout (uses config default if not specified)
     * @return The received APDU byte array
     */
    suspend fun receive(timeoutMs: Long = config.readTimeoutMs): ByteArray = transport.receive(timeoutMs)

    /**
     * Attempts to reconnect to the terminal using exponential backoff.
     *
     * Retries up to [ZvtConfig.maxRetries] times with increasing delays.
     *
     * @return true if reconnection succeeded, false if all retries exhausted
     */
    suspend fun reconnect(): Boolean {
        mutex.withLock {
            transport.disconnect()
        }

        var currentDelay = config.retryDelayMs

        for (attempt in 1..config.maxRetries) {
            logger.info("Reconnection attempt $attempt/${config.maxRetries} (delay: ${currentDelay}ms)")
            delay(currentDelay)

            try {
                mutex.withLock {
                    transport.connect(config.host, config.port)
                }
                logger.info("Reconnected successfully on attempt $attempt")
                return true
            } catch (e: Exception) {
                logger.warn("Reconnection attempt $attempt failed: ${e.message}")
                currentDelay = (currentDelay * 2).coerceAtMost(30_000L)
            }
        }

        logger.error("All ${config.maxRetries} reconnection attempts failed")
        return false
    }

    /**
     * Executes a block with automatic reconnection on connection failure.
     *
     * If the block throws an IOException, attempts reconnection before retrying.
     *
     * @param block The suspend block to execute
     * @return The result of the block
     * @throws Exception if both the block and reconnection fail
     */
    suspend fun <T> withConnection(block: suspend () -> T): T =
        try {
            block()
        } catch (e: java.io.IOException) {
            logger.warn("Connection error, attempting reconnection: ${e.message}")
            if (reconnect()) {
                block()
            } else {
                throw e
            }
        }
}
