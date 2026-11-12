/**
 * Implementation of [ZBonService].
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from `Result<T, Error>` in Stage B Batch 3.
 *
 * Integrates with TSE for signing Kassenbuch entries created during Z-Bon report
 * generation for German KassenSichV compliance. The TSE module remains
 * Result-based; FAIL-mode branches throw [PosException], QUEUE-mode falls through.
 */
package org.example.project.features.zBon.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.kassenbuch.ZBonBucket
import org.example.project.core.domain.enums.kassenbuch.zBonBucket
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.error.PosErrorCode
import org.example.project.core.mapper.websocket.createTableStatusUpdateDto
import org.example.project.core.websocket.NotificationService
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.cardTerminal.domain.service.CardSettlementService
import org.example.project.features.cardTerminal.domain.service.CardTerminalService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.service.CashBookTransferServiceImpl
import org.example.project.features.kassenbuch.data.service.CashCountDifference
import org.example.project.features.kassenbuch.data.service.ClosingCashCountBooker
import org.example.project.features.kassenbuch.data.service.ClosingTipPayoutBooker
import org.example.project.features.kassenbuch.data.service.ClosingTipPayoutPlan
import org.example.project.features.kassenbuch.data.service.ClosingTopUp
import org.example.project.features.kassenbuch.data.service.KassenbuchTseSigner
import org.example.project.features.kassenbuch.data.service.KassenminusGuard
import org.example.project.features.kassenbuch.database.tables.CashBookTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.repository.CashBookRepository
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.service.FavouriteProductService
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.table.domain.repository.TableSessionRepository
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.features.voucher.database.tables.VoucherRedeemTable
import org.example.project.features.voucher.database.tables.VoucherTable
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.features.zBon.domain.CashScope
import org.example.project.features.zBon.domain.ClosingCashTransfer
import org.example.project.features.zBon.domain.ClosingSigner
import org.example.project.features.zBon.domain.DrawerCashDerivation
import org.example.project.features.zBon.domain.EmployeeTipPayoutLedger
import org.example.project.features.zBon.domain.OpenBusinessCheck
import org.example.project.features.zBon.domain.OpenPeriodTips
import org.example.project.features.zBon.domain.ZBonOwnerTipRule
import org.example.project.features.zBon.domain.ZBonPeriodOverlapException
import org.example.project.features.zBon.domain.ZBonRevenueRule
import org.example.project.features.zBon.domain.models.EmployeeZBonPaymentBreakdown
import org.example.project.features.zBon.domain.models.EmployeeZBonSummary
import org.example.project.features.zBon.domain.models.VatGroup
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.domain.models.ZBonPaymentSummary
import org.example.project.features.zBon.domain.models.ZBonPromotionSummary
import org.example.project.features.zBon.domain.repository.ZBonRepository
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.features.zBon.mapper.toResponseDto
import org.example.project.features.zBon.mapper.toResponseDtos
import org.example.project.features.zBon.mapper.toStatusDto
import org.example.project.features.zBon.mapper.zBonDisplayName
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.TrainingFilter
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.errorHandling.RetryConfig
import org.example.project.utils.errorHandling.withRetryThrows
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.toDecimalString
import org.example.project.utils.formatter.toMoneyMessageString
import org.example.project.utils.formatter.toPercentage
import org.example.project.utils.log.AuditEntityRef
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID
import kotlin.time.ExperimentalTime

