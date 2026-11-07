/**
 * API client interface for restaurant table operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.TableSessionDto

interface RTableApiClient {
    suspend fun getAllTables(
        status: String? = null,
        locationTypeId: Long? = null,
        limit: Int? = null,
        offset: Int? = null,
        includeInactive: Boolean = false,
    ): RTableDto.RTableListResponseDto

    suspend fun searchTables(
        query: String,
        limit: Int = 5,
        includeInactive: Boolean = false,
    ): RTableDto.RTableListResponseDto

    suspend fun getTableById(id: Long): RTableDto.RTableResponseDto

    suspend fun createTable(request: RTableDto.CreateRTableRequestDto): RTableDto.RTableResponseDto

    suspend fun updateTable(
        id: Long,
        request: RTableDto.UpdateRTableRequestDto,
    ): RTableDto.RTableResponseDto

    suspend fun batchUpdatePositions(request: RTableDto.BatchTablePositionUpdateDto): RTableDto.BatchUpdatePositionsResponseDto

    suspend fun deleteTable(id: Long)

    suspend fun syncTables(lastSyncTime: String): List<RTableDto.RTableResponseDto>

    /**
     * Read-only: the table's open session. 404 `TABLE_HAS_NO_SESSION` when it has none. Never used to
     * open a table — the first order does that.
     */
    suspend fun getCurrentSession(
        tableId: Long,
        forEmployeeNumber: String? = null,
    ): TableSessionDto.TableSessionResponseDto

    suspend fun transferTable(
        sourceTableId: Long,
        request: RTableDto.TransferTableRequestDto,
    ): RTableDto.TransferTableResponseDto

    suspend fun deleteTableSession(
        tableId: Long,
        reason: String,
    )

    suspend fun getCustomerAndAddressBySessionId(sessionId: Long): TableSessionDto.SessionCustomerDto

    suspend fun assignCustomerAndAddress(
        sessionId: Long,
        request: TableSessionDto.AssignCustomerAndAddressRequestDto,
    ): TableSessionDto.TableSessionResponseDto

    suspend fun removeCustomerAndAddress(sessionId: Long): TableSessionDto.TableSessionResponseDto

    suspend fun requestBilling(tableId: Long)

    suspend fun uploadTablesCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean = false,
    ): InitialSetupDto.CsvImportResultDto

    suspend fun getCombinablePartners(tableId: Long): List<Long>

    suspend fun setCombinablePartners(
        tableId: Long,
        partnerIds: List<Long>,
    ): List<Long>

    suspend fun getAllCombinablePairs(): List<RTableDto.CombinablePairDto>

    suspend fun setAllCombinablePairs(pairs: List<RTableDto.CombinablePairDto>): List<RTableDto.CombinablePairDto>
}
