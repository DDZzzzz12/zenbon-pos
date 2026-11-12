package org.example.project.features.fiscal

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.features.audit.data.service.TransactionAuditServiceImpl
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.document.data.service.KassenbuchDocumentsImpl
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.product.domain.service.FavouriteProductService
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.repository.TseSigningQueueRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TsePendingSweeper
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.database.tables.TseSigningQueueTable
import org.example.project.features.tse.domain.models.TseSigningQueue
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.features.zBon.data.service.ZBonServiceImpl
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.TippedDayFixture
import org.example.project.testutils.TippedDayFixture.Sale
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentInstant
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.getTimeZone
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.module.Module
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * pgjdbc commits an ABORTED transaction as a silent rollback: after any failed statement Postgres
 * refuses everything until ROLLBACK (25P02), and the driver turns the final COMMIT into a ROLLBACK
 * while reporting success. So a fiscal path that catches an SQL failure inside its transaction and
 * then runs no further statement loses EVERY earlier write of that transaction and still answers
 * as if it had worked. H2 does not abort a transaction on a failed statement, which is why the
 * H2 suite never saw it.
 *
 * Each scenario injects one failing statement at a known swallow site and asserts the one
 * invariant an operator can rely on: the work is either fully committed and reported as done, or
 * fully rolled back and reported as failed. The scenarios are engine-neutral — they run against
 * whatever [database] connects — and are driven by [SilentRollbackPostgresTest] (the engine that
 * shows the bug) and [SilentRollbackH2Test] (so the default suite keeps the guard).
 */
object SilentRollbackScenarios {
    /** A statement every engine rejects at parse time, run on the CURRENT transaction's connection. */
    private fun failingStatement(): Nothing {
        TransactionManager.current().exec("SELECT broken_column FROM silent_rollback_missing_table")
        error("the failing statement unexpectedly succeeded")
    }

    private val day = listOf(Sale("anna", "1.00"), Sale("bert", "2.00", byCard = true))

    // ---------------------------------------------------------------- Z-Bon: favourites (site 1)

    /** Fails the favourites recalculation the closing triggers. */
    private class FailingFavourites : FavouriteProductService {
        var attempts = 0

        override suspend fun updateFavourites(
            limit: Int,
            periodDays: Int,
        ) {
            attempts++
            dbQuery { failingStatement() }
        }

        override suspend fun getFavourites(): List<Long> = emptyList()

        override suspend fun getFavouritesWithAnalytics(periodDays: Int) = throw NotImplementedError()
    }

    fun `a failing favourites recalculation does not lose the closing`(database: () -> Unit) {
        val favourites = FailingFavourites()
        val outcome = closeTheDay(database, module { single<FavouriteProductService> { favourites } })
        assertTrue(favourites.attempts > 0, "the scenario must actually reach the favourites step")
        outcome.requireCommittedBestEffortFailureLogged("favourite")
    }

    // ---------------------------------------------------------------- Z-Bon: report PDF (site 2)

    /** Fails storing the Z-Bon PDF — what a failing document INSERT or a failing storage-path config refresh does. */
    private class FailingZBonPdf(
        real: KassenbuchDocuments,
    ) : KassenbuchDocuments by real {
        var attempts = 0

        override suspend fun storeZBonReport(
            zBon: ZBonDto.ZBonResponseDto,
            employeeId: java.util.UUID,
            kassenbuchHeader: KassenbuchHeader?,
        ): Long {
            attempts++
            failingStatement()
        }
    }

    fun `a failing Z-Bon PDF does not lose the closing`(database: () -> Unit) {
        lateinit var pdf: FailingZBonPdf
        val outcome =
            closeTheDay(
                database,
                module {
                    single<KassenbuchDocuments> { FailingZBonPdf(KassenbuchDocumentsImpl(get(), get())).also { pdf = it } }
                },
            )
        assertTrue(pdf.attempts > 0, "the scenario must actually reach the PDF step")
        outcome.requireCommittedBestEffortFailureLogged("PDF")
    }

