package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
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
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.order.domain.service.OrderService
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
 * Pins how the four sale-signing call sites — `createOrder`, `cancelOrderItems`, `createBill` and the
 * Storno Beleg of `deleteBill` — sign their Vorgang after the Phase-1 commit: which status the entity
 * gets, whether the TSE transaction is linked, the Nachsignierung queue row and its permanent cause,
 * what happens when queueing itself fails, the FAIL-mode rejection and the bill's late-signing note.
 *
 * The services come from the **production Koin modules**, resolved in an isolated application, so the
 * test does not depend on their constructors. Three bindings are replaced underneath them, never the
 * services themselves:
 * - the TSE device ([TseSignProvider]): it quick-signs, or is unreachable;
 * - [IShopConfigurationManager]: delegates to [ShopConfigurationManager] and records the TSE reads;
 * - [TseSigningQueueService]: the production implementation behind a recorder that can fail.
 *
 * The device always reports the SIMULATION path to the signer, so NORMAL mode signs through
 * quick-sign too; the status still follows the configured mode, which is what the services decide.
 */
@DisplayName("Sale signing at the order, cancellation and bill call sites (characterisation)")
class SaleSigningCharacterisationTest {
    private enum class Site { CREATE_ORDER, CANCEL_ITEMS, CREATE_BILL, STORNO_BILL }

    /** One recorded `enqueue`, with the entity's signing status as the caller left it at that moment. */
    private data class EnqueueCall(
        val referenceId: String,
        val entityType: TseEntityType,
        val processType: String,
        val processData: String,
        val clientId: String,
        val reason: TseLateSigningReason,
        val reasonText: String,
        val statusAtCall: List<TseSigningStatus?>,
    )

    /** What the replaced bindings observe and how they behave. */
    private class Rig {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val enqueued: MutableList<EnqueueCall> = Collections.synchronizedList(mutableListOf())

        @Volatile var deviceUp = true

        @Volatile var enqueueFailure: Throwable? = null

