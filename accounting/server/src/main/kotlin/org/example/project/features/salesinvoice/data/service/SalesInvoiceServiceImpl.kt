package org.example.project.features.salesinvoice.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.config.getArNumbering
import org.example.project.core.domain.dto.salesinvoice.ArNumberingConfigDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.salesinvoice.EInvoiceFormatPreference
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.data.service.BillWriteConflict
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.repository.BillRepository
import org.example.project.features.bill.domain.service.SaleSigningGateway
import org.example.project.features.bill.domain.service.SaleSigningKind
import org.example.project.features.bill.domain.service.SaleSigningRequest
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.requireActivePaymentType
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceAllowanceCharge
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceBooking
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceLine
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceVatBreakdown
import org.example.project.features.salesinvoice.domain.repository.ArNumberSequenceRepository
import org.example.project.features.salesinvoice.domain.repository.SalesInvoiceRepository
import org.example.project.features.salesinvoice.domain.service.ArNumberingPolicy
import org.example.project.features.salesinvoice.domain.service.BillInvoiceabilityFacts
import org.example.project.features.salesinvoice.domain.service.BillInvoiceabilityGuard
import org.example.project.features.salesinvoice.domain.service.BillToInvoiceMapper
import org.example.project.features.salesinvoice.domain.service.DraftInvoiceContent
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.salesinvoice.domain.service.InvoiceSeller
import org.example.project.features.salesinvoice.domain.service.MappedLines
import org.example.project.features.salesinvoice.domain.service.ReceivableSettlement
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceAllowanceContent
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceContent
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceLineContent
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfAllowance
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfData
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfLine
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfVatRow
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceService
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceValidator
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceVatContent
import org.example.project.features.salesinvoice.domain.service.StandaloneInvoiceContent
import org.example.project.features.salesinvoice.domain.service.StandaloneInvoiceMapper
import org.example.project.features.salesinvoice.mapper.toDto
import org.example.project.features.salesinvoice.mapper.toListItemDto
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.terminal.domain.requireSigningTerminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.pdf.util.FileStorageHelper
import org.example.project.utils.builder.ProcessDataBuilder
import org.example.project.utils.builder.tse.toTseQrCodeContent
import org.example.project.utils.builder.tse.toTseReceiptData
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.log.AuditEntityRef
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import java.io.File
import java.math.BigDecimal
import java.util.UUID

/**
 * Sequences the pieces built in this slice. The only rule it owns is the **order** inside [issue]:
 * validate, then take a number, then freeze. Any other order turns a content mistake into a
 * permanent gap in a §14 sequence.
 */
