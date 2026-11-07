package org.example.project.features.tse.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.tse.domain.model.TseInitResult
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.domain.service.TseConfigurationService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.TestOnlyApi
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

/**
 * Implementation of TSE configuration service using ShopConfigurationManager.
 *
 * Validates PIN/PUK changes against TSE initialization state and synchronizes
 * mode changes with the TSE provider. All errors surface as [PosException]
 * (B1 amendment).
 *
 * A mode change never registers a TSE client: registration happens only in TSE setup and the
 * explicit `POST /api/v1/terminals/{id}/tse-register`, which also keep the terminal's
 * `tseClientRegistered` flag in step with the device (F41).
 *
 * @param reloadProviders re-initializes the TSE after a config change; supplied by the DI module (the
 *   hardware reload lives in the host); tests stand in for it.
 * @since 1.0.0
 */
class TseConfigurationServiceImpl(
    private val tseAdminProvider: TseAdminProvider,
    private val terminalRepository: TerminalRepository,
    private val reloadProviders: (configure: Boolean) -> TseInitResult,
) : TseConfigurationService {
    private val logger = LoggerFactory.getLogger(TseConfigurationServiceImpl::class.java)

    override suspend fun getTseConfig(): TseConfigDto.TseConfig =
        try {
            // Credentials never leave the server (T9-14): the stored TimeAdmin PIN is blanked.
            ShopConfigurationManager.getTseConfig().withoutCredentials()
        } catch (e: Exception) {
            logger.error("Failed to get TSE config: ${e.message}", e)
            throw PosException(
                code = PosErrorCode.TSE_NOT_CONFIGURED,
                httpStatus = HttpStatusCode.InternalServerError,
                message = "Configuration error for tse.config",
                cause = e,
            )
        }

    @OptIn(TestOnlyApi::class)
    override suspend fun updateTseConfig(
        request: TseConfigDto.UpdateTseConfigRequest,
        deviceId: String,
    ): TseConfigDto.TseConfigUpdateResultDto {
        val currentConfig =
            try {
                ShopConfigurationManager.getTseConfig()
            } catch (e: Exception) {
                logger.error("Failed to read TSE config for update: ${e.message}", e)
                throw PosException(
                    code = PosErrorCode.TSE_NOT_CONFIGURED,
                    httpStatus = HttpStatusCode.InternalServerError,
                    message = "Configuration error for tse.config",
                    cause = e,
                )
            }

        // Only the TimeAdmin PIN is configurable — the Admin PIN and PUK are not stored, so
        // there is nothing here to change them through.
        val hasPinChanges = request.timeAdminPin != null

        val isInitialized = tseAdminProvider.isInitialized()

        if (hasPinChanges && isInitialized) {
            logger.warn("Attempted to change PIN/PUK on initialized TSE - rejected")
            throw PosException(
                code = PosErrorCode.TSE_ALREADY_INITIALIZED,
                httpStatus = HttpStatusCode.Conflict,
                message = "PIN/PUK cannot be changed after TSE initialization",
            )
        }

        val modeChanging = request.mode != null && request.mode != currentConfig.mode

        val updatedConfig =
            currentConfig.copy(
                mode = request.mode ?: currentConfig.mode,
                timeAdminPin = request.timeAdminPin ?: currentConfig.timeAdminPin,
                keepAliveEnabled = request.keepAliveEnabled ?: currentConfig.keepAliveEnabled,
                keepAliveIntervalSeconds = request.keepAliveIntervalSeconds ?: currentConfig.keepAliveIntervalSeconds,
                failureBehavior = request.failureBehavior ?: currentConfig.failureBehavior,
            )

        requireTimeAdminPinConfigured(updatedConfig.timeAdminPin)
        try {
            updatedConfig.validate()
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid TSE config update: ${e.message}", e)
            throw PosException(
                code = PosErrorCode.TSE_INVALID_PARAMETERS,
                httpStatus = HttpStatusCode.BadRequest,
                message = e.message ?: "Invalid configuration",
                cause = e,
            )
        }

        try {
            ShopConfigurationManager.setTseConfig(updatedConfig)
        } catch (e: Exception) {
            logger.error("Failed to persist TSE config: ${e.message}", e)
            throw PosException(
                code = PosErrorCode.TSE_NOT_CONFIGURED,
                httpStatus = HttpStatusCode.InternalServerError,
                message = "Configuration error for tse.config",
                cause = e,
            )
        }

        // Reload providers with full initialization to pick up config changes.
        // The init result is surfaced synchronously — we do NOT auto-revert the operator's mode.
        val initResult = reloadProviders(true)

        // If mode changed and the device is operational, notify the provider of the new mode.
        // We do NOT auto-revert on failure (PIN-lockout safety + honest state): the persisted
        // mode stays as the operator chose; the result tells them what actually happened.
        if (modeChanging && request.mode != null && isInitialized && initResult is TseInitResult.Operational) {
            logger.info("TSE mode changed to ${request.mode}, notifying TSE provider...")
            // The caller need not be a terminal: the back office (adminApp) switches modes too. This
            // lookup used to throw for it — AFTER the mode had been persisted and applied above — so
            // a switch that took effect answered 400 "Terminal not found" (E2E round 4, R4-12).
            val clientId = dbQuery { terminalRepository.findByDeviceId(deviceId)?.clientId }
            try {
                tseAdminProvider.setMode(request.mode.toString(), clientId.orEmpty())
                logger.info("TSE mode updated successfully")
                TseSigningRetryService.getInstance()?.let {
                    if (it.isPaused()) {
                        delay(100L)
                        it.resume()
                    }
                }
            } catch (e: PosException) {
                // Surface, but do NOT revert the persisted config (no silent revert).
                logger.warn("Failed to notify TSE provider of mode ${request.mode}: ${e.message}")
            }
            tseAdminProvider.refreshConfig()
        }

        return mapInitResult(updatedConfig, initResult)
    }
}

