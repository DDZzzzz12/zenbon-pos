package org.example.project.features.zBon

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.dsfinvk.domain.service.DsfinvkExportService
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.models.KassenbuchLineItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.DrawerCashDerivation
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Characterises the two fiscal reads of "the ORIGINAL bill behind a clearing bill's invoice" (B2-3
 * A8, F6) before they move from the accounting extension `Bill.clearsSalesInvoice` to the ecr port
 * `BillInvoiceLink`:
 *
 * - **DSFinV-K** (`DsfinvkExportServiceImpl`, header references): a clearing bill references the
 *   bill that opened the receivable with one `Transaktion` row; when the invoice names no bill, the
 *   clearing carries no reference row at all.
 * - **Drawer cash** (`DrawerCashDerivation.receivableTenderOf`, reached through the Z-Bon closing):
 *   the aggregate's `RECEIVABLE_SETTLEMENT` line names the tender that OPENED the receivable (the
 *   original's `createsReceivable` leg); with no original bill it names none.
 * - `DrawerCashDerivation.unbookedCashTakings` (cash count, Kassenminus guard) sums the clearing's
 *   cash whatever tender opened it.
 *
 * **Failure mode of a dangling invoice id.** The DSFinV-K path dereferenced the invoice with `!!`,
 * the drawer path with `?.`. A `bill.clears_sales_invoice_id` naming no `sales_invoice` row cannot
 * be stored: `fk_bill_clears_sales_invoice_id__id` (`ON DELETE RESTRICT`, `CrossAreaForeignKeys`)
 * rejects both the dangling write and the deletion of a cleared invoice — pinned by the last two
 * tests. Neither branch is therefore reachable from data the database accepts.
 *
 * Every service is resolved from the production Koin graph (`testApp`), so this file does not depend
 * on any constructor signature and stays unchanged across the port switch.
 */
@DisplayName("Clearing bill -> original bill lookup (DSFinV-K references, drawer cash tender)")
class ClearingBillOriginalLookupCharacterisationTest {
    // ---------------------------------------------------------------- DSFinV-K

    @Test
    fun `DSFinV-K - a clearing whose invoice has an original bill references that bill once`() =
        testApp(setupData = { seed(invoiceHasOriginal = true, closed = true) }) { _ ->
            val zip = export()
            val (clearingId, originalId) = ids()
            val refs = readRows(zip, "references.csv").filter { it["BON_ID"] == "B-$clearingId" }
            assertEquals(1, refs.size, "one header reference to the original Vorgang, got $refs")
            assertEquals("Transaktion", refs.single()["REF_TYP"])
            assertEquals("B-$originalId", refs.single()["REF_BON_ID"])
            assertEquals("", refs.single()["POS_ZEILE"], "header-level reference")
            assertEquals("1", refs.single()["REF_Z_NR"], "the original's closing")
        }