class SalesInvoiceServiceImpl(
    private val repository: SalesInvoiceRepository,
    private val numbers: ArNumberSequenceRepository,
    private val facts: BillInvoiceFactsGatherer,
    private val pdf: PdfGenerationService,
    private val billRepository: BillRepository,
    private val terminalRepository: TerminalRepository,
    private val saleSigning: SaleSigningGateway,
    private val configManager: IShopConfigurationManager,
    private val allocationWriter: AllocationWriter,
) : SalesInvoiceService {
    override suspend fun createFromBillAsDto(
        request: SalesInvoiceDto.CreateFromBillRequestDto,
        createdBy: UUID?,
    ): SalesInvoiceDto.ResponseDto {
        val created = createFromBill(request.billId, request.toBuyer(), createdBy)
        return getAsDto(dbQuery { created.id.value })
    }

    override suspend fun issueAsDto(
        invoiceId: Long,
        issuedBy: UUID,
    ): SalesInvoiceDto.ResponseDto {
        issue(invoiceId, issuedBy)
        return getAsDto(invoiceId)
    }

    override suspend fun cancelAsDto(
        invoiceId: Long,
        cancelledBy: UUID,
        reason: String?,
    ): SalesInvoiceDto.ResponseDto {
        val storno = cancel(invoiceId, cancelledBy, reason)
        return getAsDto(dbQuery { storno.id.value })
    }

    override suspend fun listAsDto(
        status: String?,
        invoiceTypeCode: String?,
        open: Boolean,
        overdue: Boolean,
        debtorId: Long?,
        limit: Int,
        offset: Long,
    ): SalesInvoiceDto.ListResponseDto =
        dbQuery {
            val parsedStatus =
                status?.takeIf { it.isNotBlank() }?.let {
                    runCatching { SalesInvoiceStatus.valueOf(it.uppercase()) }.getOrElse { _ ->
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Unknown status '$it'",
                        )
                    }
                }
            val (page, total) =
                repository.list(
                    status = parsedStatus,
                    invoiceTypeCode = invoiceTypeCode?.takeIf { it.isNotBlank() },
                    open = open,
                    overdue = overdue,
                    debtorId = debtorId,
                    limit = limit.coerceIn(1, 200),
                    offset = offset.coerceAtLeast(0L),
                )
            SalesInvoiceDto.ListResponseDto(items = page.map { it.toListItemDto() }, total = total)
        }

    override suspend fun getAsDto(invoiceId: Long): SalesInvoiceDto.ResponseDto =
        dbQuery {
            (repository.findById(invoiceId) ?: throw notFound(invoiceId)).toDto()
        }

    override suspend fun createStandaloneAsDto(
        request: SalesInvoiceDto.CreateStandaloneRequestDto,
        createdBy: UUID?,
    ): SalesInvoiceDto.ResponseDto =
        dbQuery {
            val shop = ShopInfo.all().first()
            val debtor = request.debtorId?.let { Debtor.findById(it) }
            requireActiveDebtor(debtor)
            val mapped = StandaloneInvoiceMapper.toContent(request.lines)
            val invoice =
                SalesInvoice.new {
                    this.shop = shop
                    this.revenueOrigin = RevenueOrigin.STANDALONE
                    this.buyerName = request.buyerName.trim()
                    this.buyerVatId = request.buyerVatId?.trim()?.ifBlank { null }
                    this.buyerStreet = request.buyerStreet?.trim()?.ifBlank { null }
                    this.buyerZip = request.buyerZip?.trim()?.ifBlank { null }
                    this.buyerCity = request.buyerCity?.trim()?.ifBlank { null }
                    this.buyerCountry = request.buyerCountry?.trim()?.ifBlank { null }
                    this.buyerEmail = request.buyerEmail?.trim()?.ifBlank { null }
                    this.debtor = debtor
                    this.issueDate = currentTimeStamp()
                    this.serviceDate = parseDateTime(request.serviceDate)
                    this.dueDate = request.dueDate?.let { parseDateTime(it) }
                    this.paymentTermsText = request.paymentTermsText?.trim()?.ifBlank { null }
                    this.notes = request.notes?.trim()?.ifBlank { null }
                }
            writeSeller(invoice, shop)
            writeMappedContent(invoice, mapped)
            upsertBooking(invoice, request.revenueAccountNo, request.buCode)
            invoice.toDto()
        }

    override suspend fun updateStandaloneAsDto(
        invoiceId: Long,
        request: SalesInvoiceDto.UpdateStandaloneRequestDto,
    ): SalesInvoiceDto.ResponseDto =
        dbQuery {
            val invoice = repository.findById(invoiceId) ?: throw notFound(invoiceId)
            requireStandaloneOrigin(invoice)
            requireActiveDebtor(invoice.debtor)
            val mapped = StandaloneInvoiceMapper.toContent(request.lines)
            val content =
                StandaloneInvoiceContent(
                    buyerName = request.buyerName,
                    buyerVatId = request.buyerVatId,
                    buyerStreet = request.buyerStreet,
                    buyerZip = request.buyerZip,
                    buyerCity = request.buyerCity,
                    buyerCountry = request.buyerCountry,
                    buyerEmail = request.buyerEmail,
                    serviceDate = parseDateTime(request.serviceDate),
                    dueDate = request.dueDate?.let { parseDateTime(it) },
                    paymentTermsText = request.paymentTermsText,
                    notes = request.notes,
                    lines = mapped.lines,
                    vatRows = mapped.vatRows,
                    lineNetTotal = mapped.lineNetTotal,
                    taxTotal = mapped.taxTotal,
                    taxInclusiveTotal = mapped.taxInclusiveTotal,
                )
            repository.replaceDraftContent(invoice, content)
            upsertBooking(invoice, request.revenueAccountNo, request.buCode)
            invoice.toDto()
        }

    override suspend fun deleteStandalone(invoiceId: Long) {
        dbQuery {
            val invoice = repository.findById(invoiceId) ?: throw notFound(invoiceId)
            repository.deleteDraft(invoice)
        }
    }

    /**
     * The standalone editor is only for case-C invoices. A `POS_SETTLED` draft is a snapshot of a
     * fiscalised bill — its lines, VAT split and totals are the receipt's — and rewriting them with
     * typed ones would issue a §14 document for an arbitrary amount carrying the TSE signature of a
     * receipt for a different amount. `replaceDraftContent` guards only on status, so this is the
     * origin check that keeps the bill snapshot intact.
     */
    override suspend fun settleAtTill(
        invoiceId: Long,
        request: SalesInvoiceDto.SettleAtTillRequestDto,
        employeeId: UUID,
        deviceId: String,
    ): SalesInvoiceDto.ResponseDto {
        request.validate().let { errors ->
            if (errors.isNotEmpty()) {
                throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    errors.joinToString("; "),
                )
            }
        }

        // Suspend config reads, hoisted out of the transaction exactly as BillServiceImpl.createBill does.
        val mode = configManager.getTseMode()
        val defaultCurrency = configManager.getDefaultCurrency()
        val failureBehavior = configManager.getTseConfig().failureBehavior

        // Phase 1: the clearing bill, its payment and the allocation, written together or not at all.
        // Under READ_COMMITTED the bill-number counter row serialises without a serialization
        // failure; the translation is the last line of defence that keeps a numbering conflict a 409.
        val signing =
            try {
                dbQuery {
                    val invoice = repository.findById(invoiceId) ?: throw notFound(invoiceId)

                    if (invoice.status != SalesInvoiceStatus.ISSUED) {
                        throw PosException(
                            PosErrorCode.SALES_INVOICE_NOT_ISSUABLE,
                            HttpStatusCode.Conflict,
                            "Only an ISSUED invoice can be settled at the till; this one is ${invoice.status}",
                        )
                    }
                    if (invoice.revenueOrigin != RevenueOrigin.POS_RECEIVABLE) {
                        throw PosException(
                            PosErrorCode.SALES_INVOICE_ORIGIN_MISMATCH,
                            HttpStatusCode.Conflict,
                            "Only a till-opened receivable can be cleared at the till; this invoice is " +
                                "${invoice.revenueOrigin}. A bank transfer clears through the bank route, not the till.",
                        )
                    }
                    val originalBill =
                        invoice.billId?.let { Bill.findById(it.value) }
                            ?: throw PosException(
                                PosErrorCode.SALES_INVOICE_ORIGIN_MISMATCH,
                                HttpStatusCode.Conflict,
                                "Receivable invoice $invoiceId has no originating bill to supply a table session",
                            )

                    val paymentType = requireActivePaymentType(request.paymentTypeId)
                    if (paymentType.createsReceivable) {
                        throw PosException(
                            PosErrorCode.SALES_INVOICE_TENDER_NOT_SETTLING,
                            HttpStatusCode.Conflict,
                            "Tender '${paymentType.name}' opens a receivable rather than settling one",
                        )
                    }

                    val amount = BigDecimal(request.amount)
                    if (amount > invoice.openAmount) {
                        throw PosException(
                            PosErrorCode.SALES_INVOICE_OVERPAYMENT,
                            HttpStatusCode.Conflict,
                            "Amount $amount exceeds the open receivable of ${invoice.openAmount}",
                        )
                    }

                    // Terminal + fiscal-signing guard fail-fast, before any row is written.
                    val terminal = requireSigningTerminal(terminalRepository, deviceId, mode)

                    val employee =
                        Employee.findById(employeeId)
                            ?: throw PosException(
                                PosErrorCode.EMPLOYEE_NOT_FOUND,
                                HttpStatusCode.NotFound,
                                "Employee not found: $employeeId",
                            )

                    val now = currentTimeStamp()
                    val billNumber = reserveClearingBillNumber()
                    try {
                        val clearingBill =
                            Bill.new {
                                this.employeeId = employee
                                this.tableSession = originalBill.tableSession
                                this.billNumber = billNumber
                                this.billAmount = amount
                                this.discountAmount = BigDecimal.ZERO
                                this.promotionAmount = BigDecimal.ZERO
                                this.tips = BigDecimal.ZERO
                                this.paidAmount = amount
                                this.startTime = now
                                this.endTime = now
                                this.isTraining = originalBill.isTraining
                                this.clearsSalesInvoiceId = invoice.id.value
                                this.terminal = terminal
                            }

                        Payment.new {
                            this.bill = clearingBill
                            this.paymentType = paymentType
                            this.amount = amount
                            this.paymentStatus = PaymentStatus.COMPLETED
                            this.paymentTime = now
                            this.currency = defaultCurrency
                            // A clearing tender never opens a receivable — that is exactly the guard above.
                            this.createsReceivable = false
                        }

                        // Drop open_amount through the writer so recomputeSalesInvoice owns the residual.
                        allocationWriter.allocate(
                            NewAllocation(
                                sourceKind = AllocationSourceKind.TILL_RECEIPT,
                                targetKind = AllocationTargetKind.SALES_INVOICE,
                                amount = amount,
                                matchedBy = AllocationMatchedBy.MANUAL,
                                billId = clearingBill.id.value,
                                salesInvoiceId = invoice.id.value,
                            ),
                        )

                        val processData = ProcessDataBuilder.buildForBill(clearingBill)
                        ClearingSigningData(
                            billId = clearingBill.id.value,
                            billNumber = billNumber,
                            processData = processData,
                            clientId = terminal.clientId,
                        )
                    } catch (e: Exception) {
                        BillNumberTracker.release(billNumber)
                        throw e
                    }
                }
            } catch (e: Exception) {
                throw BillWriteConflict.toConflictOrNull(e) ?: e
            }

        // The row is committed; the reservation may be released.
        BillNumberTracker.release(signing.billNumber)

        // Phase 2: TSE signing, exactly as BillServiceImpl.createBill. A FAIL-mode failure still
        // leaves the committed clearing bill behind, marked FAILED, for the pending sweeper.
        // The mode and failure behaviour are the ones read before Phase 1.
        val signingRequest =
            SaleSigningRequest(
                kind = SaleSigningKind.RECEIPT,
                processData = signing.processData,
                referenceId = signing.billId.toString(),
                clientId = signing.clientId,
                entityType = TseEntityType.BILL,
            )
        val decision = saleSigning.attempt(signingRequest, mode, failureBehavior)

        dbQuery {
            val bill = Bill.findById(signing.billId)!!
            decision.transaction?.let { bill.tseTransaction = it }
            bill.tseSigningStatus = decision.status
        }

        if (decision.enqueue) {
            saleSigning.enqueue(signingRequest, decision)
        }

        decision.rethrow?.let {
            throw PosException(
                PosErrorCode.TSE_SIGNING_FAILED,
                HttpStatusCode.ServiceUnavailable,
                "TSE signing failed for clearing bill ${signing.billNumber}",
            )
        }

        return getAsDto(invoiceId)
    }

    /** Carries the little that Phase 2 signing needs across the transaction boundary. */
    private data class ClearingSigningData(
        val billId: Long,
        val billNumber: String,
        val processData: String,
        val clientId: String,
    )

    /**
     * Issues the clearing bill's number and claims it in the shared tracker, as
     * BillServiceImpl.generateAndReserveBillNumber does: drawn once, because each draw advances the
     * month's counter row, and a refused claim aborts (and so rolls back) instead of drawing again.
     * Requires an ambient transaction.
     */
    private suspend fun reserveClearingBillNumber(): String {
        val billNumber = billRepository.generatedBillNumber()
        if (BillNumberTracker.reserve(billNumber)) return billNumber
        throw PosException(
            PosErrorCode.BILL_NUMBER_COLLISION,
            HttpStatusCode.Conflict,
            "Clearing bill number $billNumber is already claimed by a pending transaction; retry the request",
        )
    }

    /**
     * A deactivated debtor takes no new invoice — created, edited or issued. The adminApp picker
     * already lists active debtors only, and the editor withholds Speichern/Ausstellen for a draft
     * whose debtor went inactive; this is the server half of that guard, so a direct API call cannot
     * go around it. Deleting such a draft stays possible: that is the way out.
     */
    private fun requireActiveDebtor(debtor: Debtor?) {
        if (debtor == null || debtor.isActive) return
        throw PosException(
            PosErrorCode.DEBTOR_INACTIVE,
            HttpStatusCode.Conflict,
            "Debtor ${debtor.displayLabel()} is deactivated and cannot be invoiced; reactivate it first",
        )
    }

    /**
     * How a refusal names the debtor. The Debitorennummer is optional, and the message used to read
     * "Debtor null is deactivated" for a debtor without one; the partner's name is always present.
     */
    private fun Debtor.displayLabel(): String {
        val name = partner.name.trim().ifBlank { "#${id.value}" }
        val number = debtorNo?.trim()?.takeIf { it.isNotEmpty() } ?: return "'$name'"
        return "$number '$name'"
    }

    private fun requireStandaloneOrigin(invoice: SalesInvoice) {
        if (invoice.revenueOrigin != RevenueOrigin.STANDALONE) {
            throw PosException(
                PosErrorCode.SALES_INVOICE_ORIGIN_MISMATCH,
                HttpStatusCode.Conflict,
                "Invoice ${invoice.id.value} has revenue origin ${invoice.revenueOrigin.name} and " +
                    "cannot be edited through the standalone editor; only STANDALONE invoices can.",
            )
        }
    }

    /**
     * Writes or updates the single booking row from the editor's values. Called at create/update so
     * the editor's values win over the debtor's defaults; `issue()` then only fills a row that does
     * not exist yet (falling back to the debtor's defaults), and 4d posts.
     */
    private fun upsertBooking(
        invoice: SalesInvoice,
        revenueAccountNo: String?,
        buCode: String?,
    ) {
        val existing = invoice.booking.firstOrNull()
        if (existing != null) {
            existing.revenueAccountNo = revenueAccountNo?.trim()?.ifBlank { null }
            existing.buCode = buCode?.trim()?.ifBlank { null }
        } else if (revenueAccountNo != null || buCode != null) {
            SalesInvoiceBooking.new {
                this.salesInvoice = invoice
                this.revenueAccountNo = revenueAccountNo?.trim()?.ifBlank { null }
                this.buCode = buCode?.trim()?.ifBlank { null }
            }
        }
    }

    private fun parseDateTime(raw: String): kotlinx.datetime.LocalDateTime =
        runCatching { kotlinx.datetime.LocalDateTime.parse(raw) }.getOrElse {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.BadRequest,
                "Invalid date-time '$raw' — expected ISO-8601 local date-time",
            )
        }

    private fun SalesInvoiceDto.CreateFromBillRequestDto.toBuyer() =
        InvoiceBuyer(
            name = buyerName,
            vatId = buyerVatId,
            street = buyerStreet,
            zip = buyerZip,
            city = buyerCity,
            country = buyerCountry,
            email = buyerEmail,
        )

    /**
     * Serves the file archived at issue. Reads bytes; renders nothing.
     */
    override suspend fun reprint(invoiceId: Long): ByteArray {
        val storagePath = ShopConfigurationManager.getDocumentStoragePath()
        val relativePath =
            dbQuery {
                val invoice = repository.findById(invoiceId) ?: throw notFound(invoiceId)
                val documentId =
                    invoice.documentId
                        ?: throw PosException(
                            PosErrorCode.SALES_INVOICE_NOT_ISSUABLE,
                            HttpStatusCode.NotFound,
                            "Invoice $invoiceId has no archived document; only an issued invoice has one",
                        )
                Document.findById(documentId.value)?.filePath
                    ?: throw PosException(
                        PosErrorCode.SALES_INVOICE_NOT_ISSUABLE,
                        HttpStatusCode.NotFound,
                        "Invoice $invoiceId references document ${documentId.value}, which does not exist",
                    )
            }

        val file = File(FileStorageHelper.resolveStoredPath(relativePath, storagePath))
        if (!file.exists()) {
            // Not a 404: the row says we issued this document and are required to keep it ten years
            // (§147 AO). A missing file is a retention incident and must read as one.
            throw PosException(
                PosErrorCode.SALES_INVOICE_NOT_ISSUABLE,
                HttpStatusCode.InternalServerError,
                "The archived PDF for invoice $invoiceId is missing from storage at ${file.path}",
            )
        }
        return withContext(Dispatchers.IO) { file.readBytes() }
    }

    override suspend fun createFromBill(
        billId: Long,
        buyer: InvoiceBuyer,
        createdBy: UUID?,
    ): SalesInvoice =
        dbQuery {
            val billFacts =
                facts.gather(billId)?.copy(receivableTender = receivableTenderOf(billId))
                    ?: throw PosException(
                        PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE,
                        HttpStatusCode.NotFound,
                        "Bill $billId not found",
                    )

            // Only a standing sale may be invoiced — refused before the mapper, whose "no items"
            // refusal would otherwise answer a Storno Beleg with a less precise reason.
            BillInvoiceabilityGuard.requireStandingSale(billFacts.billId, billFacts.kind, billFacts.isCancelled)

            // The mapper checks the bill agrees with itself; the guard checks it may be invoiced at
            // all. Both run before a row is written, so a refusal leaves nothing behind.
            val content = BillToInvoiceMapper.toDraftContent(billFacts, buyer)
            BillInvoiceabilityGuard.requireInvoiceable(
                BillInvoiceabilityFacts(
                    billId = billFacts.billId,
                    kind = billFacts.kind,
                    isCancelled = billFacts.isCancelled,
                    isTraining = billFacts.isTraining,
                    billAmount = billFacts.billAmount,
                    promotionAmount = billFacts.promotionAmount,
                    vatDetailTotal = billFacts.vatRows.fold(BigDecimal.ZERO) { acc, row -> acc + row.totalAmount },
                    ownerTip = billFacts.ownerTip,
                    hasLiveInvoice = billFacts.hasLiveInvoice,
                    receivableTender = billFacts.receivableTender,
                ),
                invoiceGross = content.taxInclusiveTotal,
            )

            writeDraft(content)
        }

    private fun writeDraft(content: DraftInvoiceContent): SalesInvoice {
        val invoice = newInvoiceHeader(content)
        content.lines.forEach { line ->
            SalesInvoiceLine.new {
                this.salesInvoice = invoice
                this.lineNo = line.lineNo
                this.itemName = line.itemName
                this.quantity = line.quantity
                this.unitCode = "C62"
                this.netUnitPrice = line.netUnitPrice
                this.lineNetAmount = line.netAmount
                this.vatRate = line.vatRatePercent
                this.vatCategoryCode = vatCategoryFor(line.vatRatePercent)
                this.vatAmount = line.vatAmount
                this.lineGrossAmount = line.grossAmount
                // A bill line is one unit at its gross price — there is no sub-cent unit to lose.
                this.grossUnitPrice = line.grossAmount
                this.sourceOrderItemId = EntityID(line.sourceOrderItemId, OrderItemTable)
            }
        }
        content.vatRows.forEach { row ->
            SalesInvoiceVatBreakdown.new {
                this.salesInvoice = invoice
                this.vatCategoryCode = vatCategoryFor(row.vatRatePercent)
                this.vatRate = row.vatRatePercent
                this.taxableAmount = row.taxableAmount
                this.vatAmount = row.vatAmount
            }
        }
        content.allowances.forEachIndexed { index, allowance ->
            SalesInvoiceAllowanceCharge.new {
                this.salesInvoice = invoice
                this.position = index + 1
                this.isCharge = false
                this.amount = allowance.amount
                this.vatCategoryCode = vatCategoryFor(allowance.vatRatePercent)
                this.vatRate = allowance.vatRatePercent
                this.vatAmount = allowance.vatAmount
                this.grossAmount = allowance.grossAmount
                this.reasonText = allowance.reason
                this.reasonCode = allowance.reasonCode
                this.billPromotionId = allowance.billPromotionId?.let { EntityID(it, BillPromotionTable) }
            }
        }
        return invoice
    }

    private fun newInvoiceHeader(content: DraftInvoiceContent): SalesInvoice {
        // Read before the row is constructed, so no query runs inside the entity's init block.
        val qrPayload = content.tse?.let { qrPayloadOfBill(content.billId, it.transactionNumber) }
        return SalesInvoice.new {
            this.shop = ShopInfo.all().first()
            // Case A or case B is decided by how the bill was SETTLED, not by the invoice: any
            // completed payment leg frozen as receivable makes this a POS_RECEIVABLE (slice 4c).
            // Re-derived at issue too, from the bill as it stands then.
            this.revenueOrigin = originFor(content.billId)
            this.billId = EntityID(content.billId, BillTable)
            this.buyerName = content.buyer.name
            this.buyerVatId = content.buyer.vatId
            this.buyerStreet = content.buyer.street
            this.buyerZip = content.buyer.zip
            this.buyerCity = content.buyer.city
            this.buyerCountry = content.buyer.country
            this.buyerEmail = content.buyer.email
            this.sellerName = content.seller.name
            this.sellerVatId = content.seller.vatId
            this.sellerTaxNumber = content.seller.taxNumber
            this.sellerStreet = content.seller.street
            this.sellerZip = content.seller.zip
            this.sellerCity = content.seller.city
            this.sellerCountry = content.seller.country
            this.issueDate = currentTimeStamp()
            this.serviceDate = content.serviceDate
            this.lineNetTotal = content.lineNetTotal
            this.allowanceTotal = content.allowanceTotal
            this.taxExclusiveTotal = content.taxExclusiveTotal
            this.taxTotal = content.taxTotal
            this.taxInclusiveTotal = content.taxInclusiveTotal
            this.notes = content.paymentNote
            content.tse?.let { tse ->
                this.tseSerialNumber = tse.serialNumber
                this.tseTransactionNumber = tse.transactionNumber
                this.tseSignatureCounter = tse.signatureCounter
                this.tseSignature = tse.signature
                this.tseSignatureAlgorithm = tse.signatureAlgorithm
                this.tseLogTimeFormat = tse.logTimeFormat
                this.tseProcessType = tse.processType
                this.tseStartTime = tse.startTime
                this.tseEndTime = tse.endTime
                this.tseTimestamp = tse.timestamp
                this.tseStartTimestamp = tse.startTimestamp
                // The ClientID has no column of its own; the payload freezes it, and is written only
                // for a NORMAL-mode signature (toTseQrCodeContent's guard) — see toTseReceiptData.
                this.tseQrCodePayload = qrPayload
            }
        }
    }

    /**
     * The Anhang I payload of [billId]'s TSE transaction, or null — when the bill is unsigned, was
     * signed in SIMULATION mode, or its transaction is not the one with [expectedTanr] (the snapshot
     * must describe a single signature, never two).
     */
    private fun qrPayloadOfBill(
        billId: Long,
        expectedTanr: Long?,
    ): String? {
        val tx = Bill.findById(billId)?.tseTransaction ?: return null
        if (expectedTanr == null || tx.transactionNumber != expectedTanr) return null
        return tx.toTseQrCodeContent()
    }

    /**
     * A draft created before the payload was snapshotted has the structured TSE columns but no
     * payload, and would therefore print the Ausfall line. Fill it at issue from the same bill
     * transaction — guarded by the TANR, so it can only ever complete the snapshot it belongs to.
     */
    private fun backfillQrPayload(invoice: SalesInvoice) {
        if (invoice.tseQrCodePayload != null) return
        val billId = invoice.billId?.value ?: return
        val tanr = invoice.tseTransactionNumber ?: return
        invoice.tseQrCodePayload = qrPayloadOfBill(billId, tanr)
    }

    /**
     * `Z` for a zero rate, `S` otherwise — what a German restaurant issues. `AE`/`K` (reverse
     * charge, intra-community) belong to the standalone invoices of slice 4b, where a human picks
     * them.
     */
    private fun vatCategoryFor(ratePercent: java.math.BigDecimal): String = if (ratePercent.signum() == 0) "Z" else "S"

    /** Snapshotted at creation, not read live at issue: `shop_info` is editable and an issued invoice is not. */
    private fun writeSeller(
        invoice: SalesInvoice,
        shop: ShopInfo,
    ) {
        val seller = facts.sellerFrom(shop)
        invoice.sellerName = seller.name
        invoice.sellerVatId = seller.vatId
        invoice.sellerTaxNumber = seller.taxNumber
        invoice.sellerStreet = seller.street
        invoice.sellerZip = seller.zip
        invoice.sellerCity = seller.city
        invoice.sellerCountry = seller.country
    }

    /** Derived lines and summed breakdown — no client-supplied total reaches the row. */
    private fun writeMappedContent(
        invoice: SalesInvoice,
        mapped: MappedLines,
    ) {
        invoice.lineNetTotal = mapped.lineNetTotal
        invoice.taxExclusiveTotal = mapped.lineNetTotal
        invoice.taxTotal = mapped.taxTotal
        invoice.taxInclusiveTotal = mapped.taxInclusiveTotal
        mapped.lines.forEachIndexed { index, line ->
            SalesInvoiceLine.new {
                this.salesInvoice = invoice
                this.lineNo = index + 1
                this.itemName = line.itemName
                this.itemDescription = line.itemDescription
                this.quantity = line.quantity
                this.unitCode = line.unitCode
                this.netUnitPrice = line.netUnitPrice
                this.lineNetAmount = line.lineNetAmount
                this.vatRate = line.vatRate
                this.vatCategoryCode = line.vatCategoryCode
                this.vatAmount = line.vatAmount
                this.lineGrossAmount = line.lineGrossAmount
                this.grossUnitPrice = line.grossUnitPrice
            }
        }
        mapped.vatRows.forEach { row ->
            SalesInvoiceVatBreakdown.new {
                this.salesInvoice = invoice
                this.vatCategoryCode = row.vatCategoryCode
                this.vatRate = row.vatRatePercent
                this.taxableAmount = row.taxableAmount
                this.vatAmount = row.vatAmount
            }
        }
    }

    override suspend fun issue(
        invoiceId: Long,
        issuedBy: UUID,
    ): SalesInvoice {
        val config = numberingConfig()
        return numberingConflictsAs409 { issueInTransaction(invoiceId, issuedBy, config) }
    }

    private suspend fun issueInTransaction(
        invoiceId: Long,
        issuedBy: UUID,
        config: ArNumberingConfigDto,
    ): SalesInvoice =
        dbQuery {
            val invoice = repository.findById(invoiceId) ?: throw notFound(invoiceId)

            // 1. Validate — before anything is consumed.
            SalesInvoiceValidator.requireIssuable(invoice.toContent())
            // A draft created while its debtor was active must not be issued after the debtor was
            // retired: the check sits here too, before the number, so a refusal leaves no gap.
            requireActiveDebtor(invoice.debtor)

            // 1a. A debtor who opted into ZUGFeRD is promised a machine-readable e-invoice. If the
            //     invoice is not a valid EN 16931 document we refuse the issue rather than silently
            //     shipping a plain PDF — and we do it BEFORE the number is consumed, so a blocked
            //     issue leaves no gap in the §14 sequence.
            val wantsZugferd = invoice.debtor?.einvoiceFormat == EInvoiceFormatPreference.ZUGFERD
            if (wantsZugferd) {
                val problem =
                    pdf.validateSalesInvoiceEInvoice(
                        invoice = invoice,
                        lines = invoice.lines.toList(),
                        vatBreakdown = invoice.vatBreakdown.toList(),
                    )
                if (problem != null) {
                    throw PosException(
                        PosErrorCode.SALES_INVOICE_EINVOICE_INVALID,
                        HttpStatusCode.UnprocessableEntity,
                        "e-invoice invalid: $problem",
                    )
                }
            }

            // 1b. A bill-derived invoice must still agree with its bill. The guard runs on the
            //     CREATE path too, but it is re-asserted here because `replaceDraftContent` is
            //     status-guarded only: any future editor — not just this slice's — that could touch
            //     a POS_SETTLED draft would otherwise issue a §14 document for an arbitrary amount
            //     carrying the TSE signature block of a receipt for a different amount. Re-running
            //     the guard before the number is consumed keeps a rejected issuance free of gaps.
            //     Every bill-derived origin, not only POS_SETTLED: a POS_RECEIVABLE draft renders a
            //     bill just the same, and the bill must still be a standing sale when it is issued.
            if (invoice.revenueOrigin != RevenueOrigin.STANDALONE) {
                val billId = invoice.billId?.value
                if (billId != null) {
                    val billFacts =
                        facts.gather(billId)
                            ?: throw PosException(
                                PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE,
                                HttpStatusCode.Conflict,
                                "Bill $billId no longer exists; invoice ${invoice.id.value} cannot be issued",
                            )
                    BillInvoiceabilityGuard.requireInvoiceable(
                        BillInvoiceabilityFacts(
                            billId = billFacts.billId,
                            kind = billFacts.kind,
                            isCancelled = billFacts.isCancelled,
                            isTraining = billFacts.isTraining,
                            billAmount = billFacts.billAmount,
                            promotionAmount = billFacts.promotionAmount,
                            vatDetailTotal = billFacts.vatRows.fold(BigDecimal.ZERO) { acc, row -> acc + row.totalAmount },
                            ownerTip = billFacts.ownerTip,
                            // At issue the invoice being issued is itself a non-cancelled row for
                            // this bill, so the gatherer's live-invoice flag would refuse it as
                            // "already invoiced". Exclude self: the only question here is whether
                            // some OTHER live invoice exists.
                            hasLiveInvoice = repository.hasLiveInvoiceForBill(billId, excludingInvoiceId = invoice.id.value),
                            receivableTender = receivableTenderOf(billId),
                        ),
                        invoiceGross = invoice.taxInclusiveTotal ?: BigDecimal.ZERO,
                    )
                }
            }

            // 2. Consume the number, inside this transaction so a later failure returns it.
            val number =
                numbers.nextNumber(
                    prefix = config.prefix,
                    year = invoice.issueDate.year,
                    startAt = config.startValue,
                    padding = config.padding,
                )

            // 3. Settle the money fields, then freeze.
            if (invoice.revenueOrigin != RevenueOrigin.STANDALONE) backfillQrPayload(invoice)
            applySettlement(invoice)
            refreshBillPaymentNote(invoice)

            // 3b. The number is the Verwendungszweck — the anchor the AR matcher matches on.
            invoice.paymentReference = number

            // 3c. A STANDALONE invoice records its booking decisions by issue at the latest; 4d
            //     resolves the Personenkonto and posts. POS_SETTLED never posts to AR and gets no row.
            if (invoice.revenueOrigin == RevenueOrigin.STANDALONE && invoice.booking.empty()) {
                SalesInvoiceBooking.new {
                    this.salesInvoice = invoice
                    this.revenueAccountNo =
                        invoice.debtor
                            ?.defaultRevenueAccountNo
                            ?.trim()
                            ?.ifBlank { null }
                    this.buCode =
                        invoice.debtor
                            ?.defaultBuCode
                            ?.trim()
                            ?.ifBlank { null }
                }
            }

            repository.markIssued(invoice, number, issuedBy)

            // 4. Archive the PDF, inside this transaction: an invoice that is issued without its
            //    document is the gap this slice exists to close. A ZUGFeRD debtor gets the hybrid
            //    PDF/A-3 (validated in step 1a); everyone else gets the plain PDF, unchanged.
            val pdfData = invoice.toPdfData(number)
            val archived =
                if (wantsZugferd) {
                    pdf.generateAndStoreSalesInvoiceZugferdPdf(
                        data = pdfData,
                        invoice = invoice,
                        lines = invoice.lines.toList(),
                        vatBreakdown = invoice.vatBreakdown.toList(),
                        employeeId = issuedBy,
                    )
                } else {
                    pdf.generateAndStoreSalesInvoicePdf(
                        data = pdfData,
                        employeeId = issuedBy,
                    )
                }
            invoice.documentId = archived.id
            invoice
        }

    /**
     * Runs a transaction that draws a §14 number, reporting a lost numbering race as 409
     * `SALES_INVOICE_NUMBER_COLLISION` rather than a 500 ([ArNumberConflict]). The transaction rolled
     * back, so the number was not consumed.
     */
    private suspend fun <T> numberingConflictsAs409(block: suspend () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            throw ArNumberConflict.toConflictOrNull(e) ?: e
        }

    override suspend fun cancel(
        invoiceId: Long,
        cancelledBy: UUID,
        reason: String?,
    ): SalesInvoice {
        // FO-65: the reason is printed on the Gutschrift, so it is mandatory and bounded like the
        // incoming-invoice cancellation's. The check runs BEFORE the audited block on purpose: a
        // malformed request is refused with 400 before anything is consumed and leaves no audit row
        // (unlike a refusal of the invoice's state, which is audited below).
        val validReason = validatedCancelReason(reason)
        val config = numberingConfig()
        // T9-12: a cancellation is a § 14 correction of an issued invoice, so it leaves an audit
        // row — on success and on refusal alike. The entity is the ORIGINAL invoice, the one acted on.
        return ExceptionConverter.catchingThrows(
            context = "cancelSalesInvoice(invoiceId=$invoiceId, cancelledBy=$cancelledBy)",
            employeeId = cancelledBy.toString(),
            operation = "CANCEL_SALES_INVOICE",
            entityType = "SALES_INVOICE",
            auditEntity = { AuditEntityRef(id = invoiceId.toString(), name = "Sales invoice $invoiceId cancelled") },
            // A refusal names the invoice too, not the "FAILED" placeholder: the attempt belongs in
            // that invoice's trail.
            failedEntityId = invoiceId.toString(),
        ) { numberingConflictsAs409 { cancelInTransaction(invoiceId, cancelledBy, validReason, config) } }
    }

    private fun validatedCancelReason(reason: String?): String {
        if (!SalesInvoiceDto.CancelRequestDto.isValidReason(reason)) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.BadRequest,
                "reason is required and must be at most ${SalesInvoiceDto.CancelRequestDto.MAX_REASON_LENGTH} characters",
            )
        }
        return reason!!.trim()
    }

    private suspend fun cancelInTransaction(
        invoiceId: Long,
        cancelledBy: UUID,
        reason: String?,
        config: ArNumberingConfigDto,
    ): SalesInvoice =
        dbQuery {
            val original = repository.findById(invoiceId) ?: throw notFound(invoiceId)

            val storno =
                SalesInvoice.new {
                    this.shop = original.shop
                    this.revenueOrigin = original.revenueOrigin
                    this.invoiceTypeCode = "381"
                    this.precedingSalesInvoice = original
                    this.buyerName = original.buyerName
                    this.buyerVatId = original.buyerVatId
                    this.buyerStreet = original.buyerStreet
                    this.buyerZip = original.buyerZip
                    this.buyerCity = original.buyerCity
                    this.buyerCountry = original.buyerCountry
                    this.buyerEmail = original.buyerEmail
                    // § 14 Abs. 4 Nr. 1–2 UStG: the Gutschrift names its issuer. It is the ORIGINAL's
                    // snapshot, not today's shop_info — the credit note reverses that document, so it
                    // must carry the same issuer even if the shop has since moved or renamed.
                    this.sellerName = original.sellerName
                    this.sellerVatId = original.sellerVatId
                    this.sellerTaxNumber = original.sellerTaxNumber
                    this.sellerStreet = original.sellerStreet
                    this.sellerZip = original.sellerZip
                    this.sellerCity = original.sellerCity
                    this.sellerCountry = original.sellerCountry
                    this.debtor = original.debtor
                    this.issueDate = currentTimeStamp()
                    this.serviceDate = original.serviceDate
                    this.currency = original.currency
                    this.lineNetTotal = original.lineNetTotal?.negate()
                    this.allowanceTotal = original.allowanceTotal?.negate()
                    this.chargeTotal = original.chargeTotal?.negate()
                    this.taxExclusiveTotal = original.taxExclusiveTotal?.negate()
                    this.taxTotal = original.taxTotal?.negate()
                    this.taxInclusiveTotal = original.taxInclusiveTotal?.negate()
                    this.amountDue = original.amountDue?.negate()
                    this.openAmount = BigDecimal.ZERO
                    this.notes = reason
                }
            copyTseSnapshot(from = original, to = storno)

            original.lines.forEach { line ->
                SalesInvoiceLine.new {
                    this.salesInvoice = storno
                    this.lineNo = line.lineNo
                    this.itemName = line.itemName
                    this.itemDescription = line.itemDescription
                    this.quantity = line.quantity
                    this.unitCode = line.unitCode
                    this.netUnitPrice = line.netUnitPrice
                    this.lineNetAmount = line.lineNetAmount.negate()
                    this.vatRate = line.vatRate
                    this.vatCategoryCode = line.vatCategoryCode
                    this.vatAmount = line.vatAmount.negate()
                    this.lineGrossAmount = line.lineGrossAmount.negate()
                    this.grossUnitPrice = line.grossUnitPrice
                }
            }
            original.vatBreakdown.forEach { row ->
                SalesInvoiceVatBreakdown.new {
                    this.salesInvoice = storno
                    this.vatCategoryCode = row.vatCategoryCode
                    this.vatRate = row.vatRate
                    this.taxableAmount = row.taxableAmount.negate()
                    this.vatAmount = row.vatAmount.negate()
                }
            }
            // The allowances reverse with the lines they reduced — same reason, rate and position,
            // every amount negated, exactly as the lines and the VAT rows above.
            original.allowanceCharges.sortedBy { it.position }.forEach { allowance ->
                SalesInvoiceAllowanceCharge.new {
                    this.salesInvoice = storno
                    this.position = allowance.position
                    this.isCharge = allowance.isCharge
                    this.amount = allowance.amount.negate()
                    this.baseAmount = allowance.baseAmount?.negate()
                    this.percentage = allowance.percentage
                    this.vatCategoryCode = allowance.vatCategoryCode
                    this.vatRate = allowance.vatRate
                    this.vatAmount = allowance.vatAmount.negate()
                    this.grossAmount = allowance.grossAmount.negate()
                    this.reasonText = allowance.reasonText
                    this.reasonCode = allowance.reasonCode
                    this.billPromotionId = allowance.billPromotionId
                }
            }

            // The original must be cancellable before the Gutschrift takes a number.
            repository.markCancelled(original, cancelledBy, reason)
            repository.updateOpenAmount(original, BigDecimal.ZERO)

            val number =
                numbers.nextNumber(
                    prefix = config.prefix,
                    year = storno.issueDate.year,
                    startAt = config.startValue,
                    padding = config.padding,
                )
            repository.markIssued(storno, number, cancelledBy)

            // The Gutschrift is itself an issued §14 document, so it is archived like one. Without
            // this an ISSUED invoice could exist with no file, which is the hole this slice closes.
            storno.documentId =
                pdf.generateAndStoreSalesInvoicePdf(storno.toPdfData(number), cancelledBy).id
            storno
        }

    /**
     * The Gutschrift carries the ORIGINAL's TSE block, verbatim.
     *
     * A credit note of a from-bill invoice inherits its revenue origin, so its PDF expects a TSE
     * disclosure; without the snapshot it printed "nicht verfügbar (Ausfall)" for a sale the TSE had
     * signed — asserting an outage that never happened. The credit note reverses a document, not the
     * till sale: no new TSE transaction exists, and the only signature that belongs to the underlying
     * Geschäftsvorfall is the original bill's. So the credit note prints exactly what the original
     * printed — the same signature block, or (when the original bill really went unsigned) the same
     * Ausfall line — and a STANDALONE original, which has neither, still prints none.
     */
    private fun copyTseSnapshot(
        from: SalesInvoice,
        to: SalesInvoice,
    ) {
        to.tseSerialNumber = from.tseSerialNumber
        to.tseTransactionNumber = from.tseTransactionNumber
        to.tseSignatureCounter = from.tseSignatureCounter
        to.tseSignature = from.tseSignature
        to.tseSignatureAlgorithm = from.tseSignatureAlgorithm
        to.tseLogTimeFormat = from.tseLogTimeFormat
        to.tseProcessType = from.tseProcessType
        to.tseStartTime = from.tseStartTime
        to.tseEndTime = from.tseEndTime
        to.tseTimestamp = from.tseTimestamp
        to.tseStartTimestamp = from.tseStartTimestamp
        to.tseQrCodePayload = from.tseQrCodePayload
    }

    /**
     * `POS_SETTLED` is paid at birth — the till already took the money, so BT-113 equals the total
     * and BT-115 is zero. Everything else is fully open until an allocation says otherwise.
     */

    /**
     * The receivable share of a bill: the sum of its completed payment legs frozen as receivable.
     */
    private fun receivableTenderOf(billId: Long): BigDecimal =
        Bill
            .findById(billId)
            ?.payments
            ?.filter { it.paymentStatus == PaymentStatus.COMPLETED }
            ?.filter { it.createsReceivable }
            ?.fold(BigDecimal.ZERO) { acc, payment -> acc + payment.amount }
            ?: BigDecimal.ZERO

    /** `POS_RECEIVABLE` when any effective leg is receivable, else `POS_SETTLED`. */
    private fun originFor(billId: Long): RevenueOrigin =
        if (receivableTenderOf(billId).signum() > 0) RevenueOrigin.POS_RECEIVABLE else RevenueOrigin.POS_SETTLED

    /**
     * Splits the total between what was paid and what is owed.
     *
     * `POS_SETTLED` is fully prepaid, `STANDALONE` fully open, and `POS_RECEIVABLE` splits by the
     * bill's receivable share — a mixed tender leaves the cash legs prepaid and only the receivable
     * open (spec §7). The origin is re-derived from the bill first, so the invoice describes the
     * settlement the bill has at issue.
     */
    private fun applySettlement(invoice: SalesInvoice) {
        val total = invoice.taxInclusiveTotal ?: BigDecimal.ZERO
        val billId = invoice.billId?.value
        if (billId != null) invoice.revenueOrigin = originFor(billId)

        when (invoice.revenueOrigin) {
            RevenueOrigin.POS_SETTLED -> {
                invoice.prepaidAmount = total
                invoice.amountDue = BigDecimal.ZERO
                invoice.openAmount = BigDecimal.ZERO
            }

            RevenueOrigin.POS_RECEIVABLE -> {
                val split = ReceivableSettlement.split(total, receivableTenderOf(billId ?: 0L))
                invoice.prepaidAmount = split.prepaid
                invoice.amountDue = split.due
                invoice.openAmount = split.due
            }

            RevenueOrigin.STANDALONE -> {
                invoice.prepaidAmount = BigDecimal.ZERO
                invoice.amountDue = total
                invoice.openAmount = total
            }
        }
    }

    /**
     * A from-bill invoice's note says what was received and what is still due. The draft wrote it
     * from the settlement the bill had then; [applySettlement] has just re-derived the split, so the
     * note is rewritten from that final split. From-bill notes are never operator-edited (the
     * standalone editor refuses those drafts), so nothing typed is lost.
     */
    private fun refreshBillPaymentNote(invoice: SalesInvoice) {
        val billId = invoice.billId?.value ?: return
        val bill = Bill.findById(billId) ?: return
        val total = invoice.taxInclusiveTotal ?: BigDecimal.ZERO
        invoice.notes =
            BillToInvoiceMapper.paymentNote(
                paidAt = invoice.serviceDate,
                billNumber = bill.billNumber,
                split =
                    ReceivableSettlement.Split(
                        prepaid = invoice.prepaidAmount ?: total,
                        due = invoice.amountDue ?: BigDecimal.ZERO,
                    ),
            )
    }

    private suspend fun numberingConfig(): ArNumberingConfigDto = ArNumberingPolicy.validated(ShopConfigurationManager.getArNumbering())

    private fun SalesInvoice.toContent() =
        SalesInvoiceContent(
            buyerName = buyerName,
            hasServiceDate = true,
            lines =
                lines.map {
                    SalesInvoiceLineContent(
                        netAmount = it.lineNetAmount,
                        vatAmount = it.vatAmount,
                        grossAmount = it.lineGrossAmount,
                        vatRate = it.vatRate,
                    )
                },
            vatRows =
                vatBreakdown.map {
                    SalesInvoiceVatContent(
                        vatRate = it.vatRate,
                        taxableAmount = it.taxableAmount,
                        vatAmount = it.vatAmount,
                    )
                },
            taxInclusiveTotal = taxInclusiveTotal,
            taxTotal = taxTotal,
            allowances =
                allowanceCharges.map {
                    SalesInvoiceAllowanceContent(
                        isCharge = it.isCharge,
                        amount = it.amount,
                        vatAmount = it.vatAmount,
                        grossAmount = it.grossAmount,
                        vatRate = it.vatRate,
                    )
                },
            lineNetTotal = lineNetTotal,
            allowanceTotal = allowanceTotal,
            chargeTotal = chargeTotal,
            taxExclusiveTotal = taxExclusiveTotal,
        )

    /** Everything the PDF shows, read off the invoice itself — never off the bill. */
    private fun SalesInvoice.toPdfData(number: String) =
        SalesInvoicePdfData(
            invoiceNumber = number,
            issueDate = issueDate,
            serviceDate = serviceDate,
            invoiceTypeCode = invoiceTypeCode,
            precedingInvoiceNumber = precedingSalesInvoice?.invoiceNumber,
            buyer =
                InvoiceBuyer(
                    name = buyerName,
                    vatId = buyerVatId,
                    street = buyerStreet,
                    zip = buyerZip,
                    city = buyerCity,
                    country = buyerCountry,
                    email = buyerEmail,
                ),
            seller =
                InvoiceSeller(
                    name = sellerName.orEmpty(),
                    vatId = sellerVatId,
                    taxNumber = sellerTaxNumber,
                    street = sellerStreet,
                    zip = sellerZip,
                    city = sellerCity,
                    country = sellerCountry,
                    // Not snapshotted in columns: rendered once, now, and frozen in the archived PDF.
                    // Without them the footer's "Bankverbindung" is empty and the payer is not told
                    // where to pay.
                    phone = shop.shopPhone.nonBlank(),
                    email = shop.shopEmail.nonBlank(),
                    website = shop.shopWebsite.nonBlank(),
                    bankName = shop.shopBankName.nonBlank(),
                    bankIban = shop.shopBankIBAN.nonBlank(),
                    bankBic = shop.shopBankBic.nonBlank(),
                ),
            lines =
                lines.map {
                    SalesInvoicePdfLine(
                        lineNo = it.lineNo,
                        itemName = it.itemName,
                        quantity = it.quantity,
                        netUnitPrice = it.netUnitPrice,
                        grossAmount = it.lineGrossAmount,
                        vatRatePercent = it.vatRate,
                        grossUnitPrice = it.grossUnitPrice,
                    )
                },
            vatRows =
                vatBreakdown.map {
                    SalesInvoicePdfVatRow(
                        vatRatePercent = it.vatRate,
                        taxableAmount = it.taxableAmount,
                        vatAmount = it.vatAmount,
                    )
                },
            allowances =
                allowanceCharges.sortedBy { it.position }.map {
                    SalesInvoicePdfAllowance(
                        reason = it.reasonText.orEmpty(),
                        vatRatePercent = it.vatRate,
                        grossAmount = it.grossAmount,
                        isCharge = it.isCharge,
                    )
                },
            // BT-109: the net after allowances. Without one it equals BT-106, so an invoice without a
            // promotion prints exactly what it always did.
            netTotal = taxExclusiveTotal ?: lineNetTotal ?: BigDecimal.ZERO,
            vatTotal = taxTotal ?: BigDecimal.ZERO,
            grossTotal = taxInclusiveTotal ?: BigDecimal.ZERO,
            prepaidAmount = prepaidAmount ?: BigDecimal.ZERO,
            amountDue = amountDue ?: BigDecimal.ZERO,
            paymentNote = notes,
            paymentReference = paymentReference,
            dueDate = dueDate,
            paymentTermsText = paymentTermsText,
            // A STANDALONE invoice never touched the till, so no TSE applies; only a from-bill
            // invoice (POS_SETTLED/POS_RECEIVABLE) must disclose an absent signature as an Ausfall.
            expectsTse = revenueOrigin != RevenueOrigin.STANDALONE,
            // The receipt's readable TSE data, from this invoice's own snapshot (AEAO Nr. 2.4.4).
            tse = toTseReceiptData(),
        )

    private fun String?.nonBlank(): String? = this?.trim()?.ifBlank { null }

    private fun notFound(id: Long) =
        PosException(
            PosErrorCode.SALES_INVOICE_NOT_ISSUABLE,
            HttpStatusCode.NotFound,
            "Sales invoice with id=$id not found",
        )
}
