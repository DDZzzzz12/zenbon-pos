package org.example.project.features.issuereport.domain.service

import org.example.project.core.domain.dto.issuereport.IssueReportDto

interface IssueReportService {
    suspend fun settings(): IssueReportDto.SettingsDto

    /** Replaces the target. A new API host clears the stored token in the same call (it was entered for the old host). */
    suspend fun saveSettings(request: IssueReportDto.UpdateSettingsRequestDto): IssueReportDto.SettingsDto

    suspend fun setToken(token: String): IssueReportDto.SettingsDto

    suspend fun clearToken(): IssueReportDto.SettingsDto

    /** Builds the report once, stores it for [employeeId] and returns it with the id a send must name. */
    suspend fun preview(
        employeeId: String,
        reporterRole: String,
        request: IssueReportDto.ReportRequestDto,
    ): IssueReportDto.PreviewDto

    /** Posts the snapshot named by `request.previewId`; without one, only a log-free report is built and posted. */
    suspend fun submit(
        employeeId: String,
        deviceId: String,
        reporterRole: String,
        request: IssueReportDto.ReportRequestDto,
    ): IssueReportDto.CreatedDto
}
