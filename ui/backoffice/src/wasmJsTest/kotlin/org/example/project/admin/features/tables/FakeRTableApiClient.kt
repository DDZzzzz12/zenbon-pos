package org.example.project.admin.features.tables

import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.TableSessionDto

/**
 * Hand-written fake; records mutating calls so tests can assert on them. Methods TableViewModel
 * never calls (session/transfer/CSV/global combinable-pairs) are left `TODO` — this fake covers
 * only the surface adminApp's Tische destination exercises.
 */
class FakeRTableApiClient(
    var tables: List<RTableDto.RTableResponseDto> = emptyList(),
    private val onError: (() -> Nothing)? = null,
) : RTableApiClient {
    var created: RTableDto.CreateRTableRequestDto? = null
    var updatedId: Long? = null
    var updated: RTableDto.UpdateRTableRequestDto? = null
    var deletedId: Long? = null
    var combinablePartnersRequestedFor: Long? = null
    var lastSetCombinablePartners: Pair<Long, List<Long>>? = null
    private var combinablePartners: List<Long> = emptyList()

    fun seedCombinablePartners(partnerIds: List<Long>) {
        combinablePartners = partnerIds
    }

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

    override suspend fun searchTables(
        query: String,
        limit: Int,
        includeInactive: Boolean,
    ) = RTableDto.RTableListResponseDto(
        tables = tables.filter { it.tableNumber.contains(query, ignoreCase = true) },
        totalCount = tables.size,
        returnedCount = tables.size,
        appliedFilters = RTableDto.FilterInfo(),
    )

    override suspend fun getTableById(id: Long) = tables.first { it.id == id }

    override suspend fun createTable(request: RTableDto.CreateRTableRequestDto): RTableDto.RTableResponseDto {
        created = request
        return RTableDto.RTableResponseDto(
            id = 99,
            tableNumber = request.tableNumber,
            seats = request.seats,
            locationTypeId = request.locationTypeId,
            locationTypeName = "Indoor",
            locationTypeEnglishName = "Indoor",
            locationTypeChineseName = "室内",
            isVirtual = false,
            isTakeaway = false,
            tableStatus = "FREE",
            currentSessionId = null,
            hasActiveSession = false,
            positionX = request.positionX,
            positionY = request.positionY,
            rotation = request.rotation,
            width = request.width,
            height = request.height,
            assignmentPriority = request.assignmentPriority,
        )
    }

    override suspend fun updateTable(
        id: Long,
        request: RTableDto.UpdateRTableRequestDto,
    ): RTableDto.RTableResponseDto {
        updatedId = id
        updated = request
        return RTableDto.RTableResponseDto(
            id = id,
            tableNumber = request.tableNumber ?: "T",
            seats = request.seats,
            locationTypeId = request.locationTypeId ?: 1L,
            locationTypeName = "Indoor",
            locationTypeEnglishName = "Indoor",
            locationTypeChineseName = "室内",
            isVirtual = false,
            isTakeaway = false,
            tableStatus = request.tableStatus ?: "FREE",
            currentSessionId = null,
            hasActiveSession = false,
            positionX = request.positionX,
            positionY = request.positionY,
            rotation = request.rotation,
            width = request.width,
            height = request.height,
            assignmentPriority = request.assignmentPriority ?: 0,
        )
    }

    override suspend fun batchUpdatePositions(request: RTableDto.BatchTablePositionUpdateDto): RTableDto.BatchUpdatePositionsResponseDto =
        TODO("not exercised by TableViewModel")

    override suspend fun deleteTable(id: Long) {
        deletedId = id
    }

    override suspend fun syncTables(lastSyncTime: String): List<RTableDto.RTableResponseDto> = tables

    override suspend fun getCurrentSession(
        tableId: Long,
        forEmployeeNumber: String?,
    ): TableSessionDto.TableSessionResponseDto = TODO("not exercised by TableViewModel")

    override suspend fun transferTable(
        sourceTableId: Long,
        request: RTableDto.TransferTableRequestDto,
    ): RTableDto.TransferTableResponseDto = TODO("not exercised by TableViewModel")

    override suspend fun deleteTableSession(
        tableId: Long,
        reason: String,
    ): Unit = TODO("not exercised by TableViewModel")

    override suspend fun getCustomerAndAddressBySessionId(sessionId: Long): TableSessionDto.SessionCustomerDto = TODO("not exercised by TableViewModel")

    override suspend fun assignCustomerAndAddress(
        sessionId: Long,
        request: TableSessionDto.AssignCustomerAndAddressRequestDto,
    ): TableSessionDto.TableSessionResponseDto = TODO("not exercised by TableViewModel")

    override suspend fun removeCustomerAndAddress(sessionId: Long): TableSessionDto.TableSessionResponseDto = TODO("not exercised by TableViewModel")

    override suspend fun requestBilling(tableId: Long): Unit = TODO("not exercised by TableViewModel")

    override suspend fun uploadTablesCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto = TODO("not exercised by TableViewModel")

    override suspend fun getCombinablePartners(tableId: Long): List<Long> {
        combinablePartnersRequestedFor = tableId
        return combinablePartners
    }

    override suspend fun setCombinablePartners(
        tableId: Long,
        partnerIds: List<Long>,
    ): List<Long> {
        lastSetCombinablePartners = tableId to partnerIds
        combinablePartners = partnerIds
        return partnerIds
    }

    override suspend fun getAllCombinablePairs(): List<RTableDto.CombinablePairDto> = TODO("not exercised by TableViewModel")

    override suspend fun setAllCombinablePairs(pairs: List<RTableDto.CombinablePairDto>): List<RTableDto.CombinablePairDto> =
        TODO("not exercised by TableViewModel")
}
