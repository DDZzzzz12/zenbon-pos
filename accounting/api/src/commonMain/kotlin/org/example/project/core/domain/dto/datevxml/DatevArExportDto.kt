package org.example.project.core.domain.dto.datevxml

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.datev.UnassignedSummary

/** DTOs for the DATEV accountsReceivableLedger (Ausgangsrechnungen / Debitoren) export — slice 4d. */
object DatevArExportDto {
    /** One accountsReceivableLedger entry: a single VAT-rate split of one invoice. */
    @Serializable
    data class ArLedgerEntryDto(
        val date: String, // issue date YYYY-MM-DD
        val amount: String, // gross split amount, decimal string
        val accountNo: String? = null, // Erlöskonto (Gegenkonto)
        val bpAccountNo: String? = null, // Debitor Personenkonto
        val buCode: String? = null,
        val tax: String? = null, // VAT rate, e.g. "19.00"
        val currencyCode: String = "EUR",
        val invoiceId: String, // invoice number
        val bookingText: String? = null,
        val information: String? = null,
        val partyId: String? = null,
        val buyerName: String? = null,
        val buyerCity: String? = null,
        val vatId: String? = null,
        val dueDate: String? = null,
        val deliveryDate: String? = null, // service date
    )

    @Serializable
    data class ArExportConfigDto(
        val clientName: String,
        val generatingSystem: String = "POS-KMP",
        val consultantNumber: String? = null,
        val clientNumber: String? = null,
    )

    /** An invoice the export cannot post because an account did not resolve (preflight, §6). */
    @Serializable
    data class UnresolvedInvoiceDto(
        val invoiceNumber: String,
        val buyerName: String,
        val reason: String, // e.g. "no Personenkonto and no Sammeldebitor" or "VAT rate 19.00 unmapped"
    )

    @Serializable
    data class ArExportStatisticsDto(
        val totalInvoices: Int,
        val totalAmount: String,
        val dateRange: String,
        val unresolved: List<UnresolvedInvoiceDto> = emptyList(),
        /**
         * The VAT splits that will be exported without a revenue account (`accountNo`) because the
         * mapping's income STANDARD cell for their rate has „Konto automatisch zuordnen" switched
         * off. Not a block — DATEV completes the account — but the operator must be told. Null = none.
         */
        val unassignedContra: UnassignedSummary? = null,
    )
}
