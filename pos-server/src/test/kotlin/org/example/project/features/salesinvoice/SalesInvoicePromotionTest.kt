package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.domain.enums.salesinvoice.EInvoiceFormatPreference
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.einvoice.DefaultEInvoiceParser
import org.example.project.einvoice.EInvoiceParseResult
import org.example.project.einvoice.check.EInvoiceInvariants
import org.example.project.einvoice.check.En16931AmountRules
import org.example.project.features.bill.RebookFixture
import org.example.project.features.bill.RebookFixture.SaleSpec
import org.example.project.features.bill.RebookFixture.Tender
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.product.domain.models.Product
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.salesinvoice.einvoice.SalesInvoiceToEInvoice
import org.example.project.features.setup.domain.models.ShopInfo
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
import java.math.RoundingMode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A till bill with a promotion (Aktion) becomes an Ausgangsrechnung whose lines are at the price
 * charged and whose promotions are EN 16931 document-level allowances (BG-20) — on the PDF as a
 * "Nachlass" block, in the CII as `SpecifiedTradeAllowanceCharge` + `AllowanceTotalAmount` (BT-107).
 *
 * Every bill goes through the REAL bill service ([RebookFixture]) under TseMode.SIMULATION, so the
 * VAT rows are the ones `BillServiceImpl` signed; the invoice must copy them and let its allowances
 * reconcile with them to the cent.
 */
@DisplayName("Sales invoice from a bill with a promotion")
class SalesInvoicePromotionTest {
    private val fixture = RebookFixture("si-promotion-device")

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private fun service() =
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
        InvoiceBuyer(name = "Musterfirma GmbH", street = "Industriestr. 5", zip = "10117", city = "Berlin", country = "DE")

    private var authorSeq = 0

    private fun author(): Employee {
        val n = ++authorSeq
        return TestData.createAdmin(employeeNumber = "SIP_A$n", username = "sip_a$n")
    }

    private fun issuer(): UUID = transaction { TestData.createAdmin(employeeNumber = "SIP_I${++authorSeq}", username = "sip_i$authorSeq").id.value }

    private fun newPromotion(
        name: String,
        scope: PromotionScope,
        type: DiscountType,
        value: String,
        targetProductId: Long? = null,
    ): Long {
        val createdBy = author()
        return transaction {
            val now = currentTimeStamp()
            Promotion
                .new {
                    this.name = name
                    this.description = null
                    this.discountType = type
                    this.discountValue = BigDecimal(value)
                    this.scope = scope
                    this.targetProduct = targetProductId?.let { Product.findById(it)!! }
                    this.isMultiUse = false
                    this.combinable = true
                    this.validFrom = now.toJavaLocalDateTime().minusSeconds(60).toKotlinLocalDateTime()
                    this.validUntil = now.toJavaLocalDateTime().plusSeconds(600).toKotlinLocalDateTime()
                    this.isActive = true
                    this.createdBy = Employee.findById(createdBy.id)!!
                    this.createdAt = now
                }.id.value
        }
    }

    private fun billWidePercent(percent: String) = newPromotion("Happy Hour", PromotionScope.BILL_WIDE, DiscountType.PERCENTAGE, percent)

    private fun itemFixed(
        name: String,
        amount: String,
    ): (Long) -> Long = { productId -> newPromotion(name, PromotionScope.ITEM_SPECIFIC, DiscountType.FIXED_AMOUNT, amount, productId) }

    private fun draft(billId: Long): Long = runBlocking { service().createFromBill(billId, buyer, createdBy = null) }.let { transaction { it.id.value } }

    private fun invoiceAndIssue(billId: Long): Long = draft(billId).also { runBlocking { service().issue(it, issuer()) } }

    private fun invoicesFor(billId: Long): Long = transaction { SalesInvoice.find { SalesInvoiceTable.billId eq billId }.count() }

