/**
 * Implementation of OrderService with business logic.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 2.
 */
package org.example.project.features.order.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.auth.PriceAuthority
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.mapper.websocket.createTableStatusUpdateDto
import org.example.project.core.utils.formatLocalDateTime
import org.example.project.core.websocket.NotificationService
import org.example.project.features.bill.domain.service.SaleSigningGateway
import org.example.project.features.bill.domain.service.SaleSigningKind
import org.example.project.features.bill.domain.service.SaleSigningRequest
import org.example.project.features.employee.database.tables.EmployeeInfoTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.domain.models.EmployeeInfo
import org.example.project.features.order.database.tables.ItemExtrasTable
import org.example.project.features.order.domain.KitchenTicketWaiter
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.order.domain.repository.OrderItemRepository
import org.example.project.features.order.domain.repository.OrderRepository
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.order.mapper.toGroupedOrderItemResponseDto
import org.example.project.features.order.mapper.toOrderResponseDto
import org.example.project.features.order.mapper.toOrderSummaryDto
import org.example.project.features.printer.database.tables.PrinterLocationTable
import org.example.project.features.printer.domain.models.PrinterLocation
import org.example.project.features.printer.domain.service.PrinterService
import org.example.project.features.product.database.tables.ChoiceGroupOptionTable
import org.example.project.features.product.database.tables.ChoiceGroupTable
import org.example.project.features.product.database.tables.ProductCategoryPrinterLocationTable
import org.example.project.features.product.database.tables.ProductExtrasTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.ChoiceGroup
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.models.ProductExtras
import org.example.project.features.product.domain.repository.ChoiceGroupRepository
import org.example.project.features.product.domain.repository.ProductChoiceGroupRepository
import org.example.project.features.setup.domain.service.ShopInfoService
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.table.domain.repository.RTableRepository
import org.example.project.features.table.domain.repository.TableSessionRepository
import org.example.project.features.table.domain.service.OwnerDecision
import org.example.project.features.table.domain.service.TableSessionOwnerPolicy
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.terminal.domain.requireSigningTerminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.builder.ProcessDataBuilder
import org.example.project.utils.config.VatMappingUtil
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.errorHandling.RetryConfig
import org.example.project.utils.errorHandling.withRetryThrows
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.toBigDecimal
import org.example.project.utils.log.AuditEntityRef
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.util.UUID
import kotlin.time.ExperimentalTime

