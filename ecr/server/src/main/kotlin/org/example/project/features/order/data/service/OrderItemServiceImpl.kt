package org.example.project.features.order.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.domain.enums.voucher.VoucherInvalidationSource
import org.example.project.core.domain.enums.voucher.VoucherStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.mapper.websocket.createTableStatusUpdateDto
import org.example.project.core.websocket.NotificationService
import org.example.project.features.bill.domain.service.SaleSigningGateway
import org.example.project.features.bill.domain.service.SaleSigningKind
import org.example.project.features.bill.domain.service.SaleSigningRequest
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.ItemExtras
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.repository.CancellationRepository
import org.example.project.features.order.domain.repository.ItemTransferRepository
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.order.mapper.negativeItemQuantity
import org.example.project.features.order.mapper.toGroupedOrderItemResponseDto
import org.example.project.features.order.mapper.toOrderItemResponseDto
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.table.domain.repository.RTableRepository
import org.example.project.features.table.domain.repository.TableSessionRepository
import org.example.project.features.table.domain.service.TableSessionOwnerPolicy
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.terminal.domain.requireSigningTerminal
import org.example.project.features.voucher.database.tables.VoucherTable
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.TrainingFilter
import org.example.project.utils.builder.ProcessDataBuilder
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.errorHandling.RetryConfig
import org.example.project.utils.errorHandling.withRetryThrows
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.formatDecimal
import org.example.project.utils.formatter.toDecimalString
import org.example.project.utils.log.AuditEntityRef
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.dao.with
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/**
 * Concrete implementation of [OrderItemService].
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 2.
 */
