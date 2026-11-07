/**
 * Model representing a printer discovered via IPP protocol.
 *
 * Contains printer metadata obtained through IPP service discovery
 * including capabilities, location, and current status.
 *
 * @since 1.0.0
 */
package org.example.project.printer.ipp.domain.model

import kotlinx.serialization.Serializable

/**
 * Discovered printer data from IPP protocol.
 *
 * @property name Human-readable printer name
 * @property uri IPP URI for addressing the printer (e.g., "ipp://192.168.1.100/ipp/print")
 * @property makeAndModel Printer manufacturer and model string
 * @property location Physical location description (if configured on the printer)
 * @property state Current printer state (idle, processing, stopped)
 * @property connectionType Connection protocol type (always "IPP" for discovered printers)
 * @property supportedFormats List of supported document format MIME types (e.g., "application/pdf")
 */
@Serializable
data class DiscoveredPrinter(
    val name: String,
    val uri: String,
    val makeAndModel: String? = null,
    val location: String? = null,
    val state: String,
    val connectionType: String = "IPP",
    val supportedFormats: List<String> = emptyList(),
)
