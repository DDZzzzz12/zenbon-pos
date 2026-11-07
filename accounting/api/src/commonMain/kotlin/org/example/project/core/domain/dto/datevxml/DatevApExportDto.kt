/**
 * Container object for DATEV XML Eingangsrechnungen (Accounts Payable) export DTOs.
 *
 * Organizes DTOs for the accountsPayableLedger export type, which generates
 * per-invoice XML files for import into DATEV Unternehmen online.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.datevxml

import kotlinx.serialization.Serializable

/**
 * Container for DATEV XML Eingangsrechnungen export DTOs.
 */
object DatevApExportDto {
    /**
     * Individual accounts payable ledger entry for DATEV XML generation.
     *
     * Maps from Document + InvoiceMetadata to the fields
     * required by the DATEV XML ledger schema (accountsPayableLedger element).
     *
     * @property date Invoice date in YYYY-MM-DD format
     * @property amount Gross amount as decimal string (e.g., "1234.56")
     * @property accountNo Gegenkonto / G/L account number
     * @property bpAccountNo Kreditorenkonto / business partner account (70000–999999999)
     * @property buCode BU-Schlüssel / posting key
     * @property tax VAT rate as decimal string (e.g., "19.00")
     * @property currencyCode Currency code (fixed "EUR")
     * @property invoiceId Rechnungsnummer (invoice number, required)
     * @property bookingText Buchungstext (max 60 chars)
     * @property information Belegtext / additional text
     * @property partyId Business partner ID (Geschäftspartner-ID)
     * @property supplierName Lieferantenname
     * @property supplierCity Lieferantenstadt
     * @property vatId USt-IdNr. (e.g., "DE123456789")
     * @property iban Payment IBAN
     * @property dueDate Fälligkeitsdatum in YYYY-MM-DD format
     * @property deliveryDate Liefer-/Leistungsdatum in YYYY-MM-DD format
     * @property costCategoryId Kost1 (cost center 1)
     * @property costCategoryId2 Kost2 (cost center 2)
     */
    @Serializable
    data class ApLedgerEntryDto(
        val date: String,
        val amount: String,
        val accountNo: String? = null,
        val bpAccountNo: String? = null,
        val buCode: String? = null,
        val tax: String? = null,
        val currencyCode: String = "EUR",
        val invoiceId: String,
        val bookingText: String? = null,
        val information: String? = null,
        val partyId: String? = null,
        val supplierName: String? = null,
        val supplierCity: String? = null,
        val vatId: String? = null,
        val iban: String? = null,
        val dueDate: String? = null,
        val deliveryDate: String? = null,
        val costCategoryId: String? = null,
        val costCategoryId2: String? = null,
        val paidAt: String? = null,
        val shipFromCountry: String? = null,
        val shipToCountry: String? = null,
        val orderId: String? = null,
    )

    /**
     * Configuration DTO for DATEV AP export.
     *
     * Contains settings required by the XML generators for proper
     * document metadata.
     *
     * @property clientName Shop/business name for document header (max 36 chars)
     * @property generatingSystem System identifier (default "POS-KMP")
     * @property consultantNumber Optional DATEV Beraternummer (max 7 chars)
     * @property clientNumber Optional DATEV Mandantennummer (max 5 chars)
     */
    @Serializable
    data class ApExportConfigDto(
        val clientName: String,
        val generatingSystem: String = "POS-KMP",
        val consultantNumber: String? = null,
        val clientNumber: String? = null,
    )

    /**
     * Statistics/preview DTO for DATEV AP export.
     *
     * Provides summary information before export for user confirmation.
     *
     * @property totalInvoices Total number of invoices in the export
     * @property totalAmount Sum of all invoice gross amounts as formatted string
     * @property dateRange Human-readable date range description
     * @property missingMetadataCount Number of invoices without complete metadata
     */
    @Serializable
    data class ApExportStatisticsDto(
        val totalInvoices: Int,
        val totalAmount: String,
        val dateRange: String,
        val missingMetadataCount: Int,
    )
}
