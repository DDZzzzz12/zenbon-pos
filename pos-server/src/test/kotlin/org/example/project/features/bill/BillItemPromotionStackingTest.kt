package org.example.project.features.bill

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.bill.RebookFixture.SaleSpec
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.product.domain.models.Product
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Item-specific promotions (Aktionen) may stack on one order item, but never below zero: each
 * deduction is bounded by what the promotions applied earlier in the request left on the item.
 *
 * Before the fix each deduction was bounded by the item's whole `finalPrice`, so two 8.00 promotions
 * on a 10.00 item took 16.00 off it: the 19 % row signed −6.00 (negative turnover on a supply that
 * was sold), the 7 % row 20.00 — which still passed the Σ rows == paid invariant and was signed and
 * exported as such.
 *
 * Every bill goes through the REAL bill service ([RebookFixture]). Under TseMode.SIMULATION
 * `TseTransaction.processDataHash` stores the signed processData verbatim.
 */
@DisplayName("Stacked item promotions never take an item below zero")
class BillItemPromotionStackingTest {
    private val deviceId = "item-promo-stacking-device"
    private val fixture = RebookFixture(deviceId)
    private var authorSeq = 0

    @AfterEach
    fun tearDown() {
        NotificationHub.resetForTest()
        BillNumberTracker.clearAll()
    }

    private fun itemPromotion(
        name: String,
        type: DiscountType,
        value: String,
    ): (Long) -> Long =
        { productId ->
            val n = ++authorSeq
            val author = TestData.createAdmin(employeeNumber = "IPST_A$n", username = "ipst_a$n")
            transaction {
                val now = currentTimeStamp()
                Promotion
                    .new {
                        this.name = name
                        this.description = null
                        this.discountType = type
                        this.discountValue = BigDecimal(value)
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

    private fun fixed(
        name: String,
        value: String,
    ) = itemPromotion(name, DiscountType.FIXED_AMOUNT, value)

    /** 10.00 @ 19 % (item 0) and 20.00 @ 7 % (item 1), every promotion on item 0. */
    private fun saleOnTheTenEuroItem(
        paid: String,
        vararg promotions: (Long) -> Long,
    ) = fixture.closeSale(
        SaleSpec(
            items = listOf("10.00" to 0, "20.00" to 1),
            paid = paid,
            itemPromotions = promotions.map { 0 to it },
        ),
    )

    /** The bill's BillPromotion.deductedAmount rows in the order they were applied. */
    private fun deductions(billId: Long): List<String> =
        BillPromotion
            .find { BillPromotionTable.billId eq billId }
            .orderBy(BillPromotionTable.id to SortOrder.ASC)
            .map { it.deductedAmount.setScale(2).toPlainString() }

    /** The bill's signed VAT rows as rate percent → gross. */
    private fun rows(bill: Bill): Map<String, String> =
        bill.vatDetails.associate {
            it.vat.vatRate
                .movePointRight(2)
                .stripTrailingZeros()
                .toPlainString() to it.totalAmount.setScale(2).toPlainString()
        }

    private fun signedProcessData(bill: Bill): String =
        assertNotNull(bill.tseTransaction, "bill ${bill.billNumber} must be TSE-signed, or this proves nothing").processDataHash!!

    private fun assertMoney(
        expected: String,
        actual: BigDecimal?,
        what: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$what: expected $expected, got ${actual?.toPlainString()}")

    @Test
    fun `two 8 euro promotions on a 10 euro item - the second takes only the 2 euro left`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = saleOnTheTenEuroItem("20.00", fixed("A", "8.00"), fixed("B", "8.00"))

                transaction {
                    val bill = Bill.findById(sale.billId)!!
                    assertEquals(listOf("8.00", "2.00"), deductions(sale.billId), "the second promotion takes only what is left")
                    assertMoney("10.00", bill.promotionAmount, "Σ deductions == the item")
                    assertMoney("20.00", bill.paidAmount, "paid")
                    assertEquals(mapOf("19" to "0.00", "7" to "20.00"), rows(bill), "no negative row")
                    assertEquals("Beleg^0.00_20.00_0.00_0.00_0.00^20.00:Unbar", signedProcessData(bill))
                }
            }
        }

    @Test
    fun `percentage then fixed on one item - the fixed promotion is capped at the rest`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale =
                    saleOnTheTenEuroItem("20.00", itemPromotion("Halber Preis", DiscountType.PERCENTAGE, "50.00"), fixed("Acht", "8.00"))

                transaction {
                    val bill = Bill.findById(sale.billId)!!
                    assertEquals(listOf("5.00", "5.00"), deductions(sale.billId))
                    assertEquals(mapOf("19" to "0.00", "7" to "20.00"), rows(bill))
                    assertEquals("Beleg^0.00_20.00_0.00_0.00_0.00^20.00:Unbar", signedProcessData(bill))
                }
            }
        }

