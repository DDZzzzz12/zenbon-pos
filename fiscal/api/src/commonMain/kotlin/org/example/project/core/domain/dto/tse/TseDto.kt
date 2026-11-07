@file:OptIn(
    ExperimentalTime::class,
//    kotlinx.serialization.InternalSerializationApi::class,
//    kotlinx.serialization.ExperimentalSerializationApi::class
)

package org.example.project.core.domain.dto.tse

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.tse.TseExportArchiveOrigin
import org.example.project.core.domain.enums.tse.TseHealthState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Container object for all TSE-related DTOs.
 *
 * Organizes DTOs by functionality: admin requests, sign requests, and responses
 * to maintain clear separation of concerns and consistent API structure.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
object TseDto {
    /**
     * Detail captured from a TSE admin/time-admin login failure so the server can report
     * the real remaining attempts and locked state (instead of guessing). Populated from the
     * Swissbit WormAuthenticationFailedException; null fields mean "unknown".
     */
    @Serializable
    data class TseAuthFailureInfo(
        val remainingRetries: Int? = null,
        val pinBlocked: Boolean = false,
    )

    /**
     * Request DTO for TSE admin command operations.
     *
     * Matches the production TSE connector admin command structure with clientId support.
     */
    @Serializable
    data class AdminRequestDto(
        val cmd: String,
        val pass: String,
        val clientid: String? = null,
        val parameters: Map<String, String> = emptyMap(),
    ) {
        fun validate() {
            require(cmd.isNotBlank()) { "Command is required" }
            require(pass.isNotBlank()) { "Password is required" }
        }
    }

    /**
     * Request DTO for TSE sign command operations.
     *
     * Matches the production TSE connector sign command structure with clientId support.
     */
    @Serializable
    data class SignRequestDto(
        val cmd: String,
        val clientid: String,
        val value: String? = null,
    ) {
        fun validate() {
            require(cmd.isNotBlank()) { "Command is required" }
        }
    }

    /**
     * Response DTO for TSE initialization operations.
     */
    @Serializable
    data class InitRequestDto(
        val pass: String,
        val port: Int = 8081,
        val mountPoint: String = "auto",
        val mode: String = "normal", // "normal" or "simulate"
        val tarExportFile: String = "/tmp/tsetarexport",
    ) {
        fun validate() {
            require(pass.isNotBlank()) { "Password is required" }
            require(port in 1024..65535) { "Port must be between 1024 and 65535" }
            require(mode.lowercase() in listOf("normal", "simulate")) { "Mode must be 'normal' or 'simulate'" }
        }
    }

    /**
     * Response DTO for TSE command results.
     */
    @Serializable
    data class CommandResultDto<T>(
        val success: Boolean,
        val message: String,
        val data: T? = null,
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    ) {
        companion object {
            fun <T> success(
                message: String,
                data: T,
            ): CommandResultDto<T> =
                CommandResultDto<T>(
                    success = true,
                    message = message,
                    data = data,
                )

            fun <T> error(message: String): CommandResultDto<T> {
                @Suppress("UNCHECKED_CAST")
                return CommandResultDto<T>(
                    success = false,
                    message = message,
                    data = null as T,
                )
            }
        }
    }

