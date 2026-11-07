/**
 * Data Transfer Objects for Initial Setup API operations.
 *
 * Container object for all Initial Setup-related DTOs including request validation,
 * response formatting, and data transformation utilities. Follows the standard
 * object container pattern for organized DTO management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.setup

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.enums.setup.SetupPriority
import org.example.project.core.domain.enums.setup.SetupSteps
import org.example.project.core.utils.DsfinvkFieldLimits
import org.example.project.core.utils.VatIdFormat

/**
 * Container object for all Initial Setup-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 */
@Suppress("UnsafeOptInUsageError")
object InitialSetupDto {
    /**
     * Request DTO for initial system setup configuration.
     *
     * Contains configuration parameters for system initialization
     * including admin user details and business information.
     */
    @Serializable
    data class InitialSetupRequestDto<T>(
        val setupStep: SetupSteps,
        val data: T,
    ) {
        /**
         * Validates the initial setup request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
        }
    }

    /**
     * Response DTO for initial setup operation results.
     *
     * Contains detailed information about setup completion status
     * and summary of created data.
     */
    @Serializable
    data class InitialSetupResponseDto(
        /** Whether setup completed successfully */
        val success: Boolean,
        /** Human-readable message about setup status */
        val message: String,
        /** Detailed information about created entities */
        val setupSummary: SetupSummaryDto,
        /** Any warnings or non-critical issues encountered */
        val warnings: List<String> = emptyList(),
        /** Timestamp when setup was completed */
        val completedAt: String, // ISO string format
        /** Duration of setup process in milliseconds */
        val durationMs: Long,
    )

    /**
     * Summary DTO of entities created during initial setup.
     *
     * Provides count and details of all data created during
     * the initialization process.
     */
    @Serializable
    data class SetupSummaryDto(
        /** Whether shop information was configured */
        val shopInfoConfigured: Boolean,
    )

    /**
     * Status DTO for system initialization check.
     *
     * Indicates current initialization state and what components
     * have been set up.
     */
    @Serializable
    data class InitialSetupStatusDto(
        /** Whether initial setup has been completed */
        val isInitialized: Boolean,
        /** Details about current system state */
        val systemState: SystemStateDto? = null,
        /** Whether system requires setup or reset */
        val requiresSetup: Boolean = true,
    )

    /**
     * System state information DTO.
     *
     * Provides detailed information about current system configuration
     * and data presence.
     */
    @Serializable
    data class SystemStateDto(
        /** Whether admin users exist */
        val hasAdminUsers: Boolean,
        /** Whether shop information is configured */
        val hasShopInfo: Boolean,
        val hasShopConfig: Boolean,
        /** Number of product categories */
        val categoryCount: Long,
        /** Number of products */
        val productCount: Long,
        /** Number of tables configured */
        val tableCount: Long,
        /** Whether VAT rates are configured */
        val hasVatRates: Boolean,
        /** Whether payment types are configured */
        val hasPaymentTypes: Boolean,
    )

    /**
     * Request DTO for system reset operations.
     *
     * Contains confirmation parameters and options for system reset.
     */
    @Serializable
    data class ResetSystemRequestDto(
        /** Confirmation string (must be "CONFIRM_RESET") */
        val confirmationCode: String,
        /** Whether to preserve admin users during reset */
        val preserveAdminUsers: Boolean = false,
        /** Whether to preserve shop information during reset */
        val preserveShopInfo: Boolean = false,
        /** Reason for system reset (for audit log) */
        val resetReason: String? = null,
    ) {
        /**
         * Validates the reset request.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(confirmationCode == "CONFIRM_RESET") {
                "Invalid confirmation code. Must be 'CONFIRM_RESET' to proceed with system reset."
            }
        }
    }

    /**
     * Validation results DTO for setup prerequisites.
     *
     * Contains information about system readiness for initial setup.
     */
    @Serializable
    data class PrerequisiteValidationDto(
        /** Whether all prerequisites are met */
        val allRequirementsMet: Boolean,
        /** Database connectivity status */
        val databaseConnected: Boolean,
        /** Whether all required tables exist */
        val requiredTablesExist: Boolean,
        /** List of missing tables (if any) */
        val missingTables: List<String> = emptyList(),
        /** Whether system has write permissions */
        val hasWritePermissions: Boolean,
        /** Any blocking issues that prevent setup */
        val blockingIssues: List<String> = emptyList(),
        /** Non-critical warnings */
        val warnings: List<String> = emptyList(),
        /** Recommended actions before setup */
        val recommendations: List<String> = emptyList(),
    )

    /**
     * DTO for individual setup step information.
     *
     * Contains details about a single setup step including completion status
     * and timing information for progress tracking.
     */
    @Serializable
    data class SetupStepDto(
        /** Sequential step identifier */
        val setupStep: SetupSteps,
        /** Whether this step is completed */
        val isCompleted: Boolean,
        val priority: SetupPriority,
        /** When the step was completed (ISO timestamp) */
        val setupTime: String? = null,
        /** When the step was reset (ISO timestamp) */
        val resetTime: String? = null,
        /** User-friendly display name for the step */
        val displayName: String,
        /** Description of what this step accomplishes */
        val description: String,
    )

    /**
     * DTO for overall setup progress information.
     *
     * Provides comprehensive progress tracking including current step,
     * completion percentage, and detailed step-by-step status.
     */
    @Serializable
    data class SetupProgressDto(
        /** Total number of setup steps */
        val totalSteps: Int,
        /** Number of completed steps */
        val completedSteps: List<SetupSteps?> = emptyList(),
        /** Progress percentage (0-100) */
        val progressPercentage: Int,
        /** Current step being worked on (null if all complete) */
        val currentStep: SetupStepDto? = null,
        /** Next step to be completed (null if all complete) */
        val nextStep: SetupStepDto? = null,
        /** List of all setup steps with their status */
        val allSteps: List<SetupStepDto>,
        /** Whether all setup steps are complete */
        val isSetupComplete: Boolean,
        /** Estimated time remaining (if available) */
        val estimatedTimeRemaining: String? = null,
    )

    // ===== COMPLETE SETUP REQUEST DTO =====

    /**
     * Request DTO for atomic complete setup via UI wizard.
     *
     * Combines owner account and shop information into a single request
     * for atomic initialization. TSE config, payment types, VAT rates,
     * and all ConfigKey defaults are auto-configured by the server.
     */
    @Serializable
    data class CompleteSetupRequestDto(
        /** Owner/administrator account information */
        val owner: OwnerSetupRequestDto,
        /** Shop/business information */
        val shop: ShopSetupRequestDto,
    ) {
        /**
         * Validates both owner and shop request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            owner.validate()
            shop.validate()
        }
    }

    // ===== STEP-SPECIFIC REQUEST DTOS =====

    /**
     * Request DTO for Step 1: Owner/Administrator Setup.
     *
     * Contains credentials and information for creating the initial
     * system administrator account.
     */
    @Serializable
    data class OwnerSetupRequestDto(
        /** Administrator username for system access */
        val adminUsername: String,
        /** Administrator password (will be hashed for security) */
        val adminPassword: String,
        /** Administrator email for notifications and recovery */
        val adminEmail: String,
        /** Administrator first name */
        val adminFirstName: String,
        /** Administrator last name */
        val adminLastName: String,
        val adminPin: String,
        val languagePreference: String = "DE",
    ) {
        /**
         * Validates the owner setup request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(adminUsername.isNotBlank()) { "Admin username is required" }
            require(adminPassword.length >= 6) { "Admin password must be at least 6 characters" }
            require(adminEmail.isNotBlank()) { "Admin email is required" }
            require(adminEmail.contains("@")) { "Admin email must be valid" }
            // The name is a fiscal field, not decoration: it is written to `employee_info` and is
            // where DSFinV-K's BEDIENER_NAME comes from, so every receipt the owner signs carries
            // it. Rejected here, before anything is written, rather than deeper down.
            require(adminFirstName.isNotBlank()) { "Admin first name is required" }
            require(adminLastName.isNotBlank()) { "Admin last name is required" }
        }
    }

    /**
     * Request DTO for Step 2: Shop Information Setup.
     *
     * Contains business information and configuration for the POS system.
     */
    @Serializable
    data class ShopSetupRequestDto(
        /** Business/shop name */
        val shopName: String,
        /** Company name (optional - can be different from shop name) */
        val companyName: String? = null,
        /** Business address  */
        val shopAddress: String,
        /** Business city */
        val shopCity: String,
        /** Postal/ZIP code */
        val zipCode: Int,
        /** Business country */
        val shopCountry: String = "Germany",
        /** Business contact phone */
        val shopPhone: String,
        /** TAX number (optional) */
        val shopTaxNumber: String? = null,
        /**
         * USt-IdNr (VAT identification number). Exported as DSFinV-K `USTID` and snapshotted onto
         * every Z-Bon as `stammUstId`, so it must be captured at setup — before the first bill.
         */
        val ustIdNr: String? = null,
        /** Bank name (optional) */
        val shopBankName: String? = null,
        /** Bank BIC code (optional) */
        val shopBankBic: String? = null,
        /** Bank IBAN (optional) */
        val shopBankIBAN: String? = null,
        /** Business contact email (optional) */
        val shopEmail: String? = null,
        /** Business timezone */
        val shopTimezone: String = "Europe/Berlin",
        /** Official website URL (optional) */
        val shopWebsite: String? = null,
        /** Currency code (ISO 4217) */
        val currencyCode: String = "EUR",
    ) {
        /**
         * Validates the shop setup request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(shopName.isNotBlank()) { "Shop name is required" }
            require(currencyCode.length == 3) { "Currency code must be 3 characters (ISO 4217)" }
            require(zipCode.toString().length in 3..10) { "ZIP code must be between 3 and 10 characters" }
            shopEmail?.let {
                require(it.contains("@")) { "Shop email must be valid if provided" }
            }

            // DSFinV-K Anhang E field lengths (round-7 C7). The wizard is where these values first
            // enter the system, and CreateShopInfoRequestDto.validate() would catch an over-long
            // one only later, in the repository, as a bare IllegalArgumentException. Checking here
            // rejects it at the step the operator is actually filling in.
            DsfinvkFieldLimits.requireMaxLength(shopName, DsfinvkFieldLimits.NAME, "Shop name (NAME)")
            DsfinvkFieldLimits.requireMaxLength(shopAddress, DsfinvkFieldLimits.STRASSE, "Street (STRASSE)")
            DsfinvkFieldLimits.requireMaxLength(shopCity, DsfinvkFieldLimits.ORT, "City (ORT)")
            DsfinvkFieldLimits.requireMaxLength(shopTaxNumber, DsfinvkFieldLimits.STNR, "Tax number (STNR)")
            VatIdFormat.requireValid(ustIdNr, "VAT ID (USTID)")
        }
    }

    /**
     * Request DTO for Step 3: Payment Types Setup.
     *
     * Contains configuration for available payment methods.
     */
    @Serializable
    data class PaymentTypesSetupRequestDto(
        /** List of payment type names to create */
        val useSamplePaymentTypes: Boolean = true,
        val paymentTypes: List<String> = emptyList(),
    ) {
        fun validate() {
            if (!useSamplePaymentTypes) {
                require(paymentTypes.isNotEmpty()) { "Payment types must be provided if sample data is used" }
                paymentTypes.forEach { type ->
                    require(type.isNotBlank()) { "Payment type name cannot be blank" }
                }
            }
        }
    }

    /**
     * Single VAT rate configuration.
     */
    @Serializable
    data class VatRateDto(
        /** VAT class (A, B, C for German compliance) */
        val vatClass: String,
        /** VAT rate as decimal (e.g., 0.19 for 19%) */
        val vatRate: Double,
        /** Optional description */
        val description: String? = null,
    )

    /**
     * Request DTO for Step 4: VAT Setup.
     *
     * Contains configuration for tax rates and VAT settings.
     */
    @Serializable
    data class VatSetupRequestDto(
        /** Whether to create standard German VAT rates (19%, 7%, 0%) */
        val createStandardGermanRates: Boolean = true,
        /** Additional custom VAT rates */
        val customRates: List<VatRateDto> = emptyList(),
    ) {
        /**
         * Validates the VAT setup request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            customRates.forEach { rate ->
                require(rate.vatClass.isNotBlank()) { "VAT class is required for custom rates" }
                require(rate.vatRate in 0.0..1.0) { "VAT rate must be between 0.0 and 1.0" }
            }
        }
    }

    /**
     * Request DTO for Step 5: TSE Mode Setup.
     *
     * Contains configuration for TSE (Technical Security Element) mode selection.
     */
    @Serializable
    data class TseSetupRequestDto(
        /** Selected TSE mode for fiscal compliance */
        val tseMode: String,
        /**
         * TODO: add necessary data for starting the physical tse, like tse pin puk etc.
         */
        /** Optional description or configuration notes */
        val description: String? = null,
    ) {
        /**
         * Validates the TSE setup request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(tseMode.isNotBlank()) { "TSE mode is required" }
            val validModes = listOf("SIMULATION", "NORMAL", "DISABLED")
            require(tseMode in validModes) { "TSE mode must be one of: ${validModes.joinToString(", ")}" }
        }
    }

    /**
     * Request DTO for Step 6: Sample Data Setup (Optional).
     *
     * Contains options for creating sample/demo data.
     */
    @Serializable
    data class SampleDataSetupRequestDto(
        /** Whether to include any sample data */
        val includeSampleData: Boolean = true,
    ) {
        /**
         * Validates the sample data setup request.
         */
        fun validate() {
            // No specific validation needed for sample data setup
        }
    }

    /**
     * Request DTO for Step 7: Employee Setup (Optional).
     *
     * Contains options for additional employee configuration.
     */
    @Serializable
    data class EmployeeSetupRequestDto(
        /** Whether to skip additional employee setup for now */
        val skipAdditionalEmployees: Boolean = true,
        /** List of additional employees to create (if not skipping) */
        val additionalEmployees: List<EmployeeDto.CreateEmployeeRequestDto> = emptyList(), // Could be expanded later
    ) {
        /**
         * Validates the employee setup request.
         */
        fun validate() {
            // No specific validation needed for basic employee setup
        }
    }

    /**
     * Request DTO for Step 8: Server Configuration.
     *
     * Contains configuration for server settings including port, logging, and document storage.
     */
    @Serializable
    data class ServerConfigSetupRequestDto(
        /** Server port number */
        val serverPort: Int = 8080,
        /** Whether server logging is enabled */
        val serverLoggingEnabled: Boolean = true,
        /** Document storage path */
        val documentStoragePath: String = "./documents",
        /** Maximum PDF file size in MB */
        val maxUploadSizeMb: Int = 20,
    ) {
        /**
         * Validates the server configuration setup request.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(serverPort in 1024..65535) { "Server port must be between 1024 and 65535" }
            require(documentStoragePath.isNotBlank()) { "Document storage path is required" }
            require(maxUploadSizeMb > 0) { "Max upload size must be greater than 0" }
            require(maxUploadSizeMb <= 100) { "Max upload size must not exceed 100 MB" }
        }
    }

    /**
     * Response DTO for individual setup step execution.
     *
     * Contains the result of executing a single setup step including
     * completion status and next step information.
     */
    @Serializable
    data class SetupStepResponseDto(
        val setupStep: SetupSteps,
        /** Whether the step completed successfully */
        val completed: Boolean,
        /** Human-readable message about the execution result */
        val message: String,
        /** ID of the next recommended step (null if setup complete) */
        val nextStep: SetupSteps?,
        /** Timestamp when the step was completed */
        val completedAt: String,
    )

    /**
     * Response DTO for CSV import operations.
     *
     * Contains statistics and results from importing CSV data including
     * success/error counts and detailed error information.
     */
    @Serializable
    data class CsvImportResultDto(
        /** Total number of rows processed in the CSV */
        val totalRows: Int,
        /** Number of rows successfully imported */
        val successCount: Int,
        /** Number of rows that failed to import */
        val errorCount: Int,
        /** Number of rows skipped (duplicates) */
        val skippedCount: Int = 0,
        /** List of error messages with row numbers */
        val errors: List<String> = emptyList(),
        /** Type of data that was imported */
        val dataType: String,
        /** Whether the import completed successfully */
        val success: Boolean,
        /** Human-readable message about the import result */
        val message: String,
    )
}
