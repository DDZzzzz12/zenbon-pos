package org.example.project.features.allocation.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.matching.MatchTelemetryDto
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.allocation.domain.models.ReversedAllocation
import org.example.project.features.allocation.domain.repository.AllocationQueueRepository
import org.example.project.features.allocation.domain.repository.AllocationRepository
import org.example.project.features.allocation.domain.service.AllocationService
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.contract.data.service.deriveInstalmentStatus
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.contract.domain.repository.ContractRepository
import org.example.project.features.document.data.repository.InvoiceStorno
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.finance.domain.service.BookkeepingStartService
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.matching.data.service.MatchSignal
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.toExactDecimalString
import org.example.project.utils.log.AuditEntityRef
import java.math.BigDecimal

/**
 * See [AllocationService]. Every method maps inside its own [dbQuery]: the rows are lazy Exposed
 * entities that throw once the transaction has closed.
 *
 * The write path validates and then hands off to [AllocationWriter], which is the only thing allowed
 * to touch `payment_allocation` — the allocation and both residuals have to commit together.
 *
 * Amounts are written with [toExactDecimalString], not `toDecimalString`: these columns are
 * `DECIMAL(19,4)` and the scale-2 formatter would silently round a residual on its way to the wire.
 *
 * ## Why over-allocation is refused here but tolerated below
 *
 * `residualOf` deliberately returns a negative residual rather than clamping, so a bad row that
 * arrived some other way stays visible instead of looking settled. This service refuses to *create*
 * that state on the **source**: assigning €120 of a €100 payment is an operator error, and 409 with
 * both figures in the message is a better answer than a negative number appearing on a screen later.
 * The target side carries no such guard — see [requireHeadroom].
 *
 * ## What this slice deliberately refuses or omits
 *
 * - `AUTO_RULE` cannot be requested: nothing applies an allocation automatically, so a client
 *   claiming it would corrupt the only record of how an assignment came about.
 * - No cross-shop guard: this branch's `InvoiceTable` has no `shop_id` and the system is single-shop.
 */
