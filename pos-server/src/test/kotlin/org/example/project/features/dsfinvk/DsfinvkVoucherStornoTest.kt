package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals

/**
 * Pins the DSFinV-K sign-flip fix for voucher redemption on a bill storno (H1).
 *
 * A sale paid with a voucher redemption (+10 item, -10 `MehrzweckgutscheinEinloesung`
 * redemption) that is fully storno'd must have its `lines.csv` redemption row AND its
 * `businesscases.csv` voucher GV_TYP aggregate net to zero across the original + reverse
 * Belege. Previously the reverse bill re-queried the original's (unnegated) redemption and
 * emitted/aggregated it a second time with the SAME negative sign, leaving the voucher
 * totals at -20 instead of 0 after a full storno.
 */
@DisplayName("DSFinV-K voucher redemption — storno sign flip (H1)")
class DsfinvkVoucherStornoTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    private val deviceId = "dsfinvk-voucher-storno-device"

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    /** The hand-built test below owns its database; the service-driven one gets it from [testApp]. */
    private fun <T> withDatabase(block: () -> T): T {
        TestDatabase.setup()
        try {
            return block()
        } finally {
            TestDatabase.teardown()
        }
    }

    @Test
    fun `full storno of a voucher-redeemed sale nets redemption lines and businesscases to zero`() = withDatabase { handBuiltVoucherStorno() }

    private fun handBuiltVoucherStorno() =
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val voucherType = TestData.createPaymentType("Voucher", isCash = false, isVoucher = true)
            val tws = TestData.createTableWithSession("VST1")
            val category = TestData.createCategory("Voucher Storno Cat")
            val product = TestData.createProduct(category.id.value, "Voucher Storno Product", "VST001")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TEST"
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

            // Sale: +10.00 item, fully paid by a -10.00 voucher redemption.
            val originalBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "VST-ORIG"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            val reverseBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "VST-REV"
                    this.billAmount = BigDecimal("-10.00")
                    this.paidAmount = BigDecimal("-10.00")
                    this.tips = BigDecimal.ZERO
                    this.deletedBill = originalBill
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            val item =
                OrderItem.new {
                    this.session = session
                    this.product = product
                    this.bill = originalBill
                    this.unitPrice = BigDecimal("10.00")
                    this.finalPrice = BigDecimal("10.00")
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat19
                    this.takeAway = false
                }

            val voucherPayment =
                Payment.new {
                    this.bill = originalBill
                    this.paymentType = voucherType
                    this.amount = BigDecimal("10.00")
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.REFUNDED
                    this.paymentTime = now
                    this.refundTime = now
                    this.refundedBillId = reverseBill
                }

            val voucher =
                Voucher.new {
                    this.orderItem = item
                    this.voucherNumber = "V-VST-1"
                    this.originalValue = BigDecimal("25.00")
                    this.remainingValue = BigDecimal("15.00")
                    this.createdAt = now
                }
            VoucherRedeem.new {
                this.voucher = voucher
                this.payment = voucherPayment
                this.redeemAmount = BigDecimal("10.00")
                this.redeemedAt = now
                this.voucherType = VoucherSourceType.INTERNAL
                this.voucherNumber = "V-VST-1"
            }

            // --- lines.csv: redemption line must net to zero across original + reverse Beleg ---
            val lines =
                service.generateLineItems(
                    bills = listOf(originalBill, reverseBill),
                    zBons = listOf(zBon),
                    cashRegisterId = "SN-1",
                )

            val redemptionLines = lines.filter { it.gvTyp == DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG }
            assertEquals(
                2,
                redemptionLines.size,
                "expected exactly one redemption line per Beleg (original + reverse), got: $redemptionLines",
            )

            val originalRedemptionLine = redemptionLines.single { it.bonId == "B-${originalBill.id.value}" }
            val reverseRedemptionLine = redemptionLines.single { it.bonId == "B-${reverseBill.id.value}" }
            assertEquals("-10.00", originalRedemptionLine.stkBr, "original Beleg redemption line must stay negative")
            assertEquals(
                "10.00",
                reverseRedemptionLine.stkBr,
                "storno reverse Beleg must emit a POSITIVE redemption line, not double-emit the original's negative",
            )

            val netRedemption = redemptionLines.sumOf { BigDecimal(it.stkBr) }
            assertEquals(
                0,
                netRedemption.compareTo(BigDecimal.ZERO),
                "redemption stkBr must net to zero across original+reverse; got $netRedemption from $redemptionLines",
            )

            // --- businesscases.csv: voucher GV_TYP aggregate must net to zero after full storno ---
            val businessCases = service.generateBusinessCases(zBons = listOf(zBon), cashRegisterId = "SN-1")
            val voucherAggregateRows = businessCases.filter { it.gvTyp == DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG }
            val netVoucherBrutto = voucherAggregateRows.sumOf { BigDecimal(it.zUmsBrutto) }
            val netVoucherNetto = voucherAggregateRows.sumOf { BigDecimal(it.zUmsNetto) }
            assertEquals(
                0,
                netVoucherBrutto.compareTo(BigDecimal.ZERO),
                "voucher GV_TYP brutto must net to zero after full storno; got $netVoucherBrutto from $voucherAggregateRows",
            )
            assertEquals(
                0,
                netVoucherNetto.compareTo(BigDecimal.ZERO),
                "voucher GV_TYP netto must net to zero after full storno; got $netVoucherNetto from $voucherAggregateRows",
            )
        }

    /**
     * An ORDINARY voucher storno through the REAL service: a 50,00 € @19 % sale fully paid by one
     * voucher (`createBill`), then `deleteBill`. The reverse Beleg refunds the voucher tender and
     * `deleteBill` stamps the redemption `reversedByBill` = the reverse bill ITSELF — branch (b) of
     * `redemptionsForBeleg`. The reverse Beleg's header nets goods −50 against the refunded voucher
     * +50, so the POSITIVE redemption line and its key-5 `lines_vat` mirror are required for
     * Σ lines == UMS_BRUTTO.
     */
    @Test
    fun `ordinary voucher storno still emits its positive redemption line`() {
        var reverseId = 0L
        testApp(setupData = { reverseId = voucherSaleThenStorno() }) { _ ->
            val zip =
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            val bon = "B-$reverseId"

            val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == bon }
            assertEquals("0,00", kopf["UMS_BRUTTO"], "reverse Beleg nets goods -50 against the refunded voucher +50")

            val lines = readRows(zip, "lines.csv").filter { it["BON_ID"] == bon }
            val redemptions = lines.filter { it["GV_TYP"] == DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG }
            assertEquals(1, redemptions.size, "the reverse Beleg keeps exactly one redemption line, got $lines")
            assertEquals("50,00", redemptions.single()["STK_BR"], "and it is POSITIVE (reversing the original's -50)")

            val lv = readRows(zip, "lines_vat.csv").filter { it["BON_ID"] == bon }
            assertEquals(1, lv.count { it["UST_SCHLUESSEL"] == "5" }, "its key-5 lines_vat mirror stays, got $lv")
        }
    }

    /** createBill (50,00 € voucher tender) → deleteBill, then attributes both Belege to one closing. */
    private fun voucherSaleThenStorno(): Long {
        val admin = TestData.createAdmin(employeeNumber = "DVS_ADM", username = "dvs_adm")
        val shop = TestData.createShopInfo()
        val vat19 = TestData.createDefaultVatRates().first()
        val voucherTender = TestData.createPaymentType("Voucher_DVS", isCash = false, isVoucher = true)
        val tws = TestData.createTableWithSession("DVS")
        val category = TestData.createCategory("DVS Cat")
        val product = TestData.createProduct(category.id.value, "DVS Product", "DVS_P", BigDecimal("50.0000"))
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-DVS")

        val itemId =
            transaction {
                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = admin
                OrderItem
                    .new {
                        this.session = session
                        this.product = product
                        this.unitPrice = BigDecimal("50.00")
                        this.finalPrice = BigDecimal("50.00")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vat19
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }.id.value
            }

        val billService = GlobalContext.get().get<BillService>()
        val billId =
            runBlocking {
                billService
                    .createBill(
                        employeeId = admin.id.value.toString(),
                        deviceId = deviceId,
                        request =
                            BillDto.CreateBillRequestDto(
                                tableSessionId = tws.session.id.value,
                                orderItems = listOf(itemId),
                                billAmount = "50.00",
                                tips = "0.00",
                                payments = listOf(PaymentDto.PaymentRequest(amount = "50.00", paymentTypeId = voucherTender.id.value)),
                                voucherRedeem =
                                    listOf(
                                        VoucherDto.RedeemVoucherRequestDto(
                                            voucherNumber = "EXT-DVS-1",
                                            redeemedAmount = "50.00",
                                            voucherSource = VoucherSourceType.EXTERNAL,
                                        ),
                                    ),
                            ),
                    ).billId
            }
        runBlocking { billService.deleteBill(admin.id.value.toString(), deviceId, billId.toString(), "voucher storno") }

        return transaction {
            val reverse = Bill.find { BillTable.deletedBillId eq billId }.single()
            val redeem =
                Bill
                    .findById(billId)!!
                    .payments
                    .single()
                    .voucherRedeem!!
            assertEquals(reverse.id.value, redeem.reversedByBill?.id?.value, "deleteBill stamps the reverse bill itself")
            // deleteBill refuses a closed day, so the closing is attached afterwards.
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            Bill.all().forEach { it.zBon = zBon }
            reverse.id.value
        }
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ';' && !inQuotes -> {
                    out.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    private fun readRows(
        zip: ByteArray,
        name: String,
    ): List<Map<String, String>> {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == name) {
                    val lines =
                        zis
                            .readBytes()
                            .decodeToString()
                            .split("\r\n")
                            .filter { it.isNotBlank() }
                    if (lines.size < 2) return emptyList()
                    val header = splitCsvLine(lines.first())
                    return lines.drop(1).map { header.zip(splitCsvLine(it)).toMap() }
                }
                entry = zis.nextEntry
            }
        }
        error("$name not found in export zip")
    }
}
