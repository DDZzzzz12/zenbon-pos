package org.example.project.features.promotion.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.product.domain.models.Product
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.promotion.domain.repository.PromotionRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.toBigDecimal
import org.jetbrains.exposed.v1.core.*
import java.math.BigDecimal
import java.util.UUID

class PromotionRepositoryImpl : PromotionRepository {
    override fun getById(id: Long): Promotion? = Promotion.findById(id)

    override fun getAll(): List<Promotion> = Promotion.all().toList()

    override fun getActiveAndValid(): List<Promotion> {
        val now = currentTimeStamp()
        return Promotion
            .find {
                (PromotionTable.isActive eq true) and
                    (PromotionTable.validFrom lessEq now) and
                    ((PromotionTable.validUntil.isNull()) or (PromotionTable.validUntil greaterEq now))
            }.toList()
    }

    override fun create(
        name: String,
        description: String?,
        discountType: String,
        discountValue: String,
        scope: String,
        targetProductId: Long?,
        isMultiUse: Boolean,
        combinable: Boolean,
        validFrom: String,
        validUntil: String?,
        createdByEmployeeId: String,
    ): Promotion {
        val employee =
            Employee.findById(UUID.fromString(createdByEmployeeId))
                ?: throw NoSuchElementException("Employee not found: $createdByEmployeeId")

        return Promotion.new {
            this.name = name
            this.description = description
            this.discountType = DiscountType.valueOf(discountType)
            this.discountValue = toBigDecimal(discountValue)
            this.scope = PromotionScope.valueOf(scope)
            this.targetProduct =
                targetProductId?.let {
                    Product.findById(it) ?: throw NoSuchElementException("Product not found: $it")
                }
            this.isMultiUse = isMultiUse
            this.combinable = combinable
            this.validFrom = LocalDateTime.parse(validFrom)
            this.validUntil = validUntil?.let { LocalDateTime.parse(it) }
            this.createdBy = employee
            this.createdAt = currentTimeStamp()
            this.isActive = true
        }
    }

    override fun update(
        id: Long,
        block: Promotion.() -> Unit,
    ): Promotion? {
        val promotion = Promotion.findById(id) ?: return null
        block(promotion)
        return promotion
    }

    override fun isRedeemed(promotionId: Long): Boolean =
        BillPromotion
            .find { BillPromotionTable.promotionId eq promotionId }
            .any { billPromotion ->
                org.example.project.features.bill.domain.models.Bill
                    .find {
                        BillTable.deletedBillId eq billPromotion.bill.id
                    }.empty()
            }

    override fun getUsageCount(promotionId: Long): Long =
        BillPromotion
            .find { BillPromotionTable.promotionId eq promotionId }
            .count()

    override fun getTotalDiscountGiven(promotionId: Long): BigDecimal =
        BillPromotion
            .find { BillPromotionTable.promotionId eq promotionId }
            .sumOf { it.deductedAmount }

    override fun getBillPromotionsByBillId(billId: Long): List<BillPromotion> =
        BillPromotion
            .find {
                BillPromotionTable.billId eq billId
            }.toList()
}
