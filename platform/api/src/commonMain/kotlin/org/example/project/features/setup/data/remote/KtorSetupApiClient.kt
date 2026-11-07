/**
 * Ktor implementation of SetupApiClient.
 *
 * Provides HTTP client implementation for initial setup API operations
 * using the shared httpClient.apiCall helper for consistent error handling.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.setup.data.remote

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.setup.SetupReadinessDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Ktor-based implementation of [SetupApiClient].
 *
 * Communicates with unauthenticated setup endpoints on the server
 * for system initialization status checks and complete setup execution.
 *
 * @param httpCall Shared HTTP call wrapper with base URL and error handling
 */
class KtorSetupApiClient(
    private val httpCall: HttpCall,
) : SetupApiClient {
    companion object {
        // No leading slash: url() prefixes the base URL, which always ends in `/`.
        // "/api/setup" would concatenate to "//api/setup/..." — a protocol-relative
        // URL that makes `api` the host whenever the base URL is relative (adminApp).
        private const val BASE_ENDPOINT = "api/setup"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    /**
     * Checks system initialization status via GET /api/setup/status.
     */
    override suspend fun checkSetupStatus(): InitialSetupDto.InitialSetupStatusDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/status")) }

    /**
     * Reads setup readiness via GET /api/setup/readiness.
     */
    override suspend fun getReadiness(): SetupReadinessDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/readiness")) }

    /**
     * Executes complete setup via POST /api/setup/initialize.
     */
    override suspend fun executeCompleteSetup(request: InitialSetupDto.CompleteSetupRequestDto): InitialSetupDto.InitialSetupResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/initialize")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun healthCheck() {
        httpCall.httpClient.apiCall<Unit> { get(url("health")) }
    }

    /**
     * Restores system from backup via POST /api/setup/restore-backup.
     */
    override suspend fun restoreBackupForSetup(backupData: ByteArray): BackupRestoreDto.RestoreResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/restore-backup")) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                backupData,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"backup.zip\"")
                                    append(HttpHeaders.ContentType, "application/zip")
                                },
                            )
                        },
                    ),
                )
            }
        }
}
