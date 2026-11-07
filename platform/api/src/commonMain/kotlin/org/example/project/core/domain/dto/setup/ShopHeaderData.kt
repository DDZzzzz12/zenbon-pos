/**
 * Data class representing shop header information for receipt printing.
 *
 * Decouples print and PDF formatters from ShopInfoService by providing
 * only the fields needed for receipt headers. The calling service
 * constructs this from its own shop info data source.
 *
 * Lives in shared so that both server-printer (ESC/POS receipts)
 * and server-pdf (PDF receipts) can reference it without cross-depending.
 *
 * @since 1.0.0
 */
package org.example.project.core.domain.dto.setup

import kotlinx.serialization.Serializable

/**
 * Shop header data for receipt printing.
 *
 * Contains the minimal set of shop details required to render
 * the header section of printed receipts (bills, Z-Bons, etc.).
 *
 * @property shopName Business name displayed prominently at the top
 * @property shopAddress Street address line
 * @property shopCity City name
 * @property zipCode Postal/ZIP code
 * @property shopPhone Contact phone number
 * @property shopTaxNumber Tax identification number (USt-IdNr.)
 */
@Serializable
data class ShopHeaderData(
    val shopName: String,
    val shopAddress: String? = null,
    val shopCity: String? = null,
    val zipCode: String? = null,
    val shopPhone: String? = null,
    val shopTaxNumber: String? = null,
)
