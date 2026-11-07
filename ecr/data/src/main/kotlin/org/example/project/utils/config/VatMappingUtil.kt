/**
 * Configurable VAT mapping utility that replaces hardcoded VatUtil.
 *
 * This utility determines VAT class based on:
 * - Product type (FOOD, DRINKS, RETAIL, SERVICE, VOUCHER, OTHERS)
 * - Conditions (toGo, reduced)
 * - Shop-specific configuration stored in database
 *
 * Uses type-safe VatMappingConfig data class with JSON serialization
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
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.models.setup.VatMappingConfig
import org.example.project.core.domain.models.setup.getRulesFor
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.slf4j.LoggerFactory

/**
 * Utility object for determining VAT class based on configurable mapping.
 *
 * Uses type-safe VatMappingConfig data class with JSON serialization.
 *
 * VAT Classes:
 * - A: Standard rate (19% in Germany)
 * - B: Reduced rate (7% in Germany)
 * - C: Zero rate (0%)
 */
object VatMappingUtil {
    private val logger = LoggerFactory.getLogger(VatMappingUtil::class.java)
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    /**
     * Determines the appropriate VAT class for a product based on type and conditions.
     *
     * The VAT mapping is loaded from the shop configuration which can be
     * customized per shop. Falls back to sensible defaults if configuration
     * is unavailable or invalid.
     *
     * @param productType The type of product (FOOD, DRINKS, etc.)
     * @param toGo Whether the product is for takeaway/to-go
     * @param reduced Whether the reduced VAT rate should be applied
     * @return The VAT entity corresponding to the determined VAT class
     * @throws IllegalStateException if the determined VAT class is not configured in the system
     */
    suspend fun determineVat(
        productType: ProductType,
        toGo: Boolean,
        reduced: Boolean = false,
        configManager: IShopConfigurationManager,
    ): Vat =
        try {
            // Get VAT mapping configuration from database
            val vatMappingJson = configManager.getVatMapping()
            val vatConfig = toVatMappingConfig(vatMappingJson)

            // Get rules for this product type
            val rules = vatConfig.getRulesFor(productType)
            val vatClass = rules.determineVatClass(isToGo = toGo, isReduced = reduced)

            // Find the VAT entity
            Vat.findById(vatClass)
                ?: error("VAT class '$vatClass' not configured for product type ${productType.name}")
        } catch (e: Exception) {
            logger.error("Failed to determine VAT for product type ${productType.name}, using fallback", e)
            // Fallback to default VAT logic
            determineFallbackVat(productType, toGo, reduced)
        }

    /**
     * VAT for a deposit / deposit-return line.
     *
     * DSFinV-K Anhang C „Pfand": Warenumschließungen share the MAIN PRODUCT's rate as
     * unselbständige Nebenleistung („Lieferung von Milch 7 % USt – Pfand Milchflasche
     * ebenfalls 7 % USt"); only Transporthilfsmittel are always at the general rate. The
     * order model records deposit lines WITHOUT a link to the triggering product, so the
     * rate is configured per PfandType — operators must create one PfandType per rate
     * family (e.g. „Mehrweg 19 %" for in-house drinks, „Mehrweg 7 %" for reduced-rate
     * goods) and assign products accordingly (audit BPOS-10; Steuerberater sign-off
     * recommended; a deposit↔main-item link is the future structural fix).
     *
     * Must be called within a transaction (reads the product's PfandType relation).
     */
    fun determineDepositVat(product: Product): Vat {
        val cls =
            product.pfandType?.vatClass
                ?: "A".also {
                    logger.warn(
                        "Deposit product {} has no PfandType — VAT falls back to class A (19%); " +
                            "verify against the Warenumschließung rule (Anhang C)",
                        product.productNumber,
                    )
                }
        return Vat.findById(cls)
            ?: Vat.findById("A")
            ?: error("VAT class '$cls' not configured for deposit product ${product.productNumber}")
    }

    /**
     * Parses JSON string to VatMappingConfig data class.
     *
     * @param jsonString JSON representation of VAT mapping
     * @return Parsed VatMappingConfig
     * @throws SerializationException if JSON is invalid
     */
    fun parseVatMapping(jsonString: String): VatMappingConfig = json.decodeFromString<VatMappingConfig>(jsonString)