    @Test
    fun `DSFinV-K - a clearing whose invoice has no original bill carries no reference row`() =
        testApp(setupData = { seed(invoiceHasOriginal = false, closed = true) }) { _ ->
            val zip = export()
            val (clearingId, _) = ids()
            val refs = readRows(zip, "references.csv").filter { it["BON_ID"] == "B-$clearingId" }
            assertEquals(emptyList(), refs, "no original Vorgang, nothing to reference")
            val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == "B-$clearingId" }
            assertEquals("23,00", kopf["UMS_BRUTTO"], "the clearing itself is still exported")
        }

    // ---------------------------------------------------------------- drawer cash

    @Test
    fun `drawer - the settlement line names the tender that opened the receivable`() =
        testApp(setupData = { seed(invoiceHasOriginal = true, closed = false) }) { client ->
            assertDrawerBefore(expected = "123.00")
            closeDay(client)
            transaction {
                val line = settlementLine()
                val onAccount = PaymentType.all().single { it.name == RECEIVABLE_TENDER }
                assertEquals(onAccount.id.value, line.paymentType?.id?.value)
                assertEquals("TE:Forderungsausgleich (bar): $RECEIVABLE_TENDER", line.belegtext)
                assertEquals(0, BigDecimal("23.00").compareTo(line.vorzBetrag))
            }
        }

    @Test
    fun `drawer - with no original bill the settlement line names no tender`() =
        testApp(setupData = { seed(invoiceHasOriginal = false, closed = false) }) { client ->
            assertDrawerBefore(expected = "123.00")
            closeDay(client)
            transaction {
                val line = settlementLine()
                assertNull(line.paymentType, "no original, no opening tender")
                assertEquals("TE:Forderungsausgleich (bar)", line.belegtext)
                assertEquals(0, BigDecimal("23.00").compareTo(line.vorzBetrag))
            }
        }

    // ---------------------------------------------------------------- dangling id

    @Test
    fun `a clearing bill cannot name an invoice that does not exist`() =
        testApp(setupData = { seed(invoiceHasOriginal = true, closed = false) }) { _ ->
            val (clearingId, _) = ids()
            assertFails("fk_bill_clears_sales_invoice_id__id must reject a dangling id") {
                transaction { Bill.findById(clearingId)!!.clearsSalesInvoiceId = 987_654_321L }
            }
        }

    @Test
    fun `an invoice a clearing bill settles cannot be deleted`() =
        testApp(setupData = { seed(invoiceHasOriginal = true, closed = false) }) { _ ->
            val (clearingId, _) = ids()
            assertFails("ON DELETE RESTRICT must keep the cleared invoice") {
                transaction { SalesInvoice.findById(Bill.findById(clearingId)!!.clearsSalesInvoiceId!!)!!.delete() }
            }
            transaction { assertNotNull(Bill.findById(clearingId)!!.clearsSalesInvoiceId) }
        }

    // ---------------------------------------------------------------- helpers

    private fun export(): ByteArray =
        runBlocking {
            GlobalContext
                .get()
                .get<DsfinvkExportService>()
                .generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    private suspend fun closeDay(client: HttpClient) {
        val token = TestAuthHelper.loginAsAdmin(client)
        val response = client.post("/api/z-bon") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")
    }

    /** 100.00 cash sale + 23.00 opened auf Rechnung (non-cash) + 23.00 cash clearing = 123.00 in the drawer. */
    private fun assertDrawerBefore(expected: String) {
        transaction {
            assertEquals(0, BigDecimal(expected).compareTo(DrawerCashDerivation.unbookedCashTakings()), "unbooked cash takings")
        }
    }

    private fun settlementLine(): KassenbuchLineItem {
        val entry =
            assertNotNull(
                KassenbuchHeader
                    .find { KassenbuchHeaderTable.isZbonAggregate eq true }
                    .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                    .firstOrNull(),
                "the Z-Bon should have booked its aggregate cash entry",
            )
        return entry.lineItems.single { it.kind == KassenbuchLineKind.RECEIVABLE_SETTLEMENT }
    }

    private fun billId(number: String): Long {
        val bill = Bill.all().single { it.billNumber == number }
        return bill.id.value
    }

    private fun ids(): Pair<Long, Long> =
        transaction {
            billId("CBO-CLR") to billId("CBO-OPEN")
        }

    /**
     * One 100.00 cash sale, one 23.00 sale opened under the non-cash `createsReceivable` tender
     * [RECEIVABLE_TENDER], an ISSUED receivable invoice (naming that original only when
     * [invoiceHasOriginal]) and a 23.00 cash clearing bill settling it. With [closed] every bill is
     * attributed to closing #1 (DSFinV-K exports by closing); otherwise the day is left open for the
     * Z-Bon under test.
     */
    private fun seed(
        invoiceHasOriginal: Boolean,
        closed: Boolean,
    ) {
        val admin = TestData.createAdmin()
        val shop = TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates()
        val cash = TestData.createPaymentType("Bar", isCash = true)
        val onAccount = TestData.createPaymentType(RECEIVABLE_TENDER, isCash = false)
        val tws = TestData.createTableWithSession("CBO1")
        val zBon = if (closed) TestData.createZBonFullDay(shop, admin, nummer = 1L) else null

        transaction {
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
            val now = currentTimeStamp()
            PaymentType.findById(onAccount.id)!!.createsReceivable = true

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = now
            table.currentSession = null
            table.tableStatus = TableStatus.FREE

            fun bill(
                number: String,
                amount: String,
                tender: PaymentType,
                createsReceivable: Boolean,
                withVat: Boolean,
                clears: Long? = null,
            ): Bill {
                val gross = BigDecimal(amount)
                val bill =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = number
                        this.billAmount = gross
                        this.paidAmount = gross
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.zBon = zBon
                        this.clearsSalesInvoiceId = clears
                    }
                if (withVat) {
                    val net = gross.divide(BigDecimal("1.19"), 2, RoundingMode.HALF_UP)
                    BillVatDetail.new {
                        this.bill = bill
                        this.vat = vats[0]
                        this.netAmount = net
                        this.vatAmount = gross - net
                        this.totalAmount = gross
                    }
                }
                Payment.new {
                    this.bill = bill
                    this.paymentType = tender
                    this.amount = gross
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                    this.createsReceivable = createsReceivable
                }
                return bill
            }

            bill("CBO-SALE", "100.00", cash, createsReceivable = false, withVat = true)
            val original = bill("CBO-OPEN", "23.00", onAccount, createsReceivable = true, withVat = true)

            val shopInfo = shop
            val invoice =
                SalesInvoice.new {
                    this.shop = shopInfo
                    this.revenueOrigin = RevenueOrigin.POS_RECEIVABLE
                    this.status = SalesInvoiceStatus.ISSUED
                    this.buyerName = "Catering Nord GmbH"
                    this.issueDate = now
                    this.serviceDate = now
                    this.billId = if (invoiceHasOriginal) original.id else null
                }

            bill("CBO-CLR", "23.00", cash, createsReceivable = false, withVat = false, clears = invoice.id.value)
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

    private companion object {
        const val RECEIVABLE_TENDER = "Auf Rechnung"
    }
}
