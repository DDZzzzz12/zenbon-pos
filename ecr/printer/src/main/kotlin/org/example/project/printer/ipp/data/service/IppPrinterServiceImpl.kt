/**
 * IPP printer service implementation using de.gmuth:ipp-client library and jmDNS.
 *
 * Provides printer discovery, status checking, and printing via IPP protocol.
 * Discovery uses a two-tier strategy:
 * 1. **CUPS** (Linux/macOS) — queries the local CUPS daemon on localhost:631
 * 2. **jmDNS** (cross-platform fallback) — discovers `_ipp._tcp` services via mDNS/DNS-SD
 *
 * The jmDNS fallback works on Windows, Linux, and macOS without requiring CUPS.
 * It discovers network printers that advertise themselves via Bonjour/mDNS and returns
 * real IPP URIs that can be used directly with [IppPrinter] for printing and status checks.
 *
 * **Windows note**: Printers discovered via mDNS are network printers that support IPP.
 * USB-only printers or printers that do not advertise via mDNS will not appear.
 * For those, the printer must be added manually using its IP address
 * (e.g., `ipp://<printer-ip>:631/ipp/print`).
 *
 * @since 1.0.0
 */
package org.example.project.printer.ipp.data.service

import de.gmuth.ipp.attributes.DocumentFormat
import de.gmuth.ipp.client.CupsClient
import de.gmuth.ipp.client.IppClient
import de.gmuth.ipp.client.IppConfig
import de.gmuth.ipp.client.IppPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.core.domain.enums.setup.IppDocumentFormat
import org.example.project.printer.core.domain.service.PrintServiceInterface
import org.example.project.printer.ipp.domain.model.DiscoveredPrinter
import org.example.project.printer.ipp.domain.service.IppPrinterService
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URI
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * IPP printer service implementation.
 *
 * Uses de.gmuth:ipp-client to communicate with IPP-capable printers.
 * Implements both [IppPrinterService] for discovery/status and
 * [PrintServiceInterface] for the print factory integration.
 */
