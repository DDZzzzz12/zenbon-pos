package org.example.project.core.domain.dto.setup

import kotlinx.serialization.Serializable

/**
 * Container object for ShopInfo synchronization DTOs.
 *
 * Provides lightweight shop information for client-side caching
 * WITHOUT sensitive data like tax numbers, bank information, etc.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
object ShopInfoSyncDto {
    /**
     * Sync DTO for shop information (public data only).
     *
     * Excludes sensitive financial information such as:
     * - Tax numbers
     * - Bank account details (IBAN, BIC, bank name)
     *
     * Used for client-side caching and display purposes.
     */
    @Serializable
    data class ShopInfoSyncResponseDto(
        val shopId: Long,
        val shopName: String,
        val companyName: String?,
        val shopAddress: String?,
        val shopCity: String?,
        val zipCode: Int?,
        val shopCountry: String?,
        val shopPhone: String?,
        val shopEmail: String?,
        val shopTimezone: String?,
        val shopWebsite: String?,
        val restaurantMode: Boolean = true,
        val kioskMode: Boolean = false,
        val logoUrl: String? = null,
    )
}
