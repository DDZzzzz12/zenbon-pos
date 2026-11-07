package org.example.project.features.promotion.database.tables

import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.order.database.tables.OrderItemTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

object BillPromotionTable : LongIdTable("bill_promotion") {
    val billId = reference("bill_id", BillTable)
    val promotionId = reference("promotion_id", PromotionTable)
    val orderItemId = reference("order_item_id", OrderItemTable).nullable()
    val deductedAmount = decimal("deducted_amount", 19, 4)
    val appliedBy = reference("applied_by", EmployeeTable)
    val appliedAt = datetime("applied_at")
}
