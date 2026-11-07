package org.example.project.core.data.domain.impl.service

import org.example.project.core.data.domain.service.FloorPlanSyncResult
import org.example.project.core.data.domain.service.FloorPlanSyncService
import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.RoomElement
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.data.remote.RoomElementApiClient
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/**
 * Floor plan synchronization service implementation.
 *
 * Coordinates synchronization between in-memory cache and server API,
 * handling creates, updates, and deletes for both tables and room elements.
 *
 * @property rTableApiClient API client for table operations
 * @property roomElementApiClient API client for room element operations
 */
class FloorPlanSyncServiceImpl(
    private val rTableApiClient: RTableApiClient,
    private val roomElementApiClient: RoomElementApiClient,
) : FloorPlanSyncService {
    override suspend fun syncFloorPlanChanges(
        newTables: List<RTable>,
        updatedTables: List<RTable>,
        deletedTables: List<RTable>,
        newElements: List<RoomElement>,
        updatedElements: List<RoomElement>,
        deletedElements: List<RoomElement>,
    ): FloorPlanSyncResult {
        val errors = mutableListOf<String>()
        val newTableIds = mutableMapOf<Long, Long>()
        val newElementIds = mutableMapOf<Long, Long>()

        var tablesCreated = 0
        var tablesUpdated = 0
        var tablesDeleted = 0
        var elementsCreated = 0
        var elementsUpdated = 0
        var elementsDeleted = 0

        for (table in newTables) {
            val createRequest =
                RTableDto.CreateRTableRequestDto(
                    tableNumber = table.tableNumber,
                    seats = table.seats.toInt(),
                    locationTypeId = table.locationTypeId,
                    positionX = table.gridX?.toFloat() ?: 0f,
                    positionY = table.gridY?.toFloat() ?: 0f,
                    rotation = table.rotation ?: 0f,
                    width = table.gridWidth?.toFloat() ?: 100f,
                    height = table.gridHeight?.toFloat() ?: 100f,
                )
            runCatchingApi { rTableApiClient.createTable(createRequest) }
                .onSuccess { created ->
                    newTableIds[table.id] = created.id
                    tablesCreated++
                }.onError { uiError ->
                    errors.add("Failed to create table ${table.tableNumber}: $uiError")
                }
        }

        val tablesToUpdate = updatedTables.filter { it.id > 0 }
        if (tablesToUpdate.isNotEmpty()) {
            val batchRequest =
                RTableDto.BatchTablePositionUpdateDto(
                    updates =
                        tablesToUpdate.mapNotNull { table ->
                            val gridX = table.gridX
                            val gridY = table.gridY
                            val rotation = table.rotation
                            val gridWidth = table.gridWidth
                            val gridHeight = table.gridHeight
                            if (gridX != null &&
                                gridY != null &&
                                rotation != null &&
                                gridWidth != null &&
                                gridHeight != null
                            ) {
                                RTableDto.TablePositionUpdate(
                                    tableId = table.id,
                                    positionX = gridX.toFloat(),
                                    positionY = gridY.toFloat(),
                                    rotation = rotation,
                                    width = gridWidth.toFloat(),
                                    height = gridHeight.toFloat(),
                                )
                            } else {
                                null
                            }
                        },
                )
            runCatchingApi { rTableApiClient.batchUpdatePositions(batchRequest) }
                .onSuccess { resp -> tablesUpdated = resp.updatedCount }
                .onError { uiError -> errors.add("Failed to sync table positions: $uiError") }
        }

        for (table in deletedTables.filter { it.id > 0 }) {
            runCatchingApi { rTableApiClient.deleteTable(table.id) }
                .onSuccess { tablesDeleted++ }
                .onError { uiError -> errors.add("Failed to delete table ${table.tableNumber}: $uiError") }
        }

        for (element in newElements) {
            val createRequest =
                RoomElementDto.CreateRoomElementRequestDto(
                    elementType = element.elementType,
                    positionX = element.gridX.toFloat(),
                    positionY = element.gridY.toFloat(),
                    width = element.gridWidth.toFloat(),
                    height = element.gridHeight.toFloat(),
                    rotation = element.rotation,
                    locationTypeId = element.locationTypeId,
                    metadata = element.metadata,
                )
            runCatchingApi { roomElementApiClient.createRoomElement(createRequest) }
                .onSuccess { created ->
                    newElementIds[element.id] = created.id
                    elementsCreated++
                }.onError { uiError -> errors.add("Failed to create room element: $uiError") }
        }

        for (element in updatedElements.filter { it.id > 0 }) {
            val updateRequest =
                RoomElementDto.UpdateRoomElementRequestDto(
                    elementType = element.elementType,
                    positionX = element.gridX.toFloat(),
                    positionY = element.gridY.toFloat(),
                    width = element.gridWidth.toFloat(),
                    height = element.gridHeight.toFloat(),
                    rotation = element.rotation,
                    locationTypeId = element.locationTypeId,
                    metadata = element.metadata,
                )
            runCatchingApi { roomElementApiClient.updateRoomElement(element.id, updateRequest) }
                .onSuccess { elementsUpdated++ }
                .onError { uiError -> errors.add("Failed to update room element ${element.id}: $uiError") }
        }

        for (element in deletedElements.filter { it.id > 0 }) {
            runCatchingApi { roomElementApiClient.deleteRoomElement(element.id) }
                .onSuccess { elementsDeleted++ }
                .onError { uiError -> errors.add("Failed to delete room element ${element.id}: $uiError") }
        }

        return FloorPlanSyncResult(
            tablesCreated = tablesCreated,
            tablesUpdated = tablesUpdated,
            tablesDeleted = tablesDeleted,
            elementsCreated = elementsCreated,
            elementsUpdated = elementsUpdated,
            elementsDeleted = elementsDeleted,
            newTableIds = newTableIds,
            newElementIds = newElementIds,
            errors = errors,
        )
    }
}
