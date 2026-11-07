/**
 * ViewModel for table operations (transfer and delete).
 *
 * Manages table transfer and deletion operations separately from the grid display logic.
 * Handles API calls, loading states, and error handling for these specific operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.data.models.RTable
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.remote.CustomerAddressApiClient
import org.example.project.core.data.remote.CustomerApiClient
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableActionKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.dashboard.presentation.state.CustomerAddressSelectionState
import org.example.project.features.dashboard.presentation.state.CustomerPanelEvent
import org.example.project.features.dashboard.presentation.state.PosDashboardEvent
import org.example.project.features.dashboard.presentation.state.TableOperationsUiState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * ViewModel for managing table transfer and deletion operations.
 *
 * Provides methods for transferring table sessions between tables and
 * deleting table sessions (including cancelling all open items).
 */
@OptIn(ExperimentalTime::class)
class TableOperationsViewModel(
    private val rTableApiClient: RTableApiClient,
    private val rTableDataProvider: RTableDataProvider,
    private val employeeApiClient: EmployeeApiClient,
    private val customerApiClient: CustomerApiClient,
    private val customerAddressApiClient: CustomerAddressApiClient,
    private val languageManager: LanguageManager,
) : ViewModel() {
    private val logger = Logger.withTag("TableOperationsViewModel")

    /** PosSuccessWatcher shows success text verbatim, so it is resolved here in the operator's language. */
    private fun localized(key: StringKey): String = getString(key, languageManager.currentLanguage.value)

    private val _uiState = MutableStateFlow(TableOperationsUiState())
    val uiState: StateFlow<TableOperationsUiState> = _uiState.asStateFlow()

    fun onEvent(event: PosDashboardEvent) {
        when (event) {
            is PosDashboardEvent.SelectTable -> _uiState.update { it.copy(selectedTable = event.table) }
            is PosDashboardEvent.SetPendingTable -> _uiState.update { it.copy(pendingTable = event.table) }
            is PosDashboardEvent.ShowDetailPanel -> _uiState.update { it.copy(showDetailPanel = event.show) }
            is PosDashboardEvent.ShowTransferDialog -> _uiState.update { it.copy(showTransferDialog = event.show) }
            is PosDashboardEvent.ShowDeleteDialog -> _uiState.update { it.copy(showDeleteDialog = event.show) }
            is PosDashboardEvent.ShowVoucherDialog ->
                _uiState.update {
                    it.copy(showVoucherDialog = event.show, voucherDialogMode = event.mode)
                }
            is PosDashboardEvent.ShowTableNumberDialog -> _uiState.update { it.copy(showTableNumberDialog = event.show) }
            is PosDashboardEvent.ShowEmployeeSelectionDialog -> _uiState.update { it.copy(showEmployeeSelectionDialog = event.show) }
            is PosDashboardEvent.DismissOverlay -> {
                val state = _uiState.value
                when {
                    state.showEmployeeSelectionDialog ->
                        _uiState.update {
                            it.copy(showEmployeeSelectionDialog = false, pendingTable = null)
                        }
                    state.showDetailPanel ->
                        _uiState.update {
                            it.copy(showDetailPanel = false, selectedTable = null)
                        }
                    state.showTableNumberDialog -> _uiState.update { it.copy(showTableNumberDialog = false) }
                    state.showTransferDialog -> _uiState.update { it.copy(showTransferDialog = false) }
                    state.showVoucherDialog -> _uiState.update { it.copy(showVoucherDialog = false) }
                    state.showDeleteDialog -> _uiState.update { it.copy(showDeleteDialog = false) }
                }
            }
        }
    }

    fun loadEmployees() {
        viewModelScope.launch {
            logger.i { "TableOperationsViewModel.loadEmployees: loading active employees" }
            runCatchingApi { employeeApiClient.getActiveEmployees() }
                .onSuccess { employees ->
                    logger.i { "TableOperationsViewModel.loadEmployees: loaded ${employees.size} employees" }
                    _uiState.update { it.copy(employees = employees) }
                }.onError { uiError ->
                    logger.e { "TableOperationsViewModel.loadEmployees: failed to load employees" }
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    fun loadTables() {
        viewModelScope.launch {
            logger.i { "TableOperationsViewModel.loadTables: loading all tables" }
            runCatchingApi { rTableDataProvider.getAllRTables() }
                .onSuccess { tables ->
                    logger.i { "TableOperationsViewModel.loadTables: loaded ${tables.size} tables" }
                    _uiState.update { it.copy(tables = tables) }
                }.onError { uiError ->
                    logger.e { "TableOperationsViewModel.loadTables: failed to load tables" }
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    /**
     * Transfer a table session to another table.
     *
     * Handles two scenarios:
     * 1. Target has active session: Transfers all open items to existing session
     * 2. Target has no session: Moves entire session to target table
     *
     * @param sourceTableId Source table ID to transfer from
     * @param targetTableId Target table ID to transfer to
     * @param reason Why the table is transferred (German reason text or the cashier's own words)
     * @param onSuccess Callback invoked on successful transfer
     */
    fun transferTable(
        sourceTableId: Long,
        targetTableId: Long,
        onSuccess: () -> Unit = {},
        employeeNumber: String,
        reason: String,
    ) {
        viewModelScope.launch {
            logger.i { "TableOperationsViewModel.transferTable: transferring table sourceId=$sourceTableId to targetId=$targetTableId" }
            _uiState.update { it.copy(isLoading = true, error = null) }

            val request =
                RTableDto.TransferTableRequestDto(
                    targetTableId = targetTableId,
                    targetEmployeeNumber = employeeNumber,
                    reason = reason,
                )

            runCatchingApi { rTableApiClient.transferTable(sourceTableId, request) }
                .onSuccess {
                    logger.i { "TableOperationsViewModel.transferTable: transfer successful" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            lastOperation = "transfer_success",
                            successMessage = localized(TableActionKeys.tableTransferred),
                        )
                    }
                    onSuccess()
                }.onError { uiError ->
                    logger.e { "TableOperationsViewModel.transferTable: transfer failed" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = uiError,
                            lastOperation = "transfer_error",
                        )
                    }
                }
        }
    }

    /**
     * Delete a table session (cancel all items and reset table).
     *
     * This operation:
     * 1. Cancels all open order items for the table's session
     * 2. Ends the active session
     * 3. Resets the table to FREE status
     *
     * @param tableId Table ID to delete session for
     * @param reason Why the session is deleted (German reason text or the cashier's own words)
     * @param onSuccess Callback invoked on successful deletion
     */
    fun deleteTableSession(
        tableId: Long,
        reason: String,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            logger.i { "TableOperationsViewModel.deleteTableSession: deleting session for tableId=$tableId" }
            _uiState.update { it.copy(isLoading = true, error = null) }

            runCatchingApi { rTableApiClient.deleteTableSession(tableId, reason) }
                .onSuccess {
                    logger.i { "TableOperationsViewModel.deleteTableSession: session deleted successfully" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            lastOperation = "delete_success",
                            successMessage = localized(TableActionKeys.tableSessionDeleted),
                        )
                    }
                    onSuccess()
                }.onError { uiError ->
                    logger.e { "TableOperationsViewModel.deleteTableSession: failed to delete session" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = uiError,
                            lastOperation = "delete_error",
                        )
                    }
                }
        }
    }

    /**
     * Request billing for a table owned by another employee.
     *
     * Sends a notification to the table owner requesting them to bill the table.
     *
     * @param tableId Table ID to request billing for
     * @param onSuccess Callback invoked on successful request
     */
    fun requestBilling(
        tableId: Long,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            logger.i { "TableOperationsViewModel.requestBilling: requesting billing for tableId=$tableId" }
            _uiState.update { it.copy(isLoading = true, error = null) }

            runCatchingApi { rTableApiClient.requestBilling(tableId) }
                .onSuccess {
                    logger.i { "TableOperationsViewModel.requestBilling: billing request sent successfully" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            lastOperation = "billing_request_success",
                            successMessage = localized(TableActionKeys.billingRequestSent),
                        )
                    }
                    onSuccess()
                }.onError { uiError ->
                    logger.e { "TableOperationsViewModel.requestBilling: billing request failed" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = uiError,
                            lastOperation = "billing_request_error",
                        )
                    }
                }
        }
    }

    /**
     * Search for tables matching the given query string at the database level.
     *
     * Delegates to [RTableDataProvider.searchRTables] which performs an efficient
     * database query with smart prioritization:
     * 1. Exact match (highest priority)
     * 2. Table number starts with query
     * 3. Table number contains query (lowest priority)
     *
     * Results are limited to 5 entries and stored in [TableOperationsUiState.tableSearchResults].
     *
     * @param query The search query to match against table numbers
     */
    fun searchTablesByNumber(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(tableSearchResults = emptyList()) }
            return
        }

        viewModelScope.launch {
            runCatchingApi { rTableDataProvider.searchRTables(query.trim()) }
                .onSuccess { results -> _uiState.update { it.copy(tableSearchResults = results) } }
                .onError { _uiState.update { it.copy(tableSearchResults = emptyList()) } }
        }
    }

    /**
     * Clear table search results when the lookup dialog is closed.
     */
    fun clearTableSearchResults() {
        _uiState.update { it.copy(tableSearchResults = emptyList()) }
    }

    /**
     * Clear any error messages.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear success message.
     */
    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    /**
     * Reset the last operation state.
     */
    fun resetOperation() {
        _uiState.update { it.copy(lastOperation = null) }
    }

    /**
     * Unified event handler for customer panel events.
     *
     * Dispatches [CustomerPanelEvent] instances to the appropriate existing methods.
     *
     * @param event The customer panel event to handle
     * @param onAssignSuccess Callback for events that modify session assignment (Confirm, RemoveAssignment)
     */
    fun onCustomerPanelEvent(
        event: CustomerPanelEvent,
        onAssignSuccess: () -> Unit = {},
    ) {
        when (event) {
            is CustomerPanelEvent.Dismiss -> toggleCustomerPanel(false)
            is CustomerPanelEvent.SelectCustomer -> selectCustomer(event.customer)
            is CustomerPanelEvent.SelectCustomerById -> selectCustomerById(event.customerId)
            is CustomerPanelEvent.SelectAddress -> confirmAddressSelection(event.customer, event.address, event.isDelivery)
            is CustomerPanelEvent.Confirm -> assignCustomerToSession(onAssignSuccess)
            is CustomerPanelEvent.ShowAddCustomerDialog -> showAddCustomerDialog()
            is CustomerPanelEvent.HideAddCustomerDialog -> hideAddCustomerDialog()
            is CustomerPanelEvent.CreateCustomer ->
                createCustomer(
                    event.firstName,
                    event.lastName,
                    event.phone,
                    event.email,
                    event.notes,
                    event.marketingConsent,
                    event.dataProcessingConsent,
                )
            is CustomerPanelEvent.ShowAddAddressDialog -> showAddAddressDialog()
            is CustomerPanelEvent.HideAddAddressDialog -> hideAddAddressDialog()
            is CustomerPanelEvent.CreateAddress ->
                createAddress(
                    event.street,
                    event.houseNumber,
                    event.apartment,
                    event.postalCode,
                    event.city,
                    event.additionalInfo,
                    event.isDefault,
                )
            is CustomerPanelEvent.ClearCustomer -> clearSelectedCustomer()
            is CustomerPanelEvent.ToggleDelivery -> toggleDeliverySession(event.enabled)
            is CustomerPanelEvent.RemoveAssignment -> removeCustomerFromSession(onAssignSuccess)
            is CustomerPanelEvent.UpdateCustomer -> updateCustomerInfo(event.customerId, event.phone, event.email)
        }
    }

    // ===== Customer/Address Selection Functions =====

    /**
     * Toggle customer selection panel for a specific table.
     *
     * @param show Whether to show the panel
     * @param table The table for customer assignment (required when showing)
     */
    fun toggleCustomerPanel(
        show: Boolean,
        table: RTable? = null,
    ) {
        _uiState.update {
            it.copy(
                showCustomerPanel = show,
                selectedTableId = if (show) table?.id else null,
                customerDialogState =
                    if (show && table != null) {
                        CustomerAddressSelectionState(
                            isTakeawayTable = table.isTakeaway,
                        )
                    } else {
                        CustomerAddressSelectionState()
                    },
            )
        }

        // Load existing customer/address if table has active session
        val sessionId = table?.currentSessionId
        if (show && sessionId != null) {
            loadSessionCustomer(sessionId)
        }
    }

    /**
     * Load customer and address for an existing session.
     */
    private fun loadSessionCustomer(sessionId: Long) {
        viewModelScope.launch {
            logger.d { "TableOperationsViewModel.loadSessionCustomer: loading customer for sessionId=$sessionId" }
            runCatchingApi { rTableApiClient.getCustomerAndAddressBySessionId(sessionId) }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isDeliverySession = data.isDelivery,
                            selectedCustomer = data.customer,
                            selectedAddress = data.deliveryAddress,
                        )
                    }
                }.onError { /* Session may not have customer assigned yet, that's OK */ }
        }
    }

    /**
     * Select a customer and load their addresses.
     */
    fun selectCustomer(customer: CustomerDto.CustomerResponseDto) {
        viewModelScope.launch {
            _uiState.update { currentState ->
                currentState.copy(
                    selectedCustomer = customer,
                    selectedAddress = null,
                    customerDialogState =
                        currentState.customerDialogState.copy(
                            isSearching = true,
                        ),
                )
            }

            runCatchingApi { customerAddressApiClient.getCustomerAddresses(customer.id) }
                .onSuccess { addresses ->
                    _uiState.update { currentState ->
                        currentState.copy(
                            customerDialogState =
                                currentState.customerDialogState.copy(
                                    addresses = addresses,
                                    isSearching = false,
                                ),
                            selectedAddress = if (addresses.size == 1) addresses.first() else null,
                        )
                    }
                }.onError { uiError ->
                    _uiState.update { currentState ->
                        currentState.copy(
                            customerDialogState =
                                currentState.customerDialogState.copy(
                                    addresses = emptyList(),
                                    isSearching = false,
                                    error = uiError.toString(),
                                ),
                        )
                    }
                }
        }
    }

    /**
     * Select a customer by ID — fetches full customer data then delegates to selectCustomer.
     */
    private fun selectCustomerById(customerId: String) {
        logger.d { "TableOperationsViewModel.selectCustomerById: customerId=$customerId" }
        viewModelScope.launch {
            _uiState.update { currentState ->
                currentState.copy(
                    customerDialogState = currentState.customerDialogState.copy(isSearching = true),
                )
            }
            runCatchingApi { customerApiClient.getCustomerById(customerId) }
                .onSuccess { customer ->
                    selectCustomer(customer)
                }.onError { uiError ->
                    logger.e { "TableOperationsViewModel.selectCustomerById: failed: $uiError" }
                    _uiState.update { currentState ->
                        currentState.copy(
                            customerDialogState =
                                currentState.customerDialogState.copy(
                                    isSearching = false,
                                    error = uiError.toString(),
                                ),
                        )
                    }
                }
        }
    }

    /**
     * Clear selected customer and address.
     */
    fun clearSelectedCustomer() {
        _uiState.update { currentState ->
            currentState.copy(
                selectedCustomer = null,
                selectedAddress = null,
                isDeliverySession = false,
                customerDialogState = CustomerAddressSelectionState(),
            )
        }
    }

    /**
     * Select an address within the review step (no longer navigates to a separate confirmation step).
     */
    fun confirmAddressSelection(
        customer: CustomerDto.CustomerResponseDto,
        address: CustomerAddressDto.CustomerAddressResponseDto,
        isDeliverySession: Boolean,
    ) {
        _uiState.update { currentState ->
            currentState.copy(
                selectedCustomer = customer,
                selectedAddress = address,
                isDeliverySession = isDeliverySession,
            )
        }
    }

    /**
     * Toggle delivery session mode.
     */
    fun toggleDeliverySession(enabled: Boolean) {
        _uiState.update { currentState ->
            currentState.copy(
                isDeliverySession = enabled,
                customerDialogState =
                    currentState.customerDialogState.copy(
                        isDeliverySession = enabled,
                    ),
            )
        }
    }

    /**
     * Show add new customer dialog.
     */
    fun showAddCustomerDialog() {
        _uiState.update { currentState ->
            currentState.copy(
                customerDialogState =
                    currentState.customerDialogState.copy(
                        showAddCustomerDialog = true,
                    ),
            )
        }
    }

    /**
     * Hide add customer dialog.
     */
    fun hideAddCustomerDialog() {
        _uiState.update { currentState ->
            currentState.copy(
                customerDialogState =
                    currentState.customerDialogState.copy(
                        showAddCustomerDialog = false,
                    ),
            )
        }
    }

    /**
     * Create a new customer.
     */
    fun createCustomer(
        firstName: String,
        lastName: String,
        phone: String?,
        email: String?,
        notes: String?,
        marketingConsent: Boolean,
        dataProcessingConsent: Boolean,
    ) {
        logger.i { "TableOperationsViewModel.createCustomer: creating customer firstName=$firstName lastName=$lastName" }
        viewModelScope.launch {
            _uiState.update { currentState ->
                currentState.copy(
                    customerDialogState =
                        currentState.customerDialogState.copy(
                            isSearching = true,
                        ),
                )
            }

            val customerNumber = "C${Clock.System.now().toEpochMilliseconds()}"

            val request =
                CustomerDto.CreateCustomerRequestDto(
                    customerNumber = customerNumber,
                    firstName = firstName,
                    lastName = lastName,
                    phone = phone,
                    email = email,
                    notes = notes,
                    marketingConsent = marketingConsent,
                    dataProcessingConsent = dataProcessingConsent,
                )

            runCatchingApi { customerApiClient.createCustomer(request) }
                .onSuccess { customer ->
                    selectCustomer(customer)
                    hideAddCustomerDialog()
                }.onError { uiError ->
                    _uiState.update { currentState ->
                        currentState.copy(
                            customerDialogState =
                                currentState.customerDialogState.copy(
                                    isSearching = false,
                                    error = uiError.toString(),
                                ),
                        )
                    }
                }
        }
    }

    /**
     * Show add new address dialog.
     */
    fun showAddAddressDialog() {
        _uiState.update { currentState ->
            currentState.copy(
                customerDialogState =
                    currentState.customerDialogState.copy(
                        showAddAddressDialog = true,
                    ),
            )
        }
    }

    /**
     * Hide add address dialog.
     */
    fun hideAddAddressDialog() {
        _uiState.update { currentState ->
            currentState.copy(
                customerDialogState =
                    currentState.customerDialogState.copy(
                        showAddAddressDialog = false,
                    ),
            )
        }
    }

    /**
     * Create a new address for the selected customer.
     */
    fun createAddress(
        street: String,
        houseNumber: String,
        apartment: String?,
        postalCode: String,
        city: String,
        additionalInfo: String?,
        isDefault: Boolean,
    ) {
        val customerId = _uiState.value.selectedCustomer?.id ?: return

        viewModelScope.launch {
            _uiState.update { currentState ->
                currentState.copy(
                    customerDialogState =
                        currentState.customerDialogState.copy(
                            isSearching = true,
                        ),
                )
            }

            val request =
                CustomerAddressDto.CreateCustomerAddressRequestDto(
                    customerId = customerId,
                    street = street,
                    houseNumber = houseNumber,
                    apartment = apartment,
                    postalCode = postalCode,
                    city = city,
                    additionalInfo = additionalInfo,
                    isDefault = isDefault,
                )

            runCatchingApi { customerAddressApiClient.createAddress(request) }
                .onSuccess {
                    val customer = _uiState.value.selectedCustomer
                    if (customer != null) {
                        selectCustomer(customer)
                    }
                    hideAddAddressDialog()
                }.onError { uiError ->
                    _uiState.update { currentState ->
                        currentState.copy(
                            customerDialogState =
                                currentState.customerDialogState.copy(
                                    isSearching = false,
                                    error = uiError.toString(),
                                ),
                        )
                    }
                }
        }
    }

    /**
     * Assign customer and address to table session and close panel.
     */
    fun assignCustomerToSession(onSuccess: () -> Unit = {}) {
        val tableId = _uiState.value.selectedTableId ?: return
        val customer = _uiState.value.selectedCustomer ?: return
        val address = _uiState.value.selectedAddress

        logger.i { "TableOperationsViewModel.assignCustomerToSession: assigning customerId=${customer.id} to tableId=$tableId" }
        viewModelScope.launch {
            var sessionId: Long? = null
            runCatchingApi { rTableDataProvider.getRTableById(tableId) }
                .onSuccess { sessionId = it?.currentSessionId }
                .onError { }

            val resolvedSessionId = sessionId
            if (resolvedSessionId == null) {
                _uiState.update {
                    it.copy(
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                            ),
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true) }

            val assignRequest =
                TableSessionDto.AssignCustomerAndAddressRequestDto(
                    customerId = customer.id,
                    deliveryAddressId = address?.id,
                    isDelivery = _uiState.value.isDeliverySession,
                    deliveryPhoneNumber = null,
                )

            runCatchingApi { rTableApiClient.assignCustomerAndAddress(resolvedSessionId, assignRequest) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showCustomerPanel = false,
                            lastOperation = "customer_assigned",
                            successMessage = localized(TableActionKeys.customerAssigned),
                        )
                    }
                    onSuccess()
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = uiError,
                        )
                    }
                }
        }
    }

    /**
     * Removes customer and address assignment from the current table session.
     *
     * Clears the customer ID, delivery address ID, isDelivery flag,
     * and delivery phone number from the session.
     *
     * @param onSuccess Callback executed after successful removal
     */

    /**
     * Update customer phone and email. Placeholder for when CustomerApiClient
     * supports updateCustomer. Currently logs that update is not yet supported.
     */
    private fun updateCustomerInfo(
        customerId: String,
        phone: String?,
        email: String?,
    ) {
        logger.w { "TableOperationsViewModel.updateCustomerInfo: customer update not yet supported via API, customerId=$customerId" }
        // CustomerApiClient does not have an updateCustomer method yet.
        // When it is added, call it here and refresh selectedCustomer on success.
    }

    fun removeCustomerFromSession(onSuccess: () -> Unit = {}) {
        val tableId = _uiState.value.selectedTableId ?: return

        logger.i { "TableOperationsViewModel.removeCustomerFromSession: removing customer from tableId=$tableId" }
        viewModelScope.launch {
            var sessionId: Long? = null
            runCatchingApi { rTableDataProvider.getRTableById(tableId) }
                .onSuccess { sessionId = it?.currentSessionId }
                .onError { }

            val resolvedSessionId = sessionId
            if (resolvedSessionId == null) {
                _uiState.update {
                    it.copy(
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                            ),
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true) }

            runCatchingApi { rTableApiClient.removeCustomerAndAddress(resolvedSessionId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            selectedCustomer = null,
                            selectedAddress = null,
                            isDeliverySession = false,
                            lastOperation = "customer_removed",
                            successMessage = localized(TableActionKeys.customerRemoved),
                        )
                    }
                    onSuccess()
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = uiError,
                        )
                    }
                }
        }
    }
}
