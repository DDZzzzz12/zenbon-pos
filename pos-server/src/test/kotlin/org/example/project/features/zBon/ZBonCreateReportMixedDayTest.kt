package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Service-level regression test for [org.example.project.features.zBon.data.service.ZBonServiceImpl.createReport]
 * with a mixed fiscal day: normal bills, a storno pair, a training bill, a cancellation,
 * a voucher sale + redeem, two payment types and two VAT rates.
 *
 * Pins the exact report totals so any change to the Z-Bon computation (grouping,
 * ordering, rounding, BigDecimal arithmetic) that alters the output is caught.
 */
@DisplayName("Z-Bon createReport — mixed day totals")
class ZBonCreateReportMixedDayTest {
    @Test
    fun `creates report with exact totals for a mixed day`() =
        testApp(
            setupData = { setupMixedDay() },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")

            val dto = testJson.decodeFromString<ZBonDto.ZBonResponseDto>(response.bodyAsText())

            // Core totals
            assertEquals("231.00", dto.totalRevenue) // 119.00 + 107.00 active revenue + 5.00 admin tip
            assertEquals("5.00", dto.adminTip)
            assertEquals("0.00", dto.totalDiscounts)
            assertEquals("8.00", dto.cancelledAmount)
            assertEquals("20.00", dto.refundAmount)
            assertEquals(1, dto.stornoBillCount)
            assertEquals("50.00", dto.trainingRevenue)
            assertEquals(1, dto.trainingBillCount)

            // Cash flow: opening 0, Z-Bon kassenbuch entry = 231.00 gross - 107.00 non-cash = 124.00.
            // 124.00 is the cash physically tendered on bill1 — the owner tip is inside its VAT rows.
            assertEquals("0.00", dto.openingBalance)
            assertEquals("0.00", dto.expenses)
            assertEquals("124.00", dto.closingBalance)

            // VAT groups — one per rate, only from active (non-storno, non-training) bills
            val vatGroups = assertNotNull(dto.vatGroups)
            assertEquals(2, vatGroups.size)
            val vat19 = assertNotNull(vatGroups.find { it.vatRate == "19.00" })
            assertEquals("104.20", vat19.netAmount)
            assertEquals("19.80", vat19.vatAmount)
            assertEquals("124.00", vat19.grossAmount) // 119.00 turnover + the 5.00 owner tip folded in
            val vat7 = assertNotNull(vatGroups.find { it.vatRate == "7.00" })
            assertEquals("100.00", vat7.netAmount)
            assertEquals("7.00", vat7.vatAmount)
            assertEquals("107.00", vat7.grossAmount)

            // Payment summaries per type/currency
            val payments = assertNotNull(dto.paymentSummary)
            assertEquals(2, payments.size)
            val cashSummary = assertNotNull(payments.find { it.paymentName == "Cash" })
            assertEquals("EUR", cashSummary.currency)
            assertEquals("124.00", cashSummary.amount)
            val cardSummary = assertNotNull(payments.find { it.paymentName == "Card" })
            assertEquals("EUR", cardSummary.currency)
            assertEquals("107.00", cardSummary.amount)

            // Employee summaries — single admin with per-payment breakdown
            val employeeSummaries = assertNotNull(dto.employeeSummaries)
            assertEquals(1, employeeSummaries.size)
            val adminSummary = employeeSummaries.first()
            assertEquals("ADMIN", adminSummary.role)
            // The 5.00 is an OWNER tip: it is carried by adminTip alone, never by a summary
            // (ZBonOwnerTipRule — summaries hold employee tips only, by the bill's tipRecipient).
            assertEquals("0.00", adminSummary.amount)
            assertFalse(adminSummary.ownerTip, "a current closing's summary carries no owner tip")
            assertEquals("226.00", adminSummary.totalRevenue)
            assertEquals(2, adminSummary.paymentBreakdown.size)
            assertEquals(
                "124.00",
                adminSummary.paymentBreakdown.find { it.paymentName == "Cash" }?.amount,
            )
            assertEquals(
                "107.00",
                adminSummary.paymentBreakdown.find { it.paymentName == "Card" }?.amount,
            )
            assertEquals("0.00", dto.totalEmployeeTips, "the day has no employee tip")

            // Vouchers and redeems assigned to the period
            val vouchers = assertNotNull(dto.vouchers)
            assertEquals(1, vouchers.size)
            assertEquals("V-100", vouchers.first().voucherNumber)
            assertEquals("25.00", vouchers.first().originalValue)
            val redeems = assertNotNull(dto.voucherRedeems)
            assertEquals(1, redeems.size)
            assertEquals("10.00", redeems.first().redeemedAmount)

            // Kassenbuch data is embedded in the created report
            assertNotNull(dto.kassenbuchSummaryDto)
        }

