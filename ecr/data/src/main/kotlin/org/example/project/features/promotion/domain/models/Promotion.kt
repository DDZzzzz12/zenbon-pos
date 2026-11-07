package org.example.project.features.promotion.domain.models

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.product.domain.models.Product
import org.example.project.features.promotion.database.tables.PromotionTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class Promotion(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Promotion>(PromotionTable)

    var name by PromotionTable.name
    var description by PromotionTable.description
    var discountType by PromotionTable.discountType
    var discountValue by PromotionTable.discountValue
    var scope by PromotionTable.scope
    var targetProduct by Product optionalReferencedOn PromotionTable.targetProductId

    /**
     * Whether **this same** promotion may be applied more than once within one bill. Contrast
     * [combinable], which is about OTHER promotions.
     */
    var isMultiUse by PromotionTable.isMultiUse

    /**
     * Whether this promotion may share a bill with **other** promotions. Contrast [isMultiUse],
     * which is about repeating THIS promotion. `BillServiceImpl` rejects a bill that offers a
     * non-combinable promotion together with a second, distinct one.
     */
    var combinable by PromotionTable.combinable
    var validFrom by PromotionTable.validFrom
    var validUntil by PromotionTable.validUntil
    var isActive by PromotionTable.isActive
    var createdBy by Employee referencedOn PromotionTable.createdBy
    var createdAt by PromotionTable.createdAt
}
