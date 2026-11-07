package org.example.project.tse.operations.signing

import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSignOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.ITseOperation
import org.example.project.tse.utils.ProcessDataValidator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Abstract base class for TSE signing operations.
 *
 * Provides common functionality shared across all sign operations:
 * - Connection validation (NORMAL mode only)
 * - Command parsing to process type via [org.example.project.core.domain.enums.tse.TseSignOperation]
 * - Lock handling via [org.example.project.tse.models.TseStatus.executeOnTseThread]
 * - Mode dispatch (SIMULATION / NORMAL / DISABLED)
 * - Helper methods for building simulated and real sign results
 * - Process data validation for DSFinV-K format
 *
 * Subclasses only need to implement [performSimulated] and [performReal]
 * with their specific logic.
 *
 * IMPORTANT - Login NOT Required:
 * Transaction signing does NOT require admin login. The TSE only requires:
 * - The TSE is INITIALIZED
 * - The client ID is registered
 * - The CTSS interface is enabled
 *
 * Thread Safety:
 * - All WormAPI operations are automatically wrapped in executeOnTseThread
 * - Only one sign command executes at a time
 * - Concurrent requests are queued in FIFO order
 *
 * @param T The return type of the operation result
 * @param command The sign command identifier (e.g., "signorders", "signbill")
 * @param requiresConnection Whether this operation requires TSE hardware connection (default: true)
 * @param requiresLock Whether this operation requires exclusive lock (default: true)
 *
 * @see ITseOperation
 * @see org.example.project.tse.models.TseStatus.executeOnTseThread
 * @since 2.0.0
 */
