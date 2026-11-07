package org.example.project.features.bill

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseFailureBehavior
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationHub
import org.example.project.core.websocket.OutgoingFrame
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.bill.RebookFixture.Sale
import org.example.project.features.bill.RebookFixture.SaleSpec
import org.example.project.features.bill.RebookFixture.Tender
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.data.service.BillServiceImpl
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.mapper.BillRelationLoader
import org.example.project.features.bill.mapper.toResponseDto
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.product.domain.models.Product
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.tse.data.repository.TseLateSigningRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseSaleSigningGateway
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.database.tables.TseSigningQueueTable
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.voucher.database.tables.VoucherTable
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Zahlartwechsel as Storno + Neubuchung (spec 2026-09-29 §1): `rebookBill` stornos the bill and books
 * the same items again as a new bill — under the original seller, with the requested tender and the
 * new bill's whole tip — in ONE transaction, then signs the Storno and the Neubuchung.
 *
 * Every bill goes through the REAL service. Under TseMode.SIMULATION `TseTransaction.processDataHash`
 * stores the signed processData verbatim, so it witnesses the exact bytes handed to the TSE.
 */
@DisplayName("Zahlartwechsel — Storno + Neubuchung")
class BillRebookTest {
    private val deviceId = "zw-rebook-device"
    private val fixture = RebookFixture(deviceId)