    // ------------------------------------------------- Z-Bon: transfer-target config read (site 2)

    /**
     * Stands in for [ShopConfigurationManager]'s cache refresh when the 30-minute cache expires
     * mid-closing: a refresh inside the closing's transaction whose SELECT fails, and is swallowed
     * — exactly what `refreshConfiguration` does. Counts the reads that happened inside a transaction.
     */
    private class RefreshingInsideTransaction : IShopConfigurationManager by ShopConfigurationManager {
        var readsInsideTransaction = 0

        override suspend fun getCashBookTransferTargetId(): Long? {
            if (TransactionManager.currentOrNull() != null) {
                readsInsideTransaction++
                runCatching { failingStatement() }
            }
            return ShopConfigurationManager.getCashBookTransferTargetId()
        }
    }

    fun `the transfer target is read before the closing transaction opens`(database: () -> Unit) {
        val config = RefreshingInsideTransaction()
        val outcome =
            closeTheDay(
                database,
                module { single<IShopConfigurationManager> { config } },
                routeTakingsToSafe = true,
            )
        outcome.requireConsistent()
        assertEquals(
            0,
            config.readsInsideTransaction,
            "a config read inside the closing can hit a cache refresh that swallows a failed SELECT",
        )
        assertEquals(HttpStatusCode.Created, outcome.status, outcome.body)
    }

    // ------------------------------------------------------------- TSE queue cancelEntry (site 3)

    private class FailingAudit(
        real: TransactionAuditService,
    ) : TransactionAuditService by real {
        override suspend fun logTransaction(
            employeeId: String,
            employeeName: String?,
            employeeNumber: String,
            authorizationType: String,
            authorizingManagerId: String,
            operation: String,
            entityType: String,
            entityId: String,
            entityName: String?,
            beforeState: Any?,
            afterState: Any?,
            deviceId: String?,
            ipAddress: String?,
            reason: String?,
            amountImpact: Double?,
            fiscalRecordId: String?,
            metadata: Map<String, Any>?,
            traceId: String?,
        ): String = failingStatement()
    }

