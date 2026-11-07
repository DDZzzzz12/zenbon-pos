package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.PermissionGroupApiClient
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Ktor implementation of [PermissionGroupApiClient].
 *
 * - GET/POST `/api/admin/permission-groups`
 * - GET `/api/admin/permission-groups/capabilities`
 * - GET/PUT/DELETE `/api/admin/permission-groups/{code}`
 */
class KtorPermissionGroupApiClient(
    private val httpCall: HttpCall,
) : PermissionGroupApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/admin/permission-groups"
    }

    private fun url(path: String = ""): String = "${httpCall.getBaseUrl()}$BASE_ENDPOINT$path"

    override suspend fun getGroups(): List<PermissionGroupDto.PermissionGroupResponseDto> = httpCall.httpClient.apiCall { get(url()) }

    override suspend fun getGroup(code: String): PermissionGroupDto.PermissionGroupResponseDto = httpCall.httpClient.apiCall { get(url("/$code")) }

    override suspend fun getCapabilityCatalog(): PermissionGroupDto.CapabilityCatalogDto = httpCall.httpClient.apiCall { get(url("/capabilities")) }

    override suspend fun createGroup(request: PermissionGroupDto.CreateGroupRequestDto): PermissionGroupDto.PermissionGroupResponseDto =
        httpCall.httpClient.apiCall {
            post(url()) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateGroup(
        code: String,
        request: PermissionGroupDto.UpdateGroupRequestDto,
    ): PermissionGroupDto.PermissionGroupResponseDto =
        httpCall.httpClient.apiCall {
            put(url("/$code")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteGroup(code: String) {
        httpCall.httpClient.apiCall<Unit> { delete(url("/$code")) }
    }
}
