/**
 * API client for TSE settings operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorTseSettingsApiClient(
    private val httpCall: HttpCall,
) : TseSettingsApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/tse"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    // Configuration

    override suspend fun getConfig(): TseConfigDto.TseConfig = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/config")) }

    override suspend fun updateConfig(request: TseConfigDto.UpdateTseConfigRequest): TseConfigDto.TseConfigUpdateResultDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/config")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    // Status & Info

    override suspend fun getStatus(): TseDto.StatusInfoDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/status")) }

    override suspend fun getInfo(): TseDto.TseInfoDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/info")) }

    override suspend fun getHealth(): TseDto.HealthCheckResultDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/health")) }

    override suspend fun getFlashHealth(): TseDto.FlashHealthResultDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/flashhealth")) }

    override suspend fun ping(): Map<String, Boolean> = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/ping")) }

    // Admin Operations

    override suspend fun runSelfTest(): TseDto.SelfTestResultDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/selftest")) }

    override suspend fun updateTime(): TseDto.TimeUpdateResultDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/updatetime")) }

    override suspend fun unlockAdmin(pin: String): TseDto.TseAdminSessionStatusDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/admin/unlock")) {
                contentType(ContentType.Application.Json)
                setBody(TseDto.AdminPinRequestDto(pass = pin))
            }
        }

    override suspend fun lockAdmin(): TseDto.TseAdminSessionStatusDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/admin/lock")) }

    override suspend fun adminSessionStatus(): TseDto.TseAdminSessionStatusDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/admin/status")) }

    override suspend fun setup(credentials: TseDto.SetupCredentialsRequestDto): TseDto.AdminResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/setup")) {
                contentType(ContentType.Application.Json)
                setBody(credentials)
            }
        }

    override suspend fun restartTse(): TseDto.AdminResultDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/restart")) }

    override suspend fun factoryReset(): TseDto.AdminResultDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/factory-reset")) }

    // Export

    override suspend fun exportFull(): TseDto.ExportResultDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/export/full")) }

    override suspend fun exportIncremental(request: TseDto.IncrementalExportRequestDto): TseDto.ExportResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/export/incremental")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun exportTimeFiltered(request: TseDto.TimeFilteredExportRequestDto): TseDto.ExportResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/export/time-filtered")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getExportSize(): TseDto.ExportSizeDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/export/size")) }

    override suspend fun deleteExportedData(): TseDto.ExportAndDeleteResultDto = httpCall.httpClient.apiCall { delete(url("$BASE_ENDPOINT/export")) }

    override suspend fun registerExportArchive(fileName: String): TseDto.ExportArchiveDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/archives")) {
                contentType(ContentType.Application.Json)
                setBody(TseDto.RegisterExportArchiveRequestDto(fileName))
            }
        }

    // Firmware

    override suspend fun checkFirmwareUpdate(): TseDto.FirmwareUpdateCheckResultDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/firmware-check")) }

    override suspend fun applyBundledFirmwareUpdate(): TseDto.FirmwareApplyResultDto =
        httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/firmware-apply-bundled")) }

    // Decommission

    override suspend fun decommission(): TseDto.AdminResultDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/decommission")) }

    // CSP Export

    override suspend fun disableExportIfCspTestFails(): TseDto.AdminResultDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/csp-export/disable")) }

    // Log Entry Browsing

    override suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto): TseDto.LogEntryBrowseResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/entries/browse")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    // LCM Export

    override suspend fun exportLcm(): TseDto.LcmExportResultDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/export/lcm")) }

    // Client & Transaction List

    override suspend fun getClients(): TseDto.ClientListResultDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/clients")) }

    override suspend fun getTransactions(): TseDto.TransactionListResultDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/transactions")) }

    override suspend fun getLastTransaction(): TseDto.LastTransactionResultDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/last-transaction")) }

    // Log-sequence plausibility verification

    override suspend fun verifyExport(fileName: String): TseVerificationDto.ReportDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/verify")) {
                contentType(ContentType.Application.Json)
                setBody(TseVerificationDto.VerifyRequestDto(fileName))
            }
        }
}
