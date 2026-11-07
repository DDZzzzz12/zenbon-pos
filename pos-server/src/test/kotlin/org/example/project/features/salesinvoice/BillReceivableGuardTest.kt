package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4c-1: the receivable is decided at settlement and frozen on the payment row, the invoice's
 * origin and money split are derived from it (§7), and a bill an issued invoice describes cannot be
 * reversed behind its back (§9a).
 *
 * Bills are built through `BillService.createBill` rather than by writing rows, so the freeze is
 * exercised on the real settlement path — writing `createsReceivable` by hand would test the
 * assertion instead of the code.
 */
@DisplayName("Receivable settlement, derived origin, and the storno guard")
class BillReceivableGuardTest {
    private val deviceId = "rcv-device"

    /** Reserved bill numbers leak across tests otherwise — same reason as BillTerminalLinkTest. */
    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private fun invoices() =
        SalesInvoiceServiceImpl(
            repository = SalesInvoiceRepositoryImpl(),
            numbers = ArNumberSequenceRepositoryImpl(),
            facts = BillInvoiceFactsGatherer(),
            pdf = GlobalContext.get().get(),
            billRepository = GlobalContext.get().get(),
            terminalRepository = GlobalContext.get().get(),
            saleSigning = GlobalContext.get().get(),
            configManager = GlobalContext.get().get(),
            allocationWriter = GlobalContext.get().get(),
        )

    private val buyer =
        InvoiceBuyer(name = "Catering Nord GmbH", street = "Hafenweg 3", zip = "20457", city = "Hamburg")

