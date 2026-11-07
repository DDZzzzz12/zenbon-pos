/**
 * Implementation of ShopInfoService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 4.
 */
package org.example.project.features.setup.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationService
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.features.setup.domain.service.ShopIdentityChangeGuard
import org.example.project.features.setup.domain.service.ShopInfoService
import org.example.project.features.setup.mapper.fromEntity
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.slf4j.LoggerFactory

/**
 * Concrete implementation of business operations including validation,
 * data transformation, and coordination with the repository layer.
 */
class ShopInfoServiceImpl(
    private val repository: ShopInfoRepository,
    private val identityGuard: ShopIdentityChangeGuard,
) : ShopInfoService {
    private val logger = LoggerFactory.getLogger(ShopInfoServiceImpl::class.java)

    override suspend fun getAllShopInfo(): List<ShopInfoDto.ShopInfoResponseDto> =
        dbQuery {
            val shops = repository.findAll()
            logger.info("getAllShopInfo: retrieved {} records", shops.size)
            shops.map { ShopInfoDto.ShopInfoResponseDto.fromEntity(it) }
        }

    override suspend fun getShopInfoById(shopId: Long): ShopInfoDto.ShopInfoResponseDto? {
        if (shopId <= 0) throw validationFailed("Shop ID must be positive")
        return dbQuery {
            repository.findById(shopId)?.let { ShopInfoDto.ShopInfoResponseDto.fromEntity(it) }
        }
    }

    override suspend fun searchShopInfoByName(query: String): List<ShopInfoDto.ShopInfoResponseDto> {
        if (query.isBlank()) throw validationFailed("Search query cannot be blank")
        if (query.length < 2) throw validationFailed("Search query must be at least 2 characters long")
        return dbQuery {
            repository.findByNameContaining(query.trim()).map {
                ShopInfoDto.ShopInfoResponseDto.fromEntity(it)
            }
        }
    }

    override suspend fun getShopInfoByCity(city: String): List<ShopInfoDto.ShopInfoResponseDto> {
        if (city.isBlank()) throw validationFailed("City name cannot be blank")
        return dbQuery {
            repository.findByCity(city.trim()).map { ShopInfoDto.ShopInfoResponseDto.fromEntity(it) }
        }
    }

    override suspend fun getShopInfoByCountry(country: String): List<ShopInfoDto.ShopInfoResponseDto> {
        if (country.isBlank()) throw validationFailed("Country name cannot be blank")
        return dbQuery {
            repository.findByCountry(country.trim()).map { ShopInfoDto.ShopInfoResponseDto.fromEntity(it) }
        }
    }

    override suspend fun createShopInfo(
        employeeId: String,
        request: ShopInfoDto.CreateShopInfoRequestDto,
    ): ShopInfoDto.ShopInfoResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createShopInfo(employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.SHOP_INFO,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.shopId.toString(), dto.shopName, "Created shop info '${dto.shopName}'")
                    },
                ),
        ) {
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw shopInfoInvalid(e.message ?: "Invalid request")
                }

                // Check for duplicate shop names in the same city
                request.shopCity?.let { city ->
                    val existingShops = repository.findByCity(city)
                    val duplicateName =
                        existingShops.any {
                            it.shopName.equals(request.shopName, ignoreCase = true)
                        }
                    if (duplicateName) {
                        throw shopNameAlreadyTaken(request.shopName, city)
                    }
                }

                val entity = repository.create(request)
                val response = ShopInfoDto.ShopInfoResponseDto.fromEntity(entity)
                logger.info("createShopInfo: created shopId={}", response.shopId)
                NotificationService.notifyDataChange(
                    ChangeEntityType.SHOP_INFO,
                    DataChangeOperation.CREATED,
                    response.shopId,
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }

    override suspend fun updateShopInfo(
        employeeId: String,
        shopId: Long,
        request: ShopInfoDto.UpdateShopInfoRequestDto,
    ): ShopInfoDto.ShopInfoResponseDto =
        ExceptionConverter.catchingThrows(
            context = "updateShopInfo(shopId=$shopId, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.SHOP_INFO,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.shopId.toString(), dto.shopName, "Updated shop info '${dto.shopName}'")
                    },
                ),
        ) {
            dbQuery {
                if (shopId <= 0) throw validationFailed("Shop ID must be positive")

                // Validate that there are changes to apply
                if (!request.hasChanges()) {
                    logger.warn("updateShopInfo: no changes for shopId={}", shopId)
                    val current = repository.findById(shopId) ?: throw shopInfoNotFound(shopId)
                    return@dbQuery ShopInfoDto.ShopInfoResponseDto.fromEntity(current)
                }

                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw shopInfoInvalid(e.message ?: "Invalid request")
                }

                // DSFinV-K closing duty and freeze-on-edit (KABS-2, round-6 C5) live behind the
                // guard port; it runs here, in this transaction, before anything is written.
                val current = repository.findById(shopId) ?: throw shopInfoNotFound(shopId)
                identityGuard.beforeUpdate(current, request)

                // Check for duplicate shop names in the same city if updating name or city
                if (request.shopName != null || request.shopCity != null) {
                    val existing = repository.findById(shopId) ?: throw shopInfoNotFound(shopId)

                    val newName = request.shopName ?: existing.shopName
                    val newCity = request.shopCity ?: existing.shopCity

                    val existingShops = repository.findByCity(newCity)
                    val otherShops = existingShops.filter { it.id.value != shopId }
                    val duplicateName = otherShops.any { it.shopName.equals(newName, ignoreCase = true) }
                    if (duplicateName) {
                        throw shopNameAlreadyTaken(newName, newCity)
                    }
                }

                val entity = repository.update(shopId, request) ?: throw shopInfoNotFound(shopId)
                val response = ShopInfoDto.ShopInfoResponseDto.fromEntity(entity)
                logger.info("updateShopInfo: updated shopId={}", shopId)
                NotificationService.notifyDataChange(
                    ChangeEntityType.SHOP_INFO,
                    DataChangeOperation.UPDATED,
                    shopId,
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }

    override suspend fun deleteShopInfo(
        employeeId: String,
        shopId: Long,
    ): String =
        ExceptionConverter.catchingThrows(
            context = "deleteShopInfo(shopId=$shopId, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.SHOP_INFO,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { shopName ->
                        EntryLogData(shopId.toString(), shopName, "Deleted shop info '$shopName'")
                    },
                ),
        ) {
            dbQuery {
                if (shopId <= 0) throw validationFailed("Shop ID must be positive")

                val shop = repository.findById(shopId) ?: throw shopInfoNotFound(shopId)
                val shopName = shop.shopName

                repository.delete(shopId)

                logger.info("deleteShopInfo: deleted shopId={}", shopId)
                NotificationService.notifyDataChange(
                    ChangeEntityType.SHOP_INFO,
                    DataChangeOperation.DELETED,
                    shopId,
                    excludeEmployeeId = employeeId,
                )
                shopName
            }
        }

    override suspend fun shopInfoExists(shopId: Long): Boolean {
        if (shopId <= 0) throw validationFailed("Shop ID must be positive")
        return dbQuery { repository.exists(shopId) }
    }

    override suspend fun hasShopInfo(): Boolean =
        dbQuery {
            repository.count() > 0
        }

    // --- PosException helpers ---

    private fun shopInfoNotFound(id: Long) =
        PosException(
            PosErrorCode.SHOP_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Shop with id=$id not found",
        )

    private fun shopNameAlreadyTaken(
        name: String,
        city: String,
    ) = PosException(
        PosErrorCode.SHOP_NAME_ALREADY_TAKEN,
        HttpStatusCode.Conflict,
        "Shop name '$name' already taken in city '$city'",
    )

    private fun shopInfoInvalid(msg: String) =
        PosException(
            PosErrorCode.SHOP_INFO_INVALID,
            HttpStatusCode.BadRequest,
            msg,
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
