/**
 * Voucher configuration utility for managing voucher system settings.
 *
 * This utility handles voucher configuration operations including:
 * - JSON serialization/deserialization of voucher configuration
 * - Validation of voucher sources and rules
 * - Integration with ShopConfigurationManager for cached configuration access
 * - Helper methods for checking voucher source support
 *
 * Uses type-safe VoucherConfig data class with JSON serialization
 * for better compile-time checking and IDE support.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.models.setup.VoucherConfig
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.slf4j.LoggerFactory

/**
 * Utility object for managing voucher system configuration.
 *
 * Uses type-safe VoucherConfig data class with JSON serialization.
 *
 * Voucher Source Types:
 * - INTERNAL: Vouchers created and sold through the POS system
 * - LEGACY: Legacy vouchers from previous systems or manual creation
 * - EXTERNAL: Vouchers from external partner systems
 * - PROMOTIONAL: Promotional vouchers for marketing campaigns
 * - GIFT_CARD: Gift cards from card processing providers
 */
object VoucherConfigUtil {
    private val logger = LoggerFactory.getLogger(VoucherConfigUtil::class.java)
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    /**
     * Gets the voucher configuration from shop configuration manager.
     *
     * @return VoucherConfig loaded from database or default configuration
     */
    suspend fun getVoucherConfig(): VoucherConfig =
        try {
            val voucherConfigJson = ShopConfigurationManager.getVoucherConfig()
            toVoucherConfig(voucherConfigJson)
        } catch (e: Exception) {
            logger.error("Failed to load voucher configuration, using default", e)
            VoucherConfig.getDefault()
        }

    /**
     * Checks if vouchers are enabled in the system.
     *
     * @return true if voucher functionality is enabled, false otherwise
     */
    suspend fun isVoucherEnabled(): Boolean =
        try {
            val config = getVoucherConfig()
            config.enabled
        } catch (e: Exception) {
            logger.error("Failed to check voucher enabled status", e)
            false
        }

    /**
     * Checks if a specific voucher source type is enabled.
     *
     * @param sourceType The voucher source type to check
     * @return true if the source type is configured and enabled, false otherwise
     */
    suspend fun isSourceEnabled(sourceType: VoucherSourceType): Boolean =
        try {
            val config = getVoucherConfig()
            config.isSourceEnabled(sourceType)
        } catch (e: Exception) {
            logger.error("Failed to check if source $sourceType is enabled", e)
            false
        }

    /**
     * Gets the configuration for a specific voucher source type.
     *
     * @param sourceType The voucher source type to get configuration for
     * @return VoucherSource configuration, or null if not configured
     */
    suspend fun getSourceConfig(sourceType: VoucherSourceType): VoucherSource? =
        try {
            val config = getVoucherConfig()
            config.getSourceConfig(sourceType)
        } catch (e: Exception) {
            logger.error("Failed to get source configuration for $sourceType", e)
            null
        }

    /**
     * Parses JSON string to VoucherConfig data class.
     *
     * @param jsonString JSON representation of voucher configuration
     * @return Parsed VoucherConfig
     * @throws SerializationException if JSON is invalid
     */
    fun parseVoucherConfig(jsonString: String): VoucherConfig = json.decodeFromString<VoucherConfig>(jsonString)

    fun toVoucherConfig(config: JsonElement): VoucherConfig = json.decodeFromJsonElement(config)

    /**
     * Converts VoucherConfig to JSON string.
     *
     * @param config The voucher configuration
     * @return JSON string representation
     */
    fun toJson(config: VoucherConfig): String = json.encodeToString(VoucherConfig.serializer(), config)

    /**
     * Gets the default voucher configuration as JSON string.
     *
     * @return Default voucher configuration in JSON format
     */
    fun getDefaultConfigJson(): String = toJson(VoucherConfig.getDefault())

    /**
     * Validates that a voucher configuration JSON string is properly formatted.
     *
     * Checks:
     * - Valid JSON structure matching VoucherConfig schema
     * - All voucher sources have valid configurations
     * - Validation rules are properly defined
     * - Global settings are within acceptable ranges
     *
     * @param voucherConfigJson JSON string to validate
     * @return true if valid, false otherwise
     */
    fun validateVoucherConfig(voucherConfigJson: String): Boolean =
        try {
            val config = parseVoucherConfig(voucherConfigJson)

            // Use built-in validation from data class
            val isValid = config.isValid()

            if (!isValid) {
                logger.error("Voucher configuration contains invalid source configurations or settings")
            }

            isValid
        } catch (e: SerializationException) {
            logger.error("Voucher configuration JSON is malformed", e)
            false
        } catch (e: Exception) {
            logger.error("Voucher configuration validation failed", e)
            false
        }

    /**
     * Creates a custom voucher configuration.
     *
     * Helper function for programmatically creating configurations
     * instead of manually writing JSON.
     *
     * @param builder Lambda to configure voucher settings
     * @return JSON string of the configured voucher system
     */
    fun createCustomConfig(builder: VoucherConfig.() -> VoucherConfig): String {
        val config = VoucherConfig.getDefault().builder()
        return toJson(config)
    }
}
