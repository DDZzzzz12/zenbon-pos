/**
 * TCP/IP transport implementation for ZVT protocol communication.
 *
 * Handles non-blocking socket I/O using Kotlin coroutines and
 * standard Java NIO. Implements frame-level reading that understands
 * the APDU structure to read complete messages.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds

/**
 * TCP socket-based transport for ZVT protocol communication.
 *
 * Creates a standard TCP socket connection to the payment terminal
 * and reads/writes complete APDU frames. Uses [Dispatchers.IO] for
 * all blocking socket operations.
 *
 * @property connectionTimeoutMs Timeout for establishing the TCP connection
 */
class ZvtTcpTransport(
    private val connectionTimeoutMs: Int = 5000,
) : ZvtTransport {
    private val logger = LoggerFactory.getLogger(ZvtTcpTransport::class.java)

    private var socket: Socket? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    override suspend fun connect(
        host: String,
        port: Int,
    ) {
        withContext(Dispatchers.IO) {
            disconnect()
            logger.info("Connecting to terminal at $host:$port (timeout: ${connectionTimeoutMs}ms)")
            val newSocket = Socket()
            newSocket.connect(InetSocketAddress(host, port), connectionTimeoutMs)
            newSocket.tcpNoDelay = true
            socket = newSocket
            logger.info("Connected to terminal at $host:$port")
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            socket?.let {
                try {
                    if (!it.isClosed) {
                        it.close()
                        logger.info("Disconnected from terminal")
                    }
                } catch (e: IOException) {
                    logger.warn("Error closing socket: ${e.message}")
                }
            }
            socket = null
        }
    }

    override suspend fun send(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val sock = socket ?: throw IllegalStateException("Not connected to terminal")
            if (sock.isClosed) throw IllegalStateException("Socket is closed")

            if (logger.isDebugEnabled) {
                logger.debug("Sending ${data.size} bytes: ${data.toHexString()}")
            }
            sock.getOutputStream().write(data)
            sock.getOutputStream().flush()
        }
    }

    override suspend fun receive(timeoutMs: Long): ByteArray =
        withContext(Dispatchers.IO) {
            withTimeout(timeoutMs.milliseconds) {
                val sock = socket ?: throw IllegalStateException("Not connected to terminal")
                if (sock.isClosed) throw IllegalStateException("Socket is closed")

                sock.soTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val inputStream = sock.getInputStream()
                val out = ByteArrayOutputStream()

                // Step 1: Read control field (2 bytes)
                val controlField = ByteArray(2)
                readFully(inputStream, controlField)
                out.write(controlField)

                // Step 2: Read length field (1 byte, possibly extended to 3)
                val firstLengthByte = inputStream.read()
                if (firstLengthByte == -1) throw IOException("Connection closed while reading length")
                out.write(firstLengthByte)

                val dataLength: Int
                if (firstLengthByte == 0xFF) {
                    // Extended length: next 2 bytes
                    val extLength = ByteArray(2)
                    readFully(inputStream, extLength)
                    out.write(extLength)
                    dataLength = ((extLength[0].toInt() and 0xFF) shl 8) or (extLength[1].toInt() and 0xFF)
                } else {
                    dataLength = firstLengthByte
                }

                // Step 3: Read data block
                if (dataLength > 0) {
                    val dataBlock = ByteArray(dataLength)
                    readFully(inputStream, dataBlock)
                    out.write(dataBlock)
                }

                val result = out.toByteArray()
                if (logger.isDebugEnabled) {
                    logger.debug("Received ${result.size} bytes: ${result.toHexString()}")
                }
                result
            }
        }

    /**
     * Reads exactly [buffer.size] bytes from the input stream, blocking until complete.
     *
     * @throws IOException if the stream ends before all bytes are read
     */
    private fun readFully(
        inputStream: java.io.InputStream,
        buffer: ByteArray,
    ) {
        var offset = 0
        while (offset < buffer.size) {
            val bytesRead = inputStream.read(buffer, offset, buffer.size - offset)
            if (bytesRead == -1) throw IOException("Connection closed unexpectedly (read $offset/${buffer.size} bytes)")
            offset += bytesRead
        }
    }

    /**
     * Extension to convert a byte array to a hex string for debug logging.
     */
    private fun ByteArray.toHexString(): String = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
}
