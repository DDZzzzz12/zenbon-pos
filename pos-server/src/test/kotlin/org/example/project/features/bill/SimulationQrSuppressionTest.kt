package org.example.project.features.bill

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.bill.mapper.toResponseDto
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * B2: in SIMULATION mode the TSE driver returns a fabricated signature —
 * `base64("SIM-<millis>-<tx>-<hash>")` — and the literal public key `base64("SIMULATED_PUBLIC_KEY")`
 * (`pos-tse/.../BaseTseSignOperation.kt` generateSimulatedSignResult).
 *
 * `BillMapper` built `tseQrCodeContent` whenever a transaction existed, with no mode guard, so
 * those fabricated values were printed on the customer's Beleg. Anhang I p. 122 makes the QR a
 * SUBSTITUTE for the readable §6 KassenSichV data, so a simulated QR asserts TSE protection that
 * does not exist — and directly contradicts the DSFinV-K export, which suppresses simulated
 * transactions and reports the same Vorgang as `TSE_TA_FEHLER = "TSE-Simulationsmodus"`.
 *
 * A Kassennachschau scanning that QR gets a failed Beleg-Verifikation and then finds the export
 * saying the Beleg was never secured. Emitting no QR is correct: an incomplete receipt is a
 * defect, a receipt carrying a fake signature is a false fiscal statement.
 */
@DisplayName("Receipt QR suppression in SIMULATION mode (B2)")
class SimulationQrSuppressionTest {
    private val deviceId = "sim-qr-device"

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    @Test
    fun `a SIMULATION-signed bill exposes no QR content`() =
        testApp(
            setupData = {
                val qr = billQrContentForMode(TseMode.SIMULATION, "SIMQR")
                assertNull(
                    qr,
                    "a fabricated SIM-… signature must never reach the printed Beleg — the export " +
                        "reports the same Vorgang as TSE_TA_FEHLER \"TSE-Simulationsmodus\"",
                )
            },
        ) { }

    @Test
    fun `a NORMAL-signed bill still exposes QR content`() =
        testApp(
            setupData = {
                val qr = billQrContentForMode(TseMode.NORMAL, "REALQR")
                assertNotNull(
                    qr,
                    "the guard must suppress ONLY simulated signatures; a real one still has to be " +
                        "printable, otherwise every receipt loses its §6 KassenSichV data",
                )
            },
        ) { }

    /**
     * Bills a one-item session through the real service, then attaches a fully-populated
     * [TseTransaction] in [mode] (every field [org.example.project.features.bill.mapper.toQrCodeDataDto]
     * requires must be non-null, or it returns null for reasons unrelated to the mode guard).
     * Returns the `tseQrCodeContent` the client would print.
     */
    private fun billQrContentForMode(
        mode: TseMode,
        suffix: String,
    ): String? {
        val admin = TestData.createAdmin(employeeNumber = "SQ_$suffix", username = "sq_$suffix")
        val vats = TestData.createDefaultVatRates()
        val cash = TestData.createPaymentType("Cash_$suffix", isCash = true)
        val tws = TestData.createTableWithSession("T_$suffix") // tableNumber is varchar(10)
        val category = TestData.createCategory("SimQr Cat $suffix")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "SimQr Product $suffix",
                productNumber = "SQ_$suffix",
                price = BigDecimal("10.0000"),
            )
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@SimulationQrSuppressionTest.deviceId + suffix
                this.clientId = "TERM-$suffix"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }

        val itemId =
            transaction {
                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = admin
                OrderItem
                    .new {
                        this.session = session
                        this.product = product
                        this.unitPrice = BigDecimal("10.00")
                        this.finalPrice = BigDecimal("10.00")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vats.first()
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }.id.value
            }

        val billService = GlobalContext.get().get<BillService>()
        val response =
            runBlocking {
                billService.createBill(
                    employeeId = admin.id.value.toString(),
                    deviceId = deviceId + suffix,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = tws.session.id.value,
                            orderItems = listOf(itemId),
                            billAmount = "10.00",
                            payments =
                                listOf(
                                    PaymentDto.PaymentRequest(amount = "10.00", paymentTypeId = cash.id.value),
                                ),
                        ),
                )
            }

        return transaction {
            val bill = Bill.findById(response.billId)!!
            bill.tseTransaction =
                TseTransaction.new {
                    this.tseSerialNumber = "SN-$suffix"
                    this.transactionNumber = 77L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar"
                    this.signature = "c2lnbmF0dXJl"
                    this.signatureCounter = 4131L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.publicKey = "cHVibGlj"
                    this.clientId = "TERM-$suffix"
                    this.startTime = currentTimeStamp()
                    this.endTime = currentTimeStamp()
                    this.tseStartTimestamp = 1_574_422_188L
                    this.tseTimestamp = 1_574_422_189L
                    this.tseMode = mode
                }
            bill.toResponseDto(SalesInvoiceBillInvoiceLink()).tseQrCodeContent
        }
    }
}