    @AfterEach
    fun tearDown() {
        NotificationHub.resetForTest()
        BillNumberTracker.clearAll()
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private fun request(
        vararg payments: Pair<String, Long>,
        tips: String = "0.00",
        voucherRedeem: List<VoucherDto.RedeemVoucherRequestDto> = emptyList(),
    ) = BillDto.RebookRequestDto(
        payments = payments.map { (amount, typeId) -> PaymentDto.PaymentRequest(amount = amount, paymentTypeId = typeId) },
        voucherRedeem = voucherRedeem,
        tips = tips,
    )

    private fun stornoOf(billId: Long): Bill = Bill.find { BillTable.deletedBillId eq billId }.single()

    /** The processData the TSE signed for [bill] — SIMULATION stores it verbatim in processDataHash. */
    private fun signedProcessData(bill: Bill): String =
        assertNotNull(bill.tseTransaction, "bill ${bill.billNumber} must be TSE-signed, or this proves nothing").processDataHash!!

    private fun assertMoney(
        expected: String,
        actual: BigDecimal?,
        what: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$what: expected $expected, got ${actual?.toPlainString()}")

    private fun assertMoney(
        expected: String,
        actual: String,
        what: String,
    ) = assertMoney(expected, BigDecimal(actual), what)

    private fun billCount(): Long = transaction { Bill.count() }

    private fun remaining(voucherNumber: String): BigDecimal =
        transaction { Voucher.find { VoucherTable.voucherNumber eq voucherNumber }.single().remainingValue }

    /** An ACTIVE internal voucher worth [value], issued by a (paid) voucher-sale line of its own. */
    private fun seedVoucher(
        number: String,
        value: String,
    ) {
        val tws = TestData.createTableWithSession("VS${number.takeLast(4)}")
        val category = TestData.createCategory("VS Cat $number")
        val product = TestData.createProduct(category.id.value, "Gutschein $number", "VS_$number", BigDecimal(value))
        val vat = TestData.createDefaultVatRates().first()
        transaction {
            val saleLine =
                OrderItem.new {
                    this.session = TableSession.findById(tws.session.id)!!
                    this.product = product
                    this.unitPrice = BigDecimal(value)
                    this.finalPrice = BigDecimal(value)
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat
                    this.takeAway = false
                }
            Voucher.new {
                this.orderItem = saleLine
                this.voucherNumber = number
                this.originalValue = BigDecimal(value)
                this.remainingValue = BigDecimal(value)
                this.createdAt = currentTimeStamp()
            }
        }
    }

    private fun redeem(
        voucherNumber: String,
        amount: String,
    ) = VoucherDto.RedeemVoucherRequestDto(voucherNumber = voucherNumber, redeemedAmount = amount, voucherSource = VoucherSourceType.INTERNAL)

    /** A BILL_WIDE percentage promotion valid from now until [validForSeconds] from now. */
    private fun seedBillWidePromotion(
        percent: String,
        validForSeconds: Long = 60,
    ): Long {
        val author = TestData.createAdmin(employeeNumber = "PROMO_A", username = "promo_a")
        return transaction {
            val now = currentTimeStamp()
            Promotion
                .new {
                    this.name = "$percent % auf alles"
                    this.description = null
                    this.discountType = DiscountType.PERCENTAGE
                    this.discountValue = BigDecimal(percent)
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.combinable = true
                    this.validFrom = now
                    this.validUntil = now.toJavaLocalDateTime().plusSeconds(validForSeconds).toKotlinLocalDateTime()
                    this.isActive = true
                    this.createdBy = Employee.findById(author.id)!!
                    this.createdAt = now
                }.id.value
        }
    }

    private fun invoiceService() =
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

    private val buyer = InvoiceBuyer(name = "Musterfirma GmbH", street = "Industriestr. 5", zip = "10117", city = "Berlin")

    private fun draftInvoiceFor(billId: Long): Long =
        runBlocking {
            invoiceService().createFromBill(billId, buyer, createdBy = null)
        }.let { transaction { it.id.value } }

    private fun invoicesFor(billId: Long): Long = transaction { SalesInvoice.find { SalesInvoiceTable.billId eq billId }.count() }

    private fun closeBusinessDay(billId: Long) {
        transaction {
            val bill = Bill.findById(billId)!!
            val now = currentTimeStamp()
            bill.zBon =
                ZBon.new {
                    this.shopInfo = ShopInfo.all().first()
                    this.employee = bill.employeeId
                    this.startTime = now
                    this.endTime = now
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }
        }
    }

    /**
     * Asserts that [rebook] is refused with [code] and that the refusal rolled everything back: no
     * bill row was written, the original still stands, and [vouchers] keep their balances.
     */
    private fun assertRefused(
        code: PosErrorCode,
        sale: Sale,
        vouchers: List<String> = emptyList(),
        rebook: () -> Unit,
    ): PosException {
        val billsBefore = billCount()
        val balancesBefore = vouchers.map(::remaining)
        val reasonBefore = transaction { Bill.findById(sale.billId)!!.deletingReason }

        val error = assertFailsWith<PosException> { rebook() }

        assertEquals(code, error.code, error.message)
        assertEquals(billsBefore, billCount(), "a refused rebook writes no bill (${error.message})")
        assertEquals(balancesBefore, vouchers.map(::remaining), "a refused rebook leaves voucher balances alone")
        assertEquals(reasonBefore, transaction { Bill.findById(sale.billId)!!.deletingReason }, "the original is untouched")
        return error
    }

    // ------------------------------------------------------------------------------------------
    // 1–3: the Storno and the Neubuchung each sign what they print
    // ------------------------------------------------------------------------------------------

    @Test
    fun `employee tip added on card - storno and new bill sign what they print`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)

                val rebooked = fixture.rebook(sale, request("25.00" to sale.cardTypeId, tips = "2.00"))

                assertEquals(BillKind.REBOOKING, rebooked.relations.kind)
                assertEquals(sale.billId, rebooked.relations.rebooksBill?.billId)
                assertMoney("2.00", rebooked.tips, "new bill tips")
                assertMoney("25.00", rebooked.paidAmount, "new bill paidAmount")
                assertEquals(TipRecipient.EMPLOYEE.name, rebooked.tipRecipient)
                assertEquals(sale.closerId, rebooked.employeeId, "booked under the original seller")
                assertMoney("23.00", rebooked.vatDetails.sumOf { BigDecimal(it.totalAmount) }, "a TrinkgeldAN tip is no VAT row")

                transaction {
                    val stornoed = Bill.findById(sale.billId)!!
                    assertNotNull(stornoed.deletingReason, "the original is cancelled")
                    val storno = stornoOf(sale.billId)
                    assertEquals(StornoReason.ZAHLARTWECHSEL, storno.stornoReason)
                    assertEquals(
                        StornoReason.ZAHLARTWECHSEL,
                        BillRelationLoader.load(listOf(storno.id.value), SalesInvoiceBillInvoiceLink()).getValue(storno.id.value).stornoReason,
                        "the Storno's relations name it a Zahlartwechsel, so its chip reads as a payment change",
                    )
                    assertMoney("-23.00", storno.paidAmount, "Storno paidAmount")
                    assertEquals("Beleg^-23.00_0.00_0.00_0.00_0.00^-23.00:Unbar", signedProcessData(storno))
                    assertEquals("Beleg^23.00_0.00_0.00_0.00_2.00^25.00:Unbar", signedProcessData(Bill.findById(rebooked.billId)!!))
                }
            }
        }

    @Test
    fun `owner tip becomes revenue at 19 percent`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.OWNER)

                val rebooked = fixture.rebook(sale, request("25.00" to sale.cardTypeId, tips = "2.00"))

                assertEquals(TipRecipient.OWNER.name, rebooked.tipRecipient)
                assertMoney("25.00", rebooked.vatDetails.sumOf { BigDecimal(it.totalAmount) }, "an owner tip is turnover")
                val row19 = rebooked.vatDetails.single()
                val expectedVat = BigDecimal("25.00") - BigDecimal("25.00").divide(BigDecimal("1.19"), 2, RoundingMode.HALF_UP)
                assertMoney(expectedVat.toPlainString(), row19.vatAmount, "19 % VAT on 25.00, derived by subtraction")
                transaction {
                    assertEquals("Beleg^25.00_0.00_0.00_0.00_0.00^25.00:Unbar", signedProcessData(Bill.findById(rebooked.billId)!!))
                }
            }
        }

    @Test
    fun `cash to card without tip`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE, payByCard = false)

                val rebooked = fixture.rebook(sale, request("23.00" to sale.cardTypeId))

                transaction {
                    assertEquals("Beleg^-23.00_0.00_0.00_0.00_0.00^-23.00:Bar", signedProcessData(stornoOf(sale.billId)))
                    assertEquals("Beleg^23.00_0.00_0.00_0.00_0.00^23.00:Unbar", signedProcessData(Bill.findById(rebooked.billId)!!))
                }
            }
        }

    @Test
    fun `the original tip recipient survives a role change of the seller`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
                transaction { Employee.findById(java.util.UUID.fromString(sale.closerId))!!.role = EmployeeRole.ADMIN }

                val rebooked = fixture.rebook(sale, request("25.00" to sale.cardTypeId, tips = "2.00"))

                assertEquals(TipRecipient.EMPLOYEE.name, rebooked.tipRecipient, "the original's TrinkgeldAN, not the seller's new role")
                assertMoney("23.00", rebooked.vatDetails.sumOf { BigDecimal(it.totalAmount) }, "an employee tip is no VAT row")
                transaction {
                    assertEquals("Beleg^23.00_0.00_0.00_0.00_2.00^25.00:Unbar", signedProcessData(Bill.findById(rebooked.billId)!!))
                }
            }
        }

    // ------------------------------------------------------------------------------------------
    // 4–6: who, where, and in which mode the Neubuchung is booked
    // ------------------------------------------------------------------------------------------

    @Test
    fun `booked under the original seller even when a manager rebooks`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val manager = TestData.createManager(employeeNumber = "ZW_BOSS", username = "zw_boss")

            val rebooked = fixture.rebook(sale, request("23.00" to sale.cashTypeId), actorId = manager.id.value.toString())

            assertEquals(sale.closerId, rebooked.employeeId, "the Neubuchung stays the seller's sale")
            transaction {
                assertEquals(
                    sale.closerId,
                    stornoOf(sale.billId)
                        .employeeId.id.value
                        .toString(),
                )
            }
            val audit =
                transaction {
                    TransactionAuditLogTable
                        .selectAll()
                        .where { TransactionAuditLogTable.operation eq "REBOOK_BILL" }
                        .orderBy(TransactionAuditLogTable.id to SortOrder.DESC)
                        .first()
                }
            assertEquals(manager.id.value.toString(), audit[TransactionAuditLogTable.employeeId], "the actor is who rebooked")
            assertEquals(sale.billId.toString(), audit[TransactionAuditLogTable.entityId], "the row names the rebooked bill")
            val stornoNumber = transaction { stornoOf(sale.billId).billNumber }
            assertEquals("Zahlartwechsel: $stornoNumber / ${rebooked.billNumber}", audit[TransactionAuditLogTable.entityName])
            // after_state is JSON naming the three Belege — not a Kotlin toString of an internal class.
            val afterState = assertNotNull(audit[TransactionAuditLogTable.afterState], "the row carries an after_state")
            val state =
                runCatching { Json.parseToJsonElement(afterState).jsonObject }
                    .getOrElse { throw AssertionError("after_state is not JSON: $afterState", it) }
            val stornoId = transaction { stornoOf(sale.billId).id.value }
            assertEquals(sale.billId, state["rebookedBillId"]?.jsonPrimitive?.long)
            assertEquals(stornoId, state["stornoBillId"]?.jsonPrimitive?.long)
            assertEquals(stornoNumber, state["stornoBillNumber"]?.jsonPrimitive?.content)
            assertEquals(rebooked.billId, state["newBillId"]?.jsonPrimitive?.long)
            assertEquals(rebooked.billNumber, state["newBillNumber"]?.jsonPrimitive?.content)
            assertFalse("processData" in afterState, "the TSE payload is not audit state")
        }

    @Test
    fun `table taken by new guests is left alone`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val (tableId, oldSessionId) = transaction { Bill.findById(sale.billId)!!.tableSession.let { it.table.id.value to it.id.value } }
            val guests = TestData.createCashier(employeeNumber = "ZW_NEW", username = "zw_new")
            val newSessionId =
                transaction {
                    RTable
                        .findById(tableId)!!
                        .startSession(Employee.findById(guests.id)!!)
                        .id.value
                }

            val updates =
                runBlocking {
                    val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
                    NotificationHub.addSseConnection(connectionId = "obs", employeeId = "obs", outgoing = out)
                    fixture.rebook(sale, request("23.00" to sale.cashTypeId))
                    generateSequence { out.tryReceive().getOrNull() }
                        .mapNotNull { (it as? OutgoingFrame.Event)?.event?.message as? NotificationDto.TableStatusUpdateDto }
                        .toList()
                }

            transaction {
                val table = RTable.findById(tableId)!!
                assertEquals(newSessionId, table.currentSession?.id?.value, "the new guests keep their session")
                assertEquals(TableStatus.OCCUPIED, table.tableStatus)
                assertTrue(TableSession.findById(oldSessionId)!!.completed, "the paid session stays completed")
                val openLeftOver = OrderItem.all().filter { it.session.id.value == oldSessionId && it.itemStatus == ItemStatus.OPEN }
                assertTrue(openLeftOver.isEmpty(), "every duplicated item was billed again")
            }
            assertTrue(updates.none { it.tableId == tableId }, "no table-status update between Storno and Neubuchung: $updates")
        }

    @Test
    fun `training bill stays training`() =
        testApp { _ ->
            val trainee = TestData.createManager(employeeNumber = "ZW_TRN", username = "zw_trn")
            transaction { Employee.findById(trainee.id)!!.trainingMode = true }
            val sale = fixture.closeSale(SaleSpec(closer = transaction { Employee.findById(trainee.id)!! }))
            transaction { assertTrue(Bill.findById(sale.billId)!!.isTraining, "precondition: a training sale") }
            transaction { Employee.findById(trainee.id)!!.trainingMode = false }

            val rebooked = fixture.rebook(sale, request("23.00" to sale.cashTypeId))

            transaction { assertTrue(Bill.findById(rebooked.billId)!!.isTraining, "the Neubuchung copies the original's training flag") }
        }

    @Test
    fun `a Neubuchung can itself be rebooked - its order-less items are handed back too`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val first = fixture.rebook(sale, request("23.00" to sale.cashTypeId))

            val second = fixture.rebook(sale.copy(billId = first.billId), request("23.00" to sale.cardTypeId))

            assertEquals(
                sale.billId,
                transaction {
                    Bill
                        .findById(first.billId)!!
                        .rebooksBill
                        ?.id
                        ?.value
                },
            )
            assertEquals(first.billId, second.relations.rebooksBill?.billId)
            assertMoney("23.00", second.billAmount, "the second Neubuchung carries every item")
        }

    @Test
    fun `a plain Storno of a Neubuchung hands its items back to the session`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val rebooked = fixture.rebook(sale, request("23.00" to sale.cashTypeId))
            val sessionId = rebooked.tableSessionId

            fixture.storno(sale.copy(billId = rebooked.billId))

            transaction {
                val storno = stornoOf(rebooked.billId)
                assertMoney("-23.00", storno.billAmount, "Storno billAmount")
                assertMoney("-23.00", storno.paidAmount, "Storno paidAmount")
                val open = OrderItem.all().filter { it.session.id.value == sessionId && it.itemStatus == ItemStatus.OPEN }
                assertEquals(1, open.size, "the Neubuchung's (order-less, duplicated) item is open again")
                assertMoney("23.00", open.single().finalPrice, "the reopened item's price")
                assertTrue(!TableSession.findById(sessionId)!!.completed, "the session is reopened with its item")
            }
        }

    // ------------------------------------------------------------------------------------------
    // 7: voucher legs — restored by the Storno, redeemed again by the Neubuchung
    // ------------------------------------------------------------------------------------------

    @Test
    fun `mistyped voucher number is corrected`() =
        testApp { _ ->
            seedVoucher("G2026-0001", "50.00")
            seedVoucher("G2026-0011", "50.00")
            val sale = fixture.closeSale(SaleSpec(tender = Tender.VOUCHER, voucherRedeem = listOf(redeem("G2026-0001", "23.00"))))
            assertMoney("27.00", remaining("G2026-0001"), "precondition: A was charged")

            val rebooked =
                fixture.rebook(sale, request("23.00" to sale.voucherTypeId, voucherRedeem = listOf(redeem("G2026-0011", "23.00"))))

            assertMoney("50.00", remaining("G2026-0001"), "A is restored by the Storno")
            assertMoney("27.00", remaining("G2026-0011"), "B is charged by the Neubuchung")
            transaction {
                val originalRedeem =
                    Payment
                        .find { PaymentTable.billId eq sale.billId }
                        .single()
                        .voucherRedeem!!
                assertEquals(stornoOf(sale.billId).id, originalRedeem.reversedByBill?.id, "the original's redemption is reversed by the Storno")
                val newRedeem =
                    Payment
                        .find { PaymentTable.billId eq rebooked.billId }
                        .single()
                        .voucherRedeem!!
                assertEquals("G2026-0011", newRedeem.voucherNumber)
                assertNull(newRedeem.reversedByBill)
            }
        }

    // ------------------------------------------------------------------------------------------
    // 8–9: promotions — re-applied as of the original's close
    // ------------------------------------------------------------------------------------------

    /** A 23.00 sale with a 10 % bill-wide promotion (2.30 off), paid 20.70 by card. */
    private fun closePromotedSale(): Pair<Sale, Long> {
        val promotionId = seedBillWidePromotion("10.00")
        val sale = fixture.closeSale(SaleSpec(paid = "20.70", promotionIds = listOf(promotionId)))
        transaction { assertMoney("2.30", Bill.findById(sale.billId)!!.promotionAmount, "precondition: promotion applied") }
        return sale to promotionId
    }

    /** Ends [promotionId] at the original's close — valid then, over by now — and deactivates it. */
    private fun endPromotion(
        promotionId: Long,
        sale: Sale,
    ) {
        transaction {
            val promotion = Promotion.findById(promotionId)!!
            promotion.validUntil = Bill.findById(sale.billId)!!.endTime
            promotion.isActive = false
        }
        Thread.sleep(5)
    }

    @Test
    fun `promotion survives although it has ended`() =
        testApp { _ ->
            val (sale, promotionId) = closePromotedSale()
            endPromotion(promotionId, sale)

            val rebooked = fixture.rebook(sale, request("20.70" to sale.cashTypeId))

            assertMoney("2.30", rebooked.promotionAmount, "the promotion is re-applied")
            assertEquals(listOf(promotionId), rebooked.promotions.map { it.promotionId })
            assertMoney("20.70", rebooked.payments.sumOf { BigDecimal(it.amount) }, "payments = billAmount − promotionAmount")
        }

    @Test
    fun `edited promotion is refused`() =
        testApp { _ ->
            val (sale, promotionId) = closePromotedSale()
            endPromotion(promotionId, sale)
            transaction { Promotion.findById(promotionId)!!.discountValue = BigDecimal("20.00") }

            assertRefused(PosErrorCode.BILL_REBOOK_PROMOTION_CHANGED, sale) {
                fixture.rebook(sale, request("20.70" to sale.cashTypeId))
            }
        }

    // ------------------------------------------------------------------------------------------
    // 10: auf Rechnung, both directions
    // ------------------------------------------------------------------------------------------

    @Test
    fun `auf Rechnung mis-click with only a draft invoice is fixed to card`() =
        testApp { _ ->
            val sale = fixture.closeSale(SaleSpec(tender = Tender.RECEIVABLE))
            draftInvoiceFor(sale.billId)
            assertEquals(1L, invoicesFor(sale.billId), "precondition: a draft exists")

            val rebooked = fixture.rebook(sale, request("23.00" to sale.cardTypeId))

            assertEquals(0L, invoicesFor(sale.billId), "the draft goes with the Storno")
            transaction {
                val payment = Payment.find { PaymentTable.billId eq rebooked.billId }.single()
                assertEquals(sale.cardTypeId, payment.paymentType.id.value)
                assertEquals(false, payment.createsReceivable)
            }
        }

    @Test
    fun `card sale rebooked to auf Rechnung can be invoiced`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)

            val rebooked = fixture.rebook(sale, request("23.00" to sale.receivableTypeId))

            transaction { assertEquals(true, Payment.find { PaymentTable.billId eq rebooked.billId }.single().createsReceivable) }
            draftInvoiceFor(rebooked.billId)
            assertEquals(1L, invoicesFor(rebooked.billId), "the Neubuchung is a standing sale an invoice can describe")
        }

    // ------------------------------------------------------------------------------------------
    // 11: refusals — each rolls the whole transaction back
    // ------------------------------------------------------------------------------------------

    @Test
    fun `refused - an already stornoed bill`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            fixture.storno(sale)
            assertRefused(PosErrorCode.BILL_ALREADY_DELETED, sale) { fixture.rebook(sale, request("23.00" to sale.cashTypeId)) }
        }

    @Test
    fun `refused - the Storno bill itself`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            fixture.storno(sale)
            val storno = sale.copy(billId = transaction { stornoOf(sale.billId).id.value })
            assertRefused(PosErrorCode.BILL_NOT_A_STANDING_SALE, storno) { fixture.rebook(storno, request("23.00" to sale.cashTypeId)) }
        }

    @Test
    fun `refused - an original that was already rebooked`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            fixture.rebook(sale, request("23.00" to sale.cashTypeId))
            assertRefused(PosErrorCode.BILL_ALREADY_DELETED, sale) { fixture.rebook(sale, request("23.00" to sale.voucherTypeId)) }
        }

    @Test
    fun `refused - a closed business day`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            closeBusinessDay(sale.billId)
            val error = assertRefused(PosErrorCode.VALIDATION_FAILED, sale) { fixture.rebook(sale, request("23.00" to sale.cashTypeId)) }
            assertEquals(HttpStatusCode.Conflict, error.httpStatus, "the closed-day refusal deleteBill uses")
        }

    @Test
    fun `refused - an issued invoice`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val invoiceId = draftInvoiceFor(sale.billId)
            val issuer = transaction { TestData.createAdmin(employeeNumber = "ZW_I", username = "zw_issuer").id.value }
            runBlocking { invoiceService().issue(invoiceId, issuer) }
            assertRefused(PosErrorCode.SALES_INVOICE_IMMUTABLE, sale) { fixture.rebook(sale, request("23.00" to sale.cashTypeId)) }
        }

    @Test
    fun `refused - a gift-voucher sale`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            transaction {
                Bill
                    .findById(sale.billId)!!
                    .items
                    .first()
                    .product.productNumber = "VOU"
            }
            assertRefused(PosErrorCode.BILL_REBOOK_UNSUPPORTED, sale) { fixture.rebook(sale, request("23.00" to sale.cashTypeId)) }
        }

    @Test
    fun `refused - a receivable clearing`() =
        testApp { _ ->
            val invoiced = fixture.closeSale(TipRecipient.EMPLOYEE)
            val invoiceId = draftInvoiceFor(invoiced.billId)
            val clearing = fixture.closeSale(TipRecipient.EMPLOYEE)
            transaction { Bill.findById(clearing.billId)!!.clearsSalesInvoiceId = SalesInvoice.findById(invoiceId)?.id?.value }
            assertRefused(PosErrorCode.BILL_REBOOK_UNSUPPORTED, clearing) {
                fixture.rebook(clearing, request("23.00" to clearing.cashTypeId))
            }
        }

    @Test
    fun `refused - same tender, vouchers and tip`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            assertRefused(PosErrorCode.BILL_REBOOK_NO_CHANGE, sale) { fixture.rebook(sale, request("23.00" to sale.cardTypeId)) }
        }

    @Test
    fun `refused - payments that do not cover total plus tip`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            assertRefused(PosErrorCode.PAYMENT_TOTAL_MISMATCH, sale) {
                fixture.rebook(sale, request("24.00" to sale.cardTypeId, tips = "2.00"))
            }
        }

    @Test
    fun `refused - a negative tip`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            assertRefused(PosErrorCode.VALIDATION_FAILED, sale) { fixture.rebook(sale, request("22.00" to sale.cardTypeId, tips = "-1.00")) }
        }

    @Test
    fun `refused - the station account`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val station = TestData.createTerminal(employeeNumber = "ZW_ST", username = "zw_station")
            assertRefused(PosErrorCode.AUTH_FORBIDDEN, sale) {
                fixture.rebook(sale, request("23.00" to sale.cashTypeId), actorId = station.id.value.toString())
            }
        }

    @Test
    fun `refused after the Storno - the restored voucher and the Storno roll back together`() =
        testApp { _ ->
            seedVoucher("G2026-0001", "50.00")
            seedVoucher("G2026-0002", "10.00")
            val sale = fixture.closeSale(SaleSpec(tender = Tender.VOUCHER, voucherRedeem = listOf(redeem("G2026-0001", "23.00"))))

            // B holds 10.00 but is asked for 23.00: refused inside the Neubuchung, after the Storno
            // already gave A its 23.00 back — which the rollback must take away again.
            assertRefused(PosErrorCode.PAYMENT_AMOUNT_INVALID, sale, vouchers = listOf("G2026-0001", "G2026-0002")) {
                fixture.rebook(sale, request("23.00" to sale.voucherTypeId, voucherRedeem = listOf(redeem("G2026-0002", "23.00"))))
            }
        }

    // ------------------------------------------------------------------------------------------
    // 12: a TSE failure queues the Belege; the rebook stands
    // ------------------------------------------------------------------------------------------

    /** A device whose every signing attempt fails, as an unreachable TSE does. */
    private val failingDevice: TseSignProvider =
        Proxy.newProxyInstance(
            TseSignProvider::class.java.classLoader,
            arrayOf(TseSignProvider::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "checkHealth" ->
                    TseDto.HealthCheckResultDto(
                        healthy = false,
                        initialized = true,
                        validTime = true,
                        passedSelfTest = true,
                        remainingSignatures = 1_000L,
                        availableCapacity = 1_000L,
                    )
                "isConnected" -> false
                "getCachedMode" -> "SIMULATION"
                "toString" -> "FailingDevice"
                "hashCode" -> 0
                "equals" -> false
                else -> throw PosException(PosErrorCode.TSE_UNREACHABLE, HttpStatusCode.FailedDependency, "TSE unreachable (test)")
            }
        } as TseSignProvider

    private fun serviceWithFailingTse(): BillServiceImpl {
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
                            failingDevice,
                            TseVorgangSigner(failingDevice, TseTransactionRepositoryImpl(), koin.get()),
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
    fun `TSE failure under FAIL - rebook stays committed, both signings attempted, Storno error thrown`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            runBlocking {
                ShopConfigurationManager.setTseConfig(
                    TseConfigDto.TseConfig(mode = TseMode.SIMULATION, timeAdminPin = "86420", failureBehavior = TseFailureBehavior.FAIL),
                )
            }
            try {
                val error =
                    assertFailsWith<PosException> {
                        runBlocking {
                            serviceWithFailingTse().rebookBill(
                                sale.closerId,
                                deviceId,
                                sale.billId.toString(),
                                request("23.00" to sale.cashTypeId),
                                fixture.capabilitiesOf(sale.closerId),
                            )
                        }
                    }

                assertEquals(PosErrorCode.TSE_SIGNING_FAILED, error.code)
                assertTrue(error.message!!.contains("reversal bill"), "the Storno's failure is the one thrown: ${error.message}")
                val suppressed = error.suppressed.filterIsInstance<PosException>().singleOrNull()
                assertNotNull(suppressed, "the Neubuchung's failure rides along")
                assertTrue(!suppressed.message!!.contains("reversal"), "the suppressed error is the Neubuchung's: ${suppressed.message}")
                transaction {
                    val storno = stornoOf(sale.billId)
                    val neubuchung = Bill.find { BillTable.rebooksBillId eq sale.billId }.single()
                    assertEquals(TseSigningStatus.FAILED, storno.tseSigningStatus, "the Storno's signing was attempted")
                    assertEquals(TseSigningStatus.FAILED, neubuchung.tseSigningStatus, "the Neubuchung's signing was attempted too")
                    assertNotNull(Bill.findById(sale.billId)!!.deletingReason, "the rebook stays committed")
                }
            } finally {
                runBlocking { ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "86420")) }
            }
        }

    @Test
    fun `TSE failure queues the Storno and the new bill`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            runBlocking {
                ShopConfigurationManager.setTseConfig(
                    TseConfigDto.TseConfig(mode = TseMode.SIMULATION, timeAdminPin = "86420", failureBehavior = TseFailureBehavior.QUEUE),
                )
            }
            try {
                val rebooked =
                    runBlocking {
                        serviceWithFailingTse().rebookBill(
                            sale.closerId,
                            deviceId,
                            sale.billId.toString(),
                            request("23.00" to sale.cashTypeId),
                            fixture.capabilitiesOf(sale.closerId),
                        )
                    }

                transaction {
                    val stornoId = stornoOf(sale.billId).id.value
                    val queued =
                        TseSigningQueueTable
                            .selectAll()
                            .map { it[TseSigningQueueTable.referenceId] }
                            .toSet()
                    assertTrue(stornoId.toString() in queued, "the Storno is queued for Nachsignierung: $queued")
                    assertTrue(rebooked.billId.toString() in queued, "the Neubuchung is queued for Nachsignierung: $queued")
                    assertNull(Bill.findById(rebooked.billId)!!.tseTransaction, "nothing was signed")
                    assertEquals(TseSigningStatus.PENDING, Bill.findById(rebooked.billId)!!.tseSigningStatus, "enqueue marks the bill PENDING")
                    assertEquals(TseSigningStatus.PENDING, Bill.findById(stornoId)!!.tseSigningStatus, "enqueue marks the Storno PENDING")
                }
            } finally {
                runBlocking { ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "86420")) }
            }
        }

    // ------------------------------------------------------------------------------------------
    // 13: the tip goes down only with BILL_VOID (user decision A)
    // ------------------------------------------------------------------------------------------

    private fun cashierId(): String =
        TestData
            .createCashier(employeeNumber = "ZW_C", username = "zw_cashier")
            .id.value
            .toString()

    @Test
    fun `a cashier may not lower an owner tip - 403, nothing written`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.OWNER, originalTip = "2.00")
            val cashier = cashierId()
            assertTrue(Capability.BILL_VOID !in fixture.capabilitiesOf(cashier), "precondition: a cashier cannot storno")

            val error =
                assertRefused(PosErrorCode.BILL_REBOOK_TIP_DECREASE_FORBIDDEN, sale) {
                    fixture.rebook(sale, request("23.00" to sale.cardTypeId, tips = "0.00"), actorId = cashier)
                }
            assertEquals(HttpStatusCode.Forbidden, error.httpStatus)
        }

    @Test
    fun `a manager may lower the tip`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.OWNER, originalTip = "2.00")
            val manager =
                TestData
                    .createManager(employeeNumber = "ZW_MG", username = "zw_mg")
                    .id.value
                    .toString()
            assertTrue(Capability.BILL_VOID in fixture.capabilitiesOf(manager), "precondition: a manager may storno")

            val rebooked = fixture.rebook(sale, request("23.00" to sale.cardTypeId, tips = "0.00"), actorId = manager)

            assertMoney("0.00", rebooked.tips, "the tip is gone")
            assertMoney("23.00", rebooked.paidAmount, "paid")
        }

    @Test
    fun `a cashier may raise the tip`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.OWNER, originalTip = "2.00")

            val rebooked = fixture.rebook(sale, request("26.00" to sale.cardTypeId, tips = "3.00"), actorId = cashierId())

            assertMoney("3.00", rebooked.tips, "the raised tip")
        }

    @Test
    fun `a cashier may keep the tip and change only the method`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.OWNER, originalTip = "2.00")

            val rebooked = fixture.rebook(sale, request("25.00" to sale.cashTypeId, tips = "2.00"), actorId = cashierId())

            assertMoney("2.00", rebooked.tips, "the kept tip")
            assertEquals(listOf(sale.cashTypeId), rebooked.payments.map { it.paymentTypeId })
        }

    // ------------------------------------------------------------------------------------------
    // 14: a kept voucher leg — restored by the Storno, redeemed again by the Neubuchung
    // ------------------------------------------------------------------------------------------

    @Test
    fun `keeping the same voucher leaves its balance unchanged overall`() =
        testApp { _ ->
            seedVoucher("G2026-0021", "50.00")
            val sale = fixture.closeSale(SaleSpec(tender = Tender.VOUCHER, voucherRedeem = listOf(redeem("G2026-0021", "23.00"))))
            assertMoney("27.00", remaining("G2026-0021"), "precondition: charged by the sale")

            // The voucher leg is kept as it stands; only a 2.00 tip is added on card.
            val rebooked =
                fixture.rebook(
                    sale,
                    request(
                        "23.00" to sale.voucherTypeId,
                        "2.00" to sale.cardTypeId,
                        tips = "2.00",
                        voucherRedeem = listOf(redeem("G2026-0021", "23.00")),
                    ),
                )

            assertMoney("27.00", remaining("G2026-0021"), "restored by the Storno, charged again by the Neubuchung")
            transaction {
                val originalRedeem =
                    Payment
                        .find { PaymentTable.billId eq sale.billId }
                        .single()
                        .voucherRedeem!!
                assertEquals(stornoOf(sale.billId).id, originalRedeem.reversedByBill?.id, "the original redemption is reversed")
                val newRedeem =
                    Payment
                        .find { PaymentTable.billId eq rebooked.billId }
                        .mapNotNull { it.voucherRedeem }
                        .single()
                assertEquals("G2026-0021", newRedeem.voucherNumber)
                assertMoney("23.00", newRedeem.redeemAmount, "the Neubuchung redeems the same amount")
                assertNull(newRedeem.reversedByBill, "the new redemption stands")
            }
        }

    // ------------------------------------------------------------------------------------------
    // 15: the till's isRebookable — an ISSUED invoice hides the action, a draft does not
    // ------------------------------------------------------------------------------------------

    private fun isRebookable(billId: Long): Boolean = transaction { Bill.findById(billId)!!.toResponseDto(SalesInvoiceBillInvoiceLink()).isRebookable }

    @Test
    fun `isRebookable is true with only a draft invoice and false once it is issued`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val invoiceId = draftInvoiceFor(sale.billId)
            assertTrue(isRebookable(sale.billId), "a draft goes with the Storno, so the rebook is offered")

            val issuer = transaction { TestData.createAdmin(employeeNumber = "ZW_I2", username = "zw_issuer2").id.value }
            runBlocking { invoiceService().issue(invoiceId, issuer) }

            assertTrue(!isRebookable(sale.billId), "an issued invoice refuses the rebook, so the till hides it")
        }

    private fun isDeletable(billId: Long): Boolean = transaction { Bill.findById(billId)!!.toResponseDto(SalesInvoiceBillInvoiceLink()).isDeletable }

    @Test
    fun `isDeletable is true with only a draft invoice and false once it is issued`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val invoiceId = draftInvoiceFor(sale.billId)
            assertTrue(isDeletable(sale.billId), "a draft goes with the Storno, so the Storno is offered")

            val issuer = transaction { TestData.createAdmin(employeeNumber = "ZW_I3", username = "zw_issuer3").id.value }
            runBlocking { invoiceService().issue(invoiceId, issuer) }

            assertTrue(!isDeletable(sale.billId), "deleteBill refuses an issued invoice, so the till hides the Storno")
        }

    // ------------------------------------------------------------------------------------------
    // 16: a promotion that no longer applies as of the original's close
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a promotion moved to start after the sale is refused as changed`() =
        testApp { _ ->
            val (sale, promotionId) = closePromotedSale()
            transaction {
                val closedAt = Bill.findById(sale.billId)!!.endTime
                Promotion.findById(promotionId)!!.validFrom =
                    closedAt.toJavaLocalDateTime().plusMinutes(1).toKotlinLocalDateTime()
            }

            assertRefused(PosErrorCode.BILL_REBOOK_PROMOTION_CHANGED, sale) {
                fixture.rebook(sale, request("20.70" to sale.cashTypeId))
            }
        }

    // ------------------------------------------------------------------------------------------
    // 17: a Beleg from before the item-promotion cap, whose stacked Aktionen took an item below zero
    // ------------------------------------------------------------------------------------------

    private var itemPromotionSeq = 0

    /** An ITEM_SPECIFIC FIXED promotion for the sale's product, as the RebookFixture factory wants it. */
    private fun itemFixedPromotion(amount: String): (Long) -> Long =
        { productId ->
            val n = ++itemPromotionSeq
            val author = TestData.createAdmin(employeeNumber = "PROMO_I$n", username = "promo_i$n")
            transaction {
                val now = currentTimeStamp()
                Promotion
                    .new {
                        this.name = "$amount € auf den Artikel"
                        this.description = null
                        this.discountType = DiscountType.FIXED_AMOUNT
                        this.discountValue = BigDecimal(amount)
                        this.scope = PromotionScope.ITEM_SPECIFIC
                        this.targetProduct = Product.findById(productId)!!
                        this.isMultiUse = false
                        this.combinable = true
                        this.validFrom = now.toJavaLocalDateTime().minusSeconds(60).toKotlinLocalDateTime()
                        this.validUntil = now.toJavaLocalDateTime().plusSeconds(600).toKotlinLocalDateTime()
                        this.isActive = true
                        this.createdBy = Employee.findById(author.id)!!
                        this.createdAt = now
                    }.id.value
            }
        }

    @Test
    fun `a pre-cap bill whose stacked item promotions over-deducted an item is refused as changed`() =
        testApp { _ ->
            // Today the second 8.00 promotion on the 10.00 item takes only 2.00. Rewrite the stored bill to
            // what a Beleg signed before that cap holds: 8.00 + 8.00 = 16.00 off, -6.00 at 19 %, paid 14.00.
            val sale =
                fixture.closeSale(
                    SaleSpec(
                        items = listOf("10.00" to 0, "20.00" to 1),
                        paid = "20.00",
                        itemPromotions = listOf(0 to itemFixedPromotion("8.00"), 0 to itemFixedPromotion("8.00")),
                    ),
                )
            transaction {
                val bill = Bill.findById(sale.billId)!!
                val rows =
                    BillPromotion
                        .find { BillPromotionTable.billId eq sale.billId }
                        .sortedBy { it.id.value }
                assertMoney("2.00", rows[1].deductedAmount, "precondition: the cap took only what was left")
                rows[1].deductedAmount = BigDecimal("8.00")
                bill.promotionAmount = BigDecimal("16.00")
                bill.paidAmount = BigDecimal("14.00")
                bill.payments.single().amount = BigDecimal("14.00")
                val row19 = bill.vatDetails.single { it.vat.vatRate.compareTo(BigDecimal("0.19")) == 0 }
                row19.totalAmount = BigDecimal("-6.00")
                row19.netAmount = BigDecimal("-5.04")
                row19.vatAmount = BigDecimal("-0.96")
            }

            // Replaying the promotions now deducts 10.00, not the 16.00 the Beleg carries: refused, never
            // re-signed with a different discount. The operator stornos and bills again.
            assertRefused(PosErrorCode.BILL_REBOOK_PROMOTION_CHANGED, sale) {
                fixture.rebook(sale, request("14.00" to sale.cashTypeId))
            }
        }
}
