package org.example.project.features.setup.domain.service

import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException

/**
 * Port consulted by [ShopInfoService] before a shop-info update is applied.
 *
 * An implementation may refuse the edit by throwing [PosException] and may perform pre-edit side
 * effects that must see the still-unchanged [current] row (for example freezing the old master
 * data onto records that depend on it). It runs inside the caller's `dbQuery` transaction,
 * after request validation and before the row is updated, so a refusal leaves no partial effect
 * and its writes commit or roll back together with the update.
 */
interface ShopIdentityChangeGuard {
    fun beforeUpdate(
        current: ShopInfo,
        request: ShopInfoDto.UpdateShopInfoRequestDto,
    )
}