        /** Thrown by the device's quick-sign: a signing error that is not a [PosException]. */
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
            rig.enqueued +=
                EnqueueCall(
                    referenceId,
                    entityType,
                    processType,
                    processData,
                    clientId,
                    reason,
                    reasonText,
                    statusesOf(entityType, referenceId),
                )
            rig.enqueueFailure?.let { throw it }
            return real.enqueue(referenceId, entityType, processType, processData, clientId, reason, reasonText, openTseTransactionNumber)
        }
    }

    /** The fixture one scenario runs against. */
    private class Fixture(
        val employeeId: String,
        val sessionId: Long,
        val tableId: Long,
        val productId: Long,
        val itemIds: List<Long>,
        val paymentTypeId: Long,
    ) {
        var originalBillId: Long? = null
    }

    /** What one call-site invocation produced. */
    private class Run(
        val fixture: Fixture,
        val error: Throwable?,
        val billReply: BillDto.BillResponseDto?,
        val koin: Koin,
    )

    @AfterEach
    fun reset() {
        BillNumberTracker.clearAll()
    }

    // ── NORMAL signed ────────────────────────────────────────────────────────────────────────────

    @Test fun `createOrder NORMAL signed`() = signedOnline(Site.CREATE_ORDER, TseMode.NORMAL, TseSigningStatus.SIGNED)

    @Test fun `cancelOrderItems NORMAL signed`() = signedOnline(Site.CANCEL_ITEMS, TseMode.NORMAL, TseSigningStatus.SIGNED)

    @Test fun `createBill NORMAL signed`() = signedOnline(Site.CREATE_BILL, TseMode.NORMAL, TseSigningStatus.SIGNED)

    @Test fun `storno bill NORMAL signed`() = signedOnline(Site.STORNO_BILL, TseMode.NORMAL, TseSigningStatus.SIGNED)

    // ── SIMULATION signed ────────────────────────────────────────────────────────────────────────

    @Test fun `createOrder SIMULATION signed`() = signedOnline(Site.CREATE_ORDER, TseMode.SIMULATION, TseSigningStatus.SIMULATION)

    @Test fun `cancelOrderItems SIMULATION signed`() = signedOnline(Site.CANCEL_ITEMS, TseMode.SIMULATION, TseSigningStatus.SIMULATION)

    @Test fun `createBill SIMULATION signed`() = signedOnline(Site.CREATE_BILL, TseMode.SIMULATION, TseSigningStatus.SIMULATION)

    @Test fun `storno bill SIMULATION signed`() = signedOnline(Site.STORNO_BILL, TseMode.SIMULATION, TseSigningStatus.SIMULATION)

    // ── no signature (DISABLED) ──────────────────────────────────────────────────────────────────

    @Test fun `createOrder no signature`() = noSignature(Site.CREATE_ORDER)

    @Test fun `cancelOrderItems no signature`() = noSignature(Site.CANCEL_ITEMS)

    @Test fun `createBill no signature`() = noSignature(Site.CREATE_BILL)

    @Test fun `storno bill no signature`() = noSignature(Site.STORNO_BILL)

    // ── failed + QUEUE ───────────────────────────────────────────────────────────────────────────

    @Test fun `createOrder failed and queued`() = failedAndQueued(Site.CREATE_ORDER)

    @Test fun `cancelOrderItems failed and queued`() = failedAndQueued(Site.CANCEL_ITEMS)

    @Test fun `createBill failed and queued`() = failedAndQueued(Site.CREATE_BILL)

    @Test fun `storno bill failed and queued`() = failedAndQueued(Site.STORNO_BILL)

    // ── failed + QUEUE, enqueue throws a PosException ────────────────────────────────────────────

    @Test fun `createOrder enqueue PosException leaves PENDING`() = enqueuePosExceptionLeavesPending(Site.CREATE_ORDER)

    @Test fun `cancelOrderItems enqueue PosException leaves PENDING`() = enqueuePosExceptionLeavesPending(Site.CANCEL_ITEMS)

    @Test fun `createBill enqueue PosException leaves PENDING`() = enqueuePosExceptionLeavesPending(Site.CREATE_BILL)

    @Test fun `storno bill enqueue PosException leaves PENDING`() = enqueuePosExceptionLeavesPending(Site.STORNO_BILL)

    // ── failed + QUEUE, enqueue throws something else: the call sites differ ─────────────────────

    @Test fun `createOrder enqueue RuntimeException propagates`() = enqueueRuntimeExceptionPropagates(Site.CREATE_ORDER)

    @Test fun `cancelOrderItems enqueue RuntimeException is swallowed and leaves PENDING`() = enqueueRuntimeExceptionSwallowed(Site.CANCEL_ITEMS)

    @Test fun `createBill enqueue RuntimeException propagates`() = enqueueRuntimeExceptionPropagates(Site.CREATE_BILL)

    @Test fun `storno bill enqueue RuntimeException propagates`() = enqueueRuntimeExceptionPropagates(Site.STORNO_BILL)

    // ── failed + FAIL ────────────────────────────────────────────────────────────────────────────

    @Test fun `createOrder failed in FAIL mode`() = failedInFailMode(Site.CREATE_ORDER)

    @Test fun `cancelOrderItems failed in FAIL mode`() = failedInFailMode(Site.CANCEL_ITEMS)

    @Test fun `createBill failed in FAIL mode`() = failedInFailMode(Site.CREATE_BILL)

    @Test fun `storno bill failed in FAIL mode`() = failedInFailMode(Site.STORNO_BILL)

    // ── signing throws something other than a PosException: only PosException is caught ──────────

    @Test fun `createOrder non-PosException from signing propagates`() = nonPosSigningErrorPropagates(Site.CREATE_ORDER)

    @Test fun `cancelOrderItems non-PosException from signing propagates`() = nonPosSigningErrorPropagates(Site.CANCEL_ITEMS)

    @Test fun `createBill non-PosException from signing propagates`() = nonPosSigningErrorPropagates(Site.CREATE_BILL)

    @Test fun `storno bill non-PosException from signing propagates`() = nonPosSigningErrorPropagates(Site.STORNO_BILL)

    // ── bill late-signing note ───────────────────────────────────────────────────────────────────

    @Test
    fun `createBill reply and detail carry the late-signing note`() =
        scenario(Site.CREATE_BILL, TseMode.NORMAL, deviceUp = false) { run ->
            assertNull(run.error)
            val reply = assertNotNull(run.billReply)
            val note = assertNotNull(reply.tseSigningNote, "a queued bill's reply must say why")
            assertTrue(note.pending)
            assertEquals("TSE_UNAVAILABLE", note.reasonCode)
            assertEquals(UNREACHABLE_TEXT, note.reasonText)
            assertNull(reply.tseTransactionId)
            val detail = run.koin.get<BillService>().getBillById(reply.billId.toString())
            assertEquals(note, detail.tseSigningNote)
        }

    @Test
    fun `storno bill detail carries the late-signing note`() =
        scenario(Site.STORNO_BILL, TseMode.NORMAL, deviceUp = false) { run ->
            assertNull(run.error)
            val reversalId = reversalOf(run.fixture).toString()
            val note =
                assertNotNull(
                    run.koin
                        .get<BillService>()
                        .getBillById(reversalId)
                        .tseSigningNote,
                )
            assertTrue(note.pending)
            assertEquals("TSE_UNAVAILABLE", note.reasonCode)
            assertEquals(UNREACHABLE_TEXT, note.reasonText)
            val original = run.koin.get<BillService>().getBillById(run.fixture.originalBillId.toString())
            assertNull(original.tseSigningNote, "the reversed bill itself was never queued")
        }

    @Test
    fun `createBill signed online carries no note and names its transaction`() =
        scenario(Site.CREATE_BILL, TseMode.NORMAL) { run ->
            val reply = assertNotNull(run.billReply)
            assertNull(reply.tseSigningNote)
            assertNotNull(reply.tseTransactionId)
            assertEquals(transactionIdOf(Site.CREATE_BILL, run.fixture), reply.tseTransactionId)
        }

    // ── order of the TSE reads ───────────────────────────────────────────────────────────────────

    @Test fun `createOrder reads mode then failure behaviour right before signing`() = readOrder(Site.CREATE_ORDER)

    @Test fun `cancelOrderItems reads mode then failure behaviour right before signing`() = readOrder(Site.CANCEL_ITEMS)

    @Test fun `createBill reads mode then failure behaviour right before signing`() = readOrder(Site.CREATE_BILL)

    @Test fun `storno bill reads mode then failure behaviour right before signing`() = readOrder(Site.STORNO_BILL)

    // ── case bodies ──────────────────────────────────────────────────────────────────────────────

    private fun signedOnline(
        site: Site,
        mode: TseMode,
        expected: TseSigningStatus,
    ) = scenario(site, mode) { run ->
        assertNull(run.error, "an online-signed Vorgang must not fail: ${run.error}")
        assertStatuses(expected, site, run.fixture)
        assertTrue(linkedOf(site, run.fixture).all { it }, "the TSE transaction must be linked")
        assertNoQueueWork(run)
    }

    private fun noSignature(site: Site) =
        scenario(site, TseMode.DISABLED) { run ->
            assertNull(run.error)
            assertStatuses(TseSigningStatus.DISABLED, site, run.fixture)
            assertTrue(linkedOf(site, run.fixture).none { it }, "no TSE transaction exists to link")
            assertNoQueueWork(run)
        }

    private fun failedAndQueued(site: Site) =
        scenario(site, TseMode.NORMAL, deviceUp = false) { run ->
            assertNull(run.error, "QUEUE mode must accept the Vorgang: ${run.error}")
            val call = run.enqueuedOnce()
            assertEquals(queueReferenceOf(site, run.fixture), call.referenceId)
            assertEquals(entityTypeOf(site), call.entityType)
            assertEquals(processTypeOf(site), call.processType)
            assertEquals(CLIENT_ID, call.clientId)
            assertTrue(call.processData.isNotBlank())
            assertEquals(TseLateSigningReason.TSE_UNAVAILABLE, call.reason)
            assertEquals(UNREACHABLE_TEXT, call.reasonText)
            assertTrue(
                call.statusAtCall.isNotEmpty() &&
                    call.statusAtCall.all {
                        it == TseSigningStatus.QUEUED
                    },
                "the caller writes QUEUED before queueing: ${call.statusAtCall}",
            )
            assertTrue(linkedOf(site, run.fixture).none { it })

            val rows = queueRows()
            assertEquals(1, rows.size, "exactly one queue row: $rows")
            val row = rows.single()
            assertEquals(call.referenceId, row.referenceId)
            assertEquals(call.entityType, row.entityType)
            assertEquals(call.processType, row.processType)
            assertEquals(call.processData, row.processData)
            assertEquals(CLIENT_ID, row.clientId)

            val late = lateRows().single()
            assertEquals(call.referenceId, late.referenceId)
            assertEquals(call.entityType, late.entityType)
            assertEquals("TSE_UNAVAILABLE", late.reasonCode)
            assertEquals(UNREACHABLE_TEXT, late.reasonText)

            // The queue marks the queued entity PENDING itself.
            assertStatuses(TseSigningStatus.PENDING, site, run.fixture)
        }

    private fun enqueuePosExceptionLeavesPending(site: Site) =
        scenario(
            site,
            TseMode.NORMAL,
            deviceUp = false,
            enqueueFailure = PosException(PosErrorCode.INTERNAL_ERROR, HttpStatusCode.InternalServerError, "queue down (test)"),
        ) { run ->
            assertNull(run.error, "a failed enqueue must not fail the Vorgang: ${run.error}")
            val call = run.enqueuedOnce()
            assertTrue(call.statusAtCall.all { it == TseSigningStatus.QUEUED })
            assertStatuses(TseSigningStatus.PENDING, site, run.fixture)
            assertTrue(queueRows().isEmpty())
            assertTrue(lateRows().isEmpty())
        }

    private fun enqueueRuntimeExceptionPropagates(site: Site) =
        scenario(site, TseMode.NORMAL, deviceUp = false, enqueueFailure = IllegalStateException("queue down (test)")) { run ->
            val error = assertIs<IllegalStateException>(run.error, "only a PosException from enqueue is caught here")
            assertEquals("queue down (test)", error.message)
            run.enqueuedOnce()
            assertStatuses(TseSigningStatus.QUEUED, site, run.fixture)
            assertTrue(queueRows().isEmpty())
        }

    private fun enqueueRuntimeExceptionSwallowed(site: Site) =
        scenario(site, TseMode.NORMAL, deviceUp = false, enqueueFailure = IllegalStateException("queue down (test)")) { run ->
            assertNull(run.error, "the cancellation catches every exception from enqueue: ${run.error}")
            run.enqueuedOnce()
            assertStatuses(TseSigningStatus.PENDING, site, run.fixture)
            assertTrue(queueRows().isEmpty())
        }

    private fun failedInFailMode(site: Site) =
        scenario(site, TseMode.NORMAL, failure = TseFailureBehavior.FAIL, deviceUp = false) { run ->
            val error = assertIs<PosException>(run.error, "FAIL mode must reject the Vorgang")
            assertEquals(PosErrorCode.TSE_SIGNING_FAILED, error.code)
            assertEquals(HttpStatusCode.ServiceUnavailable, error.httpStatus)
            assertEquals(failMessageOf(site, run.fixture), error.message)
            assertTrue(run.enqueuedCalls().isEmpty())
            assertTrue(queueRows().isEmpty())
            assertTrue(lateRows().isEmpty())
            when (site) {
                Site.CREATE_ORDER -> {
                    assertStatuses(TseSigningStatus.FAILED, site, run.fixture)
                    assertEquals(OrderStatus.CANCELLED, transaction { orderOf(run.fixture).orderStatus })
                }
                Site.CANCEL_ITEMS ->
                    transaction {
                        assertTrue(run.fixture.itemIds.all { OrderItem.findById(it)!!.itemStatus == ItemStatus.OPEN }, "items reverted to OPEN")
                        assertTrue(Cancellation.all().empty(), "cancellation rows deleted")
                    }
                Site.CREATE_BILL, Site.STORNO_BILL -> assertStatuses(TseSigningStatus.FAILED, site, run.fixture)
            }
        }

    /**
     * A non-[PosException] from the signer is not a TSE failure outcome: it escapes the service call
     * unchanged, before any status is written, so nothing is queued and the Vorgang is neither marked
     * signed nor queued (it keeps the status Phase 1 committed, for the pending sweeper).
     */
    private fun nonPosSigningErrorPropagates(site: Site) =
        scenario(site, TseMode.NORMAL, signFailure = IllegalStateException("device exploded (test)")) { run ->
            val error = assertIs<IllegalStateException>(run.error, "only a PosException from signing is caught")
            assertEquals("device exploded (test)", error.message)
            assertTrue("device.quickSign" in run.events, "the device must have been asked to sign: ${run.events}")
            assertNoQueueWork(run)
            assertTrue(linkedOf(site, run.fixture).none { it }, "no TSE transaction may be linked")
            val statuses = statusesOf(site, run.fixture)
            val settled =
                setOf(TseSigningStatus.SIGNED, TseSigningStatus.SIMULATION, TseSigningStatus.QUEUED, TseSigningStatus.DISABLED, TseSigningStatus.FAILED)
            assertTrue(statuses.isNotEmpty() && statuses.none { it in settled }, "Phase 2 must not have written a status: $statuses")
        }

    private fun readOrder(site: Site) =
        scenario(site, TseMode.SIMULATION) { run ->
            assertNull(run.error)
            val events = run.events
            val firstDevice = events.indexOfFirst { it.startsWith("device.") }
            assertTrue(firstDevice >= 2, "events: $events")
            assertEquals(
                listOf("config.getTseMode", "config.getTseConfig"),
                events.subList(firstDevice - 2, firstDevice),
                "Phase 2 reads the mode, then the failure behaviour, then signs: $events",
            )
            assertEquals(
                listOf(
                    "device.checkHealth",
                    "device.isConnected",
                    "device.getCachedMode",
                    "device.isConnected",
                    "device.getCachedMode",
                    "device.quickSign",
                ),
                events.drop(firstDevice),
                "nothing reads the TSE config after signing, and nothing is queued: $events",
            )
        }

    // ── scenario driver ──────────────────────────────────────────────────────────────────────────

    private fun scenario(
        site: Site,
        mode: TseMode,
        failure: TseFailureBehavior = TseFailureBehavior.QUEUE,
        deviceUp: Boolean = true,
        enqueueFailure: Throwable? = null,
        signFailure: Throwable? = null,
        check: suspend (RunWithRig) -> Unit,
    ) = testApp(setupData = { TestData.createShopInfo() }) { _ ->
        val rig = Rig()
        val koin = isolatedKoin(rig)
        try {
            val fixture = seed(site)
            if (site == Site.STORNO_BILL) {
                setConfig(TseMode.DISABLED, TseFailureBehavior.QUEUE)
                fixture.originalBillId = createBill(koin, fixture).billId
            }
            setConfig(mode, failure)
            rig.deviceUp = deviceUp
            rig.enqueueFailure = enqueueFailure
            rig.signFailure = signFailure
            rig.events.clear()
            rig.enqueued.clear()
            var reply: BillDto.BillResponseDto? = null
            val error =
                try {
                    reply = invoke(site, koin, fixture)
                    null
                } catch (e: Throwable) {
                    e
                }
            // Proves the service under test was built in this application, with the recording
            // bindings, and not shared from the global one.
            assertTrue("config.getTseConfig" in synchronized(rig.events) { rig.events.toList() }, "the service did not read the recording config")
            check(RunWithRig(Run(fixture, error, reply, koin), rig))
        } finally {
            setConfig(TseMode.DISABLED, TseFailureBehavior.QUEUE)
            koin.close()
        }
    }

    /** A [Run] plus the rig's recordings, frozen at the end of the call. */
    private class RunWithRig(
        run: Run,
        private val rig: Rig,
    ) {
        val fixture = run.fixture
        val error = run.error
        val billReply = run.billReply
        val koin = run.koin
        val events: List<String> = synchronized(rig.events) { rig.events.toList() }

        fun enqueuedCalls(): List<EnqueueCall> = synchronized(rig.enqueued) { rig.enqueued.toList() }

        fun enqueuedOnce(): EnqueueCall {
            val calls = enqueuedCalls()
            assertEquals(1, calls.size, "exactly one enqueue: $calls")
            return calls.single()
        }
    }

    private suspend fun invoke(
        site: Site,
        koin: Koin,
        fixture: Fixture,
    ): BillDto.BillResponseDto? =
        when (site) {
            Site.CREATE_ORDER -> {
                koin.get<OrderService>().createOrder(
                    request =
                        OrderDto.CreateOrderRequestDto(
                            tableInfoId = fixture.sessionId,
                            tableId = fixture.tableId,
                            orderItems =
                                listOf(
                                    OrderItemDto.CreateOrderItemRequestDto(
                                        productId = fixture.productId,
                                        unitPrice = "6.0",
                                        quantity = 1,
                                        discount = 1.0,
                                    ),
                                ),
                        ),
                    employeeId = fixture.employeeId,
                    deviceId = DEVICE_ID,
                    actorCapabilities = emptySet(),
                )
                null
            }
            Site.CANCEL_ITEMS -> {
                koin.get<OrderItemService>().cancelOrderItems(
                    item = fixture.itemIds,
                    employeeId = fixture.employeeId,
                    deviceId = DEVICE_ID,
                    reason = "characterisation",
                    sourceTableId = fixture.tableId,
                )
                null
            }
            Site.CREATE_BILL -> createBill(koin, fixture)
            Site.STORNO_BILL -> {
                koin.get<BillService>().deleteBill(
                    employeeId = fixture.employeeId,
                    deviceId = DEVICE_ID,
                    billId = fixture.originalBillId.toString(),
                    deletingReason = "characterisation",
                )
                null
            }
        }

    private suspend fun createBill(
        koin: Koin,
        fixture: Fixture,
    ): BillDto.BillResponseDto =
        koin.get<BillService>().createBill(
            employeeId = fixture.employeeId,
            deviceId = DEVICE_ID,
            request =
                BillDto.CreateBillRequestDto(
                    tableSessionId = fixture.sessionId,
                    orderItems = fixture.itemIds,
                    billAmount = "12.00",
                    tips = "0.00",
                    payments = listOf(PaymentDto.PaymentRequest("12.00", fixture.paymentTypeId)),
                ),
        )

    private suspend fun setConfig(
        mode: TseMode,
        failure: TseFailureBehavior,
    ) {
        ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = mode, timeAdminPin = "86420", failureBehavior = failure))
    }

    private fun seed(site: Site): Fixture {
        val admin = TestData.createAdmin(employeeNumber = "SSC_A", username = "ssc_a")
        val cash = TestData.createPaymentType("Bar_SSC", isCash = true)
        val vat19 = TestData.createDefaultVatRates().first()
        val tws = TestData.createTableWithSession("SSC_1")
        val category = TestData.createCategory("SSC Cat")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "SSC Product",
                productNumber = "SSC_1",
                price = BigDecimal("6.0000"),
            )
        val itemIds =
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
                if (site == Site.CREATE_ORDER) {
                    emptyList()
                } else {
                    (1..2).map { n ->
                        OrderItem
                            .new {
                                this.session = session
                                this.product = product
                                this.unitPrice = BigDecimal("6.00")
                                this.finalPrice = BigDecimal("6.00")
                                this.itemStatus = ItemStatus.OPEN
                                this.vat = vat19
                                this.discount = BigDecimal.ONE
                                this.customPrice = false
                                this.takeAway = false
                                this.transferred = false
                                this.sideNotes = "line $n"
                            }.id.value
                    }
                }
            }
        return Fixture(
            employeeId = admin.id.value.toString(),
            sessionId = tws.session.id.value,
            tableId = tws.table.id.value,
            productId = product.id.value,
            itemIds = itemIds,
            paymentTypeId = cash.id.value,
        )
    }

    // ── observations ─────────────────────────────────────────────────────────────────────────────

    private fun orderOf(fixture: Fixture): Orders = Orders.find { OrdersTable.tableSessionId eq fixture.sessionId }.single()

    private fun cancellationsOf(fixture: Fixture): List<Cancellation> =
        fixture.itemIds.map { id -> Cancellation.find { CancellationTable.orderItemId eq id }.single() }

    private fun billOf(fixture: Fixture): Bill = Bill.find { BillTable.tableSession eq fixture.sessionId }.single { it.deletedBill == null }

    private fun reversalOf(fixture: Fixture): Long =
        transaction {
            Bill
                .find { BillTable.deletedBillId eq fixture.originalBillId!! }
                .single()
                .id.value
        }

    private fun statusesOf(
        site: Site,
        fixture: Fixture,
    ): List<TseSigningStatus?> =
        transaction {
            when (site) {
                Site.CREATE_ORDER -> listOf(orderOf(fixture).tseSigningStatus)
                Site.CANCEL_ITEMS -> cancellationsOf(fixture).map { it.tseSigningStatus }
                Site.CREATE_BILL -> listOf(billOf(fixture).tseSigningStatus)
                Site.STORNO_BILL -> listOf(Bill.findById(reversalOf(fixture))!!.tseSigningStatus)
            }
        }

    private fun linkedOf(
        site: Site,
        fixture: Fixture,
    ): List<Boolean> =
        transaction {
            when (site) {
                Site.CREATE_ORDER -> listOf(orderOf(fixture).tseTransaction != null)
                Site.CANCEL_ITEMS -> cancellationsOf(fixture).map { it.tseTransaction != null }
                Site.CREATE_BILL -> listOf(billOf(fixture).tseTransaction != null)
                Site.STORNO_BILL -> listOf(Bill.findById(reversalOf(fixture))!!.tseTransaction != null)
            }
        }

    private fun transactionIdOf(
        site: Site,
        fixture: Fixture,
    ): Long? =
        transaction {
            when (site) {
                Site.CREATE_BILL -> billOf(fixture).tseTransaction?.id?.value
                else -> error("only bills are asked")
            }
        }

    private fun queueReferenceOf(
        site: Site,
        fixture: Fixture,
    ): String =
        transaction {
            when (site) {
                Site.CREATE_ORDER -> orderOf(fixture).id.value.toString()
                Site.CANCEL_ITEMS -> cancellationsOf(fixture).map { it.batchId }.toSet().single()!!
                Site.CREATE_BILL -> billOf(fixture).id.value.toString()
                Site.STORNO_BILL -> reversalOf(fixture).toString()
            }
        }

    private fun entityTypeOf(site: Site): TseEntityType =
        when (site) {
            Site.CREATE_ORDER -> TseEntityType.ORDER
            Site.CANCEL_ITEMS -> TseEntityType.CANCELLATION
            Site.CREATE_BILL, Site.STORNO_BILL -> TseEntityType.BILL
        }

    private fun processTypeOf(site: Site): String =
        when (site) {
            Site.CREATE_ORDER, Site.CANCEL_ITEMS -> "Bestellung-V1"
            Site.CREATE_BILL, Site.STORNO_BILL -> "Kassenbeleg-V1"
        }

    private fun failMessageOf(
        site: Site,
        fixture: Fixture,
    ): String =
        transaction {
            when (site) {
                Site.CREATE_ORDER -> "TSE signing failed for order ${orderOf(fixture).orderNumber}"
                Site.CANCEL_ITEMS -> "TSE signing failed for cancellation of items ${fixture.itemIds}"
                Site.CREATE_BILL -> "TSE signing failed for bill ${billOf(fixture).billNumber}"
                Site.STORNO_BILL -> "TSE signing failed for reversal bill ${Bill.findById(reversalOf(fixture))!!.billNumber}"
            }
        }

    private fun assertStatuses(
        expected: TseSigningStatus,
        site: Site,
        fixture: Fixture,
    ) {
        val actual = statusesOf(site, fixture)
        assertTrue(actual.isNotEmpty() && actual.all { it == expected }, "expected all $expected, got $actual")
    }

    private fun assertNoQueueWork(run: RunWithRig) {
        assertTrue(run.enqueuedCalls().isEmpty(), "nothing may be queued: ${run.enqueuedCalls()}")
        assertTrue(queueRows().isEmpty())
        assertTrue(lateRows().isEmpty())
    }

    private data class QueueRow(
        val referenceId: String,
        val entityType: TseEntityType,
        val processType: String,
        val processData: String,
        val clientId: String,
    )

    private data class LateRow(
        val referenceId: String,
        val entityType: TseEntityType,
        val reasonCode: String,
        val reasonText: String,
    )

    private fun queueRows(): List<QueueRow> =
        transaction {
            TseSigningQueueTable.selectAll().map {
                QueueRow(
                    it[TseSigningQueueTable.referenceId],
                    it[TseSigningQueueTable.entityType],
                    it[TseSigningQueueTable.processType],
                    it[TseSigningQueueTable.processData],
                    it[TseSigningQueueTable.clientId],
                )
            }
        }

    private fun lateRows(): List<LateRow> =
        transaction {
            TseLateSigningTable.selectAll().map {
                LateRow(
                    it[TseLateSigningTable.referenceId],
                    it[TseLateSigningTable.entityType],
                    it[TseLateSigningTable.reasonCode],
                    it[TseLateSigningTable.reasonText],
                )
            }
        }

    // ── replaced bindings ────────────────────────────────────────────────────────────────────────

    private fun isolatedKoin(rig: Rig): Koin =
        koinApplication(createEagerInstances = false) {
            allowOverride(true)
            modules(
                serializationModule,
                httpClientModule,
                reposModule,
                servicesModule,
                module {
                    // A Koin module holds its singletons in the module object, so this application
                    // shares every instance the global test application already built (the eager TSE
                    // workers build the signer and the factory). Redeclaring them gives fresh ones.
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
        const val DEVICE_ID = "ssc-device"
        const val CLIENT_ID = "SSC-CLIENT"
        const val UNREACHABLE_TEXT = "TSE_UNREACHABLE: TSE Server is unavailable"

        /** The entity's signing status as stored right now, read in its own transaction. */
        fun statusesOf(
            type: TseEntityType,
            referenceId: String,
        ): List<TseSigningStatus?> =
            transaction {
                when (type) {
                    TseEntityType.ORDER -> listOf(Orders.findById(referenceId.toLong())?.tseSigningStatus)
                    TseEntityType.BILL -> listOf(Bill.findById(referenceId.toLong())?.tseSigningStatus)
                    TseEntityType.CANCELLATION -> Cancellation.find { CancellationTable.batchId eq referenceId }.map { it.tseSigningStatus }
                    else -> emptyList()
                }
            }
    }
}
