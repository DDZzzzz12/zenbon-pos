package org.example.project.admin.features.floorplan

import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.data.remote.RoomElementApiClient
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.core.domain.dto.table.TableSessionDto

/**
 * Builds an [RTableDto.RTableResponseDto] positioned at grid ([gx], [gy]) for the editor tests.
 * Non-position fields carry sane defaults; only id/location/position matter to the editor.
 */
fun table(
    id: Long,
    gx: Int,
    gy: Int,
    locationTypeId: Long = 1L,
) = RTableDto.RTableResponseDto(
    id = id,
    tableNumber = "T$id",
    seats = 4,
    locationTypeId = locationTypeId,
    locationTypeName = "Indoor",
    locationTypeEnglishName = "Indoor",
    locationTypeChineseName = "室内",
    isVirtual = false,
    isTakeaway = false,
    tableStatus = "FREE",
    currentSessionId = null,
    hasActiveSession = false,
    positionX = gx.toFloat(),
    positionY = gy.toFloat(),
    rotation = 0f,
    width = 2f,
    height = 2f,
)

/** Builds a [RoomElementDto.RoomElementResponseDto] positioned at grid ([gx], [gy]). */
fun element(
    id: Long,
    gx: Int,
    gy: Int,
    locationTypeId: Long = 1L,
) = RoomElementDto.RoomElementResponseDto(
    id = id,
    elementType = "WALL",
    positionX = gx.toFloat(),
    positionY = gy.toFloat(),
    width = 1f,
    height = 1f,
    rotation = 0f,
    locationTypeId = locationTypeId,
)

/**
 * Recording fake for [RTableApiClient]. Only the surface [FloorPlanEditorViewModel] touches
 * (list / batch-position / create / delete) does real work; everything else is left unimplemented.
 */
class FakeFloorPlanRTableApiClient(
    var tables: List<RTableDto.RTableResponseDto> = emptyList(),
    private val onError: (() -> Nothing)? = null,
) : RTableApiClient {
    val batchCalls: MutableList<RTableDto.BatchTablePositionUpdateDto> = mutableListOf()
    val deleted: MutableList<Long> = mutableListOf()
    var created: RTableDto.CreateRTableRequestDto? = null
    val updates: MutableList<Pair<Long, RTableDto.UpdateRTableRequestDto>> = mutableListOf()

    override suspend fun getAllTables(
        status: String?,
        locationTypeId: Long?,
        limit: Int?,
        offset: Int?,
        includeInactive: Boolean,
    ): RTableDto.RTableListResponseDto {
        onError?.invoke()
        return RTableDto.RTableListResponseDto(
            tables = tables,
            totalCount = tables.size,
            returnedCount = tables.size,
            appliedFilters = RTableDto.FilterInfo(),
        )
    }

    override suspend fun batchUpdatePositions(request: RTableDto.BatchTablePositionUpdateDto): RTableDto.BatchUpdatePositionsResponseDto {
        batchCalls += request
        return RTableDto.BatchUpdatePositionsResponseDto(
            success = true,
            updatedCount = request.updates.size,
            requestedCount = request.updates.size,
        )
    }

    override suspend fun createTable(request: RTableDto.CreateRTableRequestDto): RTableDto.RTableResponseDto {
        created = request
        return table(id = 99L, gx = 0, gy = 0, locationTypeId = request.locationTypeId)
    }

    override suspend fun deleteTable(id: Long) {
        deleted += id
    }

    override suspend fun searchTables(
        query: String,
        limit: Int,
        includeInactive: Boolean,
    ) = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun getTableById(id: Long) = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun updateTable(
        id: Long,
        request: RTableDto.UpdateRTableRequestDto,
    ): RTableDto.RTableResponseDto {
        updates += id to request
        return tables.first { it.id == id }
    }

    override suspend fun syncTables(lastSyncTime: String): List<RTableDto.RTableResponseDto> = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun getCurrentSession(
        tableId: Long,
        forEmployeeNumber: String?,
    ): TableSessionDto.TableSessionResponseDto = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun transferTable(
        sourceTableId: Long,
        request: RTableDto.TransferTableRequestDto,
    ): RTableDto.TransferTableResponseDto = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun deleteTableSession(
        tableId: Long,
        reason: String,
    ): Unit = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun getCustomerAndAddressBySessionId(sessionId: Long): TableSessionDto.SessionCustomerDto =
        TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun assignCustomerAndAddress(
        sessionId: Long,
        request: TableSessionDto.AssignCustomerAndAddressRequestDto,
    ): TableSessionDto.TableSessionResponseDto = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun removeCustomerAndAddress(sessionId: Long): TableSessionDto.TableSessionResponseDto = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun requestBilling(tableId: Long): Unit = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun uploadTablesCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun getCombinablePartners(tableId: Long): List<Long> = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun setCombinablePartners(
        tableId: Long,
        partnerIds: List<Long>,
    ): List<Long> = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun getAllCombinablePairs(): List<RTableDto.CombinablePairDto> = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun setAllCombinablePairs(pairs: List<RTableDto.CombinablePairDto>): List<RTableDto.CombinablePairDto> =
        TODO("not exercised by FloorPlanEditorViewModel")
}

