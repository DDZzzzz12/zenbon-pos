package org.example.project.admin.features.tables.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.models.RTable
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.settings.presentation.state.TableManagementState
import org.example.project.features.settings.presentation.state.TableSortColumn

/**
 * ViewModel for the adminApp Tische destination — restaurant table CRUD, filtering, sorting and
 * pagination. Talks to [RTableApiClient] directly over HTTP; unlike composeApp's till-only
 * TableManagementViewModel there is no local cache/SyncService here (adminApp has neither
 * RTableDataProvider nor SyncService, and must not grow either). Produces the same
 * [TableManagementState] shared-ui's TableManagementContent already renders for composeApp, so the
 * two clients cannot drift on table-list behavior.
 */
class TableViewModel(
    private val rTableApiClient: RTableApiClient,
) : ViewModel() {
    private val log = Logger.withTag("tables")

    var state by mutableStateOf(TableManagementState())
        private set

    /**
     * Driven by the screen on entry (VM is retained app-wide). System tables (the virtual VOUCHER table)
     * are not listed: there is nothing an operator can do with them, and the server refuses to change
     * or delete one anyway.
     */
    fun refresh() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { rTableApiClient.getAllTables() }
                .onSuccess { data ->
                    state =
                        state.copy(
                            tables = data.tables.map { RTable.fromDto(it) }.filterNot { it.isSystem },
                            isLoading = false,
                        )
                }.onError(retry = ::refresh) { uiError ->
                    log.e { "load tables failed" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    // ----- Filter / search / sort / paging -----

    fun selectLocationFilter(locationTypeId: Long?) {
        state = state.copy(selectedLocationFilter = locationTypeId, currentPage = 0)
    }

    fun updateSearchQuery(query: String) {
        state = state.copy(searchQuery = query, currentPage = 0)
    }

    fun setSort(column: TableSortColumn) {
        val newAscending = if (state.sortColumn == column) !state.sortAscending else true
        state = state.copy(sortColumn = column, sortAscending = newAscending)
    }

    fun setPage(page: Int) {
        state = state.copy(currentPage = page)
    }

    // ----- Add/Edit dialog -----

    fun showAddDialog() {
        state = state.copy(showAddDialog = true, editingTable = null)
    }

    fun showEditDialog(table: RTable) {
        state = state.copy(showAddDialog = true, editingTable = table)
        loadCombinablePartners(table.id)
    }

    fun hideDialog() {
        state =
            state.copy(
                showAddDialog = false,
                editingTable = null,
                combinablePartnerIds = emptySet(),
                editingCombinablePartnerIds = emptySet(),
            )
    }

    fun addTable(request: RTableDto.CreateRTableRequestDto) {
        log.i { "creating table number=${request.tableNumber}" }
        viewModelScope.launch {
            runCatchingApi { rTableApiClient.createTable(request) }
                .onSuccess {
                    log.i { "table created number=${request.tableNumber}" }
                    hideDialog()
                    refresh()
                }.onError { uiError ->
                    log.e { "create table failed" }
                    state = state.copy(error = uiError)
                }
        }
    }

    fun updateTable(
        id: Long,
        request: RTableDto.CreateRTableRequestDto,
    ) {
        log.i { "updating table id=$id" }
        viewModelScope.launch {
            val updateRequest =
                RTableDto.UpdateRTableRequestDto(
                    tableNumber = request.tableNumber,
                    seats = request.seats,
                    locationTypeId = request.locationTypeId,
                    positionX = request.positionX,
                    positionY = request.positionY,
                    rotation = request.rotation,
                    width = request.width,
                    height = request.height,
                    assignmentPriority = request.assignmentPriority,
                )
            runCatchingApi { rTableApiClient.updateTable(id, updateRequest) }
                .onSuccess {
                    log.i { "table updated id=$id" }
                    saveCombinablePartners(id)
                    hideDialog()
                    refresh()
                }.onError { uiError ->
                    log.e { "update table failed id=$id" }
                    state = state.copy(error = uiError)
                }
        }
    }

    fun deleteTable(id: Long) {
        log.i { "deleting table id=$id" }
        viewModelScope.launch {
            runCatchingApi { rTableApiClient.deleteTable(id) }
                .onSuccess {
                    log.i { "table deleted id=$id" }
                    refresh()
                }.onError { uiError ->
                    log.e { "delete table failed id=$id" }
                    state = state.copy(error = uiError)
                }
        }
    }

    // ----- Combinable partners (edit mode only) -----

    fun toggleCombinablePartner(partnerId: Long) {
        val current = state.editingCombinablePartnerIds
        state =
            state.copy(
                editingCombinablePartnerIds = if (partnerId in current) current - partnerId else current + partnerId,
            )
    }

    /** Drops the unsaved picks: the staged selection returns to the saved one (the picker was cancelled). */
    fun resetCombinableSelection() {
        state = state.copy(editingCombinablePartnerIds = state.combinablePartnerIds)
    }

    /** Clears the staged combinable-partner selection when the table's edit dialog closes. */
    fun clearCombinablePartners() {
        state = state.copy(combinablePartnerIds = emptySet(), editingCombinablePartnerIds = emptySet())
    }

    /**
     * Loads a table's combinable partners into the staged selection. Public so the focused
     * per-row picker can drive it directly (there is no add/edit dialog to piggyback on anymore).
     */
    fun loadCombinablePartners(tableId: Long) {
        viewModelScope.launch {
            runCatchingApi { rTableApiClient.getCombinablePartners(tableId) }
                .onSuccess { partners ->
                    val partnerSet = partners.toSet()
                    state = state.copy(combinablePartnerIds = partnerSet, editingCombinablePartnerIds = partnerSet)
                }.onError { uiError -> log.e { "load combinable partners failed tableId=$tableId: $uiError" } }
        }
    }

    /**
     * Persists the staged combinable-partner selection if it differs from the loaded set. Public so
     * the focused per-row picker can persist on confirm, independently of an inline row edit.
     */
    fun saveCombinablePartners(tableId: Long) {
        val editingIds = state.editingCombinablePartnerIds
        val originalIds = state.combinablePartnerIds
        if (editingIds == originalIds) return
        viewModelScope.launch {
            runCatchingApi { rTableApiClient.setCombinablePartners(tableId, editingIds.toList()) }
                .onSuccess {
                    log.i { "combinable partners saved tableId=$tableId" }
                    // The edit dialog behind the picker shows the saved count.
                    state = state.copy(combinablePartnerIds = editingIds)
                }.onError { uiError -> log.e { "save combinable partners failed tableId=$tableId: $uiError" } }
        }
    }
}
