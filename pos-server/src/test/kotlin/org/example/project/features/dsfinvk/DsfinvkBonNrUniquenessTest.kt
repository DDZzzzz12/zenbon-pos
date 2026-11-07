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
 * Regression test for BON_NR collisions/zeros within a single Kassenabschluss (Task 18 / M6).
 *
 * Pre-fix, `BON_NR` was derived independently per source from unrelated numbering spaces:
 * - bills/orders: [DsfinvkExportServiceImpl.parseBillNumber] strips non-digits with a `?: 0L`
 *   fallback, so a non-numeric bill/order number (e.g. "DPS-ORIG") emits `BON_NR=0`, and two
 *   different numbers that happen to contain the same digit substring collide (e.g. "X-1" and
 *   "O-1" both parse to `1`).
 * - cancellations: `cancellation.id.value` (the Cancellation table's own PK sequence).
 * - kassenbuch: `belegNr.substringAfterLast('-')` parsed as Long, falling back to
 *   `entry.id.value` (the KassenbuchHeader table's own PK sequence) when unparsable.
 *
 * Because bills/orders/cancellations/kassenbuch entries are four independent `LongIdTable`s each
 * starting their own PK sequence at 1, the fallback/PK-derived values collide across sources
 * within the same Z-Bon (closing) — this test forces exactly that by making the first row of
 * every table have PK/number `1`. DSFinV-K requires `BON_NR` to be unique (and meaningful/non-zero)
 * within one Kassenabschluss.
 */
@DisplayName("DSFinV-K export — BON_NR uniqueness within a closing")
class DsfinvkBonNrUniquenessTest {
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
    fun `bills, order, cancellation and kassenbuch entry in one closing get unique non-zero BON_NR`() {
        val repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())

        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val tws = TestData.createTableWithSession("BN1")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-BN-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val zBon =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    // Deliberately wide (not `now`..`now`): the Kassenbuch entry below is created via
                    // KassenbuchRepositoryImpl, whose `createdAt` column default is resolved once at
                    // table-init time (not per-insert), so it will NOT equal this test's captured
                    // `now`. generateKassenbuchTransactions() falls back to a
                    // startTime<=createdAt<=endTime scan when entry.zBon is null (it always is here —
                    // createEntry() never assigns it) — a zero-width window would make that scan miss
                    // and silently place the entry in its own phantom Z_NR=0 "closing", defeating the
                    // point of this test (all 5 rows must be the SAME closing).
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
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }
            val session = TableSession.findById(tws.session.id)!!

            // Bill #1: a non-numeric bill number — pre-fix parseBillNumber(..) ?: 0L → BON_NR=0.
            Bill.new {
                this.employeeId = admin
                this.tableSession = session
                this.billNumber = "DPS-ORIG"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
                this.zBon = zBon
            }

            // Bill #2: number containing digit "1" — pre-fix parseBillNumber(..) → 1.
            Bill.new {
                this.employeeId = admin
                this.tableSession = session
                this.billNumber = "X-1"
                this.billAmount = BigDecimal("12.00")
                this.paidAmount = BigDecimal("12.00")
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
                this.zBon = zBon
            }

            // Order #1: number containing digit "1" — pre-fix parseBillNumber(..) → 1, colliding
            // with Bill #2 above.
            Orders.new {
                this.orderNumber = "O-1"
                this.employee = admin
                this.tableSession = session
                this.orderStatus = OrderStatus.CONFIRMED
                this.orderTime = now
                this.orderTotal = BigDecimal("5.00")
                this.tseSigningStatus = TseSigningStatus.PENDING
                this.isTraining = false
            }

            // Cancellation #1: pre-fix bonNr = cancellation.id.value = 1, colliding with the two
            // rows above.
            val cancelledItem =
                OrderItem.new {
                    this.session = session
                    this.product = TestData.createProduct(TestData.createCategory("BN Cat").id.value, "Tea", "TEA")
                    this.unitPrice = BigDecimal("3.00")
                    this.finalPrice = BigDecimal("3.00")
                    this.vat = TestData.createDefaultVatRates()[0]
                    this.itemStatus = ItemStatus.CANCELLED
                    this.takeAway = false
                }
            Cancellation.new {
                this.orderItem = cancelledItem
                this.reason = "test storno"
                this.employeeId = admin
                this.createdAt = now
                this.zBon = zBon
                this.tseSigningStatus = TseSigningStatus.PENDING
                this.isTraining = false
            }

            // Kassenbuch entry #1 with a non-numeric belegNr suffix — pre-fix
            // belegNr.substringAfterLast('-').toLongOrNull() is null → fallback to entry.id.value
            // = 1, colliding with the three rows above.
            repository.createEntry(
                shop = shop,
                employee = admin,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_EXPENSE,
                        belegNr = "2026-07-XYZ",
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

        val transactionsCsv = readZipEntry(zip, "transactions.csv")
        assertNotNull(transactionsCsv, "transactions.csv must be present in the export ZIP")

        // BON_NR is column index 4 in transactions.csv (Z_KASSE_ID, Z_ERSTELLUNG, Z_NR, BON_ID,
        // BON_NR, ...).
        val rows = dataRows(transactionsCsv)
        val bonNrs = rows.map { it.split(";")[4].trim('"') }

        assertEquals(5, bonNrs.size, "expected 5 transaction header rows (2 bills + order + cancellation + kassenbuch); got:\n$transactionsCsv")

        assertTrue(bonNrs.none { it == "0" }, "BON_NR must never be 0 (meaningless); got $bonNrs")
        assertEquals(
            bonNrs.size,
            bonNrs.distinct().size,
            "BON_NR must be unique within the closing; got $bonNrs",
        )
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
