package org.example.project.features.datevexportlog.domain.service

import org.example.project.core.domain.dto.datevexportlog.CatchupPreviewDto
import org.example.project.core.domain.dto.datevexportlog.CatchupResultDto
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import java.util.UUID

interface DatevExportCatchupService {
    suspend fun preview(
        type: DatevExportType,
        cadence: DatevExportCadence,
    ): CatchupPreviewDto

    suspend fun execute(
        type: DatevExportType,
        cadence: DatevExportCadence,
        exportedByUserId: UUID,
    ): CatchupResultDto
}
