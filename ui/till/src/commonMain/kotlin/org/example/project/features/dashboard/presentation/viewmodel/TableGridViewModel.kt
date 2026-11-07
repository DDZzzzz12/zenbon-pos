package org.example.project.features.dashboard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.data.cache.CacheChangeBus
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.dashboard.presentation.state.TableGridUiState
import org.example.project.features.dashboard.presentation.util.patchTableStatus
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent
import org.example.project.features.settings.data.remote.LocationTypeApiClient

/**
 * ViewModel for Dashboard screen.
 *
 * Manages restaurant table display state, including loading tables from
 * local database, grouping by table type, and handling table type selection.
 */
class TableGridViewModel(
    private val rTableDataProvider: RTableDataProvider,
    private val locationTypeApiClient: LocationTypeApiClient,
    private val cacheChangeBus: CacheChangeBus,
) : ViewModel() {
    private val logger = Logger.withTag("TableGridViewModel")

    private val _uiState = MutableStateFlow(TableGridUiState())
    val uiState: StateFlow<TableGridUiState> = _uiState.asStateFlow()

    init {
        loadLocationTypes()
        viewModelScope.launch {
            cacheChangeBus.changes.collect { change ->
                if (change.entityType == ChangeEntityType.RTABLE &&
                    (change.locationTypeId == null || change.locationTypeId == _uiState.value.selectedLocationTypeId)
                ) {
                    reloadFromCache()
                }
            }
        }
    }

    /**
     * Load available non-virtual location types, then load tables for the first type.
     */
    fun loadLocationTypes() {
        viewModelScope.launch {
            logger.i { "TableGridViewModel.loadLocationTypes: fetching non-virtual location types" }
            _uiState.update { it.copy(isLoading = true, error = null) }

            runCatchingApi { locationTypeApiClient.getAllLocationTypes() }
                .onSuccess { data ->
                    val nonVirtual =
                        data.locationTypes
                            .filter { !it.isVirtual or it.isTakeaway }
                            .sortedBy { it.sortOrder }
                    logger.i { "TableGridViewModel.loadLocationTypes: loaded ${nonVirtual.size} non-virtual location types" }
                    val selectedId =
                        if (nonVirtual.any { it.id == _uiState.value.selectedLocationTypeId }) {
                            _uiState.value.selectedLocationTypeId
                        } else {
                            nonVirtual.firstOrNull()?.id ?: _uiState.value.selectedLocationTypeId
                        }
                    _uiState.update {
                        it.copy(locationTypes = nonVirtual, selectedLocationTypeId = selectedId)
                    }
                }.onError { uiError ->
                    logger.e { "TableGridViewModel.loadLocationTypes: failed to load location types — $uiError" }
                    _uiState.update {
                        it.copy(isLoading = false, error = uiError)
                    }
                }
        }
    }

    /**
     * Load tables for the selected location type: seed the cache once per session
     * (server-touching, failures are non-blocking), then read the cached snapshot into state.
     */
    fun loadTables() {
        viewModelScope.launch {
            logger.i { "TableGridViewModel.loadTables: loading tables for locationTypeId=${_uiState.value.selectedLocationTypeId}" }
            _uiState.update { it.copy(isLoading = true, error = null) }

            runCatchingApi {
                rTableDataProvider.ensureSeeded(
                    _uiState.value.selectedLocationTypeId,
                    _uiState.value.showInactive,
                )
            }.onError { uiError ->
                // Seeding failed (network) — keep showing last cached data with a non-blocking error.
                logger.e { "TableGridViewModel.loadTables: ensureSeeded failed — $uiError" }
                _uiState.update { it.copy(error = uiError) }
            }

            val tables =
                rTableDataProvider.getRTablesByLocationTypeId(
                    _uiState.value.selectedLocationTypeId,
                    includeInactive = _uiState.value.showInactive,
                )
            logger.i { "TableGridViewModel.loadTables: loaded ${tables.size} tables from cache" }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    tablesByType = tables.sortedBy { t -> t.tableNumber.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 },
                )
            }
        }
    }

    /**
     * Re-read the cached snapshot for the selected location type without a server call
     * and without toggling the loading spinner. Triggered by a [CacheChangeBus] signal.
     */
    private fun reloadFromCache() {
        viewModelScope.launch {
            val tables =
                rTableDataProvider.getRTablesByLocationTypeId(
                    _uiState.value.selectedLocationTypeId,
                    includeInactive = _uiState.value.showInactive,
                )
            _uiState.update {
                it.copy(
                    tablesByType = tables.sortedBy { t -> t.tableNumber.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 },
                )
            }
        }
    }

    /**
     * Force-refresh from the server for the selected location type. The provider's refresh
     * replaces the cache and emits a [CacheChange] → the collector re-reads.
     */
    fun refresh() {
        viewModelScope.launch {
            runCatchingApi {
                rTableDataProvider.refresh(
                    _uiState.value.selectedLocationTypeId,
                    _uiState.value.showInactive,
                )
            }.onError { uiError ->
                logger.e { "TableGridViewModel.refresh: failed — $uiError" }
                _uiState.update { it.copy(error = uiError) }
            }
            reloadFromCache()
        }
    }

    /**
     * Apply a live table-status push (another device opened, ordered on or paid a table) to the
     * shown grid in place — no server call, no spinner.
     *
     * The cache write-through ([org.example.project.core.data.cache.TableStatusCacheWriter]) only
     * exists where there is a local database: the wasm build has none, so there nothing signalled
     * this VM and the grid stayed stale until the manual refresh. Patching here works on every
     * platform; where the cache exists its later re-read yields the same row.
     */
    fun onTableStatusChanged(event: NotificationUiEvent.TableStatusChanged) {
        _uiState.update { state ->
            state.copy(
                tablesByType =
                    state.tablesByType.patchTableStatus(
                        tableId = event.tableId,
                        status = event.status.name,
                        openAmount = event.openAmount,
                        employeeNumber = event.employeeNumber,
                        sessionId = event.sessionId,
                        isTraining = event.isTraining,
                    ),
            )
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Select a location type tab by ID.
     *
     * @param locationTypeId The location type ID to display
     */
    fun selectTableType(locationTypeId: Long) {
        _uiState.update { it.copy(selectedLocationTypeId = locationTypeId) }
        loadTables()
    }

    /**
     * Toggle showing inactive (soft-deleted) tables.
     */
    fun toggleShowInactive() {
        // Flip the flag in state FIRST so refresh() force-fetches with the new value.
        // loadTables() would no-op here (the location is already seeded with active-only
        // rows), so a force refresh is required to bring inactive tables into the cache.
        _uiState.update { it.copy(showInactive = !it.showInactive) }
        refresh()
    }
}