    private fun assertMoney(
        expected: String,
        actual: BigDecimal?,
        what: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$what: expected $expected, got ${actual?.toPlainString()}")

    private fun net(
        gross: String,
        percent: String,
    ): BigDecimal = BigDecimal(gross).divide(BigDecimal.ONE + BigDecimal(percent).movePointLeft(2), 2, RoundingMode.HALF_UP)

    /**
     * The invoice's VAT rows are the bill's rows (less any owner-tip share, none here), its allowances
     * take the lines down to them per rate, and the §14 totals hold: BT-106 − BT-107 = BT-109 = Σ BT-116.
     */
    private fun assertReconciled(invoice: SalesInvoice) {
        val rows = invoice.vatBreakdown.toList()
        val allowances = invoice.allowanceCharges.toList()
        val net = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.taxableAmount }
        val vat = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.vatAmount }
        val allowanceNet = allowances.fold(BigDecimal.ZERO) { acc, a -> acc + a.amount }
        assertMoney(net.toPlainString(), invoice.taxExclusiveTotal, "BT-109 = Σ BT-116")
        assertMoney(allowanceNet.toPlainString(), invoice.allowanceTotal, "BT-107 = Σ BT-92")
        assertMoney((net + allowanceNet).toPlainString(), invoice.lineNetTotal, "BT-106 = BT-109 + BT-107")
        assertMoney(vat.toPlainString(), invoice.taxTotal, "BT-110 = Σ BT-117")
        assertMoney((net + vat).toPlainString(), invoice.taxInclusiveTotal, "BT-112 = BT-109 + BT-110")
        for (rate in rows.map { it.vatRate }) {
            val lineGross = invoice.lines.filter { it.vatRate.compareTo(rate) == 0 }.fold(BigDecimal.ZERO) { acc, l -> acc + l.lineGrossAmount }
            val allowanceGross = allowances.filter { it.vatRate.compareTo(rate) == 0 }.fold(BigDecimal.ZERO) { acc, a -> acc + a.grossAmount }
            val row = rows.filter { it.vatRate.compareTo(rate) == 0 }.fold(BigDecimal.ZERO) { acc, r -> acc + r.taxableAmount + r.vatAmount }
            assertMoney(row.toPlainString(), lineGross - allowanceGross, "at $rate %: Σ line gross − Σ allowance gross = the row")
        }
        allowances.forEach { a ->
            assertMoney(a.grossAmount.toPlainString(), a.amount + a.vatAmount, "allowance ${a.position}: net + VAT = gross")
            assertEquals("95", a.reasonCode, "UNTDID 5189 Discount")
            assertEquals(if (a.vatRate.signum() == 0) "Z" else "S", a.vatCategoryCode)
        }
    }

    /** The bill's signed rows, copied verbatim into the invoice (no owner tip here). */
    private fun assertRowsCopied(
        invoice: SalesInvoice,
        billId: Long,
    ) {
        val billRows = Bill.findById(billId)!!.vatDetails.toList()
        val rows = invoice.vatBreakdown.toList()
        assertEquals(billRows.size, rows.size)
        billRows.forEach { billRow ->
            val rate = billRow.vat.vatRate.movePointRight(2)
            val row = rows.single { it.vatRate.compareTo(rate) == 0 }
            assertMoney(billRow.netAmount.toPlainString(), row.taxableAmount, "copied net at $rate %")
            assertMoney(billRow.vatAmount.toPlainString(), row.vatAmount, "copied VAT at $rate %")
        }
    }

    @Test
    fun `a bill-wide percentage promotion at one rate is invoiced with one allowance`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(SaleSpec(paid = "20.70", promotionIds = listOf(billWidePercent("10.00"))))

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertEquals(SalesInvoiceStatus.ISSUED, invoice.status)
                    assertMoney("23.00", invoice.lines.single().lineGrossAmount, "the line at the price charged")
                    assertMoney("20.70", invoice.taxInclusiveTotal, "the bill")
                    assertMoney("20.70", invoice.prepaidAmount, "paid in full by card")
                    val allowance = invoice.allowanceCharges.single()
                    assertEquals("Aktion: Happy Hour", allowance.reasonText)
                    assertMoney("2.30", allowance.grossAmount, "what the till took off")
                    assertMoney((net("23.00", "19") - net("20.70", "19")).toPlainString(), allowance.amount, "BT-92 net")
                    val billPromotion = BillPromotion.find { BillPromotionTable.billId eq sale.billId }.single()
                    assertEquals(billPromotion.id, allowance.billPromotionId, "audit link to the bill_promotion row")
                    assertRowsCopied(invoice, sale.billId)
                    assertReconciled(invoice)
                }
            }
        }

    @Test
    fun `a bill-wide promotion over 19 and 7 percent is apportioned exactly as the bill signed it`() =
        testApp { _ ->
            fixture.withSimulationTse {
                // 10.00 @ 19 %, 5.00 @ 7 %, 10 % off = 1.50: 1.00 / 0.50 by the shared split.
                val sale =
                    fixture.closeSale(
                        SaleSpec(items = listOf("10.00" to 0, "5.00" to 1), paid = "13.50", promotionIds = listOf(billWidePercent("10.00"))),
                    )

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    val byRate = invoice.allowanceCharges.associateBy { it.vatRate.stripTrailingZeros().toPlainString() }
                    assertMoney("1.00", byRate.getValue("19").grossAmount, "19 % share")
                    assertMoney("0.50", byRate.getValue("7").grossAmount, "7 % share")
                    assertMoney("13.50", invoice.taxInclusiveTotal, "the bill")
                    assertRowsCopied(invoice, sale.billId)
                    assertReconciled(invoice)
                }
            }
        }

    @Test
    fun `an item-specific promotion is an allowance at its item's rate`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale =
                    fixture.closeSale(
                        SaleSpec(
                            items = listOf("10.00" to 0, "5.00" to 1),
                            paid = "14.00",
                            itemPromotions = listOf(1 to itemFixed("Brot-Aktion", "1.00")),
                        ),
                    )

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    val allowance = invoice.allowanceCharges.single()
                    assertEquals("Aktion: Brot-Aktion", allowance.reasonText)
                    assertMoney("7.00", allowance.vatRate, "the item's rate")
                    assertMoney("1.00", allowance.grossAmount, "the deduction")
                    assertMoney("14.00", invoice.taxInclusiveTotal, "the bill")
                    assertRowsCopied(invoice, sale.billId)
                    assertReconciled(invoice)
                }
            }
        }

    @Test
    fun `a promotion with an owner tip - the tip comes out by the post-promotion weights`() =
        testApp { _ ->
            fixture.withSimulationTse {
                // 10.00 @ 19 %, 10.00 @ 7 %, 5.00 off the 7 % item: post-promotion 10 : 5, so the 3.00
                // owner tip went in as 2.00 / 1.00 (the line gross would have said 1.50 / 1.50).
                val sale =
                    fixture.closeSale(
                        SaleSpec(
                            recipient = TipRecipient.OWNER,
                            items = listOf("10.00" to 0, "10.00" to 1),
                            originalTip = "3.00",
                            paid = "18.00",
                            itemPromotions = listOf(1 to itemFixed("Halber Preis", "5.00")),
                        ),
                    )
                transaction {
                    val rows =
                        Bill.findById(sale.billId)!!.vatDetails.associateBy {
                            it.vat.vatRate
                                .movePointRight(2)
                                .stripTrailingZeros()
                                .toPlainString()
                        }
                    assertMoney("12.00", rows.getValue("19").totalAmount, "precondition: 10.00 + 2.00 tip")
                    assertMoney("6.00", rows.getValue("7").totalAmount, "precondition: 5.00 + 1.00 tip")
                }

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertMoney("15.00", invoice.taxInclusiveTotal, "the meal after the promotion, never the tip")
                    val byRate = invoice.vatBreakdown.associateBy { it.vatRate.stripTrailingZeros().toPlainString() }
                    assertMoney(net("10.00", "19").toPlainString(), byRate.getValue("19").taxableAmount, "19 % meal")
                    assertMoney(net("5.00", "7").toPlainString(), byRate.getValue("7").taxableAmount, "7 % meal")
                    assertMoney("5.00", invoice.allowanceCharges.single().grossAmount, "the item promotion")
                    assertReconciled(invoice)
                }
            }
        }

    @Test
    fun `a promotion with an employee tip - the tip never had a VAT row`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale =
                    fixture.closeSale(
                        SaleSpec(recipient = TipRecipient.EMPLOYEE, originalTip = "2.00", paid = "22.70", promotionIds = listOf(billWidePercent("10.00"))),
                    )

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertMoney("20.70", invoice.taxInclusiveTotal, "the meal after the promotion")
                    assertMoney("2.30", invoice.allowanceCharges.single().grossAmount, "the promotion")
                    assertRowsCopied(invoice, sale.billId)
                    assertReconciled(invoice)
                }
            }
        }

    @Test
    fun `a promoted bill tendered on account within the meal is invoiced with the meal due`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale =
                    fixture.closeSale(
                        SaleSpec(tender = Tender.RECEIVABLE, paid = "20.70", promotionIds = listOf(billWidePercent("10.00"))),
                    )

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertEquals(RevenueOrigin.POS_RECEIVABLE, invoice.revenueOrigin)
                    assertMoney("20.70", invoice.amountDue, "the meal after the promotion is owed")
                    assertMoney("20.70", invoice.openAmount, "and open")
                    assertMoney("0", invoice.prepaidAmount, "nothing paid")
                    assertReconciled(invoice)
                }
            }
        }

    @Test
    fun `the Gutschrift of a promoted invoice reverses its allowances too`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale =
                    fixture.closeSale(
                        SaleSpec(items = listOf("10.00" to 0, "5.00" to 1), paid = "13.50", promotionIds = listOf(billWidePercent("10.00"))),
                    )
                val id = invoiceAndIssue(sale.billId)

                val storno = runBlocking { service().cancel(id, issuer(), "Falscher Empfänger") }.let { transaction { it.id.value } }

                transaction {
                    val original = SalesInvoice.findById(id)!!
                    val credit = SalesInvoice.findById(storno)!!
                    assertEquals("381", credit.invoiceTypeCode)
                    assertMoney(original.allowanceTotal!!.negate().toPlainString(), credit.allowanceTotal, "BT-107 negated")
                    val originals = original.allowanceCharges.sortedBy { it.position }
                    val reversed = credit.allowanceCharges.sortedBy { it.position }
                    assertEquals(originals.size, reversed.size)
                    originals.zip(reversed).forEach { (a, r) ->
                        assertEquals(a.reasonText, r.reasonText)
                        assertMoney(a.vatRate.toPlainString(), r.vatRate, "same rate")
                        assertMoney(a.amount.negate().toPlainString(), r.amount, "net negated")
                        assertMoney(a.vatAmount.negate().toPlainString(), r.vatAmount, "VAT negated")
                        assertMoney(a.grossAmount.negate().toPlainString(), r.grossAmount, "gross negated")
                    }
                    assertMoney(original.taxExclusiveTotal!!.negate().toPlainString(), credit.taxExclusiveTotal, "BT-109 negated")
                    assertMoney(original.lineNetTotal!!.negate().toPlainString(), credit.lineNetTotal, "BT-106 negated")
                }
            }
        }

    @Test
    fun `the archived PDF prints the Nachlass block below the lines`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(SaleSpec(paid = "20.70", promotionIds = listOf(billWidePercent("10.00"))))
                val id = invoiceAndIssue(sale.billId)

                val text = Loader.loadPDF(runBlocking { service().reprint(id) }).use { PDFTextStripper().getText(it) }

                assertTrue(text.contains("Nachlass"), text)
                assertTrue(text.contains("Aktion: Happy Hour (19 % USt)"), text)
                assertTrue(text.contains("-2,30 €"), text)
                assertTrue(text.contains("20,70 €"), "Gesamtbetrag = the bill: $text")
                assertTrue(text.indexOf("Nachlass") > text.indexOf("23,00 €"), "the block follows the lines")
                assertTrue(text.indexOf("Nachlass") < text.indexOf("Gesamtbetrag"), "and precedes the totals")
            }
        }

    @Test
    fun `a ZUGFeRD debtor gets BG-20 and BT-107 in the embedded CII, and it parses back consistent`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale =
                    fixture.closeSale(
                        SaleSpec(items = listOf("10.00" to 0, "5.00" to 1), paid = "13.50", promotionIds = listOf(billWidePercent("10.00"))),
                    )
                val id = draft(sale.billId)
                transaction {
                    SalesInvoice.findById(id)!!.debtor =
                        Debtor.new {
                            this.partner =
                                BusinessPartner.new {
                                    this.shop = ShopInfo.all().first()
                                    this.name = "Musterfirma GmbH"
                                }
                            this.shop = ShopInfo.all().first()
                            this.defaultRevenueAccountNo = "8400"
                            this.einvoiceFormat = EInvoiceFormatPreference.ZUGFERD
                        }
                }
                runBlocking { service().issue(id, issuer()) }

                val mapped =
                    transaction {
                        val invoice = SalesInvoice.findById(id)!!
                        SalesInvoiceToEInvoice.map(invoice, invoice.lines.toList(), invoice.vatBreakdown.toList())
                    }
                assertNull(En16931AmountRules.check(mapped), "the written invoice satisfies the EN 16931 amount rules")

                val result = DefaultEInvoiceParser().parse(runBlocking { service().reprint(id) }, "application/pdf")
                assertTrue(result is EInvoiceParseResult.Success, "got $result")
                val parsed = (result as EInvoiceParseResult.Success).invoice
                assertNull(EInvoiceInvariants.check(parsed))
                assertNull(En16931AmountRules.check(parsed), "the parsed invoice satisfies them too")
                assertEquals(2, parsed.allowancesCharges.size, "BG-20: one allowance per rate")
                assertTrue(parsed.allowancesCharges.none { it.isCharge })
                assertEquals(setOf("Aktion: Happy Hour"), parsed.allowancesCharges.map { it.reasonText }.toSet())
                assertEquals(setOf("95"), parsed.allowancesCharges.map { it.reasonCode }.toSet())
                val allowanceSum = parsed.allowancesCharges.fold(BigDecimal.ZERO) { acc, a -> acc + a.amount }
                assertMoney(allowanceSum.toPlainString(), parsed.allowanceTotal, "BT-107 = Σ BT-92")
                assertMoney((parsed.lineNetTotal!! - parsed.allowanceTotal!!).toPlainString(), parsed.taxExclusiveTotal, "BT-109 = BT-106 − BT-107")
                assertMoney(
                    (parsed.taxExclusiveTotal!! + parsed.taxTotal!!).toPlainString(),
                    parsed.taxInclusiveTotal,
                    "BT-112 = BT-109 + BT-110",
                )
                assertMoney("13.50", parsed.prepaidAmount, "BT-113: the bill, paid at the till")
                assertMoney("0", parsed.amountDue, "BT-115: nothing due")
            }
        }

    @Test
    fun `stored promotions that do not reproduce the bill are refused and leave no draft`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(SaleSpec(paid = "20.70", promotionIds = listOf(billWidePercent("10.00"))))
                transaction {
                    val row = BillPromotion.find { BillPromotionTable.billId eq sale.billId }.single()
                    row.deductedAmount = row.deductedAmount + BigDecimal("0.01")
                }

                val error = assertFailsWith<PosException> { draft(sale.billId) }

                assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_RECONCILABLE, error.code)
                assertTrue(error.message!!.contains("promotion amount is 2.30"), error.message)
                assertEquals(0L, invoicesFor(sale.billId), "a refusal leaves no draft behind")
            }
        }

    @Test
    fun `a bill without a promotion is invoiced exactly as before - no allowance, no BT-107`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertTrue(invoice.allowanceCharges.empty())
                    assertNull(invoice.allowanceTotal, "no zero BT-107")
                    assertMoney(invoice.lineNetTotal!!.toPlainString(), invoice.taxExclusiveTotal, "BT-106 == BT-109")
                    val mapped = SalesInvoiceToEInvoice.map(invoice, invoice.lines.toList(), invoice.vatBreakdown.toList())
                    assertTrue(mapped.allowancesCharges.isEmpty())
                    assertNull(mapped.allowanceTotal)
                    assertTrue(
                        !String(
                            org.example.project.einvoice.syntax.CiiInvoiceWriter
                                .write(mapped),
                        ).contains("Allowance"),
                        "the CII carries no allowance element",
                    )
                }
            }
        }

    @Test
    fun `two item promotions stacked past the item's price are capped at the item and the bill is invoiced`() =
        testApp { _ ->
            fixture.withSimulationTse {
                // Two item-specific promotions of 8.00 each on one 10.00 item: the second takes only the 2.00 left.
                val sale =
                    fixture.closeSale(
                        SaleSpec(
                            items = listOf("10.00" to 0, "20.00" to 1),
                            paid = "20.00",
                            itemPromotions = listOf(0 to itemFixed("A", "8.00"), 0 to itemFixed("B", "8.00")),
                        ),
                    )

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertEquals(SalesInvoiceStatus.ISSUED, invoice.status)
                    assertMoney("20.00", invoice.taxInclusiveTotal, "the bill")
                    val allowances = invoice.allowanceCharges.sortedBy { it.position }
                    assertEquals(listOf("Aktion: A", "Aktion: B"), allowances.map { it.reasonText })
                    assertEquals(
                        listOf("8.00", "2.00"),
                        allowances.map { it.grossAmount.setScale(2).toPlainString() },
                        "each allowance is what its promotion actually deducted",
                    )
                    assertRowsCopied(invoice, sale.billId)
                    assertReconciled(invoice)
                }
            }
        }

    @Test
    fun `a legacy bill whose item promotions over-deducted an item below zero is refused and leaves no draft`() =
        testApp { _ ->
            fixture.withSimulationTse {
                // Bill creation now caps the second 8.00 promotion at the 2.00 left; rewrite the stored rows
                // to what a bill signed before that fix holds (8.00 + 8.00, a -6.00 row at 19 %).
                val sale =
                    fixture.closeSale(
                        SaleSpec(
                            items = listOf("10.00" to 0, "20.00" to 1),
                            paid = "20.00",
                            itemPromotions = listOf(0 to itemFixed("A", "8.00"), 0 to itemFixed("B", "8.00")),
                        ),
                    )
                transaction {
                    val bill = Bill.findById(sale.billId)!!
                    val second = BillPromotion.find { BillPromotionTable.billId eq sale.billId }.maxBy { it.id.value }
                    second.deductedAmount = BigDecimal("8.00")
                    bill.promotionAmount = BigDecimal("16.00")
                }

                val error = assertFailsWith<PosException> { draft(sale.billId) }

                assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_RECONCILABLE, error.code)
                assertTrue(error.message!!.contains("negative"), error.message)
                assertEquals(0L, invoicesFor(sale.billId), "a refusal leaves no draft behind")
            }
        }

    @Test
    fun `a capped legacy bill-wide promotion over 100 percent is invoiced as a zero invoice`() =
        testApp { _ ->
            fixture.withSimulationTse {
                // A legacy 150 % promotion (seeded directly, the editor refuses it): 34.50 is capped to 23.00
                // and the bill row at 19 % is 0.00.
                val promotionId = newPromotion("Alles gratis", PromotionScope.BILL_WIDE, DiscountType.PERCENTAGE, "150.00")
                val sale = fixture.closeSale(SaleSpec(paid = "0.00", promotionIds = listOf(promotionId)))
                transaction {
                    val bill = Bill.findById(sale.billId)!!
                    assertMoney("23.00", bill.promotionAmount, "precondition: the cap engaged")
                    assertMoney("0.00", bill.vatDetails.single().totalAmount, "precondition: a zero row")
                }

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertEquals(SalesInvoiceStatus.ISSUED, invoice.status)
                    assertMoney("0", invoice.taxInclusiveTotal, "BT-112")
                    val allowance = invoice.allowanceCharges.single()
                    assertMoney("23.00", allowance.grossAmount, "the whole line")
                    assertMoney(net("23.00", "19").toPlainString(), allowance.amount, "the allowance net is the line net")
                    assertMoney(allowance.amount.toPlainString(), invoice.lineNetTotal, "BT-106 = BT-107")
                    assertReconciled(invoice)
                }
            }
        }
}
