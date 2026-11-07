/**
 * Local implementation of [TseSignProvider] for direct TSE hardware access.
 *
 * Executes signing operations directly against the TSE hardware via server-tse
 * operation classes, bypassing the HTTP layer entirely. This eliminates the
 * ~100-500ms latency per signing operation from the remote HTTP path.
 *
 * Architecture:
 * - Main Server → LocalTseSignProvider → TseOperation.execute(TseStatus) → WormAPI → TSE Hardware
 *
 * Thread Safety:
 * - All operations go through [TseStatus.executeWithLock] which uses a mutex
 * - Only one TSE command executes at a time
 *
 * Error handling (B1 final, post-Task-5):
 * - Underlying [org.example.project.tse.operations] throw [PosException] directly
 *   per the B1 cross-walk.
 * - This provider just lets PosException propagate; unexpected exceptions become
 *   `PosException(TSE_OPERATION_FAILED, …, cause = e)`.
 *
 * @param lifecycleManager Manages the TseStatus instance and hardware lifecycle
 *
 * @see org.example.project.features.tse.domain.provider.TseSignProvider
 * @since 1.0.0
 */
package org.example.project.features.tse.data.provider.local

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseHealthState
import org.example.project.core.domain.enums.tse.TseSignOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.data.service.TseSigningRetryService
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.tse.domain.provider.TseStatusBroadcaster
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.operations.admin.status.HealthCheckOperation
import org.example.project.tse.operations.signing.TseTransactionFinishOperation
import org.example.project.tse.operations.signing.TseTransactionStartOperation
import org.example.project.tse.operations.signing.TseTransactionUpdateOperation
import org.slf4j.LoggerFactory
import org.example.project.tse.operations.signing.TseSignOperation as TseSignOp

