/**
 * ViewModel for Printer Settings screen.
 *
 * Manages printer configuration state including:
 * - Printer setup management (USB/Network printers)
 * - Printer location management (Kitchen, Bar, etc.)
 * - Print job history (audit trail)
 *
 * Integrates with:
 * - PrinterLocationApiClient for location management
 * - ProductCategoryApiClient for category data
 *
 * Future integration points:
 * - PrinterSetupApiClient for printer hardware management
 * - PrintJobHistoryApiClient for audit trail fetching
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.datetime.number
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.PrintJobHistoryApiClient
import org.example.project.core.data.remote.PrinterDiscoveryApiClient
import org.example.project.core.data.remote.PrinterLocationApiClient
import org.example.project.core.data.remote.ProductCategoryApiClient
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.utils.VoucherCatalog
import org.example.project.features.settings.presentation.components.common.DateRange
import org.example.project.features.settings.presentation.components.common.DateRangeOption
import org.example.project.features.settings.presentation.state.PrinterSettingsState

/**
 * ViewModel for managing printer settings.
 *
 * Provides state management for printer setup, printer locations,
 * and print job history with server synchronization support.
 *
 * @param printerLocationApiClient API client for printer location operations
 * @param productCategoryApiClient API client for product category data
 * @param ecrConfigurationApiClient API client for the ecr-owned shop configuration (print settings)
 * @param printJobHistoryApiClient API client for print job history operations
 * @param printerDiscoveryApiClient API client for IPP printer discovery
 */
