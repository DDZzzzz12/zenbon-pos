package org.example.project.features.dashboard.presentation.state

import org.example.project.core.data.models.RTable
import org.example.project.core.presentation.error.UiError

/**
 * UI state for the POS table detail screen's operational features.
 *
 * Manages multi-select, cancel, and transfer functionality for order items
 * on a specific table.
 *
 * @property isLoading Whether data is being loaded or an operation is in progress
 * @property error Current error state, if any
 * @property successMessage Success message to display temporarily
 * @property tableId The table ID being viewed
 * @property tableNumber Display table number
 * @property tableInfo Table information for header display
 * @property orderItems List of current order items with selection state
 * @property selectedItemIds Set of currently selected item IDs
 * @property summary Calculated order summary (total, count)
 * @property isSelectionMode Whether multi-select mode is active
 * @property availableTables List of tables available for transfer
 * @property showCancelDialog Whether cancel confirmation dialog is visible
 * @property showTransferDialog Whether transfer dialog is visible
 * @property referencePrintOffered Whether „Referenzdruck“ is offered: false once the shop's
 *   print-settings are read as disabled (the server refuses it with 409 `PRINTING_DISABLED`). Stays
 *   true while unknown or unreadable — the server's refusal still explains it then.
 * @property stornoOffered Whether the item Storno is offered. False on an order station with no
 *   manager signed in (`StationCorrectionActions.offersStorno`, owner decision 2026-10-03, NOTE-v);
 *   the screen sets it, and the ViewModel refuses a Storno locally while it is false.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
data class PosTableDetailUiState(
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val successMessage: String? = null,
    val tableId: Long = 0,
    val tableNumber: String? = null,
    val tableInfo: TableInfo? = null,
    val orderItems: List<OrderItemForSelection> = emptyList(),
    val selectedItemIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false,
    val availableTables: List<RTable> = emptyList(),
    val showCancelDialog: Boolean = false,
    val showTransferDialog: Boolean = false,
    val referencePrintOffered: Boolean = true,
    val stornoOffered: Boolean = true,
)
