package org.example.project.features.order.domain.service

import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderItemDto

/**
 * Service interface for managing order item operations such as cancellations and transfers.
 *
 * This service handles complex order item operations that require business logic validation,
 * TSE (Technical Security Element) transaction recording, and coordination between multiple
 * repository layers.
 *
 * All operations are performed within database transactions to ensure data consistency.
 * Errors are surfaced by throwing [org.example.project.infrastructure.errors.PosException];
 * the global StatusPages handler converts them to RFC 7807 problem+json responses.
 */
interface OrderItemService {
    suspend fun getItemForTableSession(
        sessionId: Long,
        itemStatus: String?,
    ): List<OrderItemDto.OrderItemResponseDto>

    /**
     * Cancels a list of order items and records the cancellation in the TSE system.
     *
     * Steps:
     * 1. Validates that the employee exists and has permission to cancel items
     * 2. Validates that all specified order items exist and are cancellable
     * 3. Creates TSE transaction entry for regulatory compliance
     * 4. Records cancellation entries in the database with employee and reason
     * 5. Updates order item status to CANCELLED
     *
     * The DB phase is atomic; if TSE signing fails in FAIL mode, the cancellation
     * is reverted in a fresh transaction.
     */
    suspend fun cancelOrderItems(
        item: List<Long>,
        employeeId: String,
        deviceId: String,
        reason: String? = null,
        sourceTableId: Long,
    ): OrderItemDto.OrderItemOperationResultDto

    /**
     * Transfers a list of order items from their current table to a different target table.
     *
     * Steps:
     * 1. Validates employee + permissions
     * 2. Validates all items exist and belong to the same source table
     * 3. Validates target table exists and is different from source
     * 4. Creates or retrieves an active table session for the target table
     * 5. Records transfer entries
     * 6. Updates order items to point to the new table session
     */
    suspend fun transferOrderItems(
        item: List<Long>,
        employeeId: String,
        deviceId: String,
        targetTableId: Long,
        currentTableId: Long,
        reason: String? = null,
        /**
         * Who asked for the transfer, when [employeeId] is a manager lent to that device (a station or
         * till manager sign-in): the owner of a session the transfer OPENS follows this employee's
         * rules, never the approving manager's. Defaults to [employeeId].
         */
        ownerBasisEmployeeId: String = employeeId,
    ): OrderItemDto.OrderItemOperationResultDto

    /**
     * Retrieves all cancellations for a given time range.
     */
    suspend fun getCancellations(
        startDate: String,
        endDate: String,
    ): List<OrderItemDto.CancellationResponseDto>

    /**
     * Retrieves the transfer history for a given table session.
     */
    suspend fun getTransferHistory(tableSessionId: Long): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto>

    /**
     * Retrieves all transfers within a date range (excludes training sessions).
     */
    suspend fun getTransfers(
        startDate: String,
        endDate: String,
    ): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto>
}
