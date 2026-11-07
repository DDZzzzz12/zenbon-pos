package org.example.project.features.table.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.table.domain.models.RTable

/**
 * Repository interface for RTable entity operations.
 *
 * Provides data access layer for restaurant table management including
 * CRUD operations, status filtering, and session management functionality.
 * Returns plain entities or nullable types; exceptions propagate to the service layer.
 */
interface RTableRepository {
    fun getAllTables(
        limit: Int? = null,
        offset: Int? = null,
        status: TableStatus? = null,
        locationTypeId: Long? = null,
        includeInactive: Boolean = false,
    ): List<RTable>

    fun getTableById(id: Long): RTable?

    /**
     * Fetch a table by id and acquire a `SELECT ... FOR UPDATE` exclusive row lock.
     *
     * Used by the session-open path to serialize concurrent opens of the same table:
     * a second transaction opening the same table blocks here until the first commits,
     * then re-reads the row (now OCCUPIED). Must be called inside an existing transaction.
     */
    fun getTableByIdForUpdate(id: Long): RTable?

    fun getTableByNumber(tableNumber: String): RTable?

    fun createTable(request: RTableDto.CreateRTableRequestDto): RTable

    fun updateTable(
        id: Long,
        request: RTableDto.UpdateRTableRequestDto,
    ): RTable?

    fun batchUpdatePositions(updates: List<RTableDto.TablePositionUpdate>): Int

    /**
     * Delete a restaurant table (soft delete).
     *
     * @return The table number of the deleted table
     * @throws NoSuchElementException if table not found
     * @throws IllegalStateException if table has active session
     */
    fun deleteTable(id: Long): String

    fun updateTableStatus(
        id: Long,
        newStatus: TableStatus,
    ): RTable?

    fun getTablesByStatus(status: TableStatus): List<RTable>

    fun getTablesByTypeId(locationTypeId: Long): List<RTable>

    fun resetTable(id: Long): RTable?

    fun getTablesUpdatedAfter(lastSyncTime: LocalDateTime): List<RTable>

    fun searchTablesByNumber(
        query: String,
        limit: Int = 5,
        includeInactive: Boolean = false,
    ): List<RTable>
}
