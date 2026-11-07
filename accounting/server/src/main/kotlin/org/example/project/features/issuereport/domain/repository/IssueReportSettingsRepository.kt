package org.example.project.features.issuereport.domain.repository

import org.example.project.features.issuereport.domain.models.IssueReportSettings

/** Reads and writes the single `issue_report_settings` row. The only place its token is sealed or opened. */
interface IssueReportSettingsRepository {
    suspend fun load(): IssueReportSettings

    /** Replaces the target and the token-source choice; leaves the stored token untouched. */
    suspend fun saveTarget(
        owner: String,
        repo: String,
        apiBaseUrl: String,
        isPublic: Boolean,
        useUpdateToken: Boolean,
    ): IssueReportSettings

    /** Seals [token] with this host's key. Fails (IllegalStateException) when no key is provisioned. */
    suspend fun setToken(token: String)

    suspend fun clearToken()
}
