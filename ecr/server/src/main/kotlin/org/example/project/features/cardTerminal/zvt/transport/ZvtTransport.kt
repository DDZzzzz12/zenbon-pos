/**
 * Transport interface for ZVT protocol communication.
 *
 * Defines the contract for sending and receiving raw byte data
 * between the ECR and PT. Implementations handle the actual
 * network communication (TCP/IP, serial, or mock for testing).
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.transport

/**
 * Transport layer interface for ZVT protocol communication.
 *
 * Provides async (coroutine-based) methods for establishing connections,
 * sending raw data, and receiving responses with configurable timeouts.
 */
interface ZvtTransport {
    /**
     * Establishes a connection to the payment terminal.
     *
     * @param host The terminal's IP address or hostname
     * @param port The TCP port number
     * @throws java.io.IOException if the connection cannot be established
     */
    suspend fun connect(
        host: String,
        port: Int,
    )

    /**
     * Closes the connection to the payment terminal.
     *
     * Safe to call multiple times. Does nothing if already disconnected.
     */
    suspend fun disconnect()

    /**
     * Sends raw bytes to the payment terminal.
     *
     * @param data The byte array to send
     * @throws java.io.IOException if the send fails
     * @throws IllegalStateException if not connected
     */
    suspend fun send(data: ByteArray)

    /**
     * Receives a complete APDU from the payment terminal.
     *
     * Reads the control field (2 bytes), then the length field (1-3 bytes),
     * then the data block. Returns the complete APDU as a byte array.
     *
     * @param timeoutMs Maximum time to wait for data in milliseconds
     * @return The complete APDU byte array
     * @throws java.io.IOException if the read fails or times out
     * @throws IllegalStateException if not connected
     */
    suspend fun receive(timeoutMs: Long): ByteArray

    /**
     * Whether the transport is currently connected.
     */
    val isConnected: Boolean
}
