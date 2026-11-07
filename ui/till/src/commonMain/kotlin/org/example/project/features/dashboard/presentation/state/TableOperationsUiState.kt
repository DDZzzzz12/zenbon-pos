/**
 * UI state for table operations (transfer, delete, and customer assignment).
 *
 * Represents the state of table transfer, deletion, and customer assignment operations,
 * including loading status and error handling.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.state

import org.example.project.core.data.models.RTable
import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.presentation.error.UiError
import org.example.project.ui.screens.voucher.components.VoucherDialogMode

/**
 * UI state for table operations.
 *
 * @property isLoading Whether an operation is currently in progress
 * @property error Error if an operation failed (for global notification system)
 * @property lastOperation Identifier for the last operation performed (for tracking success/failure)
 * @property successMessage Success message to display via global notification
 * @property tables List of available tables
 * @property employees List of available employees
 * @property showCustomerPanel Whether customer selection panel is shown
 * @property customerDialogState State of the customer address selection dialog
 * @property selectedCustomer Currently selected customer
 * @property selectedAddress Currently selected address
 * @property isDeliverySession Whether this is a delivery session
 * @property selectedTableId Table ID for customer assignment
 * @property tableSearchResults Tables matching the current keyboard search query
 * @property selectedTable Currently selected table for actions
 * @property pendingTable Table awaiting employee selection before opening
 * @property showDetailPanel Whether the table detail panel is visible
 * @property showTransferDialog Whether the transfer dialog is visible
 * @property showDeleteDialog Whether the delete confirmation dialog is visible
 * @property showVoucherDialog Whether the voucher dialog is visible
 * @property voucherDialogMode Current voucher dialog mode
 * @property showTableNumberDialog Whether the table number lookup dialog is visible
 * @property showEmployeeSelectionDialog Whether the employee selection dialog is visible
 */
data class TableOperationsUiState(
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val lastOperation: String? = null,
    val successMessage: String? = null,
    val tables: List<RTable> = emptyList(),
    val employees: List<EmployeeDto.EmployeeInfoDto> = emptyList(),
    // Customer/Address selection state
    val showCustomerPanel: Boolean = false,
    val customerDialogState: CustomerAddressSelectionState = CustomerAddressSelectionState(),
    val selectedCustomer: CustomerDto.CustomerResponseDto? = null,
    val selectedAddress: CustomerAddressDto.CustomerAddressResponseDto? = null,
    val isDeliverySession: Boolean = false,
    val selectedTableId: Long? = null,
    // Table number search results for keyboard lookup
    val tableSearchResults: List<RTable> = emptyList(),
    // Dashboard dialog/overlay state
    val selectedTable: RTable? = null,
    val pendingTable: RTable? = null,
    val showDetailPanel: Boolean = false,
    val showTransferDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val showVoucherDialog: Boolean = false,
    val voucherDialogMode: VoucherDialogMode = VoucherDialogMode.TABLE_SESSION,
    val showTableNumberDialog: Boolean = false,
    val showEmployeeSelectionDialog: Boolean = false,
) {
    val anyOverlayOpen: Boolean get() =
        showDetailPanel ||
            showTableNumberDialog ||
            showTransferDialog ||
            showDeleteDialog ||
            showVoucherDialog ||
            showEmployeeSelectionDialog
}
