package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the DSFinV-K tip-reconciliation fix (audit finding H3).
 *
 * Two defects existed:
 * 1. `transactions.csv` `UMS_BRUTTO` was sourced from `bill.billAmount` alone, which EXCLUDES the
 *    tip — but `lines_vat.csv`/`lines.csv` already emit a dedicated tip row (`bill.tips`). So
 *    `Σ(lines_vat.POS_BRUTTO for a BON_ID) != UMS_BRUTTO` whenever a tip existed.
 * 2. `generateBusinessCases` only booked the Trinkgeld GV_TYP aggregate when
 *    `tipAmount > 0 && !isStorno`, so a storno reverse bill (whose `tips` field is already
 *    negated, mirroring `billAmount`/`discountAmount`/`promotionAmount`) was skipped entirely —
 *    leaving the TrinkgeldAN aggregate positive after a full reversal instead of netting to zero.
 *
 * Fix: `UMS_BRUTTO = billAmount + tips` (so the receipt gross includes the VAT-free tip, matching
 * `Σlines_vat`), and `generateBusinessCases` books the tip unconditionally on sign (`tipAmount !=
 * 0`), reusing the already-signed `bill.tips` value so storno reversals net to zero symmetrically
 * with how items/vouchers/promotions are handled.
 */
@DisplayName("DSFinV-K export — tip reconciliation (H3)")
class DsfinvkTipReconciliationTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `sale with a tip - lines_vat POS_BRUTTO sums to transactions UMS_BRUTTO`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TIPREC1")
            val category = TestData.createCategory("Tip Reconciliation Cat")
            val product = TestData.createProduct(category.id.value, "Tip Reconciliation Product", "TR001")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TIPREC-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val zBon =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
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

            val session = TableSession.findById(tws.session.id)!!
            val vat19 = vats[0]

            // Sale: 20.00 item + 3.00 tip, no discounts/promotions/vouchers.
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TIPREC-A"
                    this.billAmount = BigDecimal("20.00")
                    this.paidAmount = BigDecimal("23.00")
                    this.tips = BigDecimal("3.00")
                    this.tipRecipient = TipRecipient.EMPLOYEE
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            OrderItem.new {
                this.session = session
                this.product = product
                this.bill = bill
                this.unitPrice = BigDecimal("20.00")
                this.finalPrice = BigDecimal("20.00")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat19
                this.takeAway = false
            }

            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("23.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        val transactionsCsv = readZipEntry(zip, "transactions.csv")
        assertNotNull(transactionsCsv, "transactions.csv must be present in the export ZIP")
        val linesVatCsv = readZipEntry(zip, "lines_vat.csv")
        assertNotNull(linesVatCsv, "lines_vat.csv must be present in the export ZIP")

        val transactionRows = parseCsv(transactionsCsv)
        val linesVatRows = parseCsv(linesVatCsv)
        assertEquals(1, transactionRows.size, "fixture builds exactly one Beleg")

        val bonId = transactionRows.first()["BON_ID"]!!
        val umsBrutto = parseAmount(transactionRows.first()["UMS_BRUTTO"]!!)

        val lineVatRowsForBon = linesVatRows.filter { it["BON_ID"] == bonId }
        assertTrue(lineVatRowsForBon.isNotEmpty(), "expected at least one lines_vat row for BON_ID=$bonId")
        val sumPosBrutto = lineVatRowsForBon.sumOf { parseAmount(it["POS_BRUTTO"]!!) }

        assertTrue(
            umsBrutto.compareTo(BigDecimal("23.00")) == 0,
            "sanity: UMS_BRUTTO must include the 3.00 tip on top of the 20.00 item (=23.00); got $umsBrutto",
        )
        assertEquals(
            0,
            sumPosBrutto.compareTo(umsBrutto),
            "Σ(lines_vat.POS_BRUTTO) for BON_ID=$bonId ($sumPosBrutto) must equal transactions.UMS_BRUTTO ($umsBrutto)",
        )
    }

    @Test
    fun `full storno of a tipped sale nets the TrinkgeldAN businesscases aggregate to zero`() =
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val tws = TestData.createTableWithSession("TIPREC2")
            val category = TestData.createCategory("Tip Storno Cat")
            val product = TestData.createProduct(category.id.value, "Tip Storno Product", "TS001")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TIPSTORNO-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val zBon =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
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

            val session = TableSession.findById(tws.session.id)!!
            val vat19 = vats[0]

            // Original sale: 20.00 item + 5.00 employee tip.
            val originalBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TIPSTORNO-ORIG"
                    this.billAmount = BigDecimal("20.00")
                    this.paidAmount = BigDecimal("25.00")
                    this.tips = BigDecimal("5.00")
                    this.tipRecipient = TipRecipient.EMPLOYEE
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            // Reverse bill: fully negated (mirrors BillServiceImpl.deleteBill's reversal).
            val reverseBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TIPSTORNO-REV"
                    this.billAmount = BigDecimal("-20.00")
                    this.paidAmount = BigDecimal("-25.00")
                    this.tips = BigDecimal("-5.00")
                    this.tipRecipient = TipRecipient.EMPLOYEE
                    this.deletedBill = originalBill
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            OrderItem.new {
                this.session = session
                this.product = product
                this.bill = originalBill
                this.unitPrice = BigDecimal("20.00")
                this.finalPrice = BigDecimal("20.00")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat19
                this.takeAway = false
            }

            val businessCases = service.generateBusinessCases(zBons = listOf(zBon), cashRegisterId = "SN-1")
            val tipAggregateRows = businessCases.filter { it.gvTyp == DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AN }

            assertTrue(tipAggregateRows.isNotEmpty(), "expected at least one TrinkgeldAN aggregate row; got: $businessCases")

            val netTipBrutto = tipAggregateRows.sumOf { BigDecimal(it.zUmsBrutto) }
            val netTipNetto = tipAggregateRows.sumOf { BigDecimal(it.zUmsNetto) }

            assertEquals(
                0,
                netTipBrutto.compareTo(BigDecimal.ZERO),
                "TrinkgeldAN brutto must net to zero after full storno; got $netTipBrutto from $tipAggregateRows",
            )
            assertEquals(
                0,
                netTipNetto.compareTo(BigDecimal.ZERO),
                "TrinkgeldAN netto must net to zero after full storno; got $netTipNetto from $tipAggregateRows",
            )
        }

    /**
     * H3 acceptance criterion also has to hold when the Bon carries a bill-wide/line-level
     * PROMOTION (promotionAmount > 0). billAmount does NOT include the promotion deduction
     * (BillServiceImpl validates `vatSum == billAmount − promotionAmount`), and lines_vat emits a
     * negative promotion Rabatt row, so UMS_BRUTTO must net the promotion (billAmount −
     * promotionAmount + tips) to equal Σlines_vat.
     */
    @Test
    fun `sale with a promotion and a tip - lines_vat POS_BRUTTO sums to transactions UMS_BRUTTO`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TIPREC3")
            val category = TestData.createCategory("Tip Promo Cat")
            val product = TestData.createProduct(category.id.value, "Tip Promo Product", "TP001")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TIPPROMO-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val zBon = newZBon(shop, admin, now)
            val session = TableSession.findById(tws.session.id)!!
            val vat19 = vats[0]

            // Sale: 20.00 item, ITEM_SPECIFIC promotion deducting 4.00, 3.00 tip.
            // Paid total = billAmount(20) − promotionAmount(4) + tips(3) = 19.00.
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TIPPROMO-A"
                    this.billAmount = BigDecimal("20.00")
                    this.promotionAmount = BigDecimal("4.00")
                    this.paidAmount = BigDecimal("19.00")
                    this.tips = BigDecimal("3.00")
                    this.tipRecipient = TipRecipient.EMPLOYEE
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            val item =
                OrderItem.new {
                    this.session = session
                    this.product = product
                    this.bill = bill
                    this.unitPrice = BigDecimal("20.00")
                    this.finalPrice = BigDecimal("20.00")
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat19
                    this.takeAway = false
                }

            val promotion =
                Promotion.new {
                    this.name = "4 EUR off"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("4.00")
                    this.scope = PromotionScope.ITEM_SPECIFIC
                    this.targetProduct = product
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            BillPromotion.new {
                this.bill = bill
                this.promotion = promotion
                this.orderItem = item
                this.deductedAmount = BigDecimal("4.00")
                this.appliedBy = admin
                this.appliedAt = now
            }

            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("19.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }

        assertReconciles("promotion+tip")
    }

    /**
     * Pins the H3b fix: `generateLineVats` previously emitted the item row at the DISCOUNTED
     * `finalPrice` AND a negative discount Rabatt row of `−(unitPrice − finalPrice)`, so a
     * discounted line was understated by `discountAmount` (double-subtraction). Fixed by mirroring
     * `generateLineItems` (lines.csv): the item row now carries the UNDISCOUNTED `unitPrice` when
     * `item.discount < 1`, so `item(unitPrice) + rabatt(-discount) = finalPrice`.
     *
     * Also verifies the full three-way per-BON_ID reconciliation:
     * `Σlines_vat.POS_BRUTTO == transactions.UMS_BRUTTO == Σtransactions_vat.BON_BRUTTO`, and that
     * lines.csv and lines_vat.csv now agree on the discounted line's (undiscounted) item-row gross.
     */
    @Test
    fun `sale with a line discount and a tip - lines_vat POS_BRUTTO sums to transactions UMS_BRUTTO`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TIPREC4")
            val category = TestData.createCategory("Tip Discount Cat")
            val product = TestData.createProduct(category.id.value, "Tip Discount Product", "TD001")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TIPDISCOUNT-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val zBon = newZBon(shop, admin, now)
            val session = TableSession.findById(tws.session.id)!!
            val vat19 = vats[0]

            // Sale: item list price 20.00 discounted to 18.00 (10% off), plus a 3.00 tip.
            // billAmount = Σ finalPrice = 18.00; paid = 18 + 3 = 21.00.
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TIPDISCOUNT-A"
                    this.billAmount = BigDecimal("18.00")
                    this.paidAmount = BigDecimal("21.00")
                    this.tips = BigDecimal("3.00")
                    this.tipRecipient = TipRecipient.EMPLOYEE
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            OrderItem.new {
                this.session = session
                this.product = product
                this.bill = bill
                this.unitPrice = BigDecimal("20.00")
                this.finalPrice = BigDecimal("18.00")
                this.discount = BigDecimal("0.90")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat19
                this.takeAway = false
            }

            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("21.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }

        assertReconciles("discount+tip")
    }

    /**
     * Full three-way reconciliation for a discounted line, with NO tip. Tip is deliberately
     * omitted here: for a non-ADMIN employee tip, `BillServiceImpl.createBillVat` intentionally
     * excludes the tip from `BillVatDetail` (tips paid to an employee aren't taxable POS revenue),
     * so `Σtransactions_vat.BON_BRUTTO` would legitimately differ from `UMS_BRUTTO` in that case —
     * a separate, correct behavior that would conflate this assertion. Removing the tip isolates
     * the H3b fix and lets all three surfaces reconcile exactly.
     *
     * Confirms: `Σlines_vat.POS_BRUTTO == transactions.UMS_BRUTTO == Σtransactions_vat.BON_BRUTTO`,
     * and that lines.csv's item row and lines_vat.csv's item row now carry the SAME (undiscounted)
     * gross for the discounted line (previously lines_vat's item row was at the discounted
     * finalPrice, disagreeing with lines.csv's undiscounted unitPrice).
     */
    @Test
    fun `sale with a line discount - lines, lines_vat, and transactions_vat fully reconcile`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TIPREC5")
            val category = TestData.createCategory("Discount ThreeWay Cat")
            val product = TestData.createProduct(category.id.value, "Discount ThreeWay Product", "TW001")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TIPREC5-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val zBon = newZBon(shop, admin, now)
            val session = TableSession.findById(tws.session.id)!!
            val vat19 = vats[0]

            // Sale: item list price 20.00 discounted to 18.00 (10% off), NO tip.
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TIPREC5-A"
                    this.billAmount = BigDecimal("18.00")
                    this.paidAmount = BigDecimal("18.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.netAmount = BigDecimal("15.13")
                this.vatAmount = BigDecimal("2.87")
                this.totalAmount = BigDecimal("18.00")
            }

            OrderItem.new {
                this.session = session
                this.product = product
                this.bill = bill
                this.unitPrice = BigDecimal("20.00")
                this.finalPrice = BigDecimal("18.00")
                this.discount = BigDecimal("0.90")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat19
                this.takeAway = false
            }

            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("18.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        val transactionsCsv = readZipEntry(zip, "transactions.csv")
        assertNotNull(transactionsCsv, "transactions.csv must be present in the export ZIP")
        val linesCsv = readZipEntry(zip, "lines.csv")
        assertNotNull(linesCsv, "lines.csv must be present in the export ZIP")
        val linesVatCsv = readZipEntry(zip, "lines_vat.csv")
        assertNotNull(linesVatCsv, "lines_vat.csv must be present in the export ZIP")
        val transactionsVatCsv = readZipEntry(zip, "transactions_vat.csv")
        assertNotNull(transactionsVatCsv, "transactions_vat.csv must be present in the export ZIP")

        val transactionRows = parseCsv(transactionsCsv)
        val linesRows = parseCsv(linesCsv)
        val linesVatRows = parseCsv(linesVatCsv)
        val transactionsVatRows = parseCsv(transactionsVatCsv)
        assertEquals(1, transactionRows.size, "fixture builds exactly one Beleg")

        val bonId = transactionRows.first()["BON_ID"]!!
        val umsBrutto = parseAmount(transactionRows.first()["UMS_BRUTTO"]!!)

        assertTrue(
            umsBrutto.compareTo(BigDecimal("18.00")) == 0,
            "sanity: no tip/promotion, so UMS_BRUTTO must equal the discounted finalPrice (18.00); got $umsBrutto",
        )

        val lineVatRowsForBon = linesVatRows.filter { it["BON_ID"] == bonId }
        assertTrue(lineVatRowsForBon.isNotEmpty(), "expected lines_vat rows for BON_ID=$bonId")
        val sumPosBrutto = lineVatRowsForBon.sumOf { parseAmount(it["POS_BRUTTO"]!!) }

        val transactionsVatRowsForBon = transactionsVatRows.filter { it["BON_ID"] == bonId }
        assertTrue(transactionsVatRowsForBon.isNotEmpty(), "expected transactions_vat rows for BON_ID=$bonId")
        val sumBonBrutto = transactionsVatRowsForBon.sumOf { parseAmount(it["BON_BRUTTO"]!!) }

        // Full three-way reconciliation, per BON_ID.
        assertEquals(
            0,
            sumPosBrutto.compareTo(umsBrutto),
            "Σ(lines_vat.POS_BRUTTO) for BON_ID=$bonId ($sumPosBrutto) must equal transactions.UMS_BRUTTO ($umsBrutto)",
        )
        assertEquals(
            0,
            sumBonBrutto.compareTo(umsBrutto),
            "Σ(transactions_vat.BON_BRUTTO) for BON_ID=$bonId ($sumBonBrutto) must equal transactions.UMS_BRUTTO ($umsBrutto)",
        )

        // lines.csv and lines_vat.csv must agree on the item row's gross for the discounted line —
        // both keyed by the shared POS_ZEILE="1" (the item row; POS_ZEILE="2" is the Rabatt row on
        // both surfaces).
        val lineItemRow = linesRows.first { it["BON_ID"] == bonId && it["POS_ZEILE"] == "1" }
        val lineVatItemRow = lineVatRowsForBon.first { it["POS_ZEILE"] == "1" }
        val lineItemGross = parseAmount(lineItemRow["STK_BR"]!!)
        val lineVatItemGross = parseAmount(lineVatItemRow["POS_BRUTTO"]!!)

        assertEquals(
            0,
            lineItemGross.compareTo(BigDecimal("20.00")),
            "lines.csv item row must carry the UNDISCOUNTED unitPrice (20.00); got $lineItemGross",
        )
        assertEquals(
            0,
            lineVatItemGross.compareTo(lineItemGross),
            "lines.csv item row ($lineItemGross) and lines_vat.csv item row ($lineVatItemGross) must " +
                "agree on the discounted line's gross after the H3b fix",
        )
    }

    /**
     * Runs a full export and asserts Σ(lines_vat.POS_BRUTTO for the single Beleg) == its
     * transactions.UMS_BRUTTO. The fixtures above each build exactly one Beleg.
     */
    private fun assertReconciles(label: String) {
        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        val transactionsCsv = readZipEntry(zip, "transactions.csv")
        assertNotNull(transactionsCsv, "transactions.csv must be present in the export ZIP")
        val linesVatCsv = readZipEntry(zip, "lines_vat.csv")
        assertNotNull(linesVatCsv, "lines_vat.csv must be present in the export ZIP")

        val transactionRows = parseCsv(transactionsCsv)
        val linesVatRows = parseCsv(linesVatCsv)
        assertEquals(1, transactionRows.size, "[$label] fixture builds exactly one Beleg")

        val bonId = transactionRows.first()["BON_ID"]!!
        val umsBrutto = parseAmount(transactionRows.first()["UMS_BRUTTO"]!!)

        val lineVatRowsForBon = linesVatRows.filter { it["BON_ID"] == bonId }
        assertTrue(lineVatRowsForBon.isNotEmpty(), "[$label] expected lines_vat rows for BON_ID=$bonId")
        val sumPosBrutto = lineVatRowsForBon.sumOf { parseAmount(it["POS_BRUTTO"]!!) }

        assertEquals(
            0,
            sumPosBrutto.compareTo(umsBrutto),
            "[$label] Σ(lines_vat.POS_BRUTTO) for BON_ID=$bonId ($sumPosBrutto) must equal " +
                "transactions.UMS_BRUTTO ($umsBrutto); diff=${sumPosBrutto - umsBrutto}",
        )
    }

    private fun newZBon(
        shop: org.example.project.features.setup.domain.models.ShopInfo,
        admin: org.example.project.features.employee.domain.models.Employee,
        now: kotlinx.datetime.LocalDateTime,
    ): ZBon =
        ZBon.new {
            this.shopInfo = shop
            this.employee = admin
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

    /**
     * Reads a single CSV entry (by exact filename) out of the export ZIP as text, or null.
     *
     * Matches on the exact basename (not a bare `endsWith` on the full entry name) — several
     * DSFinV-K filenames share a suffix (e.g. "datapayment.csv" ends with "payment.csv"), so a
     * naive suffix match can silently return the wrong file's content.
     */
    private fun readZipEntry(
        zip: ByteArray,
        name: String,
    ): String? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == name) return zis.readBytes().decodeToString()
                entry = zis.nextEntry
            }
        }
        return null
    }

    /** Parses a `;`-delimited, `"`-quoted DSFinV-K CSV into a list of header->value maps. */
    private fun parseCsv(csv: String): List<Map<String, String>> {
        val lines = csv.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val header = splitCsvLine(lines.first())
        return lines.drop(1).map { line ->
            val fields = splitCsvLine(line)
            header.indices.associate { i -> header[i] to fields.getOrElse(i) { "" } }
        }
    }

    /** Quote-aware split of a `;`-delimited DSFinV-K CSV row. */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ';' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    /** Numeric DSFinV-K fields use a comma decimal separator. */
    private fun parseAmount(value: String): BigDecimal = BigDecimal(value.replace(",", "."))
}
