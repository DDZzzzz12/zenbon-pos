package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.testutils.testKassenbuchDocuments
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
 * Regression test for `Z_START_ID`/`Z_ENDE_ID` in cashpointclosing.csv (Task 19 / M7).
 *
 * Pre-fix, [DsfinvkExportServiceImpl.generateStammAbschluss] derived `Z_START_ID`/`Z_ENDE_ID`
 * solely from the closing's Bills (sorted by `endTime`), even though a Kassenabschluss can also
 * contain Order (`O-`), Cancellation (`C-`) and Kassenbuch (`KB-`) transactions with their own
 * namespaced `BON_ID`s (see [org.example.project.export.dsfinvk.builder.BonIdNamespace]). Per
 * spec, `Z_START_ID`/`Z_ENDE_ID` = "Erste/Letzte BON_ID im Abschluss" — across ALL transactions,
 * not just bills. Two consequences of the bug:
 *  1. If a non-bill transaction is chronologically first or last, it was silently excluded.
 *  2. A closing with only Kassenbuch entries and no bills exported EMPTY `Z_START_ID`/`Z_ENDE_ID`.
 */
@DisplayName("DSFinV-K export — Z_START_ID/Z_ENDE_ID span all transaction types")
class DsfinvkZStartEndTest {
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
    fun `Z_START_ID and Z_ENDE_ID reflect the chronological first-last transaction across bill, order and cancellation`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val tws = TestData.createTableWithSession("ZSE1")

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-ZSE-TEST"
                registeredAt = currentTimeStamp()
                lastSeenAt = currentTimeStamp()
            }

            val zBon =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    this.startTime = LocalDateTime(2000, 1, 1, 0, 0)
                    this.endTime = LocalDateTime(2100, 1, 1, 0, 0)
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = LocalDateTime(2026, 7, 9, 12, 0)
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }
            val session = TableSession.findById(tws.session.id)!!

            // Chronological order within the closing: Order (08:00) -> Bill (09:00) -> Cancellation (10:00).
            // A naive lexicographic sort of the namespaced BON_IDs ("B-" < "C-" < "O-") would wrongly
            // pick the bill as first and the order as last; neither is correct here.
            val orderTime = LocalDateTime(2026, 7, 9, 8, 0)
            val billTime = LocalDateTime(2026, 7, 9, 9, 0)
            val cancellationTime = LocalDateTime(2026, 7, 9, 10, 0)

            val order =
                Orders.new {
                    this.orderNumber = "ORD-1"
                    this.employee = admin
                    this.tableSession = session
                    this.orderStatus = OrderStatus.CONFIRMED
                    this.orderTime = orderTime
                    this.orderTotal = BigDecimal("5.00")
                    this.tseSigningStatus = TseSigningStatus.PENDING
                    this.isTraining = false
                }

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "BILL-1"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = billTime
                    this.endTime = billTime
                    this.zBon = zBon
                }

            val cancelledItem =
                OrderItem.new {
                    this.session = session
                    this.product = TestData.createProduct(TestData.createCategory("ZSE Cat").id.value, "Tea", "TEA")
                    this.unitPrice = BigDecimal("3.00")
                    this.finalPrice = BigDecimal("3.00")
                    this.vat = TestData.createDefaultVatRates()[0]
                    this.itemStatus = ItemStatus.CANCELLED
                    this.takeAway = false
                }
            val cancellation =
                Cancellation.new {
                    this.orderItem = cancelledItem
                    this.reason = "test storno"
                    this.employeeId = admin
                    this.createdAt = cancellationTime
                    this.zBon = zBon
                    this.tseSigningStatus = TseSigningStatus.PENDING
                    this.isTraining = false
                }

            val expectedFirstBonId = "O-${order.id.value}"
            val expectedLastBonId = "C-${cancellation.id.value}"
            // Sanity: prove the naive lexicographic prefix sort would NOT produce this ordering.
            val billBonId = "B-${bill.id.value}"
            assertTrue(
                listOf(billBonId, expectedFirstBonId, expectedLastBonId).sorted() != listOf(expectedFirstBonId, billBonId, expectedLastBonId),
                "fixture must NOT already be lexicographically ordered — otherwise this test can't distinguish chronological from lexicographic ordering",
            )
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        // Recompute expected ids by re-querying (transaction block above closed already).
        val (expectedFirstBonId, expectedLastBonId) =
            transaction {
                val order = Orders.all().first { it.orderNumber == "ORD-1" }
                val cancellation = Cancellation.all().first()
                "O-${order.id.value}" to "C-${cancellation.id.value}"
            }

        val closingCsv = readZipEntry(zip, "cashpointclosing.csv")
        assertNotNull(closingCsv, "cashpointclosing.csv must be present in the export ZIP")
        val closingRow = dataRows(closingCsv).single().split(";").map { it.trim('"') }

        // Columns per StammAbschlussRowDto: Z_KASSE_ID, Z_ERSTELLUNG, Z_NR, Z_BUCHUNGSTAG,
        // TAXONOMIE_VERSION, Z_START_ID(5), Z_ENDE_ID(6), ...
        val zStartId = closingRow[5]
        val zEndeId = closingRow[6]

        assertEquals(expectedFirstBonId, zStartId, "Z_START_ID must be the chronologically first transaction's BON_ID (the order), not the bill")
        assertEquals(expectedLastBonId, zEndeId, "Z_ENDE_ID must be the chronologically last transaction's BON_ID (the cancellation), not the bill")

        // Cross-check against transactions.csv: Z_START_ID/Z_ENDE_ID must line up with BON_NR=1 / BON_NR=N
        // for the same closing. Since BON_NR carries the PRINTED bill number (audit BKOPF-5),
        // it is no longer a chronological sequence — the linkage is BON_START, then BON_ENDE, then
        // BON_ID, exactly how generateStammAbschluss orders the closing.
        //
        // BON_ENDE joined the key when BON_START became the Vorgang's first ORDER time (M9): a
        // Kassenbeleg and the Bestellung it grew out of then start at the same instant, and
        // lexical BON_ID would put "B-…" ahead of the "O-…" that preceded it.
        val transactionsCsv = readZipEntry(zip, "transactions.csv")
        assertNotNull(transactionsCsv)
        val txRows = dataRows(transactionsCsv).map { it.split(";").map { c -> c.trim('"') } }
        // Columns: Z_KASSE_ID(0), Z_ERSTELLUNG(1), Z_NR(2), BON_ID(3), BON_NR(4), ...,
        // BON_START(9), BON_ENDE(10)
        val chronological = txRows.sortedWith(compareBy({ it[9] }, { it[10] }, { it[3] }))
        assertEquals(zStartId, chronological.first()[3], "Z_START_ID must equal the chronologically first row's BON_ID")
        assertEquals(zEndeId, chronological.last()[3], "Z_ENDE_ID must equal the chronologically last row's BON_ID")
    }

    @Test
    fun `Z_START_ID and Z_ENDE_ID are non-empty for a closing with only a Kassenbuch entry and no bills`() {
        val repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())

        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()

            val zBon =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    // Deliberately wide window: see DsfinvkBonNrUniquenessTest for why (the Kassenbuch
                    // entry's createdAt is stamped by a table default, not this test's clock).
                    this.startTime = LocalDateTime(2000, 1, 1, 0, 0)
                    this.endTime = LocalDateTime(2100, 1, 1, 0, 0)
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = LocalDateTime(2026, 7, 9, 12, 0)
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }

            repository.createEntry(
                shop = shop,
                employee = admin,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_EXPENSE,
                        belegNr = "2026-07-KB1",
                        vorzBetrag = "-4.0",
                        belegDatum = "09.07.2026",
                        belegtext = "Barausgabe",
                    ),
                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "-4.0", ustSatz = "19")),
            )
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        val closingCsv = readZipEntry(zip, "cashpointclosing.csv")
        assertNotNull(closingCsv, "cashpointclosing.csv must be present in the export ZIP")
        val closingRow = dataRows(closingCsv).single().split(";").map { it.trim('"') }
        val zStartId = closingRow[5]
        val zEndeId = closingRow[6]

        assertTrue(zStartId.isNotBlank(), "Z_START_ID must be non-empty for a closing with only a Kassenbuch entry; got '$zStartId'")
        assertTrue(zEndeId.isNotBlank(), "Z_ENDE_ID must be non-empty for a closing with only a Kassenbuch entry; got '$zEndeId'")
        assertTrue(zStartId.startsWith("KB-"), "expected the Kassenbuch BON_ID namespace; got '$zStartId'")
        assertEquals(zStartId, zEndeId, "with a single transaction in the closing, Z_START_ID and Z_ENDE_ID must be equal")
    }

    /** Data rows (skip header) of a `\r\n`-terminated DSFinV-K CSV. */
    private fun dataRows(csv: String): List<String> = csv.split("\r\n").filter { it.isNotBlank() }.drop(1)

    /** Reads a single CSV entry (by filename suffix) out of the export ZIP as text, or null. */
    private fun readZipEntry(
        zip: ByteArray,
        name: String,
    ): String? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(name)) return zis.readBytes().decodeToString()
                entry = zis.nextEntry
            }
        }
        return null
    }
}
