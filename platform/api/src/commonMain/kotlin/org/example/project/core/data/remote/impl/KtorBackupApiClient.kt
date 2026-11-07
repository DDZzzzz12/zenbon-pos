/**
 * Ktor implementation of BackupApiClient.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.example.project.core.data.remote.BackupApiClient
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorBackupApiClient(
    private val httpCall: HttpCall,
) : BackupApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/backup"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun createBackup(request: BackupRestoreDto.CreateBackupRequestDto?): ByteArray =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/create")) {
                contentType(ContentType.Application.Json)
                request?.let { setBody(it) }
            }
        }

    override suspend fun restoreBackup(
        backupData: ByteArray,
        filename: String,
    ): BackupRestoreDto.RestoreResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/restore")) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "backup",
                                backupData,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                    append(HttpHeaders.ContentType, "application/octet-stream")
                                },
                            )
                        },
                    ),
                )
            }
        }

    override suspend fun getBackupConfig(): BackupRestoreDto.BackupConfigDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/config")) }

    override suspend fun updateBackupConfig(config: BackupRestoreDto.UpdateBackupConfigDto): BackupRestoreDto.BackupConfigDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/config")) {
                contentType(ContentType.Application.Json)
                setBody(config)
            }
        }

    override suspend fun createBackupToServer(request: BackupRestoreDto.CreateBackupToServerRequestDto): BackupRestoreDto.BackupToServerResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/create-to-server")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun restoreFromServerFile(filePath: String): BackupRestoreDto.RestoreResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/restore-from-server")) {
                contentType(ContentType.Application.Json)
                setBody(BackupRestoreDto.RestoreFromServerRequestDto(filePath))
            }
        }

    override suspend fun listDirectories(path: String?): BackupRestoreDto.DirectoryListingDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/directories")) {
                path?.let { parameter("path", it) }
            }
        }

    override suspend fun listBackups(directory: String?): BackupRestoreDto.BackupListDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/list")) {
                directory?.let { parameter("directory", it) }
            }
        }

    override suspend fun getRestoredPaths(): RestoredPathsDto.ReviewDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/restored-paths")) }

    override suspend fun resolveRestoredPaths(request: RestoredPathsDto.ResolveRequestDto): RestoredPathsDto.ReviewDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/restored-paths")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
