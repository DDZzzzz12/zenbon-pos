package org.example.project.features.contract.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.contract.ContractAlertDto
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.domain.repository.AllocationRepository
import org.example.project.features.contract.database.tables.ContractInstalmentTable
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.contract.domain.models.ContractInstalmentStatus
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.contract.domain.repository.ContractRepository
import org.example.project.features.contract.domain.repository.ContractValues
import org.example.project.features.contract.domain.service.ContractService
import org.example.project.features.document.domain.models.Document
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.toExactDecimalString
import java.math.BigDecimal

/**
 * See [ContractService]. Every method maps inside its own [dbQuery].
 *
 * Money is parsed with `toBigDecimalOrNull` and written back with `toExactDecimalString`, never
 * `toDecimalString`, which pins scale 2 and would round a `DECIMAL(19,4)` amount on its way out.
 *
 * The single shop is resolved here rather than taken from the path, the way
 * `BookkeepingStartServiceImpl` does it — no endpoint in this feature makes the client carry a
 * shopId.
 */
class ContractServiceImpl(
    private val repository: ContractRepository,
    private val allocationRepository: AllocationRepository,
    private val configManager: IShopConfigurationManager,
) : ContractService {
    override suspend fun list(
        includeInactive: Boolean,
        search: String?,
    ): ContractDto.ListResponseDto {
        // Resolved before dbQuery: it reads the shop configuration through its own transaction, and
        // pos-server/CLAUDE.md forbids nesting one inside another.
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val contracts = repository.list(includeInactive, search)
            // One lookup for the whole page rather than one per contract: both derived dates need
            // every instalment, and asking per row is an N+1 that grows with the contract count.
            val instalments = repository.instalmentsForContracts(contracts.map { it.id.value })
            val today = currentTimeStamp().date
            val alerts = alertsByContract(contracts, instalments, today)
            val items =
                contracts.map {
                    it.toDto(
                        instalments = instalments[it.id.value].orEmpty(),
                        today = today,
                        currency = currency,
                        alertCount = alerts[it.id.value].orEmpty().size,
                    )
                }
            ContractDto.ListResponseDto(items = items, total = items.size.toLong())
        }
    }

    override suspend fun get(id: Long): ContractDto.ResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery { requireContract(id).toDtoWithInstalments(currency) }
    }

    override suspend fun create(request: ContractDto.CreateRequestDto): ContractDto.ResponseDto {
        rejectInvalid(request.validate())
        val currency = configManager.getDefaultCurrency()
        return dbQuery { repository.create(requireShopId(), request.toValues()).toDtoWithInstalments(currency) }
    }

    override suspend fun update(
        id: Long,
        request: ContractDto.UpdateRequestDto,
    ): ContractDto.ResponseDto {
        rejectInvalid(request.validate())
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val contract = requireContract(id)
            repository.update(contract, request.toValues())
            contract.toDtoWithInstalments(currency)
        }
    }

    override suspend fun instalments(contractId: Long): ContractDto.InstalmentListDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            requireContract(contractId)
            val today = currentTimeStamp().date
            val items = repository.instalmentsFor(contractId).map { it.toDto(today, currency) }
            ContractDto.InstalmentListDto(items = items, total = items.size.toLong())
        }
    }

    /**
     * **Four queries, whatever the number of contracts or instalments**, and it must stay that way:
     * the contracts, their instalments in one `inList`, every linked invoice's total in one join,
     * and every allocated amount in one `inList`. Reading `instalment.invoice` or asking for one
     * instalment's allocations per row would each put back an N+1 — this branch has already had to
     * remove two of exactly that shape.
     */
    override suspend fun alerts(): ContractAlertDto.ListResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val contracts = repository.list(includeInactive = false)
            val instalments = repository.instalmentsForContracts(contracts.map { it.id.value })
            val today = currentTimeStamp().date
            val alerts = alertsByContract(contracts, instalments, today)
            val items =
                contracts.flatMap { contract ->
                    alerts[contract.id.value].orEmpty().map { it.toDto(contract, currency) }
                }
            ContractAlertDto.ListResponseDto(items = items, total = items.size.toLong())
        }
    }

    /**
     * Every listed contract's alerts, keyed by contract id.
     *
     * A deactivated contract is skipped rather than derived and discarded: switching one off is the
     * operator saying they no longer want to hear about it, and reporting a count on a row the
     * alerts endpoint deliberately omits would be two answers to one question.
     */
    private fun alertsByContract(
        contracts: List<Contract>,
        instalments: Map<Long, List<ContractInstalment>>,
        today: LocalDate,
    ): Map<Long, List<ContractAlert>> {
        val active = contracts.filter { it.isActive }
        if (active.isEmpty()) return emptyMap()
        val facts = factsByInstalment(active.flatMap { instalments[it.id.value].orEmpty() })
        return active.associate { contract ->
            contract.id.value to
                deriveAlerts(
                    rules = contract.alertRules(),
                    instalments = instalments[contract.id.value].orEmpty().mapNotNull { facts[it.id.value] },
                    asOf = today,
                )
        }
    }

    /** The two batched lookups the derivation needs, resolved once for every instalment on the page. */
    private fun factsByInstalment(instalments: List<ContractInstalment>): Map<Long, InstalmentFacts> {
        val ids = instalments.map { it.id.value }
        val invoiceTotals = repository.invoiceTotalsForInstalments(ids)
        val allocated = allocationRepository.allocatedAmountsForInstalments(ids)
        return instalments.associate { instalment ->
            val id = instalment.id.value
            id to
                InstalmentFacts(
                    id = id,
                    dueDate = instalment.dueDate.date,
                    storedStatus = instalment.status,
                    expectedAmount = instalment.expectedAmount,
                    openAmount = instalment.openAmount,
                    // Read off the column, not the accessor: this only needs to know whether a row
                    // is linked, and touching `instalment.invoice` would load an Invoice per row.
                    hasInvoice = instalment.readValues[ContractInstalmentTable.invoiceId] != null,
                    invoiceTotal = invoiceTotals[id],
                    allocatedTotal = allocated[id]?.reduce(BigDecimal::add),
                )
        }
    }

    private fun Contract.alertRules(): ContractAlertRules =
        ContractAlertRules(
            expectsInvoice = expectsInvoice,
            expectedAmount = expectedAmount,
            tolerancePct = tolerancePct,
            toleranceAbs = toleranceAbs,
        )

    /** The derivation knows nothing about which contract it ran for, so the identity is added here. */
    private fun ContractAlert.toDto(
        contract: Contract,
        currency: String,
    ): ContractAlertDto.AlertDto =
        ContractAlertDto.AlertDto(
            kind = kind.name,
            contractId = contract.id.value,
            contractName = contract.name,
            instalmentId = instalmentId,
            dueDate = dueDate.toString(),
            expectedAmount = expectedAmount?.toExactDecimalString(),
            actualAmount = actualAmount?.toExactDecimalString(),
            currency = currency,
        )

    private fun ContractDto.CreateRequestDto.toValues(): ContractValues =
        ContractValues(
            name = name.trim(),
            interval = parseInterval(interval),
            dueDay = dueDay,
            dueWeekday = dueWeekday,
            startDate = parseDate(startDate, "startDate"),
            description = description,
            supplier = resolveSupplier(supplierId),
            expectedAmount = parseMoney(expectedAmount, "expectedAmount"),
            tolerancePct = parseMoney(tolerancePct, "tolerancePct"),
            toleranceAbs = parseMoney(toleranceAbs, "toleranceAbs"),
            endDate = endDate?.let { parseDate(it, "endDate") },
            noticePeriodDays = noticePeriodDays,
            expectsInvoice = expectsInvoice,
            sepaMandateId = blankToNull(sepaMandateId),
            defaultAccountNo = defaultAccountNo,
            defaultBuCode = defaultBuCode,
            defaultCostCenter = defaultCostCenter,
            document = resolveDocument(documentId),
            isActive = true,
        )

    private fun ContractDto.UpdateRequestDto.toValues(): ContractValues =
        ContractValues(
            name = name.trim(),
            interval = parseInterval(interval),
            dueDay = dueDay,
            dueWeekday = dueWeekday,
            startDate = parseDate(startDate, "startDate"),
            description = description,
            supplier = resolveSupplier(supplierId),
            expectedAmount = parseMoney(expectedAmount, "expectedAmount"),
            tolerancePct = parseMoney(tolerancePct, "tolerancePct"),
            toleranceAbs = parseMoney(toleranceAbs, "toleranceAbs"),
            endDate = endDate?.let { parseDate(it, "endDate") },
            noticePeriodDays = noticePeriodDays,
            expectsInvoice = expectsInvoice,
            sepaMandateId = blankToNull(sepaMandateId),
            defaultAccountNo = defaultAccountNo,
            defaultBuCode = defaultBuCode,
            defaultCostCenter = defaultCostCenter,
            document = resolveDocument(documentId),
            isActive = isActive,
        )

    /**
     * The single-contract paths; the list batches its instalments instead.
     *
     * The count is derived here too, so the detail and the list agree — the same batched lookups,
     * over one contract.
     */
    private fun Contract.toDtoWithInstalments(currency: String): ContractDto.ResponseDto {
        val instalments = repository.instalmentsFor(id.value)
        val today = currentTimeStamp().date
        val alerts = alertsByContract(listOf(this), mapOf(id.value to instalments), today)
        return toDto(
            instalments = instalments,
            today = today,
            currency = currency,
            alertCount = alerts[id.value].orEmpty().size,
        )
    }

    private fun Contract.toDto(
        instalments: List<ContractInstalment>,
        today: LocalDate,
        currency: String,
        alertCount: Int,
    ): ContractDto.ResponseDto {
        val derived = instalments.map { it.dueDate.date to it.derivedStatus(today) }
        return ContractDto.ResponseDto(
            id = id.value,
            name = name,
            description = description,
            supplierId = supplier?.id?.value,
            // Through the party: the creditor role no longer carries a name of its own.
            supplierName = supplier?.partner?.name,
            expectedAmount = expectedAmount?.toExactDecimalString(),
            tolerancePct = tolerancePct?.toExactDecimalString(),
            toleranceAbs = toleranceAbs?.toExactDecimalString(),
            interval = interval,
            dueDay = dueDay,
            dueWeekday = dueWeekday,
            startDate = startDate.date.toString(),
            endDate = endDate?.date?.toString(),
            noticePeriodDays = noticePeriodDays,
            expectsInvoice = expectsInvoice,
            sepaMandateId = sepaMandateId,
            defaultAccountNo = defaultAccountNo,
            defaultBuCode = defaultBuCode,
            defaultCostCenter = defaultCostCenter,
            documentId = document?.id?.value,
            isActive = isActive,
            nextDueDate = derived.earliestUnsettled()?.toString(),
            lastPaymentDate = derived.latestPaid()?.toString(),
            currency = currency,
            alertCount = alertCount,
        )
    }

    /**
     * The earliest instalment still owing. `WAIVED` is excluded along with `PAID`: it was
     * deliberately written off, and reporting it would promise a payment that will never come.
     */
    private fun List<Pair<LocalDate, ContractInstalmentStatus>>.earliestUnsettled(): LocalDate? =
        filterNot { (_, status) ->
            status == ContractInstalmentStatus.PAID || status == ContractInstalmentStatus.WAIVED
        }.minOfOrNull { (dueDate, _) -> dueDate }

    private fun List<Pair<LocalDate, ContractInstalmentStatus>>.latestPaid(): LocalDate? =
        filter { (_, status) -> status == ContractInstalmentStatus.PAID }
            .maxOfOrNull { (dueDate, _) -> dueDate }

    /**
     * [deriveInstalmentStatus] is the only place a status is decided — the instalment list, the next
     * due date and the last payment all read it, so none of the three can drift from the others.
     */
    private fun ContractInstalment.derivedStatus(today: LocalDate): ContractInstalmentStatus =
        deriveInstalmentStatus(
            stored = status,
            dueDate = dueDate.date,
            openAmount = openAmount,
            expectedAmount = expectedAmount,
            asOf = today,
        )

    /** The stored column is not reported; see [derivedStatus]. */
    private fun ContractInstalment.toDto(
        today: LocalDate,
        currency: String,
    ): ContractDto.InstalmentDto =
        ContractDto.InstalmentDto(
            id = id.value,
            contractId = contract.id.value,
            dueDate = dueDate.date.toString(),
            expectedAmount = expectedAmount?.toExactDecimalString(),
            openAmount = openAmount?.toExactDecimalString(),
            status = derivedStatus(today).name,
            invoiceId = invoice?.id?.value,
            invoiceNumber = invoice?.invoiceNumber,
            currency = currency,
        )

    private fun requireContract(id: Long): Contract =
        repository.findById(id)
            ?: throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "Contract $id not found",
            )

    private fun requireShopId(): Long =
        ShopInfo
            .all()
            .firstOrNull()
            ?.id
            ?.value
            ?: throw PosException(PosErrorCode.SHOP_NOT_FOUND, HttpStatusCode.NotFound, "No shop configured")

    private fun resolveSupplier(supplierId: Long?): Supplier? {
        val key = supplierId ?: return null
        return Supplier.findById(key) ?: throw badRequest("Unknown supplierId $key")
    }

    private fun resolveDocument(documentId: Long?): Document? {
        val key = documentId ?: return null
        return Document.findById(key) ?: throw badRequest("Unknown documentId $key")
    }

    /** Stored as the constant's name, so an unknown one must never reach the column. */
    private fun parseInterval(raw: String): ContractInterval =
        ContractInterval.entries.firstOrNull { it.name == raw.trim().uppercase() }
            ?: throw badRequest("interval must be one of ${ContractInterval.entries.joinToString { it.name }}, was '$raw'")

    /** Never `toDouble()`. A blank is the same statement as an absent amount. */
    private fun parseMoney(
        raw: String?,
        field: String,
    ): BigDecimal? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return value.toBigDecimalOrNull() ?: throw badRequest("$field must be a decimal number, was '$raw'")
    }

    /**
     * A blank is the same statement as no identifier recorded. Storing `""` would look like a
     * recorded mandate and make matching signal 3 fire on a direct debit that carries none.
     */
    private fun blankToNull(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** The columns are `datetime`; a contract's dates are days, so they land at midnight. */
    private fun parseDate(
        raw: String,
        field: String,
    ): LocalDateTime {
        val date =
            runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
                ?: throw badRequest("$field must be an ISO date (yyyy-MM-dd), was '$raw'")
        return LocalDateTime(date, LocalTime(0, 0))
    }

    private fun rejectInvalid(fields: List<String>) {
        if (fields.isEmpty()) return
        throw badRequest("Invalid contract: ${fields.joinToString()}")
    }

    private fun badRequest(message: String) = PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, message)
}
