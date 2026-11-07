package org.example.project.features.monthlyclose.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto

/**
 * What the fiscal month close needs to know about the DATEV exports of a period: the export log
 * rows (for the `datev_exported` check and the export-status section) and whether the AP ledger
 * export would have creditor invoices to write.
 *
 * A port, declared on the fiscal side and implemented by accounting over its DATEV export log and
 * AP export, so the month close does not depend on either service.
 */
interface MonthlyCloseDatevStatus {
    /**
     * Log rows of every export type overlapping [periodStart]..[periodEnd] that the scheduled
     * auto-export wrote (source AUTO). Only these count as coverage for the checklist.
     */
    suspend fun autoExportLogs(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
    ): List<DatevExportLogDto>

    /** Log rows of every export type and every source overlapping [periodStart]..[periodEnd], for display. */
    suspend fun allExportLogs(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
    ): List<DatevExportLogDto>

    /**
     * The number of creditor invoices the AP ledger export would write for the inclusive ISO dates
     * [startDate]..[endDate], with the export's default exclusion of Kassenbuch-linked invoices, so
     * the answer can never disagree with what that export writes (E2E-2026-09-18 #10).
     */
    suspend fun apInvoiceCount(
        startDate: String,
        endDate: String,
    ): Int
}
