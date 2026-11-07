/**
 * Network printer implementation using TCP sockets.
 *
 * Handles printing to network-connected thermal printers using raw TCP sockets.
 * Sends ESC/POS byte data directly to printer's IP address and port (typically 9100).
 * Uses Kotlin coroutines for non-blocking I/O operations.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.data.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.printer.core.domain.service.PrintServiceInterface
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Network printer service implementation.
 *
 * Uses TCP sockets to send ESC/POS byte data to network printers.
 * Expects printerIdentifier in "IP:Port" format (e.g., "192.168.1.100:9100").
 */
class NetworkPrintServiceImpl : PrintServiceInterface {
    override suspend fun print(
        data: ByteArray,
        printerIdentifier: String,
    ): String =
        withContext(Dispatchers.IO) {
            val parts = printerIdentifier.split(":")
            require(parts.size == 2) {
                "Invalid printerIdentifier '$printerIdentifier'; expected IP:Port (e.g., 192.168.1.100:9100)"
            }

            val ip = parts[0]
            val port = parts[1].toIntOrNull() ?: 9100

            // Bound TCP connect + write so an offline printer fails fast instead of
            // hanging the calling request for the OS default (~75 s on Linux).
            Socket().use { socket ->
                socket.soTimeout = WRITE_TIMEOUT_MS
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                socket.getOutputStream().use { outputStream ->
                    outputStream.write(data)
                    outputStream.flush()
                }
            }

            "Print job sent successfully to network printer: $printerIdentifier"
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val WRITE_TIMEOUT_MS = 5_000
    }
}
