/**
 * API client interface for DSFinV-K (German fiscal compliance) operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

interface DsfinvkApiClient {
    suspend fun exportZip(request: DsfinvkExportDto.ExportRequestDto): ByteArray

    suspend fun validate(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ValidationResultDto

    suspend fun getStatistics(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ExportStatisticsDto
}
