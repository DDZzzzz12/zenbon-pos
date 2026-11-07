package org.example.project.features.promotion.database.tables

import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.product.database.tables.ProductTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

object PromotionTable : LongIdTable("promotion") {
    val name = varchar("name", 200)
    val description = varchar("description", 1000).nullable()
    val discountType = enumerationByName<DiscountType>("discount_type", 20)
    val discountValue = decimal("discount_value", 19, 4)
    val scope = enumerationByName<PromotionScope>("scope", 20)
    val targetProductId = reference("target_product_id", ProductTable).nullable()

    /**
     * Whether **this same** promotion may be applied more than once within one bill — e.g. an
     * ITEM_SPECIFIC promotion granted separately to each qualifying item. Nothing to do with
     * [combinable].
     */
    val isMultiUse = bool("is_multi_use").default(false)

    /**
     * Whether this promotion may share a bill with **other** promotions
     * ("Nicht mit anderen Aktionen kombinierbar" when false). Nothing to do with [isMultiUse]:
     * a non-combinable promotion may still be multi-use, it just has to be the only promotion on
     * the bill. Defaults to `true` so every pre-existing promotion keeps stacking as before.
     */
    val combinable = bool("combinable").default(true)
    val validFrom = datetime("valid_from")
    val validUntil = datetime("valid_until").nullable()
    val isActive = bool("is_active").default(true)
    val createdBy = reference("created_by", EmployeeTable)
    val createdAt = datetime("created_at")
}
