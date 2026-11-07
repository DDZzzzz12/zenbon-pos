/**
 * Data Transfer Objects for Shop Information API operations.
 *
 * Container object for all ShopInfo-related DTOs including request validation,
 * response formatting, and data transformation utilities. Follows the standard
 * object container pattern for organized DTO management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.setup

import kotlinx.serialization.Serializable
import org.example.project.core.utils.DsfinvkFieldLimits
import org.example.project.core.utils.VatIdFormat

/**
 * Container object for all ShopInfo-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain a clear separation of concerns and consistent API structure.
 */
object ShopInfoDto {
    /**
     * Response DTO for shop information data.
     *
     * Used for API responses when returning shop information to clients.
     * Contains all public fields from the entity in a serializable format.
     */
    @Serializable
    data class ShopInfoResponseDto(
        /** Shop unique identifier */
        val shopId: Long,
        /** Shop business name */
        val shopName: String,
        /** Company name (can be different from shop name) */
        val companyName: String? = null,
        /** Physical address of the shop */
        val shopAddress: String? = null,
        /** City where the shop is located */
        val shopCity: String? = null,
        /** Postal/ZIP code */
        val zipCode: Int? = null,
        /** Country where the shop is located */
        val shopCountry: String? = null,
        /** Contact phone number */
        val shopPhone: String? = null,
        /** TAX number */
        val shopTaxNumber: String? = null,
        /** USt-IdNr (VAT identification number) — DSFinV-K `USTID`. */
        val ustIdNr: String? = null,
        /** Bank name */
        val shopBankName: String? = null,
        /** Bank BIC code */
        val shopBankBic: String? = null,
        /** Bank IBAN */
        val shopBankIBAN: String? = null,
        /** Contact email address */
        val shopEmail: String? = null,
        /** Timezone identifier (e.g., "America/New_York") */
        val shopTimezone: String? = null,
        /** Official website URL */
        val shopWebsite: String? = null,
        /** Whether restaurant mode (table management, floor plans) is enabled */
        val restaurantMode: Boolean = true,
        /** Retained for compatibility; no longer read since the kiosk screen was removed. */
        val kioskMode: Boolean = false,
        /** URL path to the shop logo image */
        val logoUrl: String? = null,
        /** Record creation timestamp */
        val createAt: String, // ISO string format for JSON compatibility
    )

