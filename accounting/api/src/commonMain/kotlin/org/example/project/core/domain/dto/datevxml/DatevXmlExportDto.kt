/**
 * Container object for DATEV XML Schnittstelle export DTOs.
 *
 * Organizes DTOs for DATEV XML Schnittstelle online export functionality,
 * enabling import of cash book data into DATEV Unternehmen online / Kassenbuch online.
 *
 * The export generates a ZIP containing:
 * - document.xml (archive manifest)
 * - Per-day ledger XMLs (Kasse_YYYYMMDD.xml)
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.datevxml

import kotlinx.serialization.Serializable

/**
 * Container for DATEV XML Schnittstelle export DTOs.
 */
object DatevXmlExportDto {
    /**
     * Request DTO for initiating a DATEV XML export.
     *
     * @property startDate Start date in ISO format (YYYY-MM-DD)
     * @property endDate End date in ISO format (YYYY-MM-DD)
     */
    @Serializable
    data class ExportRequestDto(
        val startDate: String,
        val endDate: String,
    )

    /**
     * Individual cash ledger entry for DATEV XML generation.
     *
     * Maps from KassenbuchHeader + KassenbuchLineItem to the fields
     * required by the DATEV XML ledger schema (cashLedger element).
     *
     * @property date Entry date in YYYY-MM-DD format
     * @property amount Decimal amount with sign (e.g., "6352.00" or "-89.50")
     * @property accountNo Gegenkonto (counter account number)
     * @property buCode BU-Schlüssel (posting key)
     * @property costCategoryId Kost1 (cost center 1)
     * @property costCategoryId2 Kost2 (cost center 2)
     * @property tax VAT rate as decimal string (e.g., "19.00")
     * @property information Nachricht/Belegtext (additional text)
     * @property currencyCode Currency code (fixed "EUR")
     * @property invoiceId Beleg-Nr (document/invoice number)
     * @property bookingText Required booking text derived from entry type (max 60 chars)
     * @property linkedPdfFileName Optional PDF filename linked to this entry's header
     * @property headerId Kassenbuch header ID for grouping entries from the same header
     */
    @Serializable
    data class CashLedgerEntryDto(
        val date: String,
        val amount: String,
        val accountNo: String? = null,
        val buCode: String? = null,
        val costCategoryId: String? = null,
        val costCategoryId2: String? = null,
        val tax: String? = null,
        val information: String? = null,
        val currencyCode: String = "EUR",
        val invoiceId: String? = null,
        val bookingText: String,
        val linkedPdfFileName: String? = null,
        val headerId: Long = 0,
    )

    /**
     * Configuration DTO for DATEV XML export.
     *
     * Contains settings required by the XML generators for proper
     * document metadata and account references.
     *
     * @property kassenName Cash register display name (e.g., "Kasse") — used as document.xml property key="3"
     * @property clientName Shop/business name for document header (max 36 chars)
     * @property generatingSystem System identifier (default "POS-KMP")
     * @property consultantNumber Optional DATEV Beraternummer (max 7 chars)
     * @property clientNumber Optional DATEV Mandantennummer (max 5 chars)
     */
    @Serializable
    data class ExportConfigDto(
        val kassenName: String,
        val clientName: String,
        val generatingSystem: String = "POS-KMP",
        val consultantNumber: String? = null,
        val clientNumber: String? = null,
    )

    /**
     * Statistics/preview DTO for DATEV XML export.
     *
     * Provides summary information before export for user confirmation.
     *
     * @property totalEntries Total number of cash ledger entries
     * @property totalDays Number of distinct days with entries
     * @property totalAmount Sum of all entry amounts as formatted string
     * @property dateRange Human-readable date range description
     */
    @Deprecated("Use DatevExportStatisticsDto from core.domain.dto.export")
    @Serializable
    data class ExportStatisticsDto(
        val totalEntries: Int,
        val totalDays: Int,
        val totalAmount: String,
        val dateRange: String,
    )
}
