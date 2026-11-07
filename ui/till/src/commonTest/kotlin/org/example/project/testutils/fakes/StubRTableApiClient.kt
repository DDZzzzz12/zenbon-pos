package org.example.project.testutils.fakes

import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.TableSessionDto

/** Fails loudly if a test reaches the table API it declared it never uses. */
class StubRTableApiClient : RTableApiClient {
    override suspend fun getAllTables(
        status: String?,
        locationTypeId: Long?,
        limit: Int?,
        offset: Int?,
        includeInactive: Boolean,
    ): RTableDto.RTableListResponseDto = error("unused")

    override suspend fun searchTables(
        query: String,
        limit: Int,
        includeInactive: Boolean,
    ) = error("unused")

    override suspend fun getTableById(id: Long): RTableDto.RTableResponseDto = error("unused")

    override suspend fun createTable(request: RTableDto.CreateRTableRequestDto) = error("unused")

    override suspend fun updateTable(
        id: Long,
        request: RTableDto.UpdateRTableRequestDto,
    ) = error("unused")

    override suspend fun batchUpdatePositions(request: RTableDto.BatchTablePositionUpdateDto) = error("unused")

    override suspend fun deleteTable(id: Long) = error("unused")

    override suspend fun syncTables(lastSyncTime: String): List<RTableDto.RTableResponseDto> = error("unused")

    override suspend fun getCurrentSession(
        tableId: Long,
        forEmployeeNumber: String?,
    ) = error("unused")

    override suspend fun transferTable(
        sourceTableId: Long,
        request: RTableDto.TransferTableRequestDto,
    ) = error("unused")

    override suspend fun deleteTableSession(
        tableId: Long,
        reason: String,
    ) = error("unused")

    override suspend fun getCustomerAndAddressBySessionId(sessionId: Long) = error("unused")

    override suspend fun assignCustomerAndAddress(
        sessionId: Long,
        request: TableSessionDto.AssignCustomerAndAddressRequestDto,
    ) = error("unused")

    override suspend fun removeCustomerAndAddress(sessionId: Long) = error("unused")

    override suspend fun requestBilling(tableId: Long) = error("unused")

    override suspend fun uploadTablesCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto = error("unused")

    override suspend fun getCombinablePartners(tableId: Long): List<Long> = error("unused")

    override suspend fun setCombinablePartners(
        tableId: Long,
        partnerIds: List<Long>,
    ): List<Long> = error("unused")

    override suspend fun getAllCombinablePairs(): List<RTableDto.CombinablePairDto> = error("unused")

    override suspend fun setAllCombinablePairs(pairs: List<RTableDto.CombinablePairDto>): List<RTableDto.CombinablePairDto> = error("unused")
}
