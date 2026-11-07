package org.example.project.features.issuereport.data.remote

import org.example.project.core.domain.dto.issuereport.IssueReportDto

/**
 * API client for `/api/issue-reports`. The token is write-only: [setToken] sends one, nothing here
 * returns one. Throws `PosApiException` / `NetworkApiException` from the shared error layer.
 */
interface IssueReportApiClient {
    suspend fun preview(request: IssueReportDto.ReportRequestDto): IssueReportDto.PreviewDto

    suspend fun submit(request: IssueReportDto.ReportRequestDto): IssueReportDto.CreatedDto

    suspend fun settings(): IssueReportDto.SettingsDto

    suspend fun saveSettings(request: IssueReportDto.UpdateSettingsRequestDto): IssueReportDto.SettingsDto

    suspend fun setToken(token: String): IssueReportDto.SettingsDto

    suspend fun clearToken(): IssueReportDto.SettingsDto
}
