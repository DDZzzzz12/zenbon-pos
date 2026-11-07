package org.example.project.features.setup.domain.repository

import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.features.setup.domain.models.ShopInfo

/**
 * Repository interface for shop information data access operations.
 *
 * Defines the contract for shop information database operations
 * to enable testing and provide abstraction over implementation details.
 *
 * Returns plain entities or nullable types; exceptions propagate naturally
 * to the service layer.
 */
interface ShopInfoRepository {
    /**
     * Retrieves all shop information records from the database.
     *
     * @return List of all ShopInfo entities, ordered by creation date
     */
    fun findAll(): List<ShopInfo>

    /**
     * Finds a shop information record by its unique shop ID.
     *
     * @param shopId Unique identifier of the shop
     * @return ShopInfo entity or null if not found
     */
    fun findById(shopId: Long): ShopInfo?

    /**
     * Searches for shops by name containing the given query string.
     *
     * @param query Search term to match against shop names (case-insensitive)
     * @return List of matching ShopInfo entities
     */
    fun findByNameContaining(query: String): List<ShopInfo>

    /**
     * Finds shops located in a specific city.
     *
     * @param city City name to search for
     * @return List of ShopInfo entities in the specified city
     */
    fun findByCity(city: String): List<ShopInfo>

    /**
     * Finds shops located in a specific country.
     *
     * @param country Country name to search for
     * @return List of ShopInfo entities in the specified country
     */
    fun findByCountry(country: String): List<ShopInfo>

    /**
     * Creates a new shop information record.
     *
     * @param request DTO containing the data for the new record
     * @return Created ShopInfo entity
     */
    fun create(request: ShopInfoDto.CreateShopInfoRequestDto): ShopInfo

    /**
     * Updates an existing shop information record.
     *
     * @param shopId ID of the shop to update
     * @param request DTO containing the updated data
     * @return Updated ShopInfo entity or null if not found
     */
    fun update(
        shopId: Long,
        request: ShopInfoDto.UpdateShopInfoRequestDto,
    ): ShopInfo?

    /**
     * Deletes a shop information record by shop ID.
     *
     * @param shopId ID of the shop to delete
     * @throws IllegalArgumentException if shop not found
     */
    fun delete(shopId: Long)

    /**
     * Checks if a shop information record exists for the given shop ID.
     *
     * @param shopId ID of the shop to check
     * @return true if the record exists, false otherwise
     */
    suspend fun exists(shopId: Long): Boolean

    /**
     * Counts the total number of shop information records.
     *
     * @return Total count of records in the database
     */
    suspend fun count(): Long
}
