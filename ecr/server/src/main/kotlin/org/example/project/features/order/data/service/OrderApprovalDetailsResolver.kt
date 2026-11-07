package org.example.project.features.order.data.service

import org.example.project.features.auth.domain.service.ApprovalDetailsResolver
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.RTable
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

/**
 * Resolves `itemIds` to "productNumber-name" (key `items`) and `targetTableId` to the table number
 * (key `targetTable`). A failed lookup is logged and leaves the details as far as they got.
 */
class OrderApprovalDetailsResolver : ApprovalDetailsResolver {
    private val logger = LoggerFactory.getLogger(OrderApprovalDetailsResolver::class.java)

    override suspend fun resolve(details: Map<String, String>): Map<String, String> {
        val resolved = details.toMutableMap()
        try {
            // Resolve item IDs to product names
            val itemIdsStr = details["itemIds"]
            if (!itemIdsStr.isNullOrBlank()) {
                val itemIds = itemIdsStr.split(",").mapNotNull { it.trim().toLongOrNull() }
                if (itemIds.isNotEmpty()) {
                    val productNames =
                        dbQuery {
                            itemIds.mapNotNull { id ->
                                OrderItem.findById(id)?.let { item ->
                                    "${item.product.productNumber}-${item.product.name}"
                                }
                            }
                        }
                    resolved["items"] = productNames.joinToString(", ")
                }
                resolved.remove("itemIds")
            }

            // Resolve target table ID to table number
            val targetTableIdStr = details["targetTableId"]
            if (!targetTableIdStr.isNullOrBlank()) {
                val targetTableId = targetTableIdStr.toLongOrNull()
                if (targetTableId != null) {
                    val tableNumber =
                        dbQuery {
                            RTable.findById(targetTableId)?.tableNumber
                        }
                    if (tableNumber != null) {
                        resolved["targetTable"] = tableNumber
                    }
                }
                resolved.remove("targetTableId")
            }
        } catch (e: Exception) {
            logger.warn("Failed to resolve approval request details: ${e.message}")
        }
        return resolved
    }
}