    fun toVatMappingConfig(config: JsonElement): VatMappingConfig = json.decodeFromJsonElement(config)

    /**
     * Converts VatMappingConfig to JSON string.
     *
     * @param config The VAT mapping configuration
     * @return JSON string representation
     */
    fun toJson(config: VatMappingConfig): String = json.encodeToString(config)

    /**
     * Gets the default VAT mapping configuration as JSON string.
     *
     * @return Default VAT mapping in JSON format
     */
    fun getDefaultMappingJson(): String = toJson(VatMappingConfig.getDefault())

    /**
     * Fallback VAT determination using hardcoded logic.
     *
     * This is used when the configuration is unavailable or invalid.
     * Matches the original VatUtil behavior for backwards compatibility.
     *
     * @param productType The type of product
     * @param toGo Whether the product is for takeaway
     * @param reduced Whether reduced rate should apply
     * @return The VAT entity
     */
    private fun determineFallbackVat(
        productType: ProductType,
        toGo: Boolean,
        reduced: Boolean,
    ): Vat {
        logger.warn("Using fallback VAT determination for ${productType.name}")

        return when (productType) {
            ProductType.FOOD -> {
                if (toGo or reduced) {
                    Vat.Companion.findById("B") ?: error("VAT REDUCED_7 not configured")
                } else {
                    Vat.Companion.findById("A") ?: error("VAT STANDARD_19 not configured")
                }
            }
            ProductType.DRINKS -> {
                Vat.Companion.findById("A") ?: error("VAT STANDARD_19 not configured")
            }
            ProductType.RETAIL -> {
                Vat.Companion.findById("A") ?: error("Default VAT not configured")
            }
            ProductType.SERVICE -> {
                Vat.Companion.findById("A") ?: error("VAT STANDARD_19 not configured")
            }
            ProductType.VOUCHER -> {
                Vat.Companion.findById("C") ?: error("VAT ZERO not configured")
            }
            ProductType.OTHERS -> {
                Vat.Companion.findById("C") ?: error("VAT ZERO not configured")
            }
        }
    }

    /**
     * Validates that a VAT mapping JSON string is properly formatted.
     *
     * Checks:
     * - Valid JSON structure matching VatMappingConfig schema
     * - All ProductType enums are present
     * - Each product type has a "default" mapping
     * - All VAT class references are valid (A, B, or C)
     *
     * @param vatMappingJson JSON string to validate
     * @return true if valid, false otherwise
     */
    fun validateVatMapping(vatMappingJson: String): Boolean =
        try {
            val config = parseVatMapping(vatMappingJson)

            // Use built-in validation from data class
            val isValid = config.isValid()

            if (!isValid) {
                logger.error("VAT mapping contains invalid VAT class references")
            }

            isValid
        } catch (e: SerializationException) {
            logger.error("VAT mapping JSON is malformed", e)
            false
        } catch (e: Exception) {
            logger.error("VAT mapping validation failed", e)
            false
        }

    /**
     * Gets a human-readable description of the VAT mapping configuration.
     *
     * Useful for logging and debugging purposes.
     *
     * @param vatMappingJson JSON string containing VAT mapping
     * @return Formatted description string
     */
    fun describeVatMapping(vatMappingJson: String): String =
        try {
            val config = parseVatMapping(vatMappingJson)
            buildString {
                appendLine("VAT Mapping Configuration:")
                appendLine("  FOOD: ${config.FOOD.describe()}")
                appendLine("  DRINKS: ${config.DRINKS.describe()}")
                appendLine("  RETAIL: ${config.RETAIL.describe()}")
                appendLine("  SERVICE: ${config.SERVICE.describe()}")
                appendLine("  VOUCHER: ${config.VOUCHER.describe()}")
                appendLine("  OTHERS: ${config.OTHERS.describe()}")
            }
        } catch (e: Exception) {
            "Invalid VAT mapping configuration: ${e.message}"
        }

    /**
     * Creates a custom VAT mapping configuration.
     *
     * Helper function for programmatically creating configurations
     * instead of manually writing JSON.
     *
     * @param builder Lambda to configure VAT mapping
     * @return JSON string of the configured mapping
     */
    fun createCustomMapping(builder: VatMappingConfig.() -> VatMappingConfig): String {
        val config = VatMappingConfig.getDefault().builder()
        return toJson(config)
    }
}
