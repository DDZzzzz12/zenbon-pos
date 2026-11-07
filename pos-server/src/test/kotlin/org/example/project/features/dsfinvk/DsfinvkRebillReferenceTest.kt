package org.example.project.features.dsfinvk

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.bill.RebillFixture
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A sale settling items a "re-open the table" Storno handed back names each stornoed original in
 * `references.csv` — header level, REF_TYP «Transaktion», exactly like a Zahlartwechsel Neubuchung
 * (Anhang B p. 45 „eine Referenz auf den ursprünglichen Vorgang", Anhang E). One row per
 * (re-bill, original) pair, never two for the same pair.
 */
@DisplayName("DSFinV-K — re-bill references after a re-opening Storno")
class DsfinvkRebillReferenceTest {
    private val deviceId = "dsfinvk-rebill-device"
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @AfterEach
    fun tearDown() {
        NotificationHub.resetForTest()
        BillNumberTracker.clearAll()
    }

    /** H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation. */
    private fun aliasAdvisoryLock() =
        transaction {
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
        }

    private fun stornoOf(billId: Long): Long =
        transaction {
            Bill
                .find { BillTable.deletedBillId eq billId }
                .single()
                .id.value
        }

    private fun bon(id: Long) = "B-$id"

    @Test
    fun `every re-bill references each original once, and a new-items bill references nothing`() =
        testApp(
            setupData = {
                TestData.setTseMode(TseMode.DISABLED)
                TestData.createAdmin()
            },
        ) { client ->
            // Mixed: handed-back lines plus a line ordered after the re-open.
            val mixed = RebillFixture(deviceId, "DRM").seed()
            val a = mixed.bill(mixed.order("10.00", "5.00"))
            mixed.stornoReopen(a)
            val b = mixed.bill(mixed.handedBackFrom(a) + mixed.order("3.00"))

            // Split over two bills, plus a bill of new items only.
            val split = RebillFixture(deviceId, "DRS").seed()
            val c = split.bill(split.order("4.00", "6.00"))
            split.stornoReopen(c)
            val newOnly = split.bill(split.order("2.00"))
            val (c1Item, c2Item) = split.handedBackFrom(c)
            val c1 = split.bill(listOf(c1Item))
            val c2 = split.bill(listOf(c2Item))

            // Table re-opened twice.
            val twice = RebillFixture(deviceId, "DR2").seed()
            val (line1, line2) = twice.order("7.00", "8.00")
            val d1 = twice.bill(listOf(line1))
            val d2 = twice.bill(listOf(line2))
            twice.stornoReopen(d1)
            twice.stornoReopen(d2)
            val e = twice.bill(twice.handedBackFrom(d1) + twice.handedBackFrom(d2))

            // Zahlartwechsel of a re-bill: its copies carry the re-bill too — referenced ONCE.
            val chain = RebillFixture(deviceId, "DRZ").seed()
            val g = chain.bill(chain.order("10.00"))
            chain.stornoReopen(g)
            val r = chain.bill(chain.handedBackFrom(g) + chain.order("3.00"))
            val neubuchung = chain.rebookToCard(r, "13.00").billId

            aliasAdvisoryLock()
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val closing = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, closing.status, "body: ${closing.bodyAsText()}")

            val zip = service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            val refs = readRows(zip, "references.csv")

            val rebills = listOf(b, newOnly, c1, c2, e, r, neubuchung).map(::bon).toSet()
            val actual = refs.filter { it["BON_ID"] in rebills }.map { it.getValue("BON_ID") to it.getValue("REF_BON_ID") }
            val expected =
                listOf(
                    b to a,
                    c1 to c,
                    c2 to c,
                    e to d1,
                    e to d2,
                    r to g,
                    neubuchung to r,
                ).map { (from, to) -> bon(from) to bon(to) }
            assertEquals(expected.sortedBy { it.toString() }, actual.sortedBy { it.toString() }, "one row per (re-bill, original) pair, got $actual")

            refs.filter { it["BON_ID"] in rebills }.forEach { ref ->
                assertEquals("Transaktion", ref["REF_TYP"])
                assertEquals("", ref["POS_ZEILE"], "header-level reference")
                assertEquals(ref["Z_NR"], ref["REF_Z_NR"], "one closing holds the original and its re-bill")
                assertEquals(ref["Z_ERSTELLUNG"], ref["REF_DATUM"], "REF_DATUM is the referenced closing's timestamp")
                assertEquals(ref["Z_KASSE_ID"], ref["REF_Z_KASSE_ID"])
            }

            // The Stornos still carry their own reversal rows.
            listOf(a, c, d1, d2, g, r).forEach { original ->
                assertTrue(
                    refs.any { it["BON_ID"] == bon(stornoOf(original)) && it["REF_BON_ID"] == bon(original) },
                    "Storno of ${bon(original)} must reference it",
                )
            }
            // No pair is ever written twice anywhere in the file.
            val pairs = refs.map { Triple(it["BON_ID"], it["POS_ZEILE"], it["REF_BON_ID"]) }
            assertEquals(pairs.size, pairs.toSet().size, "duplicate references.csv rows: $pairs")
        }

    @Test
    fun `an original in an earlier closing resolves to that closing's Z_NR and timestamp`() =
        testApp(setupData = { TestData.setTseMode(TseMode.DISABLED) }) { _ ->
            val f = RebillFixture(deviceId, "DRE").seed()
            val a = f.bill(f.order("10.00", "5.00"))
            f.stornoReopen(a)
            val b = f.bill(f.handedBackFrom(a) + f.order("3.00"))

            // The service always closes an original with its re-bill (a Z-Bon refuses while handed-back
            // lines are open), so an original in an EARLIER closing is seeded: legacy data, or a
            // closing rule that changes later. The reference must still name the original's closing.
            val now = currentTimeStamp()
            val yesterday = LocalDateTime(now.date.minus(1, DateTimeUnit.DAY), kotlinx.datetime.LocalTime(23, 59, 59))
            val (shop, employee) = transaction { ShopInfo.all().first() to Employee.findById(UUID.fromString(f.managerId))!! }
            val earlier =
                TestData.createZBon(
                    shop,
                    employee,
                    start = LocalDateTime(yesterday.date, kotlinx.datetime.LocalTime(0, 0, 0)),
                    end = yesterday,
                    nummer = 1,
                )
            val today = TestData.createZBonFullDay(shop, employee, nummer = 2)
            transaction {
                Bill.findById(a)!!.zBon = earlier
                Bill.findById(stornoOf(a))!!.zBon = today
                Bill.findById(b)!!.zBon = today
            }

            // Export ONLY today's closing: the original is not even in the package.
            val day = now.date.toString()
            val zip = service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = day, endDate = day))
            val ref = readRows(zip, "references.csv").single { it["BON_ID"] == bon(b) }

            assertEquals(bon(a), ref["REF_BON_ID"])
            assertEquals("2", ref["Z_NR"], "the re-bill's own closing")
            assertEquals("1", ref["REF_Z_NR"], "the ORIGINAL's closing, read off the original")
            assertEquals("${yesterday.date}T23:59:59", ref["REF_DATUM"], "the referenced closing's timestamp")
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
