/**
 * Shop Information Form State
 *
 * Represents the editable form data for shop information with validation logic.
 * This class manages all shop configuration fields and provides comprehensive
 * validation rules for user input.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.state

import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.domain.dto.setup.ShopInfoSyncDto
import org.example.project.core.utils.DsfinvkFieldLimits

/**
 * Form state for shop information editing.
 *
 * Contains all editable fields for shop configuration with default values.
 * Provides validation methods and conversion to/from DTOs.
 */
data class ShopInfoFormState(
    val shopName: String = "",
    val companyName: String = "",
    val shopAddress: String = "",
    val shopCity: String = "",
    val zipCode: String = "",
    val shopCountry: String = "",
    val shopPhone: String = "",
    val shopEmail: String = "",
    val shopWebsite: String = "",
    val shopTimezone: String = "",
    val shopTaxNumber: String = "",
    val shopBankName: String = "",
    val shopBankBic: String = "",
    val shopBankIBAN: String = "",
    val restaurantMode: Boolean = true,
    val logoUrl: String = "",
) {
    /**
     * Validates all form fields and returns a map of field errors.
     *
     * Validation Rules:
     * - Required fields: shopName, shopAddress, shopCity
     * - Email: Must contain "@" if provided
     * - ZIP Code: Must be positive integer if provided
     * - Website: Must start with "http://" or "https://" if provided
     * - Timezone: Must contain "/" or match timezone pattern
     *
     * @return Map of field names to error messages. Empty map if validation passes.
     */
    fun validate(): Map<String, String> {
        val errors = mutableMapOf<String, String>()

        // Required field validations
        if (shopName.isBlank()) {
            errors["shopName"] = "Shop name is required"
        }

        if (shopAddress.isBlank()) {
            errors["shopAddress"] = "Shop address is required"
        }

        if (shopCity.isBlank()) {
            errors["shopCity"] = "City is required"
        }

        // DSFinV-K Anhang E field lengths (round-7 C7). The ViewModel caps these fields as they
        // are typed, so this branch is normally unreachable from the form — it exists for a row
        // LOADED from a server that predates the limit, which would otherwise be silently
        // re-submitted and rejected with a bare 400.
        if (shopName.length > DsfinvkFieldLimits.NAME) {
            errors["shopName"] = "Shop name must not exceed ${DsfinvkFieldLimits.NAME} characters"
        }
        if (shopAddress.length > DsfinvkFieldLimits.STRASSE) {
            errors["shopAddress"] = "Address must not exceed ${DsfinvkFieldLimits.STRASSE} characters"
        }
        if (shopCity.length > DsfinvkFieldLimits.ORT) {
            errors["shopCity"] = "City must not exceed ${DsfinvkFieldLimits.ORT} characters"
        }
        if (shopTaxNumber.length > DsfinvkFieldLimits.STNR) {
            errors["shopTaxNumber"] = "Tax number must not exceed ${DsfinvkFieldLimits.STNR} characters"
        }

        // Email validation
        if (shopEmail.isNotBlank() && !shopEmail.contains("@")) {
            errors["shopEmail"] = "Invalid email format"
        }

        // ZIP code validation
        if (zipCode.isNotBlank()) {
            val zipInt = zipCode.toIntOrNull()
            if (zipInt == null || zipInt <= 0) {
                errors["zipCode"] = "ZIP code must be a positive number"
            }
        }

        // Website validation
        if (shopWebsite.isNotBlank() &&
            !shopWebsite.startsWith("http://") &&
            !shopWebsite.startsWith("https://")
        ) {
            errors["shopWebsite"] = "Website must start with http:// or https://"
        }

        // Timezone validation
        if (shopTimezone.isNotBlank() &&
            !shopTimezone.contains("/") &&
            !shopTimezone.matches(Regex("^[A-Z]{3,4}$"))
        ) {
            errors["shopTimezone"] = "Invalid timezone format (e.g., Europe/Berlin or UTC)"
        }

        return errors
    }

    companion object {
        /**
         * Creates a form state from the full shop info DTO (with financial data).
         *
         * Used when loading shop info for editing by ADMIN/OWNER users.
         * Includes all fields including sensitive financial information.
         *
         * @param dto The full shop info DTO from the API response
         * @return FormState populated with all DTO values
         */
        fun fromFullDto(dto: ShopInfoDto.ShopInfoResponseDto): ShopInfoFormState =
            ShopInfoFormState(
                shopName = dto.shopName,
                companyName = dto.companyName ?: "",
                shopAddress = dto.shopAddress ?: "",
                shopCity = dto.shopCity ?: "",
                zipCode = dto.zipCode?.toString() ?: "",
                shopCountry = dto.shopCountry ?: "",
                shopPhone = dto.shopPhone ?: "",
                shopEmail = dto.shopEmail ?: "",
                shopWebsite = dto.shopWebsite ?: "",
                shopTimezone = dto.shopTimezone ?: "",
                shopTaxNumber = dto.shopTaxNumber ?: "",
                shopBankName = dto.shopBankName ?: "",
                shopBankBic = dto.shopBankBic ?: "",
                shopBankIBAN = dto.shopBankIBAN ?: "",
                restaurantMode = dto.restaurantMode,
                logoUrl = dto.logoUrl ?: "",
            )

        /**
         * Creates a form state from a shop info sync DTO (without financial data).
         *
         * Used for public shop information display without sensitive data.
         *
         * @param dto The shop info sync DTO from the API response
         * @return FormState populated with DTO values (financial fields empty)
         */
        fun fromDto(dto: ShopInfoSyncDto.ShopInfoSyncResponseDto): ShopInfoFormState =
            ShopInfoFormState(
                shopName = dto.shopName,
                companyName = dto.companyName ?: "",
                shopAddress = dto.shopAddress ?: "",
                shopCity = dto.shopCity ?: "",
                zipCode = dto.zipCode?.toString() ?: "",
                shopCountry = dto.shopCountry ?: "",
                shopPhone = dto.shopPhone ?: "",
                shopEmail = dto.shopEmail ?: "",
                shopWebsite = dto.shopWebsite ?: "",
                shopTimezone = dto.shopTimezone ?: "",
                // Note: These fields are not in ShopInfoSyncResponseDto
                // They should come from a dedicated admin endpoint
                shopTaxNumber = "",
                shopBankName = "",
                shopBankBic = "",
                shopBankIBAN = "",
                restaurantMode = dto.restaurantMode,
                logoUrl = dto.logoUrl ?: "",
            )
    }

    /**
     * Converts the form state to an update request DTO.
     *
     * @return UpdateShopInfoRequestDto for API submission
     */
    fun toUpdateDto(): ShopInfoDto.UpdateShopInfoRequestDto =
        ShopInfoDto.UpdateShopInfoRequestDto(
            shopName = shopName,
            companyName = companyName.ifBlank { null },
            shopAddress = shopAddress,
            shopCity = shopCity,
            zipCode = zipCode.toIntOrNull() ?: 0,
            shopCountry = shopCountry,
            shopPhone = shopPhone.ifBlank { null },
            shopTaxNumber = shopTaxNumber.ifBlank { null },
            shopBankName = shopBankName.ifBlank { null },
            shopBankBic = shopBankBic.ifBlank { null },
            shopBankIBAN = shopBankIBAN.ifBlank { null },
            shopEmail = shopEmail.ifBlank { null },
            shopWebsite = shopWebsite.ifBlank { null },
            shopTimezone = shopTimezone.ifBlank { null },
            restaurantMode = restaurantMode,
            logoUrl = logoUrl.ifBlank { null },
        )
}