class OrderServiceImpl(
    private val repository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val saleSigning: SaleSigningGateway,
    private val printerService: PrinterService,
    private val terminalRepository: TerminalRepository,
    private val shopInfoService: ShopInfoService,
    private val configManager: IShopConfigurationManager,
    private val choiceGroupRepository: ChoiceGroupRepository,
    private val productChoiceGroupRepository: ProductChoiceGroupRepository,
    private val tableRepository: RTableRepository,
    private val tableSessionRepository: TableSessionRepository,
    private val ownerPolicy: TableSessionOwnerPolicy,
) : OrderService {
    private val logger = LoggerFactory.getLogger(OrderServiceImpl::class.java)

    // Detached scope so kitchen/bar printing doesn't block order creation.
    // SupervisorJob keeps one failing print from cancelling siblings; failures
    // are still recorded by PrinterServiceImpl in print_job_history.
    private val printScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun getAllOrders(): List<OrderDto.OrderResponseDto> =
        dbQuery {
            repository.findAll().map { it.toOrderResponseDto() }.also {
                logger.info("OrderServiceImpl.getAllOrders: retrieved count=${it.size}")
            }
        }

    override suspend fun getOrderById(orderId: Long): OrderDto.OrderResponseDto? {
        if (orderId <= 0) throw validationFailed("Order ID must be positive")
        return dbQuery {
            repository.findById(orderId)?.toOrderResponseDto()
        }
    }

    override suspend fun getOrderByOrderNumber(orderNumber: String): OrderDto.OrderResponseDto? {
        if (orderNumber.isBlank()) throw validationFailed("Order number cannot be blank")
        return dbQuery {
            repository.findByOrderNumber(orderNumber)?.toOrderResponseDto()
        }
    }

    override suspend fun getOrdersByTableSessionId(tableSessionId: Long): List<OrderDto.OrderResponseDto> {
        if (tableSessionId <= 0) throw validationFailed("Table session ID must be positive")
        return dbQuery {
            repository.findByTableSessionId(tableSessionId).map { it.toOrderResponseDto(withTseData = true) }
        }
    }

    override suspend fun getOrdersByEmployeeId(employeeId: UUID): List<OrderDto.OrderResponseDto> =
        dbQuery {
            repository.findByEmployeeId(employeeId).map { it.toOrderResponseDto() }
        }

    override suspend fun getOrderSummaries(): List<OrderDto.OrderSummaryDto> =
        dbQuery {
            repository.findAll().map { it.toOrderSummaryDto() }
        }

    override suspend fun createOrder(
        request: OrderDto.CreateOrderRequestDto,
        employeeId: String,
        deviceId: String,
        actorCapabilities: Set<Capability>,
        allowVoucherProduct: Boolean,
    ): OrderDto.OrderResponseDto {
        // Data needed for TSE signing and printing after the DB transaction commits
        data class TseSigningData(
            val processData: String,
            val clientId: String,
            val referenceId: String,
            val orderNumber: String,
            val orderId: Long,
        )

        data class PrintJobData(
            val orderDto: OrderDto.OrderResponseDto,
            val printerId: Long,
            val language: Language,
        )

        data class OrderCreationResult(
            val orderResponse: OrderDto.OrderResponseDto,
            val tseSigningData: TseSigningData,
            val printJobs: List<PrintJobData>,
            val takeAway: Boolean,
            // The committed attempt ran the rotation pick, so the "next" employee moved on.
            val rotated: Boolean,
        )

        // Table status update emitted after Phase 1 commits (live open amount over SSE)
        var pendingTableNotification: NotificationDto.TableStatusUpdateDto? = null

        // Phase 1: DB transaction (retryable) — create order, items, collect TSE/print data
        val phase1 =
            // The audit wraps the retries (real-TSE QA rt14): a conflict a retry resolves is not a
            // failure and must not leave a FAILED row. Only the final outcome is audited.
            // Audited as CREATE_ORDER, exactly as `createBill` is audited as CREATE_BILL. The
            // wrapper was lost in the PosException rewrite (ffed62942), so orders wrote no
            // `transaction_audit_log` row at all while the Pruefprotokoll manual listed
            // CREATE_ORDER as audited. It matters for R5-1 in particular: a steep discount or a
            // custom price granted under a manager step-up must leave a row naming that manager
            // (`TEMPORARY_SESSION`), the same trail a lent BILL_VOID leaves.
            ExceptionConverter.catchingThrows(
                context = "createOrder(employeeId=$employeeId)",
                employeeId = employeeId,
                operation = "CREATE_ORDER",
                entityType = "ORDER",
                deviceId = deviceId,
                auditEntity = { AuditEntityRef(id = it.tseSigningData.orderId.toString(), name = it.tseSigningData.orderNumber) },
                block = {
                    withRetryThrows(operationName = "createOrder(tableSession=${request.tableInfoId})", config = RetryConfig.AGGRESSIVE) {
                        dbQuery(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
                            logger.info("OrderServiceImpl.createOrder: creating order for tableSessionId=${request.tableInfoId}")

                            // Hoisted, along with the terminal resolution + signing guard right below, so a
                            // fiscal-safety rejection fires BEFORE the order (or any of its items) is ever
                            // created — a throw here rolls back nothing because nothing has been written yet.
                            val mode = configManager.getTseMode()

                            // Resolve the terminal and enforce the fiscal-signing guard FAIL-FAST, before the
                            // order entity exists. Previously this ran late (right before signing, after the
                            // order + items were already created), so a rejection had to roll back an
                            // already-created order. See TransactionSigningGuard for the rule.
                            // requireSigningTerminal rejects non-terminal (admin-web) clients from fiscal ops
                            // and standardizes the not-found code to CARD_TERMINAL_NOT_FOUND (TerminalSigningGuard).
                            val terminal = requireSigningTerminal(terminalRepository, deviceId, mode)

                            // 1. Validate request
                            try {
                                request.validate()
                            } catch (e: IllegalArgumentException) {
                                throw validationFailed(e.message ?: "Invalid order request")
                            }

                            // 2. Find required entities
                            val employee =
                                Employee.findById(UUID.fromString(employeeId))
                                    ?: throw employeeNotFound(employeeId)

                            // 2a. No pricing under the station account (E-2). Before the capability
                            // checks in 4b and independent of them: a discount of up to 20 % needs no
                            // capability, so the station mask alone let an order station book 16,90 €
                            // at 15,21 €. Keyed on who the order is registered under, not on the
                            // device: a manager's own login at the station prices as the manager.
                            requireMayPriceLinesAs(request, employee.role)

                            // Resolve the session the order attaches to. Two paths:
                            //  - Structural (preferred): the client sends `tableId`; the SERVER
                            //    resolves the table's current active session under a table-row
                            //    lock (creating a fresh one if none is active). This makes the
                            //    server the source of truth and defeats stale session ids — a
                            //    stale `tableInfoId` is ignored when `tableId` is present.
                            //  - Legacy: the client sends only `tableInfoId` (a session id);
                            //    backward compatibility.
                            //
                            // LOCK ORDERING INVARIANT (project-wide): acquire the TABLE row lock
                            // BEFORE the SESSION row lock, and never lock a table after a session in
                            // the same transaction. Bill-creation/completion (BillServiceImpl) also
                            // locks TABLE-then-SESSION, so order-creation and bill-completion
                            // serialize on the table row: a bill can no longer complete a session
                            // while an order attaches new OPEN items to it via a disjoint lock. Do
                            // NOT introduce a SESSION-then-TABLE ordering anywhere (it would invert
                            // against this path and deadlock).
                            val requestedTableId = request.tableId
                            // Local to this attempt: a retried transaction decides the owner afresh, and
                            // only the attempt that commits reports (and publishes) its decision.
                            var ownerDecision: OwnerDecision? = null
                            val session: TableSession =
                                if (requestedTableId != null) {
                                    // Lock the table row first so concurrent opens/completions of the
                                    // same table serialize (mirrors RTableServiceImpl.getOrCreateSessionByTableId).
                                    val lockedTable =
                                        tableRepository.getTableByIdForUpdate(requestedTableId)
                                            ?: throw PosException(
                                                PosErrorCode.TABLE_NOT_FOUND,
                                                HttpStatusCode.NotFound,
                                                "Table not found: $requestedTableId",
                                            )
                                    // Who owns the session if this order OPENS one. Resolved only when the table
                                    // has no active session, so reusing one never touches the rotation cursor, and
                                    // resolved under the table lock before getOrCreateSession: r_table FOR UPDATE
                                    // -> rotationMutex -> configMutex, as in RTableServiceImpl. pickNextEmployee's
                                    // dbQuery joins THIS transaction (useNestedTransactions is off), so its cursor
                                    // write commits or rolls back with the order.
                                    val opensTable = tableSessionRepository.getActiveSessionByTableId(requestedTableId) == null
                                    // The first order IS the table's opening (the till no longer opens it with a
                                    // separate GET), so a new session's owner follows the explicit-open rule: the
                                    // named waiter, else the rotation pick, else the acting employee. An order
                                    // station never owns the session (spec 2026-09-14 §4.1). The till's expected
                                    // next employee only feeds `rotationShifted`; a mismatch never refuses the open.
                                    ownerDecision =
                                        if (opensTable) {
                                            ownerPolicy.ownerDecisionForNewSession(
                                                lockedTable,
                                                employee,
                                                request.forEmployeeNumber,
                                                request.expectedNextEmployeeId,
                                            )
                                        } else {
                                            null
                                        }
                                    val owner = ownerDecision?.employee ?: employee
                                    if (opensTable && lockedTable.isVirtual && !lockedTable.isTakeaway) {
                                        // QUICK_SALE / VOUCHER sessions are created by their own flows; one opened
                                        // here is unreachable from a floor plan and wedges the Z-Bon (E2E-2026-09-18 #4).
                                        // Checked after the owner so an order station keeps its own, more specific
                                        // 409 STATION_TABLE_VIRTUAL; a person's owner rule never rotates on a virtual
                                        // table, so reaching here touched no rotation cursor.
                                        throw PosException(
                                            PosErrorCode.TABLE_VIRTUAL_NOT_ORDERABLE,
                                            HttpStatusCode.Conflict,
                                            "Table ${lockedTable.tableNumber} is virtual and cannot be opened by an order",
                                        )
                                    }
                                    // startSession takes the session's training flag from its OWNER, and a rotated
                                    // owner is not the acting employee. Refuse before the session exists, so real
                                    // items never open a training session (and a trainee's never open a real one).
                                    if (owner.trainingMode != employee.trainingMode) {
                                        throw PosException(
                                            PosErrorCode.TRAINING_MODE_MISMATCH,
                                            HttpStatusCode.Conflict,
                                            "Cannot create order: rotated owner ${owner.employeeNumber} of table " +
                                                "${lockedTable.tableNumber} has training mode (${owner.trainingMode}), " +
                                                "employee training mode is (${employee.trainingMode})",
                                        )
                                    }
                                    // An already open session keeps its training flag; an order from the other
                                    // mode is refused here as the 409 the clients map, instead of the repository's
                                    // IllegalStateException reaching StatusPages as a 500 (QA finding NF-2).
                                    tableSessionRepository.getActiveSessionByTableId(lockedTable.id.value)?.let { open ->
                                        if (open.isTraining != owner.trainingMode) {
                                            throw PosException(
                                                PosErrorCode.TRAINING_MODE_MISMATCH,
                                                HttpStatusCode.Conflict,
                                                "Cannot create order: table ${lockedTable.tableNumber} has an open " +
                                                    "${if (open.isTraining) "training" else "real"} session, " +
                                                    "employee training mode is (${owner.trainingMode})",
                                            )
                                        }
                                    }
                                    // getOrCreateSession returns the active (completed=false) session,
                                    // or opens a fresh one under the table lock we now hold.
                                    val resolved = tableSessionRepository.getOrCreateSession(lockedTable, owner).second
                                    // Also lock the resolved session row (table-then-session) so this
                                    // order shares the session lock with bill-creation/completion.
                                    lockSessionRow(resolved.id.value) ?: resolved
                                } else {
                                    // Legacy path: we only have a session id, so read its table id with a
                                    // non-locking lookup (a session's table FK is immutable), lock the
                                    // table row FIRST, then lock the session row — keeping the
                                    // TABLE-before-SESSION ordering even though the auto-heal below may
                                    // write the table row.
                                    val requestedSessionId = request.tableInfoId
                                    val tableId =
                                        (
                                            TableSession.findById(requestedSessionId)
                                                ?: throw PosException(
                                                    PosErrorCode.TABLE_HAS_NO_SESSION,
                                                    HttpStatusCode.NotFound,
                                                    "TableSession not found: $requestedSessionId",
                                                )
                                        ).table.id.value
                                    tableRepository.getTableByIdForUpdate(tableId)
                                        ?: throw PosException(
                                            PosErrorCode.TABLE_NOT_FOUND,
                                            HttpStatusCode.NotFound,
                                            "Table not found: $tableId",
                                        )
                                    lockSessionRow(requestedSessionId)
                                        ?: throw PosException(
                                            PosErrorCode.TABLE_HAS_NO_SESSION,
                                            HttpStatusCode.NotFound,
                                            "TableSession not found: $requestedSessionId",
                                        )
                                }

                            // 2a. Reject writes to a completed session — belt-and-suspenders.
                            // The tableId path already resolves an active (completed=false)
                            // session, but the legacy tableInfoId path can still target a
                            // completed session. MUST come before the defensive auto-heal
                            // below, otherwise a completed session would be resurrected as
                            // the table's currentSession.
                            if (session.completed) {
                                throw PosException(
                                    PosErrorCode.SESSION_ALREADY_CLOSED,
                                    HttpStatusCode.Conflict,
                                    "Cannot create order: session ${session.id.value} is already completed",
                                )
                            }

                            // 2b. Defensive table status check — correct inconsistent state
                            val table = session.table
                            if (table.tableStatus != TableStatus.OCCUPIED) {
                                logger.warn("OrderServiceImpl.createOrder: table ${table.id.value} status is ${table.tableStatus}, correcting to OCCUPIED")
                                table.tableStatus = TableStatus.OCCUPIED
                                table.currentSession = session
                            }

                            // 2b-lock. Write the (already table-locked) row so this order and a
                            // concurrent bill-completion on the same table have a genuine
                            // write-write conflict, not just overlapping FOR UPDATE locks. Under
                            // REPEATABLE_READ a plain row lock gives mutual exclusion but does NOT
                            // refresh the other transaction's snapshot: without this write a bill
                            // could complete the session on a stale snapshot that never saw the new
                            // OPEN items below (the exact corruption this guards against). The write
                            // makes the loser take a serialization error and retry with a fresh
                            // snapshot — where it observes the committed OPEN items (bill then skips
                            // completion) or the completed session (order opens a fresh one). Do NOT
                            // remove this line; the FOR UPDATE locks alone do not close the race.
                            table.updateAt = currentTimeStamp()

                            // 2c. Validate training mode compatibility
                            if (session.isTraining != employee.trainingMode) {
                                throw PosException(
                                    PosErrorCode.TRAINING_MODE_MISMATCH,
                                    HttpStatusCode.Conflict,
                                    "Cannot create order: employee training mode (${employee.trainingMode}) " +
                                        "does not match session training mode (${session.isTraining})",
                                )
                            }

                            // 3. Create the main order
                            val createdOrder = repository.create(employee = employee, tableSession = session)

                            // Propagate training mode from employee to order
                            if (employee.trainingMode) {
                                createdOrder.isTraining = true
                            }

                            logger.info(
                                "OrderServiceImpl.createOrder: created orderId=${createdOrder.id.value}, orderNumber=${createdOrder.orderNumber}, training=${createdOrder.isTraining}",
                            )

                            // 4. Process order items with quantity handling
                            val createdOrderItems = mutableListOf<OrderItem>()
                            var orderTotal = BigDecimal.ZERO

                            if (request.orderItems.isEmpty()) {
                                throw orderHasNoItems()
                            }

                            // Batch-fetch all referenced products in one query (was one
                            // Product.findById per item — N+1). The per-item lookup below
                            // preserves the original PRODUCT_NOT_FOUND error per item.
                            val requestedProductIds = request.orderItems.map { it.productId }.distinct()
                            val productsById: Map<Long, Product> =
                                Product
                                    .find { ProductTable.id inList requestedProductIds }
                                    .associateBy { it.id.value }

                            for ((index, itemRequest) in request.orderItems.withIndex()) {
                                // 4a. Find product
                                val product =
                                    productsById[itemRequest.productId]
                                        ?: throw PosException(
                                            PosErrorCode.PRODUCT_NOT_FOUND,
                                            HttpStatusCode.NotFound,
                                            "Product not found: ${itemRequest.productId}",
                                        )

                                // 4a'. A voucher is sold through the voucher sale, never ordered (QA MD-10).
                                if (product.productType == ProductType.VOUCHER && !allowVoucherProduct) {
                                    throw voucherProductNotOrderable(product.productNumber)
                                }

                                // 4b. Validate custom price and high discount authority.
                                //
                                // Capability-based since Stage 3 (parent spec §5.5). This was one flag,
                                // `employee.role.isManagerOrHigher()`, gating both checks — so "may discount
                                // steeply" was a synonym for "is a manager" and could not be granted on its
                                // own. Both capabilities are seeded into MANAGER and nowhere else, so exactly
                                // the same people retain the ability: the swap separates two authorities, it
                                // does not narrow either.
                                //
                                // The set is the one the route gate resolved for this request (group, station
                                // mask, plus an active step-up), passed in as `actorCapabilities`. It used to
                                // be re-resolved here from the employee's GROUP alone, which could not see a
                                // step-up: a manager's PIN lent DISCOUNT_GRANT, the till unlocked 25 %, and
                                // this check then refused it with 403 (R5-1).
                                if (itemRequest.customPrice &&
                                    !PriceAuthority.mayUseCustomPrice(actorCapabilities, product.allowCustomPrice)
                                ) {
                                    throw PosException(
                                        PosErrorCode.CUSTOM_PRICE_NOT_ALLOWED,
                                        HttpStatusCode.Forbidden,
                                        "Employee ${employee.employeeNumber} does not have permission to set custom prices for product ${product.productNumber}",
                                    )
                                }

                                if (!PriceAuthority.mayApplyDiscount(actorCapabilities, itemRequest.discount)) {
                                    throw PosException(
                                        PosErrorCode.DISCOUNT_NOT_ALLOWED,
                                        HttpStatusCode.Forbidden,
                                        "Employee ${employee.employeeNumber} does not have permission to apply discounts greater than 20%",
                                    )
                                }

                                // 4c. Resolve grouped selections (preferred) or legacy flat extras. Sending both
                                // would price only the selections and silently drop the extras.
                                if (itemRequest.selections != null && !itemRequest.extras.isNullOrEmpty()) {
                                    throw PosException(
                                        PosErrorCode.VALIDATION_FAILED,
                                        HttpStatusCode.BadRequest,
                                        "Send an order line's options as selections or as extras, not both",
                                    )
                                }
                                val selections =
                                    itemRequest.selections
                                        ?: itemRequest.extras?.map {
                                            OrderItemDto.SelectedOptionRequestDto(optionId = it, groupId = null, quantity = 1)
                                        }
                                        ?: emptyList()

                                val (resolvedOptions, extrasDelta) =
                                    if (itemRequest.selections != null) {
                                        resolveSelections(product, selections)
                                    } else {
                                        // The legacy path carries no group, so it can never satisfy a
                                        // required group: run the min/max check with nothing chosen
                                        // first (QA MD-03). Omitting `selections` used to skip it and
                                        // book a product with a required group at its base price.
                                        resolveSelections(product, emptyList())
                                        resolveLegacyExtras(selections)
                                    }

                                // 4d. Determine VAT rate (VatMappingUtil returns Vat directly, not Result).
                                // Deposit / return lines: Anhang C ties a Warenumschließung to the MAIN
                                // product's rate — modeled via the PfandType's configured VAT class (one
                                // PfandType per rate family; see determineDepositVat, audit BPOS-10).
                                val vat =
                                    if (product.isDeposit || product.isDepositReturn) {
                                        VatMappingUtil.determineDepositVat(product)
                                    } else {
                                        VatMappingUtil.determineVat(product.productType, itemRequest.takeAway, configManager = configManager)
                                    }

                                // 4e. Calculate pricing
                                val discount = BigDecimal.valueOf(itemRequest.discount)

                                val unitPrice =
                                    if (!itemRequest.customPrice) {
                                        product.price
                                    } else {
                                        toBigDecimal(itemRequest.unitPrice)
                                    }

                                // money leaves the price computation at 2dp so everything downstream (VAT
                                // split, signed processData buckets, printed Beleg) agrees to the cent
                                // (round-3 TSER-5); scale-4 residue made the signed buckets truncate below
                                // the payment sum.
                                val finalPrice = ((unitPrice + extrasDelta) * discount).setScale(2, RoundingMode.HALF_UP)

                                if (itemRequest.quantity <= 0) {
                                    throw validationFailed("Item quantity must be positive")
                                }

                                // 4f. Create individual OrderItem entities for each quantity
                                repeat(itemRequest.quantity) {
                                    val orderItem =
                                        orderItemRepository.createOrderItem(
                                            order = createdOrder,
                                            session = session,
                                            product = product,
                                            unitPrice = unitPrice,
                                            finalPrice = finalPrice,
                                            itemStatus = ItemStatus.OPEN,
                                            vat = vat,
                                            discount = discount,
                                            customPrice = itemRequest.customPrice,
                                            takeAway = itemRequest.takeAway,
                                            sideNotes = itemRequest.sideNotes,
                                        )

                                    // 4g. Snapshot resolved options onto the order item
                                    resolvedOptions.forEach { opt ->
                                        ItemExtrasTable.insert {
                                            it[orderItemId] = orderItem.id.value
                                            it[productExtrasId] = opt.optionId
                                            it[optionNameSnapshot] = opt.name
                                            it[priceDeltaSnapshot] = opt.delta
                                            it[groupNameSnapshot] = opt.groupName
                                            // Resolution handle, not a label: lets this captured option be
                                            // re-priced off its (group, option) placement if the line is ever
                                            // re-submitted. Null on the legacy path.
                                            it[groupId] = opt.groupId
                                            it[quantity] = opt.quantity
                                        }
                                    }

                                    createdOrderItems.add(orderItem)
                                    orderTotal += finalPrice
                                }

                                logger.info(
                                    "OrderServiceImpl.createOrder: created ${itemRequest.quantity} items " +
                                        "for productId=${itemRequest.productId} (index=$index)",
                                )
                            }

                            // 5. Update order with totals
                            createdOrder.orderTotal = orderTotal
                            createdOrder.orderStatus = OrderStatus.CONFIRMED

                            logger.info("OrderServiceImpl.createOrder: completed total=$orderTotal, itemCount=${createdOrderItems.size}")

                            // 6. Collect TSE signing data (signing happens after transaction commits)
                            val processData =
                                ProcessDataBuilder.build(
                                    processType = ProcessType.BESTELLUNG_V1,
                                    items = createdOrder.items.toList().toGroupedOrderItemResponseDto(),
                                )

                            createdOrder.terminal = terminal // persist order origin (fixes DSFinV-K posTerminalId + sweeper parity)

                            val clientId = terminal.clientId

                            val tseSigningData =
                                TseSigningData(
                                    processData = processData,
                                    clientId = clientId,
                                    referenceId = createdOrder.id.value.toString(),
                                    orderNumber = createdOrder.orderNumber,
                                    orderId = createdOrder.id.value,
                                )

                            // 7. Collect print job data (printing happens after TSE signing)
                            val printJobs = mutableListOf<PrintJobData>()
                            val printEnabled = configManager.isPrinterSettingsEnabled()
                            if (printEnabled) {
                                // Resolve category → printer-location mappings with two set-based
                                // queries (was a per-item product→category→printerLocations
                                // lazy-load chain — one category + one junction query per item).
                                val categoryIdByProductId: Map<Long, Int> =
                                    ProductTable
                                        .select(ProductTable.id, ProductTable.categoryId)
                                        .where { ProductTable.id inList requestedProductIds }
                                        .associate { it[ProductTable.id].value to it[ProductTable.categoryId].value }

                                val printerLocationsByCategoryId: Map<Int, List<PrinterLocation>> =
                                    (ProductCategoryPrinterLocationTable innerJoin PrinterLocationTable)
                                        .selectAll()
                                        .where {
                                            ProductCategoryPrinterLocationTable.productCategoryId inList
                                                categoryIdByProductId.values.distinct()
                                        }.toList()
                                        .groupBy(
                                            keySelector = { it[ProductCategoryPrinterLocationTable.productCategoryId].value },
                                            valueTransform = { PrinterLocation.wrapRow(it) },
                                        )

                                // createdOrderItems holds exactly the items of createdOrder, in
                                // insertion (id) order; item.product resolves from the warm entity
                                // cache populated by the batch fetch above (no extra queries).
                                val itemsByPrinterLocation: Map<PrinterLocation, List<OrderItem>> =
                                    createdOrderItems
                                        .flatMap { item ->
                                            printerLocationsByCategoryId[categoryIdByProductId[item.product.id.value]]
                                                .orEmpty()
                                                .map { printerLocation ->
                                                    printerLocation to item
                                                }
                                        }.groupBy(
                                            keySelector = { it.first },
                                            valueTransform = { it.second },
                                        )

                                // emu21 NOTE-x: a station order is attributed to the shared terminal
                                // account; the ticket names the table's waiter instead.
                                val waiterLabel =
                                    KitchenTicketWaiter.label(
                                        orderedBy = createdOrder.employee.let { KitchenTicketWaiter.Staff(it.employeeNumber, it.role) },
                                        sessionWaiter = session.employeeId.let { KitchenTicketWaiter.Staff(it.employeeNumber, it.role) },
                                        stationName = terminal.name,
                                    )

                                itemsByPrinterLocation.forEach { (printerLocation, items) ->
                                    if (printerLocation.isActive) {
                                        logger.info(
                                            "OrderServiceImpl.createOrder: printer location '${printerLocation.printerLocation}' has ${items.size} items to print",
                                        )
                                        printJobs.add(
                                            PrintJobData(
                                                orderDto = createdOrder.toOrderResponseDto(items).copy(employeeId = waiterLabel),
                                                printerId = printerLocation.printerSetup.id.value,
                                                language = printerLocation.language,
                                            ),
                                        )
                                    }
                                }
                            }

                            // 8. Build response DTO (must be inside dbQuery for Exposed lazy loading)
                            val orderResponse = createdOrder.toOrderResponseDto().withRotationOutcome(ownerDecision)

                            // 8a. Build live table status update (carries the session's open amount)
                            pendingTableNotification =
                                createTableStatusUpdateDto(
                                    table = table,
                                    status = TableStatus.OCCUPIED,
                                    employee = session.employeeId,
                                    sessionId = session.id.value,
                                    sessionStartTime = session.startTime.toString(),
                                    isTraining = session.isTraining,
                                )

                            OrderCreationResult(
                                orderResponse = orderResponse,
                                tseSigningData = tseSigningData,
                                printJobs = printJobs,
                                takeAway = table.locationTypeRef.isTakeaway,
                                rotated = ownerDecision?.rotated == true,
                            )
                        }
                    }
                },
            )

        val (orderResponse, tseSigningData, printJobs, takeAway, rotated) = phase1

        // Emit live table status update (open amount) now that Phase 1 has committed.
        pendingTableNotification?.let { NotificationService.notifyTableStatusUpdate(it) }
        // Phase 1 committed the cursor and holds no table/rotation/config lock any more. A failed or
        // rolled-back open never gets here, so it publishes nothing; a retried one publishes once.
        if (rotated) RotationNextNotifier.publishCurrent()

        // Phase 2: TSE signing through the sale-signing gateway (mode, then failure behaviour, then sign)
        //
        // Fiscal/registration guard now enforced FAIL-FAST at the top of Phase 1 (pre-commit,
        // before the order was even created) — see TransactionSigningGuard.assertMaySign above.
        // A post-commit check here could no longer roll back an already-paid, unsigned order.

        val signingRequest =
            SaleSigningRequest(
                kind = SaleSigningKind.ORDER,
                processData = tseSigningData.processData,
                referenceId = tseSigningData.referenceId,
                clientId = tseSigningData.clientId,
                entityType = TseEntityType.ORDER,
            )
        val decision = saleSigning.attempt(signingRequest)
        val signedTx = decision.transaction

        dbQuery {
            val order = Orders.findById(tseSigningData.orderId)!!
            if (signedTx != null) order.tseTransaction = signedTx
            order.tseSigningStatus = decision.status
        }

        if (decision.enqueue) {
            try {
                saleSigning.enqueue(signingRequest, decision)
            } catch (eq: PosException) {
                logger.error("OrderServiceImpl.createOrder: failed to queue order ${tseSigningData.orderNumber}: code=${eq.code} msg=${eq.message}", eq)
                dbQuery { Orders.findById(tseSigningData.orderId)!!.tseSigningStatus = TseSigningStatus.PENDING }
            }
        }

        decision.rethrow?.let {
            // FAIL mode: Phase 1 already committed the order; cancel it and reject.
            logger.error("OrderServiceImpl.createOrder: TSE signing failed for ${tseSigningData.orderNumber}, FAIL mode — cancelling order", it)
            dbQuery { Orders.findById(tseSigningData.orderId)!!.orderStatus = OrderStatus.CANCELLED }
            throw PosException(
                PosErrorCode.TSE_SIGNING_FAILED,
                HttpStatusCode.ServiceUnavailable,
                "TSE signing failed for order ${tseSigningData.orderNumber}",
            )
        }

        // Phase 3: Print (fire-and-forget — detached so an offline printer
        // doesn't keep the HTTP response waiting on TCP connect/write).
        printJobs.forEach { job ->
            printScope.launch {
                try {
                    printerService.printOrder(job.orderDto, job.printerId, job.language, takeAway)
                } catch (e: Exception) {
                    logger.warn("OrderServiceImpl.createOrder: printing failed for order ${tseSigningData.orderNumber}, printer ${job.printerId}: ${e.message}")
                }
            }
        }

        return orderResponse
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun referencePrint(
        tableId: Long,
        orderItemIds: List<Long>,
        employeeId: String,
    ) {
        val result =
            dbQuery {
                val printEnabled = configManager.isPrinterSettingsEnabled()
                if (!printEnabled) {
                    throw PosException(
                        PosErrorCode.PRINTING_DISABLED,
                        HttpStatusCode.Conflict,
                        "Printing is not enabled",
                    )
                }

                val employee =
                    Employee.findById(UUID.fromString(employeeId))
                        ?: throw employeeNotFound(employeeId)

                val table =
                    RTable.findById(tableId)
                        ?: throw tableNotFound(tableId)

                val tableSession =
                    table.currentSession
                        ?: throw PosException(
                            PosErrorCode.TABLE_HAS_NO_SESSION,
                            HttpStatusCode.NotFound,
                            "No active session for table $tableId",
                        )

                // Get items: specific IDs or all open items for the table session
                val items =
                    if (orderItemIds.isNotEmpty()) {
                        orderItemIds
                            .mapNotNull { itemId ->
                                OrderItem.findById(itemId)
                            }.filter { it.itemStatus != ItemStatus.CANCELLED }
                    } else {
                        tableSession.orderItems.toList().filter {
                            it.itemStatus != ItemStatus.CANCELLED && it.itemStatus != ItemStatus.PAID
                        }
                    }

                if (items.isEmpty()) {
                    throw orderHasNoItems()
                }

                // Map to DTOs inside dbQuery for Exposed lazy loading
                val itemDtos = items.toGroupedOrderItemResponseDto()

                val shopInfo =
                    shopInfoService.getShopInfoById(1L)
                        ?: throw PosException(
                            PosErrorCode.INTERNAL_ERROR,
                            HttpStatusCode.NotFound,
                            "No default shop found",
                        )

                val shopHeader =
                    ShopHeaderData(
                        shopName = shopInfo.shopName,
                        shopAddress = shopInfo.shopAddress,
                        shopCity = shopInfo.shopCity,
                        zipCode = shopInfo.zipCode?.toString(),
                        shopPhone = shopInfo.shopPhone,
                        shopTaxNumber = shopInfo.shopTaxNumber,
                    )

                val now =
                    kotlin.time.Clock.System
                        .now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                val dateTime = now.toString().formatLocalDateTime(Language.GERMAN)

                printerService.printReferencePrint(
                    shopHeader = shopHeader,
                    tableNumber = table.tableNumber,
                    employeeNumber = employee.employeeNumber,
                    dateTime = dateTime,
                    items = itemDtos,
                )
            }
        // Thrown after dbQuery returns so the failure cannot roll back anything the print wrote.
        if (!result.success) {
            throw PosException(PosErrorCode.PRINTER_UNAVAILABLE, HttpStatusCode.BadGateway, result.message)
        }
    }

    /**
     * Resolves grouped selections to (snapshotRows, totalDelta) using the authoritative
     * per-placement delta from choice_group_option, and validates min/max per attached group.
     */
    private fun resolveSelections(
        product: Product,
        selections: List<OrderItemDto.SelectedOptionRequestDto>,
    ): Pair<List<ResolvedOption>, BigDecimal> {
        val attachedGroupIds = productChoiceGroupRepository.getGroupIdsForProduct(product.id.value)
        val resolved = mutableListOf<ResolvedOption>()
        var total = BigDecimal.ZERO
        val perGroupCount = mutableMapOf<Int, Int>()

        // Batch-load groups, option placements and extras up front (was one
        // getOptions + ChoiceGroup.findById + ProductExtras.findById per
        // selection, plus one ChoiceGroup.findById per attached group). The
        // lookups below throw the same errors at the same points as before.
        val selectionGroupIds = selections.mapNotNull { it.groupId }.distinct()
        val optionsByGroupId = selectionGroupIds.associateWith { choiceGroupRepository.getOptions(it) }
        val groupIds = (selectionGroupIds + attachedGroupIds).distinct()
        val groupsById: Map<Int, ChoiceGroup> =
            if (groupIds.isEmpty()) {
                emptyMap()
            } else {
                ChoiceGroup.find { ChoiceGroupTable.id inList groupIds }.associateBy { it.id.value }
            }
        val optionIds = selections.map { it.optionId }.distinct()
        val extrasById: Map<Int, ProductExtras> =
            if (optionIds.isEmpty()) {
                emptyMap()
            } else {
                ProductExtras.find { ProductExtrasTable.id inList optionIds }.associateBy { it.id.value }
            }

        selections.forEach { sel ->
            val gid =
                sel.groupId
                    ?: throw PosException(
                        PosErrorCode.CHOICE_SELECTION_INVALID,
                        HttpStatusCode.BadRequest,
                        "Option ${sel.optionId} has no group",
                    )
            if (gid !in attachedGroupIds) {
                throw PosException(
                    PosErrorCode.CHOICE_SELECTION_INVALID,
                    HttpStatusCode.BadRequest,
                    "Group $gid is not attached to product ${product.id.value}",
                )
            }
            // Locate the placement row (group_id, option_id) for the authoritative delta.
            val placement =
                optionsByGroupId.getValue(gid).firstOrNull { row ->
                    row[ChoiceGroupOptionTable.optionId].value == sel.optionId
                }
                    ?: throw PosException(
                        PosErrorCode.CHOICE_SELECTION_INVALID,
                        HttpStatusCode.BadRequest,
                        "Option ${sel.optionId} is not in group $gid",
                    )
            val delta = placement[ChoiceGroupOptionTable.priceDelta]
            val group =
                groupsById[gid]
                    ?: throw PosException(
                        PosErrorCode.CHOICE_SELECTION_INVALID,
                        HttpStatusCode.BadRequest,
                        "Group $gid not found",
                    )
            // A deleted (inactive) extra is no longer offered by its groups; a stale till must not
            // still sell it (admin21 NOTE-c21).
            val extra =
                extrasById[sel.optionId]?.takeIf { it.isActive }
                    ?: throw PosException(
                        PosErrorCode.CHOICE_SELECTION_INVALID,
                        HttpStatusCode.BadRequest,
                        "Option ${sel.optionId} is no longer offered",
                    )
            val name = extra.name
            resolved += ResolvedOption(sel.optionId, name, delta, group.name, gid, sel.quantity)
            total += delta.multiply(BigDecimal(sel.quantity))
            perGroupCount[gid] = (perGroupCount[gid] ?: 0) + sel.quantity
        }

        // Validate min/max for every attached group.
        attachedGroupIds.forEach { gid ->
            val group = groupsById[gid] ?: return@forEach
            if (!group.isActive) return@forEach
            val chosen = perGroupCount[gid] ?: 0
            if (chosen < group.minSelections || chosen > group.maxSelections) {
                throw PosException(
                    PosErrorCode.CHOICE_SELECTION_INVALID,
                    HttpStatusCode.BadRequest,
                    "Group '${group.name}' requires ${group.minSelections}..${group.maxSelections} selections, got $chosen",
                )
            }
        }
        return resolved to total
    }

    private fun voucherProductNotOrderable(productNumber: String) =
        PosException(
            PosErrorCode.VOUCHER_PRODUCT_NOT_ORDERABLE,
            HttpStatusCode.BadRequest,
            "Product $productNumber is a voucher; sell it through the voucher sale (POST /api/vouchers), not as an order line",
        )

    /** Legacy path: each extra contributes its own `price` as the delta; group name is null. */
    private fun resolveLegacyExtras(selections: List<OrderItemDto.SelectedOptionRequestDto>): Pair<List<ResolvedOption>, BigDecimal> {
        val resolved = mutableListOf<ResolvedOption>()
        var total = BigDecimal.ZERO
        // Batch-load extras in one query (was one findById per extra); the
        // per-selection lookup preserves the original EXTRA_NOT_FOUND error.
        val optionIds = selections.map { it.optionId }.distinct()
        val extrasById: Map<Int, ProductExtras> =
            if (optionIds.isEmpty()) {
                emptyMap()
            } else {
                ProductExtras.find { ProductExtrasTable.id inList optionIds }.associateBy { it.id.value }
            }
        selections.forEach { sel ->
            val extra =
                extrasById[sel.optionId]
                    ?: throw PosException(
                        PosErrorCode.EXTRA_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "ProductExtras not found: ${sel.optionId}",
                    )
            resolved += ResolvedOption(sel.optionId, extra.name, extra.price, null, null, sel.quantity)
            total += extra.price.multiply(BigDecimal(sel.quantity))
        }
        return resolved to total
    }

    private data class ResolvedOption(
        val optionId: Int,
        val name: String,
        val delta: BigDecimal,
        val groupName: String?,
        /** Null on the legacy flat-extras path, which has no group. */
        val groupId: Int?,
        val quantity: Int,
    )

    /**
     * Acquires a `SELECT … FOR UPDATE` row lock on the given table session.
     * MUST be called only after the owning table row is already locked, to keep
     * the project-wide TABLE-before-SESSION lock order. Returns null if the
     * session no longer exists.
     */
    private fun lockSessionRow(sessionId: Long): TableSession? =
        TableSession
            .find { TableSessionTable.id eq sessionId }
            .forUpdate()
            .firstOrNull()

    /**
     * Refuses an order in which any line is discounted or custom-priced when the order is registered
     * under an employee who may not price lines (`PriceAuthority.mayPriceLinesAs` — the order
     * station's shared account) — the server half of the order station's missing discount/price
     * controls (finding E-2, rule 8).
     *
     * Reuses `DISCOUNT_NOT_ALLOWED` / `CUSTOM_PRICE_NOT_ALLOWED`: the caller is not allowed that
     * discount or price, which is exactly what those codes already tell the client.
     */
    private fun requireMayPriceLinesAs(
        request: OrderDto.CreateOrderRequestDto,
        attributedRole: EmployeeRole,
    ) {
        if (PriceAuthority.mayPriceLinesAs(attributedRole)) return
        if (request.orderItems.any { it.customPrice }) {
            throw PosException(
                PosErrorCode.CUSTOM_PRICE_NOT_ALLOWED,
                HttpStatusCode.Forbidden,
                "A custom price cannot be set under the station account",
            )
        }
        if (request.orderItems.any { PriceAuthority.isDiscounted(it.discount) }) {
            throw PosException(
                PosErrorCode.DISCOUNT_NOT_ALLOWED,
                HttpStatusCode.Forbidden,
                "A discount cannot be applied under the station account",
            )
        }
    }

    /**
     * Tells the till the table went to someone other than the employee it showed as next. Only a shifted
     * decision sets the fields; otherwise the response keeps its defaults. Must run inside the transaction
     * (reads the owner's first name).
     */
    private fun OrderDto.OrderResponseDto.withRotationOutcome(decision: OwnerDecision?): OrderDto.OrderResponseDto {
        if (decision == null || !decision.rotationShifted) return this
        val owner = decision.employee
        return copy(
            rotationShifted = true,
            rotationOwner =
                RotationDto.EmployeeRefDto(
                    employeeId = owner.id.value.toString(),
                    employeeNumber = owner.employeeNumber,
                    displayName = EmployeeInfo.find { EmployeeInfoTable.employeeId eq owner.id.value }.singleOrNull()?.firstName,
                ),
        )
    }

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    private fun employeeNotFound(employeeId: String) =
        PosException(
            PosErrorCode.EMPLOYEE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Employee not found: $employeeId",
        )

    private fun tableNotFound(tableId: Long) =
        PosException(
            PosErrorCode.TABLE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Table not found: $tableId",
        )

    private fun orderHasNoItems() =
        PosException(
            PosErrorCode.ORDER_HAS_NO_ITEMS,
            HttpStatusCode.BadRequest,
            "Order has no items",
        )
}