    @Test
    fun `z-bon kassenbuch lines carry kinds and payment type refs`() =
        testApp(
            setupData = { setupMixedDay() },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")

            transaction {
                val header =
                    KassenbuchHeader
                        .all()
                        .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                        .first()
                val lines = header.lineItems.toList()
                val revenue = lines.filter { it.ustSatz != null }
                val rateless = lines.filter { it.ustSatz == null }
                assertTrue(revenue.isNotEmpty(), "expected revenue lines")
                assertTrue(rateless.isNotEmpty(), "expected rate-less lines")
                revenue.forEach { assertEquals(KassenbuchLineKind.STANDARD, it.kind) }

                // A rate-less line must be a kind that DECLARES it needs no VAT rate. Asserted
                // against the enum's own `requiresVatRate` rather than a literal kind: as of
                // ac1feb345 a cash Forderungsausgleich emits RECEIVABLE_SETTLEMENT lines that are
                // rate-less too, and the next rate-less kind must not fail here either.
                rateless.forEach {
                    assertFalse(
                        it.kind.requiresVatRate,
                        "line '${it.belegtext}' carries no VAT rate, but kind ${it.kind} declares " +
                            "requiresVatRate=true — a rated kind must not emit a rate-less line",
                    )
                }

                // The NON_CASH_PAYMENT subset keeps its own, stricter guards: a missing
                // paymentTypeId is what made these lines resolve to Resolution.Legacy and 409 the
                // whole period's DATEV export, and the belegtext is TSE-signed (byte identity).
                // Both are specific to this kind — a RECEIVABLE_SETTLEMENT line has its own
                // belegtext and a deliberately nullable paymentTypeId.
                val nonCash = rateless.filter { it.kind == KassenbuchLineKind.NON_CASH_PAYMENT }
                assertTrue(nonCash.isNotEmpty(), "expected non-cash lines")
                nonCash.forEach {
                    assertNotNull(it.paymentType, "paymentTypeId must be set on new Z-Bon non-cash lines")
                    // TSE-relevant text is unchanged (byte-identity regression)
                    assertTrue(it.belegtext!!.startsWith("TE:Unbare Zahlung: "))
                }
            }
        }

