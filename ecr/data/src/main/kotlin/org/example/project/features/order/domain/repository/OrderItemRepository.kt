package org.example.project.features.order.domain.repository

import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.domain.models.TableSession
import java.math.BigDecimal

interface OrderItemRepository {
    fun createOrderItem(
        order: Orders,
        session: TableSession,
        product: Product,
        unitPrice: BigDecimal,
        finalPrice: BigDecimal,
        itemStatus: ItemStatus,
        vat: Vat,
        discount: BigDecimal? = BigDecimal.ONE,
        customPrice: Boolean = false,
        takeAway: Boolean = false,
        transferred: Boolean = false,
        sideNotes: String?,
    ): OrderItem
}