abstract class BaseTseSignOperation<T>(
    protected val command: String,
    override val requiresConnection: Boolean = true,
    override val requiresLock: Boolean = true
) : ITseOperation<T> {

    protected val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Execute the sign operation with proper connection/lock handling and mode dispatch.
     *
     * This method:
     * 1. Logs operation start
     * 2. Validates connection if required (NORMAL mode only)
     * 3. Parses command to process type via [org.example.project.core.domain.enums.tse.TseSignOperation]
     * 4. Acquires lock if required
     * 5. Dispatches to [performSimulated] or [performReal] based on mode
     *
     * @param tseStatus Current TSE status and configuration
     * @return Sign operation result on success.
     * @throws PosException if the operation fails per the B1 cross-walk.
     */
    @Throws(PosException::class)
    override fun execute(tseStatus: TseStatus): T {
        logger.info("Executing ${this::class.simpleName}: $command")

        // Validate connection (not required for SIMULATION mode)
        if (requiresConnection && tseStatus.getMode() == TseMode.NORMAL && !tseStatus.isConnected()) {
            logger.error("TSE not connected in NORMAL mode")
            throw PosException(
                code = PosErrorCode.TSE_HARDWARE_NOT_FOUND,
                httpStatus = HttpStatusCode.ServiceUnavailable,
                message = "TSE hardware not found at mount point: TSE not connected in NORMAL mode",
            )
        }

        // Parse command to get process type
        val signCommand = TseSignOperation.Companion.fromString(command)
        if (signCommand == null) {
            logger.error("Invalid TSE sign command: $command")
            throw PosException(
                code = PosErrorCode.TSE_INVALID_PROCESS_TYPE,
                httpStatus = HttpStatusCode.BadRequest,
                message = "Invalid TSE process type: $command",
            )
        }

        val processType = signCommand.processType
        logger.debug("Process type: $processType")

        // Execute with or without lock based on operation requirements
        return try {
            if (requiresLock) {
                tseStatus.executeOnTseThread {
                    dispatchWithStaleTseRecovery(tseStatus, processType)
                }
            } else {
                dispatchWithStaleTseRecovery(tseStatus, processType)
            }
        } catch (e: WormException) {
            // E2E R2-6: sort by the SDK's own split. Only a host-side error means the device may
            // be gone; an answer from the card proves it is connected (design 2026-09-22 §4.4).
            val errorClass = WormErrorClass.of(e)
            if (errorClass.resetsConnection) {
                logger.warn("TSE connection error during ${this::class.simpleName}: ${e.message}")
                tseStatus.handleHardwareError(e)
            } else {
                logger.warn("TSE answered ${e.message} during ${this::class.simpleName}; connection kept")
            }
            throw PosException(
                code = errorClass.code,
                httpStatus = errorClass.httpStatus,
                message = errorClass.messageFor(e),
                cause = e,
            )
        }
    }

    /**
     * Runs the operation, and if the TSE turns out to be stale, fixes it and retries **once**.
     *
     * A missed maintenance window should not cost a sale. Scheduled maintenance remains the
     * primary mechanism — a self test can take up to 60 seconds and invalidates the time
     * afterwards, so it belongs at quiet moments, never mid-transaction — but when signing
     * does meet a stale TSE, the vendor's remedy for the equivalent shared-LAN-TSE case is to
     * update the time and repeat the failed transaction.
     *
     * Exactly once: if remediation did not help, the failure is real and must surface rather
     * than loop while a customer waits.
     */
    private fun dispatchWithStaleTseRecovery(tseStatus: TseStatus, processType: String): T =
        try {
            dispatchByMode(tseStatus, processType)
        } catch (e: WormException) {
            if (!isRecoverablePrecondition(e)) throw e
            logger.warn("TSE precondition unmet (${e.error()}) — remediating and retrying once")
            remediateStaleTse(tseStatus, e)
            dispatchByMode(tseStatus, processType)
        }

    /**
     * Errors meaning "the TSE needs attention", not "this transaction is invalid".
     *
     * These are recoverable: fix the precondition and the same transaction succeeds. Anything
     * else is a genuine failure and must surface unchanged.
     *
     * [WormException.error] throws `IllegalArgumentException` for a message-only exception
     * (code −1) — resolve it via `runCatching` so that throw cannot escape this check
     * unmapped; a message-only exception is never a recoverable precondition.
     */
    private fun isRecoverablePrecondition(e: WormException): Boolean =
        when (runCatching { e.error() }.getOrNull()) {
            WormError.WORM_ERROR_NO_TIME_SET,
            WormError.WORM_ERROR_WRONG_STATE_NEEDS_SELF_TEST,
            WormError.WORM_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED -> true
            else -> false
        }

    /**
     * Runs the self test and/or sets the time, whichever the error called for.
     *
     * Order matters: the self test invalidates the time, so the update must follow it — the
     * SDK is explicit that `updateTime` should come "quickly after running the self test".
     * TimeAdmin, not Admin: `tse_updateTime` accepts either, and TimeAdmin is the only
     * credential the ERS is permitted to hold.
     */
    private fun remediateStaleTse(tseStatus: TseStatus, error: WormException) {
        val store = tseStatus.getWormStore()

        if (error.error() != WormError.WORM_ERROR_NO_TIME_SET) {
            // v1 needs a registered client for the self test; v2 ignores the parameter.
            val clientId = tseStatus.getClientId()
            if (clientId != null || tseStatus.isTseV2()) {
                tseStatus.runSelfTestOnDevice(clientId)
            }
        }

        store.user_login(
            WormUserId.WORM_USER_TIME_ADMIN,
            tseStatus.getTimeAdminPin().toByteArray(Charsets.UTF_8),
        )
        try {
            store.tse_updateTime(System.currentTimeMillis() / 1000)
        } finally {
            // A resting TimeAdmin blocks every export on TSE 2.0.
            runCatching { store.user_logout(WormUserId.WORM_USER_TIME_ADMIN) }
        }
    }

    /**
     * Dispatch to the appropriate handler based on TSE mode.
     *
     * @param tseStatus Current TSE status
     * @param processType The parsed process type string
     * @return Result from simulated or real operation
     */
    private fun dispatchByMode(tseStatus: TseStatus, processType: String): T {
        return when (tseStatus.getMode()) {
            TseMode.SIMULATION -> performSimulated(tseStatus, processType)
            TseMode.NORMAL -> performReal(tseStatus, processType)
            TseMode.DISABLED -> throw PosException(
                code = PosErrorCode.TSE_INVALID_PROCESS_TYPE,
                httpStatus = HttpStatusCode.BadRequest,
                message = "Invalid TSE process type: $command",
            )
        }
    }

    /**
     * Perform the operation in SIMULATION mode.
     *
     * Generates mock results without requiring actual TSE hardware.
     * Useful for development, testing, and demonstrations.
     *
     * @param tseStatus Current TSE status and configuration
     * @param processType The process type being signed
     * @return Simulated data on success.
     * @throws PosException if simulation fails.
     */
    @Throws(PosException::class)
    protected abstract fun performSimulated(tseStatus: TseStatus, processType: String): T

    /**
     * Perform the operation in NORMAL mode using real TSE hardware.
     *
     * Executes the actual WormAPI calls against the TSE hardware.
     * All binary data should be Base64-encoded for JSON serialization.
     *
     * @param tseStatus Current TSE status and configuration
     * @param processType The process type being signed
     * @return Real data on success.
     * @throws PosException if the SDK call fails.
     */
    @Throws(PosException::class)
    protected abstract fun performReal(tseStatus: TseStatus, processType: String): T

    // ============================================================================================
    // Helper Methods
    // ============================================================================================

    /**
     * Get the WormStore instance for TSE operations.
     *
     * Convenience wrapper that delegates to [TseStatus.getWormStore].
     * Should only be called within [performReal] (after lock is acquired).
     *
     * @param tseStatus The TSE status containing the WormStore instance
     * @return The WormStore instance
     * @throws IllegalStateException if not connected
     */
    protected fun getWormStore(tseStatus: TseStatus): WormStore {
        return tseStatus.getWormStore()
    }

    /**
     * Generate a simulated [org.example.project.core.domain.dto.tse.TseDto.SignResultDto] for SIMULATION mode.
     *
     * Creates a mock signature result with deterministic test values.
     * Used by Sign, Finish, and Update operations that return SignResultDto.
     *
     * @param processType The process type being signed
     * @param transactionNumber The transaction number (real or generated)
     * @param label Optional label for the signature data prefix (default: "SIM")
     * @return A simulated SignResultDto with mock cryptographic values
     */
    protected fun generateSimulatedSignResult(
        processType: String,
        transactionNumber: Long,
        label: String = "SIM"
    ): TseDto.SignResultDto {
        val timestamp = System.currentTimeMillis()
        val signatureData = "$label-$timestamp-$transactionNumber-${processType.hashCode()}"
        val signature = Base64.getEncoder().encodeToString(signatureData.toByteArray())

        return TseDto.SignResultDto(
            success = true,
            processType = processType,
            transactionNumber = transactionNumber,
            signature = signature,
            timestamp = timestamp,
            serialNumber = Base64.getEncoder().encodeToString("SIM-TSE-001".toByteArray()),
            signatureCounter = transactionNumber,
            signatureAlgorithm = "ecdsa-plain-SHA384",
            publicKey = Base64.getEncoder().encodeToString("SIMULATED_PUBLIC_KEY".toByteArray()),
            certificate = Base64.getEncoder().encodeToString("SIMULATED_CERTIFICATE".toByteArray()),
            startTimestamp = timestamp
        )
    }

    /**
     * Build a real [TseDto.SignResultDto] from WormAPI response data.
     *
     * Retrieves signatureAlgorithm, logMessageCertificate, and TSE info from the WormStore
     * and builds a complete SignResultDto with Base64-encoded binary data.
     *
     * Used by Sign, Finish, and Update operations that return SignResultDto.
     *
     * @param processType The process type that was signed
     * @param transactionNumber The transaction number from the WormAPI response
     * @param signature The raw signature bytes from the WormAPI response
     * @param logTime The log time from the WormAPI response
     * @param serialNumber The raw serial number bytes from the WormAPI response
     * @param signatureCounter The signature counter from the WormAPI response
     * @param store The WormStore instance for retrieving metadata
     * @return A SignResultDto with Base64-encoded binary data
     */
    protected fun buildRealSignResult(
        processType: String,
        transactionNumber: Long,
        signature: ByteArray,
        logTime: Long,
        serialNumber: ByteArray,
        signatureCounter: Long,
        store: WormStore,
        /** StartTransaction log time (TSER-11) — quickSign surfaces it, two-step flows pass null. */
        startLogTime: Long? = null,
    ): TseDto.SignResultDto {
        val algorithm = WormStore.signatureAlgorithm()
        val cert = store.logMessageCertificate

        return store.info().use { info ->
            TseDto.SignResultDto(
                success = true,
                processType = processType,
                transactionNumber = transactionNumber,
                signature = Base64.getEncoder().encodeToString(signature),
                timestamp = logTime,
                serialNumber = Base64.getEncoder().encodeToString(serialNumber),
                signatureCounter = signatureCounter,
                signatureAlgorithm = algorithm,
                publicKey = Base64.getEncoder().encodeToString(info.tsePublicKey()),
                certificate = Base64.getEncoder().encodeToString(cert),
                startTimestamp = startLogTime
            )
        }
    }

    /**
     * Validate process data against DSFinV-K format.
     *
     * Runs [org.example.project.tse.utils.ProcessDataValidator.validate] and throws
     * a [PosException] if validation fails. Returns normally if validation passes
     * (allowing the caller to continue).
     *
     * Used by Finish and Update operations that receive process data from the client.
     *
     * @param value The process data string to validate
     * @throws PosException with [PosErrorCode.TSE_INVALID_PROCESS_DATA] if validation fails.
     */
    @Throws(PosException::class)
    protected fun validateProcessData(value: String, processType: String) {
        logger.debug("Validating process data format for {}", processType)
        // Task 15a: the grammar is a function of the processType. Passing it is the ONLY change on
        // this hardware-only path — every rule lives in the shared validator, which is unit-tested.
        val validationResult = ProcessDataValidator.validate(value, processType, strict = false)

        if (!validationResult.isValid) {
            logger.error(
                "Invalid process data format. Errors: ${validationResult.errors.joinToString("; ")}"
            )
            throw PosException(
                code = PosErrorCode.TSE_INVALID_PROCESS_DATA,
                httpStatus = HttpStatusCode.BadRequest,
                message = "Invalid process data format: Process data validation failed: " +
                    validationResult.errors.joinToString("; "),
            )
        }

        // Log warnings if any
        if (validationResult.warnings.isNotEmpty()) {
            logger.warn(
                "Process data warnings: ${validationResult.warnings.joinToString("; ")}"
            )
        }

        // Log parsed data if available
        validationResult.parsedData?.let { parsed ->
            logger.debug(
                "Process data parsed: Type=${parsed.type}, " +
                        "Total=${parsed.totalAmount}, PaymentMethod=${parsed.paymentMethod}"
            )
        }
    }
}
