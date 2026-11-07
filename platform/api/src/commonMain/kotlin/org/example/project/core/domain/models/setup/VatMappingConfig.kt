/**
 * Data classes for VAT mapping configuration.
 *
 * Provides type-safe representation of VAT mapping rules that determine
 * which VAT class to apply based on product type and conditions.
 *
 * Shared across server and client for consistent VAT handling.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.models.setup

import kotlinx.serialization.Serializable

/**
 * Complete VAT mapping configuration for all product types.
 *
 * Maps each ProductType to its VAT rules with support for different
 * conditions (default, toGo, reduced).
 *
 * Example usage:
 * ```kotlin
 * val config = VatMappingConfig(
 *     FOOD = VatRules(default = "A", toGo = "B", reduced = "B"),
 *     DRINKS = VatRules(default = "A")
 * )
 * ```
 */
@Serializable
data class VatMappingConfig(
    /** VAT rules for food products */
    val FOOD: VatRules,
    /** VAT rules for drink products */
    val DRINKS: VatRules,
    /** VAT rules for retail products */
    val RETAIL: VatRules,
    /** VAT rules for service products */
    val SERVICE: VatRules,
    /** VAT rules for voucher products */
    val VOUCHER: VatRules,
    /** VAT rules for other/miscellaneous products */
    val OTHERS: VatRules,
) {
    /**
     * Validates that all VAT class references are valid (A, B, or C).
     *
     * @return true if all VAT classes are valid, false otherwise
     */
    fun isValid(): Boolean {
        val allRules = listOf(FOOD, DRINKS, RETAIL, SERVICE, VOUCHER, OTHERS)
        return allRules.all { it.isValid() }
    }

    companion object {
        /**
         * Creates the default VAT mapping configuration.
         *
         * Default mapping follows German VAT rules:
         * - Food: 19% dine-in, 7% takeaway/reduced
         * - Drinks: 19% standard
         * - Retail: 19% standard
         * - Service: 19% standard
         * - Voucher: 0% (no VAT)
         * - Others: 0%
         *
         * @return Default VAT mapping configuration
         */
        fun getDefault(): VatMappingConfig =
            VatMappingConfig(
                FOOD = VatRules(default = "A", toGo = "B", reduced = "B"),
                DRINKS = VatRules(default = "A"),
                RETAIL = VatRules(default = "A"),
                SERVICE = VatRules(default = "A"),
                VOUCHER = VatRules(default = "C"),
                OTHERS = VatRules(default = "C"),
            )
    }
}

/**
 * VAT rules for a specific product type.
 *
 * Defines which VAT class to apply under different conditions.
 * The priority order is: toGo > reduced > default
 *
 * VAT Classes:
 * - A: Standard rate (19% in Germany)
 * - B: Reduced rate (7% in Germany)
 * - C: Zero rate (0%)
 */
@Serializable
data class VatRules(
    /** Default VAT class to apply (required) */
    val default: String,
    /** VAT class for takeaway/to-go items (optional) */
    val toGo: String? = null,
    /** VAT class for items with reduced rate (optional) */
    val reduced: String? = null,
) {
    /**
     * Determines the appropriate VAT class based on conditions.
     *
     * Priority order:
     * 1. toGo if available and toGo flag is true
     * 2. reduced if available and reduced flag is true
     * 3. default as fallback
     *
     * @param isToGo Whether the item is for takeaway
     * @param isReduced Whether reduced rate should apply
     * @return The VAT class identifier (A, B, or C)
     */
    fun determineVatClass(
        isToGo: Boolean,
        isReduced: Boolean,
    ): String =
        when {
            isToGo && toGo != null -> toGo
            isReduced && reduced != null -> reduced
            else -> default
        }

    /**
     * Validates that all VAT class references are valid.
     *
     * Valid VAT classes are: A, B, C
     *
     * @return true if all VAT classes are valid, false otherwise
     */
    fun isValid(): Boolean {
        val validClasses = setOf("A", "B", "C")
        return default in validClasses &&
            (toGo == null || toGo in validClasses) &&
            (reduced == null || reduced in validClasses)
    }

    /**
     * Gets a human-readable description of these VAT rules.
     *
     * @return Formatted description string
     */
    fun describe(): String =
        buildString {
            append("Default: $default")
            if (toGo != null) append(", ToGo: $toGo")
            if (reduced != null) append(", Reduced: $reduced")
        }
}