    /**
     * Request DTO for creating new shop information.
     *
     * Used for API requests when creating new shop information.
     * Contains required and optional fields for shop setup.
     */
    @Serializable
    data class CreateShopInfoRequestDto(
        /** Shop business name - required */
        val shopName: String,
        /** Company name (optional - can be different from shop name) */
        val companyName: String? = null,
        /** Physical address of the shop */
        val shopAddress: String,
        /** City where the shop is located */
        val shopCity: String,
        /** Postal/ZIP code */
        val zipCode: Int,
        /** Country where the shop is located */
        val shopCountry: String,
        /** Contact phone number */
        val shopPhone: String,
        /** TAX number */
        val shopTaxNumber: String? = null,
        /** USt-IdNr (VAT identification number) — DSFinV-K `USTID`. */
        val ustIdNr: String? = null,
        /** Bank name */
        val shopBankName: String? = null,
        /** Bank BIC code */
        val shopBankBic: String? = null,
        /** Bank IBAN */
        val shopBankIBAN: String? = null,
        /** Contact email address */
        val shopEmail: String? = null,
        /** Timezone identifier (e.g., "America/New_York") */
        val shopTimezone: String,
        /** Official website URL */
        val shopWebsite: String? = null,
        val currencyCode: String = "EUR",
    ) {
        /**
         * Validates the create request data.
         *
         * Ensures all required fields are present and optional fields
         * meet format requirements before processing.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(shopName.isNotBlank()) { "Shop name is required and cannot be blank" }

            // DSFinV-K Anhang E field lengths (round-7 C7). These four are emitted verbatim into
            // cashpointclosing.csv and location.csv for every Kassenabschluss, so an over-long
            // value here makes every future export package formally invalid. Rejected on input
            // rather than truncated on export — see DsfinvkFieldLimits.
            DsfinvkFieldLimits.requireMaxLength(shopName, DsfinvkFieldLimits.NAME, "Shop name (NAME)")
            DsfinvkFieldLimits.requireMaxLength(shopAddress, DsfinvkFieldLimits.STRASSE, "Street (STRASSE)")
            DsfinvkFieldLimits.requireMaxLength(shopCity, DsfinvkFieldLimits.ORT, "City (ORT)")
            DsfinvkFieldLimits.requireMaxLength(shopTaxNumber, DsfinvkFieldLimits.STNR, "Tax number (STNR)")
            VatIdFormat.requireValid(ustIdNr, "VAT ID (USTID)")

            // Validate email format if provided
            shopEmail?.let { email ->
                require(email.contains("@") && email.contains(".")) {
                    "Invalid email format: $email"
                }
            }

            // Validate ZIP code is positive if provided
            zipCode?.let { zip ->
                require(zip > 0) { "ZIP code must be positive: $zip" }
            }

            // Validate website URL format if provided
            shopWebsite?.let { website ->
                require(website.startsWith("http://") || website.startsWith("https://")) {
                    "Website URL must start with http:// or https://"
                }
            }

            // Validate timezone format if provided (basic check)
            shopTimezone?.let { timezone ->
                require(timezone.contains("/") || timezone.matches(Regex("^[A-Z]{3,4}$"))) {
                    "Invalid timezone format: $timezone"
                }
            }
        }
    }

    /**
     * Request DTO for updating existing shop information.
     *
     * Used for API requests when updating shop information.
     * All fields are optional to support partial updates.
     */
    @Serializable
    data class UpdateShopInfoRequestDto(
        /** Shop business name */
        val shopName: String? = null,
        /** Company name (can be different from shop name) */
        val companyName: String? = null,
        /** Physical address of the shop */
        val shopAddress: String? = null,
        /** City where the shop is located */
        val shopCity: String? = null,
        /** Postal/ZIP code */
        val zipCode: Int? = null,
        /** Country where the shop is located */
        val shopCountry: String? = null,
        /** Contact phone number */
        val shopPhone: String? = null,
        /** TAX number */
        val shopTaxNumber: String? = null,
        /** USt-IdNr (VAT identification number) — DSFinV-K `USTID`. */
        val ustIdNr: String? = null,
        /** Bank name */
        val shopBankName: String? = null,
        /** Bank BIC code */
        val shopBankBic: String? = null,
        /** Bank IBAN */
        val shopBankIBAN: String? = null,
        /** Contact email address */
        val shopEmail: String? = null,
        /** Timezone identifier (e.g., "America/New_York") */
        val shopTimezone: String? = null,
        /** Official website URL */
        val shopWebsite: String? = null,
        /** Whether restaurant mode (table management, floor plans) is enabled */
        val restaurantMode: Boolean? = null,
        /** Retained for compatibility; no longer read since the kiosk screen was removed. */
        val kioskMode: Boolean? = null,
        /** URL path to the shop logo image */
        val logoUrl: String? = null,
    ) {
        /**
         * Validates the update request data.
         *
         * Ensures provided fields meet format requirements.
         * Empty fields are ignored for partial updates.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            // Validate shop name if provided
            shopName?.let { name ->
                require(name.isNotBlank()) { "Shop name cannot be blank if provided" }
            }

            // DSFinV-K Anhang E field lengths (round-7 C7) — same four fields as the create DTO.
            // A partial update must be guarded too: this is the path a shop actually renames by.
            DsfinvkFieldLimits.requireMaxLength(shopName, DsfinvkFieldLimits.NAME, "Shop name (NAME)")
            DsfinvkFieldLimits.requireMaxLength(shopAddress, DsfinvkFieldLimits.STRASSE, "Street (STRASSE)")
            DsfinvkFieldLimits.requireMaxLength(shopCity, DsfinvkFieldLimits.ORT, "City (ORT)")
            DsfinvkFieldLimits.requireMaxLength(shopTaxNumber, DsfinvkFieldLimits.STNR, "Tax number (STNR)")
            VatIdFormat.requireValid(ustIdNr, "VAT ID (USTID)")

            // Validate email format if provided
            shopEmail?.let { email ->
                require(email.contains("@") && email.contains(".")) {
                    "Invalid email format: $email"
                }
            }

            // Validate ZIP code is positive if provided
            zipCode?.let { zip ->
                require(zip > 0) { "ZIP code must be positive: $zip" }
            }

            // Validate website URL format if provided
            shopWebsite?.let { website ->
                require(website.startsWith("http://") || website.startsWith("https://")) {
                    "Website URL must start with http:// or https://"
                }
            }

            // Validate timezone format if provided
            shopTimezone?.let { timezone ->
                require(timezone.contains("/") || timezone.matches(Regex("^[A-Z]{3,4}$"))) {
                    "Invalid timezone format: $timezone"
                }
            }
        }

        /**
         * Checks if the update request contains any actual changes.
         *
         * Determines whether the request has any non-null fields
         * to avoid unnecessary database operations.
         *
         * @return true if at least one field is provided for update
         */
        fun hasChanges(): Boolean =
            shopName != null ||
                companyName != null ||
                shopAddress != null ||
                shopCity != null ||
                zipCode != null ||
                shopCountry != null ||
                shopPhone != null ||
                shopTaxNumber != null ||
                ustIdNr != null ||
                shopBankName != null ||
                shopBankBic != null ||
                shopBankIBAN != null ||
                shopEmail != null ||
                shopTimezone != null ||
                shopWebsite != null ||
                restaurantMode != null ||
                kioskMode != null ||
                logoUrl != null
    }

    @Serializable
    data class LogoUploadResponseDto(
        val logoUrl: String,
    )

    @Serializable
    data class EmployeeShopInfoDto(
        val shopId: Long,
        val shopName: String,
        val shopAddress: String,
        val shopCity: String,
    )
}