/**
 * Map a [TseInitResult] (server-only) to the client-facing [TseConfigDto.TseConfigUpdateResultDto].
 *
 * Extracted as a pure function so the mapping (apply-status, operator message, mayBeLocked) can be
 * unit-tested without driving the full Koin-backed [TseConfigurationServiceImpl.updateTseConfig].
 */
@TestOnlyApi
fun mapInitResult(
    updatedConfig: TseConfigDto.TseConfig,
    initResult: TseInitResult,
): TseConfigDto.TseConfigUpdateResultDto {
    data class Mapped(
        val applyStatus: TseConfigDto.TseModeApplyStatus,
        val message: String,
        val mayBeLocked: Boolean,
        val remainingRetries: Int?,
    )

    val mapped =
        when (initResult) {
            TseInitResult.Operational ->
                Mapped(
                    TseConfigDto.TseModeApplyStatus.OPERATIONAL,
                    "TSE switched to ${updatedConfig.mode} and is operational",
                    false,
                    null,
                )
            TseInitResult.Disabled ->
                Mapped(
                    TseConfigDto.TseModeApplyStatus.DISABLED,
                    "TSE is disabled",
                    false,
                    null,
                )
            is TseInitResult.Unreachable ->
                Mapped(
                    TseConfigDto.TseModeApplyStatus.UNREACHABLE,
                    "Switched to ${updatedConfig.mode}, but the TSE is offline (${initResult.reason}). " +
                        "Bookings will be queued for Nachsignieren.",
                    false,
                    null,
                )
            is TseInitResult.AuthFailed ->
                Mapped(
                    TseConfigDto.TseModeApplyStatus.AUTH_FAILED,
                    buildString {
                        append("Switched to ${updatedConfig.mode}, but TSE login failed (${initResult.reason}). ")
                        append("Login will NOT be retried automatically. ")
                        when {
                            initResult.locked ->
                                append("The TSE is locked — unblock the admin user with the PUK before retrying.")
                            initResult.remainingRetries != null ->
                                append(
                                    "Verify the admin PIN (${initResult.remainingRetries} attempts left before the PIN is blocked).",
                                )
                            else ->
                                append("Verify the admin PIN; if the user is locked, unblock with the PUK.")
                        }
                    },
                    initResult.locked,
                    initResult.remainingRetries,
                )
            TseInitResult.NotCommissioned ->
                Mapped(
                    TseConfigDto.TseModeApplyStatus.NOT_COMMISSIONED,
                    "Saved. The TSE is not commissioned yet — run TSE setup to put it into operation.",
                    false,
                    null,
                )
            is TseInitResult.InvalidConfig ->
                Mapped(
                    TseConfigDto.TseModeApplyStatus.INVALID_CONFIG,
                    "Configuration invalid: ${initResult.reason}",
                    false,
                    null,
                )
        }

    return TseConfigDto.TseConfigUpdateResultDto(
        // The echo of the saved config carries no credential either (T9-14).
        config = updatedConfig.withoutCredentials(),
        applyStatus = legacyApplyStatus(mapped.applyStatus),
        message = mapped.message,
        mayBeLocked = mapped.mayBeLocked,
        remainingRetries = mapped.remainingRetries,
        applyOutcome = mapped.applyStatus,
    )
}

/**
 * [status] as a client built before [TseConfigDto.TseModeApplyStatus.NOT_COMMISSIONED] can decode it.
 *
 * Such a client fails to decode an unknown enum value, and the setup dialog saves the config before
 * it commissions — so sending the new value would break setup on every not-yet-updated till. It reads
 * UNREACHABLE instead: "saved, not operational", which is true, and is not a PIN problem.
 */
@TestOnlyApi
fun legacyApplyStatus(status: TseConfigDto.TseModeApplyStatus): TseConfigDto.TseModeApplyStatus =
    when (status) {
        TseConfigDto.TseModeApplyStatus.NOT_COMMISSIONED -> TseConfigDto.TseModeApplyStatus.UNREACHABLE
        else -> status
    }
