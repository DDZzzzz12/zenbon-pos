package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals

/**
 * A Vorgang stamped exactly on the instant two consecutive closings share must resolve to the
 * EARLIER closing — deterministically, not by whatever order the database happened to return the
 * closings in.
 *
 * Consecutive closings touch by construction (`startTime == previous.endTime`) and `closingFor`
 * uses an inclusive window at both ends, so a Vorgang on that instant matches BOTH. Round-3 INV-F8
 * resolved the resulting double-count by taking the FIRST covering closing in the list — a correct
 * rule that was resting on nothing: `generateExport` loaded the closings with no `ORDER BY`, and a
 * database is free to return rows in any order (seq scan, index scan on `created_at` and parallel
 * scan need not agree, and ZBon rows are updated after insert, so page reuse can reorder them).
 * The Z_NR a boundary Vorgang was exported under was therefore a function of the query plan.
 *
 * Only UNLINKED Vorgänge reach this path — an entry carrying a `zbon_id` is attributed by that link
 * (see [DsfinvkKassenbuchLinkedClosingAttributionTest]), which is also why the V78 `created_at`
 * realignment cannot misattribute the export.
 *
 * **How this test defeats list order, and its limit.** It seeds the LATER closing (Z_NR 2) FIRST, so
 * insertion order is the reverse of Z-number order; an unsorted query then hands Z2 back first and
 * the boundary entry lands under Z_NR 2. That reversed insertion is a STAND-IN for a Postgres plan
 * change — the tests run on H2, which returns insertion order for a simple scan, so reversing it is
 * the only lever available to reproduce the failure mode here. It is not a reproduction of the
 * production trigger, and it deliberately seeds a Z-number order that `createReport` (zbonNummer =
 * max + 1) would never produce on its own.
 *
 * [DsfinvkKassenbuchBoundaryMembershipTest] does not cover this: it passes a hand-built
 * `listOf(z1, z2)` straight to the aggregator, bypassing the production query, and asserts only
 * that the entry landed in exactly ONE closing — never which.
 *
 * The mutation this guard catches (rule R13): drop the `orderBy(zbonNummer ASC)` in
 * `generateExport` and this test goes RED with `Z_NR=2`.
 */
@DisplayName("DSFinV-K closing boundary — a boundary Vorgang resolves to the earlier closing, deterministically")
class DsfinvkClosingBoundaryOrderingTest {
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
    fun `an unlinked entry on a shared boundary is exported under the earlier closing`() {
        // Both closings sit on the SAME calendar day so one export package carries both — the only
        // configuration in which the tie-break is observable.
        //
        // Z2 is seeded FIRST on purpose: insertion order (Z2, Z1) is now the reverse of zbonNummer
        // order (Z1, Z2), so an unordered query resolves the boundary to the WRONG closing.
        seedZBon(
            start = BOUNDARY,
            end = LocalDateTime(2026, 7, 13, 18, 0),
            created = LocalDateTime(2026, 7, 13, 18, 0),
            nummer = 2L,
        )
        seedZBon(
            start = LocalDateTime(2026, 7, 13, 8, 0),
            end = BOUNDARY,
            created = BOUNDARY,
            nummer = 1L,
        )

        val entryId = seedUnlinkedEntryAt(BOUNDARY)
        val bonId = "KB-$entryId"

        val rows = readRows(export("2026-07-13"), "transactions.csv").filter { it["BON_ID"] == bonId }

        assertEquals(
            1,
            rows.size,
            "the boundary entry must appear exactly once — both closings cover the instant, so a " +
                "predicate that does not break the tie emits it twice (round-3 INV-F8). Got " +
                "${rows.size} rows under Z_NR=${rows.map { it["Z_NR"] }}",
        )
        assertEquals(
            "1",
            rows.single()["Z_NR"],
            "the boundary entry must resolve to the EARLIER closing regardless of the order the " +
                "database returned the closings in; Z_NR=2 means the tie-break followed row order " +
                "rather than zbonNummer",
        )

        // Guards the fixture: if the repository ever re-stamps createdAt, or the entry acquires a
        // zbon_id, this stops testing the boundary path and starts passing vacuously.
        transaction {
            val reread = KassenbuchHeader.findById(entryId)!!
            assertEquals(null, reread.zBon?.id?.value, "fixture: the entry must stay UNLINKED")
            assertEquals(BOUNDARY, reread.createdAt, "fixture: the entry must stay stamped on the boundary")
        }
    }

    // ---------------------------------------------------------------- fixtures

    private companion object {
        /** Z1 ends here, Z2 starts here — the shared instant both windows include. */
        val BOUNDARY = LocalDateTime(2026, 7, 13, 12, 0)
    }

    private fun admin(): Employee =
        transaction {
            Employee.find { EmployeeTable.username eq "admin" }.firstOrNull() ?: TestData.createAdmin()
        }

    private fun seedZBon(
        start: LocalDateTime,
        end: LocalDateTime,
        created: LocalDateTime,
        nummer: Long,
    ): ZBon =
        transaction {
            val adminEmployee = admin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            ZBon.new {
                this.shopInfo = shop
                this.employee = adminEmployee
                this.startTime = start
                this.endTime = end
                this.cancelledAmount = BigDecimal.ZERO
                this.totalDiscounts = BigDecimal.ZERO
                this.totalPosRevenue = BigDecimal.ZERO
                this.adminTip = BigDecimal.ZERO
                this.openingBalance = BigDecimal.ZERO
                this.expenses = BigDecimal.ZERO
                this.cashRevenue = BigDecimal.ZERO
                this.closingBalance = BigDecimal.ZERO
                this.createdAt = created
                this.trainingRevenue = BigDecimal.ZERO
                this.trainingBillCount = 0
                this.refundAmount = BigDecimal.ZERO
                this.stornoBillCount = 0
                this.zbonNummer = nummer
            }
        }

    /** A Kassenbuch entry with NO `zbon_id` — the only shape that reaches `closingFor`. */
    private fun seedUnlinkedEntryAt(at: LocalDateTime): Long =
        transaction {
            val entry =
                KassenbuchRepositoryImpl(testKassenbuchDocuments()).createEntry(
                    shop = ShopInfo.all().first(),
                    employee = admin(),
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = "2026-07-9100",
                            vorzBetrag = "2.00",
                            belegDatum = "13.07.2026",
                            belegtext = "Boundary probe",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(
                                vorzBetrag = "2.00",
                                ustSatz = "19",
                                kind = KassenbuchLineKind.STANDARD,
                            ),
                        ),
                )
            entry.zBon = null
            entry.createdAt = at
            entry.id.value
        }

    private fun export(date: String): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = date, endDate = date))
        }

    /** Quote-aware single-line CSV split (';' separator, '"' encapsulator). */
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