class PrinterSettingsViewModel(
    private val printerLocationApiClient: PrinterLocationApiClient,
    private val productCategoryApiClient: ProductCategoryApiClient,
    private val ecrConfigurationApiClient: EcrConfigurationApiClient,
    private val printJobHistoryApiClient: PrintJobHistoryApiClient,
    private val printerDiscoveryApiClient: PrinterDiscoveryApiClient,
    // Was a ShopDataManager, used only for getShopId(). That class is SQLDelight-backed and cannot
    // cross to wasm, so the caller supplies the id: the till from its ShopDataManager, adminApp
    // from its single-tenant constant.
    private val shopId: Long,
) : ViewModel() {
    private val logger = Logger.withTag("PrinterSettingsViewModel")

    private val _uiState = MutableStateFlow(PrinterSettingsState())
    val uiState: StateFlow<PrinterSettingsState> = _uiState.asStateFlow()

    init {
        // Load initial data
        loadPrintSettings()
        loadPrinterLocations()
        loadProductCategories()
    }

    // ============================================================
    // PRINTER SETUP MANAGEMENT
    // ============================================================

    /**
     * Toggles the printer setup section expanded/collapsed state.
     */
    fun togglePrinterSetupSection() {
        _uiState.update { currentState ->
            currentState.copy(
                printerSetupSection =
                    currentState.printerSetupSection.copy(
                        isExpanded = !currentState.printerSetupSection.isExpanded,
                    ),
            )
        }
    }

    /**
     * Shows the add printer dialog.
     */
    fun showAddPrinterDialog() {
        _uiState.update { currentState ->
            currentState.copy(
                printerSetupSection =
                    currentState.printerSetupSection.copy(
                        showAddDialog = true,
                        editingPrinter = null,
                    ),
            )
        }
    }

    /**
     * Shows the edit printer dialog for a specific printer.
     *
     * @param printer Printer to edit
     */
    fun showEditPrinterDialog(printer: PrinterDto.PrinterSetupResponseDto) {
        _uiState.update { currentState ->
            currentState.copy(
                printerSetupSection =
                    currentState.printerSetupSection.copy(
                        showAddDialog = true,
                        editingPrinter = printer,
                    ),
            )
        }
    }

    /**
     * Dismisses the printer dialog.
     */
    fun dismissPrinterDialog() {
        _uiState.update { currentState ->
            currentState.copy(
                printerSetupSection =
                    currentState.printerSetupSection.copy(
                        showAddDialog = false,
                        editingPrinter = null,
                    ),
            )
        }
    }

    // ============================================================
    // PRINTER LOCATION MANAGEMENT
    // ============================================================

    /**
     * Toggles the printer location section expanded/collapsed state.
     */
    fun togglePrinterLocationSection() {
        _uiState.update { currentState ->
            currentState.copy(
                printerLocationSection =
                    currentState.printerLocationSection.copy(
                        isExpanded = !currentState.printerLocationSection.isExpanded,
                    ),
            )
        }
    }

    /**
     * Loads all configured printer locations from the server.
     *
     * GET /api/printer-location?includeInactive=true
     */
    fun loadPrinterLocations() {
        viewModelScope.launch {
            logger.i { "PrinterSettingsViewModel.loadPrinterLocations: loading printer locations" }
            _uiState.update { currentState ->
                currentState.copy(
                    printerLocationSection =
                        currentState.printerLocationSection.copy(
                            isLoadingLocations = true,
                        ),
                )
            }

            runCatchingApi { printerLocationApiClient.getAllPrinterLocations(includeInactive = true) }
                .onSuccess { locations ->
                    logger.i { "PrinterSettingsViewModel.loadPrinterLocations: loaded ${locations.size} locations" }
                    _uiState.update { currentState ->
                        currentState.copy(
                            printerLocationSection =
                                currentState.printerLocationSection.copy(
                                    locations = locations,
                                    isLoadingLocations = false,
                                ),
                        )
                    }
                }.onError { uiError ->
                    logger.e { "PrinterSettingsViewModel.loadPrinterLocations: failed to load locations: $uiError" }
                    _uiState.update { currentState ->
                        currentState.copy(
                            error = uiError,
                            printerLocationSection =
                                currentState.printerLocationSection.copy(
                                    isLoadingLocations = false,
                                ),
                        )
                    }
                }
        }
    }

    /**
     * Loads product categories for the printer location dialog.
     *
     * GET /api/product-category
     */
    fun loadProductCategories() {
        viewModelScope.launch {
            runCatchingApi { productCategoryApiClient.getAllProductCategories() }
                .onSuccess { categories ->
                    _uiState.update { currentState ->
                        currentState.copy(
                            printerLocationSection =
                                currentState.printerLocationSection.copy(
                                    // The VOUCHER category holds the voucher article, which is sold through the
                                    // voucher dialog and never reaches a kitchen/bar ticket; offering it as a routing
                                    // target is a dead choice (PFAND stays: deposit lines do print).
                                    availableCategories = categories.filterNot { VoucherCatalog.isVoucherCategory(it.name) },
                                ),
                        )
                    }
                }.onError { _ ->
                    // Categories are optional, silently fall back to empty
                    _uiState.update { currentState ->
                        currentState.copy(
                            printerLocationSection =
                                currentState.printerLocationSection.copy(
                                    availableCategories = emptyList(),
                                ),
                        )
                    }
                }
        }
    }

    /**
     * Shows the add printer location dialog.
     */
    fun showAddLocationDialog() {
        _uiState.update { currentState ->
            currentState.copy(
                printerLocationSection =
                    currentState.printerLocationSection.copy(
                        showAddDialog = true,
                        editingLocation = null,
                    ),
            )
        }
    }

    /**
     * Shows the edit printer location dialog for a specific location.
     *
     * @param location Location to edit
     */
    fun showEditLocationDialog(location: PrinterDto.PrinterLocationResponseDto) {
        _uiState.update { currentState ->
            currentState.copy(
                printerLocationSection =
                    currentState.printerLocationSection.copy(
                        showAddDialog = true,
                        editingLocation = location,
                    ),
            )
        }
    }

    /**
     * Dismisses the printer location dialog.
     */
    fun dismissLocationDialog() {
        _uiState.update { currentState ->
            currentState.copy(
                printerLocationSection =
                    currentState.printerLocationSection.copy(
                        showAddDialog = false,
                        editingLocation = null,
                    ),
            )
        }
    }

    /**
     * Adds a new printer location.
     *
     * POST /api/printer-location
     *
     * @param request Location creation request
     */
    fun addPrinterLocation(request: PrinterDto.CreatePrinterLocationRequestDto) {
        viewModelScope.launch {
            logger.i { "PrinterSettingsViewModel.addPrinterLocation: creating printer location name=${request.printerLocation}" }
            _uiState.update { it.copy(isLoading = true) }

            runCatchingApi { printerLocationApiClient.createPrinterLocation(request) }
                .onSuccess {
                    logger.i { "PrinterSettingsViewModel.addPrinterLocation: location created successfully" }
                    loadPrinterLocations()
                    dismissLocationDialog()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            saveSuccess = true,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "PrinterSettingsViewModel.addPrinterLocation: failed to create location: $uiError" }
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
     * Updates an existing printer location.
     *
     * PUT /api/printer-location/{id}
     *
     * @param id Location ID
     * @param request Location update request
     */
    fun updatePrinterLocation(
        id: Long,
        request: PrinterDto.UpdatePrinterLocationRequestDto,
    ) {
        viewModelScope.launch {
            logger.i { "PrinterSettingsViewModel.updatePrinterLocation: updating locationId=$id" }
            _uiState.update { it.copy(isLoading = true) }

            runCatchingApi { printerLocationApiClient.updatePrinterLocation(id, request) }
                .onSuccess {
                    logger.i { "PrinterSettingsViewModel.updatePrinterLocation: location updated successfully" }
                    loadPrinterLocations()
                    dismissLocationDialog()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            saveSuccess = true,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "PrinterSettingsViewModel.updatePrinterLocation: failed to update location: $uiError" }
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
     * Deletes a printer location (soft delete - sets isActive to false).
     *
     * DELETE /api/printer-location/{id}
     *
     * @param id Location ID to deactivate
     */
    fun deletePrinterLocation(id: Long) {
        viewModelScope.launch {
            logger.i { "PrinterSettingsViewModel.deletePrinterLocation: deactivating locationId=$id" }
            _uiState.update { it.copy(isLoading = true) }

            runCatchingApi { printerLocationApiClient.deactivatePrinterLocation(id) }
                .onSuccess {
                    logger.i { "PrinterSettingsViewModel.deletePrinterLocation: location deactivated successfully" }
                    loadPrinterLocations()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            saveSuccess = true,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "PrinterSettingsViewModel.deletePrinterLocation: failed to deactivate location: $uiError" }
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
     * Activates a printer location (sets isActive to true).
     *
     * PATCH /api/printer-location/{id}/activate
     *
     * @param id Location ID to activate
     */
    fun activatePrinterLocation(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            runCatchingApi { printerLocationApiClient.activatePrinterLocation(id) }
                .onSuccess {
                    loadPrinterLocations()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            saveSuccess = true,
                        )
                    }
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

    // ============================================================
    // IPP PRINTER DISCOVERY
    // ============================================================

    /**
     * Discovers printers via CUPS/IPP on the server.
     *
     * GET /api/printer-discovery/discover
     */
    fun discoverPrinters() {
        viewModelScope.launch {
            logger.i { "PrinterSettingsViewModel.discoverPrinters: starting printer discovery" }
            _uiState.update { currentState ->
                currentState.copy(
                    printerLocationSection =
                        currentState.printerLocationSection.copy(
                            isDiscovering = true,
                            showDiscoveryDialog = true,
                        ),
                )
            }

            runCatchingApi { printerDiscoveryApiClient.discoverPrinters() }
                .onSuccess { discovered ->
                    logger.i { "PrinterSettingsViewModel.discoverPrinters: discovered ${discovered.size} printers" }
                    _uiState.update { currentState ->
                        currentState.copy(
                            printerLocationSection =
                                currentState.printerLocationSection.copy(
                                    discoveredPrinters = discovered,
                                    isDiscovering = false,
                                ),
                        )
                    }
                }.onError { uiError ->
                    logger.e { "PrinterSettingsViewModel.discoverPrinters: discovery failed: $uiError" }
                    _uiState.update { currentState ->
                        currentState.copy(
                            error = uiError,
                            printerLocationSection =
                                currentState.printerLocationSection.copy(
                                    discoveredPrinters = emptyList(),
                                    isDiscovering = false,
                                ),
                        )
                    }
                }
        }
    }

    /**
     * Shows the printer discovery dialog and triggers a scan.
     */
    fun showDiscoveryDialog() {
        discoverPrinters()
    }

    /**
     * Dismisses the printer discovery dialog.
     */
    fun dismissDiscoveryDialog() {
        _uiState.update { currentState ->
            currentState.copy(
                printerLocationSection =
                    currentState.printerLocationSection.copy(
                        showDiscoveryDialog = false,
                        discoveredPrinters = emptyList(),
                    ),
            )
        }
    }

    // ============================================================
    // PRINT JOB HISTORY
    // ============================================================

    /**
     * Toggles the print job history section expanded/collapsed state.
     */
    fun togglePrintJobHistorySection() {
        _uiState.update { currentState ->
            currentState.copy(
                printJobHistorySection =
                    currentState.printJobHistorySection.copy(
                        isExpanded = !currentState.printJobHistorySection.isExpanded,
                    ),
            )
        }

        // Load history when expanded
        if (_uiState.value.printJobHistorySection.isExpanded) {
            loadPrintJobHistory()
        }
    }

    /**
     * Filters the print job history to one printer location (null = all) and reloads.
     */
    fun selectHistoryLocation(id: Long?) {
        _uiState.update { currentState ->
            currentState.copy(
                printJobHistorySection = currentState.printJobHistorySection.copy(locationId = id),
            )
        }
        loadPrintJobHistory()
    }

    /**
     * Drops the location filter WITHOUT loading, for a sheet that has just closed: a reload for a
     * closed sheet is a wasted request. The next open calls [selectHistoryLocation], which loads.
     */
    fun resetHistoryLocation() {
        _uiState.update { currentState ->
            currentState.copy(
                printJobHistorySection = currentState.printJobHistorySection.copy(locationId = null),
            )
        }
    }

    /**
     * Loads print job history from the server using current filter state.
     *
     * GET /api/print-job-history?startDate=...&endDate=...&limit=50&status=...&printType=...
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun loadPrintJobHistory() {
        viewModelScope.launch {
            logger.i { "PrinterSettingsViewModel.loadPrintJobHistory: loading print job history" }
            _uiState.update { currentState ->
                currentState.copy(
                    printJobHistorySection =
                        currentState.printJobHistorySection.copy(
                            isLoadingHistory = true,
                        ),
                )
            }

            val historyState = _uiState.value.printJobHistorySection
            val dateRange = calculateDateRange(historyState.selectedDateRange)

            runCatchingApi {
                printJobHistoryApiClient.getPrintJobHistory(
                    startDate = dateRange.start.toString(),
                    endDate = dateRange.end.toString(),
                    limit = 50,
                    status = historyState.statusFilter?.name,
                    printType = historyState.printTypeFilter?.name,
                    locationId = historyState.locationId,
                )
            }.onSuccess { history ->
                logger.i { "PrinterSettingsViewModel.loadPrintJobHistory: loaded ${history.size} print jobs" }
                _uiState.update { currentState ->
                    currentState.copy(
                        printJobHistorySection =
                            currentState.printJobHistorySection.copy(
                                history = history,
                                isLoadingHistory = false,
                            ),
                    )
                }
            }.onError { uiError ->
                logger.e { "PrinterSettingsViewModel.loadPrintJobHistory: failed to load history: $uiError" }
                _uiState.update { currentState ->
                    currentState.copy(
                        error = uiError,
                        printJobHistorySection =
                            currentState.printJobHistorySection.copy(
                                isLoadingHistory = false,
                            ),
                    )
                }
            }
        }
    }

    /**
     * Updates the status filter and reloads print job history.
     *
     * @param status New status filter (null to clear)
     */
    fun updateStatusFilter(status: PrinterDto.PrintJobStatus?) {
        _uiState.update { currentState ->
            currentState.copy(
                printJobHistorySection =
                    currentState.printJobHistorySection.copy(
                        statusFilter = status,
                    ),
            )
        }
        loadPrintJobHistory()
    }

    /**
     * Updates the print type filter and reloads print job history.
     *
     * @param printType New print type filter (null to clear)
     */
    fun updatePrintTypeFilter(printType: PrinterDto.PrintType?) {
        _uiState.update { currentState ->
            currentState.copy(
                printJobHistorySection =
                    currentState.printJobHistorySection.copy(
                        printTypeFilter = printType,
                    ),
            )
        }
        loadPrintJobHistory()
    }

    /**
     * Updates the date range selection and reloads print job history.
     *
     * @param option The new date range option
     */
    fun updateDateRange(option: DateRangeOption) {
        _uiState.update { currentState ->
            currentState.copy(
                printJobHistorySection =
                    currentState.printJobHistorySection.copy(
                        selectedDateRange = option,
                    ),
            )
        }
        loadPrintJobHistory()
    }

    /**
     * Helper to calculate date range from DateRangeOption.
     */
    @kotlin.time.ExperimentalTime
    private fun calculateDateRange(option: DateRangeOption): DateRange {
        val now =
            kotlin.time.Clock.System
                .now()
        val timezone = TimeZone.currentSystemDefault()
        val today = now.toLocalDateTime(timezone)

        val startOfToday = LocalDateTime(today.year, today.month.number, today.day, 0, 0, 0)
        val endOfToday = LocalDateTime(today.year, today.month.number, today.day, 23, 59, 59)

        return when (option) {
            DateRangeOption.TODAY -> DateRange(startOfToday, endOfToday)
            DateRangeOption.YESTERDAY -> {
                val todayDate = LocalDate(today.year, today.month.number, today.day)
                val yesterday = todayDate.minus(1, DateTimeUnit.DAY)
                DateRange(
                    LocalDateTime(yesterday, LocalTime(0, 0, 0)),
                    LocalDateTime(yesterday, LocalTime(23, 59, 59)),
                )
            }
            DateRangeOption.LAST_7_DAYS -> {
                val todayDate = LocalDate(today.year, today.month.number, today.day)
                val sevenDaysAgo = todayDate.minus(7, DateTimeUnit.DAY)
                DateRange(
                    LocalDateTime(sevenDaysAgo, LocalTime(0, 0, 0)),
                    endOfToday,
                )
            }
            DateRangeOption.LAST_30_DAYS -> {
                val todayDate = LocalDate(today.year, today.month.number, today.day)
                val thirtyDaysAgo = todayDate.minus(30, DateTimeUnit.DAY)
                DateRange(
                    LocalDateTime(thirtyDaysAgo, LocalTime(0, 0, 0)),
                    endOfToday,
                )
            }
            DateRangeOption.CUSTOM -> DateRange(startOfToday, endOfToday)
        }
    }

    // ============================================================
    // PRINT SETTINGS CONFIGURATION
    // ============================================================

    /**
     * Loads print settings configuration from the server.
     *
     * GET /api/v1/config/{shopId}/print-settings
     */
    fun loadPrintSettings() {
        viewModelScope.launch {
            logger.i { "PrinterSettingsViewModel.loadPrintSettings: loading print settings" }
            _uiState.update { it.copy(isLoadingPrintSettings = true) }
            runCatchingApi { ecrConfigurationApiClient.getPrintSettings(shopId) }
                .onSuccess { resp ->
                    logger.i { "PrinterSettingsViewModel.loadPrintSettings: print settings loaded, enabled=${resp.enabled}" }
                    _uiState.update {
                        it.copy(
                            printSettingsEnabled = resp.enabled,
                            isLoadingPrintSettings = false,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "PrinterSettingsViewModel.loadPrintSettings: failed to load print settings: $uiError" }
                    _uiState.update {
                        it.copy(
                            printSettingsEnabled = false, // Default to disabled on error
                            isLoadingPrintSettings = false,
                            error = uiError,
                        )
                    }
                }
        }
    }

    /**
     * Toggles print settings on/off.
     *
     * PUT /api/v1/config/{shopId}/print-settings
     *
     * @param enabled New enabled state
     */
    fun updatePrintSettings(enabled: Boolean) {
        viewModelScope.launch {
            logger.i { "PrinterSettingsViewModel.updatePrintSettings: updating print settings enabled=$enabled" }
            _uiState.update { it.copy(isLoading = true) }

            val request = ShopConfigurationDto.UpdatePrintSettingsRequestDto(enabled = enabled)

            runCatchingApi { ecrConfigurationApiClient.updatePrintSettings(shopId, request) }
                .onSuccess { resp ->
                    logger.i { "PrinterSettingsViewModel.updatePrintSettings: print settings updated successfully" }
                    _uiState.update {
                        it.copy(
                            printSettingsEnabled = resp.enabled,
                            isLoading = false,
                            saveSuccess = true,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "PrinterSettingsViewModel.updatePrintSettings: failed to update print settings: $uiError" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = uiError,
                        )
                    }
                }
        }
    }

    // ============================================================
    // UTILITY FUNCTIONS
    // ============================================================

    /**
     * Clears error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clears save success flag.
     */
    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }

    fun testPrint(locationId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(testPrintingLocationId = locationId) }

            runCatchingApi { printerDiscoveryApiClient.testPrint(locationId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            testPrintingLocationId = null,
                            testPrintSuccess = true,
                        )
                    }
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            testPrintingLocationId = null,
                            error = uiError,
                        )
                    }
                }
        }
    }

    fun clearTestPrintSuccess() {
        _uiState.update { it.copy(testPrintSuccess = false) }
    }
}
