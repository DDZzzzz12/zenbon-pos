package org.example.project.admin.features.floorplan.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.RoomElement
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.data.remote.RoomElementApiClient
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/**
 * Editor ViewModel for one location's floor plan. Unlike composeApp's cache-backed
 * FloorPlanViewModel, this owns no local cache/SyncService: it reads tables + room elements from the
 * API, stages drag moves in memory (tracked via the dirty sets in [FloorPlanEditorState]), and on
 * [save] persists only what changed — one [RTableApiClient.batchUpdatePositions] for the moved
 * tables plus one [RoomElementApiClient.updateRoomElementPosition] per moved element.
 */
class FloorPlanEditorViewModel(
    private val rTableApiClient: RTableApiClient,
    private val roomElementApiClient: RoomElementApiClient,
) : ViewModel() {
    private val log = Logger.withTag("floorplan-editor")

    var state by mutableStateOf(FloorPlanEditorState())
        private set

    fun load(locationTypeId: Long) {
        state = state.copy(locationTypeId = locationTypeId, isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                val tables =
                    rTableApiClient
                        .getAllTables()
                        .tables
                        .map { RTable.fromDto(it) }
                        .filter { it.locationTypeId == locationTypeId }
                val elements =
                    roomElementApiClient
                        .getAllRoomElements()
                        .elements
                        .map { RoomElement.fromDto(it) }
                        .filter { it.locationTypeId == locationTypeId }
                tables to elements
            }.onSuccess { (tables, elements) ->
                state =
                    state.copy(
                        tables = tables,
                        elements = elements,
                        dirtyTableIds = emptySet(),
                        dirtyElementIds = emptySet(),
                        isLoading = false,
                    )
            }.onError(retry = { load(locationTypeId) }) { e ->
                log.e { "load failed" }
                state = state.copy(isLoading = false, error = e)
            }
        }
    }

    /**
     * Drops the staged moves/resizes (the user chose Discard). Only the dirty marks are cleared; the
     * positions themselves are replaced by the reload every leave path ends in (screen re-entry or
     * location switch), so this deliberately issues no request of its own that could race that load.
     */
    fun discardDraft() {
        state = state.copy(dirtyTableIds = emptySet(), dirtyElementIds = emptySet(), selection = null)
    }

    fun select(sel: FloorPlanSelection?) {
        state = state.copy(selection = sel)
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun moveTable(
        id: Long,
        gridX: Int,
        gridY: Int,
    ) {
        state =
            state.copy(
                tables = state.tables.map { if (it.id == id) it.copy(gridX = gridX, gridY = gridY) else it },
                dirtyTableIds = state.dirtyTableIds + id,
            )
    }

    fun moveElement(
        id: Long,
        gridX: Int,
        gridY: Int,
    ) {
        state =
            state.copy(
                elements = state.elements.map { if (it.id == id) it.copy(gridX = gridX, gridY = gridY) else it },
                dirtyElementIds = state.dirtyElementIds + id,
            )
    }

    /** Resize the table's grid footprint. Each side is clamped to ≥2 to match the floor's minimum cell. */
    fun resizeTable(
        id: Long,
        gridWidth: Int,
        gridHeight: Int,
    ) {
        state =
            state.copy(
                tables =
                    state.tables.map {
                        if (it.id == id) {
                            it.copy(gridWidth = gridWidth.coerceAtLeast(2), gridHeight = gridHeight.coerceAtLeast(2))
                        } else {
                            it
                        }
                    },
                dirtyTableIds = state.dirtyTableIds + id,
            )
    }

    /** Rotate the table, normalising the angle into [0, 360). */
    fun rotateTable(
        id: Long,
        rotation: Float,
    ) {
        val normalised = normaliseRotation(rotation)
        state =
            state.copy(
                tables = state.tables.map { if (it.id == id) it.copy(rotation = normalised) else it },
                dirtyTableIds = state.dirtyTableIds + id,
            )
    }

    /** Resize the element's grid footprint. Each side is clamped to ≥1. */
    fun resizeElement(
        id: Long,
        gridWidth: Int,
        gridHeight: Int,
    ) {
        state =
            state.copy(
                elements =
                    state.elements.map {
                        if (it.id == id) {
                            it.copy(gridWidth = gridWidth.coerceAtLeast(1), gridHeight = gridHeight.coerceAtLeast(1))
                        } else {
                            it
                        }
                    },
                dirtyElementIds = state.dirtyElementIds + id,
            )
    }

    /** Rotate the element, normalising the angle into [0, 360). */
    fun rotateElement(
        id: Long,
        rotation: Float,
    ) {
        val normalised = normaliseRotation(rotation)
        state =
            state.copy(
                elements = state.elements.map { if (it.id == id) it.copy(rotation = normalised) else it },
                dirtyElementIds = state.dirtyElementIds + id,
            )
    }

    private fun normaliseRotation(rotation: Float): Float = ((rotation % 360f) + 360f) % 360f

    fun save() {
        val locationTypeId = state.locationTypeId ?: return
        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                val dirtyTables = state.tables.filter { it.id in state.dirtyTableIds }
                if (dirtyTables.isNotEmpty()) {
                    rTableApiClient.batchUpdatePositions(
                        RTableDto.BatchTablePositionUpdateDto(
                            updates =
                                dirtyTables.map {
                                    RTableDto.TablePositionUpdate(
                                        tableId = it.id,
                                        positionX = (it.gridX ?: 0).toFloat(),
                                        positionY = (it.gridY ?: 0).toFloat(),
                                        rotation = it.rotation ?: 0f,
                                        width = (it.gridWidth ?: 2).toFloat(),
                                        height = (it.gridHeight ?: 2).toFloat(),
                                    )
                                },
                        ),
                    )
                }
                state.elements.filter { it.id in state.dirtyElementIds }.forEach { el ->
                    roomElementApiClient.updateRoomElementPosition(
                        el.id,
                        RoomElementDto.UpdateRoomElementPositionRequestDto(
                            positionX = el.gridX.toFloat(),
                            positionY = el.gridY.toFloat(),
                            rotation = el.rotation,
                        ),
                    )
                    // Element dimensions live on a separate endpoint (unlike tables, where width/height ride
                    // the batch position update). Writing both for every dirty element keeps save() simple —
                    // a redundant dimensions write on a pure-move is harmless.
                    roomElementApiClient.updateRoomElementDimensions(
                        el.id,
                        RoomElementDto.UpdateRoomElementDimensionsRequestDto(
                            width = el.gridWidth.toFloat(),
                            height = el.gridHeight.toFloat(),
                        ),
                    )
                }
            }.onSuccess {
                state = state.copy(isSaving = false)
                load(locationTypeId)
            }.onError { e ->
                log.e { "save failed" }
                state = state.copy(isSaving = false, error = e)
            }
        }
    }

    /**
     * Renames the table and sets its seats ("Nummer und Plätze ändern"), then reloads. The reload drops
     * staged moves, so the screen asks the leave guard first.
     */
    fun updateTableNumberAndSeats(
        id: Long,
        tableNumber: String,
        seats: Int?,
    ) {
        val locationTypeId = state.locationTypeId ?: return
        viewModelScope.launch {
            runCatchingApi {
                rTableApiClient.updateTable(id, RTableDto.UpdateRTableRequestDto(tableNumber = tableNumber, seats = seats))
            }.onSuccess { load(locationTypeId) }
                .onError { e ->
                    log.e { "update table number/seats failed" }
                    state = state.copy(error = e)
                }
        }
    }

    fun addTable(
        tableNumber: String,
        seats: Int?,
        gridX: Int,
        gridY: Int,
    ) {
        val locationTypeId = state.locationTypeId ?: return
        viewModelScope.launch {
            runCatchingApi {
                rTableApiClient.createTable(
                    RTableDto.CreateRTableRequestDto(
                        tableNumber = tableNumber,
                        seats = seats,
                        locationTypeId = locationTypeId,
                        positionX = gridX.toFloat(),
                        positionY = gridY.toFloat(),
                    ),
                )
            }.onSuccess { load(locationTypeId) }
                .onError { e ->
                    log.e { "add table failed" }
                    state = state.copy(error = e)
                }
        }
    }

    fun addElement(
        elementType: String,
        gridX: Int,
        gridY: Int,
        gridWidth: Int,
        gridHeight: Int,
    ) {
        val locationTypeId = state.locationTypeId ?: return
        viewModelScope.launch {
            runCatchingApi {
                roomElementApiClient.createRoomElement(
                    RoomElementDto.CreateRoomElementRequestDto(
                        elementType = elementType,
                        positionX = gridX.toFloat(),
                        positionY = gridY.toFloat(),
                        width = gridWidth.toFloat(),
                        height = gridHeight.toFloat(),
                        locationTypeId = locationTypeId,
                    ),
                )
            }.onSuccess { load(locationTypeId) }
                .onError { e ->
                    log.e { "add element failed" }
                    state = state.copy(error = e)
                }
        }
    }

    fun deleteSelected() {
        val locationTypeId = state.locationTypeId ?: return
        when (val sel = state.selection) {
            is FloorPlanSelection.Table ->
                viewModelScope.launch {
                    runCatchingApi { rTableApiClient.deleteTable(sel.id) }
                        .onSuccess {
                            state = state.copy(selection = null)
                            load(locationTypeId)
                        }.onError { e ->
                            log.e { "delete table failed" }
                            state = state.copy(error = e)
                        }
                }
            is FloorPlanSelection.Element ->
                viewModelScope.launch {
                    runCatchingApi { roomElementApiClient.deleteRoomElement(sel.id) }
                        .onSuccess {
                            state = state.copy(selection = null)
                            load(locationTypeId)
                        }.onError { e ->
                            log.e { "delete element failed" }
                            state = state.copy(error = e)
                        }
                }
            null -> Unit
        }
    }
}
