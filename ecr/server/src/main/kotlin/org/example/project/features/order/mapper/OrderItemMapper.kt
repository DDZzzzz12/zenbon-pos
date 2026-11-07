/**
 * OrderItem entity to DTO mapping functions.
 *
 * Provides conversion methods for OrderItem entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.order.mapper

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.utils.formatter.formatDecimal
import org.example.project.utils.formatter.toFormattedDouble
import java.math.BigDecimal

/**
 * Converts OrderItem entity to OrderItemResponseDto.
 */
fun OrderItem.toOrderItemResponseDto(): OrderItemDto.OrderItemResponseDto =
    OrderItemDto.OrderItemResponseDto(
        orderItemIds = listOf(this.id.value),
        ordersId = this.order?.let { setOf(it.id.value) } ?: emptySet(),
        product =
            ProductDto.OrderItemProductDto(
                productId = this.product.id.value,
                productNumber = this.product.productNumber,
                productName = this.product.name,
                productType = this.product.productType.name,
            ),
        unitPrice = formatDecimal(this.unitPrice),
        finalPrice = formatDecimal(this.finalPrice),
        // Per-unit CHARGED gross (finalPrice) un-rounded so the Bestellung-V1 signer truncates
        // it to 2dp (TSER-6); NOT formatDecimal (HALF_UP) which would pre-round it.
        chargedUnitPrice = this.finalPrice.toPlainString(),
        itemStatus = this.itemStatus.name,
        vatClass = this.vat.id.value,
        vatRate = formatDecimal(this.vat.vatRate),
        discount = toFormattedDouble(this.discount),
        customPrice = this.customPrice,
        takeAway = this.takeAway,
        transferred = this.transferred,
        sideNotes = this.sideNotes,
        extras =
            this.itemExtras
                .map { ie ->
                    OrderItemDto.SelectedOptionDto(
                        optionId = ie.productExtras?.id?.value,
                        name = ie.optionNameSnapshot,
                        priceDelta = ie.priceDeltaSnapshot.toPlainString(),
                        groupName = ie.groupNameSnapshot,
                        groupId = ie.groupIdSnapshot?.value,
                        quantity = ie.quantity,
                    )
                }.ifEmpty { null },
        quantity = 1, // Single item has quantity 1
    )

/**
 * Converts a list of OrderItem entities to grouped OrderItemResponseDto list.
 * Groups items by identical properties (except ID) and calculates quantity.
 */
fun List<OrderItem>.toGroupedOrderItemResponseDto(): List<OrderItemDto.OrderItemResponseDto> =
    this
        .groupBy { item ->
            // Create a grouping key based on all properties except ID
            GroupingKey(
                productId = item.product.id.value,
                unitPrice = item.unitPrice,
                // Key on the CHARGED per-unit price (finalPrice = base+extras after discount) so two
                // items with the same base/extras but different discount never collapse into one line
                // and get signed with a single representative's charged price (audit TSER-6).
                finalPrice = item.finalPrice,
                itemStatus = item.itemStatus.name,
                vatClass = item.vat.vatClass,
                vatRate = item.vat.vatRate,
                customPrice = item.customPrice,
                takeAway = item.takeAway,
                transferred = item.transferred,
                sideNotes = item.sideNotes,
                extras =
                    item.itemExtras.map { ie ->
                        // groupId is part of the identity, not decoration: the same option in two groups
                        // carries two different deltas, so two lines that agree on everything else must
                        // still not collapse into one representative.
                        "${ie.productExtras?.id?.value}|${ie.optionNameSnapshot}|${ie.priceDeltaSnapshot.toPlainString()}|${ie.groupNameSnapshot}|${ie.groupIdSnapshot?.value}|${ie.quantity}"
                    },
            )
        }.map { (groupingKey, groupedItems) ->
            // Take the first item as representative for the group
            val representative = groupedItems.first()
            val product = representative.product

            OrderItemDto.OrderItemResponseDto(
                orderItemIds = groupedItems.map { it.id.value },
                ordersId = groupedItems.mapNotNull { it.order?.id?.value }.toSet(),
                product =
                    ProductDto.OrderItemProductDto(
                        productId = representative.product.id.value,
                        productNumber = representative.product.productNumber,
                        productName = product.name,
                        en = product.enName,
                        ch = product.chName,
                        productType = representative.product.productType.name,
                    ),
                unitPrice = formatDecimal(representative.unitPrice),
                finalPrice =
                    formatDecimal(
                        representative.finalPrice
                            .multiply(groupedItems.size.toBigDecimal()),
                    ),
                // Per-unit CHARGED gross for the Bestellung-V1 signer (TSER-6): the representative's
                // finalPrice (single unit), un-rounded so the signer truncates it to 2dp. Distinct
                // from the LINE-TOTAL `finalPrice` above (×quantity). GroupingKey now keys on
                // finalPrice, so every item in a group shares this exact charged price.
                chargedUnitPrice = representative.finalPrice.toPlainString(),
                itemStatus = representative.itemStatus.name,
                vatClass = representative.vat.id.value,
                vatRate = formatDecimal(representative.vat.vatRate),
                discount = toFormattedDouble(representative.discount),
                customPrice = representative.customPrice,
                takeAway = representative.takeAway,
                transferred = representative.transferred,
                sideNotes = representative.sideNotes,
                extras =
                    representative.itemExtras
                        .map { ie ->
                            OrderItemDto.SelectedOptionDto(
                                optionId = ie.productExtras?.id?.value,
                                name = ie.optionNameSnapshot,
                                priceDelta = ie.priceDeltaSnapshot.toPlainString(),
                                groupName = ie.groupNameSnapshot,
                                groupId = ie.groupIdSnapshot?.value,
                                quantity = ie.quantity,
                            )
                        }.ifEmpty { null },
                quantity = groupedItems.size, // Number of items in this group
            )
        }

fun List<OrderItemDto.OrderItemResponseDto>.negativeItemQuantity(): List<OrderItemDto.OrderItemResponseDto> =
    this.map {
        it.copy(
            quantity = -it.quantity,
        )
    }

/**
 * Data class for grouping OrderItems by their properties (excluding ID).
 */
private data class GroupingKey(
    val productId: Long,
    val unitPrice: BigDecimal,
    val finalPrice: BigDecimal,
    val itemStatus: String,
    val vatClass: String,
    val vatRate: BigDecimal,
    val customPrice: Boolean,
    val takeAway: Boolean,
    val transferred: Boolean,
    val sideNotes: String?,
    val extras: List<String>?,
)
