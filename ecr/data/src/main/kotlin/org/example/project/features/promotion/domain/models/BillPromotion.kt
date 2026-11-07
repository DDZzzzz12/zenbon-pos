package org.example.project.features.promotion.domain.models

import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class BillPromotion(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<BillPromotion>(BillPromotionTable)

    var bill by Bill referencedOn BillPromotionTable.billId
    var promotion by Promotion referencedOn BillPromotionTable.promotionId
    var orderItem by OrderItem optionalReferencedOn BillPromotionTable.orderItemId
    var deductedAmount by BillPromotionTable.deductedAmount
    var appliedBy by Employee referencedOn BillPromotionTable.appliedBy
    var appliedAt by BillPromotionTable.appliedAt
}