class IppPrinterServiceImpl :
    IppPrinterService,
    PrintServiceInterface {
    private val logger = LoggerFactory.getLogger(IppPrinterServiceImpl::class.java)

    /**
     * Shared IPP configuration that trusts self-signed SSL certificates.
     *
     * CUPS typically uses self-signed certificates for HTTPS (ipps://) connections.
     * Without this, Java's default SSL trust store rejects the connection with
     * SSLHandshakeException / PKIX path building failed.
     */
    private fun createIppConfig(): IppConfig =
        IppConfig().apply {
            trustAnyCertificateAndSSLHostname()
        }

    companion object {
        /** Default timeout for jmDNS mDNS service discovery in milliseconds. */
        private const val MDNS_DISCOVERY_TIMEOUT_MS = 8000L

        /** mDNS service type for IPP printers (standard, unencrypted). */
        private const val IPP_SERVICE_TYPE = "_ipp._tcp.local."

        /** mDNS service type for IPP printers over TLS (encrypted). */
        private const val IPPS_SERVICE_TYPE = "_ipps._tcp.local."
    }

    /**
     * Discover printers on the local network.
     *
     * Uses a two-tier discovery strategy:
     * 1. **CUPS** — queries the local CUPS daemon (Linux/macOS). Fast and includes
     *    locally configured printers (USB via CUPS, shared printers, etc.).
     * 2. **jmDNS** — if CUPS is unavailable or returns no printers, falls back to
     *    mDNS/DNS-SD discovery of `_ipp._tcp` and `_ipps._tcp` services.
     *    Works cross-platform (Windows, Linux, macOS) without CUPS.
     *
     * Discovered printers include supported document formats and real IPP URIs
     * that can be used directly with [printViaIpp] and [getPrinterStatus].
     *
     * @return Result containing list of discovered printers or error
     */
    override suspend fun discoverPrinters(): List<DiscoveredPrinter> =
        withContext(Dispatchers.IO) {
            logger.info("Discovering printers...")
            val discoveredPrinters = mutableListOf<DiscoveredPrinter>()
            discoverViaCups(discoveredPrinters)
            if (discoveredPrinters.isEmpty()) {
                discoverViaMdns(discoveredPrinters)
            }
            discoveredPrinters.toList()
        }

    /**
     * Discover printers via the local CUPS daemon.
     *
     * Connects to CUPS on localhost:631 and queries all configured printers.
     * Silently catches errors when CUPS is not running (e.g., on Windows).
     *
     * @param results Mutable list to append discovered printers to
     */
    private fun discoverViaCups(results: MutableList<DiscoveredPrinter>) {
        try {
            logger.info("Discovering ViaCups...")
            val cupsClient = CupsClient(ippClient = IppClient(createIppConfig()))
            val printers = cupsClient.getPrinters()

            printers.forEach { printer ->
                val state =
                    try {
                        when (printer.state.code) {
                            3 -> "idle"
                            4 -> "processing"
                            5 -> "stopped"
                            else -> "unknown"
                        }
                    } catch (_: Exception) {
                        "unknown"
                    }

                val formats =
                    try {
                        extractSupportedFormats(printer)
                    } catch (_: Exception) {
                        emptyList()
                    }

                results.add(
                    DiscoveredPrinter(
                        name = printer.name.text,
                        uri = printer.printerUri.toString(),
                        makeAndModel =
                            try {
                                printer.makeAndModel.text
                            } catch (_: Exception) {
                                null
                            },
                        location =
                            try {
                                printer.location.text
                            } catch (_: Exception) {
                                null
                            },
                        state = state,
                        supportedFormats = formats,
                    ),
                )
            }

            if (results.isNotEmpty()) {
                logger.info("Discovered ${results.size} printer(s) via CUPS")
            }
        } catch (e: Exception) {
            logger.debug("CUPS discovery failed (CUPS may not be running): ${e.message}")
        }
    }

    /**
     * Discover printers via mDNS/DNS-SD using jmDNS.
     *
     * Scans the local network for `_ipp._tcp` and `_ipps._tcp` services advertised
     * via multicast DNS (Bonjour). Works on all platforms without requiring CUPS.
     *
     * Each discovered service provides an IPP URI constructed from the service's
     * host, port, and resource path (TXT record "rp" field).
     *
     * Note: Only network printers that advertise via mDNS are found.
     * USB-only printers or printers not broadcasting mDNS will not appear.
     *
     * @param results Mutable list to append discovered printers to
     */
    private fun discoverViaMdns(results: MutableList<DiscoveredPrinter>) {
        try {
            JmDNS.create(InetAddress.getLocalHost()).use { jmdns ->
                logger.info("Discovering ViaMdns...")
                // Discover both plain IPP and IPP-over-TLS printers
                val ippServices = jmdns.list(IPP_SERVICE_TYPE, MDNS_DISCOVERY_TIMEOUT_MS)
                val ippsServices = jmdns.list(IPPS_SERVICE_TYPE, MDNS_DISCOVERY_TIMEOUT_MS)

                // Track URIs to deduplicate printers that advertise both IPP and IPPS
                val seenUris = mutableSetOf<String>()

                // Process plain IPP services first
                for (service in ippServices) {
                    val printer = mapServiceInfoToPrinter(service, scheme = "ipp") ?: continue
                    if (seenUris.add(printer.uri)) {
                        results.add(printer)
                    }
                }

                // Process IPPS services, skip duplicates
                for (service in ippsServices) {
                    val printer = mapServiceInfoToPrinter(service, scheme = "ipps") ?: continue
                    if (seenUris.add(printer.uri)) {
                        results.add(printer)
                    }
                }

                if (results.isNotEmpty()) {
                    logger.info("Discovered ${results.size} printer(s) via mDNS/DNS-SD")
                } else {
                    logger.debug("No IPP printers found via mDNS/DNS-SD")
                }
            }
        } catch (e: Exception) {
            logger.debug("mDNS discovery failed: ${e.message}")
        }
    }

    /**
     * Map a jmDNS [ServiceInfo] to a [DiscoveredPrinter].
     *
     * Constructs the IPP URI from the service's host address, port, and
     * resource path (TXT record "rp" field). Extracts printer metadata
     * from standard IPP/Bonjour TXT record keys.
     *
     * @param serviceInfo The resolved mDNS service info
     * @param scheme URI scheme to use ("ipp" or "ipps")
     * @return Mapped [DiscoveredPrinter] or null if the service lacks required fields
     */
    private fun mapServiceInfoToPrinter(
        serviceInfo: ServiceInfo,
        scheme: String,
    ): DiscoveredPrinter? {
        val resourcePath = serviceInfo.getPropertyString("rp") ?: return null

        // Prefer IP address over hostname for reliability (avoids .local resolution issues)
        val host = serviceInfo.hostAddresses.firstOrNull() ?: serviceInfo.server ?: return null
        val port = serviceInfo.port
        val ippUri = "$scheme://$host:$port/$resourcePath"

        // Extract supported formats from the "pdl" TXT record (comma-separated MIME types)
        val formats =
            serviceInfo
                .getPropertyString("pdl")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

        // "ty" = human-readable printer type/model, "note" = admin-assigned location
        val makeAndModel =
            serviceInfo.getPropertyString("ty")
                ?: serviceInfo.getPropertyString("product")?.removeSurrounding("(", ")")

        return DiscoveredPrinter(
            name = serviceInfo.name,
            uri = ippUri,
            makeAndModel = makeAndModel,
            location = serviceInfo.getPropertyString("note"),
            state = "unknown", // mDNS TXT records don't carry printer state
            supportedFormats = formats,
        )
    }

    /**
     * Get the current status of a specific printer via IPP.
     *
     * @param printerUri IPP URI of the printer to check
     * @return Result containing printer details with current state or error
     */
    override suspend fun getPrinterStatus(printerUri: String): DiscoveredPrinter =
        withContext(Dispatchers.IO) {
            val printer = IppPrinter(URI(printerUri), ippConfig = createIppConfig())

            val state =
                try {
                    when (printer.state.code) {
                        3 -> "idle"
                        4 -> "processing"
                        5 -> "stopped"
                        else -> "unknown"
                    }
                } catch (_: Exception) {
                    "unknown"
                }

            val formats =
                try {
                    extractSupportedFormats(printer)
                } catch (_: Exception) {
                    emptyList()
                }

            DiscoveredPrinter(
                name = printer.name.text,
                uri = printerUri,
                makeAndModel =
                    try {
                        printer.makeAndModel.text
                    } catch (_: Exception) {
                        null
                    },
                location =
                    try {
                        printer.location.text
                    } catch (_: Exception) {
                        null
                    },
                state = state,
                supportedFormats = formats,
            )
        }

    /**
     * Send raw print data to a printer via IPP protocol using default octet-stream format.
     *
     * @param data Raw byte data (ESC/POS commands) to send
     * @param printerUri IPP URI of the target printer
     * @return Result containing success message or error
     */
    override suspend fun printViaIpp(
        data: ByteArray,
        printerUri: String,
    ): String = printViaIpp(data, printerUri, IppDocumentFormat.ESC_POS)

    /**
     * Send print data to a printer via IPP protocol with specified document format.
     *
     * For ESC_POS format, sends raw bytes as application/octet-stream (default).
     * For PDF format, sends the document with application/pdf MIME type.
     *
     * @param data Print data bytes (ESC/POS commands or PDF document)
     * @param printerUri IPP URI of the target printer
     * @param documentFormat Format of the print data
     * @return Result containing success message or error
     */
    override suspend fun printViaIpp(
        data: ByteArray,
        printerUri: String,
        documentFormat: IppDocumentFormat,
    ): String =
        withContext(Dispatchers.IO) {
            val printer = IppPrinter(URI(printerUri), ippConfig = createIppConfig())
            val inputStream = ByteArrayInputStream(data)

            when (documentFormat) {
                IppDocumentFormat.ESC_POS -> printer.printJob(inputStream)
                IppDocumentFormat.PDF -> printer.printJob(inputStream, DocumentFormat.PDF)
            }

            val formatLabel = if (documentFormat == IppDocumentFormat.PDF) "PDF" else "ESC/POS"
            "Print job ($formatLabel) sent successfully to IPP printer: $printerUri"
        }

    /**
     * Get supported document formats for a specific printer.
     *
     * Queries the printer's document-format-supported attribute via IPP.
     *
     * @param printerUri IPP URI of the printer to query
     * @return Result containing list of supported MIME type strings or error
     */
    override suspend fun getSupportedFormats(printerUri: String): List<String> =
        withContext(Dispatchers.IO) {
            val printer = IppPrinter(URI(printerUri), ippConfig = createIppConfig())
            extractSupportedFormats(printer)
        }

    /**
     * PrintServiceInterface implementation for factory integration.
     *
     * Delegates to [printViaIpp] for IPP-based printing with default ESC/POS format.
     *
     * @param data ESC/POS byte commands to print
     * @param printerIdentifier IPP URI of the target printer
     * @return Result with success message or error details
     */
    override suspend fun print(
        data: ByteArray,
        printerIdentifier: String,
    ): String = printViaIpp(data, printerIdentifier)

    /**
     * Extract supported document formats from an IPP printer's attributes.
     *
     * Reads the "document-format-supported" attribute which lists MIME types
     * the printer can accept (e.g., "application/pdf", "application/octet-stream").
     *
     * @param printer The IPP printer to query
     * @return List of supported MIME type strings
     */
    private fun extractSupportedFormats(printer: IppPrinter): List<String> =
        try {
            printer.documentFormatSupported.map { it.toString() }
        } catch (_: Exception) {
            emptyList()
        }
}
