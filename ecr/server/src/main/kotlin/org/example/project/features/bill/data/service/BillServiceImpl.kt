package org.example.project.features.bill.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.employee.isTerminal
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.tse.BonType
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.domain.enums.voucher.VoucherInvalidationSource
import org.example.project.core.domain.enums.voucher.VoucherStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.error.PosErrorCode
import org.example.project.core.mapper.websocket.createTableStatusUpdateDto
import org.example.project.core.utils.ItemPromotionCap
import org.example.project.core.utils.RebookTender
import org.example.project.core.utils.isStandingSale
import org.example.project.core.websocket.NotificationService
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.RebookEligibility
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.bill.domain.repository.BillRepository
import org.example.project.features.bill.domain.requireMayBillAs
import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.bill.domain.service.SaleSigningGateway
import org.example.project.features.bill.domain.service.SaleSigningKind
import org.example.project.features.bill.domain.service.SaleSigningRequest
import org.example.project.features.bill.mapper.rebookFacts
import org.example.project.features.bill.mapper.toResponseDto
import org.example.project.features.bill.mapper.toResponseDtos
import org.example.project.features.bill.mapper.toSummaryDtos
import org.example.project.features.bill.mapper.toTseQrCodeDataDto
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.ItemExtrasTable
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.ItemExtras
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.payment.domain.requireActivePaymentType
import org.example.project.features.printer.domain.service.PrinterService
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.table.domain.repository.RTableRepository
import org.example.project.features.table.domain.repository.TableSessionRepository
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.terminal.domain.requireSigningTerminal
import org.example.project.features.voucher.database.tables.VoucherTable
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.infrastructure.errors.PosException
import org.example.project.money.Decimal
import org.example.project.utils.NonTaxableTipCarrier
import org.example.project.utils.OwnerTipApportionment
import org.example.project.utils.OwnerTipBasis
import org.example.project.utils.PromotionGroupApportionment
import org.example.project.utils.PromotionGroupBasis
import org.example.project.utils.TestOnlyApi
import org.example.project.utils.builder.ProcessDataBuilder
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.formatDecimal
import org.example.project.utils.formatter.toBigDecimal
import org.example.project.utils.log.AuditEntityRef
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.exceptions.EntityNotFoundException
import org.jetbrains.exposed.v1.jdbc.*
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.util.UUID

/**
 * Implementation of [BillService].
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 2.
 */
