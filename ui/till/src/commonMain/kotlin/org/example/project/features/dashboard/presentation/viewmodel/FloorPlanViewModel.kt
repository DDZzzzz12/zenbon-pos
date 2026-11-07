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
import org.example.project.core.data.provider.RoomElementDataProvider
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.utils.GridConverter
import org.example.project.features.dashboard.presentation.state.FloorPlanUiState
import org.example.project.features.dashboard.presentation.util.patchTableStatus
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent

/**
 * ViewModel for the floor plan screen.
 *
 * Manages the state and business logic for the read-only floor plan view,
 * including loading tables and room elements, handling viewport transformations
 * (zoom/pan) and combine-mode table connections. Layout authoring lives in the
 * web admin panel, not on the till.
 *
 * This ViewModel follows the unidirectional data flow pattern with StateFlow,
 * ensuring predictable state management and easy testability.
 *
 * @property rTableManager Manager for restaurant table data operations
 * @property roomElementManager Manager for room element data operations
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
class FloorPlanViewModel(
    private val rTableDataProvider: RTableDataProvider,
    private val roomElementDataProvider: RoomElementDataProvider,
    private val rTableApiClient: RTableApiClient,
    private val cacheChangeBus: CacheChangeBus,
) : ViewModel() {
    private val logger = Logger.withTag("FloorPlanViewModel")

    private val _uiState = MutableStateFlow(FloorPlanUiState())
    val uiState: StateFlow<FloorPlanUiState> = _uiState.asStateFlow()

    /** Location type currently displayed — tracked so cache-change signals re-read the right slice. */
    private var currentLocationTypeId: Long? = null

    init {
        viewModelScope.launch {
            cacheChangeBus.changes.collect { change ->
                val locId = currentLocationTypeId ?: return@collect
                if ((
                        change.entityType == ChangeEntityType.RTABLE ||
                            change.entityType == ChangeEntityType.ROOM_ELEMENT
                    ) &&
                    (change.locationTypeId == null || change.locationTypeId == locId)
                ) {
                    reloadFromCache(locId)
                }
            }
        }
    }

    /**
     * Loads all floor plan data from local database.
     *
     * Fetches both restaurant tables and room elements (walls, doors, windows, etc.)
     * and updates the UI state. Shows loading indicator during fetch and handles
     * errors gracefully.
     *
     * Data is stored using grid coordinates (80x50 grid) which are converted to
     * pixels at render time based on screen dimensions.
     */
    fun loadFloorPlanData(locationTypeId: Long) {
        logger.i { "FloorPlanViewModel.loadFloorPlanData: loading for locationTypeId=$locationTypeId" }
        currentLocationTypeId = locationTypeId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Seed both caches once per session (server-touching, failures are non-blocking —
            // surface the error but keep showing last cached data rather than clearing the cache).
            runCatchingApi { rTableDataProvider.ensureSeeded(locationTypeId) }
                .onError { uiError ->
                    logger.e { "loadFloorPlanData seed tables failed: $uiError" }
                    _uiState.update { it.copy(error = uiError, isLoading = false) }
                }
            runCatchingApi { roomElementDataProvider.ensureSeeded(locationTypeId) }
                .onError { uiError ->
                    logger.e { "loadFloorPlanData seed elements failed: $uiError" }
                    _uiState.update { it.copy(error = uiError, isLoading = false) }
                }

            val tables = rTableDataProvider.getRTablesByLocationTypeId(locationTypeId)
            val roomElements = roomElementDataProvider.getElementsByLocationType(locationTypeId.toString())

            logger.i { "FloorPlanViewModel.loadFloorPlanData: loaded ${tables.size} tables, ${roomElements.size} elements from cache" }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    tables = tables,
                    roomElements = roomElements,
                )
            }
        }
    }

    /**
     * Apply a live table-status push to the shown floor plan in place — no server call. Needed
     * because the cache write-through that would otherwise signal this VM does not exist in the
     * wasm build (no local database); see [TableGridViewModel.onTableStatusChanged].
     */
    fun onTableStatusChanged(event: NotificationUiEvent.TableStatusChanged) {
        _uiState.update { state ->
            state.copy(
                tables =
                    state.tables.patchTableStatus(
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
     * Re-read both cached snapshots for [locationTypeId] without a server call and without
     * toggling the loading spinner. Triggered by a [CacheChangeBus] signal.
     */
    private fun reloadFromCache(locationTypeId: Long) {
        viewModelScope.launch {
            val tables = rTableDataProvider.getRTablesByLocationTypeId(locationTypeId)
            val roomElements = roomElementDataProvider.getElementsByLocationType(locationTypeId.toString())
            _uiState.update {
                it.copy(tables = tables, roomElements = roomElements)
            }
        }
    }

    /**
     * Force-refresh both providers from the server for [locationTypeId]. Each provider's refresh
     * replaces its cache and emits a [CacheChange] → the collector re-reads.
     */
    fun refresh(locationTypeId: Long) {
        currentLocationTypeId = locationTypeId
        viewModelScope.launch {
            runCatchingApi { rTableDataProvider.refresh(locationTypeId) }
                .onError { uiError ->
                    logger.e { "FloorPlanViewModel.refresh tables failed: $uiError" }
                    _uiState.update { it.copy(error = uiError, isLoading = false) }
                }
            runCatchingApi { roomElementDataProvider.refresh(locationTypeId) }
                .onError { uiError ->
                    logger.e { "FloorPlanViewModel.refresh elements failed: $uiError" }
                    _uiState.update { it.copy(error = uiError, isLoading = false) }
                }
            reloadFromCache(locationTypeId)
        }
    }

    /**
     * Updates the zoom level by a newZoom amount.
     *
     * Applies zoom with constraints to prevent extreme zoom levels.
     * Range: 50% (0.5x) to 500% (5.0x)
     * Default: 100% (1.0x)
     *
     * @param newZoom Zoom newZoom to apply (positive = zoom in, negative = zoom out)
     */
    fun updateZoom(newZoom: Float) {
        _uiState.update { currentState ->
            currentState.copy(zoomLevel = newZoom)
        }
    }

    /**
     * Updates the pan offset to new absolute positions.
     *
     * Sets the viewport pan to the specified pixel offsets. The offsets are
     * calculated and constrained by the gesture handlers before being passed here.
     *
     * @param newPanX New horizontal pan offset in pixels (absolute position)
     * @param newPanY New vertical pan offset in pixels (absolute position)
     */
    fun updatePan(
        newPanX: Float,
        newPanY: Float,
    ) {
        _uiState.update { currentState ->
            currentState.copy(
                panOffsetX = newPanX,
                panOffsetY = newPanY,
            )
        }
    }

    /**
     * Updates the grid cell dimensions based on canvas dimensions.
     *
     * Call this whenever the canvas/viewport size changes (e.g., window resize)
     * to recalculate grid cell dimensions for responsive layout. The dimensions are
     * calculated to fit the 80x50 grid within the available space.
     *
     * @param width New canvas width in pixels
     * @param height New canvas height in pixels
     */
    fun updateGridCellDimensions(
        width: Float,
        height: Float,
    ) {
        val newDimensions = GridConverter.calculateCellDimensions(width, height)
        _uiState.update { currentState ->
            currentState.copy(gridCellDimensions = newDimensions)
        }
    }

    /**
     * Resets the viewport to default zoom and pan.
     *
     * Useful for "reset view" functionality.
     */
    fun resetViewport() {
        _uiState.update {
            it.copy(
                zoomLevel = 1.0f,
                panOffsetX = 0f,
                panOffsetY = 0f,
            )
        }
    }

    // --- Combine Mode Methods ---

    fun toggleCombineMode() {
        val current = _uiState.value.isCombineMode
        if (!current) {
            _uiState.update { it.copy(isCombineMode = true) }
            loadCombineConnections()
        } else {
            if (_uiState.value.combineConnectionsModified) {
                saveCombineConnections()
            }
            _uiState.update {
                it.copy(
                    isCombineMode = false,
                    combiningFromTableId = null,
                    combineHoverTableId = null,
                    combineConnectionsModified = false,
                )
            }
        }
    }

    fun startCombineDrag(tableId: Long) {
        _uiState.update { it.copy(combiningFromTableId = tableId) }
    }

    fun updateCombineDragTarget(
        x: Float,
        y: Float,
    ) {
        _uiState.update { it.copy(combineLineEndX = x, combineLineEndY = y) }
    }

    fun updateCombineHoverTable(tableId: Long?) {
        _uiState.update { it.copy(combineHoverTableId = tableId) }
    }

    fun endCombineDrag(targetTableId: Long? = null) {
        val state = _uiState.value
        val sourceId = state.combiningFromTableId ?: return
        val effectiveTarget = targetTableId ?: state.combineHoverTableId

        if (effectiveTarget != null && effectiveTarget != sourceId) {
            val sourceTable = state.tables.find { it.id == sourceId }
            val targetTable = state.tables.find { it.id == effectiveTarget }

            if (sourceTable != null &&
                targetTable != null &&
                sourceTable.locationTypeId == targetTable.locationTypeId &&
                sourceTable.isActive &&
                targetTable.isActive &&
                !sourceTable.isVirtual &&
                !targetTable.isVirtual
            ) {
                val connections = state.combineConnections.toMutableMap()
                val sourcePartners = (connections[sourceId] ?: emptySet()).toMutableSet()
                val targetPartners = (connections[effectiveTarget] ?: emptySet()).toMutableSet()

                if (sourcePartners.contains(effectiveTarget)) {
                    sourcePartners.remove(effectiveTarget)
                    targetPartners.remove(sourceId)
                } else {
                    sourcePartners.add(effectiveTarget)
                    targetPartners.add(sourceId)
                }

                connections[sourceId] = sourcePartners
                connections[effectiveTarget] = targetPartners

                _uiState.update {
                    it.copy(combineConnections = connections, combineConnectionsModified = true)
                }
            }
        }

        _uiState.update {
            it.copy(combiningFromTableId = null, combineHoverTableId = null)
        }
    }

    private fun loadCombineConnections() {
        viewModelScope.launch {
            runCatchingApi { rTableApiClient.getAllCombinablePairs() }
                .onSuccess { pairs ->
                    val connections = mutableMapOf<Long, MutableSet<Long>>()
                    for (pair in pairs) {
                        connections.getOrPut(pair.tableAId) { mutableSetOf() }.add(pair.tableBId)
                        connections.getOrPut(pair.tableBId) { mutableSetOf() }.add(pair.tableAId)
                    }
                    _uiState.update { it.copy(combineConnections = connections) }
                }.onError { uiError ->
                    logger.e { "Failed to load combinable pairs: $uiError" }
                }
        }
    }

    private fun saveCombineConnections() {
        viewModelScope.launch {
            val connections = _uiState.value.combineConnections
            // Build unique pairs from bidirectional map
            val pairs = mutableSetOf<Pair<Long, Long>>()
            for ((tableId, partners) in connections) {
                for (partnerId in partners) {
                    val pair = if (tableId < partnerId) tableId to partnerId else partnerId to tableId
                    pairs.add(pair)
                }
            }

            val pairDtos = pairs.map { RTableDto.CombinablePairDto(it.first, it.second) }
            runCatchingApi { rTableApiClient.setAllCombinablePairs(pairDtos) }
                .onSuccess { saved ->
                    logger.i { "Saved ${saved.size} combinable pairs" }
                }.onError { uiError ->
                    logger.e { "Failed to save combinable pairs: $uiError" }
                }
        }
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
