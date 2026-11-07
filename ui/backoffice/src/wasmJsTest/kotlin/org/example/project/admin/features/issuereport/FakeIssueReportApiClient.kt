package org.example.project.admin.features.issuereport

import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.features.issuereport.data.remote.IssueReportApiClient

/**
 * Records every call and every request body. [calls] is what the capability tests assert on: a gate
 * that is declared but never consulted leaves this list non-empty.
 */
class FakeIssueReportApiClient : IssueReportApiClient {
    val calls = mutableListOf<String>()
    val reportsSent = mutableListOf<IssueReportDto.ReportRequestDto>()
    val settingsSent = mutableListOf<IssueReportDto.UpdateSettingsRequestDto>()
    val tokensSent = mutableListOf<String>()

    var settings: IssueReportDto.SettingsDto = defaultSettings()
    var created = IssueReportDto.CreatedDto(issueNumber = 42, issueUrl = "https://github.com/DDZzzzz12/POS-KMP/issues/42")

    /** Thrown by every write and by preview/submit while set; reads of settings are unaffected. */
    var failWith: Throwable? = null

    /** Every preview gets a fresh id, so a test can tell which preview a send referred to. */
    private var previews = 0

    /** Thrown by submit only while set (e.g. a 409 for an expired preview). */
    var failSubmitWith: Throwable? = null

    override suspend fun preview(request: IssueReportDto.ReportRequestDto): IssueReportDto.PreviewDto {
        calls += "preview"
        reportsSent += request
        failWith?.let { throw it }
        previews++
        return IssueReportDto.PreviewDto(
            title = request.title,
            body = "${request.description}\n\n---\nscreen: ${request.screen}\nlogs: ${request.includeLogs}",
            repository = "${settings.owner}/${settings.repo}",
            isPublic = settings.isPublic,
            previewId = "preview-$previews",
        )
    }

    override suspend fun submit(request: IssueReportDto.ReportRequestDto): IssueReportDto.CreatedDto {
        calls += "submit"
        reportsSent += request
        failWith?.let { throw it }
        failSubmitWith?.let { throw it }
        return created
    }

    override suspend fun settings(): IssueReportDto.SettingsDto {
        calls += "settings"
        return settings
    }

    override suspend fun saveSettings(request: IssueReportDto.UpdateSettingsRequestDto): IssueReportDto.SettingsDto {
        calls += "saveSettings"
        settingsSent += request
        failWith?.let { throw it }
        settings =
            settings.copy(
                owner = request.owner,
                repo = request.repo,
                apiBaseUrl = request.apiBaseUrl,
                isPublic = request.isPublic,
                useUpdateToken = request.useUpdateToken,
            )
        return settings
    }

    override suspend fun setToken(token: String): IssueReportDto.SettingsDto {
        calls += "setToken"
        tokensSent += token
        failWith?.let { throw it }
        settings =
            settings.copy(
                tokenState = IssueReportDto.TokenState.SET,
                tokenPreview = "••••" + token.takeLast(4),
                tokenSetAt = "2026-10-10T08:00:00Z",
            )
        return settings
    }

    override suspend fun clearToken(): IssueReportDto.SettingsDto {
        calls += "clearToken"
        failWith?.let { throw it }
        settings = settings.copy(tokenState = IssueReportDto.TokenState.NONE, tokenPreview = null, tokenSetAt = null)
        return settings
    }

    companion object {
        fun defaultSettings(): IssueReportDto.SettingsDto =
            IssueReportDto.SettingsDto(
                owner = "DDZzzzz12",
                repo = "POS-KMP",
                apiBaseUrl = "https://api.github.com",
                isPublic = false,
                tokenState = IssueReportDto.TokenState.NONE,
                tokenPreview = null,
                tokenSetAt = null,
                useUpdateToken = false,
                updateTokenState = IssueReportDto.TokenState.NONE,
            )
    }
}