/**
 * Recording fake for [RoomElementApiClient]. Only list / position-write / create / delete do real
 * work; the rest are left unimplemented.
 */
class FakeFloorPlanRoomElementApiClient(
    var elements: List<RoomElementDto.RoomElementResponseDto> = emptyList(),
    private val onError: (() -> Nothing)? = null,
) : RoomElementApiClient {
    val positionWrites: MutableList<Pair<Long, RoomElementDto.UpdateRoomElementPositionRequestDto>> = mutableListOf()
    val dimensionWrites: MutableList<Pair<Long, RoomElementDto.UpdateRoomElementDimensionsRequestDto>> = mutableListOf()
    val deleted: MutableList<Long> = mutableListOf()
    var created: RoomElementDto.CreateRoomElementRequestDto? = null

    override suspend fun getAllRoomElements(
        type: String?,
        locationId: Long?,
        lastUpdateTime: String?,
    ): RoomElementDto.RoomElementListResponseDto {
        onError?.invoke()
        return RoomElementDto.RoomElementListResponseDto(
            elements = elements,
            totalCount = elements.size,
            returnedCount = elements.size,
            appliedFilters = RoomElementDto.FilterInfo(),
        )
    }

    override suspend fun createRoomElement(request: RoomElementDto.CreateRoomElementRequestDto): RoomElementDto.RoomElementResponseDto {
        created = request
        return element(id = 99L, gx = request.positionX.toInt(), gy = request.positionY.toInt(), locationTypeId = request.locationTypeId)
    }

    override suspend fun updateRoomElementPosition(
        id: Long,
        request: RoomElementDto.UpdateRoomElementPositionRequestDto,
    ): RoomElementDto.RoomElementResponseDto {
        positionWrites += id to request
        return element(id = id, gx = request.positionX.toInt(), gy = request.positionY.toInt())
    }

    override suspend fun deleteRoomElement(id: Long) {
        deleted += id
    }

    override suspend fun getRoomElementById(id: Long): RoomElementDto.RoomElementResponseDto = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun getRoomElementsByType(type: String): List<RoomElementDto.RoomElementResponseDto> = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun getRoomElementsByLocation(locationId: Long): List<RoomElementDto.RoomElementResponseDto> =
        TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun updateRoomElement(
        id: Long,
        request: RoomElementDto.UpdateRoomElementRequestDto,
    ): RoomElementDto.RoomElementResponseDto = TODO("not exercised by FloorPlanEditorViewModel")

    override suspend fun updateRoomElementDimensions(
        id: Long,
        request: RoomElementDto.UpdateRoomElementDimensionsRequestDto,
    ): RoomElementDto.RoomElementResponseDto {
        dimensionWrites += id to request
        return element(id = id, gx = 0, gy = 0)
    }

    override suspend fun syncRoomElements(lastSyncTime: String): List<RoomElementDto.RoomElementResponseDto> = TODO("not exercised by FloorPlanEditorViewModel")
}
