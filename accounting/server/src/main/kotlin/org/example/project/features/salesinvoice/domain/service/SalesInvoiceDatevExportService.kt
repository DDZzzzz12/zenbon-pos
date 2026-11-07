package org.example.project.features.salesinvoice.domain.service

import org.example.project.core.domain.dto.datevxml.DatevArExportDto
import java.util.UUID

/**
 * The DATEV Debitoren (accountsReceivableLedger) export for STANDALONE outgoing invoices — slice 4d.
 *
 * STANDALONE is the only revenue origin exported here: `POS_SETTLED` / `POS_RECEIVABLE` turnover is
 * already in the Kassenbuch DATEV export, and re-exporting it would double-book the sale. The export
 * is a manual ADMIN action, not part of the auto-catchup cadence.
 */
interface SalesInvoiceDatevExportService {
    /** Preflight: counts, total, and any invoices whose accounts do not resolve. No side effects. */
    suspend fun statistics(
        startDate: String,
        endDate: String,
    ): DatevArExportDto.ArExportStatisticsDto

    /**
     * Builds the accountsReceivableLedger ZIP for STANDALONE invoices issued in [startDate, endDate].
     * Throws SALES_INVOICE_EXPORT_BLOCKED (409) listing the offending invoices if any account is
     * unresolvable — never a partial ZIP. Records the run via DatevExportLogService.
     */
    suspend fun export(
        startDate: String,
        endDate: String,
        exportedBy: UUID,
    ): ByteArray
}
