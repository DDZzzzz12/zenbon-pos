/**
 * Enumeration for account mapping types in Kassenbuch system.
 *
 * Defines the different types of POS entities that can be mapped to
 * DATEV account codes for automated cash book entry generation and
 * proper accounting classification.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.kassenbuch

/**
 * Types of entity mappings for DATEV account assignment.
 *
 * Categorizes different POS system entities that need to be mapped
 * to specific DATEV account codes for automated bookkeeping.
 */
enum class AccountMappingType(
    val displayName: String,
    val description: String,
    val exampleInternalId: String,
) {
    /** Payment type mappings (Cash, Card, Digital) */
    PAYMENT_TYPE(
        displayName = "Zahlungsart",
        description = "Maps payment types to corresponding DATEV accounts",
        exampleInternalId = "payment_type_1",
    ),

    /** Product category mappings for revenue accounts */
    PRODUCT_CATEGORY(
        displayName = "Produktkategorie",
        description = "Maps product categories to revenue accounts with VAT",
        exampleInternalId = "category_food",
    ),

    /** Expense category mappings for cost accounts */
    EXPENSE_CATEGORY(
        displayName = "Ausgabenkategorie",
        description = "Maps expense types to cost accounts",
        exampleInternalId = "office_supplies",
    ),

    /** VAT rate mappings for tax accounts */
    VAT_RATE(
        displayName = "Mehrwertsteuersatz",
        description = "Maps VAT rates to tax liability accounts",
        exampleInternalId = "vat_19",
    ),

    /** Employee-specific mappings for payroll accounts */
    EMPLOYEE_TYPE(
        displayName = "Mitarbeitertyp",
        description = "Maps employee types to payroll-related accounts",
        exampleInternalId = "employee_tips",
    ),

    /** Tip distribution mappings */
    TIP_CATEGORY(
        displayName = "Trinkgeldkategorie",
        description = "Maps tip types to tip-related accounts",
        exampleInternalId = "tip_cash",
    ),

    /** Voucher and discount mappings */
    DISCOUNT_TYPE(
        displayName = "Rabattart",
        description = "Maps discount types to revenue reduction accounts",
        exampleInternalId = "customer_discount",
    ),

    /** Cash register/workstation mappings */
    CASH_REGISTER(
        displayName = "Kassensystem",
        description = "Maps cash registers to specific cash accounts",
        exampleInternalId = "register_001",
    ),

    /** Location-specific mappings for multi-location businesses */
    LOCATION(
        displayName = "Standort",
        description = "Maps business locations to location-specific accounts",
        exampleInternalId = "location_main",
    ),

    /** Special transaction type mappings */
    SPECIAL_TRANSACTION(
        displayName = "Sonderbuchung",
        description = "Maps special transaction types to corresponding accounts",
        exampleInternalId = "cash_difference",
    ),
    ;

    /**
     * Gets the default debit account for this mapping type.
     *
     * @return Default SKR04 debit account number
     */
    fun getDefaultDebitAccount(): String =
        when (this) {
            PAYMENT_TYPE -> "1000" // Kasse
            PRODUCT_CATEGORY -> "1000" // Kasse (for cash sales)
            EXPENSE_CATEGORY -> "4000" // Büroausgaben (default expense)
            VAT_RATE -> "1000" // Kasse
            EMPLOYEE_TYPE -> "6330" // Löhne und Gehälter
            TIP_CATEGORY -> "1000" // Kasse
            DISCOUNT_TYPE -> "8400" // Erlösschmälerungen
            CASH_REGISTER -> "1000" // Kasse
            LOCATION -> "1000" // Kasse
            SPECIAL_TRANSACTION -> "1000" // Kasse
        }

    /**
     * Gets the default credit account for this mapping type.
     *
     * @return Default SKR04 credit account number
     */
    fun getDefaultCreditAccount(): String =
        when (this) {
            PAYMENT_TYPE -> "8400" // Umsatzerlöse
            PRODUCT_CATEGORY -> "8400" // Umsatzerlöse
            EXPENSE_CATEGORY -> "1000" // Kasse
            VAT_RATE -> "1776" // Umsatzsteuer 19%
            EMPLOYEE_TYPE -> "1000" // Kasse
            TIP_CATEGORY -> "8336" // Trinkgelder
            DISCOUNT_TYPE -> "1000" // Kasse
            CASH_REGISTER -> "8400" // Umsatzerlöse
            LOCATION -> "8400" // Umsatzerlöse
            SPECIAL_TRANSACTION -> "9000" // Eigenkapital
        }

    /**
     * Checks if this mapping type typically involves VAT.
     *
     * @return true if VAT handling is typically required
     */
    fun isVatRelevant(): Boolean =
        when (this) {
            PRODUCT_CATEGORY, VAT_RATE, DISCOUNT_TYPE -> true
            else -> false
        }

    /**
     * Gets the typical configuration fields for this mapping type.
     *
     * @return List of configuration field names
     */
    fun getConfigurationFields(): List<String> =
        when (this) {
            PAYMENT_TYPE -> listOf("fee_account", "fee_percentage")
            PRODUCT_CATEGORY -> listOf("vat_rate", "revenue_type")
            EXPENSE_CATEGORY -> listOf("vat_deductible", "cost_center")
            VAT_RATE -> listOf("tax_rate", "tax_code")
            EMPLOYEE_TYPE -> listOf("tax_class", "social_security")
            TIP_CATEGORY -> listOf("distribution_method", "tax_treatment")
            DISCOUNT_TYPE -> listOf("discount_account", "vat_treatment")
            CASH_REGISTER -> listOf("register_number", "location")
            LOCATION -> listOf("cost_center", "profit_center")
            SPECIAL_TRANSACTION -> listOf("correction_type", "approval_required")
        }
}
