package org.example.project.features.dashboard.presentation.state

import org.example.project.core.data.models.RTable
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.presentation.error.UiError
import org.example.project.money.Decimal

/**
 * Tab selection for table management screen.
 */
enum class TableTab {
    DETAILS,
    ORDERS,
    BILLS,
}

sealed interface TimelineEntry {
    val timestamp: String

    data class OrderEntry(
        val order: OrderDto.OrderResponseDto,
    ) : TimelineEntry {
        override val timestamp: String = order.orderTime
    }

    data class TransferEntry(
        val transfer: ItemTransferHistoryDto.ItemTransferHistoryResponseDto,
    ) : TimelineEntry {
        override val timestamp: String = transfer.transferTime
    }
}

/**
 * UI state for Table Order History screen with integrated table details.
 *
 * Manages the state of displaying table details, order history, and bills for a specific table,
 * including loading, error, data display states, and multi-select functionality.
 *
 * @property isLoading Whether order history data is being loaded
 * @property orders List of orders for the table session
 * @property bills List of bills for the table session
 * @property isLoadingBills Whether bills data is being loaded
 * @property error Error message if loading failed
 * @property tableId The table ID being viewed
 * @property tableNumber Display table number
 * @property selectedOrder Currently selected order for detail view (optional)
 * @property activeTab Currently active tab view
 * @property tableInfo Table information for details view
 * @property orderItems List of current order items with selection state
 * @property selectedItemIds Set of currently selected item IDs
 * @property summary Calculated order summary (total, tax, count)
 * @property showCancelDialog Whether cancel confirmation dialog is visible
 * @property showTransferDialog Whether transfer dialog is visible
 * @property isSelectionMode Whether multi-select mode is active
 * @property availableTables List of tables available for transfer
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
data class TableOrderHistoryUiState(
    val isLoading: Boolean = false,
    val orders: List<OrderDto.OrderResponseDto> = emptyList(),
    val bills: List<BillDto.BillResponseDto> = emptyList(),
    val isLoadingBills: Boolean = false,
    val error: UiError? = null,
    val successMessage: String? = null,
    val tableId: Long = 0,
    val tableNumber: String? = null,
    val selectedOrder: OrderDto.OrderResponseDto? = null,
    val selectedBill: BillDto.BillResponseDto? = null,
    // Tab management
    val activeTab: TableTab = TableTab.DETAILS,
    // Table details properties
    val tableInfo: TableInfo? = null,
    val orderItems: List<OrderItemForSelection> = emptyList(),
    val selectedItemIds: Set<Long> = emptySet(),
    val summary: OrderSummary? = null,
    val showCancelDialog: Boolean = false,
    val showTransferDialog: Boolean = false,
    val isSelectionMode: Boolean = false,
    val availableTables: List<RTable> = emptyList(),
    val transfers: List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto> = emptyList(),
    val selectedTransfer: ItemTransferHistoryDto.ItemTransferHistoryResponseDto? = null,
) {
    /**
     * Check if there are any orders to display.
     */
    val hasOrders: Boolean
        get() = orders.isNotEmpty()

    /**
     * Check if there are any bills to display.
     */
    val hasBills: Boolean
        get() = bills.isNotEmpty()

    /**
     * Get total number of orders.
     */
    val orderCount: Int
        get() = orders.size

    /**
     * Get total number of bills.
     */
    val billCount: Int
        get() = bills.size

    /**
     * Get total number of items across all orders.
     */
    val totalItemCount: Int
        get() = orders.sumOf { it.orderItems.size }

    val timeline: List<TimelineEntry>
        get() {
            val orderEntries = orders.map { TimelineEntry.OrderEntry(it) }
            val transferEntries = transfers.map { TimelineEntry.TransferEntry(it) }
            return (orderEntries + transferEntries).sortedByDescending { it.timestamp }
        }

    val hasTimelineEntries: Boolean
        get() = orders.isNotEmpty() || transfers.isNotEmpty()
}

/**
 * Table information for header display.
 *
 * @property tableId Unique table identifier
 * @property tableNumber Display number/name
 * @property serverName Name of server/waiter assigned
 * @property elapsedTimeSeconds Time in seconds since table was occupied
 * @property guestCount Number of guests (optional)
 * @property status Current table status
 */
data class TableInfo(
    val tableId: Long,
    val tableNumber: String,
    val serverName: String?,
    val elapsedTimeSeconds: Long?,
    val guestCount: Int? = null,
    val status: String,
)

/**
 * Order summary calculations for display.
 *
 * @property itemCount Total number of items
 * @property total Grand total including tax
 */
data class OrderSummary(
    val itemCount: Int,
    val total: Decimal,
)
