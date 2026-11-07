/**
 * Implementation of RTableService.
 *
 * Throws [PosException] on errors. Migrated from Result<T, Error> in Stage B
 * Batch 10 commit 4/4. Retires the OrderItemServiceImpl boundary adapter
 * (catch PosException -> toLegacyError) added in 3b6559dc.
 */
package org.example.project.features.table.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.domain.dto.table.TableUtilizationStatsDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.employee.isTerminal
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.core.mapper.websocket.createTableStatusUpdateDto
import org.example.project.core.websocket.NotificationService
import org.example.project.core.websocket.PendingBillingRequestStore
import org.example.project.features.bill.domain.requireMayBillAs
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.data.service.RotationNextNotifier
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.repository.ItemTransferRepository
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.table.domain.repository.CombinableTablesRepository
import org.example.project.features.table.domain.repository.RTableRepository
import org.example.project.features.table.domain.repository.TableSessionRepository
import org.example.project.features.table.domain.service.RTableService
import org.example.project.features.table.domain.service.TableSessionOwnerPolicy
import org.example.project.features.table.mapper.toResponse
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.SystemRecordGuard
import org.example.project.utils.builder.CsvReader
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.toDecimalString
import org.example.project.utils.log.AuditEntityRef
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID

class RTableServiceImpl(
    private val tableRepository: RTableRepository,
    private val tableSessionRepository: TableSessionRepository,
    private val transferRepository: ItemTransferRepository,
    private val orderItemService: OrderItemService,
    private val combinableTablesRepository: CombinableTablesRepository,
    private val ownerPolicy: TableSessionOwnerPolicy,
) : RTableService {
    private val logger = LoggerFactory.getLogger(RTableServiceImpl::class.java)

    override suspend fun getOrCreateSessionByTableId(
        tableId: Long,
        employeeId: String,
        forEmployeeNumber: String?,
    ): TableSessionDto.TableSessionResponseDto {
        var pendingNotification: NotificationDto.TableStatusUpdateDto? = null
        // Whether the committed attempt ran the rotation pick; reset per attempt, since Exposed may retry.
        var rotated = false

        val response =
            dbQuery {
                rotated = false
                // Lock the table row first so concurrent opens of the same table serialize:
                // the loser blocks here, then re-reads the row as OCCUPIED and returns early below.
                val table = tableRepository.getTableByIdForUpdate(tableId) ?: throw tableNotFound(tableId)

                if (table.currentSession != null && table.tableStatus == TableStatus.OCCUPIED) {
                    // Training mode validation is handled by TableSessionRepositoryImpl.getOrCreateSession
                    return@dbQuery table.currentSession!!.toResponse()
                }

                if (table.tableStatus == TableStatus.FREE && table.currentSession == null) {
                    val actingEmployee =
                        Employee.findById(UUID.fromString(employeeId))
                            ?: throw employeeNotFound(employeeId)
                    // Under the table lock and before getOrCreateSession, so the rotation pick keeps the
                    // r_table FOR UPDATE -> rotationMutex -> configMutex order. An order station never owns
                    // the session (spec 2026-09-14): for a station the policy returns the rotation pick or
                    // throws a 409; for a person it returns the named, rotated or acting employee.
                    // No expectation on this legacy path, so it never reports a shift; it only publishes.
                    val decision = ownerPolicy.ownerDecisionForNewSession(table, actingEmployee, forEmployeeNumber, expectedNextEmployeeId = null)
                    rotated = decision.rotated
                    val employee = decision.employee
                    if (table.isVirtual && !table.isTakeaway) {
                        // QUICK_SALE / VOUCHER sessions are created by their own flows; one opened here is
                        // unreachable from a floor plan and wedges the Z-Bon (E2E-2026-09-18 #4). Checked
                        // after the owner, as in OrderServiceImpl, so an order station keeps its 409
                        // STATION_TABLE_VIRTUAL; a person's owner rule never rotates on a virtual table.
                        throw PosException(
                            PosErrorCode.TABLE_VIRTUAL_NOT_ORDERABLE,
                            HttpStatusCode.Conflict,
                            "Table ${table.tableNumber} is virtual and cannot be opened",
                        )
                    }

                    val (_, tableSession) = tableSessionRepository.getOrCreateSession(table, employee)

                    pendingNotification =
                        createTableStatusUpdateDto(
                            table = table,
                            status = table.tableStatus,
                            sessionId = tableSession.id.value,
                            sessionStartTime = tableSession.startTime.toString(),
                            // An already open table keeps its owner; only a new session belongs to `employee`.
                            employee = tableSession.employeeId,
                            isTraining = tableSession.isTraining,
                        )

                    return@dbQuery tableSession.toResponse()
                }

                throw employeeNotFound(employeeId)
            }

        pendingNotification?.let { NotificationService.notifyTableStatusUpdate(it) }
        // After commit, holding no table/rotation/config lock: a rolled-back open never reaches here.
        if (rotated) RotationNextNotifier.publishCurrent()
        return response
    }

    override suspend fun getCurrentSessionOrNull(tableId: Long): TableSessionDto.TableSessionResponseDto? =
        dbQuery {
            val table = tableRepository.getTableById(tableId) ?: throw tableNotFound(tableId)
            table.currentSession?.takeIf { !it.completed }?.toResponse()
        }

    override suspend fun getAllTables(
        limit: Int?,
        offset: Int?,
        status: TableStatus?,
        locationTypeId: Long?,
        includeInactive: Boolean,
    ): RTableDto.RTableListResponseDto =
        dbQuery {
            val entities = tableRepository.getAllTables(limit, offset, status, locationTypeId, includeInactive)

            val tables = toResponsesWithOpenAmount(entities)
            logger.debug(
                "getAllTables: returned {} tables (status={}, locationTypeId={})",
                tables.size,
                status,
                locationTypeId,
            )
            RTableDto.RTableListResponseDto(
                tables = tables,
                totalCount = tables.size,
                returnedCount = tables.size,
                appliedFilters =
                    RTableDto.FilterInfo(
                        status = status?.name,
                        type = locationTypeId?.toString(),
                        limit = limit,
                        offset = offset,
                    ),
            )
        }

    override suspend fun getTableById(id: Long): RTableDto.RTableResponseDto =
        dbQuery {
            val table = tableRepository.getTableById(id) ?: throw tableNotFound(id)
            toResponsesWithOpenAmount(listOf(table)).single()
        }

    override suspend fun getTableByNumber(tableNumber: String): RTableDto.RTableResponseDto =
        dbQuery {
            val table =
                tableRepository.getTableByNumber(tableNumber)
                    ?: throw PosException(
                        PosErrorCode.TABLE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Table with number '$tableNumber' not found",
                    )
            toResponsesWithOpenAmount(listOf(table)).single()
        }

    override suspend fun createTable(
        employeeId: String,
        request: RTableDto.CreateRTableRequestDto,
    ): RTableDto.RTableResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createTable(employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.RTABLE,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.tableNumber, "Created table '${dto.tableNumber}'")
                    },
                ),
        ) {
            dbQuery {
                val table = tableRepository.createTable(request)
                logger.info("createTable: created new table={}", request.tableNumber)
                val response = table.toResponse()
                NotificationService.notifyDataChange(
                    ChangeEntityType.RTABLE,
                    DataChangeOperation.CREATED,
                    table.id.value,
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }

    override suspend fun updateTable(
        employeeId: String,
        id: Long,
        request: RTableDto.UpdateRTableRequestDto,
    ): RTableDto.RTableResponseDto {
        if (!request.hasChanges()) {
            logger.debug("updateTable: no changes provided for table id={}", id)
            return getTableById(id)
        }

        return ExceptionConverter.catchingThrows(
            context = "updateTable(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.RTABLE,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.tableNumber, "Updated table '${dto.tableNumber}'")
                    },
                ),
        ) {
            dbQuery {
                requireNotSystemTable(id, "changed")
                val table = tableRepository.updateTable(id, request) ?: throw tableNotFound(id)
                logger.info("updateTable: updated table id={}", id)
                val response = toResponsesWithOpenAmount(listOf(table)).single()
                NotificationService.notifyDataChange(
                    ChangeEntityType.RTABLE,
                    DataChangeOperation.UPDATED,
                    id,
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }
    }

    override suspend fun batchUpdateTablePositions(request: RTableDto.BatchTablePositionUpdateDto): Int {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid batch update data")
        }

        val count = dbQuery { tableRepository.batchUpdatePositions(request.updates) }
        logger.info(
            "batchUpdateTablePositions: updated positions for {} of {} requested tables",
            count,
            request.updates.size,
        )
        NotificationService.notifyDataChange(ChangeEntityType.RTABLE, DataChangeOperation.UPDATED)
        return count
    }

    override suspend fun deleteTable(
        employeeId: String,
        id: Long,
    ): String =
        ExceptionConverter.catchingThrows(
            context = "deleteTable(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.RTABLE,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { name ->
                        EntryLogData(id.toString(), name, "Deleted table '$name'")
                    },
                ),
        ) {
            dbQuery {
                requireNotSystemTable(id, "deleted")
                val name =
                    try {
                        combinableTablesRepository.removeAllForTable(id)
                        tableRepository.deleteTable(id)
                    } catch (e: NoSuchElementException) {
                        throw tableNotFound(id)
                    } catch (e: IllegalStateException) {
                        throw PosException(
                            PosErrorCode.TABLE_HAS_ACTIVE_SESSION,
                            HttpStatusCode.Conflict,
                            e.message ?: "Table $id is referenced by an active session",
                        )
                    }

                logger.info("deleteTable: deleted table id={}", id)
                NotificationService.notifyDataChange(
                    ChangeEntityType.RTABLE,
                    DataChangeOperation.DELETED,
                    id,
                    excludeEmployeeId = employeeId,
                )
                name
            }
        }

    override suspend fun updateTableStatus(
        id: Long,
        request: RTableDto.UpdateTableStatusRequestDto,
    ): RTableDto.RTableResponseDto =
        dbQuery {
            val newStatus = request.getTableStatus()
            val table = tableRepository.updateTableStatus(id, newStatus) ?: throw tableNotFound(id)
            logger.info("updateTableStatus: updated table id={} to status={}", id, newStatus.name)
            toResponsesWithOpenAmount(listOf(table)).single()
        }

    override suspend fun getAvailableTables(): List<RTableDto.RTableResponseDto> = getTablesByStatus(TableStatus.FREE)

    override suspend fun getTablesByStatus(status: TableStatus): List<RTableDto.RTableResponseDto> =
        dbQuery {
            val tables = toResponsesWithOpenAmount(tableRepository.getTablesByStatus(status))
            logger.debug("getTablesByStatus: found {} tables with status={}", tables.size, status.name)
            tables
        }

    override suspend fun getTablesByTypeId(locationTypeId: Long): List<RTableDto.RTableResponseDto> =
        dbQuery {
            val tables = toResponsesWithOpenAmount(tableRepository.getTablesByTypeId(locationTypeId))
            logger.debug("getTablesByTypeId: found {} tables with locationTypeId={}", tables.size, locationTypeId)
            tables
        }

    override suspend fun resetTable(id: Long): RTableDto.RTableResponseDto =
        dbQuery {
            val table = tableRepository.resetTable(id) ?: throw tableNotFound(id)
            logger.info("resetTable: reset table id={}", id)
            table.toResponse()
        }

    override suspend fun getTableUtilizationStats(): TableUtilizationStatsDto.TableUtilizationStatsResponseDto =
        dbQuery {
            val tables = tableRepository.getAllTables()
            val totalTables = tables.size
            val availableTables = tables.count { it.tableStatus == TableStatus.FREE }
            val occupiedTables = tables.count { it.tableStatus == TableStatus.OCCUPIED }
            val reservedTables = tables.count { it.tableStatus == TableStatus.RESERVED }

            val utilizationPercentage =
                if (totalTables > 0) {
                    ((occupiedTables + reservedTables).toDouble() / totalTables) * 100
                } else {
                    0.0
                }

            val tablesByType =
                tables
                    .groupBy { it.locationTypeRef.name }
                    .mapValues { (_, tableList) -> tableList.size }

            TableUtilizationStatsDto.TableUtilizationStatsResponseDto(
                totalTables = totalTables,
                availableTables = availableTables,
                occupiedTables = occupiedTables,
                reservedTables = reservedTables,
                tablesByType = tablesByType,
                utilizationPercentage = utilizationPercentage,
            )
        }

    override suspend fun getTablesUpdatedAfter(lastSyncTime: String): List<RTableDto.RTableResponseDto> {
        val parsedDateTime =
            try {
                LocalDateTime.parse(lastSyncTime)
            } catch (e: Exception) {
                throw validationFailed("Invalid lastSyncTime: ${e.message ?: lastSyncTime}")
            }
        return dbQuery {
            toResponsesWithOpenAmount(tableRepository.getTablesUpdatedAfter(parsedDateTime))
        }
    }

    override suspend fun transferTable(
        sourceTableId: Long,
        request: RTableDto.TransferTableRequestDto,
        managerId: String,
        deviceId: String?,
    ): RTableDto.TransferTableResponseDto =
        ExceptionConverter.catchingThrows(
            context = "transferTable(source=$sourceTableId, target=${request.targetTableId}, manager=$managerId)",
            employeeId = managerId,
            // Without the device the audit cannot see a manager sign-in lent to it: the row said DIRECT.
            deviceId = deviceId,
            operation = "TRANSFER_TABLE",
            entityType = "TABLE_SESSION",
            // The session that moved or was reassigned. `forItemTransfer` leaves `sessionId` null:
            // those items went into the TARGET's session, whose id the DTO never carries, and the
            // ids it does carry are TABLE ids, which under entityType=TABLE_SESSION would collide
            // numerically with an unrelated real session. So that branch declines by returning null
            // — see AuditEntityRef — while the other two carry the source session's own id.
            auditEntity = { response ->
                response.sessionId?.let { sessionId ->
                    AuditEntityRef(id = sessionId.toString(), name = "${response.message} | Grund: ${request.reason}")
                }
            },
        ) {
            try {
                request.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid transfer request")
            }

            val pendingNotifications = mutableListOf<NotificationDto.TableStatusUpdateDto>()

            val response =
                dbQuery {
                    val sourceTable =
                        tableRepository.getTableById(sourceTableId)
                            ?: throw tableNotFound(sourceTableId)
                    val targetTable =
                        tableRepository.getTableById(request.targetTableId)
                            ?: throw tableNotFound(request.targetTableId)

                    if (sourceTableId == request.targetTableId) {
                        val sourceEmployeeNumber = sourceTable.currentSession?.employeeId?.employeeNumber
                        if (sourceEmployeeNumber == request.targetEmployeeNumber) {
                            throw validationFailed("Cannot transfer to the same table with the same employee")
                        }
                    }

                    val sourceSession =
                        sourceTable.currentSession
                            ?: throw PosException(
                                PosErrorCode.TABLE_HAS_NO_SESSION,
                                HttpStatusCode.Conflict,
                                "Source table $sourceTableId has no active session to transfer",
                            )

                    val targetSession = targetTable.currentSession

                    val targetEmployee =
                        Employee
                            .find {
                                EmployeeTable.employeeNumber eq request.targetEmployeeNumber
                            }.firstOrNull() ?: throw employeeNotFound(request.targetEmployeeNumber)

                    // Spec 2026-09-14: a terminal account (an order station's shared employee) never owns a table session.
                    if (targetEmployee.role.isTerminal()) throw terminalCannotOwnSession(targetEmployee)

                    val manager =
                        Employee.findById(UUID.fromString(managerId))
                            ?: throw employeeNotFound(managerId)

                    when {
                        sourceTableId == request.targetTableId -> {
                            // Scenario 0: same table, different employee - reassign only
                            sourceSession.employeeId = targetEmployee
                            logger.info(
                                "transferTable: reassigned session {} on table {} to employee {}",
                                sourceSession.id.value,
                                sourceTable.id.value,
                                targetEmployee.employeeNumber,
                            )

                            pendingNotifications.add(
                                createTableStatusUpdateDto(
                                    table = sourceTable,
                                    status = sourceTable.tableStatus,
                                    sessionId = sourceSession.id.value,
                                    sessionStartTime = sourceSession.startTime.toString(),
                                    employee = targetEmployee,
                                ),
                            )

                            RTableDto.TransferTableResponseDto.forSessionMove(
                                sourceTableId = sourceTable.id.value,
                                targetTableId = sourceTable.id.value,
                                sessionId = sourceSession.id.value,
                            )
                        }

                        targetSession != null && targetTable.tableStatus == TableStatus.OCCUPIED -> {
                            // Scenario 1: target has active session - transfer items to it
                            transferItemsToExistingSession(
                                sourceTable = sourceTable,
                                sourceSession = sourceSession,
                                targetTable = targetTable,
                                targetSession = targetSession,
                                employee = manager,
                                reason = request.reason,
                                pendingNotifications = pendingNotifications,
                            )
                        }

                        else -> {
                            // Scenario 2: target has no session - move entire session
                            moveSessionToTable(
                                sourceTable = sourceTable,
                                sourceSession = sourceSession,
                                targetTable = targetTable,
                                employee = targetEmployee,
                                reason = request.reason,
                                pendingNotifications = pendingNotifications,
                            )
                        }
                    }
                }

            pendingNotifications.forEach { NotificationService.notifyTableStatusUpdate(it) }
            response
        }

    /**
     * Transfers all open order items from source session to target session.
     * Used when target table already has an active session.
     */
    private fun transferItemsToExistingSession(
        sourceTable: RTable,
        sourceSession: TableSession,
        targetTable: RTable,
        targetSession: TableSession,
        employee: Employee,
        reason: String?,
        pendingNotifications: MutableList<NotificationDto.TableStatusUpdateDto>,
    ): RTableDto.TransferTableResponseDto {
        val orderItems =
            OrderItem
                .find {
                    (OrderItemTable.sessionId eq sourceSession.id) and (OrderItemTable.itemStatus eq ItemStatus.OPEN)
                }.toList()

        if (orderItems.isEmpty()) {
            throw validationFailed("No open items to transfer from source table")
        }

        // Record one transfer event covering all items, then update each item.
        // All items were filtered above by sourceSession, so a single fromTable is correct.
        transferRepository.createTransferBatch(
            itemIds = orderItems.map { it.id.value },
            fromTable = sourceSession,
            toTable = targetSession,
            employee = employee,
            reason = reason,
        )
        orderItems.forEach { item ->
            item.session = targetSession
            item.transferred = true
        }

        // The one closing rule (paid/cancelled lines do not keep a session open). The old check
        // counted every line of any status, so a table with a paid round stayed OCCUPIED with
        // nothing on it after its open round moved away.
        tableSessionRepository.completeIfNoOpenItems(sourceSession.id.value)?.let {
            sourceSession.note =
                (
                    "Table ${sourceTable.tableNumber} was transferred to another active table " +
                        "${targetTable.tableNumber}. ${reason ?: "No reason provided."}"
                ).take(SESSION_NOTE_MAX)
        }

        // The note column is VARCHAR(500) and the reason may already use all 500 characters, so the
        // sentence around it is capped; the full reason is kept on the item transfer and in the audit row.
        targetSession.note =
            (
                "Transferred from table ${sourceTable.id.value} to table " +
                    "${targetTable.id.value}. ${reason ?: "No reason provided."}"
            ).take(SESSION_NOTE_MAX)

        pendingNotifications.add(
            createTableStatusUpdateDto(
                table = sourceTable,
                status = sourceTable.tableStatus,
                sessionId = sourceSession.id.value,
                sessionStartTime = sourceSession.startTime.toString(),
                employee = employee,
            ),
        )

        // The target absorbed every moved item, so its openAmount changed too and it must be
        // broadcast as well — otherwise every other device keeps the pre-transfer total on the
        // target card until someone taps refresh (E2E-2026-09-15 N6; this is the whole-table twin
        // of the per-item fix in OrderItemServiceImpl.transferInTransaction).
        //
        // Unconditional on purpose. `transferTable` routes source == target to the reassign-only
        // branch before this function is ever reached, so the two tables are always distinct and
        // this cannot emit a duplicate card. Do NOT reintroduce a "only when the target was freshly
        // opened" guard — that guard IS the bug.
        //
        // Both cards are built here, inside the caller's `dbQuery`: createTableStatusUpdateDto
        // resolves the session and its open items eagerly into a DTO of primitives, so nothing
        // lazy escapes the transaction. `pendingNotifications` is emitted only after it commits.
        //
        // The card names the TARGET session's own owner, not the acting manager: a transfer moves
        // items, it does not change who serves the target table.
        pendingNotifications.add(
            createTableStatusUpdateDto(
                table = targetTable,
                status = targetTable.tableStatus,
                sessionId = targetSession.id.value,
                sessionStartTime = targetSession.startTime.toString(),
                employee = targetSession.employeeId,
            ),
        )

        logger.info(
            "transferItemsToExistingSession: transferred {} items from table {} to table {}",
            orderItems.size,
            sourceTable.id.value,
            targetTable.id.value,
        )

        return RTableDto.TransferTableResponseDto.forItemTransfer(
            sourceTableId = sourceTable.id.value,
            targetTableId = targetTable.id.value,
            itemCount = orderItems.size,
        )
    }

    /**
     * Moves the entire session from source table to target table.
     * Used when target table has no active session.
     */
    private fun moveSessionToTable(
        sourceTable: RTable,
        sourceSession: TableSession,
        targetTable: RTable,
        employee: Employee,
        reason: String?,
        pendingNotifications: MutableList<NotificationDto.TableStatusUpdateDto>,
    ): RTableDto.TransferTableResponseDto {
        sourceSession.table = targetTable
        sourceSession.employeeId = employee

        sourceTable.tableStatus = TableStatus.FREE
        sourceTable.currentSession = null

        targetTable.tableStatus = TableStatus.OCCUPIED
        targetTable.currentSession = sourceSession

        logger.info(
            "moveSessionToTable: moved session {} from table {} to table {}. reason='{}'",
            sourceSession.id.value,
            sourceTable.id.value,
            targetTable.id.value,
            reason,
        )

        pendingNotifications.add(
            createTableStatusUpdateDto(
                table = sourceTable,
                status = sourceTable.tableStatus,
                employee = employee,
            ),
        )

        pendingNotifications.add(
            createTableStatusUpdateDto(
                table = targetTable,
                status = targetTable.tableStatus,
                sessionId = sourceSession.id.value,
                sessionStartTime = sourceSession.startTime.toString(),
                employee = employee,
            ),
        )

        return RTableDto.TransferTableResponseDto.forSessionMove(
            sourceTableId = sourceTable.id.value,
            targetTableId = targetTable.id.value,
            sessionId = sourceSession.id.value,
        )
    }

    override suspend fun deleteTableSession(
        tableId: Long,
        employeeId: String,
        deviceId: String,
        reason: String?,
    ) {
        ExceptionConverter.catchingThrows<Unit>(
            context = "deleteTableSession(tableId=$tableId, employeeId=$employeeId, reason=$reason)",
            employeeId = employeeId,
            operation = "DELETE_TABLE_SESSION",
            entityType = "TABLE_SESSION",
            deviceId = deviceId,
        ) {
            var pendingNotification: NotificationDto.TableStatusUpdateDto? = null

            // Cancel open items first (orderItemService.cancelOrderItems controls its own
            // transactions and TSE signing). Boundary adapter (catch PosException ->
            // toLegacyError) retired in Stage B Batch 10/4 — exceptions propagate directly.
            val openItemIds =
                dbQuery {
                    val table = tableRepository.getTableById(tableId) ?: throw tableNotFound(tableId)
                    val session =
                        table.currentSession ?: throw PosException(
                            PosErrorCode.TABLE_HAS_NO_SESSION,
                            HttpStatusCode.Conflict,
                            "Table $tableId has no active session to delete",
                        )
                    session.orderItems.filter { it.itemStatus == ItemStatus.OPEN }.map { it.id.value }
                }

            if (openItemIds.isNotEmpty()) {
                orderItemService.cancelOrderItems(
                    item = openItemIds,
                    employeeId = employeeId,
                    deviceId = deviceId,
                    reason = reason,
                    sourceTableId = tableId,
                )
            }

            dbQuery {
                // Lock the table first (TABLE before SESSION), as every other session writer does.
                val table = tableRepository.getTableByIdForUpdate(tableId) ?: throw tableNotFound(tableId)
                // cancelOrderItems already ended the session when it cancelled the last OPEN item.
                val session = table.currentSession ?: return@dbQuery
                if (!session.completed && tableSessionRepository.completeIfNoOpenItems(session.id.value) == null) {
                    // Items were added between the cancel and this lock. Never report success for a
                    // session that is still open (E2E-2026-09-18 #3).
                    throw PosException(
                        PosErrorCode.TABLE_HAS_ACTIVE_SESSION,
                        HttpStatusCode.Conflict,
                        "Table $tableId still has open items — session not deleted",
                    )
                }
                val employee =
                    Employee.findById(UUID.fromString(employeeId))
                        ?: throw employeeNotFound(employeeId)
                pendingNotification =
                    createTableStatusUpdateDto(
                        table = table,
                        status = table.tableStatus,
                        sessionId = session.id.value,
                        sessionStartTime = session.startTime.toString(),
                        employee = employee,
                    )
            }

            pendingNotification?.let { NotificationService.notifyTableStatusUpdate(it) }

            // Session ended — the owner no longer needs a recoverable billing request for this table.
            PendingBillingRequestStore.clearForTable(tableId)
        }
    }

    override suspend fun importTablesCsv(
        csvContent: ByteArray,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto =
        ExceptionConverter.catchingThrows(
            context = "importTablesCsv(overwrite=$overwrite)",
        ) {
            logger.info("importTablesCsv: starting CSV import for tables")

            val csvData = CsvReader.parseCsvBytes(csvContent, delimiter = ';', hasHeader = true)
            if (csvData.isEmpty()) throw validationFailed("CSV file is empty or could not be parsed")

            var successCount = 0
            var errorCount = 0
            val errors = mutableListOf<String>()

            csvData.forEachIndexed { index, rowMap ->
                val rowNumber = index + 2
                try {
                    importTableRow(rowMap, overwrite)?.let { error ->
                        errors.add("Row $rowNumber: $error")
                        errorCount++
                    } ?: run { successCount++ }
                } catch (e: Exception) {
                    logger.error("importTablesCsv: error processing table row {}: {}", rowNumber, e.message, e)
                    errors.add("Row $rowNumber: ${e.message ?: "Unknown error"}")
                    errorCount++
                }
            }

            // Reset PostgreSQL sequence after CSV import to prevent duplicate key violations
            try {
                dbQuery {
                    TransactionManager.current().exec(
                        "SELECT setval('r_table_table_id_seq', COALESCE((SELECT MAX(table_id) FROM r_table), 1), true)",
                    )
                }
                logger.info("importTablesCsv: reset sequence for TABLE after CSV import")
            } catch (e: Exception) {
                logger.warn("importTablesCsv: failed to reset sequence for TABLE: {}", e.message)
            }

            val skippedCount = csvData.size - successCount - errorCount
            InitialSetupDto.CsvImportResultDto(
                totalRows = csvData.size,
                successCount = successCount,
                errorCount = errorCount,
                skippedCount = skippedCount,
                errors = errors,
                dataType = "TABLE",
                success = errorCount == 0,
                message = buildImportMessage(successCount, errorCount, skippedCount),
            )
        }

    /**
     * Imports a single table row from CSV map data.
     *
     * @return Error message if import fails, null if successful
     */
    private suspend fun importTableRow(
        rowMap: Map<String, String>,
        overwrite: Boolean,
    ): String? {
        return try {
            val id = rowMap["table_id"]?.toLongOrNull()
            val tableNumber = rowMap["table_number"] ?: return "Missing 'table_number' column"
            val seats = rowMap["seats"]?.toIntOrNull()
            val locationTypeName = rowMap["location_type"] ?: return "Missing 'location_type' column"
            val positionX = rowMap["position_x"]?.toFloatOrNull()
            val positionY = rowMap["position_y"]?.toFloatOrNull()
            val rotation = rowMap["rotation"]?.toFloatOrNull()
            val width = rowMap["width"]?.toFloatOrNull()
            val height = rowMap["height"]?.toFloatOrNull()

            dbQuery {
                try {
                    val locationType =
                        org.example.project.features.table.domain.models.LocationType
                            .find {
                                org.example.project.features.table.database.tables.LocationTypeTable.name eq
                                    locationTypeName.uppercase()
                            }.firstOrNull()
                            ?: return@dbQuery "Unknown location_type: $locationTypeName"

                    if (overwrite) {
                        tableRepository.updateTable(
                            id = id ?: throw IllegalArgumentException("Missing 'id' column"),
                            request =
                                RTableDto.UpdateRTableRequestDto(
                                    tableNumber = tableNumber,
                                    seats = seats,
                                    locationTypeId = locationType.id.value,
                                    positionX = positionX,
                                    positionY = positionY,
                                    rotation = rotation,
                                    width = width,
                                    height = height,
                                ),
                        )
                    } else {
                        tableRepository.createTable(
                            request =
                                RTableDto.CreateRTableRequestDto(
                                    tableNumber = tableNumber,
                                    seats = if (seats == 0) null else seats,
                                    locationTypeId = locationType.id.value,
                                    positionX = positionX,
                                    positionY = positionY,
                                    rotation = rotation,
                                    width = width,
                                    height = height,
                                ),
                        )
                    }
                    null
                } catch (e: IllegalArgumentException) {
                    if (e.message?.contains("Duplicate table number") == true) {
                        logger.info("importTableRow: table '{}' already exists, skipping", tableNumber)
                        null
                    } else {
                        e.message
                    }
                }
            }
        } catch (e: Exception) {
            e.message
        }
    }

    override suspend fun searchTables(
        query: String,
        limit: Int,
        includeInactive: Boolean,
    ): RTableDto.RTableListResponseDto =
        dbQuery {
            val tables = toResponsesWithOpenAmount(tableRepository.searchTablesByNumber(query, limit, includeInactive))
            logger.debug("searchTables: returned {} tables for query={}", tables.size, query)
            RTableDto.RTableListResponseDto(
                tables = tables,
                totalCount = tables.size,
                returnedCount = tables.size,
                appliedFilters =
                    RTableDto.FilterInfo(
                        status = null,
                        type = null,
                        limit = limit,
                        offset = null,
                    ),
            )
        }

    @OptIn(kotlin.time.ExperimentalTime::class)
    override suspend fun requestBilling(
        tableId: Long,
        requestingEmployeeId: String,
    ) {
        val (ownerEmployeeId, billingRequest) =
            dbQuery {
                val requestingEmployee =
                    Employee.findById(UUID.fromString(requestingEmployeeId))
                        ?: throw employeeNotFound(requestingEmployeeId)
                // No bill is registered under the station account, so it may not ask for one either
                // (OS-b) — checked before the table is read. This retires the station path the
                // 2026-09-14 ownership amendment routed through here. Attribution, not device: a
                // person's own login on the station device asks like anywhere else.
                // `requestedByStation` below stays for rows raised before.
                requireMayBillAs(requestingEmployee.role)
                val table = RTable.findById(tableId) ?: throw tableNotFound(tableId)
                val session =
                    table.currentSession ?: throw PosException(
                        PosErrorCode.TABLE_HAS_NO_SESSION,
                        HttpStatusCode.Conflict,
                        "Table $tableId has no active session",
                    )
                val ownerId =
                    session.employeeId.id.value
                        .toString()
                ownerId to
                    NotificationDto.BillingRequestNotificationDto(
                        timestamp =
                            kotlin.time.Clock.System
                                .now()
                                .toString(),
                        tableId = tableId,
                        tableNumber = table.tableNumber,
                        requestingEmployeeNumber = requestingEmployee.employeeNumber,
                        // Decided here because this is the one place the requesting employee row is
                        // already loaded, so the flag costs no extra query. The username is the only
                        // sound test for the shared device account — TerminalEmployee documents why
                        // its role and its employee number are not.
                        requestedByStation = TerminalEmployee.isDeviceAccount(requestingEmployee.username),
                    )
            }

        NotificationService.notifyBillingRequest(
            billingRequest = billingRequest,
            targetEmployeeId = ownerEmployeeId,
        )
    }

    private fun buildImportMessage(
        successCount: Int,
        errorCount: Int,
        skippedCount: Int,
    ): String {
        val parts = mutableListOf<String>()
        if (successCount > 0) parts.add("$successCount imported")
        if (skippedCount > 0) parts.add("$skippedCount skipped (duplicates)")
        if (errorCount > 0) parts.add("$errorCount failed")
        return if (parts.isEmpty()) {
            "No tables processed"
        } else {
            "TABLE: ${parts.joinToString(", ")}"
        }
    }

    override suspend fun getCombinablePartners(tableId: Long): List<Long> =
        dbQuery {
            tableRepository.getTableById(tableId) ?: throw tableNotFound(tableId)
            combinableTablesRepository.getPartners(tableId)
        }

    override suspend fun setCombinablePartners(
        tableId: Long,
        partnerIds: List<Long>,
    ): List<Long> {
        data class TableSnapshot(
            val number: String,
            val isActive: Boolean,
            val isVirtual: Boolean,
        )

        val tableSnapshot =
            dbQuery {
                val t = tableRepository.getTableById(tableId) ?: return@dbQuery null
                TableSnapshot(t.tableNumber, t.isActive, t.isVirtual)
            } ?: throw tableNotFound(tableId)

        if (!tableSnapshot.isActive) {
            throw validationFailed("Table ${tableSnapshot.number} is not active")
        }
        if (tableSnapshot.isVirtual) {
            throw validationFailed("Virtual tables cannot be combined")
        }

        for (partnerId in partnerIds) {
            if (partnerId == tableId) {
                throw validationFailed("Cannot combine table with itself")
            }
            val partnerSnapshot =
                dbQuery {
                    val p = tableRepository.getTableById(partnerId) ?: return@dbQuery null
                    TableSnapshot(p.tableNumber, p.isActive, p.isVirtual)
                } ?: throw tableNotFound(partnerId)
            if (!partnerSnapshot.isActive) {
                throw validationFailed("Partner table ${partnerSnapshot.number} is not active")
            }
            if (partnerSnapshot.isVirtual) {
                throw validationFailed("Virtual table ${partnerSnapshot.number} cannot be combined")
            }
        }

        return dbQuery {
            combinableTablesRepository.setPartners(tableId, partnerIds)
            combinableTablesRepository.getPartners(tableId)
        }
    }

    override suspend fun getAllCombinablePairs(): List<Pair<Long, Long>> =
        dbQuery {
            combinableTablesRepository.getAll()
        }

    override suspend fun setAllCombinablePairs(pairs: List<Pair<Long, Long>>): List<Pair<Long, Long>> =
        dbQuery {
            val partnerMap = mutableMapOf<Long, MutableList<Long>>()
            for ((a, b) in pairs) {
                partnerMap.getOrPut(a) { mutableListOf() }.add(b)
                partnerMap.getOrPut(b) { mutableListOf() }.add(a)
            }

            val existingPairs = combinableTablesRepository.getAll()
            val existingTableIds = (existingPairs.map { it.first } + existingPairs.map { it.second }).toSet()

            val newTableIds = partnerMap.keys
            for (tableId in existingTableIds) {
                if (tableId !in newTableIds) {
                    combinableTablesRepository.removeAllForTable(tableId)
                }
            }

            for ((tableId, partners) in partnerMap) {
                combinableTablesRepository.setPartners(tableId, partners.distinct())
            }

            combinableTablesRepository.getAll()
        }

    /**
     * Maps [entities] to responses carrying `openAmount` — the sum of the current session's OPEN
     * items, null when there is no session or nothing is open. The one computation every table read
     * uses: `GET /api/tables/{id}` used to skip it and answered `openAmount: null` for a table the
     * list showed with a balance (e2e backlog). One grouped query for the whole batch, not one per
     * table. Must run inside an active transaction.
     */
    private fun toResponsesWithOpenAmount(entities: List<RTable>): List<RTableDto.RTableResponseDto> {
        val sessionIds = entities.mapNotNull { it.currentSession?.id?.value }
        val openBySession = openAmountsBySession(sessionIds)
        return entities.map { entity ->
            val amount =
                entity.currentSession
                    ?.id
                    ?.value
                    ?.let { openBySession[it] }
                    ?.takeIf { it > BigDecimal.ZERO }
                    ?.toDecimalString()
            entity.toResponse().copy(openAmount = amount)
        }
    }

    private fun openAmountsBySession(sessionIds: List<Long>): Map<Long, BigDecimal> {
        if (sessionIds.isEmpty()) return emptyMap()
        val sumCol = OrderItemTable.finalPrice.sum()
        return OrderItemTable
            .select(OrderItemTable.sessionId, sumCol)
            .where {
                (OrderItemTable.sessionId inList sessionIds) and
                    (OrderItemTable.itemStatus eq ItemStatus.OPEN)
            }.groupBy(OrderItemTable.sessionId)
            .associate { row ->
                row[OrderItemTable.sessionId].value to (row[sumCol] ?: BigDecimal.ZERO)
            }
    }

    /** Must run inside a dbQuery transaction. Refuses to change or delete a system table (the virtual VOUCHER table). */
    private fun requireNotSystemTable(
        id: Long,
        action: String,
    ) {
        val existing = tableRepository.getTableById(id) ?: throw tableNotFound(id)
        if (existing.isSystem) {
            throw SystemRecordGuard.protectedRecord("Table", existing.tableNumber, action)
        }
    }

    private fun tableNotFound(id: Long) =
        PosException(
            PosErrorCode.TABLE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Table $id not found",
        )

    private fun employeeNotFound(idOrNumber: String) =
        PosException(
            PosErrorCode.EMPLOYEE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Employee '$idOrNumber' not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    private fun terminalCannotOwnSession(employee: Employee) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "Terminal account ${employee.employeeNumber} cannot own a table session",
        )
}

/** `table_session.note` is `varchar(500)`. */
private const val SESSION_NOTE_MAX = 500