class AllocationServiceImpl(
    private val queueRepository: AllocationQueueRepository,
    private val writer: AllocationWriter,
    private val allocationRepository: AllocationRepository,
    private val contractRepository: ContractRepository,
    private val bookkeepingStart: BookkeepingStartService,
    private val configManager: IShopConfigurationManager,
) : AllocationService {
    override suspend fun queue(
        filter: AllocationDto.QueueFilter,
        limit: Int,
        offset: Long,
    ): AllocationDto.QueueResponseDto {
        // Outside the dbQuery below: cutoff() opens a transaction of its own, and nesting one is
        // the pattern pos-server/CLAUDE.md forbids.
        val bookedFrom = bookkeepingStart.cutoff()
        return dbQuery {
            val unassignedOnly = filter == AllocationDto.QueueFilter.NICHT_ZUGEORDNET
            val transactions = queueRepository.queue(unassignedOnly, bookedFrom, limit, offset)
            // One lookup for the whole page rather than one per row.
            val allocations = queueRepository.allocationsForTransactions(transactions.map { it.id.value })
            AllocationDto.QueueResponseDto(
                items = transactions.map { it.toRow(allocations[it.id.value].orEmpty()) },
                total = queueRepository.countQueue(unassignedOnly, bookedFrom),
            )
        }
    }

    override suspend fun openInvoices(
        supplierId: Long?,
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenInvoiceListDto {
        val bookedFrom = bookkeepingStart.cutoff()
        return dbQuery {
            AllocationDto.OpenInvoiceListDto(
                // The Zuordnung editor lists every open invoice, not only the late ones.
                items =
                    queueRepository
                        .openInvoices(
                            supplierId = supplierId,
                            overdueAsOf = null,
                            bookedFrom = bookedFrom,
                            limit = limit,
                            offset = offset,
                        ).map { it.toOpenInvoice() },
                total = queueRepository.countOpenInvoices(supplierId, overdueAsOf = null, bookedFrom = bookedFrom),
            )
        }
    }

    override suspend fun openSalesInvoices(
        debtorId: Long?,
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenSalesInvoiceListDto {
        val bookedFrom = bookkeepingStart.cutoff()
        return dbQuery {
            AllocationDto.OpenSalesInvoiceListDto(
                items =
                    queueRepository
                        .openSalesInvoices(
                            debtorId = debtorId,
                            overdueAsOf = null,
                            bookedFrom = bookedFrom,
                            limit = limit,
                            offset = offset,
                        ).map { it.toOpenSalesInvoice() },
                total = queueRepository.countOpenSalesInvoices(debtorId, overdueAsOf = null, bookedFrom = bookedFrom),
            )
        }
    }

    override suspend fun openInstalments(
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenInstalmentListDto {
        val bookedFrom = bookkeepingStart.cutoff()
        // Read out here for the same reason as cutoff() above: the configuration lookup can fall
        // through to the database in its own transaction, and nesting one is forbidden.
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val today = currentTimeStamp().date
            AllocationDto.OpenInstalmentListDto(
                items =
                    queueRepository
                        .openInstalments(bookedFrom = bookedFrom, limit = limit, offset = offset)
                        .map { it.toOpenInstalment(today, currency) },
                total = queueRepository.countOpenInstalments(bookedFrom),
            )
        }
    }

    override suspend fun create(
        request: AllocationDto.CreateRequestDto,
        createdBy: String?,
    ): AllocationDto.CreateResponseDto = dbQuery { request.validate().write(createdBy) }

    override suspend fun createAll(
        requests: List<AllocationDto.CreateRequestDto>,
        createdBy: String?,
    ): List<AllocationDto.CreateResponseDto> {
        if (requests.isEmpty()) badRequest("A batch needs at least one allocation")
        return dbQuery {
            // One dbQuery around the whole list: the writer below requires an ambient transaction and
            // recomputes both residuals per write, so the single-item path composes here unchanged and
            // any rejection rolls the entire set back.
            val validated = requests.map { it.validate() }
            requireBatchHeadroom(validated)
            validated.map { it.write(createdBy) }
        }
    }

    override suspend fun reverse(
        allocationId: Long,
        reversedBy: String?,
    ) {
        // T9-12: the row is hard-deleted, so the audit row — written outside this transaction by
        // catchingThrows — is the only trace of what was undone. The block returns the snapshot.
        ExceptionConverter.catchingThrows(
            context = "reverseAllocation(allocationId=$allocationId, reversedBy=$reversedBy)",
            employeeId = reversedBy,
            operation = "REVERSE_ALLOCATION",
            entityType = "PAYMENT_ALLOCATION",
            auditEntity = { reversed -> AuditEntityRef(id = reversed.id.toString(), name = reversed.describe()) },
        ) { dbQuery { removeAndSnapshot(allocationId) } }
    }

    private fun removeAndSnapshot(allocationId: Long): ReversedAllocation {
        val existing =
            allocationRepository.findById(allocationId)
                ?: throw PosException(
                    PosErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatusCode.NotFound,
                    "Allocation $allocationId not found",
                )
        val snapshot =
            ReversedAllocation(
                id = allocationId,
                sourceKind = existing.sourceKind,
                sourceId =
                    existing.bankTransaction?.id?.value
                        ?: existing.kassenbuchHeader?.id?.value
                        ?: existing.bill?.id?.value,
                targetKind = existing.targetKind,
                targetId =
                    existing.invoice?.id?.value
                        ?: existing.salesInvoice?.id?.value
                        ?: existing.contractInstalment?.id?.value
                        ?: existing.counterKassenbuchHeader?.id?.value
                        ?: existing.counterBankAccount?.id?.value,
                amount = existing.amount,
            )
        writer.remove(allocationId)
        return snapshot
    }

    private fun ReversedAllocation.describe(): String = "$sourceKind ${sourceId ?: "-"} -> $targetKind ${targetId ?: "-"}: ${amount.toExactDecimalString()}"

    /**
     * Two grouped queries, not a scan: the counts are over every allocation ever made.
     *
     * Every [AllocationMatchedBy] constant is reported, with a zero where nothing used it — a caller
     * reading `byMatchedBy["SUGGESTION_ACCEPTED"]` must get `0` rather than null, or "no suggestions
     * accepted yet" renders as a blank where a number belongs. The signals get no such treatment:
     * their names live in the matcher and the ladder is expected to grow.
     */
    override suspend fun telemetry(): MatchTelemetryDto.ResponseDto =
        dbQuery {
            val byMatchedBy = allocationRepository.countByMatchedBy()
            MatchTelemetryDto.ResponseDto(
                byMatchedBy = AllocationMatchedBy.entries.associate { it.name to (byMatchedBy[it.name] ?: 0L) },
                bySignal = allocationRepository.countByMatchedSignal(),
                total = byMatchedBy.values.sum(),
            )
        }

    /**
     * One request whose kinds, amount, provenance and both endpoints have been resolved, so writing
     * it touches nothing that could still turn out to be invalid.
     *
     * It is a step of its own because a batch has to reject the whole list before any of it is
     * written: validating inside the write loop would still roll back correctly, but the error would
     * be about the state left behind rather than the item the operator got wrong.
     */
    private data class ValidatedAllocation(
        val request: AllocationDto.CreateRequestDto,
        val sourceKind: AllocationSourceKind,
        val targetKind: AllocationTargetKind,
        val amount: BigDecimal,
        val matchedBy: AllocationMatchedBy,
        val matchedSignal: String?,
        val transaction: BankStatementTransaction?,
        val invoice: Invoice?,
        val instalment: ContractInstalment?,
        val salesInvoice: SalesInvoice?,
    )

    private fun AllocationDto.CreateRequestDto.validate(): ValidatedAllocation {
        val sourceKind = this.sourceKind.toSourceKind()
        val targetKind = this.targetKind.toTargetKind()
        val target = targetKind.requireTarget(this)
        val matchedBy = this.matchedBy.toMatchedBy()
        return ValidatedAllocation(
            request = this,
            sourceKind = sourceKind,
            targetKind = targetKind,
            amount = amount.toPositiveAmount(),
            matchedBy = matchedBy,
            matchedSignal = signalFor(matchedBy),
            transaction = sourceKind.requireSource(this),
            invoice = target.invoice,
            instalment = target.instalment,
            salesInvoice = target.salesInvoice,
        )
    }

    /**
     * Writes one validated allocation and reads both residuals back. Requires an ambient transaction:
     * a batch calls this once per item and they must commit together.
     */
    private fun ValidatedAllocation.write(createdBy: String?): AllocationDto.CreateResponseDto {
        // Re-checked per item, not only per batch: after the previous write the source residual has
        // already been recomputed, so this reads what is genuinely left.
        requireHeadroom(transaction, amount)

        val written =
            writer.allocate(
                NewAllocation(
                    sourceKind = sourceKind,
                    targetKind = targetKind,
                    amount = amount,
                    matchedBy = matchedBy,
                    matchedSignal = matchedSignal,
                    bankTransactionId = transaction?.id?.value,
                    kassenbuchHeaderId = request.kassenbuchHeaderId,
                    billId = request.billId,
                    invoiceId = invoice?.id?.value,
                    contractInstalmentId = instalment?.id?.value,
                    salesInvoiceId = salesInvoice?.id?.value,
                    counterKassenbuchHeaderId = request.counterKassenbuchHeaderId,
                    counterBankAccountId = request.counterBankAccountId,
                    accountNo = request.accountNo,
                    bookingText = request.bookingText,
                    note = request.note,
                    createdBy = createdBy,
                ),
            )

        return AllocationDto.CreateResponseDto(
            allocation = written.toRow(),
            sourceOpenAmount = transaction?.openAmount?.toExactDecimalString(),
            // Only one of the three is ever set — the target kind decides which.
            targetOpenAmount = (salesInvoice?.openAmount ?: invoice?.openAmount ?: instalment?.openAmount)?.toExactDecimalString(),
        )
    }

    /**
     * A batch's items share a payment, so what each one may take is the *sum* against that payment's
     * residual. Checking them one at a time would let two 60s pass against a 100 and only refuse the
     * second once the first had been written.
     */
    private fun requireBatchHeadroom(items: List<ValidatedAllocation>) {
        items
            .groupBy { it.transaction }
            .forEach { (transaction, group) ->
                requireHeadroom(transaction, group.fold(BigDecimal.ZERO) { sum, item -> sum + item.amount })
            }
    }

    /**
     * A cash-book source is read only — `kassenbuch_header` is TSE-signed and this feature never
     * writes it, so there is no residual to check and nothing to look up here.
     */
    private fun AllocationSourceKind.requireSource(request: AllocationDto.CreateRequestDto): BankStatementTransaction? =
        when (this) {
            AllocationSourceKind.BANK_TRANSACTION -> {
                val id =
                    request.bankTransactionId
                        ?: badRequest("BANK_TRANSACTION needs a bankTransactionId")
                val found =
                    BankStatementTransaction.findById(id)
                        ?: notFound("Bank transaction $id not found")
                requireResolvedStatement(found, id)
                found
            }

            AllocationSourceKind.CASH_BOOK_ENTRY -> {
                request.kassenbuchHeaderId ?: badRequest("CASH_BOOK_ENTRY needs a kassenbuchHeaderId")
                null
            }

            AllocationSourceKind.TILL_RECEIPT -> {
                // Resolved only to prove the row exists, exactly as CASH_BOOK_ENTRY above: a
                // clearing Bill is not a transaction with a residual, so there is nothing to return.
                val id = request.billId ?: badRequest("TILL_RECEIPT needs a billId")
                Bill.findById(id) ?: notFound("Bill $id not found")
                null
            }
        }

    /**
     * Which of our accounts a statement belongs to is our decision and may not have been made yet.
     * Until it is, the transaction has no shop, so no guard depending on one can be evaluated — which
     * is why such rows never enter the queue either.
     *
     * The account id is read off `readValues` rather than through the `bankAccount` accessor, which
     * would materialize the whole `BankAccount` entity just to learn whether it exists.
     */
    private fun requireResolvedStatement(
        source: BankStatementTransaction,
        id: Long,
    ) {
        val resolved = source.statement.readValues[BankStatementTable.bankAccountId] != null
        if (!resolved) {
            throw PosException(
                PosErrorCode.ALLOCATION_SOURCE_UNRESOLVED,
                HttpStatusCode.Conflict,
                "Transaction $id belongs to a statement not yet assigned to a bank account",
            )
        }
    }

    /**
     * The entity a target kind names — exactly one of the three is ever set. A holder rather than a
     * `Pair` because a third residual-carrying kind (`SALES_INVOICE`) does not fit a pair.
     */
    private data class ResolvedTarget(
        val invoice: Invoice? = null,
        val instalment: ContractInstalment? = null,
        val salesInvoice: SalesInvoice? = null,
    )

    /**
     * Resolves whichever target the declared kind names; at most one of the three is ever non-null,
     * and the terminal kinds resolve to neither because they carry no row and no residual.
     *
     * Two of those terminal kinds nonetheless require a counter-leg id, resolved here only to prove
     * the row exists — not to produce a residual, which they still do not have.
     */
    private fun AllocationTargetKind.requireTarget(request: AllocationDto.CreateRequestDto): ResolvedTarget =
        when (this) {
            AllocationTargetKind.INVOICE -> {
                val id = request.invoiceId ?: badRequest("INVOICE needs an invoiceId")
                val invoice = Invoice.findById(id) ?: notFound("Invoice $id not found")
                // T9-19: a cancelled invoice and its correction carry a zero residual and never
                // appear in the picker, but a direct API call could still target one — and the
                // recompute would resurrect the cancelled invoice as partly open.
                if (InvoiceStorno.isFrozen(invoice)) {
                    throw PosException(
                        PosErrorCode.ALLOCATION_TARGET_NOT_OPEN,
                        HttpStatusCode.Conflict,
                        "Invoice $id is cancelled or is a cancellation; it cannot be an allocation target",
                    )
                }
                ResolvedTarget(invoice = invoice)
            }

            AllocationTargetKind.CONTRACT_INSTALMENT -> {
                val id =
                    request.contractInstalmentId
                        ?: badRequest("CONTRACT_INSTALMENT needs a contractInstalmentId")
                ResolvedTarget(instalment = contractRepository.findInstalment(id) ?: notFound("Contract instalment $id not found"))
            }

            AllocationTargetKind.SALES_INVOICE -> {
                val id =
                    request.salesInvoiceId
                        ?: badRequest("SALES_INVOICE needs a salesInvoiceId")
                val invoice =
                    SalesInvoice.findById(id)
                        ?: notFound("Sales invoice $id not found")
                // The picker lists only ISSUED invoices, but this guard is what keeps a direct API
                // call from targeting a DRAFT or CANCELLED one. Writing open_amount onto a cancelled
                // invoice would make it report itself partially paid and consume the payment's
                // residual against an obligation that no longer exists.
                if (invoice.status != SalesInvoiceStatus.ISSUED) {
                    throw PosException(
                        PosErrorCode.ALLOCATION_TARGET_NOT_OPEN,
                        HttpStatusCode.Conflict,
                        "Sales invoice $id is ${invoice.status.name}; only an ISSUED invoice can be an " +
                            "allocation target",
                    )
                }
                ResolvedTarget(salesInvoice = invoice)
            }

            AllocationTargetKind.CASH_TRANSIT -> {
                // A Geldtransit that names no cash-book entry records nothing: the spec's warning is
                // that an unmatched half means cash left the till and never reached the bank. The
                // pairing itself (direction, amount, date) is deliberately not checked here — that
                // needs a rule for partial and multi-day transits, which this slice does not settle.
                val id =
                    request.counterKassenbuchHeaderId
                        ?: badRequest("CASH_TRANSIT needs a counterKassenbuchHeaderId naming the cash-book side of the transfer")
                KassenbuchHeader.findById(id) ?: notFound("Kassenbuch entry $id not found")
                ResolvedTarget()
            }

            AllocationTargetKind.INTERNAL_TRANSFER -> {
                val id =
                    request.counterBankAccountId
                        ?: badRequest("INTERNAL_TRANSFER needs a counterBankAccountId naming the other account")
                BankAccount.findById(id) ?: notFound("Bank account $id not found")
                ResolvedTarget()
            }

            AllocationTargetKind.EXPENSE_NO_DOC -> {
                // The only kind whose booking text is mandatory: an undocumented expense has no
                // document by definition, so this text is the sole evidence of what the money was.
                // A FEE is self-evident from the transaction it sits on, so its text stays optional.
                if (request.bookingText.isNullOrBlank()) {
                    badRequest("EXPENSE_NO_DOC needs a bookingText: with no document, it is the only record of what was paid")
                }
                ResolvedTarget()
            }

            AllocationTargetKind.FEE -> ResolvedTarget()
        }

    /**
     * Refuse to assign more than the payment has left.
     *
     * The residual read here is the **effective** one — `open_amount` when set, else the full
     * amount — for the same reason the queue coalesces: a NULL means no residual has been computed,
     * not that the entry is spent. Reading it as zero would reject every first allocation against a
     * freshly imported transaction.
     *
     * There is deliberately **no matching guard on the target**. An instalment is an expectation,
     * not a debt: a variable obligation (Strom, Telefon) genuinely settles above what was expected,
     * and refusing that would block a correct booking. The residual simply goes negative, which is
     * what keeps the overshoot visible. Do not "fix" this into a check.
     */
    private fun requireHeadroom(
        transaction: BankStatementTransaction?,
        amount: BigDecimal,
    ) {
        val source = transaction ?: return
        val remaining = source.openAmount ?: source.amount
        if (amount > remaining) {
            throw PosException(
                PosErrorCode.ALLOCATION_EXCEEDS_SOURCE,
                HttpStatusCode.Conflict,
                "Cannot assign ${amount.toExactDecimalString()}: " +
                    "only ${remaining.toExactDecimalString()} of this payment is unassigned",
            )
        }
    }

    private fun String.toSourceKind(): AllocationSourceKind =
        AllocationSourceKind.entries.firstOrNull { it.name == this }
            ?: badRequest("Unknown sourceKind '$this'")

    private fun String.toTargetKind(): AllocationTargetKind =
        AllocationTargetKind.entries.firstOrNull { it.name == this }
            ?: badRequest("Unknown targetKind '$this'")

    /**
     * `AUTO_RULE` is refused rather than accepted or quietly downgraded to `MANUAL`: nothing in this
     * system applies an allocation by itself, so a client claiming it would poison the one column the
     * reporting reads to learn which rules actually help.
     *
     * The exhaustive `when` is deliberate — a future constant fails to compile here and forces a
     * decision rather than defaulting into acceptance.
     */
    private fun String.toMatchedBy(): AllocationMatchedBy {
        val parsed =
            AllocationMatchedBy.entries.firstOrNull { it.name == this }
                ?: badRequest("Unknown matchedBy '$this'")
        return when (parsed) {
            AllocationMatchedBy.MANUAL, AllocationMatchedBy.SUGGESTION_ACCEPTED -> parsed
            AllocationMatchedBy.AUTO_RULE ->
                badRequest("matchedBy AUTO_RULE cannot be set by a client: nothing applies allocations automatically")
        }
    }

    /**
     * The signal behind an accepted suggestion, or null.
     *
     * A `MANUAL` allocation naming one is **refused**, not stored and not silently dropped: the
     * column exists to say which of the ladder's signals earn their place, and a human's own reading
     * counted as a rule's success would corrupt exactly those numbers. Dropping it quietly would
     * leave a client believing it was recorded.
     *
     * A name that is not one of [MatchSignal]'s is refused for the same reason one step further out.
     * `countByMatchedSignal` groups by the raw column, so an unrecognised string would appear as its
     * own category beside the seven real ones and nothing would distinguish a rule from a typo. The
     * sibling `byMatchedBy` cannot be polluted this way because it is built by iterating its enum;
     * this is what gives `bySignal` the same property.
     */
    private fun AllocationDto.CreateRequestDto.signalFor(matchedBy: AllocationMatchedBy): String? {
        val signal = matchedSignal?.takeIf { it.isNotBlank() } ?: return null
        if (matchedBy == AllocationMatchedBy.MANUAL) {
            badRequest(
                "matchedSignal '$signal' cannot be set on a MANUAL allocation: " +
                    "a signal records which suggestion rule produced an assignment, not a human's own reading",
            )
        }
        if (MatchSignal.entries.none { it.name == signal }) {
            badRequest(
                "Unknown matchedSignal '$signal': expected one of " +
                    MatchSignal.entries.joinToString { it.name },
            )
        }
        return signal
    }

    /** Money arrives as a decimal string; a bare number would already have lost precision. */
    private fun String.toPositiveAmount(): BigDecimal {
        val parsed = toBigDecimalOrNull() ?: badRequest("Amount '$this' is not a decimal number")
        if (parsed <= BigDecimal.ZERO) badRequest("Amount must be positive, was '$this'")
        return parsed
    }

    private fun BankStatementTransaction.toRow(allocations: List<PaymentAllocation>): AllocationDto.QueueRowDto =
        AllocationDto.QueueRowDto(
            transactionId = id.value,
            bookingDate = bookingDate.toString(),
            valueDate = valueDate.toString(),
            amount = amount.toExactDecimalString(),
            openAmount = (openAmount ?: amount).toExactDecimalString(),
            currency = currency,
            creditDebit = creditDebit,
            counterpartyName = counterpartyName,
            counterpartyIban = counterpartyIban,
            remittance = remittanceUnstructured ?: remittanceStructuredRef,
            allocations = allocations.map { it.toRow() },
            reversalIndicator = reversalIndicator,
        )

    private fun Invoice.toOpenInvoice(): AllocationDto.OpenInvoiceDto =
        AllocationDto.OpenInvoiceDto(
            invoiceId = id.value,
            invoiceNumber = invoiceNumber,
            supplierId = supplier?.id?.value,
            supplierName = sellerName,
            issueDate = issueDate?.toString(),
            dueDate = dueDate?.toString(),
            grossTotal = taxInclusiveTotal?.toExactDecimalString(),
            openAmount = (openAmount ?: amountDue ?: taxInclusiveTotal ?: BigDecimal.ZERO).toExactDecimalString(),
            currency = currency,
        )

    /** The AR mirror of [Invoice.toOpenInvoice]; [SalesInvoice.buyerName] is the snapshot, not the master record. */
    private fun SalesInvoice.toOpenSalesInvoice(): AllocationDto.OpenSalesInvoiceDto =
        AllocationDto.OpenSalesInvoiceDto(
            salesInvoiceId = id.value,
            invoiceNumber = invoiceNumber,
            debtorId = debtor?.id?.value,
            debtorName = buyerName,
            issueDate = issueDate.toString(),
            dueDate = dueDate?.toString(),
            grossTotal = taxInclusiveTotal?.toExactDecimalString(),
            openAmount = (openAmount ?: amountDue ?: taxInclusiveTotal ?: BigDecimal.ZERO).toExactDecimalString(),
            currency = currency,
        )

    /**
     * The status is **derived**, never read off the column, so this picker and the Verträge screen
     * cannot disagree about whether an instalment is `OVERDUE` or `PARTIAL`.
     *
     * [currency] is the shop's configured default, the same value `ContractServiceImpl` stamps on
     * the Verträge screen — the instalment itself carries none, and defaulting to EUR here would let
     * the two screens quote the same instalment in different currencies.
     */
    private fun ContractInstalment.toOpenInstalment(
        today: LocalDate,
        currency: String,
    ): AllocationDto.OpenInstalmentDto {
        val owningContract = contract
        return AllocationDto.OpenInstalmentDto(
            instalmentId = id.value,
            contractId = owningContract.id.value,
            contractName = owningContract.name,
            // Through the party: the creditor role no longer carries a name of its own.
            supplierName = owningContract.supplier?.partner?.name,
            dueDate = dueDate.date.toString(),
            expectedAmount = expectedAmount?.toExactDecimalString(),
            openAmount = (openAmount ?: expectedAmount ?: BigDecimal.ZERO).toExactDecimalString(),
            currency = currency,
            status =
                deriveInstalmentStatus(
                    stored = status,
                    dueDate = dueDate.date,
                    openAmount = openAmount,
                    expectedAmount = expectedAmount,
                    asOf = today,
                ).name,
        )
    }

    /**
     * The instalment is resolved once into a local: the row needs its contract's name and its due
     * date, not only the id, so there is nothing to save by reading the column off `readValues` —
     * and on a target that is not an instalment the accessor is null without touching the database.
     */
    private fun PaymentAllocation.toRow(): AllocationDto.AllocationRowDto {
        val instalment = contractInstalment
        return AllocationDto.AllocationRowDto(
            id = id.value,
            sourceKind = sourceKind,
            targetKind = targetKind,
            amount = amount.toExactDecimalString(),
            matchedBy = matchedBy,
            invoiceId = invoice?.id?.value,
            invoiceNumber = invoice?.invoiceNumber,
            salesInvoiceId = salesInvoice?.id?.value,
            salesInvoiceNumber = salesInvoice?.invoiceNumber,
            contractInstalmentId = instalment?.id?.value,
            contractName = instalment?.contract?.name,
            instalmentDueDate = instalment?.dueDate?.date?.toString(),
            accountNo = accountNo,
            bookingText = bookingText,
            note = note,
        )
    }

    private fun badRequest(message: String): Nothing = throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, message)

    private fun notFound(message: String): Nothing = throw PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, message)
}
