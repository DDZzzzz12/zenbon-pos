package org.example.project.features.salesinvoice

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceService
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
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
 * Slice 4c-2c: a receivable opened at the till (`POS_RECEIVABLE`) is cleared at the counter by
 * `settleAtTill`, which writes one TSE-signed clearing [Bill] carrying no order items and no VAT
 * detail, its single payment leg, and the `TILL_RECEIPT` allocation that drops `open_amount`.
 *
 * Bills are built through `BillService.createBill`, like [BillReceivableGuardTest], so the settlement
 * that opens the receivable runs on the real path rather than being written by hand.
 */
@DisplayName("Clearing a till receivable at the counter")
class TillClearingTest {
    private val deviceId = "till-clear-device"

    /** Reserved bill numbers leak across tests otherwise — same reason as BillReceivableGuardTest. */
    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private val buyer =
        InvoiceBuyer(name = "Catering Nord GmbH", street = "Hafenweg 3", zip = "20457", city = "Hamburg")

    private fun invoices(): SalesInvoiceService = GlobalContext.get().get()

    private fun seedTerminal() =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@TillClearingTest.deviceId
                this.clientId = "TILL-CLEAR-CLIENT"
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
        val tws = TestData.createTableWithSession("TC_$suffix")
        val category = TestData.createCategory("TC Cat $suffix")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "TC Product $suffix",
                productNumber = "TC_$suffix",
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

