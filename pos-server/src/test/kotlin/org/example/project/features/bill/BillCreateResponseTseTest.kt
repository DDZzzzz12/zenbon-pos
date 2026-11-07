package org.example.project.features.bill

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.data.service.BillServiceImpl
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.repository.TseLateSigningRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseSaleSigningGateway
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * E2E round 4, R4-2: `POST /api/v1/bills` answered with the DTO built *before* signing, so an
 * online-signed sale came back with `tseTransactionId: null` while the database held the
 * transaction — and the till renders its receipt dialog, QR code included, from that reply.
 *
 * The harness signs for real: SIMULATION mode through the production [TseVorgangSigner], with only
 * the device replaced. The default test app never signs a bill (its TSE is DISABLED), which would
 * leave the reply's null indistinguishable from a bill with no signature at all.
 */
@DisplayName("createBill — the reply carries the signature the bill was given")
class BillCreateResponseTseTest {
    private val deviceId = "resp-tse-device"

    @AfterEach
    fun reset() {
        BillNumberTracker.clearAll()
    }

    /** A device that quick-signs, which is all SIMULATION asks of it. */
    private val simulatingDevice: TseSignProvider =
        Proxy.newProxyInstance(
            TseSignProvider::class.java.classLoader,
            arrayOf(TseSignProvider::class.java),
        ) { _, method, args ->
            when (method.name) {
                "quickSign" ->
                    TseDto.SignResultDto(
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
                        healthy = true,
                        initialized = true,
                        validTime = true,
                        passedSelfTest = true,
                        remainingSignatures = 1_000L,
                        availableCapacity = 1_000L,
                    )
                "isConnected" -> true
                "getCachedMode" -> "SIMULATION"
                "toString" -> "SimulatingDevice"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("TseSignProvider.${method.name} is not used here")
            }
        } as TseSignProvider

    private fun billService(): BillServiceImpl {
        val koin = GlobalContext.get()
        return BillServiceImpl(
            repository = koin.get(),
            tableSessionRepository = koin.get(),
            printerService = koin.get(),
            saleSigning =
                TseSaleSigningGateway(
                    tseServiceFactory =
                        TseServiceFactory(
                            TseTransactionRepositoryImpl(),
                            simulatingDevice,
                            TseVorgangSigner(simulatingDevice, TseTransactionRepositoryImpl(), koin.get()),
                        ),
                    tseSigningQueueService = koin.get(),
                    configManager = ShopConfigurationManager,
                    lateSigningRepository = TseLateSigningRepositoryImpl(),
                ),
            terminalRepository = koin.get(),
            configManager = ShopConfigurationManager,
            tableRepository = koin.get(),
            invoices = SalesInvoiceBillInvoiceLink(),
        )
    }

    @Test
    fun `the reply names the TSE transaction stored on the bill`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "RESP_A", username = "resp_a")
            val cash = TestData.createPaymentType("Bar_RESP", isCash = true)
            val vat19 = TestData.createDefaultVatRates().first()
            val tws = TestData.createTableWithSession("RESP_1")
            val category = TestData.createCategory("RESP Cat")
            val product =
                TestData.createProduct(
                    categoryId = category.id.value,
                    productName = "RESP Product",
                    productNumber = "RESP_1",
                    price = BigDecimal("12.0000"),
                )
            val itemId =
                transaction {
                    val now = currentTimeStamp()
                    Terminal.new {
                        this.deviceId = this@BillCreateResponseTseTest.deviceId
                        this.clientId = "RESP-CLIENT"
                        this.registeredAt = now
                        this.lastSeenAt = now
                    }
                    val session = TableSession.findById(tws.session.id)!!
                    session.employeeId = admin
                    OrderItem
                        .new {
                            this.session = session
                            this.product = product
                            this.unitPrice = BigDecimal("12.00")
                            this.finalPrice = BigDecimal("12.00")
                            this.itemStatus = ItemStatus.OPEN
                            this.vat = vat19
                            this.discount = BigDecimal.ONE
                            this.customPrice = false
                            this.takeAway = false
                            this.transferred = false
                            this.sideNotes = null
                        }.id.value
                }
            runBlocking {
                ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.SIMULATION, timeAdminPin = "86420"))
            }

            try {
                val reply =
                    runBlocking {
                        billService().createBill(
                            employeeId = admin.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = tws.session.id.value,
                                    orderItems = listOf(itemId),
                                    billAmount = "12.00",
                                    tips = "0.00",
                                    payments = listOf(PaymentDto.PaymentRequest("12.00", cash.id.value)),
                                ),
                        )
                    }

                val stored =
                    transaction {
                        Bill
                            .findById(reply.billId)!!
                            .tseTransaction
                            ?.id
                            ?.value
                    }
                assertNotNull(stored, "the harness must sign the bill, or this proves nothing")
                assertEquals(stored, reply.tseTransactionId, "the reply must carry the transaction the bill was signed with")
            } finally {
                runBlocking {
                    ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "86420"))
                }
            }
        }
}
