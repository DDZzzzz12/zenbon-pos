package org.example.project.features.salesinvoice

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseFailureBehavior
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceService
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.database.tables.TseLateSigningTable
import org.example.project.features.tse.database.tables.TseSigningQueueTable
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.infrastructure.di.httpClientModule
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.di.servicesModule
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how `settleAtTill` TSE-signs its clearing bill after the Phase-1 commit (B2-4 A3): the status
 * the bill gets, whether the TSE transaction is linked, the Nachsignierung queue call and its
 * permanent cause, the FAIL-mode rejection, and the exact sequence of TSE config reads, device calls
 * and queue calls.
 *
 * Model: `SaleSigningCharacterisationTest`. The service comes from the **production Koin modules**,
 * resolved in an isolated application; only the TSE device, the config manager (a recorder over
 * [ShopConfigurationManager]) and the signing queue (a recorder over the production implementation)
 * are replaced underneath it. The device always reports the SIMULATION path to the signer.
 */
@DisplayName("Sales-invoice till clearing TSE signing (characterisation)")
class SalesInvoiceSigningCharacterisationTest {
    private data class EnqueueCall(
        val referenceId: String,
        val entityType: TseEntityType,
        val processType: String,
        val processData: String,
        val clientId: String,
        val reason: TseLateSigningReason,
        val reasonText: String,
        val statusAtCall: TseSigningStatus?,
    )

    private class Rig {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val enqueued: MutableList<EnqueueCall> = Collections.synchronizedList(mutableListOf())

        @Volatile var deviceUp = true

        @Volatile var enqueueFailure: Throwable? = null

        @Volatile var signFailure: Throwable? = null
    }