    private fun seedTerminal() =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@BillReceivableGuardTest.deviceId
                this.clientId = "RCV-CLIENT"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }

    /** One 80.00 @19% OPEN item on a fresh session; returns (itemId, sessionId). */
    private fun seedOrderItem(
        admin: Employee,
        suffix: String,
    ): Pair<Long, Long> {
        val vat19 = TestData.createDefaultVatRates().first()
        val tws = TestData.createTableWithSession("RCV_$suffix")
        val category = TestData.createCategory("RCV Cat $suffix")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "RCV Product $suffix",
                productNumber = "RCV_$suffix",
                price = BigDecimal("80.0000"),
            )
        val itemId =
            transaction {
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
        return itemId to tws.session.id.value
    }

    private fun receivableType(name: String): PaymentType =
        TestData.createPaymentType(name, isCash = false).also {
            transaction { PaymentType.findById(it.id)!!.createsReceivable = true }
        }

    /** Settles one 80.00 bill with the given legs and returns its id. */
    private fun settle(
        admin: Employee,
        suffix: String,
        legs: List<PaymentDto.PaymentRequest>,
    ): Long {
        val (itemId, sessionId) = seedOrderItem(admin, suffix)
        seedTerminal()
        return runBlocking {
            GlobalContext.get().get<BillService>().createBill(
                employeeId = admin.id.value.toString(),
                deviceId = deviceId,
                request =
                    BillDto.CreateBillRequestDto(
                        tableSessionId = sessionId,
                        orderItems = listOf(itemId),
                        billAmount = "80.00",
                        tips = "0.00",
                        payments = legs,
                    ),
            )
        }.billId
    }

    private fun issueInvoiceFor(
        billId: Long,
        issuer: Employee,
    ): Long {
        val draftId = runBlocking { invoices().createFromBill(billId, buyer, createdBy = null) }.id.value
        runBlocking { invoices().issue(draftId, issuedBy = issuer.id.value) }
        return draftId
    }

    @Test
    fun `a fully on-account settlement makes the invoice POS_RECEIVABLE and fully open`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "RCV_A", username = "rcv_a")
            val transfer = receivableType("Ueberweisung_A")
            val billId = settle(admin, "A", listOf(PaymentDto.PaymentRequest("80.00", transfer.id.value)))

            val invoiceId = issueInvoiceFor(billId, admin)

            transaction {
                val invoice = SalesInvoice.findById(invoiceId)!!
                assertEquals(RevenueOrigin.POS_RECEIVABLE, invoice.revenueOrigin)
                assertEquals(0, BigDecimal("80.00").compareTo(invoice.amountDue), "the whole total is owed")
                assertEquals(0, BigDecimal.ZERO.compareTo(invoice.prepaidAmount), "nothing was prepaid")
                assertEquals(0, BigDecimal("80.00").compareTo(invoice.openAmount), "open for the allocation engine")
                val note = invoice.notes.orEmpty()
                assertTrue(!note.contains("dankend erhalten"), "an open receivable must not be acknowledged as received: $note")
                assertTrue(note.contains("80,00 €") && note.contains("zur Zahlung fällig"), "the note must state the due amount: $note")
            }
        }

    @Test
    fun `the worked mixed tender prepays 30 and owes 50`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "RCV_B", username = "rcv_b")
            val cash = TestData.createPaymentType("Bar_B", isCash = true)
            val transfer = receivableType("Ueberweisung_B")
            val billId =
                settle(
                    admin,
                    "B",
                    listOf(
                        PaymentDto.PaymentRequest("30.00", cash.id.value),
                        PaymentDto.PaymentRequest("50.00", transfer.id.value),
                    ),
                )

            val invoiceId = issueInvoiceFor(billId, admin)

            transaction {
                val invoice = SalesInvoice.findById(invoiceId)!!
                assertEquals(RevenueOrigin.POS_RECEIVABLE, invoice.revenueOrigin)
                assertEquals(0, BigDecimal("30.00").compareTo(invoice.prepaidAmount), "the cash share is prepaid")
                assertEquals(0, BigDecimal("50.00").compareTo(invoice.amountDue), "only the receivable share is owed")
                assertEquals(
                    0,
                    invoice.taxInclusiveTotal!!.compareTo(invoice.prepaidAmount!! + invoice.amountDue!!),
                    "prepaid + due == total (BT-113 + BT-115)",
                )
                val note = invoice.notes.orEmpty()
                assertTrue(note.startsWith("Teilbetrag von 30,00 € dankend erhalten"), "only the cash share is received: $note")
                assertTrue(note.contains("offene Betrag von 50,00 €"), "the open share must be stated as due: $note")
            }
        }

    @Test
    fun `an ordinary cash settlement stays POS_SETTLED and owes nothing`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "RCV_C", username = "rcv_c")
            val cash = TestData.createPaymentType("Bar_C", isCash = true)
            val billId = settle(admin, "C", listOf(PaymentDto.PaymentRequest("80.00", cash.id.value)))

            val invoiceId = issueInvoiceFor(billId, admin)

            transaction {
                val invoice = SalesInvoice.findById(invoiceId)!!
                assertEquals(RevenueOrigin.POS_SETTLED, invoice.revenueOrigin)
                assertEquals(0, BigDecimal.ZERO.compareTo(invoice.openAmount), "case A opens nothing")
                assertTrue(invoice.notes.orEmpty().startsWith("Betrag dankend erhalten am "), "a paid bill keeps the thank-you note")
            }
        }

    @Test
    fun `the payment snapshot wins over a later edit of the payment type`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "RCV_D", username = "rcv_d")
            val transfer = receivableType("Ueberweisung_D")
            val billId = settle(admin, "D", listOf(PaymentDto.PaymentRequest("80.00", transfer.id.value)))

            // The shop decides tomorrow that this tender is no longer a receivable.
            transaction { PaymentType.findById(transfer.id)!!.createsReceivable = false }

            val draftId = runBlocking { invoices().createFromBill(billId, buyer, createdBy = null) }.id.value

            transaction {
                assertEquals(
                    RevenueOrigin.POS_RECEIVABLE,
                    SalesInvoice.findById(draftId)!!.revenueOrigin,
                    "the frozen payment flag decides, not the type's current value",
                )
            }
        }

    @Test
    fun `a bill whose invoice is issued cannot be storno'd, and the refusal names it`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "RCV_E", username = "rcv_e")
            val transfer = receivableType("Ueberweisung_E")
            val billId = settle(admin, "E", listOf(PaymentDto.PaymentRequest("80.00", transfer.id.value)))
            val invoiceId = issueInvoiceFor(billId, admin)
            val number = transaction { SalesInvoice.findById(invoiceId)!!.invoiceNumber }

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        GlobalContext.get().get<BillService>().deleteBill(
                            employeeId = admin.id.value.toString(),
                            deviceId = deviceId,
                            billId = billId.toString(),
                            deletingReason = "Versehen",
                        )
                    }
                }

            assertNotNull(number)
            assertTrue(
                error.message?.contains(number) == true,
                "the refusal must name the invoice to cancel first, was: ${error.message}",
            )
            transaction {
                assertNull(Bill.findById(billId)!!.deletingReason, "a refused storno leaves the bill untouched")
            }
        }

    @Test
    fun `a draft invoice does not block the storno and goes with it`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "RCV_F", username = "rcv_f")
            val cash = TestData.createPaymentType("Bar_F", isCash = true)
            val billId = settle(admin, "F", listOf(PaymentDto.PaymentRequest("80.00", cash.id.value)))
            val draftId = runBlocking { invoices().createFromBill(billId, buyer, createdBy = null) }.id.value

            runBlocking {
                GlobalContext.get().get<BillService>().deleteBill(
                    employeeId = admin.id.value.toString(),
                    deviceId = deviceId,
                    billId = billId.toString(),
                    deletingReason = "Versehen",
                )
            }

            transaction {
                assertNull(SalesInvoice.findById(draftId), "a draft consumed no number and goes with the bill")
            }
        }
}
