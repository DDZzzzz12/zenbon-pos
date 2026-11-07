package org.example.project.features.setup.data.repository

import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.features.setup.database.tables.ShopInfoTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory

/**
 * Implementation of ShopInfoRepository using Exposed ORM.
 *
 * Provides concrete implementation of data access operations using
 * Exposed ORM for database interactions. Exceptions propagate to
 * the service layer for Result wrapping.
 */
class ShopInfoRepositoryImpl : ShopInfoRepository {
    private val logger = LoggerFactory.getLogger(ShopInfoRepositoryImpl::class.java)

    override fun findAll(): List<ShopInfo> {
        logger.debug("Finding all shop information records")
        return ShopInfo.Companion
            .all()
            .orderBy(ShopInfoTable.createAt to SortOrder.DESC)
            .toList()
    }

    override fun findById(shopId: Long): ShopInfo? {
        logger.debug("Finding shop information by shop ID: $shopId")
        return ShopInfo.Companion.findById(shopId)
    }

    override fun findByNameContaining(query: String): List<ShopInfo> {
        logger.debug("Searching shop information by name containing: $query")
        return ShopInfo.Companion
            .find {
                ShopInfoTable.shopName like "%$query%"
            }.orderBy(ShopInfoTable.shopName to SortOrder.ASC)
            .toList()
    }

    override fun findByCity(city: String): List<ShopInfo> {
        logger.debug("Finding shop information by city: $city")
        return ShopInfo.Companion
            .find {
                ShopInfoTable.shopCity eq city
            }.orderBy(ShopInfoTable.shopName to SortOrder.ASC)
            .toList()
    }

    override fun findByCountry(country: String): List<ShopInfo> {
        logger.debug("Finding shop information by country: $country")
        return ShopInfo.Companion
            .find {
                ShopInfoTable.shopCountry eq country
            }.orderBy(ShopInfoTable.shopName to SortOrder.ASC)
            .toList()
    }

    override fun create(request: ShopInfoDto.CreateShopInfoRequestDto): ShopInfo {
        request.validate()

        logger.info("Creating new shop information for shop: ${request.shopName}")

        val currentTime = currentTimeStamp()

        return ShopInfo.new {
            shopName = request.shopName
            companyName = request.companyName
            shopAddress = request.shopAddress
            shopCity = request.shopCity
            zipCode = request.zipCode
            shopCountry = request.shopCountry
            shopPhone = request.shopPhone
            shopTaxNumber = request.shopTaxNumber
            ustIdNr = request.ustIdNr
            shopBankName = request.shopBankName
            shopBankBic = request.shopBankBic
            shopBankIBAN = request.shopBankIBAN
            shopEmail = request.shopEmail
            shopTimezone = request.shopTimezone
            shopWebsite = request.shopWebsite
            createAt = currentTime
        }
    }

    override fun update(
        shopId: Long,
        request: ShopInfoDto.UpdateShopInfoRequestDto,
    ): ShopInfo? {
        request.validate()

        logger.info("Updating shop information for shop ID: $shopId")

        val entity = ShopInfo.Companion.findById(shopId) ?: return null

        // Apply updates only for non-null fields
        request.shopName?.let { entity.shopName = it }
        request.companyName?.let { entity.companyName = it }
        request.shopAddress?.let { entity.shopAddress = it }
        request.shopCity?.let { entity.shopCity = it }
        request.zipCode?.let { entity.zipCode = it }
        request.shopCountry?.let { entity.shopCountry = it }
        request.shopPhone?.let { entity.shopPhone = it }
        request.shopTaxNumber?.let { entity.shopTaxNumber = it }
        request.ustIdNr?.let { entity.ustIdNr = it }
        request.shopBankName?.let { entity.shopBankName = it }
        request.shopBankBic?.let { entity.shopBankBic = it }
        request.shopBankIBAN?.let { entity.shopBankIBAN = it }
        request.shopEmail?.let { entity.shopEmail = it }
        request.shopTimezone?.let { entity.shopTimezone = it }
        request.shopWebsite?.let { entity.shopWebsite = it }
        request.restaurantMode?.let { entity.restaurantMode = it }
        request.kioskMode?.let { entity.kioskMode = it }
        request.logoUrl?.let { entity.logoUrl = it.ifBlank { null } }

        logger.info("Updated shop information for shop ID: $shopId")
        return entity
    }

    override fun delete(shopId: Long) {
        logger.info("Deleting shop information for shop ID: $shopId")

        val entity =
            ShopInfo.Companion.findById(shopId)
                ?: throw IllegalArgumentException("Shop not found: $shopId")

        entity.delete()
        logger.info("Deleted shop information for shop ID: $shopId")
    }

    override suspend fun exists(shopId: Long): Boolean {
        logger.debug("Checking existence of shop information for shop ID: $shopId")
        return ShopInfo.Companion.findById(shopId) != null
    }

    override suspend fun count(): Long {
        logger.debug("Counting total shop information records")
        return ShopInfoTable.selectAll().count()
    }
}
