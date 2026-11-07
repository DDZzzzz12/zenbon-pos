package org.example.project.features.table.domain.service

import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.domain.dto.table.TableUtilizationStatsDto
import org.example.project.core.domain.enums.rTable.TableStatus

/**
 * Service interface for restaurant table business operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors.
 * Migrated from Result<T, Error> in Stage B Batch 10 (commit 4/4).
 */
interface RTableService {
    /** Get or create a table session for the given table. */
    suspend fun getOrCreateSessionByTableId(
        tableId: Long,
        employeeId: String,
        forEmployeeNumber: String? = null,
    ): TableSessionDto.TableSessionResponseDto

    /** The table's open session, or null. Never creates one. */
    suspend fun getCurrentSessionOrNull(tableId: Long): TableSessionDto.TableSessionResponseDto?

    /** Retrieve all restaurant tables with optional filtering and pagination. */
    suspend fun getAllTables(
        limit: Int?,
        offset: Int?,
        status: TableStatus? = null,
        locationTypeId: Long? = null,
        includeInactive: Boolean = false,
    ): RTableDto.RTableListResponseDto

    /** Retrieve a specific table by its unique identifier. */
    suspend fun getTableById(id: Long): RTableDto.RTableResponseDto

    /** Retrieve a table by its table number. */
    suspend fun getTableByNumber(tableNumber: String): RTableDto.RTableResponseDto

    /** Create a new restaurant table with business validation. */
    suspend fun createTable(
        employeeId: String,
        request: RTableDto.CreateRTableRequestDto,
    ): RTableDto.RTableResponseDto

    /** Update an existing table with new information. */
    suspend fun updateTable(
        employeeId: String,
        id: Long,
        request: RTableDto.UpdateRTableRequestDto,
    ): RTableDto.RTableResponseDto

    /** Batch update positions and dimensions for multiple tables. */
    suspend fun batchUpdateTablePositions(request: RTableDto.BatchTablePositionUpdateDto): Int

    /** Delete a restaurant table from the system. Returns the deleted table number. */
    suspend fun deleteTable(
        employeeId: String,
        id: Long,
    ): String

    /** Change the status of a restaurant table with business logic. */
    suspend fun updateTableStatus(
        id: Long,
        request: RTableDto.UpdateTableStatusRequestDto,
    ): RTableDto.RTableResponseDto

    /** Get all available (free) tables for seating coordination. */
    suspend fun getAvailableTables(): List<RTableDto.RTableResponseDto>

    /** Get all tables filtered by current status. */
    suspend fun getTablesByStatus(status: TableStatus): List<RTableDto.RTableResponseDto>

    /** Get all tables of a specific type. */
    suspend fun getTablesByTypeId(locationTypeId: Long): List<RTableDto.RTableResponseDto>

    /** Reset a table to free status and end active session. */
    suspend fun resetTable(id: Long): RTableDto.RTableResponseDto

    /** Get table utilization statistics. */
    suspend fun getTableUtilizationStats(): TableUtilizationStatsDto.TableUtilizationStatsResponseDto

    /** Get tables updated after a specific timestamp for synchronization. */
    suspend fun getTablesUpdatedAfter(lastSyncTime: String): List<RTableDto.RTableResponseDto>

    /** Transfer a table session to another table. */
    suspend fun transferTable(
        sourceTableId: Long,
        request: RTableDto.TransferTableRequestDto,
        managerId: String,
        /** The device the request came from: lets the audit row find a manager sign-in lent to it. */
        deviceId: String? = null,
    ): RTableDto.TransferTableResponseDto

    /** Delete/reset a table by cancelling all open items, ending the session, and resetting the table. */
    suspend fun deleteTableSession(
        tableId: Long,
        employeeId: String,
        deviceId: String,
        reason: String? = null,
    )

    /** Import tables from CSV file content. */
    suspend fun importTablesCsv(
        csvContent: ByteArray,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto

    /** Search tables by partial table number match. */
    suspend fun searchTables(
        query: String,
        limit: Int = 5,
        includeInactive: Boolean = false,
    ): RTableDto.RTableListResponseDto

    /**
     * Request billing for a table owned by another employee.
     *
     * Refused with 403 when [requestingEmployeeId] is the order station's shared account
     * (`BillingAuthority.mayBillAs`, finding OS-b): no bill is ever registered under it, so it may not
     * ask for one either. A person may ask from any device.
     */
    suspend fun requestBilling(
        tableId: Long,
        requestingEmployeeId: String,
    )

    /** Get IDs of tables that can be combined with the given table. */
    suspend fun getCombinablePartners(tableId: Long): List<Long>

    /** Set combinable partners for a table (full replace). Validates tables are active and non-virtual. */
    suspend fun setCombinablePartners(
        tableId: Long,
        partnerIds: List<Long>,
    ): List<Long>

    /** Get all combinable pairs in the system. */
    suspend fun getAllCombinablePairs(): List<Pair<Long, Long>>

    /** Batch set all combinable pairs (full replace of all combinations). */
    suspend fun setAllCombinablePairs(pairs: List<Pair<Long, Long>>): List<Pair<Long, Long>>
}
