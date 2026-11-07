/**
 * Implementation of TableSessionService.
 *
 * Throws [PosException] on errors. Migrated from Result<T, Error> in Stage B Batch 10.
 *
 * Note: list-style getters (getAllSessions, getSessionById, ...) preserve their
 * legacy null/empty-on-failure semantics for now — those are non-error pathways
 * and a fuller cleanup will follow in Batch 10/4 (RTable).
 */
package org.example.project.features.table.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.domain.enums.employee.isTerminal
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.customer.mapper.toResponse
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.table.domain.repository.TableSessionRepository
import org.example.project.features.table.domain.service.TableSessionService
import org.example.project.features.table.mapper.toResponse
import org.example.project.features.table.mapper.toSessionSummaryDto
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.slf4j.LoggerFactory
import java.util.UUID

class TableSessionServiceImpl(
    private val sessionRepository: TableSessionRepository,
    private val invoices: BillInvoiceLink,
) : TableSessionService {
    private val logger = LoggerFactory.getLogger(TableSessionServiceImpl::class.java)

    override suspend fun getAllSessions(
        limit: Int,
        offset: Int,
        tableId: Long?,
        employeeId: UUID?,
        completed: Boolean?,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?,
    ): List<TableSessionDto.TableSessionResponseDto>? =
        try {
            dbQuery {
                sessionRepository
                    .getAllSessions(limit, offset, tableId, employeeId, completed, startDate, endDate)
                    .map { it.toResponse() }
            }
        } catch (e: Exception) {
            logger.error("getAllSessions: error getting all sessions", e)
            null
        }

    override suspend fun getCustomerAndAddressBySessionId(sessionId: Long): TableSessionDto.SessionCustomerDto =
        dbQuery {
            val session = TableSession.findById(sessionId) ?: throw sessionNotFound(sessionId)
            TableSessionDto.SessionCustomerDto(
                isDelivery = session.delivery,
                customer = session.customer?.toResponse(),
                deliveryAddress = session.deliveryAddress?.toResponse(),
            )
        }

    override suspend fun getSessionById(id: Long): TableSessionDto.TableSessionResponseDto? =
        try {
            dbQuery {
                sessionRepository.getSessionById(id)?.toResponse()
            }
        } catch (e: Exception) {
            logger.error("getSessionById: error getting session by id={}", id, e)
            null
        }

    override suspend fun getActiveSessionByTableId(tableId: Long): TableSessionDto.TableSessionResponseDto? =
        try {
            dbQuery {
                sessionRepository.getActiveSessionByTableId(tableId)?.toResponse()
            }
        } catch (e: Exception) {
            logger.error("getActiveSessionByTableId: error for tableId={}", tableId, e)
            null
        }

    override suspend fun getSessionsByTableId(
        tableId: Long,
        limit: Int,
        offset: Int,
    ): List<TableSessionDto.TableSessionResponseDto>? =
        try {
            dbQuery {
                sessionRepository.getSessionsByTableId(tableId, limit, offset).map { it.toResponse() }
            }
        } catch (e: Exception) {
            logger.error("getSessionsByTableId: error for tableId={}", tableId, e)
            null
        }

    override suspend fun getSessionsByEmployeeId(
        employeeId: UUID,
        limit: Int,
        offset: Int,
    ): List<TableSessionDto.TableSessionResponseDto>? =
        try {
            dbQuery {
                sessionRepository.getSessionsByEmployeeId(employeeId, limit, offset).map { it.toResponse() }
            }
        } catch (e: Exception) {
            logger.error("getSessionsByEmployeeId: error for employeeId={}", employeeId, e)
            null
        }

    override suspend fun getSessionSummaries(
        limit: Int,
        offset: Int,
        activeOnly: Boolean,
    ): List<TableSessionDto.SessionSummaryDto> =
        try {
            val completed = if (activeOnly) false else null
            dbQuery {
                sessionRepository
                    .getAllSessions(limit, offset, completed = completed)
                    .map { it.toSessionSummaryDto(invoices) }
            }
        } catch (e: Exception) {
            logger.error("getSessionSummaries: error getting session summaries", e)
            emptyList()
        }

    override suspend fun assignCustomerAndAddress(
        sessionId: Long,
        request: TableSessionDto.AssignCustomerAndAddressRequestDto,
    ): TableSessionDto.TableSessionResponseDto {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }

        val customerId = UUID.fromString(request.customerId)
        val deliveryAddressId = request.deliveryAddressId?.let { UUID.fromString(it) }

        return dbQuery {
            val session =
                sessionRepository.assignCustomerAndAddress(
                    sessionId = sessionId,
                    customerId = customerId,
                    deliveryAddressId = deliveryAddressId,
                    isDelivery = request.isDelivery,
                    deliveryPhoneNumber = request.deliveryPhoneNumber,
                ) ?: throw sessionNotFound(sessionId)

            logger.info(
                "assignCustomerAndAddress: assigned customer {} to session {} (delivery: {})",
                customerId,
                sessionId,
                request.isDelivery,
            )
            session.toResponse()
        }
    }

    override suspend fun removeCustomerAndAddress(sessionId: Long): TableSessionDto.TableSessionResponseDto =
        dbQuery {
            val session =
                sessionRepository.removeCustomerAndAddress(sessionId)
                    ?: throw sessionNotFound(sessionId)
            logger.info("removeCustomerAndAddress: removed customer assignment from session {}", sessionId)
            session.toResponse()
        }

    override suspend fun repairCompletedButCurrentSessions(): TableSessionDto.SessionRepairResultDto =
        dbQuery {
            var clearedDanglingPointers = 0
            var reopenedSessions = 0
            var movedOpenItems = 0
            var skippedTerminalOwned = 0

            // Select every table that still references a session, then keep only the
            // corrupt ones (currentSession completed). This catches BOTH variants:
            // OCCUPIED-with-completed-session AND FREE-but-dangling-pointer.
            val corruptTables =
                RTable
                    .find { RTableTable.currentSessionId.isNotNull() }
                    .filter { it.currentSession?.completed == true }

            for (table in corruptTables) {
                val old = table.currentSession!! // completed
                val openItems = old.orderItems.filter { it.itemStatus == ItemStatus.OPEN }

                if (openItems.isEmpty()) {
                    // No un-billed work — just clear the dangling pointer (frees the table).
                    table.reset()
                    clearedDanglingPointers++
                } else if (old.employeeId.role.isTerminal()) {
                    // Spec 2026-09-14 §4.2: a terminal account never owns a new session. This direct startSession
                    // bypasses the getOrCreateSession invariant, so skip the table (left for a manager) rather
                    // than re-open it for a terminal account. Only dev databases from before the station-ownership
                    // change can hold such a session.
                    logger.warn(
                        "repairCompletedButCurrentSessions: skipped table {}: stranded session {} is owned by a terminal account",
                        table.tableNumber,
                        old.id.value,
                    )
                    skippedTerminalOwned++
                } else {
                    // Policy A: move OPEN items onto a fresh active session and re-occupy.
                    val newSession = table.startSession(old.employeeId)
                    openItems.forEach { item ->
                        item.session = newSession
                        item.order?.tableSession = newSession
                        movedOpenItems++
                    }
                    reopenedSessions++
                }
            }

            logger.warn(
                "repairCompletedButCurrentSessions: cleared {} dangling pointer(s), " +
                    "reopened {} session(s), moved {} OPEN item(s), skipped {} terminal-owned session(s)",
                clearedDanglingPointers,
                reopenedSessions,
                movedOpenItems,
                skippedTerminalOwned,
            )

            TableSessionDto.SessionRepairResultDto(
                clearedDanglingPointers = clearedDanglingPointers,
                reopenedSessions = reopenedSessions,
                movedOpenItems = movedOpenItems,
            )
        }

    private fun sessionNotFound(id: Long) =
        PosException(
            PosErrorCode.TABLE_HAS_NO_SESSION,
            HttpStatusCode.NotFound,
            "Table session $id not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