    /** Settles one 80.00 bill fully on account and returns its id. */
    private fun settleOnAccount(
        admin: Employee,
        suffix: String,
        transfer: PaymentType,
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
                        payments = listOf(PaymentDto.PaymentRequest("80.00", transfer.id.value)),
                    ),
            )
        }.billId
    }

    /** Opens an ISSUED, fully-open POS_RECEIVABLE invoice of 80.00 and returns its id. */
    private fun issuedReceivable(
        admin: Employee,
        suffix: String,
        transfer: PaymentType,
    ): Long {
        val billId = settleOnAccount(admin, suffix, transfer)
        val draftId = runBlocking { invoices().createFromBill(billId, buyer, createdBy = null) }.id.value
        runBlocking { invoices().issue(draftId, issuedBy = admin.id.value) }
        return draftId
    }

    private fun clearingBillFor(invoiceId: Long): Bill? = Bill.find { BillTable.clearsSalesInvoiceId eq invoiceId }.firstOrNull()

    @Test
    fun `a cash clearing closes the receivable and books a payment-only bill`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "TC_A", username = "tc_a")
            val transfer = receivableType("Ueberweisung_TC_A")
            val cash = TestData.createPaymentType("Bar_TC_A", isCash = true)
            val invoiceId = issuedReceivable(admin, "A", transfer)

            runBlocking {
                invoices().settleAtTill(
                    invoiceId = invoiceId,
                    request = SalesInvoiceDto.SettleAtTillRequestDto(cash.id.value, "80.00"),
                    employeeId = admin.id.value,
                    deviceId = deviceId,
                )
            }

            transaction {
                val bill = assertNotNull(clearingBillFor(invoiceId), "a clearing bill was written")
                assertEquals(invoiceId, bill.clearsSalesInvoiceId)
                assertEquals(0, BigDecimal("80.00").compareTo(bill.billAmount), "billAmount is the tender")
                assertEquals(0, bill.vatDetails.count(), "a clearing carries no VAT detail")
                val payments = bill.payments.toList()
                assertEquals(1, payments.size, "one payment leg, no order items")
                assertEquals(0, BigDecimal("80.00").compareTo(payments.first().amount))
                assertTrue(!payments.first().createsReceivable, "the clearing tender opens no receivable")

                val invoice = SalesInvoice.findById(invoiceId)!!
                assertEquals(0, BigDecimal.ZERO.compareTo(invoice.openAmount), "the receivable is closed")

                val allocations =
                    PaymentAllocation.find { PaymentAllocationTable.salesInvoiceId eq invoiceId }.toList()
                assertEquals(1, allocations.size, "one TILL_RECEIPT allocation")
                assertEquals(AllocationSourceKind.TILL_RECEIPT.name, allocations.first().sourceKind)
                assertEquals(
                    bill.id.value,
                    allocations
                        .first()
                        .bill
                        ?.id
                        ?.value,
                )
            }
        }

    @Test
    fun `a card clearing closes the receivable with the chosen tender`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "TC_B", username = "tc_b")
            val transfer = receivableType("Ueberweisung_TC_B")
            val card = TestData.createPaymentType("Karte_TC_B", isCash = false)
            val invoiceId = issuedReceivable(admin, "B", transfer)

            runBlocking {
                invoices().settleAtTill(
                    invoiceId = invoiceId,
                    request = SalesInvoiceDto.SettleAtTillRequestDto(card.id.value, "80.00"),
                    employeeId = admin.id.value,
                    deviceId = deviceId,
                )
            }

            transaction {
                val bill = assertNotNull(clearingBillFor(invoiceId))
                assertEquals(
                    card.id.value,
                    bill.payments
                        .first()
                        .paymentType.id.value,
                    "the card tender was booked",
                )
                val invoice = SalesInvoice.findById(invoiceId)!!
                assertEquals(0, BigDecimal.ZERO.compareTo(invoice.openAmount), "the receivable is closed")
            }
        }

    @Test
    fun `a partial clearing leaves the rest open and the invoice ISSUED`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "TC_C", username = "tc_c")
            val transfer = receivableType("Ueberweisung_TC_C")
            val cash = TestData.createPaymentType("Bar_TC_C", isCash = true)
            val invoiceId = issuedReceivable(admin, "C", transfer)

            runBlocking {
                invoices().settleAtTill(
                    invoiceId = invoiceId,
                    request = SalesInvoiceDto.SettleAtTillRequestDto(cash.id.value, "50.00"),
                    employeeId = admin.id.value,
                    deviceId = deviceId,
                )
            }

            transaction {
                val invoice = SalesInvoice.findById(invoiceId)!!
                assertEquals(0, BigDecimal("30.00").compareTo(invoice.openAmount), "30.00 of 80.00 remains open")
                assertEquals(SalesInvoiceStatus.ISSUED, invoice.status, "a partial payment does not close it")
            }
        }

    @Test
    fun `a receivable-creating tender is refused and writes no bill`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "TC_D", username = "tc_d")
            val transfer = receivableType("Ueberweisung_TC_D")
            val onAccount = receivableType("Ziel_TC_D")
            val invoiceId = issuedReceivable(admin, "D", transfer)

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        invoices().settleAtTill(
                            invoiceId = invoiceId,
                            request = SalesInvoiceDto.SettleAtTillRequestDto(onAccount.id.value, "80.00"),
                            employeeId = admin.id.value,
                            deviceId = deviceId,
                        )
                    }
                }

            assertEquals(PosErrorCode.SALES_INVOICE_TENDER_NOT_SETTLING, error.code)
            transaction {
                assertNull(clearingBillFor(invoiceId), "a refused settlement writes no bill")
                assertEquals(
                    0,
                    BigDecimal("80.00").compareTo(SalesInvoice.findById(invoiceId)!!.openAmount),
                    "the receivable is untouched",
                )
            }
        }

    /** MD-06: a deactivated tender takes no new money, a till clearing included. */
    @Test
    fun `a deactivated tender is refused with 409 and writes no bill`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "TC_I", username = "tc_i")
            val transfer = receivableType("Ueberweisung_TC_I")
            val retired = TestData.createPaymentType("EC_TC_I", isCash = false, isActive = false)
            val invoiceId = issuedReceivable(admin, "I", transfer)

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        invoices().settleAtTill(
                            invoiceId = invoiceId,
                            request = SalesInvoiceDto.SettleAtTillRequestDto(retired.id.value, "80.00"),
                            employeeId = admin.id.value,
                            deviceId = deviceId,
                        )
                    }
                }

            assertEquals(PosErrorCode.PAYMENT_TYPE_INACTIVE, error.code)
            assertEquals(HttpStatusCode.Conflict, error.httpStatus)
            transaction {
                assertNull(clearingBillFor(invoiceId), "a refused settlement writes no bill")
                assertEquals(0, BigDecimal("80.00").compareTo(SalesInvoice.findById(invoiceId)!!.openAmount))
            }
        }

    @Test
    fun `an overpayment is refused and leaves the receivable open`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "TC_E", username = "tc_e")
            val transfer = receivableType("Ueberweisung_TC_E")
            val cash = TestData.createPaymentType("Bar_TC_E", isCash = true)
            val invoiceId = issuedReceivable(admin, "E", transfer)

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        invoices().settleAtTill(
                            invoiceId = invoiceId,
                            request = SalesInvoiceDto.SettleAtTillRequestDto(cash.id.value, "100.00"),
                            employeeId = admin.id.value,
                            deviceId = deviceId,
                        )
                    }
                }

            assertEquals(PosErrorCode.SALES_INVOICE_OVERPAYMENT, error.code)
            transaction {
                assertNull(clearingBillFor(invoiceId))
                assertEquals(
                    0,
                    BigDecimal("80.00").compareTo(SalesInvoice.findById(invoiceId)!!.openAmount),
                    "80.00 stays open",
                )
            }
        }

    @Test
    fun `a standalone invoice cannot be cleared at the till`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "TC_F", username = "tc_f")
            val cash = TestData.createPaymentType("Bar_TC_F", isCash = true)

            val standaloneId =
                runBlocking {
                    invoices()
                        .createStandaloneAsDto(
                            request =
                                SalesInvoiceDto.CreateStandaloneRequestDto(
                                    buyerName = "Kanzlei Maas",
                                    serviceDate = "2026-08-18T00:00:00",
                                    lines =
                                        listOf(
                                            SalesInvoiceDto.StandaloneLineInputDto(
                                                itemName = "Beratung",
                                                quantity = "1",
                                                grossUnitPrice = "119.00",
                                                vatRate = "19.00",
                                            ),
                                        ),
                                ),
                            createdBy = null,
                        ).id
                }
            runBlocking { invoices().issue(standaloneId, issuedBy = admin.id.value) }

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        invoices().settleAtTill(
                            invoiceId = standaloneId,
                            request = SalesInvoiceDto.SettleAtTillRequestDto(cash.id.value, "119.00"),
                            employeeId = admin.id.value,
                            deviceId = deviceId,
                        )
                    }
                }

            assertEquals(PosErrorCode.SALES_INVOICE_ORIGIN_MISMATCH, error.code)
            assertTrue(
                error.message?.contains("STANDALONE") == true,
                "the refusal names why a standalone cannot be cleared here, was: ${error.message}",
            )
        }

    @Test
    fun `a draft receivable cannot be cleared at the till`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "TC_G", username = "tc_g")
            val transfer = receivableType("Ueberweisung_TC_G")
            val cash = TestData.createPaymentType("Bar_TC_G", isCash = true)
            val billId = settleOnAccount(admin, "G", transfer)
            // A draft — createFromBill without issue.
            val draftId = runBlocking { invoices().createFromBill(billId, buyer, createdBy = null) }.id.value

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        invoices().settleAtTill(
                            invoiceId = draftId,
                            request = SalesInvoiceDto.SettleAtTillRequestDto(cash.id.value, "80.00"),
                            employeeId = admin.id.value,
                            deviceId = deviceId,
                        )
                    }
                }

            assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
            transaction {
                assertNull(clearingBillFor(draftId), "a draft opens no clearing bill")
            }
        }
}