    /**
     * Abandoning a signature leaves a Beleg permanently unsigned (§146a AO); its audit row is the
     * only record of who did it and why. A failed audit insert must therefore fail the cancel —
     * the entry stays queued and the operator sees an error — never commit the abandonment
     * unrecorded, nor (on Postgres) report a cancel that was silently rolled back.
     */
    fun `a failing audit insert fails the cancel visibly`(database: () -> Unit) {
        database()
        try {
            val service =
                TseSigningQueueServiceImpl(
                    TseSigningQueueRepositoryImpl(),
                    TseTransactionRepositoryImpl(),
                    UnusedTse,
                    FailingAudit(TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json)),
                )
            val entryId =
                runBlocking {
                    service
                        .enqueue(
                            referenceId = "77",
                            entityType = TseEntityType.BILL,
                            processType = "Kassenbeleg-V1",
                            processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                            clientId = "TERM-SILENT",
                            reason = TseLateSigningReason.TSE_UNAVAILABLE,
                            reasonText = "test",
                        ).id
                }

            val cancel = runCatching { runBlocking { service.cancelEntry(entryId, employeeId = "emp-7", reason = "device replaced") } }
            val status = transaction { TseSigningQueue.findById(entryId)!!.signingStatus }
            val auditRows =
                transaction {
                    TransactionAuditLogTable
                        .selectAll()
                        .where { TransactionAuditLogTable.operation eq "TSE_QUEUE_CANCELLED" }
                        .count()
                }

            if (cancel.isSuccess && status != TseSigningStatus.CANCELLED) {
                fail("cancelEntry reported success but the cancel was rolled back (status $status) — a silent rollback")
            }
            assertTrue(cancel.isFailure, "an abandonment whose audit row could not be written must not succeed")
            assertTrue(status != TseSigningStatus.CANCELLED, "the failed cancel must leave the entry as it was, got $status")
            assertEquals(0L, auditRows)
        } finally {
            TestDatabase.teardown()
        }
    }

    // ---------------------------------------------------------------- TSE pending sweeper (sweep)

    /**
     * The real queue service, except that every enqueue of [failingReference] runs a failing
     * statement. Every one, not just the first: Exposed re-runs a top-level transaction that threw an
     * SQL error, so a fake that failed once would succeed on the retry.
     */
    private class FailingEnqueueFor(
        private val real: TseSigningQueueService,
        private val failingReference: String,
    ) : TseSigningQueueService by real {
        var calls = 0

        override suspend fun enqueue(
            referenceId: String,
            entityType: TseEntityType,
            processType: String,
            processData: String,
            clientId: String,
            reason: TseLateSigningReason,
            reasonText: String,
            openTseTransactionNumber: Long?,
        ): TseSigningQueueDto.QueueEntryResponseDto {
            calls++
            if (referenceId == failingReference) dbQuery { failingStatement() }
            return real.enqueue(referenceId, entityType, processType, processData, clientId, reason, reasonText, openTseTransactionNumber)
        }
    }

    /**
     * Two bills stranded PENDING without a queue row; enqueuing one of them fails. The other bill
     * must stay enqueued, and what the sweeper reports must be what is stored —
     * on Postgres the failure used to roll back the whole cycle while it reported one bill queued.
     */
    fun `one failing enqueue does not undo the rest of the sweep`(database: () -> Unit) {
        database()
        try {
            val queued = seedStrandedBill("A")
            val failing = seedStrandedBill("B")
            val queue =
                FailingEnqueueFor(
                    TseSigningQueueServiceImpl(
                        TseSigningQueueRepositoryImpl(),
                        TseTransactionRepositoryImpl(),
                        UnusedTse,
                        TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json),
                    ),
                    failingReference = failing.toString(),
                )
            val reported = runBlocking { TsePendingSweeper(TseSigningQueueRepositoryImpl(), queue).sweep() }
            val stored = transaction { TseSigningQueueTable.selectAll().map { it[TseSigningQueueTable.referenceId] } }

            assertTrue(queue.calls >= 2, "the scenario must reach both bills")
            assertEquals(reported, stored.size, "the sweeper reported $reported enqueued but $stored were committed")
            assertEquals(listOf(queued.toString()), stored, "the healthy bill stays enqueued, the failing one is not")
        } finally {
            TestDatabase.teardown()
        }
    }

    /** A forward-sale bill, PENDING and unsigned, closed an hour ago on a TSE-registered till; returns its id. */
    @OptIn(ExperimentalTime::class)
    private fun seedStrandedBill(tag: String): Long =
        transaction {
            val admin = TestData.createAdmin(employeeNumber = "SRS_$tag", username = "srs_$tag")
            val cash = TestData.createPaymentType("Cash_SRS_$tag", isCash = true)
            val session = TableSession.findById(TestData.createTableWithSession("SRS$tag").session.id)!!
            val closedAt = currentInstant().minus(60.minutes).toLocalDateTime(getTimeZone())
            val till =
                Terminal.new {
                    deviceId = "dev_srs_$tag"
                    clientId = "TERM-SRS-$tag"
                    registeredAt = currentTimeStamp()
                    lastSeenAt = currentTimeStamp()
                    tseClientRegistered = true
                }
            val bill =
                Bill.new {
                    employeeId = admin
                    tableSession = session
                    billNumber = "SRS-$tag"
                    billAmount = BigDecimal("10.00")
                    paidAmount = BigDecimal("10.00")
                    tips = BigDecimal.ZERO
                    startTime = closedAt
                    endTime = closedAt
                    tseTransaction = null
                    tseSigningStatus = TseSigningStatus.PENDING
                    terminal = till
                }
            Payment.new {
                this.bill = bill
                paymentType = cash
                amount = BigDecimal("10.00")
                currency = "EUR"
                paymentStatus = PaymentStatus.COMPLETED
                paymentTime = closedAt
            }
            bill.id.value
        }

    // ---------------------------------------------------------------------------- shared plumbing

    /** The closing's response, what the database holds afterwards and what [ZBonServiceImpl] logged. */
    private data class ClosingOutcome(
        val status: HttpStatusCode,
        val body: String,
        val zBons: Long,
        val aggregates: Long,
        val logged: List<ILoggingEvent>,
    ) {
        /** Fully committed and reported as done, or fully rolled back and reported as failed. */
        fun requireConsistent() {
            if (status == HttpStatusCode.Created) {
                assertEquals(1L, zBons, "the closing answered 201 but no Z-Bon was committed — a silent rollback. $body")
                assertEquals(1L, aggregates, "the closing answered 201 but its Kassenbuch entry was not committed")
            } else {
                assertTrue(status.value >= 400, "unexpected status $status")
                assertEquals(0L, zBons, "a failed closing must not leave a Z-Bon behind")
                assertEquals(0L, aggregates, "a failed closing must not leave its Kassenbuch entry behind")
            }
        }

        /** A best-effort step failed: the closing must still be committed, and the failure logged. */
        fun requireCommittedBestEffortFailureLogged(step: String) {
            requireConsistent()
            assertEquals(HttpStatusCode.Created, status, "a best-effort $step failure must not fail the closing: $body")
            assertTrue(
                logged.any { it.level.isGreaterOrEqual(Level.WARN) && it.formattedMessage.contains(step, ignoreCase = true) },
                "the $step failure must be logged; got ${logged.map { it.formattedMessage }}",
            )
        }
    }

    private fun closeTheDay(
        database: () -> Unit,
        override: Module,
        routeTakingsToSafe: Boolean = false,
    ): ClosingOutcome {
        val fixture = TippedDayFixture("silent-rollback-till", "SRB")
        val logger = LoggerFactory.getLogger(ZBonServiceImpl::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        lateinit var outcome: ClosingOutcome
        try {
            testApp(
                setupData = {
                    fixture.seed(day)
                    if (routeTakingsToSafe) fixture.routeTakingsToSafe()
                },
                overrides = listOf(override),
                database = database,
            ) { client ->
                try {
                    val response = fixture.closeWith(client, ZBonDto.CreateZBonRequestDto())
                    outcome =
                        transaction {
                            ClosingOutcome(
                                status = response.status,
                                body = runBlocking { response.bodyAsText() },
                                zBons = ZBon.all().count(),
                                aggregates = KassenbuchHeader.find { KassenbuchHeaderTable.isZbonAggregate eq true }.count(),
                                logged = appender.list.toList(),
                            )
                        }
                } finally {
                    if (routeTakingsToSafe) fixture.stopRoutingToSafe()
                }
            }
        } finally {
            logger.detachAppender(appender)
            BillNumberTracker.clearAll()
        }
        assertFalse(outcome.body.isEmpty() && outcome.status == HttpStatusCode.Created, "empty 201 body")
        return outcome
    }

    /** The cancel path never signs; any call is a test bug. */
    private object UnusedTse : TseSignProvider {
        override suspend fun quickSign(
            processType: String,
            processData: String,
            clientId: String,
        ) = throw UnsupportedOperationException()

        override suspend fun startTransaction(
            processType: String,
            clientId: String,
        ) = throw UnsupportedOperationException()

        override suspend fun updateTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
        ) = throw UnsupportedOperationException()

        override suspend fun finishTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
            processType: String,
        ) = throw UnsupportedOperationException()

        override suspend fun checkHealth() = null

        override fun isConnected(): Boolean = false

        override fun getCachedMode(): String = "SIMULATION"

        override fun registerConnectionListener() = Unit

        override suspend fun restartConnection() = null

        override fun close() = Unit
    }
}
