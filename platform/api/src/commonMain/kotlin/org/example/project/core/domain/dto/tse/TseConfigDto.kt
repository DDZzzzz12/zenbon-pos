package org.example.project.core.domain.dto.tse

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseFailureBehavior

/**
 * Container object for TSE configuration DTOs.
 *
 * TSE configuration is stored in ShopConfiguration table as JSON values with keys:
 * - "tse.config" - Main TSE configuration
 * - "tse.lifecycle" - TSE lifecycle tracking data
 *
 * @since 1.0.0
 */
object TseConfigDto {
    /**
     * TSE configuration DTO stored in ShopConfiguration.
     *
     * Key: "tse.config"
     *
     * Contains all necessary information to start and manage the TSE Connector Server,
     * including hardware settings, credentials, and operating mode.
     *
     * SECURITY WARNING:
     * - PIN/PUK should be encrypted before storage
     * - Consider using environment variables for sensitive data
     * - Default credentials MUST be changed in production
     */
    @Serializable
    data class TseConfig(
        // Operating Mode
        val mode: TseMode = TseMode.DISABLED,
        // Authentication.
        //
        // Only the TimeAdmin PIN is stored. The Admin PIN, Admin PUK and credential seed are
        // deliberately absent: the Swissbit guidance manual (2.5) forbids the ERS from storing
        // them while explicitly permitting the TimeAdmin PIN, which is why time sync can stay
        // unattended. Admin credentials are supplied per operation by the operator and held
        // only in memory — see TseAdminSession.
        val timeAdminPin: String,
        // Keep-Alive Configuration
        val keepAliveEnabled: Boolean = true,
        val keepAliveIntervalSeconds: Int = 30,
        // Failure Behavior: QUEUE (continue, sign later) or FAIL (rollback transaction)
        val failureBehavior: TseFailureBehavior = TseFailureBehavior.QUEUE,
        /**
         * Response-only: whether a TimeAdmin PIN is stored, since [timeAdminPin] itself never leaves the
         * server. Lets a client refuse a save that would be refused for a missing PIN before sending it.
         * Null in storage (it is never persisted — null is the default and so omitted) and from older
         * servers; a client then leaves the decision to the server.
         */
        val timeAdminPinConfigured: Boolean? = null,
    ) {
        /**
         * Validate configuration values.
         * Throws IllegalArgumentException if invalid.
         */
        fun validate() {
            require(timeAdminPin.isNotBlank()) { "Time admin PIN is required" }
            require(keepAliveIntervalSeconds > 0) { "Keep-alive interval must be positive" }
        }

        /**
         * This configuration as it may leave the server: the TimeAdmin PIN blanked.
         *
         * The PIN is stored so time sync runs unattended, but no API response returns it (T9-14).
         * A blank PIN in a client's update request means "keep the stored one", so a form filled
         * from this copy and saved unchanged does not overwrite the PIN.
         */
        fun withoutCredentials(): TseConfig = copy(timeAdminPin = "", timeAdminPinConfigured = timeAdminPin.isNotBlank())
    }

    /**
     * Request DTO to update TSE configuration.
     */
    @Serializable
    data class UpdateTseConfigRequest(
        val mountPoint: String? = null,
        val mode: TseMode? = null,
        // No adminPin/adminPuk/credentialSeed: they are never stored, so there is nothing here
        // to update. Setup takes them as request parameters instead — see
        // TseDto.SetupCredentialsRequestDto.
        val timeAdminPin: String? = null,
        val keepAliveEnabled: Boolean? = null,
        val keepAliveIntervalSeconds: Int? = null,
        val failureBehavior: TseFailureBehavior? = null,
    )

    /**
     * Response DTO for TSE configuration endpoint from main server.
     *
     * Used to deserialize the response from main server's /api/internal/tse/config endpoint.
     * This is the wrapper response that indicates whether TSE is configured and provides
     * the configuration if available.
     *
     * @property configured Whether TSE configuration exists on the main server
     * @property message Human-readable status message
     * @property config The TSE configuration if configured, null otherwise
     */
    @Serializable
    data class TseConfigResponseDto(
        val configured: Boolean,
        val message: String,
        val config: TseConfig? = null,
        val clientId: String? = null,
        /**
         * Whether the proprietary Swissbit SDK is present on the server's classpath.
         *
         * LOCAL connection mode drives the TSE hardware in-process through that SDK, so
         * without it LOCAL cannot sign and terminals can never complete TSE client
         * registration. The server rejects switching to LOCAL in that state; this flag
         * lets the UI disable the option up front instead of surfacing a raw error.
         *
         * Defaults to true so an older server that does not send the field keeps the
         * previous UI behaviour rather than hiding LOCAL mode.
         */
        val tseSdkAvailable: Boolean = true,
    )

    /** Status of applying a TSE config/mode change, surfaced to the operator. */
    enum class TseModeApplyStatus {
        OPERATIONAL,
        DISABLED,
        UNREACHABLE,
        AUTH_FAILED,
        INVALID_CONFIG,

        /**
         * The TSE answers but is still UNINITIALIZED — it needs setup, not a PIN. Never sent in
         * [TseConfigUpdateResultDto.applyStatus], only in [TseConfigUpdateResultDto.applyOutcome]:
         * a client built before this value existed cannot decode it (no `coerceInputValues`, and the
         * field has no default), which would fail the setup dialog's pre-save and so the setup itself.
         */
        NOT_COMMISSIONED,
    }

    /** Result of applying a TSE config/mode change, surfaced synchronously to the operator. */
    @Serializable
    data class TseConfigUpdateResultDto(
        val config: TseConfig,
        /**
         * The outcome as an older client can decode it: only the original five values. Read
         * [effectiveApplyStatus] instead — it prefers [applyOutcome].
         */
        val applyStatus: TseModeApplyStatus,
        /** Human-readable detail; safe to show in the UI. */
        val message: String,
        /** True when AUTH_FAILED and the TSE may be locked — UI may route to the PUK unblock flow. */
        val mayBeLocked: Boolean = false,
        /** Real remaining PIN attempts from the TSE when AUTH_FAILED and known; null otherwise. */
        val remainingRetries: Int? = null,
        /**
         * The full outcome, including values newer than [applyStatus] may carry
         * ([TseModeApplyStatus.NOT_COMMISSIONED]). Null from a server that predates it. An older
         * client ignores this key (`ignoreUnknownKeys`).
         */
        val applyOutcome: TseModeApplyStatus? = null,
    ) {
        /** What happened: [applyOutcome] when the server sent it, else the legacy [applyStatus]. */
        val effectiveApplyStatus: TseModeApplyStatus get() = applyOutcome ?: applyStatus
    }
}