class OrderItemServiceImpl(
    private val cancellationRepository: CancellationRepository,
    private val transferRepository: ItemTransferRepository,
    private val tableSessionRepository: TableSessionRepository,
    private val tableRepository: RTableRepository,
    private val saleSigning: SaleSigningGateway,
    private val terminalRepository: TerminalRepository,
    private val configManager: IShopConfigurationManager,
    private val ownerPolicy: TableSessionOwnerPolicy,
) : OrderItemService {
    private val logger = LoggerFactory.getLogger(OrderItemServiceImpl::class.java)

    override suspend fun getItemForTableSession(
        sessionId: Long,
        itemStatus: String?,
    ): List<OrderItemDto.OrderItemResponseDto> =
        dbQuery {
            val items =
                if (itemStatus != null) {
                    val status =
                        try {
                            ItemStatus.valueOf(itemStatus.uppercase())
                        } catch (e: IllegalArgumentException) {
                            throw PosException(
                                PosErrorCode.VALIDATION_FAILED,
                                HttpStatusCode.BadRequest,
                                "Invalid itemStatus value: $itemStatus",
                            )
                        }
                    OrderItem
                        .find {
                            (OrderItemTable.sessionId eq sessionId) and (OrderItemTable.itemStatus eq status)
                        }.toList()
                } else {
                    OrderItem.find { OrderItemTable.sessionId eq sessionId }.toList()
                }
            items.toGroupedOrderItemResponseDto()
        }

    override suspend fun getCancellations(
        startDate: String,
        endDate: String,
    ): List<OrderItemDto.CancellationResponseDto> =
        dbQuery {
            val startDateTime =
                try {
                    LocalDateTime.parse(startDate)
                } catch (e: IllegalArgumentException) {
                    throw validationFailed("Invalid startDate: $startDate")
                }
            val endDateTime =
                try {
                    LocalDateTime.parse(endDate)
                } catch (e: IllegalArgumentException) {
                    throw validationFailed("Invalid endDate: $endDate")
                }

            val query =
                (CancellationTable.createdAt greaterEq startDateTime) and
                    (CancellationTable.createdAt lessEq endDateTime) and
                    TrainingFilter.cancellationNotTraining()

            // Eager-load the relation graph touched by the mapping below so a large date
            // range returns in a handful of queries instead of N+1 per cancellation row.
            Cancellation
                .find { query }
                .with(
                    Cancellation::orderItem,
                    Cancellation::employeeId,
                    OrderItem::order,
                    OrderItem::product,
                    OrderItem::vat,
                    OrderItem::session,
                    OrderItem::itemExtras,
                    TableSession::table,
                    ItemExtras::productExtras,
                ).map { cancellation ->
                    val orderItem = cancellation.orderItem
                    val employee = cancellation.employeeId
                    OrderItemDto.CancellationResponseDto(
                        cancellationId = cancellation.id.value,
                        orderItemId = orderItem.toOrderItemResponseDto(),
                        canceledAmount = orderItem.finalPrice.toDecimalString(),
                        reason = cancellation.reason,
                        employeeNumber = employee.employeeNumber,
                        createdAt = cancellation.createdAt.toString(),
                        sessionId = orderItem.session.id.value,
                        tableNumber = orderItem.session.table.tableNumber,
                    )
                }
        }

    override suspend fun getTransferHistory(tableSessionId: Long): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto> =
        dbQuery {
            val transfers = transferRepository.getTransfersByTableSessionId(tableSessionId)
            transfers
                .map { transfer ->
                    val isIncoming = transfer.toTableSession.id.value == tableSessionId
                    val itemIds = transfer.transferredItemIds()
                    val items =
                        if (itemIds.isNotEmpty()) {
                            OrderItem.find { OrderItemTable.id inList itemIds }.toList()
                        } else {
                            emptyList()
                        }
                    val totalAmount = items.sumOf { it.finalPrice }

                    ItemTransferHistoryDto.ItemTransferHistoryResponseDto(
                        transferId = transfer.id.value,
                        direction = if (isIncoming) "IN" else "OUT",
                        fromTableNumber = transfer.fromTableSession.table.tableNumber,
                        toTableNumber = transfer.toTableSession.table.tableNumber,
                        employeeName = transfer.employee.employeeNumber,
                        transferTime = transfer.transferTime.toString(),
                        items = items.toGroupedOrderItemResponseDto(),
                        totalAmount = formatDecimal(totalAmount),
                    )
                }.sortedBy { it.transferTime }
        }

    override suspend fun getTransfers(
        startDate: String,
        endDate: String,
    ): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto> =
        dbQuery {
            val startDateTime =
                try {
                    LocalDateTime.parse(startDate)
                } catch (e: IllegalArgumentException) {
                    throw validationFailed("Invalid startDate: $startDate")
                }
            val endDateTime =
                try {
                    LocalDateTime.parse(endDate)
                } catch (e: IllegalArgumentException) {
                    throw validationFailed("Invalid endDate: $endDate")
                }

            val transfers =
                transferRepository
                    .getTransfersBetween(startDateTime, endDateTime)
                    // Exclude transfers where either session is training
                    .filter { !it.fromTableSession.isTraining && !it.toTableSession.isTraining }

            transfers
                .map { transfer ->
                    val itemIds = transfer.transferredItemIds()
                    val items =
                        if (itemIds.isNotEmpty()) {
                            OrderItem.find { OrderItemTable.id inList itemIds }.toList()
                        } else {
                            emptyList()
                        }
                    val totalAmount = items.sumOf { it.finalPrice }

                    ItemTransferHistoryDto.ItemTransferHistoryResponseDto(
                        transferId = transfer.id.value,
                        direction = if (transfer.fromTableSession.id.value == transfer.toTableSession.id.value) "IN" else "OUT",
                        fromTableNumber = transfer.fromTableSession.table.tableNumber,
                        toTableNumber = transfer.toTableSession.table.tableNumber,
                        employeeName = transfer.employee.employeeNumber,
                        transferTime = transfer.transferTime.toString(),
                        items = items.toGroupedOrderItemResponseDto(),
                        totalAmount = formatDecimal(totalAmount),
                    )
                }.sortedBy { it.transferTime }
        }

    /**
     * `CANCEL_ORDER_ITEMS` has always been in [ExceptionConverter.shouldAuditOperation]'s critical
     * list and `OrderItemRoutes` documents that the cancellation "is recorded on the TSE and in the
     * audit trail" — but nothing in this package ever called the wrapper that consults that list,
     * so a Storno left `transaction_audit_log` untouched (E2E-2026-09-20 #4). The `operation`
     * string in the route is the *approval-request* name for `requirePermissionOrDefer`, not an
     * audit write.
     *
     * The audit row is written by the wrapper, which also stamps `authorization_type` from any
     * elevation in force, or `APPROVAL_REQUEST` with the approving manager when this runs as a
     * deferred action — `DeferredActionExecutor` puts an `ApprovalAuthorization` on the coroutine
     * context, because the route has already answered 202 and no `AuthorizationResult` reaches
     * this layer (E2E-2026-09-20 round 2, R2-1).
     */
    override suspend fun cancelOrderItems(
        item: List<Long>,
        employeeId: String,
        deviceId: String,
        reason: String?,
        sourceTableId: Long,
    ): OrderItemDto.OrderItemOperationResultDto =
        ExceptionConverter.catchingThrows(
            context = "cancelOrderItems(items=${item.size}, sourceTableId=$sourceTableId)",
            employeeId = employeeId,
            entityType = "OrderItem",
            operation = "CANCEL_ORDER_ITEMS",
            deviceId = deviceId,
            auditEntity = { AuditEntityRef(id = sourceTableId.toString(), name = it.message) },
        ) {
            cancelOrderItemsInternal(item, employeeId, deviceId, reason, sourceTableId)
        }

    private suspend fun cancelOrderItemsInternal(
        item: List<Long>,
        employeeId: String,
        deviceId: String,
        reason: String?,
        sourceTableId: Long,
    ): OrderItemDto.OrderItemOperationResultDto {
        data class TseSigningData(
            /**
             * M2: ONE payload covering every item in this cancel action, signed ONCE — exactly as
             * an order of N items is signed once over all its lines. The N `Cancellation` rows are
             * stamped with [batchId] so the export emits them as ONE Vorgang carrying N Bonpos
             * lines, which is what makes a single signature verifiable against it.
             */
            val processData: String,
            val clientId: String,
            val referenceId: String,
            val batchId: String,
            val cancelledItemIds: List<Long>,
        )

        data class CancellationResult(
            val operationResult: OrderItemDto.OrderItemOperationResultDto,
            val tseSigningData: TseSigningData,
        )

        // Phase 1: DB transaction (retryable) — cancel items, collect TSE data
        val phase1 =
            withRetryThrows(
                operationName = "cancelOrderItems(table=$sourceTableId, items=${item.size})",
                config = RetryConfig.AGGRESSIVE,
            ) {
                dbQuery(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
                    // Hoisted, along with the terminal resolution + signing guard right below, so a
                    // fiscal-safety rejection fires BEFORE any Cancellation row is ever created — a
                    // throw here rolls back nothing because nothing has been written yet.
                    val mode = configManager.getTseMode()

                    // Resolve the terminal and enforce the fiscal-signing guard FAIL-FAST, before any
                    // cancellation entity exists. Previously this ran late (right before signing,
                    // after the cancellation rows + item-status updates were already made), so a
                    // rejection had to roll back an already-committed cancellation. See
                    // TransactionSigningGuard for the rule.
                    // requireSigningTerminal rejects non-terminal (admin-web) clients from fiscal ops
                    // and standardizes the not-found code to CARD_TERMINAL_NOT_FOUND (TerminalSigningGuard).
                    val terminal = requireSigningTerminal(terminalRepository, deviceId, mode)

                    // 1. Find required entities
                    val employee =
                        Employee.findById(UUID.fromString(employeeId))
                            ?: throw employeeNotFound(employeeId)

                    val table =
                        RTable.findById(sourceTableId)
                            ?: throw tableNotFound(sourceTableId)

                    val session =
                        table.currentSession
                            ?: throw validationFailed("No active session for table: $sourceTableId")

                    if (session.completed) {
                        throw PosException(
                            PosErrorCode.SESSION_ALREADY_CLOSED,
                            HttpStatusCode.Conflict,
                            "Cannot cancel items: session ${session.id.value} is already completed",
                        )
                    }

                    // 1b. Validate training mode
                    if (session.isTraining != employee.trainingMode) {
                        throw PosException(
                            PosErrorCode.TRAINING_MODE_MISMATCH,
                            HttpStatusCode.Conflict,
                            "Cannot cancel items: employee training mode (${employee.trainingMode}) " +
                                "does not match session training mode (${session.isTraining})",
                        )
                    }

                    // 2. Validate items
                    val orderItems =
                        session.orderItems.filter {
                            item.contains(it.id.value) && it.itemStatus == ItemStatus.OPEN
                        }
                    if (orderItems.isEmpty() || orderItems.size != item.size) {
                        throw validationFailed(
                            "Invalid item IDs: expected ${item.size}, found ${orderItems.size}",
                        )
                    }

                    // 3. Collect TSE signing data — ONE payload for the whole cancel action (M2).
                    //
                    // Anhang I p.113/117: the reversal sign lives in MENGE only; the Preis stays the
                    // Brutto-Einzelpreis. Negating both signed a semantically positive order, and
                    // string-prefixing an already-negative deposit price produced the malformed
                    // "--0.25" (round-3 TSER-7).
                    //
                    // One payload, one signature, one Vorgang — mirroring createOrder, which signs once
                    // over all of an order's lines. The batch id below is what lets the export emit
                    // these N rows as that single Vorgang instead of N separate Belege.
                    val processData =
                        ProcessDataBuilder.build(
                            processType = ProcessType.BESTELLUNG_V1,
                            items =
                                orderItems
                                    .toGroupedOrderItemResponseDto()
                                    .negativeItemQuantity(),
                        )
                    val batchId = UUID.randomUUID().toString()

                    val clientId = terminal.clientId

                    // 4. Create cancellation records and update item status
                    orderItems.forEach { orderItem ->
                        cancellationRepository
                            .createCancellation(
                                employee = employee,
                                orderItem = orderItem,
                                reason = reason,
                                transaction = null,
                                isTraining = employee.trainingMode,
                                // M2: every row from this ONE cancel action carries the same batch id, so the
                                // export emits them as ONE Vorgang with N Bonpos lines — the shape the single
                                // signature actually covers.
                            ).also { it.batchId = batchId }
                        orderItem.itemStatus = ItemStatus.CANCELLED
                    }

                    // 4b. Invalidate vouchers linked to cancelled items
                    orderItems
                        .filter { it.product.productNumber == "VOU" }
                        .forEach { orderItem ->
                            val voucher = Voucher.find { VoucherTable.orderItem eq orderItem.id }.firstOrNull()
                            if (voucher != null &&
                                voucher.status == VoucherStatus.ACTIVE &&
                                voucher.remainingValue.compareTo(voucher.originalValue) == 0
                            ) {
                                voucher.invalidate(
                                    reason = "Order cancelled${reason?.let { ": $it" } ?: ""}",
                                    source = VoucherInvalidationSource.ORDER_CANCELLED,
                                )
                                logger.info(
                                    "OrderItemServiceImpl.cancelOrderItems: invalidated voucher " +
                                        "${voucher.voucherNumber} for cancelled order item ${orderItem.id.value}",
                                )
                            }
                        }

                    val cancelledTotal = orderItems.sumOf { it.finalPrice }.toDecimalString()
                    val operationResult =
                        OrderItemDto.OrderItemOperationResultDto.forCancellation(
                            itemIds = orderItems.map { it.id.value },
                            totalAmount = cancelledTotal,
                        )

                    // 5. Complete session if no remaining open items (the one closing rule)
                    val completedSession = tableSessionRepository.completeIfNoOpenItems(session.id.value)
                    if (completedSession != null) {
                        val statusUpdate =
                            createTableStatusUpdateDto(
                                table = completedSession.table,
                                status = completedSession.table.tableStatus,
                                sessionId = completedSession.id.value,
                                sessionStartTime = completedSession.startTime.toString(),
                                // The session's OWNER, not the acting employee — the same
                                // distinction `openedTableCard` draws for transfers. Sending the
                                // canceller relabelled the table on every device that took the
                                // push, until someone forced an authoritative fetch
                                // (E2E-2026-09-20 #5).
                                employee = completedSession.employeeId,
                            )
                        NotificationService.notifyTableStatusUpdate(statusUpdate)
                    } else {
                        // Items remain open — table stays OCCUPIED; emit so the live open amount refreshes.
                        // Emitted in-transaction to mirror the adjacent completion branch; SSE is a hint, corrected by the next event / authoritative fetch.
                        val statusUpdate =
                            createTableStatusUpdateDto(
                                table = session.table,
                                status = TableStatus.OCCUPIED,
                                // See the completed branch above: the card names the session's
                                // waiter, who may not be the employee cancelling.
                                employee = session.employeeId,
                                sessionId = session.id.value,
                                sessionStartTime = session.startTime.toString(),
                                isTraining = session.isTraining,
                            )
                        NotificationService.notifyTableStatusUpdate(statusUpdate)
                    }

                    CancellationResult(
                        operationResult = operationResult,
                        tseSigningData =
                            TseSigningData(
                                processData = processData,
                                clientId = clientId,
                                referenceId = batchId,
                                batchId = batchId,
                                cancelledItemIds = orderItems.map { it.id.value },
                            ),
                    )
                }
            }

        val (operationResult, tseSigningData) = phase1

        // Phase 2: TSE signing via shared resolver — ONE transaction for the whole cancel action,
        // matching the ONE Vorgang the export emits for this batch (M2).
        // The gateway reads the mode, then the failure behaviour, then signs.
        //
        // Fiscal/registration guard now enforced FAIL-FAST at the top of Phase 1 (pre-commit,
        // before any cancellation row was created) — see TransactionSigningGuard.assertMaySign
        // above. A post-commit check here could no longer roll back an already-committed
        // cancellation.
        val signingRequest =
            SaleSigningRequest(
                kind = SaleSigningKind.ORDER,
                processData = tseSigningData.processData,
                referenceId = tseSigningData.referenceId,
                clientId = tseSigningData.clientId,
                entityType = TseEntityType.CANCELLATION,
            )
        val decision = saleSigning.attempt(signingRequest)
        val signedTx = decision.transaction

        dbQuery {
            tseSigningData.cancelledItemIds.forEach { itemId ->
                val cancellation = Cancellation.find { CancellationTable.orderItemId eq itemId }.firstOrNull()
                if (cancellation != null) {
                    if (signedTx != null) cancellation.tseTransaction = signedTx
                    cancellation.tseSigningStatus = decision.status
                }
            }
        }

        if (decision.enqueue) {
            // referenceId is the batch id — one queue entry re-drives the whole Vorgang. It used to
            // be `item.joinToString(";")`, which overflows TseSigningQueueTable.referenceId
            // (varchar(100)) at ~12 items; and the call was unguarded, so the resulting 22001
            // escaped as a 500 AFTER the cancellation had committed, stranding it unsigned with no
            // queue row at all.
            try {
                saleSigning.enqueue(signingRequest.copy(referenceId = tseSigningData.batchId), decision)
            } catch (eq: Exception) {
                logger.error(
                    "OrderItemServiceImpl.cancelOrderItems: failed to queue cancellation batch " +
                        "${tseSigningData.batchId} — it stays PENDING for the sweeper",
                    eq,
                )
                dbQuery {
                    tseSigningData.cancelledItemIds.forEach { itemId ->
                        Cancellation
                            .find { CancellationTable.orderItemId eq itemId }
                            .firstOrNull()
                            ?.tseSigningStatus = TseSigningStatus.PENDING
                    }
                }
            }
        }

        decision.rethrow?.let {
            // FAIL mode: the whole batch is one Vorgang and it went unsigned, so revert all of it.
            logger.error("OrderItemServiceImpl.cancelOrderItems: TSE signing failed, FAIL mode — reverting cancellation", it)
            dbQuery {
                tseSigningData.cancelledItemIds.forEach { itemId ->
                    OrderItem.findById(itemId)?.itemStatus = ItemStatus.OPEN
                    Cancellation.find { CancellationTable.orderItemId eq itemId }.forEach { it.delete() }
                }
            }
            throw PosException(
                PosErrorCode.TSE_SIGNING_FAILED,
                HttpStatusCode.ServiceUnavailable,
                "TSE signing failed for cancellation of items ${tseSigningData.cancelledItemIds}",
            )
        }

        return operationResult
    }

    /** Audited for the same reason as [cancelOrderItems] — see its KDoc. */
    override suspend fun transferOrderItems(
        item: List<Long>,
        employeeId: String,
        deviceId: String,
        targetTableId: Long,
        currentTableId: Long,
        reason: String?,
        ownerBasisEmployeeId: String,
    ): OrderItemDto.OrderItemOperationResultDto =
        ExceptionConverter.catchingThrows(
            context = "transferOrderItems(from=$currentTableId, to=$targetTableId, items=${item.size})",
            employeeId = employeeId,
            entityType = "OrderItem",
            operation = "TRANSFER_ORDER_ITEMS",
            deviceId = deviceId,
            auditEntity = { AuditEntityRef(id = targetTableId.toString(), name = "${it.message} | Grund: $reason") },
        ) {
            transferOrderItemsInternal(item, employeeId, deviceId, targetTableId, currentTableId, reason, ownerBasisEmployeeId)
        }

    private suspend fun transferOrderItemsInternal(
        item: List<Long>,
        employeeId: String,
        deviceId: String,
        targetTableId: Long,
        currentTableId: Long,
        reason: String?,
        ownerBasisEmployeeId: String,
    ): OrderItemDto.OrderItemOperationResultDto {
        // Table cards are built inside the transaction (lazy loading) but sent only after it commits:
        // a retried attempt can pick a different rotated owner, and a rolled-back one announces nothing.
        val (transferResult, tableCards, rotated) =
            withRetryThrows(
                operationName = "transferOrderItems(from=$currentTableId, to=$targetTableId, items=${item.size})",
                config = RetryConfig.AGGRESSIVE,
            ) {
                dbQuery(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
                    transferInTransaction(item, employeeId, targetTableId, currentTableId, reason, ownerBasisEmployeeId)
                }
            }
        tableCards.forEach { NotificationService.notifyTableStatusUpdate(it) }
        // A station's transfer onto a free table advanced the rotation cursor. The transaction has committed
        // and no table/rotation/config lock is held any more, which publishCurrent requires. A rolled-back
        // attempt never gets here; a retried one publishes once.
        if (rotated) RotationNextNotifier.publishCurrent()
        return transferResult
    }

    /**
     * The body of [transferOrderItems], run inside its REPEATABLE_READ transaction.
     *
     * Lock order: `r_table FOR UPDATE` on the source AND target rows, ascending by id → session rows
     * (written by getOrCreateSession, the item moves and completeSession) → `rotationMutex` →
     * `configMutex` (both inside the owner policy's rotation pick). Tables come first, in one global
     * order, so opposite transfers cannot deadlock each other, and neither can a concurrent
     * `createOrder`, which holds its table lock while it waits for `rotationMutex`. Postgres cannot
     * detect a cycle that runs through that in-process mutex.
     *
     * Under REPEATABLE_READ, the `FOR UPDATE` locks above give mutual exclusion but do NOT by
     * themselves refresh a concurrent transaction's snapshot — a transfer into an already-open
     * target writes no table or session row of its own, so a bill that snapshotted the target
     * table earlier would otherwise wait on the lock and then complete the session without ever
     * seeing the moved-in OPEN items. The write to every locked table's `updateAt` right after the
     * target lookup below forces that bill to retry against a fresh snapshot instead.
     */
    private suspend fun transferInTransaction(
        item: List<Long>,
        employeeId: String,
        targetTableId: Long,
        currentTableId: Long,
        reason: String?,
        ownerBasisEmployeeId: String,
    ): Triple<OrderItemDto.OrderItemOperationResultDto, List<NotificationDto.TableStatusUpdateDto>, Boolean> {
        val tableCards = mutableListOf<NotificationDto.TableStatusUpdateDto>()

        // 1. Find required entities
        val employee =
            Employee.findById(UUID.fromString(employeeId))
                ?: throw employeeNotFound(employeeId)

        val lockedTables =
            listOf(currentTableId, targetTableId)
                .distinct()
                .sorted()
                .associateWith { tableRepository.getTableByIdForUpdate(it) }

        val currentSession =
            tableSessionRepository.getActiveSessionByTableId(currentTableId)
                ?: throw validationFailed("No active session for table: $currentTableId")

        // 1b. Validate training mode
        if (currentSession.isTraining != employee.trainingMode) {
            throw PosException(
                PosErrorCode.TRAINING_MODE_MISMATCH,
                HttpStatusCode.Conflict,
                "Cannot transfer items: employee training mode (${employee.trainingMode}) " +
                    "does not match source session training mode (${currentSession.isTraining})",
            )
        }

        val targetTable = lockedTables[targetTableId] ?: throw tableNotFound(targetTableId)

        // 1c-lock. Write every locked table row so a bill that took its REPEATABLE_READ snapshot
        // before this transfer started has a genuine write-write conflict, not just an overlapping
        // FOR UPDATE lock. Under REPEATABLE_READ a plain row lock gives mutual exclusion but does
        // NOT refresh the other transaction's snapshot: a transfer into an already-open target
        // writes no table or session row of its own, so without this write a bill could complete
        // the session on a stale snapshot that never saw the moved-in OPEN items
        // (BillServiceImpl.kt ~1891) — the same corruption OrderServiceImpl guards against at its
        // 2b-lock. The write makes the loser take a serialization error and retry with a fresh
        // snapshot. Do NOT remove this line; the FOR UPDATE locks alone do not close the race.
        lockedTables.values.forEach { it?.updateAt = currentTimeStamp() }

        // 2. Validate items — before the owner/session resolution below, so an invalid item list
        // never advances the in-memory rotation cursor on an attempt that is about to roll back.
        val orderItems = currentSession.orderItems.filter { item.contains(it.id.value) }
        if (orderItems.isEmpty() || orderItems.size != item.size) {
            throw validationFailed(
                "Invalid item IDs: expected ${item.size}, found ${orderItems.size}",
            )
        }

        // If the transfer OPENS the target table, an order station never owns that session; a
        // person still does (spec 2026-09-14 §4.1, §8). Resolved only when no session is active.
        // The owner follows whoever ASKED (the station, or the cashier at a till), not a manager lent to
        // that device to authorise it: [employee] is the actor on the records, never the table's owner.
        val ownerBasis =
            if (ownerBasisEmployeeId == employeeId) {
                employee
            } else {
                Employee.findById(UUID.fromString(ownerBasisEmployeeId)) ?: throw employeeNotFound(ownerBasisEmployeeId)
            }
        val ownerDecision =
            if (tableSessionRepository.getActiveSessionByTableId(targetTableId) == null) {
                ownerPolicy.ownerDecisionForBookingOpen(targetTable, ownerBasis)
            } else {
                null
            }
        val targetOwner = ownerDecision?.employee ?: employee
        val rotated = ownerDecision?.rotated == true
        // startSession takes the session's training flag from its OWNER, and a rotated owner is not the
        // acting employee. Refuse before the session exists, so real items never move into a training
        // session (and a trainee's never into a real one).
        if (targetOwner.trainingMode != employee.trainingMode) {
            throw PosException(
                PosErrorCode.TRAINING_MODE_MISMATCH,
                HttpStatusCode.Conflict,
                "Cannot transfer items: rotated owner ${targetOwner.employeeNumber} of table " +
                    "${targetTable.tableNumber} has training mode (${targetOwner.trainingMode}), " +
                    "employee training mode is (${employee.trainingMode})",
            )
        }
        val targetSession =
            tableSessionRepository.getOrCreateSession(
                employee = targetOwner,
                table = targetTable,
            )

        // 3. Record one transfer event covering all items, then update each item.
        // Validation above guarantees every item belongs to currentSession,
        // so a single fromTable is correct.
        transferRepository.createTransferBatch(
            itemIds = orderItems.map { it.id.value },
            fromTable = currentSession,
            toTable = targetSession.second,
            employee = employee,
            reason = reason,
        )
        orderItems.forEach { orderItem ->
            orderItem.session = targetSession.second
            orderItem.transferred = true
        }

        val transferredTotal = orderItems.sumOf { it.finalPrice }.toDecimalString()
        val operationResult =
            OrderItemDto.OrderItemOperationResultDto.forTransfer(
                itemIds = orderItems.map { it.id.value },
                targetTableId = targetTableId,
                totalAmount = transferredTotal,
            )

        // 4. Complete the current session when nothing OPEN is left on it (the one closing rule).
        val completedSession = tableSessionRepository.completeIfNoOpenItems(currentSession.id.value)

        if (completedSession == null) {
            // BOTH cards go out on BOTH exits of this function. Here the source session survives
            // with fewer items, so its openAmount changed: without its card every floor plan keeps
            // the pre-transfer total until someone taps refresh (E2E-2026-09-15 N6). The target's
            // amount changed as well — whether this transfer opened it or it was already occupied —
            // so its card is never conditional on a new session having been created.
            tableCards += openedTableCard(currentSession)
            tableCards += openedTableCard(targetSession.second)
            return Triple(operationResult, tableCards, rotated)
        }

        // 5. The source session was completed as all its open items were transferred.
        tableCards +=
            createTableStatusUpdateDto(
                table = completedSession.table,
                status = completedSession.table.tableStatus,
                sessionId = completedSession.id.value,
                sessionStartTime = completedSession.startTime.toString(),
                employee = employee,
            )
        // The target card, unconditionally — the same rule as the early exit above. Guarding it
        // on `targetSession.first` (which means getOrCreateSession CREATED a session, i.e. the
        // target was free) left an already-occupied target showing its pre-transfer total on
        // every other device whenever the source emptied out (E2E-2026-09-16 F14). The early
        // return above makes the two exits mutually exclusive, so this cannot double-emit.
        tableCards += openedTableCard(targetSession.second)
        return Triple(operationResult, tableCards, rotated)
    }

    /**
     * Card for a table whose session is open — the transfer's target, whether this transfer opened
     * that session or joined one that already existed, or the source while it survives the move.
     * It names the session's owner, who may not be the acting employee.
     */
    private fun openedTableCard(session: TableSession) =
        createTableStatusUpdateDto(
            table = session.table,
            status = session.table.tableStatus,
            sessionId = session.id.value,
            sessionStartTime = session.startTime.toString(),
            employee = session.employeeId,
        )

    // --- PosException helpers ---

    private fun employeeNotFound(id: String) =
        PosException(
            PosErrorCode.EMPLOYEE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Employee not found: $id",
        )

    private fun tableNotFound(id: Long) =
        PosException(
            PosErrorCode.TABLE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Table not found: $id",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