    @Test
    fun `fixed then percentage - the percentage keeps the item price as its base`() =
        testApp { _ ->
            fixture.withSimulationTse {
                // 3.00 off, then 50 % of the 10.00 price (5.00, not 50 % of the 7.00 left): 2.00 remain.
                val sale =
                    saleOnTheTenEuroItem("22.00", fixed("Drei", "3.00"), itemPromotion("Halber Preis", DiscountType.PERCENTAGE, "50.00"))

                transaction {
                    val bill = Bill.findById(sale.billId)!!
                    assertEquals(listOf("3.00", "5.00"), deductions(sale.billId))
                    assertEquals(mapOf("19" to "2.00", "7" to "20.00"), rows(bill))
                    assertEquals("Beleg^2.00_20.00_0.00_0.00_0.00^22.00:Unbar", signedProcessData(bill))
                }
            }
        }

    @Test
    fun `fixed then free item - the free item takes what is left`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = saleOnTheTenEuroItem("20.00", fixed("Drei", "3.00"), itemPromotion("Gratis", DiscountType.FREE_ITEM, "0"))

                transaction {
                    val bill = Bill.findById(sale.billId)!!
                    assertEquals(listOf("3.00", "7.00"), deductions(sale.billId))
                    assertEquals(mapOf("19" to "0.00", "7" to "20.00"), rows(bill))
                    assertEquals("Beleg^0.00_20.00_0.00_0.00_0.00^20.00:Unbar", signedProcessData(bill))
                }
            }
        }

    @Test
    fun `a third promotion that finds nothing left is recorded with zero`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = saleOnTheTenEuroItem("20.00", fixed("Vier", "4.00"), fixed("Sechs", "6.00"), fixed("Fünf", "5.00"))

                transaction {
                    val bill = Bill.findById(sale.billId)!!
                    assertEquals(listOf("4.00", "6.00", "0.00"), deductions(sale.billId), "the audit row stays, at zero")
                    assertMoney("10.00", bill.promotionAmount, "Σ deductions")
                    assertEquals(mapOf("19" to "0.00", "7" to "20.00"), rows(bill))
                    assertEquals("Beleg^0.00_20.00_0.00_0.00_0.00^20.00:Unbar", signedProcessData(bill))
                }
            }
        }

    @Test
    fun `stacked promotions that fit the item deduct exactly what they did before`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = saleOnTheTenEuroItem("23.00", fixed("Drei", "3.00"), fixed("Vier", "4.00"))

                transaction {
                    val bill = Bill.findById(sale.billId)!!
                    assertEquals(listOf("3.00", "4.00"), deductions(sale.billId))
                    assertEquals(mapOf("19" to "3.00", "7" to "20.00"), rows(bill))
                    assertEquals("Beleg^3.00_20.00_0.00_0.00_0.00^23.00:Unbar", signedProcessData(bill))
                }
            }
        }

    @Test
    fun `the capped bill exports Rabatt lines equal to its promotion and no negative turnover`() =
        testApp(setupData = {
            TestData.setTseMode(TseMode.DISABLED)
            TestData.createAdmin()
        }) { client ->
            val sale = saleOnTheTenEuroItem("20.00", fixed("A", "8.00"), fixed("B", "8.00"))
            val zip = closeAndExport(client)
            val bon = "B-${sale.billId}"

            val rabatt =
                readRows(zip, "lines.csv")
                    .filter { it["BON_ID"] == bon && it["GV_TYP"] == DsfinvkExportDto.BusinessCaseTypes.RABATT }
            assertEquals(listOf("-8,00", "-2,00"), rabatt.map { it["STK_BR"] }, "one Rabatt line per promotion, as applied")
            assertMoney("-10.00", rabatt.fold(BigDecimal.ZERO) { acc, r -> acc + parse(r["STK_BR"]!!) }, "Σ Rabatt == promotion amount")

            val txVat = readRows(zip, "transactions_vat.csv").filter { it["BON_ID"] == bon }
            txVat.forEach { assertTrue(parse(it["BON_BRUTTO"]!!).signum() >= 0, "no negative turnover per key: $txVat") }
            val linesVat = readRows(zip, "lines_vat.csv").filter { it["BON_ID"] == bon }
            txVat.forEach { row ->
                val key = row["UST_SCHLUESSEL"]
                val lines = linesVat.filter { it["UST_SCHLUESSEL"] == key }.fold(BigDecimal.ZERO) { acc, r -> acc + parse(r["POS_BRUTTO"]!!) }
                assertMoney(parse(row["BON_BRUTTO"]!!).toPlainString(), lines, "key $key: Σ lines_vat == transactions_vat")
            }
            assertEquals(
                "20,00",
                readRows(zip, "transactions.csv").single { it["BON_ID"] == bon }["UMS_BRUTTO"],
                "the Beleg's turnover is what was paid",
            )
            val rabattCases =
                readRows(zip, "businesscases.csv").filter { it["GV_TYP"] == DsfinvkExportDto.BusinessCaseTypes.RABATT }
            assertMoney(
                "-10.00",
                rabattCases.fold(BigDecimal.ZERO) { acc, r -> acc + parse(r["Z_UMS_BRUTTO"]!!) },
                "Rabatt business cases",
            )
        }

    /** Closes the day through the real Z-Bon route and exports it. */
    private suspend fun closeAndExport(client: HttpClient): ByteArray {
        transaction {
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
        }
        val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
        val closing = client.post("/api/z-bon") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Created, closing.status, "body: ${closing.bodyAsText()}")
        return DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())
            .generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
    }

    private fun parse(amount: String): BigDecimal = BigDecimal(amount.replace(",", "."))

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
