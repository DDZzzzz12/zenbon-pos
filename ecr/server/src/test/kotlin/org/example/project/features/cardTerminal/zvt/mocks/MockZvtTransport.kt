/**
 * Mock ZVT transport for testing.
 *
 * Provides an in-memory transport that records sent data and
 * replays pre-configured responses for protocol-level testing
 * without requiring a real payment terminal.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.mocks

import org.example.project.features.cardTerminal.zvt.transport.ZvtTransport
import java.io.IOException
import java.util.LinkedList

/**
 * In-memory mock transport for testing ZVT protocol operations.
 *
 * Records all sent bytes and returns pre-queued responses in order.
 * Useful for verifying APDU construction and protocol flow.
 */
class MockZvtTransport : ZvtTransport {
    private var connected = false

    /** All data sent via [send], in order. */
    val sentData = mutableListOf<ByteArray>()

    /** Queue of responses to return from [receive]. */
    private val responseQueue = LinkedList<ByteArray>()

    override val isConnected: Boolean get() = connected

    override suspend fun connect(
        host: String,
        port: Int,
    ) {
        connected = true
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun send(data: ByteArray) {
        if (!connected) throw IllegalStateException("Not connected")
        sentData.add(data.copyOf())
    }

    override suspend fun receive(timeoutMs: Long): ByteArray {
        if (!connected) throw IllegalStateException("Not connected")
        if (responseQueue.isEmpty()) throw IOException("No more mock responses queued")
        return responseQueue.poll()
    }

    /**
     * Enqueues a response to be returned by the next [receive] call.
     *
     * @param data The response bytes
     */
    fun enqueueResponse(data: ByteArray) {
        responseQueue.add(data.copyOf())
    }

    /**
     * Enqueues an ACK response (80 00 00).
     */
    fun enqueueAck() {
        enqueueResponse(byteArrayOf(0x80.toByte(), 0x00, 0x00))
    }

    /**
     * Enqueues a Completion response (06 0F 00).
     */
    fun enqueueCompletion() {
        enqueueResponse(byteArrayOf(0x06, 0x0F, 0x00))
    }

    /**
     * Enqueues a NAK response with the given error code.
     *
     * @param errorCode The error code byte
     */
    fun enqueueNak(errorCode: Int) {
        enqueueResponse(byteArrayOf(0x84.toByte(), errorCode.toByte(), 0x00))
    }

    /**
     * Resets the mock state (clears sent data and response queue).
     */
    fun reset() {
        sentData.clear()
        responseQueue.clear()
        connected = false
    }

    /**
     * Returns the last sent data as a hex string for assertion.
     */
    fun lastSentHex(): String? = sentData.lastOrNull()?.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
}
