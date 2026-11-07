/**
 * Data classes for voucher configuration.
 *
 * Provides type-safe representation of voucher settings including support for
 * external/legacy vouchers from different sources with configurable validation
 * and redemption rules.
 *
 * Shared across server and client for consistent voucher handling.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.models.setup

import kotlinx.serialization.Serializable

/**
 * Complete voucher system configuration.
 *
 * Defines which voucher sources are supported in the POS system.
 *
 * Example usage:
 * ```kotlin
 * val config = VoucherConfig(
 *     enabled = true,
 *     sources = listOf(
 *         VoucherSource(type = VoucherSourceType.INTERNAL, enabled = true),
 *         VoucherSource(type = VoucherSourceType.LEGACY, enabled = true)
 *     )
 * )
 * ```
 */
@Serializable
data class VoucherConfig(
    /** Master switch for voucher functionality */
    val enabled: Boolean,
    /** List of supported voucher sources */
    val sources: List<VoucherSource>,
) {
    /**
     * Gets configuration for a specific voucher source type.
     *
     * @param sourceType The voucher source type to get configuration for
     * @return VoucherSource configuration, or null if not configured
     */
    fun getSourceConfig(sourceType: VoucherSourceType): VoucherSource? = sources.find { it.type == sourceType }

    /**
     * Checks if a specific voucher source type is enabled.
     *
     * @param sourceType The voucher source type to check
     * @return true if the source is configured and enabled, false otherwise
     */
    fun isSourceEnabled(sourceType: VoucherSourceType): Boolean = getSourceConfig(sourceType)?.enabled == true

    /**
     * Validates the voucher configuration.
     *
     * @return true if configuration is valid, false otherwise
     */
    fun isValid(): Boolean {
        // At least INTERNAL source must be configured if vouchers are enabled
        if (enabled && sources.none { it.type == VoucherSourceType.INTERNAL }) {
            return false
        }

        // All sources must have valid configurations
        return sources.all { it.isValid() }
    }

    companion object {
        /**
         * Creates the default voucher configuration.
         *
         * Default configuration:
         * - Vouchers disabled by default
         * - Only INTERNAL source configured
         *
         * @return Default voucher configuration
         */
        fun getDefault(): VoucherConfig =
            VoucherConfig(
                enabled = false,
                sources =
                    listOf(
                        VoucherSource(
                            type = VoucherSourceType.INTERNAL,
                            name = "Internal System Vouchers",
                            enabled = true,
                            description = "Vouchers created and sold through the POS system",
                        ),
                    ),
            )
    }
}

/**
 * Voucher source type enumeration.
 *
 * Defines the different types of voucher sources supported by the system.
 */
@Serializable
enum class VoucherSourceType(
    val german: String,
) {
    /** Vouchers created and sold through the POS system */
    INTERNAL("Intern"),

    /** Legacy vouchers from previous systems or manual creation */
    LEGACY("Alt"),

    /** Vouchers from external partner systems or third-party providers */
    EXTERNAL("Extern"),

    /** Promotional vouchers for marketing campaigns */
    PROMOTIONAL("Aktion"),

    /** Gift cards from card processing providers */
    GIFT_CARD("Geschenkkarte"),
}

/**
 * Configuration for a specific voucher source.
 *
 * Defines a voucher source and whether it's enabled in the system.
 */
@Serializable
data class VoucherSource(
    /** Type of voucher source */
    val type: VoucherSourceType,
    /** Human-readable name for this voucher source */
    val name: String,
    /** Whether this source is currently enabled */
    val enabled: Boolean,
    /** Optional description of this voucher source */
    val description: String? = null,
) {
    /**
     * Validates the voucher source configuration.
     *
     * @return true if source configuration is valid, false otherwise
     */
    fun isValid(): Boolean {
        // Name must not be blank
        return name.isNotBlank()
    }

    /**
     * Gets a human-readable description of this voucher source.
     *
     * @return Formatted description string
     */
    fun describe(): String =
        buildString {
            append("$name (${type.name})")
            if (enabled) append(" - ENABLED") else append(" - DISABLED")
            description?.let { append(" - $it") }
        }
}
