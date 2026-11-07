package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
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
import kotlin.test.assertTrue

/**
 * Round-5 KABS-1 follow-up — Kassenbuch attribution must follow the `zbon_id` LINK, in both
 * directions, and the two directions are the same test because they are the same predicate.
 *
 * The KABS-1 fix (83c8c71b3) widened DSFinV-K's Kassenbuch SELECTION to
 * `zbon_id IN (exported) OR createdAt IN (window)`, rescuing legacy rows whose `createdAt` was
 * frozen to one table-init instant by an Exposed `.default(currentTimeStamp())` and could therefore
 * sit outside their own closing's window. It left ATTRIBUTION on the timestamp, which produced a
 * worse defect than the one it fixed:
 *
 *  - direction 1 (the widening, guarded by [linked entry outside its closing window is exported
 *    under its own closing]): re-narrowing the predicate to window-only must fail here. Every other
 *    export test seeds its entry inside the window, so the whole suite stays green under that
 *    regression — this is its only coverage.
 *  - direction 2 (the double-admission, same test): an entry linked to Z12 but frozen inside Z5's
 *    window was admitted by BOTH exports — by window in Z5's, by `zbon_id` in Z12's — and emitted
 *    under Z_NR=5 and Z_NR=12 respectively. `BON_ID`/`BON_NR` are closing-independent
 *    (`"KB-$id"`, `BonNrRanges.KASSENBUCH + id`), so nothing downstream can collapse the pair: a
 *    day-by-day Kassennachschau export set double-counts the cash while each package reconciles
 *    internally.
 *
 * The two closings here are created on different calendar days precisely so they land in separate
 * export packages, which is the only configuration in which the double-admission is observable.
 */
@DisplayName("DSFinV-K Kassenbuch entries are attributed to their LINKED closing (round-5 KABS-1)")
class DsfinvkKassenbuchLinkedClosingAttributionTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun admin() =
        transaction {
            org.example.project.features.employee.domain.models.Employee
                .find { org.example.project.features.employee.database.tables.EmployeeTable.username eq "admin" }
                .firstOrNull() ?: TestData.createAdmin()
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

    /**
     * A Kassenbuch entry explicitly linked to [linkedTo], but carrying a [frozenAt] `createdAt` that
     * lies OUTSIDE that closing's window — the exact shape a pre-KABS-1 frozen column default
     * produced on legacy data.
     */
    private fun seedLinkedEntryFrozenAt(
        linkedTo: ZBon,
        frozenAt: LocalDateTime,
        belegNr: String,
    ): Long =
        transaction {
            val shop = ShopInfo.all().first()
            val entry =
                KassenbuchRepositoryImpl(testKassenbuchDocuments()).createEntry(
                    shop = shop,
                    employee = admin(),
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = belegNr,
                            vorzBetrag = "2.00",
                            belegDatum = "13.07.2026",
                            belegtext = "KABS-1 frozen-timestamp probe",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "2.00", ustSatz = "19", kind = KassenbuchLineKind.STANDARD),
                        ),
                )
            entry.zBon = linkedTo
            entry.createdAt = frozenAt
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

    @Test
    fun `linked entry is exported exactly once, under its own closing, in neither neighbour`() {
        // Z5 closes Monday; Z12 closes Tuesday. Separate calendar days => separate export packages.
        seedZBon(
            start = LocalDateTime(2026, 7, 13, 8, 0),
            end = LocalDateTime(2026, 7, 13, 18, 0),
            created = LocalDateTime(2026, 7, 13, 18, 0),
            nummer = 5L,
        )
        val z12 =
            seedZBon(
                start = LocalDateTime(2026, 7, 14, 8, 0),
                end = LocalDateTime(2026, 7, 14, 18, 0),
                created = LocalDateTime(2026, 7, 14, 18, 0),
                nummer = 12L,
            )

        // Linked to Z12, but frozen at a Monday instant that sits squarely inside Z5's window.
        val entryId =
            seedLinkedEntryFrozenAt(
                linkedTo = z12,
                frozenAt = LocalDateTime(2026, 7, 13, 12, 0),
                belegNr = "2026-07-9001",
            )
        val bonId = "KB-$entryId"

        val mondayZip = export("2026-07-13")
        val tuesdayZip = export("2026-07-14")

        val files = listOf("transactions.csv", "transactions_tse.csv", "lines.csv", "datapayment.csv")

        // Direction 1 — the KABS-1 widening: the entry's own closing MUST carry it, even though its
        // frozen createdAt is outside that closing's window. Narrowing selection back to
        // window-only makes this fail.
        val tuesdayTransactions = readRows(tuesdayZip, "transactions.csv").filter { it["BON_ID"] == bonId }
        assertEquals(
            1,
            tuesdayTransactions.size,
            "the linked entry must appear exactly once in its own closing's export; got " +
                "${tuesdayTransactions.size} transactions.csv rows for $bonId",
        )
        assertEquals(
            "12",
            tuesdayTransactions.single()["Z_NR"],
            "the entry must carry the Z_NR of the closing it is LINKED to",
        )

        // Direction 2 — no double admission: Monday's package covers the frozen instant by window,
        // but the entry is attributable only to Z12. Emitting it here too would put the same
        // BON_ID in two packages under two different Z_NR, double-counting the cash.
        for (file in files) {
            val strays = readRows(mondayZip, file).filter { it["BON_ID"] == bonId }
            assertTrue(
                strays.isEmpty(),
                "$file in the 2026-07-13 package must not contain $bonId — it is linked to " +
                    "closing 12, which that package does not export. Found ${strays.size} row(s) " +
                    "under Z_NR=${strays.map { it["Z_NR"] }.distinct()}; the same BON_ID is also " +
                    "emitted by the 2026-07-14 package, so the cash is counted twice across the " +
                    "export set.",
            )
        }

        // And the entry really is still linked + frozen — guards the fixture itself against a
        // silent repository change re-stamping createdAt.
        transaction {
            val reread = KassenbuchHeader.findById(entryId)!!
            assertEquals(z12.id.value, reread.zBon?.id?.value, "fixture: entry must stay linked to Z12")
            assertEquals(
                LocalDateTime(2026, 7, 13, 12, 0),
                reread.createdAt,
                "fixture: entry's createdAt must stay frozen outside Z12's window",
            )
        }
    }
}