class LocalTseSignProvider(
    private val lifecycleManager: LocalTseLifecycleManager,
    private val powerLossRecovery: TsePowerLossRecovery = TsePowerLossRecovery.forLocalTse(lifecycleManager),
) : TseSignProvider {
    private val logger = LoggerFactory.getLogger(LocalTseSignProvider::class.java)

    private fun simulatedHealthResult(): TseDto.HealthCheckResultDto =
        TseDto.HealthCheckResultDto(
            healthy = false, // matches existing SIMULATION-branch behaviour
            health = TseHealthState.HEALTHY,
            maintenanceOps = emptySet(),
            initialized = true,
            validTime = true,
            passedSelfTest = true,
            remainingSignatures = Long.MAX_VALUE,
            availableCapacity = Long.MAX_VALUE,
        )

    private fun stubResultFor(
        configuredMode: TseMode,
        health: TseHealthState,
        ops: Set<String>,
    ): TseDto.HealthCheckResultDto =
        TseDto.HealthCheckResultDto(
            healthy = false,
            health = health,
            maintenanceOps = ops,
            initialized = false,
            validTime = false,
            passedSelfTest = false,
            remainingSignatures = 0L,
            availableCapacity = 0L,
        )

    /**
     * Quick sign a transaction using direct hardware access.
     *
     * @throws PosException on TSE failure
     */
    override suspend fun quickSign(
        processType: String,
        processData: String,
        clientId: String,
    ): TseDto.SignResultDto {
        // Task 15d: resolve outside the try — an unknown processType is a permanent caller error,
        // and the generic catch below would turn it into a retryable-looking TSE failure.
        val cmd = resolveCommand(processType)
        return try {
            ensureModeConsistent()
            val operation = TseSignOp(command = cmd, value = processData, clientId = clientId)
            operation.execute(lifecycleManager.getTseStatus())
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            logger.error("Local TSE quick sign failed", e)
            throw unexpected("TSE quick sign failed", e)
        }
    }

    /**
     * Starts a new TSE transaction using direct hardware access.
     *
     * @throws PosException on TSE failure
     */
    override suspend fun startTransaction(
        processType: String,
        clientId: String,
    ): TseDto.TransactionStartResultDto {
        // Task 15d: see quickSign — resolve outside the try so a permanent caller error is never
        // dressed up as a retryable TSE failure.
        val cmd = resolveCommand(processType)
        return try {
            ensureModeConsistent()
            val operation = TseTransactionStartOperation(command = cmd, clientId = clientId)
            operation.execute(lifecycleManager.getTseStatus())
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            logger.error("Local TSE start transaction failed", e)
            throw unexpected("TSE start transaction failed", e)
        }
    }

    /**
     * Updates an ongoing TSE transaction using direct hardware access.
     *
     * @throws PosException on TSE failure
     */
    override suspend fun updateTransaction(
        transactionNumber: Long,
        processData: String,
        clientId: String,
    ): TseDto.SignResultDto =
        try {
            ensureModeConsistent()
            val cmd = TseSignOperation.SIGN_ORDERS.op
            val operation =
                TseTransactionUpdateOperation(
                    command = cmd,
                    transactionNumber = transactionNumber,
                    value = processData,
                    clientId = clientId,
                )
            operation.execute(lifecycleManager.getTseStatus())
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            logger.error("Local TSE update transaction failed", e)
            throw unexpected("TSE update transaction failed", e)
        }

    /**
     * Finishes a TSE transaction using direct hardware access.
     *
     * @throws PosException on TSE failure
     */
    override suspend fun finishTransaction(
        transactionNumber: Long,
        processData: String,
        clientId: String,
        processType: String,
    ): TseDto.SignResultDto {
        // Resolved outside the try, like quickSign: an unknown process type is a caller error.
        val cmd = resolveCommand(processType)
        return try {
            ensureModeConsistent()
            val operation =
                TseTransactionFinishOperation(
                    command = cmd,
                    transactionNumber = transactionNumber,
                    value = processData,
                    clientId = clientId,
                )
            operation.execute(lifecycleManager.getTseStatus())
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            logger.error("Local TSE finish transaction failed", e)
            throw unexpected("TSE finish transaction failed", e)
        }
    }

    /**
     * Checks TSE health using direct hardware access.
     *
     * Executes a [HealthCheckOperation] against the local TseStatus.
     * Also broadcasts state changes via [TseStatusBroadcaster].
     *
     * @return HealthCheckResultDto if connected, null if not reachable
     */
    override suspend fun checkHealth(): TseDto.HealthCheckResultDto? {
        val tseStatus = lifecycleManager.getTseStatus()
        val retry = TseSigningRetryService.getInstance()
        val config = ShopConfigurationManager.getTseConfig()

        // A hardware error clears the mount point (TseStatus.handleHardwareError) and nothing else
        // ever put it back, so one bad Vorgang disabled signing until restart while the retry
        // service skipped every cycle at debug level (E2E-2026-09-20 #13). This tick is the
        // recovery point: it is a no-op unless the mount point is actually gone.
        lifecycleManager.reacquireIfLost(
            currentMountPoint = tseStatus.getMountPoint(),
            configuredMode = config.mode,
        )

        val rawResult: TseDto.HealthCheckResultDto? =
            when (config.mode) {
                TseMode.DISABLED -> null
                TseMode.SIMULATION -> simulatedHealthResult()
                TseMode.NORMAL ->
                    runCatching {
                        HealthCheckOperation().execute(tseStatus)
                    }.getOrElse { e ->
                        when {
                            e is PosException && e.code == PosErrorCode.TSE_UNREACHABLE ->
                                logger.debug("Local TSE health check: hardware not present (${e.message})")
                            e is PosException -> logger.warn("Local TSE health check failed: ${e.message}")
                            else -> logger.error("Local TSE health check exception", e)
                        }
                        null
                    }
            }

        val inMaintenance = tseStatus.isInMaintenance()
        val ops = if (inMaintenance) tseStatus.currentMaintenanceOps() else emptySet()
        val health = deriveTseHealth(config.mode, rawResult, inMaintenance)

        // A replugged TSE answers again but has lost its self test and its time (F50). The sweeper
        // signs nothing until this check is healthy, so nothing else would ever repair it. Runs in
        // the background; a later tick sees the result.
        powerLossRecovery.onHealthResult(config.mode, rawResult, inMaintenance)

        if (retry != null &&
            retry.isPaused() &&
            (config.mode == TseMode.SIMULATION || rawResult != null)
        ) {
            retry.resume()
        }

        logger.info(
            "Local TSE health check: configuredMode={} health={} ops={} rawHealthy={}",
            config.mode,
            health,
            ops,
            rawResult?.healthy,
        )

        TseStatusBroadcaster.broadcastIfStateChanged(
            connected = rawResult != null || config.mode == TseMode.SIMULATION,
            health = health,
            mode = config.mode.name,
            ops = ops,
        )

        return rawResult?.withDerivedHealth(health, ops)
            ?: if (config.mode == TseMode.DISABLED) null else stubResultFor(config.mode, health, ops)
    }

    /**
     * Gets the current TSE connection status from local hardware.
     *
     * @return true if TSE hardware is connected
     */
    override fun isConnected(): Boolean {
        val live = lifecycleManager.getTseStatus().getMode()
        // Advisory only — the signing guard (ensureModeConsistent) re-reads the persisted config and
        // fails closed. This non-suspending check uses the configured mode last seen, and trusts the
        // live mode when none has been seen yet (before the first initialize).
        val configured = lifecycleManager.lastKnownConfiguredMode() ?: live
        // SIMULATION is "always connected" only when the operator CONFIGURED it. A live SIMULATION
        // under a configured NORMAL is the 2026-09-18 drift — report not connected so callers queue.
        if (live == TseMode.SIMULATION && configured == TseMode.SIMULATION) return true
        if (live != configured) return false
        return lifecycleManager.getTseStatus().isConnected()
    }

    /**
     * Gets the cached TSE operating mode from local hardware.
     *
     * @return Mode string (NORMAL, SIMULATION, DISABLED)
     */
    override fun getCachedMode(): String = lifecycleManager.getTseStatus().getMode().name

    /**
     * Registers a WebSocket connection listener for TSE status broadcasts.
     *
     * Delegates to [TseStatusBroadcaster] for shared broadcast logic.
     */
    override fun registerConnectionListener() {
        TseStatusBroadcaster.registerConnectionListener()
    }

    /**
     * Restarts the TSE connection by re-initializing the lifecycle manager.
     *
     * Resets mode to NORMAL, resumes retry service, and performs health check.
     *
     * @return Health check result if connection succeeded, null otherwise
     */
    override suspend fun restartConnection(): TseDto.HealthCheckResultDto? {
        logger.info("Restarting local TSE connection...")

        // Reset mode to NORMAL
        val config = ShopConfigurationManager.getTseConfig()
        ShopConfigurationManager.setTseConfig(config.copy(mode = TseMode.NORMAL))

        // Resume retry service if paused
        TseSigningRetryService.getInstance()?.resume()

        // Force broadcast by resetting state
        TseStatusBroadcaster.resetState()

        // Perform health check (will broadcast new state)
        return checkHealth()
    }

    /**
     * No-op for local provider — lifecycle is managed by [LocalTseLifecycleManager].
     */
    override fun close() {
        // Lifecycle managed by LocalTseLifecycleManager
    }

    /**
     * Fails closed when the live TseStatus mode disagrees with the configured mode. Throwing
     * TSE_UNREACHABLE routes through TseSigningOutcomeResolver → failureBehavior (QUEUE), the
     * same path as an unplugged TSE. Never lets a configured-NORMAL shop reach performSimulated.
     */
    private suspend fun ensureModeConsistent() {
        val configured = lifecycleManager.configuredMode()
        val live = lifecycleManager.getTseStatus().getMode()
        if (configured != live) {
            logger.error("TSE mode drift: configured={} live={} — refusing to sign", configured, live)
            throw PosException(
                code = PosErrorCode.TSE_UNREACHABLE,
                httpStatus = HttpStatusCode.ServiceUnavailable,
                message = "TSE mode mismatch (configured $configured, live $live)",
            )
        }
    }

    /**
     * Resolves the TSE command string from a DSFinV-K process type.
     *
     * Delegates to [TseSignOperation.commandForProcessType] so every process type
     * (Bestellung-V1, Kassenbeleg-V1, SonstigerVorgang, …) maps to its correct
     * command rather than collapsing everything non-order into a Kassenbeleg.
     *
     * @param processType The DSFinV-K process type
     * @return The corresponding TSE command string
     */
    private fun resolveCommand(processType: String): String = TseSignOperation.commandForProcessType(processType)

    private fun unexpected(
        msg: String,
        e: Throwable,
    ): PosException =
        PosException(
            code = PosErrorCode.TSE_OPERATION_FAILED,
            httpStatus = HttpStatusCode.InternalServerError,
            message = "$msg: ${e.message ?: e::class.simpleName}",
            cause = e,
        )
}

