/**
 * Client-side service interface for order operations.
 *
 * Provides methods for managing orders from the client application,
 * including submitting new orders, retrieving order items, and
 * performing item management operations (cancel/transfer).
 *
 * All methods throw PosApiException/NetworkApiException on failure;
 * callers wrap in runCatchingApi.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.domain.service

import org.example.project.core.data.models.Items
import org.example.project.core.data.remote.ApprovalOutcome
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto

interface ClientOrderService {
    /**
     * Submit an order in ONE request.
     *
     * @param sessionId the table's open session, or null when the table is free — the order then
     *   opens it on the server, in the same transaction.
     * @param items the order items to submit
     * @param tableId owning table id; with it the server resolves (or opens) the session itself
     * @param forEmployeeNumber who owns the session when this order opens the table
     * @param expectedNextEmployeeId the rotation's next employee as the till showed it when the free table
     *   was opened (captured then, never read at submit); null when nothing was shown or the table already
     *   has a session. Not sent when [forEmployeeNumber] names the owner.
     */
    suspend fun submitOrder(
        sessionId: Long?,
        items: List<Items>,
        tableId: Long? = null,
        forEmployeeNumber: String? = null,
        expectedNextEmployeeId: String?,
    ): OrderDto.OrderResponseDto?

    suspend fun getOrderItemsForTable(tableId: Long): List<OrderItemDto.OrderItemResponseDto>

    /** @return whether the items were cancelled, or an approval request was raised instead. */
    suspend fun cancelOrderItems(
        itemIds: List<Long>,
        reason: String? = null,
        sourceTableId: Long,
    ): ApprovalOutcome

    /** @return whether the items were transferred, or an approval request was raised instead. */
    suspend fun transferOrderItems(
        itemIds: List<Long>,
        targetTableId: Long,
        sourceTableId: Long,
        reason: String,
    ): ApprovalOutcome
}
