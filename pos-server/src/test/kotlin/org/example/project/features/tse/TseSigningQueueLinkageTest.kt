package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.tse.data.repository.TseLateSigningRepositoryImpl
import org.example.project.features.tse.data.repository.TseSigningQueueRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseDeviceInfoCache
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.domain.models.TseSigningQueue
import org.example.project.features.tse.domain.models.TseTransactionState
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.infrastructure.di.httpClientModule
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.di.servicesModule
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.math.BigDecimal
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * H9 (audit 2026-07-13): a late signature must be LINKED to its entity. The storno-bill
 * producer enqueued the reverse Bill under CANCELLATION (whose consumer parses OrderItem
 * ids), and the Z-Bon producer enqueued "KB-<belegNr>" (whose consumer does toLong()) —
 * both left the entity permanently „TSE-Signierung ausstehend" although a valid signature
 * existed. These tests pin the consumer contracts: BILL/KASSENBUCH take numeric entity
 * ids and link them; a malformed referenceId must fail the entry LOUDLY, never sign
 * without linking.
 */
@DisplayName("TSE signing queue — late-signature entity linkage (H9)")
class TseSigningQueueLinkageTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        startKoin {
            modules(serializationModule, httpClientModule, reposModule, servicesModule)
        }
    }

    @AfterEach
    fun teardown() {
        GlobalContext.stopKoin()
        TestDatabase.teardown()
    }

    private fun service() =
        TseSigningQueueServiceImpl(
            TseSigningQueueRepositoryImpl(),
            TseTransactionRepositoryImpl(),
            FakeTseSignProvider(),
            NoOpTransactionAuditService(),
        )

    @Test
    fun `BILL entry links the bill to its late-signed transaction`() {
        var billId = 0L
        transaction {
            val admin = TestData.createAdmin()
            TestData.createShopInfo()
            val tws = TestData.createTableWithSession("LNK1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            billId =
                Bill
                    .new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "6001"
                        this.billAmount = BigDecimal("10.00")
                        this.paidAmount = BigDecimal("10.00")
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.tseSigningStatus = TseSigningStatus.QUEUED
                    }.id.value
        }

        val svc = service()
        val entry =
            runBlocking {
                svc.enqueue(
                    referenceId = billId.toString(),
                    entityType = TseEntityType.BILL,
                    processType = "Kassenbeleg-V1",
                    processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                    clientId = "POS-1",
                    reason = TseLateSigningReason.TSE_UNAVAILABLE,
                    reasonText = "test",
                )
            }
        runBlocking { svc.processEntry(entry.id) }

        transaction {
            val bill = Bill.findById(billId)!!
            assertNotNull(bill.tseTransaction, "late-signed bill must be linked to its transaction")
        }
    }

    /**
     * The Nachsignierung record (tse_late_signing, V82): recorded with its reason at queue time,
     * stamped when the queue signs, and never removed by queue cleanup — otherwise a late-signed
     * Vorgang becomes indistinguishable from one signed on time.
     */
    @Test
    fun `a late-signed Vorgang keeps its reason after signing and after queue cleanup`() {
        val svc = service()
        val entry =
            runBlocking {
                svc.enqueue(
                    referenceId = "4711",
                    entityType = TseEntityType.ORDER,
                    processType = "Bestellung-V1",
                    processData = "1;\"Cola\";4.50",
                    clientId = "POS-1",
                    reason = TseLateSigningReason.TSE_UNAVAILABLE,
                    reasonText = "TSE_HARDWARE_NOT_FOUND: TSE hardware not found",
                )
            }
        val queued = transaction { TseLateSigningRepositoryImpl().noteFor(TseEntityType.ORDER, "4711") }!!
        kotlin.test.assertTrue(queued.pending)
        kotlin.test.assertEquals("TSE_UNAVAILABLE", queued.reasonCode)

        runBlocking { svc.processEntry(entry.id) }
        val signed = transaction { TseLateSigningRepositoryImpl().noteFor(TseEntityType.ORDER, "4711") }!!
        kotlin.test.assertFalse(signed.pending, "the queue signed it, so it is no longer pending")
        assertNotNull(signed.signedAt)
        kotlin.test.assertEquals("TSE_HARDWARE_NOT_FOUND: TSE hardware not found", signed.reasonText)

        Thread.sleep(5) // the cleanup cutoff is "now"; make the entry strictly older
        runBlocking { svc.cleanupOldEntries(olderThanDays = 0) }
        transaction {
            kotlin.test.assertNull(TseSigningQueue.findById(entry.id), "precondition: cleanup removed the queue row")
            assertNotNull(TseLateSigningRepositoryImpl().noteFor(TseEntityType.ORDER, "4711"), "the record outlives the queue row")
        }
    }

    @Test
    fun `KASSENBUCH entry links the header via its numeric id`() {
        var headerId = 0L
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            headerId =
                KassenbuchRepositoryImpl(testKassenbuchDocuments())
                    .createEntry(
                        shop = shop,
                        employee = admin,
                        headerData =
                            KassenbuchDto.KassenbuchData(
                                entryType = KassenbuchEntryType.CASH_SALE,
                                belegNr = "2026-07-0001",
                                vorzBetrag = "10.0",
                                belegDatum = "14.07.2026",
                                belegtext = "ZBon Nr. 1",
                            ),
                        lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "10.0", ustSatz = "19")),
                    ).id.value
        }

        val svc = service()
        val entry =
            runBlocking {
                svc.enqueue(
                    referenceId = headerId.toString(),
                    entityType = TseEntityType.KASSENBUCH,
                    processType = "SonstigerVorgang",
                    processData = "ZBon Nr. 1",
                    clientId = "POS-1",
                    reason = TseLateSigningReason.TSE_UNAVAILABLE,
                    reasonText = "test",
                )
            }
        runBlocking { svc.processEntry(entry.id) }

        transaction {
            assertNotNull(
                KassenbuchHeader.findById(headerId)!!.tseTransaction,
                "late-signed kassenbuch header must be linked to its transaction",
            )
        }
    }

    @Test
    fun `malformed KASSENBUCH referenceId must not report SIGNED while linking nothing`() {
        val svc = service()
        // Either enqueue/processing throws (loud producer-bug surface), or the entry must NOT
        // be reported as successfully signed — silently swallowing the NumberFormatException
        // left the Vorgang permanently „ausstehend" in every export while the queue claimed
        // success (H9).
        val processed =
            runCatching {
                runBlocking {
                    val entry =
                        svc.enqueue(
                            referenceId = "KB-2026-07-0001", // the pre-fix Z-Bon producer format — not a numeric id
                            entityType = TseEntityType.KASSENBUCH,
                            processType = "SonstigerVorgang",
                            processData = "ZBon Nr. 1",
                            clientId = "POS-1",
                            reason = TseLateSigningReason.TSE_UNAVAILABLE,
                            reasonText = "test",
                        )
                    svc.processEntry(entry.id)
                }
            }
        processed.getOrNull()?.let {
            assertNotEquals(
                TseSigningStatus.SIGNED,
                it.signingStatus,
                "a signature that cannot be linked to its entity must fail the entry loudly",
            )
        }
    }

    /**
     * The CANCELLATION consumer must accept the reference its OWN producer writes.
     *
     * `OrderItemServiceImpl.cancelOrderItems` enqueues `referenceId = batchId` — a UUID — because
     * the previous `items.joinToString(";")` overflowed `TseSigningQueueTable.referenceId`
     * (varchar(100)) at ~12 items. The consumer still parsed that reference as a `;`-separated
     * list of numeric OrderItem ids, so `"<uuid>".toLong()` threw inside `enqueue`'s own dbQuery
     * and rolled the queue row back: the Storno committed unsigned with no queue row and no
     * tse_late_signing row, and nothing re-drives it (TsePendingSweeper is Bill-only).
     */
    @Test
    fun `CANCELLATION enqueued under its batch id keeps its queue row`() {
        val batchId = "5a201b20-5258-4910-8bad-999e73be3738"
        transaction { TestData.createShopInfo() }
        createCancelledItemInBatch(batchId, tableNumber = "LNK3")

        val svc = service()
        val entry =
            runBlocking {
                svc.enqueue(
                    referenceId = batchId,
                    entityType = TseEntityType.CANCELLATION,
                    processType = "Bestellung-V1",
                    processData = "1;\"Cola\";-4.90",
                    clientId = "POS-1",
                    reason = TseLateSigningReason.TSE_UNAVAILABLE,
                    reasonText = "TSE_UNREACHABLE: TSE Server is unavailable",
                )
            }

        transaction {
            assertNotNull(
                TseSigningQueue.findById(entry.id),
                "the queue row must survive the enqueue — without it nothing ever re-drives the Storno",
            )
        }
        assertNotNull(
            transaction { TseLateSigningRepositoryImpl().noteFor(TseEntityType.CANCELLATION, batchId) },
            "the Nachsignierung record must survive too, or the export has no outage cause to print",
        )
    }

    @Test
    fun `CANCELLATION entry links every cancellation in the batch to its late-signed transaction`() {
        val batchId = "48f983d0-73fd-4fa9-9e9f-dc9b32eb5765"
        transaction { TestData.createShopInfo() }
        val cancellationIds =
            listOf(
                createCancelledItemInBatch(batchId, tableNumber = "LNK4"),
                createCancelledItemInBatch(batchId, tableNumber = "LNK5"),
            )

        val svc = service()
        val entry =
            runBlocking {
                svc.enqueue(
                    referenceId = batchId,
                    entityType = TseEntityType.CANCELLATION,
                    processType = "Bestellung-V1",
                    processData = "1;\"Cola\";-4.90",
                    clientId = "POS-1",
                    reason = TseLateSigningReason.TSE_UNAVAILABLE,
                    reasonText = "test",
                )
            }
        runBlocking { svc.processEntry(entry.id) }

        transaction {
            cancellationIds.forEach { id ->
                val cancellation = Cancellation.findById(id)!!
                assertNotNull(
                    cancellation.tseTransaction,
                    "late-signed cancellation $id must be linked to its transaction",
                )
                kotlin.test.assertEquals(
                    TseSigningStatus.SIGNED,
                    cancellation.tseSigningStatus,
                    "a linked cancellation is no longer pending",
                )
            }
        }
    }

    /**
     * Rows queued BEFORE the producer switched to the batch id still carry
     * `items.joinToString(";")`. Resolving only by batch id would find nothing for them and the arm
     * would succeed having linked nothing — the exact silent-no-op H9 exists to prevent. The
     * legacy shape must still link.
     */
    @Test
    fun `CANCELLATION queued under the legacy item-id list still links its cancellations`() {
        transaction { TestData.createShopInfo() }
        val cancellationId = createCancelledItemInBatch(batchId = null, tableNumber = "LNK6")
        val itemId =
            transaction {
                Cancellation
                    .findById(cancellationId)!!
                    .orderItem.id.value
            }

        val svc = service()
        val entry =
            runBlocking {
                svc.enqueue(
                    referenceId = itemId.toString(),
                    entityType = TseEntityType.CANCELLATION,
                    processType = "Bestellung-V1",
                    processData = "1;\"Cola\";-4.90",
                    clientId = "POS-1",
                    reason = TseLateSigningReason.TSE_UNAVAILABLE,
                    reasonText = "test",
                )
            }
        runBlocking { svc.processEntry(entry.id) }

        transaction {
            assertNotNull(
                Cancellation.findById(cancellationId)!!.tseTransaction,
                "a legacy item-id reference must still link its cancellation",
            )
        }
    }

    /** One CANCELLED order item plus its `Cancellation` row, stamped with [batchId]. */
    private fun createCancelledItemInBatch(
        batchId: String?,
        tableNumber: String,
    ): Long =
        transaction {
            val admin =
                TestData.createAdmin(
                    employeeNumber = "CNC_$tableNumber",
                    username = "cnc_$tableNumber",
                )
            val tws = TestData.createTableWithSession(tableNumber)
            val session = TableSession.findById(tws.session.id)!!
            val item = TestData.createOrderItemInSession(session, ItemStatus.CANCELLED)
            Cancellation
                .new {
                    this.orderItem = item
                    this.employeeId = admin
                    this.createdAt = currentTimeStamp()
                    this.tseSigningStatus = TseSigningStatus.PENDING
                    this.batchId = batchId
                }.id.value
        }

    /**
     * TSER-12, re-keyed for the signer (design 2026-09-22 §4.1, deviation 5): the queue no longer
     * reads `open_tse_transaction_number`. A STARTED row is found by ([referenceId], [entityType])
     * and FINISHED on the device, so a started TANR is never abandoned for a brand-new one.
     */
    @Test
    fun `a started TSE transaction is finished, not replaced by a new one`() {
        var billId = 0L
        var startedTxEntityId = 0L
        transaction {
            val admin = TestData.createAdmin()
            TestData.createShopInfo()
            val tws = TestData.createTableWithSession("LNK2")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            billId =
                Bill
                    .new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "6002"
                        this.billAmount = BigDecimal("10.00")
                        this.paidAmount = BigDecimal("10.00")
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.tseSigningStatus = TseSigningStatus.QUEUED
                    }.id.value
            // The transaction the TSE already STARTED before the finish failed (TSER-12).
            startedTxEntityId =
                org.example.project.features.tse.domain.models.TseTransaction
                    .new {
                        this.tseSerialNumber = ScriptedTseSignProvider.SERIAL_HEX
                        this.transactionNumber = 4711L
                        this.processType = "Kassenbeleg-V1"
                        this.processDataHash = null
                        this.startTime = now
                        this.clientId = "POS-1"
                        this.tseStartTimestamp = 1_752_000_000L
                        this.referenceId = billId.toString()
                        this.entityType = TseEntityType.BILL.name
                        this.state = TseTransactionState.STARTED
                    }.id.value
        }

        val provider = ScriptedTseSignProvider(mode = "NORMAL")
        provider.openOnDevice += 4711L
        // The attached device must be IDENTIFIED before a stored TANR may be finished on it: after
        // a restart the signer refuses to finish blind, because an unidentified device may be a
        // replacement whose counter restarted at 1. This is what `LocalTseAdminProvider.getInfo`
        // (the status screen's own call) records in production — no signing, no extra device I/O.
        TseDeviceInfoCache.forgetSerialForTests()
        TseDeviceInfoCache.recordSerial(ScriptedTseSignProvider.SERIAL_HEX)
        val svc =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                provider,
                NoOpTransactionAuditService(),
            )
        val entry =
            runBlocking {
                svc.enqueue(
                    referenceId = billId.toString(),
                    entityType = TseEntityType.BILL,
                    processType = "Kassenbeleg-V1",
                    processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                    clientId = "POS-1",
                    reason = TseLateSigningReason.TSE_UNAVAILABLE,
                    reasonText = "test",
                )
            }
        runBlocking { svc.processEntry(entry.id) }

        kotlin.test.assertEquals(
            listOf("finish:4711:Kassenbeleg-V1"),
            provider.calls,
            "worker must FINISH the started transaction and neither start nor quick-sign a new one",
        )
        transaction {
            val started =
                org.example.project.features.tse.domain.models.TseTransaction
                    .findById(startedTxEntityId)!!
            kotlin.test.assertNotNull(started.signature, "the STARTED entity carries the finish signature")
            kotlin.test.assertEquals(TseTransactionState.FINISHED, started.state)
            assertNotNull(Bill.findById(billId)!!.tseTransaction)
            kotlin.test.assertEquals(
                startedTxEntityId,
                Bill
                    .findById(billId)!!
                    .tseTransaction!!
                    .id.value,
                "the bill links to the started transaction, not a duplicate row",
            )
        }
    }

    private class FakeTseSignProvider : TseSignProvider {
        override suspend fun quickSign(
            processType: String,
            processData: String,
            clientId: String,
        ): TseDto.SignResultDto {
            quickSignCalls++
            return TseDto.SignResultDto(
                success = true,
                processType = processType,
                transactionNumber = 1L,
                signature = "SIM-SIGNATURE",
                timestamp = 1_752_000_000L,
                serialNumber = "SIM-TSE-001",
                signatureCounter = 1L,
                signatureAlgorithm = "ecdsa-plain-SHA384",
                publicKey = null,
                certificate = null,
            )
        }

        override suspend fun startTransaction(
            processType: String,
            clientId: String,
        ): TseDto.TransactionStartResultDto =
            TseDto.TransactionStartResultDto(
                success = true,
                processType = processType,
                transactionNumber = 1L,
                timestamp = 1_752_000_000L,
                clientId = clientId,
                serialNumber = "SIM-TSE-001",
            )

        override suspend fun updateTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
        ): TseDto.SignResultDto = quickSign("Bestellung-V1", processData, clientId)

        var finishCalls = 0
        var quickSignCalls = 0

        override suspend fun finishTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
            processType: String,
        ): TseDto.SignResultDto {
            finishCalls++
            return TseDto.SignResultDto(
                success = true,
                processType = "Kassenbeleg-V1",
                transactionNumber = transactionNumber, // echo the finished transaction
                signature = "LATE-FINISH-SIGNATURE",
                timestamp = 1_752_000_100L,
                serialNumber = "SIM-TSE-001",
                signatureCounter = 2L,
                signatureAlgorithm = "ecdsa-plain-SHA384",
                publicKey = null,
                certificate = null,
            )
        }

        override suspend fun checkHealth(): TseDto.HealthCheckResultDto =
            TseDto.HealthCheckResultDto(
                healthy = true,
                initialized = true,
                validTime = true,
                passedSelfTest = true,
                remainingSignatures = 1_000L,
                availableCapacity = 1_000L,
            )

        override fun isConnected(): Boolean = true

        override fun getCachedMode(): String = "SIMULATION"

        override fun registerConnectionListener() = Unit

        override suspend fun restartConnection(): TseDto.HealthCheckResultDto? = null

        override fun close() = Unit
    }

    private class NoOpTransactionAuditService : TransactionAuditService {
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
        ): String = "AUDIT-NOOP"

        override suspend fun getTransactionLogs(query: TransactionAuditDto.TransactionAuditQueryDto): List<TransactionAuditDto.TransactionAuditLogDto> =
            emptyList()

        override suspend fun getTransactionById(transactionId: String): TransactionAuditDto.TransactionAuditLogDto = throw NotImplementedError()

        override suspend fun getEmployeeTransactions(
            employeeId: String,
            limit: Int,
        ): List<TransactionAuditDto.TransactionAuditLogDto> = emptyList()

        override suspend fun getEntityTransactions(
            entityType: String,
            entityId: String,
        ): List<TransactionAuditDto.TransactionAuditLogDto> = emptyList()

        override suspend fun getPaginatedTransactionLogs(
            query: TransactionAuditDto.PaginatedTransactionAuditQueryDto,
        ): TransactionAuditDto.PaginatedTransactionAuditResponseDto = throw NotImplementedError()
    }
}