    /**
     * Response DTO for TSE status information.
     */
    @Serializable
    data class StatusInfoDto(
        val connected: Boolean,
        val mode: String,
        val mountPoint: String?,
        val busy: Boolean = false,
        val initializationState: String? = null,
        val lastError: String? = null,
        val sdkVersion: String? = null,
        val logTimeFormat: String? = null,
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    /**
     * Request DTO for starting a TSE transaction.
     *
     * Used to initiate a new transaction that will be finished later.
     */
    @Serializable
    data class TransactionStartRequestDto(
        val cmd: String,
        val clientId: String,
    ) {
        fun validate() {
            require(cmd.isNotBlank()) { "Command is required" }
        }
    }

    /**
     * Response DTO for TSE transaction start operation.
     *
     * Contains the transaction number that must be used to finish the transaction.
     */
    @Serializable
    data class TransactionStartResultDto(
        val success: Boolean,
        val processType: String,
        val transactionNumber: Long,
        val timestamp: Long,
        val clientId: String,
        val serialNumber: String,
    )

    /**
     * Request DTO for finishing a TSE transaction.
     *
     * Must provide the transaction number from the start operation
     * and the final process data to be signed.
     */
    @Serializable
    data class TransactionFinishRequestDto(
        val cmd: String,
        val transactionNumber: Long,
        val value: String,
        val clientid: String,
    ) {
        fun validate() {
            require(cmd.isNotBlank()) { "Command is required" }
            require(transactionNumber > 0) { "Transaction number must be positive" }
            require(value.isNotBlank()) { "Process data value is required" }
        }
    }

    /**
     * Request DTO for updating a TSE transaction.
     *
     * Updates a previously started transaction with new process data.
     * Each update creates a new signature, allowing for incremental transaction building.
     *
     * Important:
     * - Multiple updates can be performed on the same transaction
     * - Each update generates a new signature
     * - Same client that started the transaction must perform the update
     * - Requires active CTSS interface and valid TSE time
     */
    @Serializable
    data class TransactionUpdateRequestDto(
        val cmd: String,
        val transactionNumber: Long,
        val value: String,
        val clientid: String,
    ) {
        fun validate() {
            require(cmd.isNotBlank()) { "Command is required" }
            require(transactionNumber > 0) { "Transaction number must be positive" }
            require(value.isNotBlank()) { "Process data value is required" }
        }
    }

    /**
     * Response DTO for TSE sign operation results.
     */
    @Serializable
    data class SignResultDto(
        val success: Boolean,
        val processType: String,
        val transactionNumber: Long,
        val signature: String,
        val timestamp: Long,
        val serialNumber: String,
        val signatureCounter: Long? = null,
        val signatureAlgorithm: String? = null,
        val publicKey: String? = null,
        val certificate: String? = null,
        /**
         * TSE log time of the StartTransaction (device units). The one-shot quickSign flow
         * surfaces it so TSE_TA_START can carry the TRUE start log time instead of the
         * server clock (audit TSER-11). Null on legacy/remote peers that predate the field.
         */
        val startTimestamp: Long? = null,
    )

    /**
     * Response DTO for TSE hardware information.
     *
     * Contains comprehensive information about the TSE hardware including
     * initialization state, capacity, signatures, certificates, and configuration.
     */
    @Serializable
    data class TseInfoDto(
        val initializationState: String,
        val hasValidTime: Boolean,
        val hasPassedSelfTest: Boolean,
        val isDevelopmentFirmware: Boolean,
        // Long, not Int: the SDK reports these in bytes and every real TSE exceeds
        // Int.MAX_VALUE. An 8 GB device truncated to -589,934,592, which corrupted the
        // capacity progress bar and would defeat any free-space threshold.
        val capacity: Long,
        val size: Long,
        val hasChangedPuk: Boolean,
        val hasChangedAdminPin: Boolean,
        val hasChangedTimeAdminPin: Boolean,
        val timeUntilNextSelfTest: Int,
        val startedTransactions: Int,
        val maxStartedTransactions: Int,
        val createdSignatures: Int,
        val maxSignatures: Int,
        val remainingSignatures: Int,
        val maxTimeSynchronizationDelay: Int,
        val maxUpdateDelay: Int,
        val tsePublicKey: String,
        val timeUntilNextTimeSynchronization: Int,
        val tseSerialNumberBytes: String,
        val tseSerialNumberHex: String,
        val tseDescription: String,
        val registeredClients: Int,
        val maxRegisteredClients: Int,
        val certificateExpirationDate: Long,
        val tarExportSizeInSectors: Int,
        val tarExportSize: Int,
        val hardwareVersion: Int,
        val softwareVersion: Int,
        val formFactor: String,
        // --- TSE 2.0 (SDK 6.0). Defaulted so existing callers and older peers keep working. ---
        /** True when the attached device is a TSE 2.0, certified under the newer BSI guideline. */
        val isTseV2: Boolean = false,
        /**
         * Name of the currently logged-in TSE user, e.g. `WORM_USER_ADMIN`, or
         * `WORM_USER_UNAUTHENTICATED` when nobody is. Only meaningful on TSE 2.0, where a
         * single session is allowed and a stuck TimeAdmin silently blocks every export.
         */
        val loggedInUser: String = "WORM_USER_UNAUTHENTICATED",
        /** Seconds until TSE 2.0 logs the current user out for inactivity; 0 when nobody is. */
        val loggedInUserAutoLogOutTimeout: Int = 0,
        /** Supersedes [hasChangedPuk] on TSE 2.0, where every user has its own PUK. */
        val hasChangedAdminPuk: Boolean = false,
        val hasChangedTimeAdminPuk: Boolean = false,
        val hasChangedLoggerPuk: Boolean = false,
        val hasChangedLoggerPin: Boolean = false,
    ) {
        /** Unused capacity in bytes, floored at 0. */
        val freeBytes: Long get() = (capacity - size).coerceAtLeast(0)

        /**
         * True when the TSE should be exported and cleared soon.
         *
         * TSE 2.0 starts refusing transactions while roughly 1 MB is still free, reserving
         * that space for the login and time update an export-and-delete needs — so waiting
         * for the device to be physically full is far too late. Swissbit recommends acting
         * below 50 MB free. A capacity of 0 means "not reported" and deliberately does not
         * warn, to avoid a false alarm on every device that has not published one.
         */
        val shouldExportSoon: Boolean get() = capacity > 0 && freeBytes < EXPORT_SOON_THRESHOLD_BYTES

        /**
         * True when the TSE is approaching its signature limit.
         *
         * A device is good for roughly 20 million signatures, and Swissbit advises replacing
         * it once that is reached. Certificate expiry already gets a warning, and this is the
         * other thing that ends a device's usable life — so give the operator the same notice
         * and the same chance to order a replacement before it stops signing.
         *
         * A maximum of 0 means "not reported" and never warns, so a device that publishes no
         * budget does not raise an alarm on every render.
         */
        val signaturesRunningLow: Boolean
            get() =
                maxSignatures > 0 &&
                    remainingSignatures < maxSignatures / SIGNATURE_WARNING_FRACTION

        companion object {
            /** Swissbit's recommended export threshold: 50 MB of remaining free space. */
            const val EXPORT_SOON_THRESHOLD_BYTES: Long = 50L * 1024 * 1024

            /** Warn once under 5 % of the signature budget remains. */
            const val SIGNATURE_WARNING_FRACTION: Int = 20
        }
    }

    /**
     * Response data classes for TSE endpoints
     */
    @Serializable
    data class TseSignStatusResponse(
        val service: String,
        val status: String,
        val tse_connected: Boolean,
    )

    @Serializable
    data class TseInitStatusResponse(
        val initialized: Boolean,
        val mode: String,
        val mountPoint: String,
        val serverPort: Int,
        val lastCheckResult: String,
        val isTseTimeSet: Boolean,
    )

    // ============================================================================================
    // DSFinV-K Export DTOs
    // ============================================================================================

    /**
     * Request DTO for TSE export operations (DSFinV-K compliance).
     *
     * Supports multiple export types as required by German fiscal regulations:
     * - **full**: Complete TAR archive export of all signed transaction logs
     * - **incremental**: Export only new data since last export (uses internal state tracking)
     * - **filtered_time**: Export transactions within a specific time range
     * - **filtered_transaction**: Export specific transaction range by transaction numbers
     * - **size**: Calculate export size without performing actual export
     * - **delete_exported**: Delete exported data after successful incremental export
     *
     * DSFinV-K Reference:
     * - https://www.bzst.de/DE/Unternehmen/Aussenpruefungen/DigitaleSchnittstelleFin662anzverwaltung/digitaleschnittstellefinanzverwaltung_node.html
     *
     * @property cmd The export command type (export_full, export_incremental, etc.)
     * @property pass Admin PIN for TSE authentication
     * @property clientid Optional client identifier
     * @property outputPath File path where the TAR export will be saved
     * @property startTimestamp Start timestamp for time-filtered exports (Unix epoch seconds)
     * @property endTimestamp End timestamp for time-filtered exports (Unix epoch seconds)
     * @property startTransactionNumber Start transaction number for filtered exports
     * @property endTransactionNumber End transaction number for filtered exports
     * @property startSignatureCounter Start signature counter for incremental exports
     * @property maxRecords Maximum number of records to export (0 = unlimited)
     */
    @Serializable
    data class ExportRequestDto(
        val cmd: String,
        val pass: String,
        val clientid: String? = null,
        val outputPath: String? = null,
        val startTimestamp: Long? = null,
        val endTimestamp: Long? = null,
        val startTransactionNumber: Long? = null,
        val endTransactionNumber: Long? = null,
        val startSignatureCounter: Long? = null,
        val maxRecords: Int? = null,
    ) {
        fun validate() {
            require(cmd.isNotBlank()) { "Export command is required" }
            require(pass.isNotBlank()) { "Password is required" }

            // Validate time range if specified
            if (startTimestamp != null && endTimestamp != null) {
                require(endTimestamp >= startTimestamp) {
                    "End timestamp must be >= start timestamp"
                }
            }

            // Validate transaction range if specified
            if (startTransactionNumber != null && endTransactionNumber != null) {
                require(endTransactionNumber >= startTransactionNumber) {
                    "End transaction number must be >= start transaction number"
                }
            }
        }
    }

    /**
     * Request DTO for TSE export size calculation.
     *
     * Used to calculate export size without performing actual export.
     *
     * @property pass Admin PIN for TSE authentication
     * @property state Optional Base64-encoded incremental export state
     */
    @Serializable
    data class ExportSizeRequestDto(
        val pass: String,
        val state: String? = null,
    ) {
        fun validate() {
            require(pass.isNotBlank()) { "Password is required" }
        }
    }

    /**
     * Response DTO for TSE export operation results.
     *
     * Contains comprehensive information about a completed export operation,
     * including file location, size, duration, and state tracking for incremental exports.
     *
     * Export Types:
     * - **FULL**: Complete export of all transaction logs
     * - **INCREMENTAL**: Export of new data since last export
     * - **FILTERED_TIME**: Export filtered by time range
     * - **FILTERED_TRANSACTION**: Export filtered by transaction numbers
     *
     * State Tracking (for incremental exports):
     * - `newState` contains an opaque state string that should be stored
     * - Pass this state as `startSignatureCounter` for next incremental export
     * - Allows efficient export of only new transactions
     *
     * @property success Whether the export completed successfully
     * @property exportType Type of export performed (FULL, INCREMENTAL, etc.)
     * @property filePath Absolute path to the exported TAR file
     * @property fileName Name of the exported file
     * @property fileSize Size of the exported file in bytes
     * @property bytesExported Total bytes written during export
     * @property exportDurationMs Duration of export operation in milliseconds
     * @property timestamp Unix timestamp when export completed
     * @property allDataExported True if all matching data was exported (no more data available)
     * @property newState Opaque state string for next incremental export (null if not applicable)
     * @property message Human-readable status message
     */
    @Serializable
    data class ExportResultDto(
        val success: Boolean,
        val exportType: String,
        val filePath: String? = null,
        val fileName: String? = null,
        val fileSize: Long = 0,
        val bytesExported: Long = 0,
        val exportDurationMs: Long = 0,
        val timestamp: Long = 0,
        val allDataExported: Boolean = false,
        val newState: String? = null,
        val message: String = "",
    )

    /**
     * Response DTO for TSE export size calculation.
     *
     * Used to determine export size before performing actual export.
     * Helps with storage planning and progress indication.
     *
     * Size Information:
     * - `fullExportSizeBytes`: Size if full export is performed
     * - `incrementalExportSizeBytes`: Size if incremental export is performed
     * - Sector sizes are provided for low-level storage planning
     *
     * Incremental Export Availability:
     * - `hasStateForIncremental` indicates if incremental export is possible
     * - If false, only full export is available (first export or state lost)
     *
     * @property fullExportSizeBytes Estimated size of full export in bytes
     * @property fullExportSizeSectors Estimated size of full export in TSE sectors
     * @property incrementalExportSizeBytes Estimated size of incremental export in bytes
     * @property incrementalExportSizeSectors Estimated size of incremental export in sectors
     * @property hasStateForIncremental True if incremental export state is available
     * @property timestamp Unix timestamp when size was calculated
     */
    @Serializable
    data class ExportSizeDto(
        val fullExportSizeBytes: Long = 0,
        val fullExportSizeSectors: Long = 0,
        val incrementalExportSizeBytes: Long = 0,
        val incrementalExportSizeSectors: Long = 0,
        val hasStateForIncremental: Boolean = false,
        val timestamp: Long = 0,
    )

    /**
     * Result of `DELETE /api/tse/export` — one self-contained "export and delete" sitting.
     *
     * The TSE refuses to delete while it holds anything unexported, and the Admin login the
     * delete needs is itself a new signed log entry. So the operation logs Admin in, writes a
     * complete TAR of everything (the login entry included) to [archiveFilePath], and deletes only
     * once that file is written and checked. The archive is the **only** copy of the deleted
     * entries: the operator must keep it for the statutory retention period.
     *
     * @property archiveFileName Name of the TAR written before the delete
     * @property archiveFilePath Absolute path of that TAR
     * @property archiveSizeBytes Size of the TAR on disk
     * @property usedBlocksBefore Used TSE storage (blocks, `WormInformation.size`) before the delete
     * @property usedBlocksAfter Used TSE storage after the delete; null if it could not be read back
     * @property exportSizeBytesBefore Full-export size (`WormInformation.tarExportSize`) before
     * @property exportSizeBytesAfter Full-export size after the delete; null if it could not be read back
     */
    @Serializable
    data class ExportAndDeleteResultDto(
        val success: Boolean,
        val archiveFileName: String,
        val archiveFilePath: String,
        val archiveSizeBytes: Long,
        val usedBlocksBefore: Long,
        val usedBlocksAfter: Long? = null,
        val exportSizeBytesBefore: Long,
        val exportSizeBytesAfter: Long? = null,
        val timestamp: Long = 0,
        val message: String = "",
        /**
         * Whether the archive was entered in the archive register, which the DSFinV-K export reads to
         * cover the deleted period. False means it must be registered by hand (`POST /api/tse/archives`)
         * before a DSFinV-K export of that period can carry its TSE data.
         */
        val archiveRegistered: Boolean = false,
    )

    /** Registers an existing TSE archive by its file name inside the server's TSE export folder. */
    @Serializable
    data class RegisterExportArchiveRequestDto(
        val fileName: String,
    )

    /**
     * One registered TSE archive: a TAR holding entries that may no longer be on the device (an
     * export-and-delete archive). The DSFinV-K export ships it alongside the device's own TAR when
     * the period reaches back into the deleted entries, after re-checking [sha256].
     *
     * Ranges are null when the archive holds no log message of that kind; [logTimeFrom]/[logTimeTo]
     * are Unix seconds on the TSE's clock.
     */
    @Serializable
    data class ExportArchiveDto(
        val id: Long,
        val fileName: String,
        val tseSerialHex: String,
        val tanrFrom: Long? = null,
        val tanrTo: Long? = null,
        val signatureCounterFrom: Long? = null,
        val signatureCounterTo: Long? = null,
        val logTimeFrom: Long? = null,
        val logTimeTo: Long? = null,
        val sizeBytes: Long,
        val sha256: String,
        val origin: TseExportArchiveOrigin,
        /** ISO local date-time (shop time zone) the archive was registered. */
        val createdAt: String,
    )

    // ============================================================================================
    // Firmware Update DTOs
    // ============================================================================================

    /**
     * Result DTO for TSE firmware update check operation.
     *
     * Reports availability of both bundled (offline) and online firmware updates.
     * Bundled updates are included with the TSE SDK and can be applied without internet.
     * Online updates require connectivity to check the manufacturer's update server.
     *
     * @property updateAvailable Whether any firmware update is available (bundled or online)
     * @property bundledUpdateAvailable Whether a bundled (offline) firmware update is available
     * @property bundledVersion Version string of the bundled update (null if not available)
     * @property downloadUrl URL for online firmware download (null if not available)
     * @property message Human-readable status message
     * @property timestamp Unix timestamp when check was performed
     */
    @Serializable
    data class FirmwareUpdateCheckResultDto(
        val updateAvailable: Boolean,
        val bundledUpdateAvailable: Boolean = false,
        val bundledVersion: String? = null,
        val downloadUrl: String? = null,
        val message: String = "",
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    // ============================================================================================
    // Admin Command Result DTOs
    // ============================================================================================

    /**
     * Generic result DTO for simple admin commands.
     *
     * Used for commands that return a simple success/message response:
     * - setup, factory_reset, decom, userunblock
     *
     * @property success Whether the command completed successfully
     * @property message Human-readable status message
     * @property clientId Client ID if relevant to the operation
     * @property timestamp Unix timestamp when command completed
     */
    @Serializable
    data class AdminResultDto(
        val success: Boolean,
        val message: String,
        val clientId: String? = null,
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    /**
     * Result DTO for TSE health check operation.
     *
     * @property healthy Overall health status
     * @property health The traffic-light state. Defaults to [healthy]'s own verdict (HEALTHY or
     *   DEGRADED), never to HEALTHY regardless: the driver's health check does not set it, and a
     *   flat HEALTHY default once answered `"healthy":false` next to `"health":"HEALTHY"` (F50).
     *   The server always overwrites it with the mode/maintenance-aware derivation.
     * @property maintenanceOps Maintenance operations in flight when the check ran; empty otherwise.
     * @property initialized Whether TSE is in INITIALIZED state
     * @property validTime Whether TSE has valid time
     * @property passedSelfTest Whether self-test has passed
     * @property remainingSignatures Number of signatures remaining
     * @property availableCapacity Available storage capacity in bytes
     * @property timestamp Unix timestamp when check was performed
     */
    @Serializable
    data class HealthCheckResultDto(
        val healthy: Boolean,
        val health: TseHealthState = if (healthy) TseHealthState.HEALTHY else TseHealthState.DEGRADED,
        val maintenanceOps: Set<String> = emptySet(),
        val initialized: Boolean,
        val validTime: Boolean,
        val passedSelfTest: Boolean,
        val remainingSignatures: Long,
        val availableCapacity: Long,
        val certificateExpirationDate: Long = 0L,
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    /**
     * Result DTO for TSE self-test operation.
     *
     * @property success Whether self-test passed
     * @property message Status message
     * @property forced Whether the self-test was forced
     * @property durationMs Duration of self-test in milliseconds
     * @property timestamp Unix timestamp when test completed
     */
    @Serializable
    data class SelfTestResultDto(
        val success: Boolean,
        val message: String,
        val forced: Boolean = false,
        val durationMs: Long = 0,
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    /**
     * Result DTO for TSE time update operation.
     *
     * @property success Whether time update succeeded
     * @property tseTimestamp Unix timestamp set on the TSE
     * @property message Status message
     * @property timestamp Unix timestamp when update was performed
     */
    @Serializable
    data class TimeUpdateResultDto(
        val success: Boolean,
        val tseTimestamp: Long,
        val message: String = "Time updated successfully",
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    /**
     * Result DTO for client registration operation.
     *
     * @property success Whether registration succeeded
     * @property clientId The registered client ID
     * @property message Status message
     * @property timestamp Unix timestamp when registration completed
     */
    @Serializable
    data class ClientRegistrationResultDto(
        val success: Boolean,
        val clientId: String,
        val message: String = "Client registered successfully",
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    /**
     * Result DTO for local TAR export operation.
     *
     * @property success Whether export succeeded
     * @property filePath Path to the exported file
     * @property fileSize Size of the exported file in bytes
     * @property message Status message
     * @property timestamp Unix timestamp when export completed
     */
    @Serializable
    data class LocalExportResultDto(
        val success: Boolean,
        val filePath: String,
        val fileSize: Long,
        val message: String = "Export completed successfully",
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    // ============================================================================================
    // Client and Transaction List DTOs
    // ============================================================================================

    /**
     * Result DTO for listing registered clients.
     *
     * @property success Whether the operation succeeded
     * @property clients List of registered client IDs
     * @property count Number of registered clients
     * @property message Status message
     * @property timestamp Unix timestamp when query completed
     */
    @Serializable
    data class ClientListResultDto(
        val success: Boolean,
        val clients: List<String>,
        val count: Int,
        val message: String = "Clients retrieved successfully",
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    /**
     * Result DTO for listing started (unfinished) transactions.
     *
     * @property success Whether the operation succeeded
     * @property transactionNumbers List of started transaction numbers
     * @property count Number of started transactions
     * @property clientId Client ID filter if applied (null = all clients)
     * @property message Status message
     * @property timestamp Unix timestamp when query completed
     */
    @Serializable
    data class TransactionListResultDto(
        val success: Boolean,
        val transactionNumbers: List<Long>,
        val count: Int,
        val clientId: String? = null,
        val message: String = "Transactions retrieved successfully",
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    /**
     * Result DTO for getting the last transaction response.
     *
     * @property success Whether the operation succeeded
     * @property transactionNumber Transaction number of the last transaction
     * @property signatureCounter Signature counter value
     * @property logTime Log timestamp (Unix epoch seconds)
     * @property serialNumberHex TSE serial number in hexadecimal
     * @property signatureBase64 Signature encoded in Base64
     * @property message Status message
     * @property timestamp Unix timestamp when query completed
     */
    @Serializable
    data class LastTransactionResultDto(
        val success: Boolean,
        val transactionNumber: Long,
        val signatureCounter: Long,
        val logTime: Long,
        val serialNumberHex: String,
        val signatureBase64: String,
        val message: String = "Last transaction retrieved successfully",
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    // ============================================================================================
    // Flash Health DTOs
    // ============================================================================================

    /**
     * Result DTO for TSE flash memory health information.
     *
     * Provides diagnostic information about the health of the TSE's flash memory.
     * Used to determine if the TSE needs replacement.
     *
     * Health Indicators:
     * - ECC errors indicate flash memory degradation
     * - Spare block percentage shows remaining redundancy
     * - Erase count percentage shows remaining write cycles
     * - Data retention shows estimated data persistence
     *
     * @property success Whether the operation succeeded
     * @property uncorrectableEccErrors Number of uncorrectable ECC errors (critical)
     * @property percentageRemainingSpareBlocks Remaining spare blocks (0-100%)
     * @property percentageRemainingEraseCounts Remaining erase cycles (0-100%)
     * @property percentageRemainingTenYearsDataRetention Data retention estimate (0-100%)
     * @property needsReplacement True if TSE should be replaced
     * @property message Status message
     * @property timestamp Unix timestamp when check completed
     */
    @Serializable
    data class FlashHealthResultDto(
        val success: Boolean,
        val uncorrectableEccErrors: Long,
        val percentageRemainingSpareBlocks: Int,
        val percentageRemainingEraseCounts: Int,
        val percentageRemainingTenYearsDataRetention: Int,
        val needsReplacement: Boolean,
        val message: String = "Flash health retrieved successfully",
        val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    )

    // ============================================================================================
    // Admin Operation Request DTOs (for dedicated endpoints)
    // ============================================================================================

    /**
     * Basic request DTO with admin PIN only.
     *
     * Used for operations that only require admin authentication:
     * - POST /admin/info
     * - POST /admin/health
     * - POST /admin/flash-health
     * - POST /admin/factory-reset
     * - POST /admin/time
     * - POST /admin/ctss/enable
     * - POST /admin/ctss/disable
     */
    @Serializable
    data class AdminPinRequestDto(
        val pass: String,
    ) {
        fun validate() {
            require(pass.isNotBlank()) { "Admin PIN is required" }
        }
    }

    /**
     * Request DTO for TSE setup operation.
     *
     * Endpoint: POST /admin/setup
     *
     * @property pass Admin PIN for TSE authentication
     * @property puk Admin PUK for credential setup
     * @property timeAdminPin Time admin PIN for time operations
     * @property mode TSE operating mode (NORMAL or SIMULATION)
     * @property clientId Optional client ID to register during setup
     */
    @Serializable
    data class SetupRequestDto(
        val pass: String,
        val puk: String,
        val timeAdminPin: String,
        val mode: String,
        val clientId: String,
        val credentialSeed: String,
    ) {
        fun validate() {
            require(pass.isNotBlank()) { "Admin PIN is required" }
            require(!puk.isNullOrBlank()) { "Admin PUK is required for setup" }
            require(!timeAdminPin.isNullOrBlank()) { "Time Admin PIN is required for setup" }
            require(!mode.isNullOrBlank()) { "Mode is required for setup" }
        }
    }

    /**
     * State of the in-memory TSE admin sitting.
     *
     * @property unlocked True while an operator-supplied Admin PIN is held
     * @property idleTimeoutSeconds How long an unused session survives
     */
    @Serializable
    data class TseAdminSessionStatusDto(
        val unlocked: Boolean,
        val idleTimeoutSeconds: Long,
    )

    /**
     * Credentials an operator supplies to commission a TSE.
     *
     * Carried in the POST /api/tse/setup body rather than read from configuration: none of
     * these are stored (Swissbit guidance manual 2.5), and the credential seed is single-use
     * anyway — it is consumed by the very setup call it is sent for.
     *
     * @property adminPin Admin PIN to set on the device
     * @property adminPuk Admin PUK to set on the device
     * @property credentialSeed One-time seed printed with the device (e.g. "SwissbitSwissbit")
     * @property terminalId Client id (e.g. "TER-001") of the terminal to register as the TSE's first
     *   client. It must be an active fiscal terminal but need not be paired to a device. Null keeps
     *   the original behaviour — the calling device's own terminal — so an older till still works.
     */
    @Serializable
    data class SetupCredentialsRequestDto(
        val adminPin: String,
        val adminPuk: String,
        val credentialSeed: String,
        val terminalId: String? = null,
    ) {
        fun validate() {
            require(adminPin.isNotBlank()) { "Admin PIN is required" }
            require(adminPuk.isNotBlank()) { "Admin PUK is required" }
            require(credentialSeed.isNotBlank()) { "Credential seed is required" }
            require(terminalId == null || terminalId.isNotBlank()) { "Terminal id must not be blank" }
        }
    }

    /**
     * Request DTO for TSE self-test operation.
     *
     * Endpoints:
     * - POST /admin/selftest
     * - POST /admin/selftest/force
     *
     * @property pass Admin PIN for TSE authentication
     * @property clientId Optional client ID for self-test execution
     */
    @Serializable
    data class SelfTestRequestDto(
        val pass: String,
        val clientId: String? = null,
    ) {
        fun validate() {
            require(pass.isNotBlank()) { "Admin PIN is required" }
        }
    }

    /**
     * Request DTO for client registration.
     *
     * Endpoint: POST /admin/clients
     *
     * @property pass Admin PIN for TSE authentication
     * @property clientId Client ID to register with the TSE
     */
    @Serializable
    data class ClientRegistrationRequestDto(
        val pass: String,
        val clientId: String,
    ) {
        fun validate() {
            require(pass.isNotBlank()) { "Admin PIN is required" }
            require(clientId.isNotBlank()) { "Client ID is required" }
        }
    }

    // ============================================================================================
    // Typed Export Request DTOs (for main server → TSE provider chain)
    // ============================================================================================

    /**
     * Request DTO for incremental TSE export.
     *
     * @property outputPath Optional custom output directory (default: "exports")
     * @property lastState Base64-encoded previous incremental export state (null for first export)
     * @property maxExportSize Maximum export size in bytes (0 = unlimited)
     */
    @Serializable
    data class IncrementalExportRequestDto(
        val outputPath: String? = null,
        val lastState: String? = null,
        val maxExportSize: Long = 0,
    )

    /**
     * Request DTO for time-filtered TSE export.
     *
     * Send EITHER a calendar-day range ([startDay]/[endDay]) OR an instant range
     * ([startDate]/[endDate]). The day form is what an operator picks: the server resolves it in
     * the **shop** time zone as the whole of both days, `[startDay 00:00, endDay+1 00:00)` — the
     * same inclusive period DSFinV-K uses. Converting on the client used the device's zone and
     * sent the end day's midnight, which silently dropped the end day (T5-6). The instant form is
     * kept for callers that already hold instants.
     *
     * @property outputPath Optional custom output directory (default: "exports")
     * @property startDate Start timestamp in Unix milliseconds (instant form)
     * @property endDate End timestamp in Unix milliseconds, inclusive (instant form)
     * @property startDay First day of the period, ISO `yyyy-MM-dd`, shop time zone (day form)
     * @property endDay Last day of the period, ISO `yyyy-MM-dd`, included in full (day form)
     * @property clientId TSE client to export (a terminal's client id); null or blank = all clients
     */
    @Serializable
    data class TimeFilteredExportRequestDto(
        val outputPath: String? = null,
        val startDate: Long? = null,
        val endDate: Long? = null,
        val startDay: String? = null,
        val endDay: String? = null,
        val clientId: String? = null,
    ) {
        /**
         * The inclusive `[start, end]` range in Unix milliseconds. A day range resolves in
         * [shopZone]; its end is the last millisecond of [endDay], so the whole day is exported.
         *
         * @throws IllegalArgumentException when neither or both forms are given, a form is
         *   incomplete, a day is not ISO `yyyy-MM-dd`, or the end precedes the start.
         */
        fun resolveEpochMillis(shopZone: TimeZone): Pair<Long, Long> {
            val hasDays = startDay != null || endDay != null
            val hasInstants = startDate != null || endDate != null
            require(hasDays != hasInstants) { "Send either startDay/endDay or startDate/endDate" }
            val range = if (hasDays) dayRangeMillis(shopZone) else instantRangeMillis()
            require(range.second >= range.first) { "End date must be >= start date" }
            return range
        }

        private fun dayRangeMillis(shopZone: TimeZone): Pair<Long, Long> {
            val first = LocalDate.parse(requireNotNull(startDay) { "startDay is required" })
            val last = LocalDate.parse(requireNotNull(endDay) { "endDay is required" })
            val start = first.atStartOfDayIn(shopZone).toEpochMilliseconds()
            val endExclusive = last.plus(1, DateTimeUnit.DAY).atStartOfDayIn(shopZone).toEpochMilliseconds()
            return start to endExclusive - 1
        }

        private fun instantRangeMillis(): Pair<Long, Long> =
            requireNotNull(startDate) { "startDate is required" } to requireNotNull(endDate) { "endDate is required" }
    }

    /**
     * Request DTO for transaction-filtered TSE export.
     *
     * @property outputPath Optional custom output directory (default: "exports")
     * @property startTransaction Start transaction number
     * @property endTransaction End transaction number
     * @property clientId TSE client to export (a terminal's client id); null or blank = all clients
     */
    @Serializable
    data class TransactionFilteredExportRequestDto(
        val outputPath: String? = null,
        val startTransaction: Long,
        val endTransaction: Long,
        val clientId: String? = null,
    ) {
        fun validate() {
            require(endTransaction >= startTransaction) { "End transaction must be >= start transaction" }
        }
    }

    /**
     * Request DTO for simple TSE TAR export operation (admin endpoint).
     *
     * Endpoint: POST /admin/export
     *
     * Note: For advanced export operations with filtering, use [ExportRequestDto] instead.
     *
     * @property pass Admin PIN for TSE authentication
     * @property outputPath Optional custom output path for the TAR archive
     */
    @Serializable
    data class AdminExportRequestDto(
        val pass: String,
        val outputPath: String,
    ) {
        fun validate() {
            require(pass.isNotBlank()) { "Admin PIN is required" }
        }
    }

    /**
     * Request DTO for user unblock operation.
     *
     * Endpoint: POST /admin/users/unblock
     *
     * @property pass Admin PIN (not used for unblock, but included for consistency)
     * @property userId User to unblock (ADMIN or TIME_ADMIN)
     * @property puk Personal Unblocking Key
     * @property newPin New PIN to set after unblock
     */
    @Serializable
    data class UserUnblockRequestDto(
        val pass: String,
        val userId: String? = null,
        val puk: String? = null,
        val newPin: String? = null,
    ) {
        fun validate() {
            require(pass.isNotBlank()) { "Admin PIN is required" }
            require(!puk.isNullOrBlank()) { "PUK is required for unblock" }
            require(!newPin.isNullOrBlank()) { "New PIN is required for unblock" }
        }
    }

    /**
     * Request DTO for PIN change operation.
     *
     * Endpoint: POST /admin/users/pin
     *
     * @property pass Admin PIN (for request authentication)
     * @property userId User whose PIN to change (ADMIN or TIME_ADMIN)
     * @property currentPin Current PIN for authentication
     * @property newPin New PIN to set
     */
    @Serializable
    data class ChangePinRequestDto(
        val pass: String,
        val userId: String? = null,
        val currentPin: String? = null,
        val newPin: String? = null,
    ) {
        fun validate() {
            require(pass.isNotBlank()) { "Admin PIN is required" }
            require(!currentPin.isNullOrBlank()) { "Current PIN is required" }
            require(!newPin.isNullOrBlank()) { "New PIN is required" }
        }
    }

    // ========================================
    // Firmware Apply DTOs (Phase 3)
    // ========================================

    /**
     * Result DTO for bundled firmware update application.
     *
     * @property success Whether the firmware update was applied successfully
     * @property message Human-readable result message
     * @property previousVersion Firmware version before update
     * @property newVersion Firmware version after update (null if TSE rebooted)
     */
    @Serializable
    data class FirmwareApplyResultDto(
        val success: Boolean,
        val message: String,
        val previousVersion: String? = null,
        val newVersion: String? = null,
    )

    // ========================================
    // Log Entry Browsing DTOs (Phase 5)
    // ========================================

    /**
     * DTO for a single TSE log entry.
     *
     * @property id Entry ID in the TSE store
     * @property type Entry type (TRANSACTION, SYSTEM_LOG, SE_AUDIT_LOG)
     * @property logMessageHex Raw log message as hex string
     * @property processDataHex Raw process data as hex string
     * @property processDataText UTF-8 decoded process data (if decodable)
     * @property logMessageSize Size of log message in bytes
     * @property processDataSize Size of process data in bytes
     * @property parsed Best-effort parsed TR-03153 fields
     */
    @Serializable
    data class LogEntryDto(
        val id: Long,
        val type: String,
        val logMessageHex: String,
        val processDataHex: String,
        val processDataText: String? = null,
        val logMessageSize: Long,
        val processDataSize: Long,
        val parsed: ParsedLogMessageDto? = null,
    )

    /**
     * Parsed TR-03153 ASN.1 DER fields from a TSE log message.
     *
     * Fields are best-effort: null if parsing fails for that field.
     *
     * @property version Log message format version
     * @property operationType Operation type (e.g. "StartTransaction", "FinishTransaction")
     * @property clientId Client ID that created this entry
     * @property processType Process type string
     * @property transactionNumber Transaction number
     * @property signatureCounter Signature counter value
     * @property logTime Unix timestamp of log entry
     * @property serialNumberHex TSE serial number as hex
     * @property signatureHex Signature value as hex
     * @property signatureAlgorithm OID of signature algorithm
     * @property eventOrigin System-log event origin component (TSE 2.0 / v3 only; e.g. "SMA", "CSP")
     * @property eventTriggeredByUser System-log user that triggered the event (v3 only; e.g. "smaadmin")
     * @property eventDataHex Event-specific data of a v3 system log ([3] eventData contents) as hex
     * @property authenticationResult Outcome of an authenticate* event ("success", "incorrectPin", …)
     * @property remainingRetries Remaining login attempts reported by an authenticate* event
     * @property authRole Role of the user in an authenticate* event
     */
    @Serializable
    data class ParsedLogMessageDto(
        val version: Int? = null,
        val operationType: String? = null,
        val clientId: String? = null,
        val processType: String? = null,
        val transactionNumber: Long? = null,
        val signatureCounter: Long? = null,
        val logTime: Long? = null,
        val serialNumberHex: String? = null,
        val signatureHex: String? = null,
        val signatureAlgorithm: String? = null,
        val eventOrigin: String? = null,
        val eventTriggeredByUser: String? = null,
        val eventDataHex: String? = null,
        val authenticationResult: String? = null,
        val remainingRetries: Long? = null,
        val authRole: String? = null,
    )

    /**
     * Result DTO for log entry browsing.
     *
     * @property entries List of log entries
     * @property totalCount Total number of entries (null if unknown)
     * @property hasMore Whether more entries are available
     */
    @Serializable
    data class LogEntryBrowseResultDto(
        val entries: List<LogEntryDto>,
        val totalCount: Int? = null,
        val hasMore: Boolean = false,
    )

    /**
     * Request DTO for browsing log entries.
     *
     * @property direction Browse direction: "first", "last", or "byId"
     * @property entryId Entry ID for "byId" direction
     * @property count Number of entries to fetch
     */
    @Serializable
    data class LogEntryBrowseRequestDto(
        val direction: String = "first",
        val entryId: Long? = null,
        val count: Int = 20,
    ) {
        fun validate() {
            require(direction in listOf("first", "last", "byId")) { "Direction must be first, last, or byId" }
            if (direction == "byId") {
                require(entryId != null) { "Entry ID is required for byId direction" }
            }
            require(count in 1..100) { "Count must be between 1 and 100" }
        }
    }

    // ========================================
    // LCM Export DTOs (Phase 6)
    // ========================================

    /**
     * Result DTO for LCM data export.
     *
     * @property success Whether the export was successful
     * @property message Human-readable result message
     * @property formatVersion LCM format/version string returned by the SDK
     * @property filePath Path where the LCM file was written
     */
    @Serializable
    data class LcmExportResultDto(
        val success: Boolean,
        val message: String,
        val formatVersion: String? = null,
        val filePath: String? = null,
    )
}
