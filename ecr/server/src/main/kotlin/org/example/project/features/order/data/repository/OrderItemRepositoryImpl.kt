package org.example.project.features.order.data.repository

import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.order.domain.repository.OrderItemRepository
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.domain.models.TableSession
import java.math.BigDecimal

class OrderItemRepositoryImpl : OrderItemRepository {
    override fun createOrderItem(
        order: Orders,
        session: TableSession,
        product: Product,
        unitPrice: BigDecimal,
        finalPrice: BigDecimal,
        itemStatus: ItemStatus,
        vat: Vat,
        discount: BigDecimal?,
        customPrice: Boolean,
        takeAway: Boolean,
        transferred: Boolean,
        sideNotes: String?,
    ): OrderItem =
        OrderItem.new {
            this.order = order
            this.session = session
            this.product = product
            this.unitPrice = unitPrice
            this.finalPrice = finalPrice
            this.itemStatus = ItemStatus.OPEN
            this.vat = vat
            this.discount = discount ?: BigDecimal.ONE
            this.customPrice = customPrice
            this.takeAway = takeAway
            this.transferred = transferred
            this.sideNotes = sideNotes
        }
}
