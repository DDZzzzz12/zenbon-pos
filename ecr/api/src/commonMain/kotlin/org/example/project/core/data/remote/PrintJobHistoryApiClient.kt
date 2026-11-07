/**
 * API client interface for print job history operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.printer.PrinterDto

interface PrintJobHistoryApiClient {
    suspend fun getPrintJobHistory(
        startDate: String,
        endDate: String,
        limit: Int = 50,
        status: String? = null,
        printType: String? = null,
        locationId: Long? = null,
    ): List<PrinterDto.PrintJobHistoryDto>
}
