package org.example.project.features.table.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.table.TableSessionDto
import java.util.UUID

/**
 * Business service interface for table session operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors.
 */
interface TableSessionService {
    suspend fun getAllSessions(
        limit: Int = 50,
        offset: Int = 0,
        tableId: Long? = null,
        employeeId: UUID? = null,
        completed: Boolean? = null,
        startDate: LocalDateTime? = null,
        endDate: LocalDateTime? = null,
    ): List<TableSessionDto.TableSessionResponseDto>?

    suspend fun getSessionById(id: Long): TableSessionDto.TableSessionResponseDto?

    suspend fun getActiveSessionByTableId(tableId: Long): TableSessionDto.TableSessionResponseDto?

    suspend fun getSessionsByTableId(
        tableId: Long,
        limit: Int = 50,
        offset: Int = 0,
    ): List<TableSessionDto.TableSessionResponseDto>?

    suspend fun getSessionsByEmployeeId(
        employeeId: UUID,
        limit: Int = 50,
        offset: Int = 0,
    ): List<TableSessionDto.TableSessionResponseDto>?

    suspend fun getSessionSummaries(
        limit: Int = 50,
        offset: Int = 0,
        activeOnly: Boolean = false,
    ): List<TableSessionDto.SessionSummaryDto>

    suspend fun getCustomerAndAddressBySessionId(sessionId: Long): TableSessionDto.SessionCustomerDto

    suspend fun assignCustomerAndAddress(
        sessionId: Long,
        request: TableSessionDto.AssignCustomerAndAddressRequestDto,
    ): TableSessionDto.TableSessionResponseDto

    suspend fun removeCustomerAndAddress(sessionId: Long): TableSessionDto.TableSessionResponseDto

    /**
     * One-off, idempotent repair for tables that reference a *completed* session
     * (the corrupt invariant). Covers both variants: table OCCUPIED with a
     * completed currentSession, and table FREE/other but still pointing at a
     * completed session (the FREE-but-dangling variant left by the old ZBon heal).
     *
     * Policy A: if the completed session has no OPEN order items, the dangling
     * pointer is cleared (table freed). If it has OPEN items, a fresh active
     * session is opened for the table and each OPEN item plus its parent order is
     * moved onto the new session; the completed session's paid history is left
     * untouched.
     *
     * @return counts of each repair variant applied.
     */
    suspend fun repairCompletedButCurrentSessions(): TableSessionDto.SessionRepairResultDto
}
