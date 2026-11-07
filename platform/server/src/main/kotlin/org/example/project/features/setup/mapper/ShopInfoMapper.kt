/**
 * Shop Information entity to DTO mapping functions.
 *
 * Provides conversion methods for ShopInfo entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.setup.mapper

import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.features.setup.domain.models.ShopInfo

/**
 * Converts ShopInfo entity to ShopInfoResponseDto.
 */
fun ShopInfo.toResponse(): ShopInfoDto.ShopInfoResponseDto =
    ShopInfoDto.ShopInfoResponseDto(
        shopId = this.id.value,
        shopName = this.shopName,
        companyName = this.companyName,
        shopAddress = this.shopAddress,
        shopCity = this.shopCity,
        zipCode = this.zipCode,
        shopCountry = this.shopCountry,
        shopPhone = this.shopPhone,
        shopTaxNumber = this.shopTaxNumber,
        ustIdNr = this.ustIdNr,
        shopBankName = this.shopBankName,
        shopBankBic = this.shopBankBic,
        shopBankIBAN = this.shopBankIBAN,
        shopEmail = this.shopEmail,
        shopTimezone = this.shopTimezone,
        shopWebsite = this.shopWebsite,
        restaurantMode = this.restaurantMode,
        kioskMode = this.kioskMode,
        logoUrl = this.logoUrl,
        createAt = this.createAt.toString(),
    )

fun ShopInfo.toEmployeeShopInfoResponseDto(): ShopInfoDto.EmployeeShopInfoDto =
    ShopInfoDto.EmployeeShopInfoDto(
        shopId = this.id.value,
        shopName = this.shopName,
        shopAddress = this.shopAddress,
        shopCity = this.shopCity,
    )

/**
 * Extension function to create ShopInfoResponseDto.fromEntity() functionality.
 */
fun ShopInfoDto.ShopInfoResponseDto.Companion.fromEntity(entity: ShopInfo): ShopInfoDto.ShopInfoResponseDto = entity.toResponse()