    private class RecordingQueue(
        private val real: TseSigningQueueService,
        private val rig: Rig,
    ) : TseSigningQueueService by real {
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
            rig.events += "queue.enqueue"
            val status = transaction { Bill.findById(referenceId.toLong())?.tseSigningStatus }
            rig.enqueued += EnqueueCall(referenceId, entityType, processType, processData, clientId, reason, reasonText, status)
            rig.enqueueFailure?.let { throw it }
            return real.enqueue(referenceId, entityType, processType, processData, clientId, reason, reasonText, openTseTransactionNumber)
        }
    }

    private class Run(
        val invoiceId: Long,
        val error: Throwable?,
        val events: List<String>,
        val enqueued: List<EnqueueCall>,
    )

    @AfterEach
    fun reset() = BillNumberTracker.clearAll()

    // ── outcomes ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `NORMAL signed links the transaction and marks SIGNED`() =
        scenario(TseMode.NORMAL) { run ->
            assertNull(run.error, "an online-signed clearing must not fail: ${run.error}")
            assertEquals(TseSigningStatus.SIGNED, statusOf(run.invoiceId))
            assertTrue(linked(run.invoiceId), "the TSE transaction must be linked")
            assertNoQueueWork(run)
            assertEquals(CONFIG_READS + ONLINE_SIGN, run.events)
        }

    @Test
    fun `SIMULATION signed links the transaction and marks SIMULATION`() =
        scenario(TseMode.SIMULATION) { run ->
            assertNull(run.error)
            assertEquals(TseSigningStatus.SIMULATION, statusOf(run.invoiceId))
            assertTrue(linked(run.invoiceId))
            assertNoQueueWork(run)
            assertEquals(CONFIG_READS + ONLINE_SIGN, run.events)
        }

    @Test
    fun `DISABLED gives no signature and marks DISABLED`() =
        scenario(TseMode.DISABLED) { run ->
            assertNull(run.error)
            assertEquals(TseSigningStatus.DISABLED, statusOf(run.invoiceId))
            assertTrue(!linked(run.invoiceId), "no TSE transaction exists to link")
            assertNoQueueWork(run)
            assertEquals(CONFIG_READS, run.events, "a disabled TSE is never asked")
        }

    @Test
    fun `failed under QUEUE writes QUEUED, enqueues once with the unavailable cause, then PENDING`() =
        scenario(TseMode.NORMAL, deviceUp = false) { run ->
            assertNull(run.error, "QUEUE mode must accept the clearing: ${run.error}")
            assertEquals(CONFIG_READS + OFFLINE_SIGN + "queue.enqueue", run.events)
            val call = run.enqueued.single()
            assertEquals(clearingBill(run.invoiceId).id.value.toString(), call.referenceId)
            assertEquals(TseEntityType.BILL, call.entityType)
            assertEquals("Kassenbeleg-V1", call.processType)
            assertEquals(CLIENT_ID, call.clientId)
            assertTrue(call.processData.isNotBlank())
            assertEquals(TseLateSigningReason.TSE_UNAVAILABLE, call.reason)
            assertEquals(UNREACHABLE_TEXT, call.reasonText)
            assertEquals(TseSigningStatus.QUEUED, call.statusAtCall, "the caller writes QUEUED before queueing")
            assertTrue(!linked(run.invoiceId))

            val row = queueRows().single()
            assertEquals(listOf(call.referenceId, "BILL", call.processType, call.processData, CLIENT_ID), row)
            assertEquals(listOf(call.referenceId, "BILL", "TSE_UNAVAILABLE", UNREACHABLE_TEXT), lateRows().single())
            assertEquals(TseSigningStatus.PENDING, statusOf(run.invoiceId), "the queue marks the bill PENDING")
            assertEquals(0, BigDecimal.ZERO.compareTo(transaction { SalesInvoice.findById(run.invoiceId)!!.openAmount }))
        }

    @Test
    fun `failed under FAIL rejects with TSE_SIGNING_FAILED and leaves the committed bill FAILED`() =
        scenario(TseMode.NORMAL, failure = TseFailureBehavior.FAIL, deviceUp = false) { run ->
            val error = assertIs<PosException>(run.error, "FAIL mode must reject the clearing")
            assertEquals(PosErrorCode.TSE_SIGNING_FAILED, error.code)
            assertEquals(HttpStatusCode.ServiceUnavailable, error.httpStatus)
            assertEquals("TSE signing failed for clearing bill ${clearingBill(run.invoiceId).billNumber}", error.message)
            assertNull(error.cause, "the rejection is a fresh exception, not the signing error")
            assertEquals(TseSigningStatus.FAILED, statusOf(run.invoiceId))
            assertTrue(!linked(run.invoiceId))
            assertNoQueueWork(run)
            assertEquals(CONFIG_READS + OFFLINE_SIGN, run.events)
        }

    @Test
    fun `a PosException from enqueue propagates and leaves QUEUED`() =
        scenario(
            TseMode.NORMAL,
            deviceUp = false,
            enqueueFailure = PosException(PosErrorCode.INTERNAL_ERROR, HttpStatusCode.InternalServerError, "queue down (test)"),
        ) { run ->
            val error = assertIs<PosException>(run.error, "settleAtTill does not catch a queue failure")
            assertEquals("queue down (test)", error.message)
            assertEquals(1, run.enqueued.size)
            assertEquals(TseSigningStatus.QUEUED, statusOf(run.invoiceId))
            assertTrue(queueRows().isEmpty())
            assertTrue(lateRows().isEmpty())
        }

    @Test
    fun `a RuntimeException from enqueue propagates and leaves QUEUED`() =
        scenario(TseMode.NORMAL, deviceUp = false, enqueueFailure = IllegalStateException("queue down (test)")) { run ->
            val error = assertIs<IllegalStateException>(run.error)
            assertEquals("queue down (test)", error.message)
            assertEquals(1, run.enqueued.size)
            assertEquals(TseSigningStatus.QUEUED, statusOf(run.invoiceId))
            assertTrue(queueRows().isEmpty())
        }

    @Test
    fun `a non-PosException from signing propagates before any status is written`() =
        scenario(TseMode.NORMAL, signFailure = IllegalStateException("device exploded (test)")) { run ->
            val error = assertIs<IllegalStateException>(run.error, "only a PosException from signing is caught")
            assertEquals("device exploded (test)", error.message)
            assertEquals(CONFIG_READS + ONLINE_SIGN, run.events)
            assertNoQueueWork(run)
            assertTrue(!linked(run.invoiceId))
            assertEquals(PHASE_ONE_STATUS, statusOf(run.invoiceId), "Phase 2 must not have written a status")
        }

    @Test
    fun `a not-operational PosException from the device queues with the TSE_NOT_OPERATIONAL cause`() =
        scenario(
            TseMode.NORMAL,
            signFailure = PosException(PosErrorCode.TSE_NOT_OPERATIONAL, HttpStatusCode.ServiceUnavailable, "not operational (test)"),
        ) { run ->
            assertNull(run.error, "${run.error}")
            val call = run.enqueued.single()
            assertEquals(TseLateSigningReason.TSE_NOT_OPERATIONAL, call.reason)
            assertEquals("TSE_NOT_OPERATIONAL: not operational (test)", call.reasonText)
            assertEquals(TseSigningStatus.QUEUED, call.statusAtCall)
            assertEquals(TseSigningStatus.PENDING, statusOf(run.invoiceId))
        }

    @Test
    fun `any other PosException from the device queues with the TSE_ERROR cause`() =
        scenario(
            TseMode.NORMAL,
            signFailure = PosException(PosErrorCode.INTERNAL_ERROR, HttpStatusCode.InternalServerError, "odd answer (test)"),
        ) { run ->
            assertNull(run.error, "${run.error}")
            val call = run.enqueued.single()
            assertEquals(TseLateSigningReason.TSE_ERROR, call.reason)
            assertEquals("INTERNAL_ERROR: odd answer (test)", call.reasonText)
            assertEquals(listOf(call.referenceId, "BILL", "TSE_ERROR", "INTERNAL_ERROR: odd answer (test)"), lateRows().single())
        }

    /**
     * No signature while the service reads an enabled mode (fix round 1 of the A3 review). The only
     * production route to a null `signTransaction` is the factory's DISABLED service: the factory reads
     * the global configuration itself, so a service that read NORMAL before Phase 1 still gets no
     * signature when the global read says DISABLED. The resolver maps that outcome to DISABLED in every
     * mode — nothing is linked, nothing is queued, the device is never asked.
     */
    @Test
    fun `no signature in NORMAL mode marks DISABLED and queues nothing`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val rig = Rig()
            val koin = isolatedKoin(rig)
            koin.loadModules(listOf(module { single<IShopConfigurationManager> { normalReadingConfig(rig) } }), allowOverride = true)
            try {
                setConfig(TseMode.DISABLED, TseFailureBehavior.QUEUE)
                val fixture = seedReceivable(koin)
                rig.events.clear()
                rig.enqueued.clear()
                val error =
                    try {
                        koin.get<SalesInvoiceService>().settleAtTill(
                            invoiceId = fixture.invoiceId,
                            request = SalesInvoiceDto.SettleAtTillRequestDto(fixture.cashTypeId, "80.00"),
                            employeeId = fixture.employeeId,
                            deviceId = DEVICE_ID,
                        )
                        null
                    } catch (e: Throwable) {
                        e
                    }
                val run =
                    Run(
                        fixture.invoiceId,
                        error,
                        synchronized(rig.events) { rig.events.toList() },
                        synchronized(rig.enqueued) { rig.enqueued.toList() },
                    )
                assertNull(run.error, "${run.error}")
                assertEquals(CONFIG_READS, run.events, "read once before Phase 1; the DISABLED service never asks the device")
                assertEquals(TseSigningStatus.DISABLED, statusOf(run.invoiceId))
                assertTrue(!linked(run.invoiceId), "no TSE transaction exists to link")
                assertNoQueueWork(run)
                assertEquals(0, BigDecimal.ZERO.compareTo(transaction { SalesInvoice.findById(run.invoiceId)!!.openAmount }))
            } finally {
                setConfig(TseMode.DISABLED, TseFailureBehavior.QUEUE)
                koin.close()
            }
        }

    // ── scenario driver ─────────────────────────────────────────────────────────────────────────

    private fun scenario(
        mode: TseMode,
        failure: TseFailureBehavior = TseFailureBehavior.QUEUE,
        deviceUp: Boolean = true,
        enqueueFailure: Throwable? = null,
        signFailure: Throwable? = null,
        check: suspend (Run) -> Unit,
    ) = testApp(setupData = { TestData.createShopInfo() }) { _ ->
        val rig = Rig()
        val koin = isolatedKoin(rig)
        try {
            setConfig(TseMode.DISABLED, TseFailureBehavior.QUEUE)
            val fixture = seedReceivable(koin)
            setConfig(mode, failure)
            rig.deviceUp = deviceUp
            rig.enqueueFailure = enqueueFailure
            rig.signFailure = signFailure
            rig.events.clear()
            rig.enqueued.clear()
            val error =
                try {
                    koin.get<SalesInvoiceService>().settleAtTill(
                        invoiceId = fixture.invoiceId,
                        request = SalesInvoiceDto.SettleAtTillRequestDto(fixture.cashTypeId, "80.00"),
                        employeeId = fixture.employeeId,
                        deviceId = DEVICE_ID,
                    )
                    null
                } catch (e: Throwable) {
                    e
                }
            val events = synchronized(rig.events) { rig.events.toList() }
            val enqueued = synchronized(rig.enqueued) { rig.enqueued.toList() }
            assertNotNull(transaction { Bill.find { BillTable.clearsSalesInvoiceId eq fixture.invoiceId }.firstOrNull() }, "Phase 1 committed")
            check(Run(fixture.invoiceId, error, events, enqueued))
        } finally {
            setConfig(TseMode.DISABLED, TseFailureBehavior.QUEUE)
            koin.close()
        }
    }

    private class Fixture(
        val invoiceId: Long,
        val employeeId: java.util.UUID,
        val cashTypeId: Long,
    )

    /** An ISSUED, fully-open 80.00 POS_RECEIVABLE invoice, built in DISABLED mode on the real path. */
    private suspend fun seedReceivable(koin: Koin): Fixture {
        val admin = TestData.createAdmin(employeeNumber = "SISC_A", username = "sisc_a")
        val transfer =
            TestData.createPaymentType("Ueberweisung_SISC", isCash = false).also {
                transaction { PaymentType.findById(it.id)!!.createsReceivable = true }
            }
        val cash = TestData.createPaymentType("Bar_SISC", isCash = true)
        val vat19 = TestData.createDefaultVatRates().first()
        val tws = TestData.createTableWithSession("SISC_1")
        val category = TestData.createCategory("SISC Cat")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "SISC Product",
                productNumber = "SISC_1",
                price = BigDecimal("80.0000"),
            )
        val itemId =
            transaction {
                val now = currentTimeStamp()
                Terminal.new {
                    this.deviceId = DEVICE_ID
                    this.clientId = CLIENT_ID
                    this.registeredAt = now
                    this.lastSeenAt = now
                    this.fiscal = true
                    this.tseClientRegistered = true
                    this.isActive = true
                }
                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = admin
                OrderItem
                    .new {
                        this.session = session
                        this.product = product
                        this.unitPrice = BigDecimal("80.00")
                        this.finalPrice = BigDecimal("80.00")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vat19
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }.id.value
            }
        val billId =
            koin
                .get<BillService>()
                .createBill(
                    employeeId = admin.id.value.toString(),
                    deviceId = DEVICE_ID,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = tws.session.id.value,
                            orderItems = listOf(itemId),
                            billAmount = "80.00",
                            tips = "0.00",
                            payments = listOf(PaymentDto.PaymentRequest("80.00", transfer.id.value)),
                        ),
                ).billId
        val invoices = koin.get<SalesInvoiceService>()
        val buyer = InvoiceBuyer(name = "Catering Nord GmbH", street = "Hafenweg 3", zip = "20457", city = "Hamburg")
        val draftId = invoices.createFromBill(billId, buyer, createdBy = null).id.value
        invoices.issue(draftId, issuedBy = admin.id.value)
        return Fixture(draftId, admin.id.value, cash.id.value)
    }

    private suspend fun setConfig(
        mode: TseMode,
        failure: TseFailureBehavior,
    ) {
        ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = mode, timeAdminPin = "86420", failureBehavior = failure))
    }

    // ── observations ────────────────────────────────────────────────────────────────────────────

    private fun clearingBill(invoiceId: Long): Bill = transaction { Bill.find { BillTable.clearsSalesInvoiceId eq invoiceId }.single() }

    private fun statusOf(invoiceId: Long): TseSigningStatus? = transaction { clearingBill(invoiceId).tseSigningStatus }

    private fun linked(invoiceId: Long): Boolean = transaction { clearingBill(invoiceId).tseTransaction != null }

    private fun assertNoQueueWork(run: Run) {
        assertTrue(run.enqueued.isEmpty(), "nothing may be queued: ${run.enqueued}")
        assertTrue(queueRows().isEmpty())
        assertTrue(lateRows().isEmpty())
    }

    private fun queueRows(): List<List<String>> =
        transaction {
            TseSigningQueueTable.selectAll().map {
                listOf(
                    it[TseSigningQueueTable.referenceId],
                    it[TseSigningQueueTable.entityType].name,
                    it[TseSigningQueueTable.processType],
                    it[TseSigningQueueTable.processData],
                    it[TseSigningQueueTable.clientId],
                )
            }
        }

    private fun lateRows(): List<List<String>> =
        transaction {
            TseLateSigningTable.selectAll().map {
                listOf(
                    it[TseLateSigningTable.referenceId],
                    it[TseLateSigningTable.entityType].name,
                    it[TseLateSigningTable.reasonCode],
                    it[TseLateSigningTable.reasonText],
                )
            }
        }

    // ── replaced bindings ───────────────────────────────────────────────────────────────────────

    private fun isolatedKoin(rig: Rig): Koin =
        koinApplication(createEagerInstances = false) {
            allowOverride(true)
            modules(
                serializationModule,
                httpClientModule,
                reposModule,
                servicesModule,
                module {
                    single<TseSignProvider> { device(rig) }
                    single { TseVorgangSigner(get(), get(), get()) }
                    single<TseServiceFactory> { TseServiceFactory(get(), get(), get()) }
                    single<IShopConfigurationManager> { recordingConfig(rig) }
                    single<TseSigningQueueService> {
                        RecordingQueue(
                            TseSigningQueueServiceImpl(
                                queueRepository = get(),
                                tseTransactionRepository = get(),
                                tseSignClient = get(),
                                transactionAuditService = get(),
                                signer = get(),
                            ),
                            rig,
                        )
                    }
                },
            )
        }.koin

    private fun recordingConfig(rig: Rig): IShopConfigurationManager =
        Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, args ->
            if (method.name == "getTseMode" || method.name == "getTseConfig") rig.events += "config.${method.name}"
            try {
                method.invoke(ShopConfigurationManager, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as IShopConfigurationManager

    /** Records like [recordingConfig], but reports NORMAL as the mode while the global configuration says DISABLED. */
    private fun normalReadingConfig(rig: Rig): IShopConfigurationManager =
        Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, args ->
            if (method.name == "getTseMode" || method.name == "getTseConfig") rig.events += "config.${method.name}"
            if (method.name == "getTseMode") return@newProxyInstance TseMode.NORMAL
            try {
                method.invoke(ShopConfigurationManager, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as IShopConfigurationManager

    private fun device(rig: Rig): TseSignProvider =
        Proxy.newProxyInstance(
            TseSignProvider::class.java.classLoader,
            arrayOf(TseSignProvider::class.java),
        ) { _, method, args ->
            when (method.name) {
                "toString" -> return@newProxyInstance "CharacterisationDevice"
                "hashCode" -> return@newProxyInstance 0
                "equals" -> return@newProxyInstance false
            }
            rig.events += "device.${method.name}"
            when (method.name) {
                "quickSign" ->
                    rig.signFailure?.let { throw it } ?: TseDto.SignResultDto(
                        success = true,
                        processType = args!![0] as String,
                        transactionNumber = 41L,
                        signature = "U0lH",
                        timestamp = 1_790_000_000L,
                        serialNumber = "AA".repeat(32),
                        signatureCounter = 82L,
                        signatureAlgorithm = "ecdsa-plain-SHA384",
                        startTimestamp = 1_790_000_000L,
                    )
                "checkHealth" ->
                    TseDto.HealthCheckResultDto(
                        healthy = rig.deviceUp,
                        initialized = rig.deviceUp,
                        validTime = rig.deviceUp,
                        passedSelfTest = rig.deviceUp,
                        remainingSignatures = 1_000L,
                        availableCapacity = 1_000L,
                    )
                "isConnected" -> rig.deviceUp
                "getCachedMode" -> "SIMULATION"
                else -> throw UnsupportedOperationException("TseSignProvider.${method.name} is not used here")
            }
        } as TseSignProvider

    private companion object {
        const val DEVICE_ID = "sisc-device"
        const val CLIENT_ID = "SISC-CLIENT"
        const val UNREACHABLE_TEXT = "TSE_UNREACHABLE: TSE Server is unavailable"

        /** Both TSE reads happen once, before Phase 1, hoisted out of the transaction. */
        val CONFIG_READS = listOf("config.getTseMode", "config.getTseConfig")

        val ONLINE_SIGN =
            listOf(
                "device.checkHealth",
                "device.isConnected",
                "device.getCachedMode",
                "device.isConnected",
                "device.getCachedMode",
                "device.quickSign",
            )

        /** An unreachable device: health check, connection probe, retry, then the signer gives up. */
        val OFFLINE_SIGN =
            listOf(
                "device.checkHealth",
                "device.isConnected",
                "device.isConnected",
                "device.checkHealth",
                "device.isConnected",
            )

        /** What Phase 1 leaves on the clearing bill before signing (the entity default, for the pending sweeper). */
        val PHASE_ONE_STATUS: TseSigningStatus? = TseSigningStatus.PENDING
    }
}
