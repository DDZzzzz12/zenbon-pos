package org.example.project.features.setup.domain.service

import org.example.project.core.domain.dto.setup.ShopInfoDto

/**
 * Service interface for shop information business operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 4.
 */
interface ShopInfoService {
    /** Retrieves all shop information records. */
    suspend fun getAllShopInfo(): List<ShopInfoDto.ShopInfoResponseDto>

    /** Retrieves shop information by shop ID; returns null if not found. */
    suspend fun getShopInfoById(shopId: Long): ShopInfoDto.ShopInfoResponseDto?

    /** Searches shops by name containing the given query string. */
    suspend fun searchShopInfoByName(query: String): List<ShopInfoDto.ShopInfoResponseDto>

    /** Retrieves shops located in a specific city. */
    suspend fun getShopInfoByCity(city: String): List<ShopInfoDto.ShopInfoResponseDto>

    /** Retrieves shops located in a specific country. */
    suspend fun getShopInfoByCountry(country: String): List<ShopInfoDto.ShopInfoResponseDto>

    /** Creates a new shop information record. Throws PosException on failure. */
    suspend fun createShopInfo(
        employeeId: String,
        request: ShopInfoDto.CreateShopInfoRequestDto,
    ): ShopInfoDto.ShopInfoResponseDto

    /** Updates an existing shop information record. Throws PosException on failure. */
    suspend fun updateShopInfo(
        employeeId: String,
        shopId: Long,
        request: ShopInfoDto.UpdateShopInfoRequestDto,
    ): ShopInfoDto.ShopInfoResponseDto

    /** Deletes a shop information record by ID; returns the deleted shop name. */
    suspend fun deleteShopInfo(
        employeeId: String,
        shopId: Long,
    ): String

    /** Checks if a shop information record exists for the given shop ID. */
    suspend fun shopInfoExists(shopId: Long): Boolean

    /** Returns true if at least one shop information record exists in the system. */
    suspend fun hasShopInfo(): Boolean
}