/**
 * Single source of truth for (configuredMode, rawResult, inMaintenance) → health.
 * See spec §2.4 for the decision table. Top-level + internal so unit tests can call it directly.
 */
internal fun deriveTseHealth(
    configuredMode: TseMode,
    rawResult: TseDto.HealthCheckResultDto?,
    inMaintenance: Boolean,
): TseHealthState =
    when {
        configuredMode == TseMode.DISABLED -> TseHealthState.UNREACHABLE
        configuredMode == TseMode.SIMULATION -> TseHealthState.HEALTHY
        rawResult == null -> TseHealthState.UNREACHABLE
        inMaintenance -> TseHealthState.MAINTENANCE
        rawResult.healthy -> TseHealthState.HEALTHY
        else -> TseHealthState.DEGRADED
    }

/**
 * This raw device result with the derived [health] and the in-flight maintenance [ops] set.
 *
 * `HealthCheckOperation` fills neither, so a result that skipped this step used to answer
 * `"healthy":false` next to `"health":"HEALTHY"` (F50). Every health result leaving this server
 * goes through here — [LocalTseSignProvider.checkHealth] and `LocalTseAdminProvider.healthCheck`.
 */
internal fun TseDto.HealthCheckResultDto.withDerivedHealth(
    health: TseHealthState,
    ops: Set<String>,
): TseDto.HealthCheckResultDto = copy(health = health, maintenanceOps = ops)
