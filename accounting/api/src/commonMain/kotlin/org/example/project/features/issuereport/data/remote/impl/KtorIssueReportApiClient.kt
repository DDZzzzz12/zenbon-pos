package org.example.project.features.issuereport.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.features.issuereport.data.remote.IssueReportApiClient

class KtorIssueReportApiClient(
    private val httpCall: HttpCall,
) : IssueReportApiClient {
    private companion object {
        const val BASE = "api/issue-reports"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun preview(request: IssueReportDto.ReportRequestDto): IssueReportDto.PreviewDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE/preview")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun submit(request: IssueReportDto.ReportRequestDto): IssueReportDto.CreatedDto =
        httpCall.httpClient.apiCall {
            post(url(BASE)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun settings(): IssueReportDto.SettingsDto = httpCall.httpClient.apiCall { get(url("$BASE/settings")) }

    override suspend fun saveSettings(request: IssueReportDto.UpdateSettingsRequestDto): IssueReportDto.SettingsDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE/settings")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun setToken(token: String): IssueReportDto.SettingsDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE/token")) {
                contentType(ContentType.Application.Json)
                setBody(IssueReportDto.SetTokenRequestDto(token))
            }
        }

    override suspend fun clearToken(): IssueReportDto.SettingsDto = httpCall.httpClient.apiCall { delete(url("$BASE/token")) }
}