class BillServiceImpl(
    private val repository: BillRepository,
    private val tableSessionRepository: TableSessionRepository,
    private val printerService: PrinterService,
    private val saleSigning: SaleSigningGateway,
    private val terminalRepository: TerminalRepository,
    private val configManager: IShopConfigurationManager,
    private val tableRepository: RTableRepository,
    private val invoices: BillInvoiceLink,
) : BillService {
    private val logger = LoggerFactory.getLogger(BillServiceImpl::class.java)

    /**
     * A single bill's response plus its Nachsignierung note. Only single-bill reads and the print
     * path use it — on a list it would cost one query per row.
     */
    private fun Bill.toDetailDto(): BillDto.BillResponseDto {
        val note = saleSigning.signingNoteFor(TseEntityType.BILL, id.value.toString())
        return toResponseDto(invoices).copy(tseSigningNote = note)
    }

    override suspend fun getAllBills(includeDeleted: Boolean): List<BillDto.BillResponseDto> =
        dbQuery {
            val bills = repository.findAll()
            val filtered = if (includeDeleted) bills else bills.filter { it.deletingReason == null }
            filtered.toResponseDtos(invoices)
        }

    override suspend fun getBillById(billId: String): BillDto.BillResponseDto =
        dbQuery {
            val bill = repository.findById(billId.toLong()) ?: throw billNotFound(billId)
            bill.toDetailDto()
        }

    override suspend fun getBillByNumber(billNumber: String): BillDto.BillResponseDto =
        dbQuery {
            val bill = repository.findByBillNumber(billNumber) ?: throw billNotFoundByNumber(billNumber)
            bill.toDetailDto()
        }

    override suspend fun getBillByTseTransactionNumber(transactionNumber: Long): BillDto.BillResponseDto =
        dbQuery {
            val bill =
                repository.findByTseTransactionNumber(transactionNumber)
                    ?: throw PosException(
                        PosErrorCode.BILL_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Bill not found for TSE transaction number $transactionNumber",
                    )
            bill.toDetailDto()
        }

    override suspend fun getBillsByEmployeeId(
        employeeId: String,
        includeDeleted: Boolean,
    ): List<BillDto.BillResponseDto> =
        dbQuery {
            val employee =
                Employee.findById(UUID.fromString(employeeId))
                    ?: throw employeeNotFound(employeeId)

            val bills = repository.findByEmployee(employee)
            val filtered = if (includeDeleted) bills else bills.filter { it.deletingReason == null }
            filtered.toResponseDtos(invoices)
        }

    override suspend fun getBillsByTableSessionId(
        tableSessionId: Long,
        includeDeleted: Boolean,
    ): List<BillDto.BillResponseDto> =
        dbQuery {
            val tableSession =
                TableSession.findById(tableSessionId)
                    ?: throw PosException(
                        PosErrorCode.TABLE_HAS_NO_SESSION,
                        HttpStatusCode.NotFound,
                        "TableSession not found: $tableSessionId",
                    )

            val bills = repository.findByTableSessionId(tableSession.id.value)
            val filtered = if (includeDeleted) bills else bills.filter { it.deletingReason == null }
            filtered.toResponseDtos(invoices)
        }

    override suspend fun getBillsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        includeDeleted: Boolean,
    ): List<BillDto.BillResponseDto> =
        dbQuery {
            val bills = repository.findByDateRange(startDate, endDate)
            val filtered = if (includeDeleted) bills else bills.filter { it.deletingReason == null }
            filtered.toResponseDtos(invoices)
        }

    override suspend fun getBillsByAmountRange(
        minAmount: BigDecimal,
        maxAmount: BigDecimal,
        includeDeleted: Boolean,
    ): List<BillDto.BillResponseDto> =
        dbQuery {
            val bills = repository.findByAmountRange(minAmount, maxAmount)
            val filtered = if (includeDeleted) bills else bills.filter { it.deletingReason == null }
            filtered.toResponseDtos(invoices)
        }

    override suspend fun printBill(
        billId: String,
        employeeId: String,
        bewirtungsbeleg: Boolean,
    ) {
        try {
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
                    val employeeUuid = UUID.fromString(employeeId)
                    val employee =
                        Employee.findById(employeeUuid)
                            ?: throw EntityNotFoundException(EntityID(id = employeeUuid, EmployeeTable), Employee)
                    val billIdLong = billId.toLong()
                    val bill =
                        Bill.findById(billIdLong)
                            ?: throw EntityNotFoundException(EntityID(id = billIdLong, BillTable), Bill)

                    val detail = bill.toDetailDto()
                    if (bewirtungsbeleg) requireBewirtungsbelegable(detail)
                    printerService.printBill(detail, employee, bewirtungsbeleg = bewirtungsbeleg)
                }
            // Thrown after dbQuery returns so the failure cannot roll back the print audit row.
            if (!result.success) {
                throw PosException(PosErrorCode.PRINTER_UNAVAILABLE, HttpStatusCode.BadGateway, result.message)
            }
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            logger.error("BillServiceImpl.printBill: failed to print billId=$billId", e)
            throw e
        }
    }

    override suspend fun createBill(
        employeeId: String,
        deviceId: String,
        request: BillDto.CreateBillRequestDto,
    ): BillDto.BillResponseDto {
        data class BillCreationResult(
            val billResponse: BillDto.BillResponseDto,
            val signing: BillSigning,
        )

        // Phase 1: DB transaction (retryable) — create bill, collect TSE data.
        // The audit wraps the retries, not the other way round: a bill-number conflict that a retry
        // resolves is contention, not a failure, and wrote an ERROR trace plus a FAILED CREATE_BILL
        // row for a bill that exists (real-TSE QA rt14). Only the final outcome is audited.
        val phase1 =
            ExceptionConverter.catchingThrows(
                context = "createBill(employeeId=$employeeId)",
                employeeId = employeeId,
                operation = "CREATE_BILL",
                entityType = "BILL",
                deviceId = deviceId,
                // The bill this call created. `signing` carries the committed row's own
                // primary key and Beleg number, so the audit row joins straight to `bill`.
                auditEntity = { AuditEntityRef(id = it.signing.billId.toString(), name = it.signing.billNumber) },
                block = {
                    BillWriteConflict.retrying(operationName = "createBill(employeeId=$employeeId, tableSessionId=${request.tableSessionId})") {
                        dbQuery(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
                            // Hoisted, along with the terminal resolution + signing guard right below,
                            // so a fiscal-safety rejection fires BEFORE the bill entity is ever created
                            // — a throw here rolls back nothing because nothing has been written yet.
                            val mode = configManager.getTseMode()

                            // Resolve the terminal and enforce the fiscal-signing guard FAIL-FAST,
                            // before the bill entity exists. Previously this ran late (right before
                            // signing, after createBillInternal had already created the bill, marked
                            // items PAID, and recorded payments), so a rejection had to roll back an
                            // already-created, already-paid bill. See TransactionSigningGuard for the
                            // rule.
                            // requireSigningTerminal rejects non-terminal (admin-web) clients from fiscal ops
                            // and standardizes the not-found code to CARD_TERMINAL_NOT_FOUND (TerminalSigningGuard).
                            val terminal = requireSigningTerminal(terminalRepository, deviceId, mode)

                            // The employee the bill is registered under.
                            val employee =
                                Employee.findById(UUID.fromString(employeeId))
                                    ?: throw employeeNotFound(employeeId)

                            // No bill under the station account (OS-b), before anything is written.
                            // Attribution, not device: a person's own login on the station device bills.
                            requireMayBillAs(employee.role)

                            // 1. Validate the request
                            try {
                                request.validate()
                            } catch (e: IllegalArgumentException) {
                                throw validationFailed(e.message ?: "Invalid bill request")
                            }

                            // LOCK ORDERING INVARIANT (project-wide): acquire the TABLE row
                            // lock BEFORE the SESSION row lock, then never lock a table after
                            // a session in the same transaction.
                            //
                            // Bill completion writes the table row (`table.reset()` via
                            // completeSession). Order-creation resolves/creates the session
                            // under a table-row lock. Both therefore take TABLE-then-SESSION,
                            // so a bill completing a session and an order attaching new OPEN
                            // items to that same session serialize on the table row instead of
                            // racing across disjoint locks.
                            //
                            // We only have the session id here, so read the session's table id
                            // with a non-locking lookup first (a session's table FK is
                            // immutable, so this read needs no lock), lock the table row, then
                            // lock the session row.
                            val tableId =
                                (
                                    TableSession.findById(request.tableSessionId)
                                        ?: throw PosException(
                                            PosErrorCode.TABLE_HAS_NO_SESSION,
                                            HttpStatusCode.NotFound,
                                            "TableSession not found: ${request.tableSessionId}",
                                        )
                                ).table.id.value

                            tableRepository.getTableByIdForUpdate(tableId)
                                ?: throw PosException(
                                    PosErrorCode.TABLE_NOT_FOUND,
                                    HttpStatusCode.NotFound,
                                    "Table not found: $tableId",
                                )

                            // Lock the session row so bill-creation serializes against
                            // concurrent order-creation, and reject an already-completed
                            // session.
                            val session =
                                TableSession
                                    .find { TableSessionTable.id eq request.tableSessionId }
                                    .forUpdate()
                                    .firstOrNull()
                                    ?: throw PosException(
                                        PosErrorCode.TABLE_HAS_NO_SESSION,
                                        HttpStatusCode.NotFound,
                                        "TableSession not found: ${request.tableSessionId}",
                                    )

                            if (session.completed) {
                                throw PosException(
                                    PosErrorCode.SESSION_ALREADY_CLOSED,
                                    HttpStatusCode.Conflict,
                                    "Cannot create bill: session ${request.tableSessionId} is already completed",
                                )
                            }

                            if (session.isTraining != employee.trainingMode) {
                                throw PosException(
                                    PosErrorCode.TRAINING_MODE_MISMATCH,
                                    HttpStatusCode.Conflict,
                                    "Cannot create bill: employee training mode (${employee.trainingMode}) " +
                                        "does not match session training mode (${session.isTraining})",
                                )
                            }

                            // 3. Validate employee authorization
                            val allowCrossEmployeeBilling = configManager.isAllowCrossEmployeeBilling()
                            val authorized =
                                session.employeeId == employee ||
                                    employee.isManager() ||
                                    (allowCrossEmployeeBilling && !employee.role.isTerminal())
                            if (!authorized) {
                                throw billAuthorizationDenied(
                                    "Creating Bill for table session ${request.tableSessionId} " +
                                        "(table ${session.table.tableNumber}) is not allowed for employee $employeeId",
                                )
                            }

                            val bill =
                                createBillInternal(
                                    employee = employee,
                                    session = session,
                                    orderItems = request.orderItems,
                                    billAmount = toBigDecimal(request.billAmount),
                                    tips = toBigDecimal(request.tips),
                                    paymentsRequest = request.payments,
                                    voucherRedeems = request.voucherRedeem,
                                    promotionRequests = request.promotions,
                                    deviceId = deviceId,
                                )

                            // Collect TSE signing data. Shared with the PENDING-sweeper (TSER-9) so a
                            // swept re-enqueue signs byte-identical data — see ProcessDataBuilder.buildForBill.
                            val processData = ProcessDataBuilder.buildForBill(bill)
                            bill.terminal = terminal // persist so TsePendingSweeper can rebuild the signing client

                            val clientId = terminal.clientId

                            val billResponse = bill.toResponseDto(invoices)
                            logger.info("BillServiceImpl.createBill: bill created id=${bill.id.value}, number=${bill.billNumber}")

                            BillCreationResult(
                                billResponse = billResponse,
                                signing =
                                    BillSigning(
                                        billId = bill.id.value,
                                        billNumber = bill.billNumber,
                                        processData = processData,
                                        clientId = clientId,
                                        logTag = "BillServiceImpl.createBill",
                                    ),
                            )
                        }
                    }
                },
            )

        val (billResponse, signing) = phase1

        // Phase 2: TSE signing via shared resolver
        val signed = signBillOrQueue(signing)

        // The response was built before signing. The till renders its receipt dialog — QR code and
        // TSE transaction included — straight from this reply, so a bill signed online must be
        // re-read, or the receipt shows no TSE data for a sale that has it (E2E round 4, R4-2).
        if (signed.signed) return dbQuery { Bill.findById(signing.billId)!!.toResponseDto(invoices) }
        // A queued bill must already show why on the receipt the till renders from it.
        if (!signed.enqueued) return billResponse
        val note = dbQuery { saleSigning.signingNoteFor(TseEntityType.BILL, signing.billId.toString()) }
        return billResponse.copy(tseSigningNote = note)
    }

    /**
     * Slice 4c §9a — a bill that an outgoing invoice describes cannot be reversed or re-tendered
     * behind that invoice's back.
     *
     * An `ISSUED` invoice is refused: cancelling it is a §14 act that consumes a second number for
     * the 381 Gutschrift and records a reason, and performing that implicitly inside a till storno
     * would hide a numbered legal document behind an unrelated button. A `DRAFT` consumed nothing
     * and describes a bill that no longer stands, so [deleteDraftFor] removes it with the storno.
     *
     * This closes a hole that predates 4c: a case-A bill could already be storno'd while its issued
     * invoice stood, leaving a §14 document for a reversed sale.
     */
    private fun requireNoIssuedInvoice(
        bill: Bill,
        action: String,
    ) {
        val invoice = invoices.liveInvoiceFor(bill.id.value) ?: return
        if (!invoice.issued) return
        throw PosException(
            PosErrorCode.SALES_INVOICE_IMMUTABLE,
            HttpStatusCode.Conflict,
            "Cannot $action bill ${bill.billNumber}: invoice ${invoice.invoiceNumber} was issued for it. " +
                "Cancel that invoice first — an issued invoice is only left through a 381 Gutschrift.",
        )
    }

    /**
     * A Bewirtungsbeleg documents a meal the guest may deduct, so it prints only for a standing sale
     * ([isStandingSale]). A Storno and its cancelled original no longer stand, and a receivable
     * clearing is a payment receipt. The plain receipt reprint stays open for every bill. The till
     * hides the button on the same predicate (CLAUDE.md rule 8).
     */
    private fun requireBewirtungsbelegable(bill: BillDto.BillResponseDto) {
        if (bill.isStandingSale()) return
        val cancelled = if (bill.relations.isCancelled) ", cancelled" else ""
        throw PosException(
            PosErrorCode.BILL_NOT_A_STANDING_SALE,
            HttpStatusCode.Conflict,
            "Bill ${bill.billNumber} is not a standing sale (${bill.relations.kind}$cancelled); " +
                "a Bewirtungsbeleg prints only for the original of a sale that was not cancelled.",
        )
    }

    /**
     * A Storno that re-opens [session] makes it the table's `currentSession` again. If the table was
     * freed after the sale and NEW guests now sit there (a different, open session is current),
     * re-opening would overwrite their session and hide it from the floor plan — so the storno is
     * refused before anything is written. The till then offers a Storno without re-opening
     * (`restoreSession = false`), which leaves the table alone. Same predicate as the till's
     * conflict dialog (CLAUDE.md rule 8).
     *
     * Only a completed session is re-opened, and a virtual table (counter voucher sale) is never
     * restored, so both pass. The table row is locked FOR UPDATE so a party seated concurrently
     * (`RTableServiceImpl` opens a session under the same lock) cannot slip past the check. No
     * session row lock is held here, so this respects table-before-session lock ordering.
     */
    private fun requireTableFreeForRestore(session: TableSession) {
        if (!session.completed) return
        val location = session.table.locationTypeRef
        if (!location.isTakeaway && location.isVirtual) return
        val table = tableRepository.getTableByIdForUpdate(session.table.id.value) ?: return
        val current = table.currentSession ?: return
        if (current.id.value == session.id.value || current.completed) return
        throw PosException(
            PosErrorCode.TABLE_HAS_ACTIVE_SESSION,
            HttpStatusCode.Conflict,
            "Cannot re-open session ${session.id.value}: table ${table.id.value} is occupied by session " +
                "${current.id.value}. Storno without re-opening the table (restoreSession=false).",
        )
    }

    /** Removes a `DRAFT` invoice that describes a bill being reversed. It consumed no number. */
    private fun deleteDraftFor(bill: Bill) {
        invoices.deleteDraftInvoiceFor(bill.id.value)
    }

    override suspend fun deleteBill(
        employeeId: String,
        deviceId: String,
        billId: String,
        deletingReason: String?,
        restoreSession: Boolean,
    ) {
        data class StornoCommit(
            val signing: BillSigning,
            /**
             * Card for a table whose session this storno handed items back to (re-opened or still
             * live); sent after the commit.
             */
            val rebillingTableUpdate: NotificationDto.TableStatusUpdateDto? = null,
        )

        // Phase 1: DB transaction (retryable) — create reversal, collect TSE data
        val commit =
            ExceptionConverter.catchingThrows(
                context = "deleteBill($billId)",
                employeeId = employeeId,
                operation = "DELETE_BILL",
                entityType = "BILL",
                deviceId = deviceId,
                // The bill that was REVERSED, not the reversal the block returns: `billId` is
                // this call's own subject (`repository.findById(billId.toLong())` above), so a
                // DELETE_BILL row carries the same entity_id its CREATE_BILL row did and the two
                // join into one bill's lifecycle. The reversal is named in entity_name and its id
                // is in afterState.
                auditEntity = { AuditEntityRef(id = billId, name = "Storno: ${it.signing.billNumber}") },
                block = {
                    BillWriteConflict.retrying(operationName = "deleteBill(employeeId=$employeeId, billNumber=$billId, deletingReason=$deletingReason)") {
                        var billNumberToRelease: String? = null
                        try {
                            dbQuery(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
                                // Hoisted, along with the terminal resolution + signing guard right
                                // below, so a fiscal-safety rejection fires BEFORE the reverse bill is
                                // ever created — a throw here rolls back nothing because nothing has
                                // been written yet.
                                val mode = configManager.getTseMode()

                                // Resolve the terminal and enforce the fiscal-signing guard FAIL-FAST,
                                // before the reverse-bill entity exists. Previously this ran late
                                // (right before signing, after the reverse bill was created, payments
                                // refunded, and VAT details reversed), so a rejection had to roll back
                                // an already-created refund. See TransactionSigningGuard for the rule.
                                // requireSigningTerminal rejects non-terminal (admin-web) clients from fiscal ops
                                // and standardizes the not-found code to CARD_TERMINAL_NOT_FOUND (TerminalSigningGuard).
                                val terminal = requireSigningTerminal(terminalRepository, deviceId, mode)

                                // 1. Find required entities (employee lookup ensures the actor exists)
                                val actor =
                                    Employee.findById(UUID.fromString(employeeId))
                                        ?: throw employeeNotFound(employeeId)

                                // No Storno by the station account (OS-b) — not even under a step-up
                                // lending BILL_VOID, which does not change who acts. Before the
                                // reverse bill exists.
                                requireMayBillAs(actor.role)

                                val bill =
                                    repository.findById(billId.toLong())
                                        ?: throw billNotFound(billId)

                                val session = requireStornoable(bill, "delete")
                                // 4c §9a: a draft invoice goes with the storno.
                                deleteDraftFor(bill)

                                // Before the bill number is reserved: a refusal writes nothing.
                                if (restoreSession) requireTableFreeForRestore(session)

                                val billNumber = generateAndReserveBillNumber()
                                billNumberToRelease = billNumber

                                val storno =
                                    stornoWithinTx(
                                        bill = bill,
                                        session = session,
                                        terminal = terminal,
                                        context =
                                            StornoContext(
                                                billNumber = billNumber,
                                                freeText = deletingReason,
                                                category = null,
                                                mode = if (restoreSession) StornoMode.REOPEN_FOR_REBILLING else StornoMode.PLAIN,
                                                logTag = "BillServiceImpl.deleteBill",
                                            ),
                                    )

                                StornoCommit(
                                    signing =
                                        BillSigning(
                                            billId = storno.reverseBill.id.value,
                                            billNumber = storno.reverseBill.billNumber,
                                            processData = storno.processData,
                                            clientId = terminal.clientId,
                                            logTag = "BillServiceImpl.deleteBill",
                                            isReversal = true,
                                        ),
                                    // Built after the items were re-added, so the card's open amount
                                    // is the restored one. The floor plans kept showing the table
                                    // "Frei" until a restart (E2E-2026-09-18 #11), and a reopen into a
                                    // session that was still open (partial bill) kept the old amount
                                    // on every card (emu18 NOTE-g).
                                    rebillingTableUpdate =
                                        storno.rebillingSession?.let { rebilling ->
                                            createTableStatusUpdateDto(
                                                table = rebilling.table,
                                                status = rebilling.table.tableStatus,
                                                sessionId = rebilling.id.value,
                                                sessionStartTime = rebilling.startTime.toString(),
                                                employee = rebilling.employeeId,
                                                isTraining = rebilling.isTraining,
                                            )
                                        },
                                )
                            }
                        } catch (e: Exception) {
                            billNumberToRelease?.let { BillNumberTracker.release(it) }
                            billNumberToRelease = null
                            throw e
                        }
                    }
                },
            )

        // After the commit only: a rolled-back storno must announce nothing.
        commit.rebillingTableUpdate?.let { NotificationService.notifyTableStatusUpdate(it) }

        // Phase 2: TSE signing via shared resolver
        signBillOrQueue(commit.signing)
    }

    /** One rebook request, as the route hands it over. */
    private data class RebookCall(
        val employeeId: String,
        val deviceId: String,
        val billId: String,
        val request: BillDto.RebookRequestDto,
        /** What the route resolved the caller to hold; `BILL_VOID` lets the rebook lower the tip. */
        val actorCapabilities: Set<Capability>,
    )

    /** What a rebook's Phase 1 committed, for its TSE phase and its audit row. */
    private data class RebookWritten(
        val stornoNumber: String,
        val newBillNumber: String,
        val newBillId: Long,
        val stornoSigning: BillSigning,
        val newBillSigning: BillSigning,
    ) {
        /** The REBOOK_BILL row's `after_state`: the three Belege by id and number — not the TSE payloads. */
        fun toAuditJson(rebookedBillId: String): String =
            buildJsonObject {
                put("rebookedBillId", rebookedBillId.toLongOrNull())
                put("stornoBillId", stornoSigning.billId)
                put("stornoBillNumber", stornoNumber)
                put("newBillId", newBillId)
                put("newBillNumber", newBillNumber)
            }.toString()
    }

    override suspend fun rebookBill(
        employeeId: String,
        deviceId: String,
        billId: String,
        request: BillDto.RebookRequestDto,
        actorCapabilities: Set<Capability>,
    ): BillDto.BillResponseDto {
        val call = RebookCall(employeeId, deviceId, billId, request, actorCapabilities)
        // Phase 1: one DB transaction (retryable) — Storno + Neubuchung, collect both TSE payloads.
        val written =
            ExceptionConverter.catchingThrows(
                context = "rebookBill($billId)",
                employeeId = employeeId,
                operation = "REBOOK_BILL",
                entityType = "BILL",
                deviceId = deviceId,
                // The bill that was rebooked — the call's own subject, as for DELETE_BILL — so the
                // row joins that bill's lifecycle; the Storno and the Neubuchung are named.
                auditEntity = { AuditEntityRef(id = billId, name = "Zahlartwechsel: ${it.stornoNumber} / ${it.newBillNumber}") },
                auditState = { it.toAuditJson(rebookedBillId = billId) },
                block = { BillWriteConflict.retrying(operationName = "rebookBill(employeeId=$employeeId, billId=$billId)") { rebookCommitted(call) } },
            )

        // Phase 2: Storno first, then the Neubuchung — each signed or queued like any bill.
        signRebook(written)
        return dbQuery { repository.findById(written.newBillId)!!.toDetailDto() }
    }

    /**
     * Signs both Belege of a committed rebook, Storno first. Under FAIL the Storno's signing error is
     * held until the Neubuchung was attempted too: both bills are already committed, and a Neubuchung
     * left without any signing attempt would stay unsigned without even a queue entry. The Storno's
     * error is the one thrown; a Neubuchung error rides along as suppressed, so neither is lost.
     */
    private suspend fun signRebook(written: RebookWritten) {
        val stornoFailure = signingFailureOf(written.stornoSigning)
        val newBillFailure = signingFailureOf(written.newBillSigning)
        val first = stornoFailure ?: newBillFailure ?: return
        if (stornoFailure != null && newBillFailure != null) first.addSuppressed(newBillFailure)
        throw first
    }

    /** [signBillOrQueue], with its FAIL-mode error returned instead of thrown. */
    private suspend fun signingFailureOf(signing: BillSigning): PosException? =
        try {
            signBillOrQueue(signing)
            null
        } catch (e: PosException) {
            e
        }

    /**
     * Runs [rebookWithinTx] in one REPEATABLE_READ transaction. The Storno number is released
     * after the transaction ends either way — after a commit the row holds it, after a rollback
     * nothing does. The Neubuchung's number is managed by [createBillInternal] itself.
     */
    private suspend fun rebookCommitted(call: RebookCall): RebookWritten {
        val reserved = mutableListOf<String>()
        try {
            return dbQuery(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) { rebookWithinTx(call, reserved) }
        } finally {
            reserved.forEach { BillNumberTracker.release(it) }
        }
    }

    /**
     * Zahlartwechsel as Storno + Neubuchung, inside the caller's transaction. Every refusal throws
     * before anything is written, or rolls the whole transaction back — Storno, restored vouchers
     * and the deleted draft invoice included.
     */
    private suspend fun rebookWithinTx(
        call: RebookCall,
        reservedNumbers: MutableList<String>,
    ): RebookWritten {
        val mode = configManager.getTseMode()
        val terminal = requireSigningTerminal(terminalRepository, call.deviceId, mode)
        requireRebookActor(call.employeeId)

        val bill = repository.findById(call.billId.toLong()) ?: throw billNotFound(call.billId)
        requireRebookable(bill)
        val session = requireStornoable(bill, action = "rebook")
        val tips = requireRebookTender(bill, call.request, mayLowerTip = Capability.BILL_VOID in call.actorCapabilities)

        // 4c §9a: a draft invoice goes with the storno; the Neubuchung can be invoiced afresh.
        deleteDraftFor(bill)
        val stornoNumber = generateAndReserveBillNumber().also { reservedNumbers += it }
        val storno =
            stornoWithinTx(
                bill = bill,
                session = session,
                terminal = terminal,
                context =
                    StornoContext(
                        billNumber = stornoNumber,
                        freeText = "Zahlartwechsel",
                        category = StornoReason.ZAHLARTWECHSEL,
                        mode = StornoMode.REBOOK,
                        logTag = "BillServiceImpl.rebookBill",
                    ),
            )

        val newBill = bookRebookedSale(RebookSale(bill, session, storno, tips, call))
        newBill.rebooksBill = bill
        newBill.terminal = terminal // persist so TsePendingSweeper can rebuild the signing client

        logger.info("BillServiceImpl.rebookBill: ${bill.billNumber} rebooked as ${newBill.billNumber} via Storno ${storno.reverseBill.billNumber}")
        return RebookWritten(
            stornoNumber = storno.reverseBill.billNumber,
            newBillNumber = newBill.billNumber,
            newBillId = newBill.id.value,
            stornoSigning =
                BillSigning(
                    billId = storno.reverseBill.id.value,
                    billNumber = storno.reverseBill.billNumber,
                    processData = storno.processData,
                    clientId = terminal.clientId,
                    logTag = "BillServiceImpl.rebookBill",
                    isReversal = true,
                ),
            // Built after the training flag and every row of the new bill are in place.
            newBillSigning =
                BillSigning(
                    billId = newBill.id.value,
                    billNumber = newBill.billNumber,
                    processData = ProcessDataBuilder.buildForBill(newBill),
                    clientId = terminal.clientId,
                    logTag = "BillServiceImpl.rebookBill",
                ),
        )
    }

    /** The acting person exists and may bill — a station account may not (OS-b). */
    private fun requireRebookActor(employeeId: String) {
        val actor = Employee.findById(UUID.fromString(employeeId)) ?: throw employeeNotFound(employeeId)
        requireMayBillAs(actor.role)
    }

    /**
     * The bill is a standing sale the rebook supports ([RebookEligibility], the rule the till's
     * `isRebookable` reads too). A cancelled original — stornoed or already rebooked — is refused as
     * already cancelled, exactly as `deleteBill` refuses it; a Storno Beleg is not a sale at all.
     */
    private fun requireRebookable(bill: Bill) {
        val refusal = RebookEligibility.refusal(bill.rebookFacts()) ?: return
        throw when (refusal) {
            RebookEligibility.Refusal.NOT_A_STANDING_SALE ->
                if (bill.deletedBill == null) {
                    billAlreadyDeleted(bill.id.value.toString())
                } else {
                    PosException(
                        PosErrorCode.BILL_NOT_A_STANDING_SALE,
                        HttpStatusCode.Conflict,
                        "Bill ${bill.billNumber} is a Storno Beleg; only a standing sale can be rebooked",
                    )
                }
            RebookEligibility.Refusal.UNSUPPORTED ->
                PosException(
                    PosErrorCode.BILL_REBOOK_UNSUPPORTED,
                    HttpStatusCode.Conflict,
                    "Bill ${bill.billNumber} sells a gift voucher or clears an invoice; storno it and bill again by hand",
                )
        }
    }

    /**
     * The requested tender is well formed, covers exactly the bill total (after promotions) plus the
     * new tip, names existing, active payment types, and changes something ([RebookTender], the rule the
     * till's confirm button reads too). Lowering the tip additionally needs `BILL_VOID` ([mayLowerTip]).
     * Must run before the Storno refunds the original's payments. Returns the Neubuchung's tip as
     * stored money.
     */
    private fun requireRebookTender(
        bill: Bill,
        request: BillDto.RebookRequestDto,
        mayLowerTip: Boolean,
    ): BigDecimal {
        val tips = request.tips.toBigDecimalOrNull() ?: throw validationFailed("tips is not a number: ${request.tips}")
        val payments =
            request.payments.map { payment ->
                val amount =
                    payment.amount.toBigDecimalOrNull()
                        ?: throw validationFailed("payment amount is not a number: ${payment.amount}")
                payment.paymentTypeId to amount
            }
        val requested =
            RebookTender(
                payments = payments.map { (typeId, amount) -> typeId to amount.toDecimal() },
                tip = tips.toDecimal(),
                vouchers = request.voucherRedeem.map { it.voucherNumber },
            )
        if (!requested.isWellFormed()) {
            throw validationFailed("Every payment must be positive with at most two decimals, the tip not negative")
        }
        val billTotal = bill.billAmount - bill.promotionAmount
        if (!requested.balances(billTotal.toDecimal())) {
            throw paymentTotalMismatch((billTotal + tips).toPlainString(), payments.sumOf { it.second }.toPlainString())
        }
        val current = bill.currentTender()
        if (!requested.changesFrom(current)) {
            throw PosException(
                PosErrorCode.BILL_REBOOK_NO_CHANGE,
                HttpStatusCode.Conflict,
                "The requested tender, vouchers and tip equal those of bill ${bill.billNumber}; nothing to rebook",
            )
        }
        if (requested.lowersTipFrom(current) && !mayLowerTip) {
            throw PosException(
                PosErrorCode.BILL_REBOOK_TIP_DECREASE_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "Lowering the tip of bill ${bill.billNumber} from ${bill.tips.toPlainString()} to ${tips.toPlainString()} requires BILL_VOID",
            )
        }
        payments.forEach { (typeId, _) -> requireActivePaymentType(typeId) }
        return tips
    }

    /** The bill's standing tender: its completed payments, their redeemed vouchers and its tip. */
    private fun Bill.currentTender(): RebookTender {
        val completed = payments.filter { it.paymentStatus == PaymentStatus.COMPLETED }
        return RebookTender(
            payments = completed.map { it.paymentType.id.value to it.amount.toDecimal() },
            tip = tips.toDecimal(),
            vouchers = completed.mapNotNull { it.voucherRedeem?.voucherNumber },
        )
    }

    private fun BigDecimal.toDecimal(): Decimal = Decimal.parse(toPlainString())

    /** Everything the Neubuchung is booked from. */
    private data class RebookSale(
        val original: Bill,
        /** The original's session, completed by the original; the duplicated items are back on it. */
        val session: TableSession,
        val storno: StornoResult,
        /** The Neubuchung's whole tip, validated by [requireRebookTender]. */
        val tips: BigDecimal,
        val call: RebookCall,
    )

    /**
     * Books the Storno's duplicated items again as a new bill through the ordinary bill-creation
     * core: under the ORIGINAL seller, with the original's tip recipient and training flag, the requested tender and tip, and the original's promotions re-applied as of
     * the original's close. The session was completed by the original and stays completed; the table
     * is not touched (new guests may sit there).
     */
    private suspend fun bookRebookedSale(sale: RebookSale): Bill {
        val original = sale.original
        val newBill =
            try {
                createBillInternal(
                    employee = original.employeeId,
                    session = sale.session,
                    orderItems =
                        sale.storno.duplicatedFrom.values
                            .map { it.id.value },
                    billAmount = original.billAmount,
                    tips = sale.tips,
                    paymentsRequest = sale.call.request.payments,
                    voucherRedeems = sale.call.request.voucherRedeem,
                    promotionRequests = original.promotionRequestsFor(sale.storno.duplicatedFrom),
                    deviceId = sale.call.deviceId,
                    skipSessionCompletion = true,
                    rebookBasis =
                        RebookBasis(
                            promotionsAsOf = original.endTime,
                            isTraining = original.isTraining,
                            tipRecipient = original.tipRecipient,
                        ),
                )
            } catch (e: PosException) {
                // The tender was checked to cover billAmount − the ORIGINAL promotion amount + tip,
                // with billAmount and tip unchanged, so a total mismatch here can only mean the
                // re-applied promotions deduct a different amount today.
                if (e.code == PosErrorCode.PAYMENT_TOTAL_MISMATCH) throw rebookPromotionChanged(original)
                throw e
            }
        // Unreachable backstop: createBillInternal's total check already caught a different promotion
        // amount above. Kept so a future change to that check cannot let a drifted promotion through.
        if (newBill.promotionAmount.compareTo(original.promotionAmount) != 0) throw rebookPromotionChanged(original)
        return newBill
    }

    /**
     * The original's promotions as apply-requests for the Neubuchung, in the order they were applied;
     * an item-specific one is re-pointed at the duplicate of the item it discounted.
     */
    private fun Bill.promotionRequestsFor(duplicatedFrom: Map<Long, OrderItem>): List<PromotionDto.PromotionApplyRequestDto> =
        BillPromotion
            .find { BillPromotionTable.billId eq this@promotionRequestsFor.id }
            .orderBy(BillPromotionTable.id to SortOrder.ASC)
            .map { applied ->
                PromotionDto.PromotionApplyRequestDto(
                    promotionId = applied.promotion.id.value,
                    orderItemId = applied.orderItem?.let { item -> duplicatedFrom.getValue(item.id.value).id.value },
                )
            }

    private fun rebookPromotionChanged(bill: Bill) =
        PosException(
            PosErrorCode.BILL_REBOOK_PROMOTION_CHANGED,
            HttpStatusCode.Conflict,
            "A promotion on bill ${bill.billNumber} changed since the sale; storno and bill again",
        )

    /** The checks a Storno needs on the bill itself; runs inside the caller's transaction. */
    private fun requireStornoable(
        bill: Bill,
        action: String,
    ): TableSession {
        if (bill.deletingReason != null || bill.deletedBill != null) {
            throw billAlreadyDeleted(bill.id.value.toString())
        }

        // Round-3 KABS-1: once the business day is closed, the bill's data is
        // frozen into an exported Kassenabschluss. A storno would stamp
        // reversedByBill on the ORIGINAL's redemptions (silently rewriting the
        // closed closing's businesscases) and its cash refund would never reach
        // the current closing's balance.
        if (bill.zBon != null) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.Conflict,
                "Cannot $action bill: business day of bill ${bill.id.value} is already closed (Z-Bon exists). " +
                    "Book the correction as a new negative sale or Kassenbuch entry.",
            )
        }

        // 4c §9a: an issued invoice blocks the storno.
        requireNoIssuedInvoice(bill, action)

        return TableSession.findById(bill.tableSession.id.value)
            ?: throw PosException(
                PosErrorCode.TABLE_HAS_NO_SESSION,
                HttpStatusCode.NotFound,
                "TableSession not found: ${bill.tableSession.id.value}",
            )
    }

    /** What a Storno wrote, for the caller's TSE phase and notifications. */
    private data class StornoResult(
        val reverseBill: Bill,
        val processData: String,
        /** Original billed item id → its re-billable duplicate (item-specific promotions follow it). */
        val duplicatedFrom: Map<Long, OrderItem>,
        /**
         * The real-table session a [StornoMode.REOPEN_FOR_REBILLING] Storno handed the items back to —
         * re-opened if it was completed, or still live after a partial bill. Its open amount changed,
         * so the caller announces its card. Null for [StornoMode.PLAIN], [StornoMode.REBOOK] (the
         * Neubuchung pays the copies at once, the open amount is unchanged) and virtual tables.
         */
        val rebillingSession: TableSession?,
    )

    /** Why and how a Storno is booked. */
    private data class StornoContext(
        /**
         * The Storno Beleg's number, already reserved by the caller — who releases it if the
         * transaction fails, including at commit, which this function cannot see.
         */
        val billNumber: String,
        val freeText: String?,
        val category: StornoReason?,
        val mode: StornoMode,
        /** Prefix for log lines, e.g. `BillServiceImpl.deleteBill`. */
        val logTag: String,
    )

    /** What a Storno does with the bill's table session and its items, besides reversing the sale. */
    private enum class StornoMode {
        /**
         * Ordinary Storno, switch on: reopen the session/table and hand back for re-billing the items
         * that came from an order and items that were themselves re-billed duplicates
         * (`duplicatedFromBill`, no order) — only legacy order-less lines (the removed quick sale)
         * are skipped. Guarded by [requireTableFreeForRestore] before the bill number is reserved.
         */
        REOPEN_FOR_REBILLING,

        /**
         * Ordinary Storno, switch off: session and table untouched (new guests may sit there), no
         * items duplicated, an unused sold voucher invalidated — it dies with the sale.
         */
        PLAIN,

        /**
         * Zahlartwechsel: table and session untouched, every billed item duplicated for the
         * immediate Neubuchung, no table guard. Never invalidates a voucher (a rebook refuses
         * gift-voucher sales).
         */
        REBOOK,
    }

    /**
     * Performs a full bill Storno (Belegstorno) per DSFinV-K — see [BillService.deleteBill].
     * Books the Storno Beleg for [bill] inside the caller's transaction: reverse bill, refunded payments,
     * restored vouchers, negated VAT rows, and — per [StornoContext.mode] — the session reopened and/or
     * items duplicated back to it for re-billing. Only [StornoMode.REOPEN_FOR_REBILLING] touches the
     * table; [StornoMode.PLAIN] and [StornoMode.REBOOK] leave it and its session exactly as they are (a
     * Zahlartwechsel re-bills at once and must not reopen a table that new guests may occupy).
     */
    private fun stornoWithinTx(
        bill: Bill,
        session: TableSession,
        terminal: Terminal,
        context: StornoContext,
    ): StornoResult {
        // 3. Create reverse bill (negative amounts)
        val reverseBill =
            Bill.new {
                this.employeeId = bill.employeeId
                this.tableSession = session
                this.billNumber = context.billNumber
                this.billAmount = bill.billAmount.negate()
                this.discountAmount = bill.discountAmount.negate()
                this.promotionAmount = bill.promotionAmount.negate()
                this.tips = bill.tips.negate()
                this.tipRecipient = bill.tipRecipient
                this.deletingReason = context.freeText
                this.deletedBill = bill
                this.paidAmount = bill.paidAmount.negate()
                this.startTime = currentTimeStamp()
                this.endTime = currentTimeStamp()
                this.isTraining = bill.isTraining
                this.stornoReason = context.category
            }

        logger.info(
            "${context.logTag}: reverse bill created id=${reverseBill.id.value}, number=${reverseBill.billNumber} for original bill ${bill.billNumber}, training=${reverseBill.isTraining}",
        )

        // 4. Refund the bill's completed payments, restore voucher balances, and
        // stamp their redemptions as reversed for the Z-Bon.
        val refundedPayments = bill.payments.filter { it.paymentStatus == PaymentStatus.COMPLETED }
        refundedPayments.forEach { payment ->
            payment.paymentStatus = PaymentStatus.REFUNDED
            payment.refundTime = currentTimeStamp()
            payment.refundedBillId = reverseBill

            payment.voucherRedeem?.let { redeem ->
                if (!bill.isTraining) {
                    redeem.voucher?.let { voucher ->
                        voucher.remainingValue += redeem.redeemAmount
                        voucher.fullyRedeemedAt = null
                    }
                }
                redeem.reversedByBill = reverseBill
            }
        }

        // 5. Reverse Bill VAT Details
        bill.vatDetails.forEach { originalVatDetail ->
            BillVatDetail.new {
                this.bill = reverseBill
                this.totalAmount = originalVatDetail.totalAmount.negate()
                this.netAmount = originalVatDetail.netAmount.negate()
                this.vatAmount = originalVatDetail.vatAmount.negate()
                this.vat = originalVatDetail.vat
            }
        }

        logger.info(
            "${context.logTag}: reversed ${bill.vatDetails.count()} VAT details for reverse bill ${reverseBill.billNumber}",
        )

        bill.deletingReason = reverseBill.billNumber + " : " + context.freeText

        // 6. Collect TSE signing data
        val stornoBonType = if (bill.isTraining) BonType.AV_TRAINING else BonType.BELEG
        val processData =
            ProcessDataBuilder.build(
                processType = ProcessType.KASSENBELEG_V1,
                bonType = stornoBonType,
                vatDetail = reverseBill.vatDetails.toList(),
                payments = refundedPayments,
                // The original's non-taxable tip, negated. A pre-2026-09-16
                // original whose tip is a 0 % row (copied above already)
                // contributes ZERO. SAME function
                // DsfinvkExportServiceImpl.generateBonkopfUst calls for the
                // storno's key-5 row, so the signed WORM payload and the
                // export read one formula.
                nonTaxableExtra = NonTaxableTipCarrier.outsideVatDetails(bill).negate(),
            )
        reverseBill.terminal = terminal // persist so TsePendingSweeper can rebuild the signing client

        // 7. Handle virtual-table (counter voucher sale) vs table-session restoration.
        // A virtual table has no seating to hand back, so a Storno there only
        // invalidates the voucher; a real table session is re-opened for re-billing
        // (only in [StornoMode.REOPEN_FOR_REBILLING]).
        val duplicated = mutableMapOf<Long, OrderItem>()
        // Set when this storno hands items back to a real table's session for re-billing (below).
        var rebillingSession: TableSession? = null
        val sessionLocation = session.table.locationTypeRef
        val billedItems = bill.items.toList()
        val isVirtualTableBill = !sessionLocation.isTakeaway && sessionLocation.isVirtual

        // Batched lookups for the loops below (replaces one itemExtras query
        // per duplicated item and one voucher query per VOU item):
        // one query for all snapshotted extras of the billed items, one query
        // for all vouchers referencing them. Read-only; write order unchanged.
        val billedItemIds = billedItems.map { it.id.value }
        val extrasByItemId: Map<Long, List<ItemExtras>> =
            if (billedItemIds.isEmpty()) {
                emptyMap()
            } else {
                ItemExtras
                    .find { ItemExtrasTable.orderItemId inList billedItemIds }
                    .groupBy { it.orderItem.id.value }
            }
        val vouchersByItemId: Map<Long, Voucher> =
            if (billedItemIds.isEmpty()) {
                emptyMap()
            } else {
                Voucher
                    .find { VoucherTable.orderItem inList billedItemIds }
                    .groupBy { it.orderItem.id.value }
                    .mapValues { (_, vouchers) -> vouchers.first() }
            }

        // A sold voucher that is not handed back for re-billing dies with the
        // sale — unless it was already (partly) spent.
        fun invalidateUnusedVoucher(voucher: Voucher?) {
            if (voucher != null &&
                voucher.status == VoucherStatus.ACTIVE &&
                voucher.remainingValue.compareTo(voucher.originalValue) == 0
            ) {
                voucher.invalidate(
                    reason = "Storno: ${reverseBill.billNumber}",
                    source = VoucherInvalidationSource.BILL_STORNO,
                )
                logger.info(
                    "${context.logTag}: invalidated voucher ${voucher.voucherNumber} for Storno without re-billing",
                )
            }
        }

        if (isVirtualTableBill) {
            val voucherItem = billedItems.firstOrNull { it.product.productNumber == "VOU" }
            invalidateUnusedVoucher(voucherItem?.let { vouchersByItemId[it.id.value] })
            logger.info("${context.logTag}: virtual-table bill — skipped session/table restoration")
        } else if (context.mode == StornoMode.PLAIN) {
            // The cashier chose a plain Storno: the session stays completed, the
            // table is untouched (it may seat new guests), and nothing is copied
            // for re-billing. The reverse bill above already cancels the sale.
            billedItems
                .filter { it.product.productNumber == "VOU" }
                .forEach { invalidateUnusedVoucher(vouchersByItemId[it.id.value]) }
            logger.info(
                "${context.logTag}: restoreSession=false — session ${session.id.value} left closed, no items duplicated",
            )
        } else {
            if (context.mode == StornoMode.REOPEN_FOR_REBILLING) rebillingSession = session
            if (context.mode == StornoMode.REOPEN_FOR_REBILLING && session.completed) {
                session.completed = false
                session.endTime = null

                val table = RTable.findById(session.table.id.value)
                table?.let {
                    it.tableStatus = TableStatus.OCCUPIED
                    it.currentSession = session
                }

                logger.info("${context.logTag}: session ${session.id.value} restored after bill Storno")
            }

            // 8. Duplicate order items for re-billing (DSFinV-K compliant)
            billedItems
                .filter { context.mode == StornoMode.REBOOK || it.order != null || it.duplicatedFromBill != null }
                .forEach { originalItem ->
                    val extras = extrasByItemId[originalItem.id.value].orEmpty()

                    val newItem =
                        OrderItem.new {
                            this.order = null
                            this.session = originalItem.session
                            this.bill = null
                            this.product = originalItem.product
                            this.unitPrice = originalItem.unitPrice
                            this.finalPrice = originalItem.finalPrice
                            this.itemStatus = ItemStatus.OPEN
                            this.vat = originalItem.vat
                            this.discount = originalItem.discount
                            this.customPrice = originalItem.customPrice
                            this.takeAway = originalItem.takeAway
                            this.transferred = originalItem.transferred
                            this.sideNotes = originalItem.sideNotes
                            this.duplicatedFromBill = bill
                        }

                    // Copy snapshotted option rows onto the duplicated item.
                    extras.forEach { extra ->
                        ItemExtrasTable.insert {
                            it[orderItemId] = newItem.id.value
                            it[productExtrasId] = extra.productExtras?.id?.value
                            it[optionNameSnapshot] = extra.optionNameSnapshot
                            it[priceDeltaSnapshot] = extra.priceDeltaSnapshot
                            it[groupNameSnapshot] = extra.groupNameSnapshot
                            it[quantity] = extra.quantity
                        }
                    }

                    if (originalItem.product.productNumber == "VOU") {
                        val voucher = vouchersByItemId[originalItem.id.value]
                        if (voucher != null) {
                            voucher.orderItem = newItem
                            logger.info(
                                "${context.logTag}: re-linked voucher ${voucher.voucherNumber} to duplicated item ${newItem.id.value}",
                            )
                        }
                    }

                    duplicated[originalItem.id.value] = newItem
                }

            logger.info(
                "${context.logTag}: duplicated ${duplicated.size} items from canceled bill ${bill.billNumber} for re-billing (skipped ${billedItems.size - duplicated.size} order-less items)",
            )
        }

        logger.info(
            "${context.logTag}: completed original=${bill.billNumber}, reverse=${reverseBill.billNumber}",
        )

        return StornoResult(
            reverseBill = reverseBill,
            processData = processData,
            duplicatedFrom = duplicated,
            rebillingSession = rebillingSession,
        )
    }

    /** Phase 2 of every bill write: which bill to sign, with what, and how to name it in logs. */
    private data class BillSigning(
        val billId: Long,
        val billNumber: String,
        val processData: String,
        val clientId: String,
        /** Prefix for log lines, e.g. `BillServiceImpl.createBill`. */
        val logTag: String,
        /** A Storno Beleg: logged as "reversal", and the FAIL-mode error says "reversal bill". */
        val isReversal: Boolean = false,
    )

    /** How [signBillOrQueue] ended when it did not throw. */
    private data class SignOrQueueResult(
        /** Signed online; the bill row now carries its TSE transaction. */
        val signed: Boolean,
        /** Handed to the Nachsignierung queue per the TSE failure policy. */
        val enqueued: Boolean,
    )

    /**
     * Phase 2 of every bill write: sign [BillSigning.processData] for the bill, or queue it per the TSE
     * failure policy. Runs after the Phase-1 commit. The fiscal/registration guard is enforced
     * FAIL-FAST at the top of Phase 1 (pre-commit, before the bill was even created) — see
     * TransactionSigningGuard; a check here could no longer roll back a committed bill.
     */
    private suspend fun signBillOrQueue(signing: BillSigning): SignOrQueueResult {
        val logNoun = if (signing.isReversal) "reversal" else "bill"
        val signingRequest =
            SaleSigningRequest(
                kind = SaleSigningKind.RECEIPT,
                processData = signing.processData,
                referenceId = signing.billId.toString(),
                clientId = signing.clientId,
                // Every bill write — a reversal too — is a BILL: the CANCELLATION consumer parses
                // the reference as OrderItem ids and would never link a storno Beleg — leaving it
                // permanently „TSE-Signierung ausstehend" after Nachsignieren (audit H9).
                entityType = TseEntityType.BILL,
            )
        val decision = saleSigning.attempt(signingRequest)
        val signedTx = decision.transaction

        dbQuery {
            val bill = Bill.findById(signing.billId)!!
            if (signedTx != null) bill.tseTransaction = signedTx
            bill.tseSigningStatus = decision.status
        }

        if (decision.enqueue) {
            try {
                saleSigning.enqueue(signingRequest, decision)
            } catch (eq: PosException) {
                logger.error(
                    "${signing.logTag}: failed to queue $logNoun ${signing.billNumber}: code=${eq.code} msg=${eq.message}",
                    eq,
                )
                dbQuery { Bill.findById(signing.billId)!!.tseSigningStatus = TseSigningStatus.PENDING }
            }
        }

        decision.rethrow?.let {
            logger.error("${signing.logTag}: TSE signing failed for $logNoun ${signing.billNumber}, FAIL mode", it)
            val subject = if (signing.isReversal) "reversal bill" else "bill"
            throw PosException(
                PosErrorCode.TSE_SIGNING_FAILED,
                HttpStatusCode.ServiceUnavailable,
                "TSE signing failed for $subject ${signing.billNumber}",
            )
        }

        return SignOrQueueResult(signed = signedTx != null, enqueued = decision.enqueue)
    }

    override suspend fun getBillSummaries(
        offset: Int,
        limit: Int,
        includeDeleted: Boolean,
    ): List<BillDto.BillSummaryDto> =
        dbQuery {
            repository.findWithPagination(offset, limit, includeDeleted).toSummaryDtos(invoices)
        }

    override suspend fun searchBills(
        employeeId: String?,
        tableSessionId: Long?,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?,
        minAmount: BigDecimal?,
        maxAmount: BigDecimal?,
        tipRecipient: String?,
        includeDeleted: Boolean,
        offset: Int,
        limit: Int,
        includeTraining: Boolean,
    ): List<BillDto.BillResponseDto> {
        // Same refusal the in-memory drop/take used to raise (IllegalArgumentException -> 400),
        // now raised before a negative value reaches the SQL OFFSET/LIMIT.
        require(offset >= 0) { "Requested element count $offset is less than zero." }
        require(limit >= 0) { "Requested element count $limit is less than zero." }
        return dbQuery {
            val parsedTipRecipient =
                tipRecipient?.let {
                    try {
                        TipRecipient.valueOf(it)
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid tipRecipient: expected EMPLOYEE or OWNER, got $it",
                        )
                    }
                }

            val bills =
                repository.searchBills(
                    employeeId = employeeId,
                    tableSessionId = tableSessionId,
                    startDate = startDate,
                    endDate = endDate,
                    minAmount = minAmount,
                    maxAmount = maxAmount,
                    tipRecipient = parsedTipRecipient,
                    includeDeleted = includeDeleted,
                    offset = offset.toLong(),
                    limit = limit,
                    includeTraining = includeTraining,
                )
            bills.toResponseDtos(invoices)
        }
    }

    override suspend fun getBillStatistics(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        includeDeleted: Boolean,
    ): BillDto.BillStatisticsDto =
        dbQuery {
            val allBills = repository.findByDateRange(startDate, endDate)
            val bills = if (includeDeleted) allBills else allBills.filter { it.deletingReason == null }

            val totalRevenue = repository.getTotalRevenue(startDate, endDate, includeDeleted)
            val totalTips = repository.getTotalTips(startDate, endDate, includeDeleted)
            val totalCount = repository.countByDateRange(startDate, endDate, includeDeleted)
            val deletedCount = bills.count { it.deletedBill != null }.toLong()
            val voucherCount = bills.flatMap { bill -> bill.payments.mapNotNull { it.voucherRedeem } }.count().toLong()

            val averageAmount =
                if (totalCount > 0) {
                    totalRevenue.divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP)
                } else {
                    BigDecimal.ZERO
                }

            val billsByEmployee =
                bills
                    .groupBy {
                        it.employeeId.id.value
                            .toString()
                    }.mapValues { it.value.size.toLong() }

            val billsByTipRecipient =
                bills
                    .groupBy { it.tipRecipient.name }
                    .mapValues { it.value.size.toLong() }

            BillDto.BillStatisticsDto(
                totalBills = totalCount,
                totalRevenue = formatDecimal(totalRevenue),
                totalTips = formatDecimal(totalTips),
                averageBillAmount = formatDecimal(averageAmount),
                deletedBills = deletedCount,
                billsWithVouchers = voucherCount,
                billsByEmployee = billsByEmployee,
                billsByTipRecipient = billsByTipRecipient,
            )
        }

    private fun redeemVoucher(
        payment: Payment,
        dto: VoucherDto.RedeemVoucherRequestDto,
    ) {
        val voucher =
            if (dto.voucherSource == VoucherSourceType.INTERNAL) {
                // Lock the voucher row to prevent concurrent redemptions from reading the same balance
                Voucher
                    .find { VoucherTable.voucherNumber eq dto.voucherNumber }
                    .forUpdate()
                    .firstOrNull()
                    ?: throw PosException(
                        PosErrorCode.VOUCHER_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Voucher not found: ${dto.voucherNumber}",
                    )
            } else {
                null
            }

        val redeemAmount = toBigDecimal(dto.redeemedAmount)

        // Round-3 INV-F4: the voucher redemption is ONE event with two DB representations —
        // the tender side (Payment.amount, netted into UMS_BRUTTO / transactions_vat) and the
        // Bonpos side (VoucherRedeem.redeemAmount, emitted into lines.csv / lines_vat /
        // businesscases). They are linked only by convention, so a request that pairs a
        // voucher payment of e.g. 20.00 with redeemedAmount=15.00 passes every bill guard
        // (paid-total uses Payment.amount only) yet splits the two export files. Reject the
        // divergence here — this is the single choke point billing routes through.
        if (redeemAmount.compareTo(payment.amount) != 0) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.UnprocessableEntity,
                "Voucher redeemedAmount ($redeemAmount) must equal the voucher payment amount " +
                    "(${payment.amount}) — they are two representations of ONE redemption (round-3 INV-F4)",
            )
        }

        // Validate sufficient balance before deduction (under row lock)
        if (voucher != null && !payment.bill.isTraining) {
            if (voucher.remainingValue < redeemAmount) {
                throw PosException(
                    PosErrorCode.PAYMENT_AMOUNT_INVALID,
                    HttpStatusCode.BadRequest,
                    "Insufficient voucher balance: remaining=${voucher.remainingValue}, requested=$redeemAmount",
                )
            }
        }

        val redeem =
            VoucherRedeem.new {
                this.payment = payment
                this.voucher = voucher
                this.redeemAmount = redeemAmount
                this.redeemedAt = currentTimeStamp()
                this.voucherType = dto.voucherSource
                this.voucherNumber = dto.voucherNumber
            }
        // Skip voucher balance modification for training bills — training should not
        // affect real voucher balances
        if (voucher != null && !payment.bill.isTraining) {
            val remainingAmount = voucher.remainingValue - redeem.redeemAmount
            if (remainingAmount.compareTo(BigDecimal.ZERO) == 0 && voucher.fullyRedeemedAt == null) {
                voucher.remainingValue = BigDecimal.ZERO
                voucher.fullyRedeemedAt = currentTimeStamp()
            } else {
                voucher.remainingValue = remainingAmount
            }
        }
    }

    /**
     * Resolves the 0% ("Nicht steuerbar", UST_SCHLUESSEL 5) [Vat] entity. The standard German setup
     * seeds this row (class "ZERO"/"C", rate 0.0). Must be called inside the active bill-creation
     * transaction.
     *
     * Its ONLY remaining caller is an OWNER tip (TrinkgeldAG) on a bill with no items at all: there
     * is no item rate for the tip to follow, so it needs a rate of its own. An EMPLOYEE tip
     * (TrinkgeldAN) no longer produces a [BillVatDetail] row — it is non-taxable and is not
     * turnover, and is signed into the 5th brutto slot via ProcessDataBuilder's `nonTaxableExtra`
     * instead (E2E-2026-09-15 N1).
     *
     * @throws org.example.project.core.error.PosException if no 0% VAT rate is configured — such a
     *   tip cannot be signed into an unbalanced Beleg, so we fail loudly rather than drop it.
     */
    private fun resolveZeroRateVat(): Vat =
        Vat.all().firstOrNull { it.vatRate.compareTo(BigDecimal.ZERO) == 0 }
            ?: throw validationFailed(
                "No 0% (Nicht steuerbar) VAT rate configured; cannot represent an owner tip on a bill with no items",
            )

    private fun createBillVat(
        bill: Bill,
        totalAmount: BigDecimal,
        vat: Vat,
    ) {
        val vatRate = vat.vatRate.add(BigDecimal.ONE)
        BillVatDetail.new {
            this.bill = bill
            this.totalAmount = totalAmount
            this.netAmount = totalAmount.divide(vatRate, 2, RoundingMode.HALF_UP)
            this.vatAmount = totalAmount.subtract(netAmount)
            this.vat = vat
        }
    }

    /**
     * How a Zahlartwechsel's new bill departs from an ordinary sale; null for an ordinary sale.
     * The new bill re-books a sale that already happened, so it takes two facts from the original.
     */
    internal data class RebookBasis(
        /**
         * The original's close. The re-applied promotions are checked against this time, not now,
         * and regardless of their current `isActive` flag — they were valid when the guest was served.
         */
        val promotionsAsOf: LocalDateTime,
        /**
         * The original's training flag, replacing the seller's current training mode. Set before the
         * voucher legs are redeemed, because a training bill must not touch real voucher balances.
         */
        val isTraining: Boolean,
        /**
         * The original's tip recipient, replacing the one `BillRepositoryImpl.create` derives from the
         * seller's CURRENT role — a role change since the sale must not turn an employee tip into
         * taxed turnover (or back). Set before the VAT rows and the process data are built.
         */
        val tipRecipient: TipRecipient,
    )

    /**
     * A refusal of one of the bill's requested promotions. On a Zahlartwechsel ([rebookBasis] set)
     * the same promotions were accepted at the original's close and are checked against that time,
     * so a refusal now can only mean the promotion was edited since the sale — reported as
     * `BILL_REBOOK_PROMOTION_CHANGED` rather than as the ordinary sale's refusal.
     */
    private fun promotionRefused(
        refusal: PosException,
        rebookBasis: RebookBasis?,
    ): PosException =
        if (rebookBasis == null) {
            refusal
        } else {
            PosException(
                PosErrorCode.BILL_REBOOK_PROMOTION_CHANGED,
                HttpStatusCode.Conflict,
                "A promotion changed since the sale; storno and bill again (${refusal.message})",
            )
        }

    /**
     * Internal utility: Creates a bill entity within an existing transaction.
     * **IMPORTANT:** must be called within an active database transaction.
     */
    @OptIn(TestOnlyApi::class) // scaleDeductionsToCap: same-file production use of the test-visible helper
    internal suspend fun createBillInternal(
        employee: Employee,
        session: TableSession,
        orderItems: List<Long>,
        billAmount: BigDecimal,
        tips: BigDecimal,
        paymentsRequest: List<PaymentDto.PaymentRequest>,
        voucherRedeems: List<VoucherDto.RedeemVoucherRequestDto> = emptyList(),
        promotionRequests: List<PromotionDto.PromotionApplyRequestDto> = emptyList(),
        deviceId: String,
        skipSessionCompletion: Boolean = false,
        rebookBasis: RebookBasis? = null,
    ): Bill {
        // Track bill number to release on transaction completion or failure
        var billNumberToRelease: String? = null

        try {
            // 4. Generate and reserve bill number (thread-safe)
            val billNumber = generateAndReserveBillNumber()
            billNumberToRelease = billNumber

            // 1. Create bill entity with pre-generated bill number
            val bill =
                repository.create(
                    employee = employee,
                    tableSession = session,
                    billAmount = billAmount,
                    tips = tips,
                    billNumber = billNumber,
                )

            // Propagate training mode from employee to bill — or, on a Zahlartwechsel, from the
            // original, whatever the seller's training mode is today.
            bill.isTraining = rebookBasis?.isTraining ?: employee.trainingMode
            rebookBasis?.let { basis -> bill.tipRecipient = basis.tipRecipient }

            logger.info(
                "BillServiceImpl.createBillInternal: bill created id=${bill.id.value}, number=${bill.billNumber}, training=${bill.isTraining}",
            )

            // 2. Validate and associate order items
            if (orderItems.isEmpty()) {
                throw PosException(
                    PosErrorCode.ORDER_HAS_NO_ITEMS,
                    HttpStatusCode.BadRequest,
                    "Order items cannot be empty",
                )
            }

            // Lock all order item rows in ONE batch query to prevent concurrent bills from
            // claiming the same items (was one SELECT ... FOR UPDATE per item). Ids are
            // sorted so row locks are acquired in deterministic order — deadlock behavior
            // is no worse than the previous per-id loop. Every row locked before is still
            // locked; only the round-trip count changed.
            val lockedItemsById =
                OrderItem
                    .find {
                        (OrderItemTable.id inList orderItems.distinct().sorted()) and
                            (OrderItemTable.sessionId eq session.id)
                    }.forUpdate()
                    .associateBy { it.id.value }

            val items = mutableListOf<OrderItem>()
            orderItems.forEach { itemId ->
                val item =
                    lockedItemsById[itemId]
                        ?: throw PosException(
                            PosErrorCode.ITEM_NOT_IN_SESSION,
                            HttpStatusCode.NotFound,
                            "OrderItem $itemId not found in session ${session.id.value}",
                        )

                if (item.itemStatus != ItemStatus.OPEN) {
                    throw PosException(
                        PosErrorCode.ITEM_NOT_OPEN,
                        HttpStatusCode.Conflict,
                        "Order item $itemId must be in OPEN status, current status: ${item.itemStatus}",
                    )
                }

                // Update item
                item.bill = bill
                item.itemStatus = ItemStatus.PAID
                items += item
            }

            // 2b. Calculate total discount amount from items with discounts
            val totalDiscount =
                items
                    .filter { it.discount < BigDecimal.ONE }
                    .sumOf { it.unitPrice - it.finalPrice }
            bill.discountAmount = totalDiscount

            // 3. Create paymentsRequest
            if (paymentsRequest.isEmpty()) {
                throw validationFailed("At least one payment must be provided")
            }

            val currency = configManager.getDefaultCurrency()
            paymentsRequest.forEach { dto ->
                // A deactivated tender takes no new money (MD-06); its historic payments are untouched.
                val paymentType = requireActivePaymentType(dto.paymentTypeId)

                Payment.new {
                    this.bill = bill
                    this.paymentType = paymentType
                    this.amount = toBigDecimal(dto.amount)
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = currentTimeStamp()
                    this.currency = currency
                    // Frozen here, never read live: editing the type later must not reclassify
                    // this settlement (slice 4c, spec §3.2).
                    this.createsReceivable = paymentType.createsReceivable
                }
            }

            val payments = bill.payments

            val voucherPayments = payments.filter { it.paymentType.isVoucher }
            if (voucherPayments.size != voucherRedeems.size) {
                throw validationFailed(
                    "Number of voucher payments (${voucherPayments.size}) does not match number of voucher redemptions (${voucherRedeems.size})",
                )
            }

            // 4. Process voucher redemptions if provided — link each to its matching voucher payment
            voucherPayments.zip(voucherRedeems).forEach { (payment, dto) ->
                redeemVoucher(payment, dto)
            }

            // 4b. Apply promotional vouchers (Aktionsgutscheine)
            var totalPromotionAmount = BigDecimal.ZERO
            val itemPromotionDeductions = mutableMapOf<Long, BigDecimal>() // orderItemId -> deduction
            // Round-3 INV-F3: keep the created BillPromotion audit rows so, if the cap engages
            // below, their deductedAmount can be scaled to match the capped bill.promotionAmount.
            val createdBillPromotions = mutableListOf<BillPromotion>()

            if (promotionRequests.isNotEmpty()) {
                val now = rebookBasis?.promotionsAsOf ?: currentTimeStamp()

                // Batch-fetch all referenced promotions in one query (was one findById per
                // request entry). Missing ids still fail lazily below so the first offending
                // entry in request order produces the same validation error as before.
                val promotionsById =
                    Promotion
                        .find {
                            PromotionTable.id inList promotionRequests.map { it.promotionId }.distinct()
                        }.associateBy { it.id.value }

                // A promotion flagged `combinable = false` ("Nicht mit anderen Aktionen
                // kombinierbar") must be the ONLY promotion on the bill. Counted by DISTINCT
                // promotionId: the same id appearing twice is `isMultiUse` (one promotion applied
                // to several items), which is a different axis and stays governed by the per-scope
                // rules below. This sits BEFORE the application loop deliberately - no
                // BillPromotion row, no deduction and no VAT-group mutation may happen for a bill
                // that is going to be rejected.
                val distinctPromotionIds = promotionRequests.map { it.promotionId }.distinct()
                if (distinctPromotionIds.size > 1) {
                    val nonCombinable =
                        distinctPromotionIds.firstNotNullOfOrNull { id ->
                            promotionsById[id]?.takeIf { !it.combinable }
                        }
                    if (nonCombinable != null) {
                        throw promotionRefused(
                            PosException(
                                PosErrorCode.PROMOTION_NOT_COMBINABLE,
                                HttpStatusCode.Conflict,
                                "Promotion '${nonCombinable.name}' cannot be combined with other promotions",
                            ),
                            rebookBasis,
                        )
                    }
                }

                // Apply ITEM_SPECIFIC promotions first, then BILL_WIDE
                val sorted =
                    promotionRequests.sortedBy { req ->
                        val promo =
                            promotionsById[req.promotionId]
                                ?: throw promotionRefused(validationFailed("Promotion not found: ${req.promotionId}"), rebookBasis)
                        if (promo.scope == PromotionScope.ITEM_SPECIFIC) 0 else 1
                    }

                for (req in sorted) {
                    val promotion =
                        promotionsById[req.promotionId]
                            ?: throw promotionRefused(validationFailed("Promotion not found: ${req.promotionId}"), rebookBasis)

                    // Validate active and within validity. A Zahlartwechsel re-applies a promotion the
                    // guest already received, so its current active flag does not matter.
                    if (rebookBasis == null && !promotion.isActive) {
                        throw validationFailed("Promotion ${promotion.name} is not active")
                    }
                    if (promotion.validFrom > now) {
                        throw promotionRefused(validationFailed("Promotion ${promotion.name} is not yet valid"), rebookBasis)
                    }
                    if (promotion.validUntil != null && promotion.validUntil!! < now) {
                        throw promotionRefused(validationFailed("Promotion ${promotion.name} has expired"), rebookBasis)
                    }

                    // Validate per-bill application rules
                    when {
                        promotion.scope == PromotionScope.BILL_WIDE -> {
                            val countInRequest = sorted.count { it.promotionId == req.promotionId }
                            if (countInRequest > 1) {
                                throw promotionRefused(validationFailed("BILL_WIDE promotion ${promotion.name} can only be applied once per bill"), rebookBasis)
                            }
                        }
                        promotion.scope == PromotionScope.ITEM_SPECIFIC && !promotion.isMultiUse -> {
                            val countInRequest = sorted.count { it.promotionId == req.promotionId }
                            if (countInRequest > 1) {
                                throw promotionRefused(
                                    validationFailed("Single-apply promotion ${promotion.name} can only be applied once per bill"),
                                    rebookBasis,
                                )
                            }
                        }
                        promotion.scope == PromotionScope.ITEM_SPECIFIC && promotion.isMultiUse -> {
                            val duplicateItem = sorted.count { it.promotionId == req.promotionId && it.orderItemId == req.orderItemId }
                            if (duplicateItem > 1) {
                                throw promotionRefused(validationFailed("Promotion ${promotion.name} already applied to this item"), rebookBasis)
                            }
                        }
                    }

                    // Calculate deduction
                    val deductedAmount: BigDecimal
                    when (promotion.scope) {
                        PromotionScope.ITEM_SPECIFIC -> {
                            val targetItemId =
                                req.orderItemId
                                    ?: throw promotionRefused(validationFailed("orderItemId required for ITEM_SPECIFIC promotion"), rebookBasis)
                            val targetItem =
                                items.firstOrNull { it.id.value == targetItemId }
                                    ?: throw validationFailed("Order item $targetItemId not found in bill items")
                            val targetProduct =
                                promotion.targetProduct
                                    ?: throw promotionRefused(validationFailed("ITEM_SPECIFIC promotion ${promotion.name} has no target product"), rebookBasis)
                            if (targetItem.product.id.value != targetProduct.id.value) {
                                throw promotionRefused(validationFailed("Order item product does not match promotion target product"), rebookBasis)
                            }

                            // Stacked item promotions never take an item below zero: each deduction is
                            // bounded by what the promotions applied to this item EARLIER in the request
                            // left on it (ItemPromotionCap, the till's rule too), not by the whole
                            // finalPrice — two 8.00 promotions on a 10.00 item used to deduct 16.00 and
                            // sign a negative VAT row. The base is unchanged (a percentage is still of
                            // finalPrice), so a bill whose promotions never overlapped deducts exactly
                            // what it did before; one that finds nothing left deducts 0 and keeps its row.
                            val uncapped =
                                when (promotion.discountType) {
                                    DiscountType.FIXED_AMOUNT -> promotion.discountValue
                                    // TSER-3: a promotion deduction is a TERMINAL monetary amount, not an
                                    // intermediate to be apportioned — it lands verbatim in the printed
                                    // Beleg, in BillPromotion.deductedAmount, and via `groupTotal -=
                                    // deduction` in a signed BillVatDetail bucket. Anhang I requires the
                                    // signed Umsätze to carry EXACTLY two decimals and to match the printed
                                    // Beleg, and fmtAmount truncates (DOWN), so a scale-4 deduction left a
                                    // sub-cent the truncation dropped. HALF_UP here matches the printed
                                    // Beleg and makes the bucket scale-2 by construction, so DOWN and
                                    // HALF_UP agree on every surface. BILL_WIDE rounds the same way: its
                                    // scale-4 deduction made the bill total (26.7120) unpayable in cents.
                                    DiscountType.PERCENTAGE ->
                                        (
                                            targetItem.finalPrice * promotion.discountValue /
                                                BigDecimal(
                                                    100,
                                                )
                                        ).setScale(2, RoundingMode.HALF_UP)
                                    DiscountType.FREE_ITEM -> targetItem.finalPrice
                                }
                            val alreadyDeducted = itemPromotionDeductions[targetItemId] ?: BigDecimal.ZERO
                            deductedAmount =
                                ItemPromotionCap
                                    .cap(
                                        deduction = uncapped.toDecimal(),
                                        itemPrice = targetItem.finalPrice.toDecimal(),
                                        alreadyDeducted = alreadyDeducted.toDecimal(),
                                    ).toPlainString()
                                    .toBigDecimal()
                            itemPromotionDeductions[targetItemId] =
                                (itemPromotionDeductions[targetItemId] ?: BigDecimal.ZERO) + deductedAmount
                        }
                        PromotionScope.BILL_WIDE -> {
                            val remainingBillTotal = bill.billAmount - totalPromotionAmount
                            deductedAmount =
                                when (promotion.discountType) {
                                    DiscountType.FIXED_AMOUNT -> minOf(promotion.discountValue, remainingBillTotal)
                                    DiscountType.PERCENTAGE ->
                                        (
                                            remainingBillTotal * promotion.discountValue /
                                                BigDecimal(
                                                    100,
                                                )
                                        ).setScale(2, RoundingMode.HALF_UP)
                                    DiscountType.FREE_ITEM -> throw validationFailed("FREE_ITEM not supported for BILL_WIDE scope")
                                }
                        }
                    }

                    // Create BillPromotion audit record
                    val billPromotion =
                        BillPromotion.new {
                            this.bill = bill
                            this.promotion = promotion
                            this.orderItem = req.orderItemId?.let { id -> items.first { it.id.value == id } }
                            this.deductedAmount = deductedAmount
                            this.appliedBy = employee
                            this.appliedAt = currentTimeStamp()
                        }
                    createdBillPromotions += billPromotion

                    totalPromotionAmount += deductedAmount
                }

                // Cap total deductions to bill amount
                if (totalPromotionAmount > bill.billAmount) {
                    // Round-3 INV-F3: keep the audit rows consistent with the capped header — the
                    // export derives Rabatt lines from BillPromotion.deductedAmount and UMS_BRUTTO
                    // from bill.promotionAmount; scale the rows so Σ deductedAmount == the capped
                    // total (last row absorbs the rounding remainder for a penny-exact sum).
                    scaleDeductionsToCap(createdBillPromotions, bill.billAmount, totalPromotionAmount)
                    totalPromotionAmount = bill.billAmount
                }

                bill.promotionAmount = totalPromotionAmount
            }

            // 5. Validate payment amounts
            val paidAmount = payments.sumOf { it.amount }
            val totalAmount = bill.billAmount - bill.promotionAmount + bill.tips
            if (totalAmount.compareTo(paidAmount) != 0) {
                throw paymentTotalMismatch(totalAmount.toPlainString(), paidAmount.toPlainString())
            }

            // 6. Calculate and create VAT details
            // THE per-key promotion split (PromotionGroupApportionment): item-specific deductions stay
            // on their item's Vat group; ALL bill-wide deductions, aggregated, are apportioned with a
            // penny-exact largest-remainder split — per RATE first (== per UST_SCHLUESSEL, matching the
            // export), then across same-rate Vat entities by their own post-item-deduction gross (two
            // distinct Vat entities CAN share a rate; VatServiceImpl only enforces uniqueness on
            // vatClass). The shares sum to EXACTLY the bill-wide deduction at scale 2, so the signed
            // BillVatDetail brutto stays balanced (Σ vatDetails == paidAmount, RA-1). The SAME function
            // rebuilds this split from the stored bill_promotion rows when the bill becomes an
            // Ausgangsrechnung (BillToInvoiceMapper), so each promotion there is an allowance that
            // reconciles with these rows to the cent.
            val itemsByVatGroup = items.groupBy { it.vat }
            val promotionSplit =
                PromotionGroupApportionment.split(
                    basis =
                        PromotionGroupBasis(
                            chargedGross = itemsByVatGroup.mapValues { (_, groupItems) -> groupItems.sumOf { it.finalPrice } },
                            itemDeductions =
                                itemsByVatGroup.mapValues { (_, groupItems) ->
                                    groupItems.fold(BigDecimal.ZERO) { acc, item ->
                                        acc + (itemPromotionDeductions[item.id.value] ?: BigDecimal.ZERO)
                                    }
                                },
                            billWideDeduction =
                                totalPromotionAmount - itemPromotionDeductions.values.fold(BigDecimal.ZERO) { acc, v -> acc + v },
                            rateOf = { it.vatRate },
                        ),
                    tieBreaker = compareBy { it.id.value },
                )
            val vatToTotalPrice = promotionSplit.postPromotionGross.toMutableMap()

            // Tip modelling keys off bill.tipRecipient (DSFinV-K TrinkgeldAG vs TrinkgeldAN),
            // NOT employee.role. (These agree today — BillRepository derives tipRecipient from the
            // closer's role — but tipRecipient is the fiscal source of truth.)
            //  - OWNER (TrinkgeldAG): the tip is taxable at the rate of the underlying supply
            //    (Anhang I p. 120 signs it in the 19 % container), so it is distributed across the
            //    item VAT groups. The weights come from OwnerTipWeights: post-promotion gross, then
            //    the charged item gross when a promotion discounted the bill to zero, then the item
            //    count. A zero price does not make the supply non-taxable, so the tip never lands
            //    in key 5 while there is an item whose rate it can follow.
            //  - EMPLOYEE (TrinkgeldAN): the tip is non-taxable and is NOT turnover, so it gets no
            //    BillVatDetail row at all. It reaches the signature as ProcessDataBuilder's
            //    `nonTaxableExtra`, which places it in the same 5th brutto slot the old 0% row
            //    occupied — the signed bytes are unchanged, but the tip no longer leaks into the
            //    Z-Bon VAT groups, a "TE0%" Kassenbuch line or revenue figures (E2E-2026-09-15 N1).
            //  - An OWNER tip on a bill with no items at all has no item rate to follow, so it
            //    keeps the single 0%/key-5 "Nicht steuerbar" row.
            val itemsByVat = items.groupBy { it.vat }
            val tipShares =
                if (bill.tipRecipient == TipRecipient.OWNER) {
                    // THE shared split (OwnerTipApportionment): the SAME weights and penny-exact
                    // two-pass apportionment `DsfinvkExportServiceImpl.apportionOwnerTip` exports and
                    // `BillToInvoiceMapper` takes back out of the Ausgangsrechnung, so the signed
                    // BillVatDetail, the DSFinV-K export and the invoice agree exactly per rate.
                    // Per RATE first (== per UST_SCHLUESSEL), then across same-rate Vat entities.
                    OwnerTipApportionment.split(
                        tip = bill.tips,
                        basis =
                            OwnerTipBasis(
                                postPromotionGross = vatToTotalPrice,
                                chargedGross = itemsByVat.mapValues { (_, group) -> group.sumOf { it.finalPrice } },
                                itemCount = itemsByVat.mapValues { (_, group) -> BigDecimal(group.size) },
                                rateOf = { it.vatRate },
                            ),
                        tieBreaker = compareBy { it.id.value },
                    )
                } else {
                    null
                }
            if (tipShares != null) {
                vatToTotalPrice.forEach { (vat, groupTotal) ->
                    createBillVat(bill, groupTotal + (tipShares[vat] ?: BigDecimal.ZERO), vat)
                }
            } else {
                vatToTotalPrice.forEach { (vat, totalPrice) ->
                    createBillVat(bill, totalPrice, vat)
                }
                // EMPLOYEE tip (TrinkgeldAN): non-taxable, carried OUTSIDE the VAT breakdown and
                // signed into the 5th brutto slot by ProcessDataBuilder. An OWNER tip on a bill
                // with no items has no rate to follow and keeps the 0%/key-5 row.
                if (bill.tipRecipient == TipRecipient.OWNER && bill.tips.compareTo(BigDecimal.ZERO) != 0) {
                    createBillVat(bill, bill.tips, resolveZeroRateVat())
                }
            }

            // 7. Finalize bill
            bill.paidAmount = paidAmount
            bill.endTime = currentTimeStamp()

            // 8. Validate VAT calculation. An EMPLOYEE tip is deliberately NOT part of
            // bill.vatDetails (it is non-taxable, signed into the 5th slot), so it is excluded
            // from the expected total here and added back by ProcessDataBuilder.
            //
            // NOT merely a sanity check: NonTaxableTipCarrier keys on exactly this invariant to
            // tell a post-change bill from a pre-upgrade one (whose tip IS a 0% row). Weakening
            // it — or adding a bill-creation path that skips it — mis-signs those bills into WORM,
            // where an imbalance is only ever a warning. BillTipNotAVatRowTest pins the coupling.
            val nonTaxableTip =
                if (bill.tipRecipient == TipRecipient.EMPLOYEE) bill.tips else BigDecimal.ZERO
            val vatSum = bill.vatDetails.sumOf { it.totalAmount }
            val expectedVatTotal = bill.paidAmount - nonTaxableTip
            if (vatSum.compareTo(expectedVatTotal) != 0) {
                throw validationFailed(
                    "Sum of VAT amounts ($vatSum) must equal paid amount minus non-taxable tip ($expectedVatTotal)",
                )
            }

            // 9. Complete table session if all items are processed
            // Skipped when caller handles session lifecycle externally (e.g. the counter voucher sale)
            val hasOpenItems = session.orderItems.any { it.itemStatus == ItemStatus.OPEN }
            if (!skipSessionCompletion && !hasOpenItems) {
                try {
                    val completedSession = tableSessionRepository.completeSession(session.id.value)
                    if (completedSession.completed &&
                        completedSession.table.locationTypeRef.id.value !in
                        setOf(
                            WellKnownLocationTypes.VOUCHER,
                            WellKnownLocationTypes.QUICK_SALE,
                        )
                    ) {
                        val statusUpdate =
                            createTableStatusUpdateDto(
                                table = completedSession.table,
                                status = completedSession.table.tableStatus,
                                sessionId = completedSession.id.value,
                                sessionStartTime = completedSession.startTime.toString(),
                                employee = employee,
                            )
                        NotificationService.notifyTableStatusUpdate(statusUpdate)
                    }
                } catch (e: IllegalStateException) {
                    // Session has open items or is already completed - not fatal
                    if (e.message?.contains("open order items") == true) {
                        logger.warn("BillServiceImpl.createBillInternal: session completion failed with open order items, continuing")
                    } else {
                        throw e
                    }
                }
            } else if (!skipSessionCompletion && hasOpenItems) {
                // Partial payment — bill created but OPEN items remain; emit so the live open amount refreshes.
                // Emitted in-transaction to mirror the adjacent completion branch; SSE is a hint, corrected by the next event / authoritative fetch.
                if (session.table.locationTypeRef.id.value !in
                    setOf(
                        WellKnownLocationTypes.VOUCHER,
                        WellKnownLocationTypes.QUICK_SALE,
                    )
                ) {
                    val statusUpdate =
                        createTableStatusUpdateDto(
                            table = session.table,
                            status = TableStatus.OCCUPIED,
                            // The card names the session's owner, not whoever billed part of it.
                            employee = session.employeeId,
                            sessionId = session.id.value,
                            sessionStartTime = session.startTime.toString(),
                            isTraining = session.isTraining,
                        )
                    NotificationService.notifyTableStatusUpdate(statusUpdate)
                }
            }

            // Release bill number on successful transaction
            billNumberToRelease.let { BillNumberTracker.release(it) }
            billNumberToRelease = null

            return bill
        } catch (e: Exception) {
            billNumberToRelease?.let { BillNumberTracker.release(it) }
            throw e
        }
    }

    /**
     * Issues the next bill number (RE-YYMMDD-SEQUENCE) and claims it in [BillNumberTracker].
     * Caller MUST release the bill number after transaction completion using BillNumberTracker.release().
     *
     * Uniqueness comes from the counter row [BillRepository.generatedBillNumber] locks for the rest
     * of the transaction, not from the tracker, which is an in-process second guard. The number is
     * drawn ONCE: every draw advances the counter, so drawing again after a refused reservation
     * would skip a number for good. A refused reservation therefore aborts the transaction instead —
     * the rollback returns the drawn number too.
     */
    private suspend fun generateAndReserveBillNumber(): String {
        val billNumber = repository.generatedBillNumber()
        if (!BillNumberTracker.reserve(billNumber)) {
            throw PosException(
                PosErrorCode.BILL_NUMBER_COLLISION,
                HttpStatusCode.Conflict,
                "Bill number $billNumber is already claimed by a pending transaction; retry the request",
            )
        }
        logger.info("BillServiceImpl.generateAndReserveBillNumber: reserved billNumber=$billNumber")
        return billNumber
    }

    override suspend fun getTseQrCodeData(billId: String): BillDto.TseQrCodeDataDto? =
        dbQuery {
            val bill = repository.findById(billId.toLong()) ?: throw billNotFound(billId)
            bill.toTseQrCodeDataDto()
        }

    // --- PosException helpers ---

    private fun billNotFound(id: String) =
        PosException(
            PosErrorCode.BILL_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Bill with id=$id not found",
        )

    private fun billNotFoundByNumber(billNumber: String) =
        PosException(
            PosErrorCode.BILL_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Bill with number=$billNumber not found",
        )

    private fun billAlreadyDeleted(id: String) =
        PosException(
            PosErrorCode.BILL_ALREADY_DELETED,
            HttpStatusCode.Conflict,
            "Bill $id is already deleted",
        )

    private fun billAuthorizationDenied(msg: String) =
        PosException(
            PosErrorCode.BILL_AUTHORIZATION_DENIED,
            HttpStatusCode.Forbidden,
            msg,
        )

    private fun paymentTotalMismatch(
        expected: String,
        actual: String,
    ) = PosException(
        PosErrorCode.PAYMENT_TOTAL_MISMATCH,
        HttpStatusCode.BadRequest,
        "Payment total $actual does not match bill total $expected",
    )

    private fun employeeNotFound(employeeId: String) =
        PosException(
            PosErrorCode.EMPLOYEE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Employee not found: $employeeId",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}

/**
 * Round-3 INV-F3: when the promotion cap engages (Σ deductedAmount > billAmount), scale the
 * per-row [BillPromotion.deductedAmount] proportionally so their Σ equals the capped total
 * [cap] EXACTLY at scale 2. The last row absorbs the rounding remainder, so the audit rows
 * (which the DSFinV-K export turns into Rabatt lines) stay reconciled with the capped
 * `bill.promotionAmount` (which drives UMS_BRUTTO). Mutates [promos] in place; caller supplies
 * the pre-cap sum as [uncappedTotal]. No-op when [uncappedTotal] is zero or [promos] is empty.
 */
@TestOnlyApi
fun scaleDeductionsToCap(
    promos: List<BillPromotion>,
    cap: BigDecimal,
    uncappedTotal: BigDecimal,
) {
    if (promos.isEmpty() || uncappedTotal.signum() == 0) return
    val scale = cap.divide(uncappedTotal, 10, RoundingMode.HALF_UP)
    var remaining = cap
    promos.forEachIndexed { i, p ->
        val capped =
            if (i == promos.lastIndex) {
                remaining.setScale(2, RoundingMode.HALF_UP)
            } else {
                (p.deductedAmount * scale).setScale(2, RoundingMode.HALF_UP)
            }
        p.deductedAmount = capped
        remaining -= capped
    }
}