class ZBonServiceImpl(
    private val repository: ZBonRepository,
    private val kassenbuchRepository: KassenbuchRepository,
    private val cashBookRepository: CashBookRepository,
    private val cashBookTransferService: CashBookTransferServiceImpl,
    private val tseServiceFactory: TseServiceFactory,
    private val tseSigningQueueService: TseSigningQueueService,
    private val terminalRepository: TerminalRepository,
    private val documents: KassenbuchDocuments,
    private val favouriteProductService: FavouriteProductService,
    private val configManager: IShopConfigurationManager,
    private val cardTerminalService: CardTerminalService,
    private val cardSettlementService: CardSettlementService,
    private val tableSessionRepository: TableSessionRepository,
    /** Names the original bill behind a clearing bill's invoice for the drawer derivation (accounting port). */
    private val invoices: BillInvoiceLink,
) : ZBonService {
    private val logger = LoggerFactory.getLogger(ZBonServiceImpl::class.java)

    /**
     * The one Kassenbuch signing path (FAIL/QUEUE/PENDING decision, status write, Nachsignieren
     * enqueue), shared with KassenbuchServiceImpl and CashBookTransferServiceImpl. The closing
     * used to hand-roll phase 2 for its aggregate and never wrote the header's status — the row
     * sat at PENDING while carrying a signature — and never signed its Geldtransit leg at all.
     */
    private val tseSigner = KassenbuchTseSigner(configManager, tseServiceFactory, tseSigningQueueService)

    /**
     * Books the employee-tip payouts a closing carries (F58) inside the closing's own transaction.
     * Built from collaborators this service already holds, so no constructor call site changes.
     */
    private val closingTipPayoutBooker = ClosingTipPayoutBooker(kassenbuchRepository, documents)

    /** Books the dialog's drawer count inside the closing's own transaction (F58), after the payouts. */
    private val closingCashCountBooker = ClosingCashCountBooker(kassenbuchRepository, documents)

    /** The three sums a closing derives from its Kassenbuch entries. */
    private data class KassenbuchTotals(
        val expenses: java.math.BigDecimal,
        val revenues: java.math.BigDecimal,
        val transfers: java.math.BigDecimal,
    )

    /**
     * The ids of the shop's FISCAL cash books (the Ladenkasse) — the one definition the balance gate,
     * the daily Geldtransit and the sweep share, so they cannot disagree about which books a Z-Bon owns.
     */
    private fun fiscalCashBookIds(): List<Long> =
        CashBookTable
            .select(CashBookTable.id)
            .where { CashBookTable.isFiscal eq true }
            .map { it[CashBookTable.id].value }

    /**
     * The still-unswept entries of the shop's FISCAL books — the set a closing collects.
     *
     * Extracted because `createReport` reads it TWICE: once before creating the daily Geldtransit
     * (to know the takings, since `report.cashRevenue` is not assigned until the sweep) and once
     * as the sweep itself. The second call deliberately runs later and therefore also picks up the
     * legs the first one sized. Two hand-written copies of this predicate would drift apart, which
     * is the failure the R7-D9 comment below spells out.
     *
     * A Tresor is not an elektronisches Aufzeichnungssystem, so its cash is neither this closing's
     * business nor part of the Kassenbestand — it is closed by its own Kassensturz.
     */
    private fun unsweptFiscalHeaders(fiscalBookIds: List<Long>): List<KassenbuchHeader> =
        KassenbuchHeader
            .find {
                (KassenbuchHeaderTable.isLocked eq false) and
                    (KassenbuchHeaderTable.cashBookId inList fiscalBookIds)
            }.toList()

    /**
     * How a closing buckets its entries — the ONE definition, used by both the pre-transfer
     * takings figure and the sweep's reported totals.
     *
     * A Geldtransit is its own GV_TYP under DSFinV-K, NOT an Auszahlung, so it must not land in
     * the reported `expenses`. It still belongs in `closingBalance` — the cash genuinely left the
     * drawer — which is why it gets its own bucket rather than being skipped.
     */
    private fun bucketKassenbuchTotals(headers: List<KassenbuchHeader>): KassenbuchTotals {
        var expenses = java.math.BigDecimal.ZERO
        var revenues = java.math.BigDecimal.ZERO
        var transfers = java.math.BigDecimal.ZERO
        headers.forEach { header ->
            val amount = header.totalAmount
            // Bucketed by the entry TYPE wherever DATEV has an opinion about it, falling back to
            // the sign only for the types it leaves unmapped. Splitting purely on the sign reported
            // a CORRECTION_IN — the storno leg of a correction to a cash expense, and so positive —
            // as cash takings, while DATEV books that same entry on the EXPENSE side. Measured on
            // 2026-09-20, that inflated Z-Bon 139's cash_revenue to 81,74 € against actual takings
            // of 69,40 € (E2E-2026-09-20 #10).
            //
            // The partition stays total and every bucket still feeds closingBalance below, so the
            // closing arithmetic is unchanged for every entry type — only the two REPORTED figures
            // move, which is the whole point.
            when (header.entryType.zBonBucket(isNegative = amount < java.math.BigDecimal.ZERO)) {
                ZBonBucket.REVENUE -> revenues += amount
                ZBonBucket.EXPENSE -> expenses += amount
                ZBonBucket.NEUTRAL -> transfers += amount
            }
        }
        return KassenbuchTotals(expenses, revenues, transfers)
    }

    override suspend fun getAllReports(): List<ZBonDto.ZBonResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getAllReports().toResponseDtos(currency)
        }
    }

    override suspend fun getReportById(
        id: Long,
        withKassenbuchData: Boolean,
    ): ZBonDto.ZBonResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val report = repository.getReportById(id) ?: throw zBonNotFound(id)
            report.toResponseDto(currency, withKassenbuchData, withTipPayouts = true)
        }
    }

    override suspend fun getReportsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
    ): List<ZBonDto.ZBonResponseDto> {
        if (startDate >= endDate) {
            throw validationFailed("Start date must be before end date")
        }
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getReportsByDateRange(startDate, endDate).toResponseDtos(currency)
        }
    }

    override suspend fun getLatestReportByShop(): ZBonDto.ZBonResponseDto? {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getLatestReportByShop()?.toResponseDto(currency)
        }
    }

    override suspend fun getOpenPeriodTips(): List<ZBonDto.OpenPeriodTipDto> =
        dbQuery {
            OpenPeriodTips.current().map { position ->
                ZBonDto.OpenPeriodTipDto(
                    employeeId =
                        position.employee.id.value
                            .toString(),
                    employeeNumber = position.employee.employeeNumber,
                    employeeName = position.employee.zBonDisplayName(),
                    tipTotal = position.total.toDecimalString(),
                    cashTip = position.cash.toDecimalString(),
                    cardTip = position.card.toDecimalString(),
                )
            }
        }

    override suspend fun getClosingReadiness(deviceId: String): ZBonDto.ClosingReadinessDto {
        val mode = configManager.getTseMode()
        val transfer = dailyTransferConfigured(configManager.getCashBookTransferTargetId())
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val shop =
                ShopInfo.all().firstOrNull()
                    ?: throw PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Shop info not found")
            when (val r = ClosingSigner.resolve(terminalRepository, deviceId, mode, cashBookRepository.fiscalBookFor(shop))) {
                is ClosingSigner.Resolution.Resolved ->
                    ZBonDto.ClosingReadinessDto(
                        signerName = r.signerName,
                        dailyTransferConfigured = transfer,
                        currency = currency,
                        tseDisabled = mode == TseMode.DISABLED,
                    )
                is ClosingSigner.Resolution.Unavailable ->
                    ZBonDto.ClosingReadinessDto(
                        signerName = r.signerName,
                        signerProblem = r.problem,
                        dailyTransferConfigured = transfer,
                        currency = currency,
                        tseDisabled = mode == TseMode.DISABLED,
                    )
            }
        }
    }

    /** A daily Geldtransit target is configured and usable — the same test the closing applies before booking one. */
    private suspend fun dailyTransferConfigured(targetId: Long?): Boolean {
        if (targetId == null) return false
        return dbQuery { CashBook.findById(targetId)?.let { it.isActive && !it.isFiscal } ?: false }
    }

    @OptIn(ExperimentalTime::class)
    @Suppress("DefaultLocale")
    override suspend fun createReport(
        employeeId: String,
        deviceId: String,
        countedAmount: String?,
        tipPayouts: List<ZBonDto.CreateTipPayoutRequestDto>,
        heldCapabilities: Set<Capability>,
        topUp: ZBonDto.ClosingTopUpRequestDto?,
    ): ZBonDto.ZBonResponseDto {
        data class ZBonCreationResult(
            val responseDto: ZBonDto.ZBonResponseDto,
            /** The closing's own Kassenbuch entry, which the Z-Bon PDF is linked to after the commit. */
            val zBonKassenbuchHeaderId: Long,
            /** Who signs this closing's movements, and why; null when it has none (the aggregate is never signed). */
            val signer: ClosingSigner.Signer?,
            /** The daily Geldtransit's Ladenkasse leg, when a transfer was booked — signed in phase 2 too. */
            val transferLegSigning: CashBookTransferServiceImpl.LegSigningData?,
            /** F59: the top-up's Ladenkasse leg (CASH_TRANSFER_IN), when one was booked — signed right after the aggregate. */
            val topUpLegSigning: CashBookTransferServiceImpl.LegSigningData?,
            /** F59: the bank/payroll settlements recorded in this closing — nothing to sign, audited only. */
            val tipSettlements: List<ClosingTipPayoutBooker.Settled>,
            /** The employee-tip payouts booked in this closing (F58) — signed in phase 2 under [signer]. */
            val tipPayouts: List<ClosingTipPayoutBooker.Booked>,
            /** The drawer count's Kassendifferenz booked in this closing, or null (no count / exact drawer). */
            val cashCount: ClosingCashCountBooker.Booked?,
        )

        // Resolved once for the whole request, above the retry loop and outside the transaction:
        // getDefaultCurrency() is suspend and reads a 30-minute cache, and the mappers below are
        // plain functions that must not become suspend.
        val currency = configManager.getDefaultCurrency()

        // The signer phase 1 resolved and committed with the closing — set once, right after phase 1,
        // and read only by `auditState` below, which runs after the block has returned successfully.
        var closingSigner: ClosingSigner.Signer? = null

        return ExceptionConverter.catchingThrows(
            context = "createReport(employee=$employeeId)",
            employeeId = employeeId,
            entityType = "Z-report",
            operation = "CREATE_REPORT",
            deviceId = deviceId,
            // The Z-report this closing created. `id` is the `zbon` row's own primary key, from
            // `report.toResponseDto`; `zbonNummer` is the Z-Nr an auditor reads off the printed
            // closing and the DSFinV-K export, so it belongs in the label, not the id - it is
            // „aufsteigend, fortlaufend" per shop, and the primary key is what joins.
            auditEntity = { AuditEntityRef(id = it.id.toString(), name = "Z-Bon ${it.zbonNummer}") },
            // Which till signed the closing's cash movements — the signer phase 1 resolved and phase 2
            // signed under, never a second resolution (spec 2026-10-09 §2).
            auditState = { closingAuditState(it, closingSigner) },
        ) {
            // Read the TSE mode BEFORE opening the transaction, for the same reason the ERP config is
            // read early in KassenbuchServiceImpl.createSignedEntry: on a cache miss `getTseMode()`
            // takes `configMutex` and falls through to its own `dbQuery`, and nesting one transaction in
            // another is forbidden here. Acquiring a mutex while holding a DB connection is also a
            // lock-ordering hazard — and this transaction holds `pg_advisory_xact_lock(1)`, so stalling
            // inside it would block every Z-Bon and Kassenbuch write shop-wide.
            //
            // Hoisting above the retry also means a retried transaction sees ONE consistent mode rather
            // than re-reading mid-flight.
            val tseMode = configManager.getTseMode()

            // F58: the payouts the closing books inside itself, and the drawer count. Shape (400) and
            // authority (403) are checked first — nothing is read or written before they pass.
            val payoutPlan = ClosingTipPayoutPlan.parse(tipPayouts)
            payoutPlan.requireAuthorized(heldCapabilities)
            // F59 option A: a top-up needs cash payouts, so CASHBOOK_WRITE was just required with them
            // — the capability of every other Geldtransit.
            val closingTopUp = ClosingTopUp.parse(topUp, payoutPlan)
            val counted = countedAmount?.let { CashCountDifference.parseCounted(it) }

            // Spec 2026-10-09: which entries of this closing must be TSE-signed is known from the request
            // and the config, before anything is written. The aggregate is not one of them. A configured
            // daily Geldtransit counts even on a day that will move nothing — the conservative choice
            // keeps this decidable up front and equal to what GET /closing-readiness reports.
            //
            // The target is read HERE, outside the closing's transaction, like the TSE mode above: on
            // a cache expiry the config manager refreshes from the database and swallows a failed
            // SELECT, and on Postgres a failed statement aborts the transaction it ran in, so a read
            // inside the closing could doom it. The id is the one the closing books against.
            val configuredTransferTargetId = configManager.getCashBookTransferTargetId()
            val transferConfigured = dailyTransferConfigured(configuredTransferTargetId)
            val needsSigner =
                counted != null || payoutPlan.cashPayouts.isNotEmpty() || closingTopUp != null || transferConfigured

            // Everything else — open tables, open business, the payout limits — is checked INSIDE the
            // closing's transaction below, and the count, the payouts and the closing are all booked
            // in that same transaction (F58). A refusal or a failure anywhere therefore writes nothing:
            // no Kassendifferenz can be left outside a closing (E2E-2026-09-18 #8), and none can
            // assume payouts that were not booked. The count used to be booked first, in its own
            // transaction, behind a read-only precondition check; a table opened between that check
            // and the closing then left the count booked against a refused closing.

            // Cards for item-less sessions the closing ended (validateSessionAndTableStatus). Reassigned by
            // every attempt, so a retried transaction never announces a previous attempt's sessions.
            var endedSessionCards: List<NotificationDto.TableStatusUpdateDto> = emptyList()

            // Phase 1: DB transaction (retryable) — create Z-report, kassenbuch entry (without TSE), collect TSE signing data
            val phase1: ZBonCreationResult =
                withRetryThrows(
                    operationName = "createReport",
                    config = RetryConfig.CONSERVATIVE,
                ) {
                    dbQuery(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
                        logger.debug("ZBonServiceImpl.createReport: creating new Z-report for employee={}", employeeId)

                        // Acquire advisory lock to serialize Z-Bon creation — prevents duplicate reports
                        TransactionManager.current().exec("SELECT pg_advisory_xact_lock(1)")

                        val employee =
                            Employee.findById(UUID.fromString(employeeId))
                                ?: throw PosException(
                                    PosErrorCode.EMPLOYEE_NOT_FOUND,
                                    HttpStatusCode.NotFound,
                                    "Employee not found: $employeeId",
                                )

                        val shopInfo =
                            ShopInfo.all().firstOrNull()
                                ?: throw PosException(
                                    PosErrorCode.RESOURCE_NOT_FOUND,
                                    HttpStatusCode.NotFound,
                                    "Shop info not found",
                                )

                        endedSessionCards = validateSessionAndTableStatus()

                        // Fail fast, before the report row exists: a refusal must not consume a Z-number or
                        // book a difference. Only a closing with cash movements needs a signer.
                        val signer: ClosingSigner.Signer? =
                            if (needsSigner) {
                                ClosingSigner.require(terminalRepository, deviceId, tseMode, cashBookRepository.fiscalBookFor(shopInfo))
                            } else {
                                null
                            }
                        val signingClientId: String? = signer?.clientId

                        // Fail-fast BEFORE creating the report row: a failed attempt must not burn the
                        // zbon_id BIGSERIAL, and must not do the work of building a closing that is
                        // then thrown away. Note Z_NR itself is NOT at risk — zbonNummer is computed
                        // as max(zbon_nummer) + 1 inside this transaction (ZBonRepositoryImpl), so a
                        // rolled-back attempt recomputes the same number; „aufsteigend, fortlaufend"
                        // (audit H5) is preserved by that computation, not by this gate.
                        // The authoritative sweep below still re-checks after createReport.
                        // Shared with ShopInfoServiceImpl's Tz. 3.2 closing duty — one predicate, two
                        // polarities. See OpenBusinessCheck.
                        if (!OpenBusinessCheck.hasOpenBusiness(CashScope.FISCAL_ONLY)) {
                            logger.warn("ZBonServiceImpl.createReport: nothing open to close for Z-report")
                            throw PosException(
                                PosErrorCode.ZBON_NOTHING_TO_CLOSE,
                                HttpStatusCode.BadRequest,
                                "No open business to close for Z-report",
                            )
                        }

                        val report =
                            try {
                                repository.createReport(employee = employee, shopInfo = shopInfo)
                            } catch (e: ZBonPeriodOverlapException) {
                                // NEW-25: the repository's overlap guard is a business refusal, not a
                                // crash. Nothing was written yet, so the transaction rolls back clean.
                                throw PosException(
                                    PosErrorCode.ZBON_PERIOD_OVERLAP,
                                    HttpStatusCode.Conflict,
                                    e.message ?: "Z-report already exists for overlapping period",
                                )
                            }
                        logger.info("ZBonServiceImpl.createReport: created new Z-report with id={}", report.id.value)

                        // Validate kassenbuch balance consistency early (fail-fast before expensive processing).
                        //
                        // Selected by id ALONE — deliberately no `createdAt less report.startTime` window.
                        // `currentBalance` is a running balance carried forward in id order, so an
                        // independent ordering by `createdAt` only has to disagree ONCE to read the wrong
                        // row, and it does, systematically: the previous closing's own aggregate entry is
                        // written inside that closing, and V78 then realigns its `created_at` to the
                        // TSE-attested signature time — which is stamped in phase 2, AFTER phase 1 set
                        // `endTime`. Since the next period's `startTime` IS that `endTime`
                        // (ZBonRepositoryImpl), the aggregate entry falls on the far side of a strict
                        // `less` and the gate read the second-to-last entry, refusing every closing with a
                        // "Kassenbuch balance mismatch" the operator cannot clear.
                        //
                        // No window is needed to be correct: this runs BEFORE the closing writes any entry
                        // of its own, and `isLocked` already means "swept by a previous closing", so the
                        // highest-id locked entry IS the last entry of the previous period. Entries booked
                        // since then are unlocked and rightly excluded — they are what this closing sweeps.
                        //
                        // FISCAL books only (T9-1). `currentBalance` runs PER cash book, and `isLocked`
                        // is not the Z-Bon's alone: a Tresor's own Kassensturz (CashBookCloseServiceImpl)
                        // locks the Tresor's entries too. Across all books, the highest-id locked entry
                        // after a Tresor count is that Tresor's last entry, so the gate compared the
                        // Tresor's balance with the Ladenkasse's closing balance and refused every
                        // Z-Bon from then on — the operator could not clear it, because only a Z-Bon
                        // locks fiscal entries. `closingBalance` is the fiscal book's balance (the sweep
                        // below collects fiscal entries only), so the comparison must read the fiscal
                        // book's running balance: the same `fiscalCashBookIds()` set the sweep uses.
                        val lastKassenEntry =
                            KassenbuchHeader
                                .find {
                                    (KassenbuchHeaderTable.isLocked eq true) and
                                        (KassenbuchHeaderTable.cashBookId inList fiscalCashBookIds())
                                }.orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                                .firstOrNull()

                        val currentKassenBalance = lastKassenEntry?.currentBalance ?: BigDecimal.ZERO
                        val lastClosingBalance =
                            ZBon
                                .find { ZBonTable.zbonNummer eq report.zbonNummer - 1L }
                                .firstOrNull()
                                ?.closingBalance ?: BigDecimal.ZERO

                        if (currentKassenBalance.compareTo(lastClosingBalance) != 0) {
                            throw validationFailed(
                                "Kassenbuch balance mismatch - last closing balance: ${lastClosingBalance.toMoneyMessageString()}, " +
                                    "current kassenbuch balance: ${currentKassenBalance.toMoneyMessageString()}",
                            )
                        }

                        // Find all bills after report start time without zBonId (including training)
                        val allBills =
                            Bill
                                .find {
                                    (BillTable.zBonId eq null) and (BillTable.startTime greater report.startTime)
                                }.toList()

                        // Deliberately NOT rejected when empty (round-7 C9). A day with Kassenbuch
                        // movement but no sales — a Privateinlage, a Barausgabe, a Geldtransit — is
                        // business that must be closable: the widened OpenBusinessCheck now makes such
                        // an entry block a fiscal Stammdaten edit, so a closing that refused to sweep
                        // it would leave the operator with no way out of the Tz. 3.2 closing duty.
                        //
                        // Rejecting HERE was the wrong PLACE regardless: "nothing to close" is one
                        // decision and belongs in one gate, before the report row is built, not split
                        // across two predicates that disagree about what business is.
                        //
                        // It did NOT, however, burn a Z_NR, contrary to what this comment claimed
                        // before review: zbonNummer is not a sequence — ZBonRepositoryImpl computes
                        // max(zbon_nummer) + 1 inside the transaction, so a rolled-back refusal
                        // recomputes the identical number. What rolls forward is the zbon_id BIGSERIAL,
                        // which is not the fiscal field. The fail-fast gate above is still worth having
                        // for the reason stated there; it just isn't holding back a Z_NR.
                        if (allBills.isEmpty()) {
                            logger.info("ZBonServiceImpl.createReport: no bills in period — closing Kassenbuch movement only")
                        }

                        val (trainingBills, realBills) = allBills.partition { it.isTraining }

                        allBills.forEach { it.zBon = report }

                        report.trainingRevenue = trainingBills.sumOf { it.billAmount - it.promotionAmount }
                        report.trainingBillCount = trainingBills.size

                        val activeBills = DrawerCashDerivation.activeBills(realBills)
                        // 4c-2c: a clearing Bill takes money for a sale ALREADY booked, so it is not
                        // revenue and the drawer derivation cannot express it (netCashAmount removes the
                        // non-cash part OF REVENUE, and a clearing contributes none). Split it out here and
                        // handle it explicitly below: exclude it from revenue, add only its cash leg to the
                        // drawer, and record its gross separately as totalReceivableCleared.
                        val clearingBills = DrawerCashDerivation.clearingBills(activeBills)
                        val saleBills = DrawerCashDerivation.saleBills(activeBills)

                        val stornoBills = realBills.filter { it.deletedBill != null }
                        val cancelledOriginals = realBills.filter { it.deletingReason != null && it.deletedBill == null }
                        report.refundAmount = stornoBills.sumOf { it.paidAmount }.negate()
                        report.stornoBillCount = stornoBills.size
                        // "davon Zahlartwechsel": the Stornos of a Storno + Neubuchung stay in the fiscal
                        // refund figure above and are broken out here, because they refund nothing.
                        val rebookStornos = stornoBills.filter { it.stornoReason == StornoReason.ZAHLARTWECHSEL }
                        report.rebookStornoCount = rebookStornos.size
                        report.rebookStornoAmount = rebookStornos.sumOf { it.paidAmount }.negate()

                        if (cancelledOriginals.size != stornoBills.size) {
                            logger.warn(
                                "ZBonServiceImpl.createReport: cancelled originals count ({}) != storno bills count ({}), possible data inconsistency",
                                cancelledOriginals.size,
                                stornoBills.size,
                            )
                        }

                        // Batch-fetch per-bill children ONCE (vat details, payments) instead of one
                        // lazy query per bill per usage. Fiscal equivalence: bills are iterated in the
                        // same encounter order as before, per-bill rows are ordered by id (matching the
                        // insertion order the per-bill lazy loads returned), grouping keys and BigDecimal
                        // arithmetic are unchanged.
                        val activeBillIds = activeBills.map { it.id }
                        val vatDetailsByBill = DrawerCashDerivation.vatDetailsByBill(activeBills)
                        val paymentsByBill = DrawerCashDerivation.paymentsByBill(activeBills)

                        // F58 — the BINDING payout check: under the advisory lock, against the very
                        // bills whose employee summaries this closing writes below. A refusal here rolls
                        // back the whole closing; the up-front check makes it unreachable short of a race.
                        payoutPlan.requireWithin(OpenPeriodTips.of(activeBills, paymentsByBill))

                        // Pfand split (spec 2026-07-09 §2.1): billed items of the SAME activeBills the
                        // vatGroups derive from (storno-consistent by exclusion). Batch-fetch + warm the
                        // product cache to avoid N+1 (matches the vatDetails/payments batch above).
                        val billedItems =
                            if (activeBillIds.isEmpty()) {
                                emptyList()
                            } else {
                                OrderItem.find { OrderItemTable.billId inList activeBillIds }.toList()
                            }
                        if (billedItems.isNotEmpty()) {
                            Product
                                .forEntityIds(
                                    billedItems.map { it.readValues[OrderItemTable.productId] }.distinct(),
                                ).toList()
                        }
                        val pfandByRate = ZBonPfandAggregator.aggregate(billedItems)

                        val vatMap = activeBills.flatMap { vatDetailsByBill[it.id].orEmpty() }.groupBy { it.vat }
                        val taxGroups = mutableMapOf<Vat, Map<String, BigDecimal>>()

                        vatMap.forEach { (vat, vatDetails) ->
                            taxGroups[vat] =
                                mapOf(
                                    "net" to vatDetails.sumOf { it.netAmount },
                                    "vat" to vatDetails.sumOf { it.vatAmount },
                                    "total" to vatDetails.sumOf { it.totalAmount },
                                )
                        }

                        taxGroups.forEach { (vat, vatDetails) ->
                            VatGroup.new {
                                this.vat = vat
                                this.netAmount = vatDetails["net"] ?: BigDecimal.ZERO
                                this.vatAmount = vatDetails["vat"] ?: BigDecimal.ZERO
                                this.grossAmount = vatDetails["total"] ?: BigDecimal.ZERO
                                this.zBon = report
                            }
                        }

                        val paymentCurrencyGroup = activeBills.flatMap { paymentsByBill[it.id].orEmpty() }.groupBy { it.currency }
                        val paymentGroup = mutableMapOf<String, MutableMap<PaymentType, BigDecimal>>()

                        paymentCurrencyGroup.forEach { (currency, payments) ->
                            val currencyPayments = paymentGroup.getOrPut(currency) { mutableMapOf() }
                            payments.groupBy { it.paymentType }.forEach { (paymentType, paymentList) ->
                                currencyPayments[paymentType] = (currencyPayments[paymentType] ?: BigDecimal.ZERO) +
                                    paymentList.sumOf { it.amount }
                            }
                        }

                        paymentGroup.forEach { (currency, paymentDetails) ->
                            paymentDetails.forEach { (paymentType, amount) ->
                                ZBonPaymentSummary.new {
                                    this.zBon = report
                                    this.currency = currency
                                    this.paymentType = paymentType
                                    this.amount = amount
                                }
                            }
                        }

                        val billsByEmployee = activeBills.groupBy { it.employeeId }
                        // By the bills' stored tipRecipient, not the closer's current role, so the
                        // owner tip agrees with the VAT rows and DSFinV-K after a role change.
                        val adminTipTotal = ZBonOwnerTipRule.ownerTips(activeBills)

                        billsByEmployee.forEach { (emp, employeeBills) ->
                            // Employee tips only: the owner tip is carried by adminTip alone.
                            val tipSum = ZBonOwnerTipRule.employeeTips(employeeBills)
                            // Sale bills only, like totalPosRevenue below: a receivable the employee
                            // collected is not their turnover, so the rows (+ the owner tip) add up
                            // to the Umsatz. Its payment stays in the breakdown — the money came in.
                            val revenueSum = ZBonRevenueRule.saleRevenue(DrawerCashDerivation.saleBills(employeeBills))

                            val employeeSummary =
                                EmployeeZBonSummary.new {
                                    this.zBon = report
                                    this.employee = emp
                                    this.tipAmount = tipSum
                                    this.totalRevenue = revenueSum
                                    this.createdAt = currentTimeStamp()
                                }

                            employeeBills
                                .flatMap { paymentsByBill[it.id].orEmpty() }
                                .groupBy { Pair(it.paymentType, it.currency) }
                                .forEach { (key, payments) ->
                                    EmployeeZBonPaymentBreakdown.new {
                                        this.employeeZBonSummary = employeeSummary
                                        this.paymentType = key.first
                                        this.currency = key.second
                                        this.amount = payments.sumOf { it.amount }
                                    }
                                }
                        }

                        // saleBills, not activeBills: a clearing's billAmount is not turnover (§6).
                        val totalRevenue = ZBonRevenueRule.saleRevenue(saleBills)
                        report.totalReceivableCleared = clearingBills.sumOf { it.billAmount }
                        report.totalPosRevenue = totalRevenue + adminTipTotal
                        report.adminTip = adminTipTotal
                        report.totalDiscounts = activeBills.sumOf { it.discountAmount + it.promotionAmount }

                        // Per-promotion breakdown: count + total per promotion granted in this closing.
                        // Scoped to the same bills as the revenue (DrawerCashDerivation.activeBills): a
                        // stornoed original keeps its bill_promotion row while its Storno carries none,
                        // so counting every bill of the closing reported a reversed sale's discount as
                        // granted — and a Zahlartwechsel, whose Neubuchung re-applies it, twice.
                        val promotionTotals =
                            BillPromotionTable
                                .innerJoin(PromotionTable, { BillPromotionTable.promotionId }, { PromotionTable.id })
                                .innerJoin(BillTable, { BillPromotionTable.billId }, { BillTable.id })
                                .select(
                                    PromotionTable.id,
                                    PromotionTable.name,
                                    PromotionTable.discountType,
                                    BillPromotionTable.id.count(),
                                    BillPromotionTable.deductedAmount.sum(),
                                ).where {
                                    (BillTable.zBonId eq report.id) and
                                        (BillTable.isTraining eq false) and
                                        BillTable.deletingReason.isNull() and
                                        BillTable.deletedBillId.isNull()
                                }.groupBy(PromotionTable.id, PromotionTable.name, PromotionTable.discountType)
                                .toList()

                        var totalPromotionDeducted = java.math.BigDecimal.ZERO
                        promotionTotals.forEach { row ->
                            val deducted = row[BillPromotionTable.deductedAmount.sum()] ?: java.math.BigDecimal.ZERO
                            ZBonPromotionSummary.new {
                                this.zBon = report
                                this.promotionId = row[PromotionTable.id].value
                                this.promotionName = row[PromotionTable.name]
                                this.discountType = row[PromotionTable.discountType].name
                                this.timesApplied = row[BillPromotionTable.id.count()].toInt()
                                this.totalDeducted = deducted
                                this.createdAt = currentTimeStamp()
                            }
                            totalPromotionDeducted += deducted
                        }
                        report.totalPromotions = totalPromotionDeducted

                        val allCancellations =
                            Cancellation
                                .find {
                                    (CancellationTable.zBonId eq null) and (CancellationTable.createdAt greater report.startTime)
                                }.toList()

                        allCancellations.forEach { it.zBon = report }

                        // Claim every still-unlinked order up to this closing's window end — including
                        // ones OLDER than its start, which is the point: an order left in a gap because
                        // a previous closing never ran would otherwise stay unattributed and vanish from
                        // the export (it is inside the export's overall window but covered by no
                        // closing). The next closing that runs adopts it, exactly as a late bill lands
                        // in the closing that finds it, so a TSE-signed AVBestellung is never lost.
                        val unlinkedOrders =
                            Orders
                                .find {
                                    (OrdersTable.zBonId eq null) and (OrdersTable.orderTime lessEq report.endTime)
                                }.toList()
                        unlinkedOrders.forEach { it.zBon = report }
                        if (unlinkedOrders.isNotEmpty()) {
                            logger.info(
                                "ZBonServiceImpl.createReport: attached {} order(s) to closing {}",
                                unlinkedOrders.size,
                                report.zbonNummer,
                            )
                        }
                        val realCancellations = allCancellations.filter { !it.isTraining }
                        // Warm the OrderItem entity cache in ONE query so the sumOf below resolves
                        // each cancellation.orderItem from cache instead of one SELECT per row.
                        // Same entities, same values — the sum is unchanged.
                        if (realCancellations.isNotEmpty()) {
                            OrderItem
                                .forEntityIds(
                                    realCancellations.map { it.readValues[CancellationTable.orderItemId] }.distinct(),
                                ).toList()
                        }
                        report.cancelledAmount = realCancellations.sumOf { it.orderItem.finalPrice }

                        val vouchers =
                            Voucher.find {
                                (VoucherTable.zBonId eq null) and
                                    (VoucherTable.createdAt greater report.startTime) and
                                    (VoucherTable.createdAt less report.endTime)
                            }
                        vouchers.forEach { it.zBon = report }

                        // Reversed redemptions (storno / payment change) are excluded: their money did not
                        // stay redeemed, and the rest of the report already excludes storno'd bills. They
                        // keep zBonId = null permanently — the redeemedAt window never matches a later report.
                        VoucherRedeem
                            .find {
                                VoucherRedeemTable.zBonId.isNull() and
                                    VoucherRedeemTable.standing() and
                                    (VoucherRedeemTable.redeemedAt greater report.startTime) and
                                    (VoucherRedeemTable.redeemedAt less report.endTime)
                            }.forEach { it.zBon = report }

                        report.openingBalance = lastClosingBalance

                        // Create ONE kassenbuch entry for the entire Z-Bon WITHOUT TSE signing.
                        // TSE signing happens in Phase 2 after the DB transaction commits.
                        val now = currentTimeStamp()
                        val belegDatum = String.format("%02d.%02d.%04d", now.day, now.month.number, now.year)

                        // The drawer derivation is SHARED with the pre-closing cash count
                        // (KassenbuchServiceImpl.recordCashCount compares the drawer against
                        // book + these takings), so it is one definition, not a copy: 4c-2c's
                        // clearing rules — non-cash tender over SALE bills only, a cash clearing
                        // added back — live in DrawerCashDerivation.
                        val takings = DrawerCashDerivation.derive(activeBills, vatDetailsByBill, paymentsByBill, invoices::originalBillIdOfClearing)
                        val saleNonCashByType = takings.saleNonCashByType
                        val totalNonCashWithdrawals = takings.totalNonCashWithdrawals
                        val clearingCashByReceivableTender = takings.clearingCashByReceivableTender
                        val nonTaxableTips = takings.nonTaxableTips
                        val netCashAmount = takings.netCashAmount

                        // The aggregate entry lands in the fiscal book, so its number must come
                        // from that book's series — resolved once and reused below.
                        val fiscalBookId = cashBookRepository.fiscalBookFor(shopInfo).id.value
                        val zBonBelegNr = kassenbuchRepository.getNextBelegNr(shopInfo, fiscalBookId)

                        val zBonHeaderData =
                            KassenbuchDto.KassenbuchData(
                                entryType = KassenbuchEntryType.CASH_SALE,
                                belegNr = zBonBelegNr,
                                vorzBetrag = netCashAmount.toPlainString(),
                                belegDatum = belegDatum,
                                belegtext = "ZBon Nr. ${report.zbonNummer}",
                                isZbonAggregate = true,
                                cashBookId = fiscalBookId,
                            )

                        val allLineItems = mutableListOf<KassenbuchDto.LineItemData>()

                        report.vatGroups.forEach { vat ->
                            val rate = vat.vat.vatRate.toPercentage()
                            val pfand =
                                pfandByRate[rate]
                                    ?: ZBonPfandAggregator.PfandTotals(BigDecimal.ZERO, BigDecimal.ZERO)
                            // returned is ≤ 0, so subtracting it adds the deposit-return back out
                            val standardAmount = vat.grossAmount - pfand.deposit - pfand.returned
                            if (standardAmount.compareTo(BigDecimal.ZERO) != 0) {
                                allLineItems.add(
                                    KassenbuchDto.LineItemData(
                                        vorzBetrag = standardAmount.toPlainString(),
                                        ustSatz = rate,
                                        belegtext = "TE$rate%",
                                        kind = KassenbuchLineKind.STANDARD,
                                    ),
                                )
                            }
                            if (pfand.deposit.compareTo(BigDecimal.ZERO) != 0) {
                                allLineItems.add(
                                    KassenbuchDto.LineItemData(
                                        vorzBetrag = pfand.deposit.toPlainString(),
                                        ustSatz = rate,
                                        belegtext = "TEPfand$rate%",
                                        kind = KassenbuchLineKind.PFAND,
                                    ),
                                )
                            }
                            if (pfand.returned.compareTo(BigDecimal.ZERO) != 0) {
                                allLineItems.add(
                                    KassenbuchDto.LineItemData(
                                        vorzBetrag = pfand.returned.toPlainString(),
                                        ustSatz = rate,
                                        belegtext = "TELeergut$rate%",
                                        kind = KassenbuchLineKind.PFAND,
                                    ),
                                )
                            }
                        }

                        // P1.3: an EMPLOYEE tip (TrinkgeldAN) is non-taxable and is NOT turnover, so P1.1
                        // stopped storing it as a 0 % BillVatDetail row. It therefore no longer reaches
                        // `report.vatGroups` and no longer rides out of the loop above as a "TE0%" line —
                        // but the cash IS in the drawer and netCashAmount now carries it, so it needs a
                        // line of its own or the aggregate's lines stop summing to its own header.
                        //
                        // `ustSatz = "0"` is DELIBERATE and must stay (decided 2026-09-16, plan task
                        // P1.3a; kept 2026-10 when the kind below was introduced). It no longer decides
                        // anything about ACCOUNTS — see the next paragraph — it exists for the SIGNED
                        // bytes, explained under SIGNING below.
                        //
                        // ACCOUNTS (2026-10, F51): the line is kind EMPLOYEE_TIP. Before, it was a
                        // STANDARD line at rate "0" and DATEV booked it through income[STANDARD]["0"] —
                        // 8100 (SKR03) / 4100 (SKR04), "steuerfreie Umsätze", a REVENUE account, for
                        // money that is not turnover. EMPLOYEE_TIP resolves through its own special
                        // slot instead (KassenbuchLineKind.specialBooking -> mapping.special
                        // [EMPLOYEE_TIP], default 1590 / 1370 Durchlaufende Posten), which takes
                        // precedence over the rate axis, so the "0" is never read for the account.
                        // The slot is seeded for both charts and backfilled on read into mappings
                        // stored before it existed (DatevAccountMappingDefaults.withBackfilledDefaults),
                        // so the old failure mode the P1.3a note warned about — a rate-less tip line
                        // falling to Resolution.Legacy and 409-ing the whole month — cannot occur on a
                        // configured shop. The tip payout after the closing books the opposite leg on
                        // the same slot. DatevBuchungsstapelEmployeeTipTest is the standing gate.
                        //
                        // The amount is cash AND card employee tips: a card tip is inside the card
                        // payment the NON_CASH_PAYMENT line nets out below, so it reaches the drawer
                        // as cash and is paid out from it. This aggregate is excluded from the DSFinV-K
                        // export (`!isZbonAggregate`), so the CSV is unaffected; were it ever exported,
                        // KassenbuchDsfinvkMapping maps the kind to TrinkgeldAN (key 5).
                        //
                        // SIGNING — none: this aggregate is no longer TSE-signed (spec 2026-10-09), so the
                        // rate "0" below affects only the line model and DATEV, not any signed bytes.
                        //
                        // The Forderungsausgleich lines below are rate-less BY DESIGN and are therefore
                        // absent from `umst`; they are pinned non-taxable by their own GV_TYP
                        // (FORDERUNGSAUFLOESUNG) wherever a GV_TYP-aware path reads them, and they
                        // resolve their DATEV account on the payment-type axis instead of the rate one.
                        // Different mechanism — do not reason from one of these two lines to the other:
                        // giving a clearing line `ustSatz = "0"` like the tip line above would re-book
                        // revenue the invoice already booked AND change this aggregate's signed bytes.
                        if (nonTaxableTips.compareTo(BigDecimal.ZERO) != 0) {
                            allLineItems.add(
                                KassenbuchDto.LineItemData(
                                    vorzBetrag = nonTaxableTips.toPlainString(),
                                    ustSatz = "0",
                                    belegtext = "TE:Trinkgeld (AN)",
                                    kind = KassenbuchLineKind.EMPLOYEE_TIP,
                                ),
                            )
                        }

                        saleNonCashByType.forEach { (paymentType, amount) ->
                            allLineItems.add(
                                KassenbuchDto.LineItemData(
                                    vorzBetrag = amount.negate().toPlainString(),
                                    belegtext = "TE:Unbare Zahlung: ${paymentType.name}",
                                    kind = KassenbuchLineKind.NON_CASH_PAYMENT,
                                    paymentTypeId = paymentType.id.value,
                                ),
                            )
                        }

                        // 4c-2c: the +clearingCash added to netCashAmount above needs its own line so the
                        // aggregate's lines still sum to the header. A Forderungsausgleich is non-taxable
                        // (key 5) — KassenbuchDsfinvkMapping routes RECEIVABLE_SETTLEMENT there.
                        //
                        // ONE LINE PER RECEIVABLE TENDER, each carrying that tender's paymentTypeId.
                        // The line is rate-less, so KassenbuchAccountResolver resolves it on the
                        // payment-type axis — through the SAME mapping.paymentTypes cell the invoice's
                        // opening NON_CASH_PAYMENT leg booked through, which is why no new mapping cell,
                        // seeded default or settings backfill is needed. Flattening the day's clearings
                        // into one paymentTypeId-less line is what made EVERY cash clearing resolve to
                        // Resolution.Legacy and 409 the whole period's Buchungsstapel (and the Belege
                        // ZIP), on a shop whose mapping was fully configured.
                        //
                        // The aggregate is not signed (spec 2026-10-09), so the split cannot move any
                        // signed payload; netCashAmount is a function of the SUMMED clearing cash, which
                        // the split leaves unchanged.
                        clearingCashByReceivableTender.forEach { (receivableTender, amount) ->
                            if (amount.compareTo(BigDecimal.ZERO) == 0) return@forEach
                            allLineItems.add(
                                KassenbuchDto.LineItemData(
                                    vorzBetrag = amount.toPlainString(),
                                    belegtext =
                                        receivableTender
                                            ?.let { "TE:Forderungsausgleich (bar): ${it.name}" }
                                            ?: "TE:Forderungsausgleich (bar)",
                                    kind = KassenbuchLineKind.RECEIVABLE_SETTLEMENT,
                                    paymentTypeId = receivableTender?.id?.value,
                                ),
                            )
                        }

                        val zBonKassenBuchEntry =
                            kassenbuchRepository.createEntry(
                                shop = shopInfo,
                                employee = employee,
                                headerData = zBonHeaderData,
                                lineItemsData = allLineItems,
                            )
                        zBonKassenBuchEntry.zBon = report
                        // Not a Geschäftsvorfall: it restates sales each bill already signed (AEAO zu § 146a
                        // Nr. 1.11.2; spec 2026-10-09). The row stays — the running balance needs it — but it
                        // creates no TSE transaction. It never reaches DSFinV-K (`!isZbonAggregate`).
                        zBonKassenBuchEntry.tseSigningStatus = TseSigningStatus.NOT_APPLICABLE

                        // F58: the employee-tip payouts handed out in this closing. Booked HERE — after the
                        // aggregate whose EMPLOYEE_TIP line credits the tips to the Durchlaufende-Posten
                        // account, before the Geldtransit and the sweep — so each is an unlocked fiscal
                        // entry the sweep below locks into THIS closing, and the TSE signs them in booking
                        // order: aggregate, payouts, Geldtransit leg. Each one is a CASH_WITHDRAWAL,
                        // bucketed as an expense, so it lowers closingBalance but not the transfer amount
                        // (which sums revenues only). Same transaction as the closing: they commit with
                        // it or not at all (ClosingTipPayoutBooker).
                        if (!payoutPlan.isEmpty && allLineItems.none { it.kind == KassenbuchLineKind.EMPLOYEE_TIP }) {
                            throw PosException(
                                PosErrorCode.ZBON_TIP_PAYOUT_NOT_AVAILABLE,
                                HttpStatusCode.Conflict,
                                "Z-Bon ${report.zbonNummer} books no employee tip, so none can be paid out in it",
                            )
                        }
                        val fiscalBook = cashBookRepository.fiscalBookFor(shopInfo)

                        // F59 option A: the top-up from another book, booked BEFORE the payouts it covers,
                        // through the ordinary transfer legs and their Kassenminus guard on the source.
                        val topUpLegs =
                            closingTopUp?.book(
                                transferService = cashBookTransferService,
                                fiscalBook = fiscalBook,
                                operator = employee,
                                belegDatum = belegDatum,
                                zbonNummer = report.zbonNummer,
                            )

                        // F59 — the Kassenminus guard for the cash payouts, under the closing's lock. The
                        // aggregate and the top-up are booked, so the fiscal book's balance IS the drawer:
                        // the takings are already in it (BOOKED_BY_CLOSING). Refused → nothing is written.
                        KassenminusGuard.requireCovered(
                            kassenbuchRepository,
                            fiscalBook,
                            payoutPlan.cashTotal,
                            KassenminusGuard.Takings.BOOKED_BY_CLOSING,
                        )

                        val payoutClosing =
                            ClosingTipPayoutBooker.Closing(
                                report = report,
                                shop = shopInfo,
                                operator = employee,
                                fiscalBookId = fiscalBookId,
                                belegDatum = belegDatum,
                            )
                        val bookedTipPayouts = closingTipPayoutBooker.book(payoutPlan, payoutClosing)
                        // F59 option C: bank/payroll settlements — rows only, no Kassenbuch entry, no cash.
                        val recordedSettlements = closingTipPayoutBooker.recordSettlements(payoutPlan, payoutClosing)

                        // F58: the drawer count of the closing dialog, booked HERE — after the aggregate and
                        // the payouts, so the fiscal book's balance at this point IS the drawer the
                        // operator counted (book + takings − tips handed out), and before the Geldtransit
                        // and the sweep, so its difference is swept into THIS closing (and, a surplus
                        // being revenue, travels to the safe as the count always did). Same transaction:
                        // a refused closing writes no difference.
                        val bookedCashCount =
                            counted?.let {
                                closingCashCountBooker.book(
                                    ClosingCashCountBooker.Count(
                                        counted = it,
                                        takings = netCashAmount,
                                        tipPayouts = payoutPlan.cashTotal,
                                        topUp = closingTopUp?.amount ?: BigDecimal.ZERO,
                                    ),
                                    ClosingTipPayoutBooker.Closing(
                                        report = report,
                                        shop = shopInfo,
                                        operator = employee,
                                        fiscalBookId = fiscalBookId,
                                        belegDatum = belegDatum,
                                    ),
                                )
                            }

                        // The daily Geldtransit (Task 4, 2026-08-08-zbon-auto-transfer.md): today's
                        // takings move to the safe as part of the closing, when a transfer target is
                        // configured. Booked HERE — after the aggregate CASH_SALE entry above, so it
                        // is already among the fiscal book's unlocked rows, and BEFORE the untouched
                        // sweep below, so that SAME sweep locks the fiscal (Ladenkasse) leg, attributes
                        // it to this `report`, and buckets it into `transfers` rather than
                        // `revenues`/`expenses` (ZBonServiceImpl's own A2 fix, above this block in the
                        // file history). A leg created AFTER the sweep would be stranded
                        // is_locked=false forever — under CashScope.ALL_BOOKS that permanently blocks
                        // Stammdaten edits, with no unlock/delete/repair path (see
                        // ZBonCreateReportStrandedEntryTest for the general shape of that failure).
                        //
                        // The transfer amount must be the closing's cash revenue, but `report.cashRevenue`
                        // itself is not assigned until the sweep runs below (it is one of the sweep's
                        // own outputs — the CASH_SALE entry just created is itself one of the rows that
                        // sweep sums). It is nonetheless DETERMINED at this point: this transaction holds
                        // pg_advisory_xact_lock(1) under REPEATABLE_READ, so no concurrent writer can add
                        // another unlocked fiscal entry, and the only rows this block is about to insert
                        // are the two transfer legs — which the sweep's bucketing below routes to
                        // `transfers`, never to `revenues`. So recomputing the sweep's revenue predicate
                        // HERE, before creating the legs, yields exactly the value `report.cashRevenue`
                        // will hold once the untouched sweep assigns it below.
                        //
                        // Built ONCE here and reused by the transfer amount, the sweep, and the
                        // sweep's post-condition — see the R7-D9 comment further down: those two
                        // predicates must remain the SAME set, or a Tresor-shaped gap between them
                        // strands an entry and wedges every future closing. Hoisted above the
                        // transfer so all three share one list rather than three lookups that can
                        // drift. A transfer leg does not change which books are fiscal, so
                        // computing it before the legs exist is safe.
                        val fiscalBookIds = fiscalCashBookIds()

                        // Kept for phase 2: the Ladenkasse leg is a fiscal entry the closing must sign.
                        var dailyTransferLegs: CashBookTransferServiceImpl.TransferLegs? = null
                        // Gated on the SAME up-front decision that sized needsSigner: a target that became
                        // usable after it was computed must not be booked unsigned.
                        val transferTargetId = if (transferConfigured) configuredTransferTargetId else null
                        if (transferTargetId != null) {
                            val targetBook = CashBook.findById(transferTargetId)
                            // isActive: a deactivated book must not receive cash. !isFiscal: a
                            // mis-set key must not make the Ladenkasse transfer to itself.
                            if (targetBook != null && targetBook.isActive && !targetBook.isFiscal) {
                                // The SAME query and the SAME bucketing the sweep uses below —
                                // one definition, not a copy. The amount cannot simply read
                                // report.cashRevenue: that is assigned inside the sweep, which must
                                // run AFTER these legs exist, so at this point it is still unset.
                                // Sharing the helpers is what keeps this equal to what the sweep
                                // will compute; two hand-written copies would drift the first time
                                // the bucketing changes, exactly as it did when a Geldtransit
                                // stopped counting as an expense.
                                val pendingCashRevenue =
                                    bucketKassenbuchTotals(unsweptFiscalHeaders(fiscalBookIds)).revenues
                                // F58: less the tips paid out in this closing — that cash left with the
                                // staff, so the drawer ends at its float. One rule: ClosingCashTransfer.
                                // F59: plus the top-up (a NEUTRAL transfer, so not in the revenues), and
                                // never more than the drawer holds.
                                val transferAmount =
                                    ClosingCashTransfer.amount(
                                        ClosingCashTransfer.Inputs(
                                            cashTakings = pendingCashRevenue,
                                            cashTipPayouts = payoutPlan.cashTotal,
                                            topUp = closingTopUp?.amount ?: BigDecimal.ZERO,
                                            drawerBalance = kassenbuchRepository.getCurrentBalance(shopInfo, fiscalBook.id.value),
                                        ),
                                    )

                                // A zero-takings day books nothing rather than a 0,00 EUR transfer.
                                if (transferAmount.signum() > 0) {
                                    // The Z-Bon PDF (this closing's natural Beleg for the transfer) is
                                    // only generated further below, AFTER the sweep — generating it
                                    // here would require reordering the closing around the transfer,
                                    // which constraint (2) above forbids. Both legs fall back to a
                                    // generated Eigenbeleg instead (documentId = null); reported as a
                                    // deviation from the plan's Step 3 illustration.
                                    dailyTransferLegs =
                                        cashBookTransferService.createTransferLegsInTransaction(
                                            fromBook = fiscalBook,
                                            toBook = targetBook,
                                            amount = transferAmount,
                                            belegDatum = belegDatum,
                                            employee = employee,
                                            transferId = UUID.randomUUID().toString(),
                                            note = "Tageseinnahmen Z-Bon ${report.zbonNummer}",
                                            documentIdForFromLeg = null,
                                            documentIdForToLeg = null,
                                            belegtextForToLeg = "Tageseinnahmen",
                                            takings = KassenminusGuard.Takings.BOOKED_BY_CLOSING,
                                        )
                                }
                            }
                        }

                        // Sweep EVERY still-unlocked entry — the SAME set the post-condition below
                        // guards — not an id window above the last locked entry (round-8 R7-D9). The
                        // post-condition counts `isLocked eq false` GLOBALLY; an id-bounded sweep locks
                        // a NARROWER set, so a single entry stranded BELOW the watermark (produced by
                        // the createSignedEntry-vs-createReport REPEATABLE_READ race: an entry committed
                        // after this closing pinned its snapshot has a low id but is invisible to the
                        // sweep) is never re-swept yet always counted — bricking this and every FUTURE
                        // closing with "not locked" forever, and there is no unlock/delete/repair path.
                        // Sweeping exactly what the post-condition checks makes the two provably agree, so
                        // a stranded entry is HEALED into the next closing instead of wedging the shop.
                        // (An entry committed AFTER this snapshot is invisible to both the sweep and the
                        // post-condition, so it is correctly left for the next closing — not stranded.)
                        // Fiscal books only. A Tresor is not an elektronisches
                        // Aufzeichnungssystem, so its cash is neither this closing's business
                        // nor part of the Kassenbestand — it is closed by its own Kassensturz.
                        // With one fiscal book this list is every book, so the predicate below
                        // selects exactly the rows it selected before cash books existed.
                        //
                        // Built ONCE and reused for both the sweep and the post-condition below —
                        // see the R7-D9 comment: the two predicates must remain the SAME set, or a
                        // Tresor-shaped gap between them strands an entry and wedges every future
                        // closing.
                        // Materialize once: the lazy SizedIterable would re-execute the SELECT on every
                        // iteration (expenses/revenues sum + locking loop = 2 scans -> 1).
                        // Same helper the transfer block above used, so the two see the same set —
                        // this fetch simply runs later and therefore also picks up the legs it created.
                        val kassenbuchHeaders = unsweptFiscalHeaders(fiscalBookIds)

                        val totals = bucketKassenbuchTotals(kassenbuchHeaders)
                        report.expenses = totals.expenses
                        report.cashRevenue = totals.revenues
                        report.closingBalance =
                            report.openingBalance + totals.expenses + totals.revenues + totals.transfers
                        report.endTime = currentTimeStamp()

                        // Deliberately kept as DAO field writes (NOT a bulk DSL update): Exposed
                        // batches these into few UPDATEs at flush, and a DSL update would leave the
                        // cached KassenbuchHeader entities stale (isLocked=false) — toResponseDto(true)
                        // below serializes those cached entities (incl. isLocked) into the fiscal report.
                        kassenbuchHeaders.forEach { header ->
                            header.isLocked = true
                            if (header.zBon == null) {
                                header.zBon = report
                            }
                        }
                        val unLockedCount =
                            KassenbuchHeader
                                .find {
                                    (KassenbuchHeaderTable.isLocked eq false) and
                                        (KassenbuchHeaderTable.cashBookId inList fiscalBookIds)
                                }.count()
                        if (unLockedCount > 0) {
                            throw validationFailed("Some kassenbuch entries are not locked - cannot create Z-report")
                        }

                        TableSession
                            .find {
                                (TableSessionTable.completed eq true) and (TableSessionTable.zBonId eq null)
                            }.forEach { session ->
                                session.zBon = report
                            }

                        logger.info(
                            "ZBonServiceImpl.createReport: successfully created Z-report {} with total revenue={}",
                            report.id.value,
                            totalRevenue,
                        )

                        val zbonResponseDto = report.toResponseDto(currency, withKassenBuchData = true, withTipPayouts = true)

                        // The Z-Bon PDF and the favourites recalculation are NOT done here: both are
                        // best-effort and run after this transaction has committed (storeZBonReportBestEffort,
                        // refreshFavouritesBestEffort). Caught inside this transaction, a failed statement
                        // of theirs aborted it on Postgres, and pgjdbc then turned the COMMIT into a silent
                        // ROLLBACK: the closing answered 201 having written nothing — no Z-Bon, no
                        // Kassenbuch entry, no payouts, no count, no transfer — and phase 2 went on to sign
                        // entries that did not exist.

                        // The daily Geldtransit's Ladenkasse leg is a fiscal entry like any other.
                        // createTransferLegsInTransaction marks it PENDING and leaves the signing to
                        // its caller — this closing — under the SAME client as the aggregate. Before
                        // this it was never signed and never queued: PENDING forever, with no TSE
                        // transaction, and TsePendingSweeper (bills only) never rescued it.
                        val transferLegSigning =
                            dailyTransferLegs?.let { legs ->
                                cashBookTransferService.buildLegSigningData(
                                    headerId = legs.fromHeaderId,
                                    belegNr = legs.fromBelegNr,
                                    netCashAmount = legs.amount.negate(),
                                    entryType = KassenbuchEntryType.CASH_TRANSFER_OUT,
                                    clientId = signingClientId ?: error("a Geldtransit leg was booked without a signer"),
                                )
                            }

                        // F59: the top-up's Ladenkasse leg is the IN leg — signed like any fiscal transfer leg.
                        val topUpLegSigning =
                            topUpLegs?.let { legs ->
                                cashBookTransferService.buildLegSigningData(
                                    headerId = legs.toHeaderId,
                                    belegNr = legs.toBelegNr,
                                    netCashAmount = legs.amount,
                                    entryType = KassenbuchEntryType.CASH_TRANSFER_IN,
                                    clientId = signingClientId ?: error("the top-up leg was booked without a signer"),
                                )
                            }

                        ZBonCreationResult(
                            responseDto = zbonResponseDto,
                            zBonKassenbuchHeaderId = zBonKassenBuchEntry.id.value,
                            signer = signer,
                            transferLegSigning = transferLegSigning,
                            topUpLegSigning = topUpLegSigning,
                            tipSettlements = recordedSettlements,
                            tipPayouts = bookedTipPayouts,
                            cashCount = bookedCashCount,
                        )
                    }
                }

            // Phase 1 committed: only now may the floor plans learn that the closing freed those tables.
            // A rolled-back close announces nothing.
            endedSessionCards.forEach { NotificationService.notifyTableStatusUpdate(it) }

            // Best-effort follow-ups, each in its own transaction now that the closing is committed —
            // a failure here is logged and leaves the closing intact. Kept ahead of phase 2, where they
            // ran before (inside phase 1), so nothing moves relative to the TSE signatures.
            storeZBonReportBestEffort(phase1.responseDto, UUID.fromString(employeeId), phase1.zBonKassenbuchHeaderId)
            refreshFavouritesBestEffort(phase1.responseDto.id)

            // Phase 2: TSE signing (outside dbQuery — prevents orphaned TSE transactions on DB rollback)
            val responseDto = phase1.responseDto
            val clientId = phase1.signer?.clientId
            closingSigner = phase1.signer

            try {
                // F59: the top-up's Ladenkasse leg, booked before the payouts it covers.
                phase1.topUpLegSigning?.let { leg ->
                    tseSigner.sign(
                        headerId = leg.headerId,
                        belegNr = leg.belegNr,
                        processData = leg.processData,
                        processType = leg.processType,
                        clientId = leg.clientId,
                    )
                }

                // F58: the in-closing tip payouts, in booking order after the aggregate — same signer,
                // same FAIL/QUEUE decision, same client as every other entry of this closing.
                phase1.tipPayouts.forEach { payout ->
                    tseSigner.sign(
                        headerId = payout.headerId,
                        belegNr = payout.belegNr,
                        processData = payout.processData,
                        processType = payout.processType,
                        clientId = clientId ?: error("closing movements were booked without a signer"),
                    )
                }

                // F58: the dialog's Kassendifferenz, booked after the payouts.
                phase1.cashCount?.let { count ->
                    tseSigner.sign(
                        headerId = count.headerId,
                        belegNr = count.belegNr,
                        processData = count.processData,
                        processType = count.processType,
                        clientId = clientId ?: error("closing movements were booked without a signer"),
                    )
                }

                // Then the daily Geldtransit's Ladenkasse leg — after the aggregate, so the TSE's
                // signature counters follow the Kassenbuch's booking order. Same signer, same decision.
                phase1.transferLegSigning?.let { leg ->
                    tseSigner.sign(
                        headerId = leg.headerId,
                        belegNr = leg.belegNr,
                        processData = leg.processData,
                        processType = leg.processType,
                        clientId = leg.clientId,
                    )
                    logger.info(
                        "ZBonServiceImpl.createReport: TSE signing completed for Geldtransit leg KB-{}",
                        leg.belegNr,
                    )
                }
            } finally {
                // Committed payouts are audited whatever phase 2 did — exactly the row a payout after
                // the closing writes, so the trail does not depend on which dialog paid the tip.
                phase1.tipPayouts.forEach { auditTipPayout(it, responseDto.id, employeeId, deviceId) }
                phase1.tipSettlements.forEach { auditTipSettlement(it, responseDto.id, employeeId, deviceId) }
            }

            // Phase 3: Card terminal daily closing (if ZVT enabled) — best-effort.
            if (configManager.isZvtEnabled()) {
                try {
                    val terminals = cardTerminalService.getAvailableTerminals()
                    for (terminal in terminals) {
                        try {
                            cardSettlementService.executeSettlement(terminal.id, responseDto.id)
                            logger.info(
                                "ZBonServiceImpl.createReport: card terminal settlement completed for: {}",
                                terminal.name,
                            )
                        } catch (e: Exception) {
                            logger.error(
                                "ZBonServiceImpl.createReport: card terminal settlement failed for: {}",
                                terminal.name,
                                e,
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.error("ZBonServiceImpl.createReport: card terminal settlement phase failed", e)
                }
            }

            responseDto
        }
    }

    /**
     * Renders and stores the Z-Bon PDF, linked to the closing's Kassenbuch entry, in its OWN
     * transaction after the closing has committed. Best-effort: a failure is logged and the closing
     * stands; the PDF can be rendered again from the stored closing.
     */
    private suspend fun storeZBonReportBestEffort(
        zBon: ZBonDto.ZBonResponseDto,
        employeeId: UUID,
        kassenbuchHeaderId: Long,
    ) {
        try {
            dbQuery {
                documents.storeZBonReport(
                    zBon = zBon,
                    employeeId = employeeId,
                    kassenbuchHeader = KassenbuchHeader.findById(kassenbuchHeaderId),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "ZBonServiceImpl.createReport: failed to generate Z-Bon PDF for Z-Bon {}, report still created",
                zBon.id,
                e,
            )
        }
    }

    /** Recalculates the favourite products after a committed closing. Best-effort, own transaction. */
    private suspend fun refreshFavouritesBestEffort(zBonId: Long) {
        try {
            favouriteProductService.updateFavourites()
            logger.info("ZBonServiceImpl.createReport: favourite products recalculated after Z-Bon {}", zBonId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "ZBonServiceImpl.createReport: failed to recalculate favourite products after Z-Bon {}: {}",
                zBonId,
                e.message,
            )
        }
    }

    /**
     * The CREATE_REPORT audit row's `after_state`: the closing's response as JSON, plus which TSE client
     * signed its cash movements and why — the designated till, the calling till, the TSE-disabled
     * placeholder (nothing was signed) or none, because the closing had no movement that needed one.
     * The `CASH_SALE` aggregate is never signed, so a plain closing names no signer.
     */
    private fun closingAuditState(
        response: ZBonDto.ZBonResponseDto,
        signer: ClosingSigner.Signer?,
    ): String =
        buildJsonObject {
            put("signingClientId", signer?.clientId)
            put("signingClientSource", signer?.source?.name ?: ClosingSigner.NO_SIGNER_NEEDED)
            put("report", Json.encodeToJsonElement(ZBonDto.ZBonResponseDto.serializer(), response))
        }.toString()

    /**
     * The `PAY_OUT_EMPLOYEE_TIP` audit row of a payout booked in the closing (F58): the same operation,
     * entity (the Kassenbuch header + Beleg-Nr.) and TSE reference as the payout after a closing writes.
     * The block only reads — the booking committed with the closing.
     */

    /**
     * The audit row of a bank/payroll settlement recorded in a closing (F59) — the row a settlement
     * after the closing writes too (`SETTLE_EMPLOYEE_TIP_BANK_PAYROLL`), so the trail does not depend
     * on which dialog recorded it.
     */
    private suspend fun auditTipSettlement(
        settled: ClosingTipPayoutBooker.Settled,
        zBonId: Long,
        employeeId: String,
        deviceId: String,
    ) {
        ExceptionConverter.catchingThrows(
            context = "settleEmployeeTipViaBankPayroll(zBonId=$zBonId, employeeId=$employeeId, inClosing=true)",
            employeeId = employeeId,
            operation = "SETTLE_EMPLOYEE_TIP_BANK_PAYROLL",
            entityType = "EMPLOYEE_TIP_PAYOUT",
            deviceId = deviceId,
            auditEntity = { response ->
                AuditEntityRef(id = response.payoutId.toString(), name = "Trinkgeld Bank/Lohn ${settled.recipientName}")
            },
            auditState = { it.toString() },
        ) {
            dbQuery {
                val zBon = ZBon.findById(zBonId) ?: error("Z-Bon $zBonId vanished after its tip settlements were recorded")
                val status =
                    EmployeeTipPayoutLedger.positionOf(zBon, settled.employeeId)?.toStatusDto()
                        ?: error("Employee ${settled.employeeId} lost their tip position in Z-Bon $zBonId")
                ZBonDto.TipPayoutResponseDto(
                    kassenbuchHeaderId = null,
                    belegNr = null,
                    status = status,
                    payoutId = settled.payoutId,
                    method = TipPayoutMethod.BANK_PAYROLL,
                )
            }
        }
    }

    private suspend fun auditTipPayout(
        payout: ClosingTipPayoutBooker.Booked,
        zBonId: Long,
        employeeId: String,
        deviceId: String,
    ) {
        ExceptionConverter.catchingThrows(
            context = "payOutEmployeeTip(zBonId=$zBonId, employeeId=$employeeId, inClosing=true)",
            employeeId = employeeId,
            operation = "PAY_OUT_EMPLOYEE_TIP",
            entityType = "KASSENBUCH",
            deviceId = deviceId,
            auditEntity = { (response, tseTransactionId) ->
                AuditEntityRef(id = response.kassenbuchHeaderId.toString(), name = response.belegNr, fiscalRecordId = tseTransactionId)
            },
            auditState = { (response, _) -> response.toString() },
        ) {
            dbQuery {
                val zBon = ZBon.findById(zBonId) ?: error("Z-Bon $zBonId vanished after its tip payouts were booked")
                val status =
                    EmployeeTipPayoutLedger.positionOf(zBon, payout.employeeId)?.toStatusDto()
                        ?: error("Employee ${payout.employeeId} lost their tip position in Z-Bon $zBonId")
                val tseTransactionId =
                    KassenbuchHeader
                        .findById(payout.headerId)
                        ?.tseTransaction
                        ?.id
                        ?.value
                        ?.toString()
                ZBonDto.TipPayoutResponseDto(
                    kassenbuchHeaderId = payout.headerId,
                    belegNr = payout.belegNr,
                    status = status,
                ) to tseTransactionId
            }
        }
    }

    /**
     * Refuses the closing while any table holds an OPEN item, and ends every session that holds none
     * (table opened but nothing ordered, every item cancelled, a session left behind by an old till).
     * Returns the cards for the sessions it ended; the caller broadcasts them after the commit.
     */

    /** Open, non-training sessions — training sessions never block a Z-Bon. */
    private val openSessionFilter = {
        ((TableSessionTable.completed eq false) or (TableSessionTable.endTime eq null)) and
            TrainingFilter.sessionNotTraining()
    }

    private fun validateSessionAndTableStatus(): List<NotificationDto.TableStatusUpdateDto> {
        // TABLE before SESSION: lock every occupied table, and every table an open session points at
        // (an orphaned session's table can read FREE), before the sessions themselves. A session that
        // opens after this plain read does so on a table it made OCCUPIED, so the lock covers it too.
        val openSessionTableIds = TableSession.find(openSessionFilter).map { it.table.id.value }
        val occupiedTables =
            RTable
                .find {
                    RTableTable.currentSessionId.isNotNull() or
                        (RTableTable.tableStatus neq TableStatus.FREE) or
                        (RTableTable.id inList openSessionTableIds)
                }.forUpdate()
                .toList()

        // Lock rows to prevent concurrent item additions during validation.
        val openSessions = TableSession.find(openSessionFilter).forUpdate().toList()

        val endedSessionCards = mutableListOf<NotificationDto.TableStatusUpdateDto>()
        openSessions.forEach {
            if (it.orderItems.any { item -> item.itemStatus == ItemStatus.OPEN }) {
                throw tablesOpen(it.table.tableNumber)
            }
            // No OPEN item = no unpaid business. It must not wedge the daily close
            // (E2E-2026-09-18 #3/#4): end it inside the closing's transaction.
            logger.info(
                "ZBonServiceImpl.validateSessionAndTableStatus: ending abandoned session {} on table {}",
                it.id.value,
                it.table.tableNumber,
            )
            tableSessionRepository.completeIfNoOpenItems(it.id.value)?.let { ended ->
                endedSessionCards +=
                    createTableStatusUpdateDto(
                        table = ended.table,
                        status = ended.table.tableStatus,
                        sessionId = ended.id.value,
                        sessionStartTime = ended.startTime.toString(),
                        employee = ended.employeeId,
                    )
            }
        }
        if (occupiedTables.isNotEmpty()) {
            occupiedTables.forEach {
                val session = it.currentSession
                if (session != null) {
                    if (session.completed) {
                        it.reset() // sets tableStatus = FREE AND currentSession = null
                        logger.warn(
                            "ZBonServiceImpl.validateSessionAndTableStatus: table {} is now free - session was completed",
                            it.tableNumber,
                        )
                    } else {
                        logger.warn(
                            "ZBonServiceImpl.validateSessionAndTableStatus: table {} is now occupied by session {} - cannot create Z-report",
                            it.tableNumber,
                            session.id.value,
                        )
                        throw validationFailed(
                            "Table ${it.tableNumber} is now occupied by session ${session.id.value} - cannot create Z-report",
                        )
                    }
                } else {
                    it.tableStatus = TableStatus.FREE
                }
            }
        }
        return endedSessionCards
    }

    override suspend fun getZBonHistory(
        startDate: String,
        endDate: String,
    ): List<ZBonDto.ZBonSummeryDto> {
        try {
            LocalDate.parse(startDate)
            LocalDate.parse(endDate)
        } catch (e: Exception) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.BadRequest,
                "Invalid date format. Expected YYYY-MM-DD.",
            )
        }
        val currency = configManager.getDefaultCurrency()
        return dbQuery { repository.getZBonHistory(startDate, endDate).map { it.copy(currency = currency) } }
    }

    override suspend fun getRecentReports(limit: Int): List<ZBonDto.ZBonSummeryDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getRecentReports(limit).map { zbon ->
                ZBonDto.ZBonSummeryDto(
                    id = zbon.id.value,
                    zbonNummer = zbon.zbonNummer,
                    startTime = zbon.startTime.toString(),
                    endTime = zbon.endTime.toString(),
                    totalRevenue = zbon.totalPosRevenue.toDecimalString(),
                    currency = currency,
                )
            }
        }
    }

    override suspend fun getZBonPdfBytes(zbonId: Long): ByteArray {
        // Try the stored PDF via the Kassenbuch header that actually carries it.
        //
        // `kasseBuch` is NOT "the Z-Bon's entry": createReport attributes EVERY unswept fiscal
        // header of the period to the report (`if (header.zBon == null) header.zBon = report`), so
        // this collection is the whole day's Kassenbuch — cash expenses, deposits, corrections and
        // both Geldtransit legs — and each of those carries its OWN linked Beleg. Only the
        // aggregate CASH_SALE entry gets the Z-Bon report attached, by
        // `PdfGenerationServiceImpl.generateZBonReportPdf`.
        //
        // Taking `.firstOrNull()` therefore returned an arbitrary row's document — in practice the
        // day's earliest entry, so an expense receipt or the Geldtransit Eigenbeleg — and the
        // download handed the operator a different document that is also a valid PDF, which is
        // exactly the kind of wrong answer nobody notices. Select by document type instead.
        val storedPdfId =
            dbQuery {
                val zbon = ZBon.findById(zbonId) ?: return@dbQuery null
                documents.storedZBonReportId(zbon.kasseBuch)
            }

        if (storedPdfId != null) {
            // Treat any failure as "fall through to regen" — best-effort retrieval.
            try {
                val bytes = documents.documentBytes(storedPdfId)
                logger.info(
                    "ZBonServiceImpl.getZBonPdfBytes: returning stored PDF for Z-Bon {} (documentId={})",
                    zbonId,
                    storedPdfId,
                )
                return bytes
            } catch (e: Exception) {
                logger.warn(
                    "ZBonServiceImpl.getZBonPdfBytes: stored PDF not found on disk for Z-Bon {}, falling back to regeneration",
                    zbonId,
                )
            }
        }

        logger.info("ZBonServiceImpl.getZBonPdfBytes: regenerating PDF for Z-Bon {}", zbonId)
        val report = getReportById(zbonId, true)
        return documents.zBonReportPdf(report)
    }

    // -------- Local PosException helpers --------

    private fun zBonNotFound(id: Long) =
        PosException(
            PosErrorCode.ZBON_NOT_FOUND,
            HttpStatusCode.NotFound,
            "ZBon with id=$id not found",
        )

    /**
     * An open table blocks the daily close: a typed 409 the till explains ("Tische noch offen"),
     * not a 400 VALIDATION_FAILED it rendered as "Unerwarteter Fehler" (E2E-2026-09-18 #19).
     */
    private fun tablesOpen(tableNumber: String) =
        PosException(
            PosErrorCode.ZBON_TABLES_OPEN,
            HttpStatusCode.Conflict,
            "Table $tableNumber is occupied by open items - cannot create Z-report",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