    private fun setupMixedDay() {
        val admin = TestData.createAdmin()
        TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates() // [0]=19% Normal, [1]=7% Reduced
        val cash = TestData.createPaymentType("Cash", isCash = true)
        val card = TestData.createPaymentType("Card", isCash = false)
        val tws = TestData.createTableWithSession("Z1")
        val category = TestData.createCategory("ZBon Cat")
        val product = TestData.createProduct(category.id.value, "ZBon Product", "ZBP001")

        transaction {
            // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")

            val now = currentTimeStamp()

            // Terminal for the token's deviceId (no X-Device-ID header at login => "unknown")
            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            // Close the session and free the table so Z-Bon validation passes
            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = now
            table.currentSession = null
            table.tableStatus = TableStatus.FREE

            val vat19 = vats[0]
            val vat7 = vats[1]

            fun newBill(
                nr: String,
                amount: String,
                paid: String,
                tip: String = "0.00",
                training: Boolean = false,
            ): Bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = nr
                    this.billAmount = BigDecimal(amount)
                    this.paidAmount = BigDecimal(paid)
                    this.tips = BigDecimal(tip)
                    this.startTime = now
                    this.endTime = now
                    this.isTraining = training
                }

            // Closed by an ADMIN, so the tip is TrinkgeldAG (OWNER) — what BillRepositoryImpl derives
            // for an ADMIN closer, and what this test's own "adminTip 5.00" assertions describe.
            // Set explicitly because the column DEFAULTS to EMPLOYEE: leaving it would model a
            // non-taxable TrinkgeldAN closed by an admin, a shape production cannot produce, and
            // DrawerCashDerivation would then correctly add the 5.00 back to the drawer (plan
            // 2026-09-16 P1.3) and this fixture's 119.00 closing balance would no longer hold.
            val bill1 =
                newBill("ZBMIX-1", "119.00", "124.00", tip = "5.00").apply {
                    this.tipRecipient = TipRecipient.OWNER
                }
            val bill2 = newBill("ZBMIX-2", "107.00", "107.00")
            newBill("ZBMIX-3", "50.00", "50.00", training = true)
            val bill4 = newBill("ZBMIX-4", "20.00", "20.00").apply { deletingReason = "storno test" }
            newBill("ZBMIX-5", "-20.00", "-20.00").apply { deletedBill = bill4 }

            // Production shape for an OWNER tip on a bill WITH items: BillServiceImpl folds the tip
            // across the item rates (`OwnerTipWeights` + `PromotionApportionment`), so the single
            // 19 % row carries 119.00 + 5.00 = 124.00 — and step 8 then enforces
            // `Σ vatDetails == paidAmount` (124.00) because the non-taxable tip is zero for an OWNER.
            // A 119.00 row against a 124.00 paidAmount is a shape `createBill` would REJECT, and it
            // understated the drawer by the tip while this same test asserted 124.00 cash was taken.
            BillVatDetail.new {
                this.bill = bill1
                this.vat = vat19
                this.netAmount = BigDecimal("104.20")
                this.vatAmount = BigDecimal("19.80")
                this.totalAmount = BigDecimal("124.00")
            }
            BillVatDetail.new {
                this.bill = bill2
                this.vat = vat7
                this.netAmount = BigDecimal("100.00")
                this.vatAmount = BigDecimal("7.00")
                this.totalAmount = BigDecimal("107.00")
            }

            fun pay(
                target: Bill,
                type: PaymentType,
                value: String,
            ): Payment =
                Payment.new {
                    this.bill = target
                    this.paymentType = type
                    this.amount = BigDecimal(value)
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                }
            pay(bill1, cash, "124.00")
            val cardPayment = pay(bill2, card, "107.00")

            // Cancelled item => cancelledAmount 8.00
            val cancelledItem =
                OrderItem.new {
                    this.session = session
                    this.product = product
                    this.unitPrice = BigDecimal("8.00")
                    this.finalPrice = BigDecimal("8.00")
                    this.itemStatus = ItemStatus.CANCELLED
                    this.vat = vat19
                    this.takeAway = false
                }
            Cancellation.new {
                this.orderItem = cancelledItem
                this.reason = "dropped plate"
                this.employeeId = admin
                this.createdAt = now
                this.isTraining = false
            }

            // Voucher sold in the period (order item on bill2) + partial redeem
            val voucherItem =
                OrderItem.new {
                    this.session = session
                    this.product = product
                    this.bill = bill2
                    this.unitPrice = BigDecimal("25.00")
                    this.finalPrice = BigDecimal("25.00")
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat19
                    this.takeAway = false
                }
            val voucher =
                Voucher.new {
                    this.orderItem = voucherItem
                    this.voucherNumber = "V-100"
                    this.originalValue = BigDecimal("25.00")
                    this.remainingValue = BigDecimal("15.00")
                    this.createdAt = now
                }
            VoucherRedeem.new {
                this.voucher = voucher
                this.payment = cardPayment
                this.redeemAmount = BigDecimal("10.00")
                this.redeemedAt = now
                this.voucherType = VoucherSourceType.INTERNAL
                this.voucherNumber = "V-100"
            }
        }
    }
}
