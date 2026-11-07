package org.example.project.features.update.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.features.update.data.remote.UpdateApiClient

class KtorUpdateApiClient(
    private val httpCall: HttpCall,
) : UpdateApiClient {
    companion object {
        private const val BASE = "api/update"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun status(): UpdateDto.StatusDto = httpCall.httpClient.apiCall { get(url("$BASE/status")) }

    override suspend fun settings(): UpdateDto.SettingsDto = httpCall.httpClient.apiCall { get(url("$BASE/settings")) }

    override suspend fun saveSettings(request: UpdateDto.UpdateSettingsRequestDto): UpdateDto.SettingsDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE/settings")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun setToken(token: String): UpdateDto.SettingsDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE/token")) {
                contentType(ContentType.Application.Json)
                setBody(UpdateDto.SetTokenRequestDto(token))
            }
        }

    override suspend fun clearToken(): UpdateDto.SettingsDto = httpCall.httpClient.apiCall { delete(url("$BASE/token")) }

    override suspend fun checkNow(): UpdateDto.CheckResultDto = httpCall.httpClient.apiCall { post(url("$BASE/check")) }

    override suspend fun stageNow(): UpdateDto.CheckResultDto = httpCall.httpClient.apiCall { post(url("$BASE/stage")) }

    override suspend fun applyNow(): UpdateDto.ApplyStartedDto = httpCall.httpClient.apiCall { post(url("$BASE/apply")) }
}
