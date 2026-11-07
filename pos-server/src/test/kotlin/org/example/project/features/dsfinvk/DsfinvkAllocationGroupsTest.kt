package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.table.domain.models.TableSession
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * BKOPF-2 (round-3 audit): ABRECHNUNGSKREIS must UNIQUELY identify the Geschäftsvorfall
 * (Tz. 2.7.1 / GoBD Rz. 94). A bare table number ("Tisch-5") repeats across visits and even
 * within one closing (lunch + dinner at the same table), so it cannot attribute orders to the
 * right Rechnung — the precondition for the Tz.-2.7 relief this system relies on. Adding the
 * session id ("Tisch-5/S-<sessionId>") disambiguates while keeping the table number readable.
 */
@DisplayName("DSFinV-K allocation_groups — ABRECHNUNGSKREIS carries the session key (BKOPF-2)")
class DsfinvkAllocationGroupsTest {
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
    fun `bill and order in one session share a session-scoped ABRECHNUNGSKREIS and a second visit at the same table differs`() {
        var sessionId1 = 0L
        var sessionId2 = 0L
        var billId1 = 0L
        var orderId1 = 0L
        var billId2 = 0L

        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val now = currentTimeStamp()

            // --- Visit 1: table "5", one bill AND one order in the SAME session. ---
            val tws1 = TestData.createTableWithSession("5")
            val session1 = TableSession.findById(tws1.session.id)!!
            sessionId1 = session1.id.value

            val bill1 =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session1
                    this.billNumber = "5100"
                    this.billAmount = BigDecimal("12.00")
                    this.paidAmount = BigDecimal("12.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            billId1 = bill1.id.value

            val order1 =
                Orders.new {
                    this.orderNumber = "5-ORD-1"
                    this.employee = admin
                    this.tableSession = session1
                    this.orderStatus = OrderStatus.CONFIRMED
                    this.orderTime = now
                    this.orderTotal = BigDecimal("5.00")
                    this.tseSigningStatus = TseSigningStatus.PENDING
                    this.isTraining = false
                }
            orderId1 = order1.id.value

            // --- Visit 2: the SAME physical table "5", a DIFFERENT session (later party same day). ---
            val cashier2 = TestData.createCashier(employeeNumber = "CSH-5B", username = "cashier_5b")
            val session2 = tws1.table.startSession(cashier2)
            sessionId2 = session2.id.value

            val bill2 =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session2
                    this.billNumber = "5200"
                    this.billAmount = BigDecimal("30.00")
                    this.paidAmount = BigDecimal("30.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            billId2 = bill2.id.value
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        val rows = readRows(zip, "allocation_groups.csv")

        val bill1Group = rows.single { it["BON_ID"] == "B-$billId1" }["ABRECHNUNGSKREIS"]
        val order1Group = rows.single { it["BON_ID"] == "O-$orderId1" }["ABRECHNUNGSKREIS"]
        val bill2Group = rows.single { it["BON_ID"] == "B-$billId2" }["ABRECHNUNGSKREIS"]

        // 1) The bill and order of the SAME session (Geschäftsvorfall) share one ABRECHNUNGSKREIS…
        assertEquals(
            "Tisch-5/S-$sessionId1",
            bill1Group,
            "the bill's ABRECHNUNGSKREIS must carry the table number AND the session id",
        )
        assertEquals(
            bill1Group,
            order1Group,
            "the order and bill of one session must share the same session-scoped ABRECHNUNGSKREIS",
        )

        // 2) …and a second visit at the SAME table number must NOT collide with the first.
        assertTrue(bill2Group!!.startsWith("Tisch-5/"), "second visit keeps the readable table number, got $bill2Group")
        assertEquals("Tisch-5/S-$sessionId2", bill2Group)
        assertNotEquals(
            bill1Group,
            bill2Group,
            "two sessions at the same table must get distinct ABRECHNUNGSKREIS values (session id disambiguates)",
        )
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
