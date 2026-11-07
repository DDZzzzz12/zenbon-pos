package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-4 BKOPF-1 — a Kassenbuch correction REVERSAL is a „negative Darstellung eines Beleges"
 * and must carry its own `Bon_Referenzen` row.
 *
 * `KassenbuchServiceImpl.correctEntry` books a REVERSAL (`CORRECTION_OUT`/`CORRECTION_IN`,
 * sign-inverted, BON_TYP `Beleg`) linked to the original by `correctsHeaderId`. DSFinV-K
 * Tz. 4.2.2 p. 35 is unambiguous for that form: „muss ein Datensatz in der Datei:
 * Bon_Referenzen angelegt werden", and Anhang B p. 45 repeats the requirement for the
 * umgekehrte-Vorzeichen Beleg generally. `generateReferences` only ever saw `bills`, so the
 * equivalent bill storno WAS referenced while the Kassenbuch reversal was not — leaving the
 * counter-booking untraceable to the Vorgang it reverses.
 *
 * Semantics asserted here (verified in round 3, see `docs/audits/dsfinvk-tse/tracks/02-bonkopf.md`
 * checklist 5) — they are counter-intuitive and a previous round got POS_ZEILE exactly backwards:
 *  - `REF_TYP` = `Transaktion`, `REF_NAME` EMPTY (REF_NAME qualifies only „ExterneSonstige").
 *  - `REF_BON_ID` = the ORIGINAL (reversed) Vorgang's BON_ID.
 *  - `REF_DATUM` / `REF_Z_NR` describe the **REFERENCED** closing — full ISO 8601 timestamp, not
 *    a date, and cross-period-safe: the reversed entry may live in an EARLIER Kassenabschluss
 *    than the reversal, which is exactly what [cross-period correction] seeds.
 *  - `POS_ZEILE` is „Zeilennummer des referenzierenden Vorgangs" (official index.xml Description,
 *    line 1598) and is OMITTED for a Bonkopf-level reference. Emitting the REFERENCED entry's line
 *    number here would create a dangling `(BON_ID, POS_ZEILE)` join.
 *
 * The RE_ENTRY carries the same `correctsHeaderId` link but is a genuine NEW Geschäftsvorfall,
 * not a Stornierung — it must NOT assert one, so it gets no reference row.
 *
 * The correction pair is seeded through the repository rather than `correctEntry` because the
 * service path needs a TSE device and a PDF Eigenbeleg; the export only ever reads
 * `correctionRole` + `correctsHeader`, which is precisely what `correctEntry` stamps
 * (`KassenbuchServiceImpl:581/591`).
 */
@DisplayName("DSFinV-K Kassenbuch correction reversals carry a Bon_Referenzen row (round-4 BKOPF-1)")
class DsfinvkKassenbuchReferenceTest {
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
     * Seeds one Kassenbuch entry linked to [closing] at [at], optionally stamped as part of a
     * correction pair. Amounts are cash-flow signed, as the repository stores them.
     */
    private fun seedEntry(
        closing: ZBon,
        at: LocalDateTime,
        belegNr: String,
        entryType: KassenbuchEntryType,
        amount: String,
        correctsHeaderId: Long? = null,
        correctionRole: CorrectionRole? = null,
    ): Long =
        transaction {
            val shop = ShopInfo.all().first()
            val entry =
                KassenbuchRepositoryImpl(testKassenbuchDocuments()).createEntry(
                    shop = shop,
                    employee = admin(),
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = entryType,
                            belegNr = belegNr,
                            vorzBetrag = amount,
                            belegDatum = "%02d.%02d.%d".format(at.dayOfMonth, at.monthNumber, at.year),
                            belegtext = "BKOPF-1 probe $belegNr",
                            correctsHeaderId = correctsHeaderId,
                            correctionRole = correctionRole,
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(
                                vorzBetrag = amount,
                                ustSatz = "19",
                                kind = KassenbuchLineKind.STANDARD,
                            ),
                        ),
                )
            entry.zBon = closing
            entry.createdAt = at
            entry.id.value
        }

    private fun export(
        startDate: String,
        endDate: String,
    ): ByteArray =
        runBlocking {
            service.generateExport(
                DsfinvkExportDto.ExportRequestDto(startDate = startDate, endDate = endDate),
            )
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
    fun `cross-period Kassenbuch correction reversal references the corrected entry (BKOPF-1)`() {
        // Z5 closes Monday and carries the ORIGINAL; Z12 closes Wednesday and carries the
        // correction pair. Cross-period on purpose: a same-closing-only fixture would pass with an
        // implementation that simply reuses the REFERENCING row's own closing.
        val z5 =
            seedZBon(
                start = LocalDateTime(2026, 7, 13, 8, 0),
                end = LocalDateTime(2026, 7, 13, 18, 0),
                created = LocalDateTime(2026, 7, 13, 18, 30),
                nummer = 5L,
            )
        val z12 =
            seedZBon(
                start = LocalDateTime(2026, 7, 15, 8, 0),
                end = LocalDateTime(2026, 7, 15, 18, 0),
                created = LocalDateTime(2026, 7, 15, 19, 45),
                nummer = 12L,
            )

        // Original: a 40.00 € Barausgabe booked on Monday.
        val originalId =
            seedEntry(
                closing = z5,
                at = LocalDateTime(2026, 7, 13, 11, 0),
                belegNr = "2026-07-8001",
                entryType = KassenbuchEntryType.CASH_EXPENSE,
                amount = "-40.00",
            )
        // Wednesday: it was wrong. REVERSAL (CORRECTION_IN, sign-inverted) + RE_ENTRY.
        val reversalId =
            seedEntry(
                closing = z12,
                at = LocalDateTime(2026, 7, 15, 9, 30),
                belegNr = "2026-07-8002",
                entryType = KassenbuchEntryType.CORRECTION_IN,
                amount = "40.00",
                correctsHeaderId = originalId,
                correctionRole = CorrectionRole.REVERSAL,
            )
        val reEntryId =
            seedEntry(
                closing = z12,
                at = LocalDateTime(2026, 7, 15, 9, 31),
                belegNr = "2026-07-8003",
                entryType = KassenbuchEntryType.CASH_EXPENSE,
                amount = "-25.00",
                correctsHeaderId = originalId,
                correctionRole = CorrectionRole.RE_ENTRY,
            )

        // One package spanning both closings, so referential closure (invariant 6) is checkable.
        val zip = export("2026-07-13", "2026-07-15")

        val refs = readRows(zip, "references.csv").filter { it["BON_ID"] == "KB-$reversalId" }
        assertEquals(
            1,
            refs.size,
            "the correction REVERSAL KB-$reversalId must carry exactly one Bon_Referenzen row " +
                "(Tz. 4.2.2 p. 35); references.csv holds " +
                "${readRows(zip, "references.csv").map { it["BON_ID"] }}",
        )
        val ref = refs.single()

        assertEquals("Transaktion", ref["REF_TYP"], "an in-system reversal is REF_TYP Transaktion")
        assertEquals("KB-$originalId", ref["REF_BON_ID"], "REF_BON_ID is the reversed Vorgang's BON_ID")
        assertEquals("", ref["REF_NAME"], "REF_NAME qualifies only ExterneSonstige (Anhang E p. 102)")
        assertEquals(
            "",
            ref["POS_ZEILE"],
            "POS_ZEILE is the REFERENCING Vorgang's line number and is omitted on a Bonkopf-level " +
                "reference — never the referenced entry's line, which would dangle",
        )

        // The REFERENCING row is stamped with the reversal's OWN closing (Z12) …
        assertEquals("12", ref["Z_NR"], "the reference row belongs to the reversal's own Kassenabschluss")
        assertEquals(
            "2026-07-15T19:45:00",
            ref["Z_ERSTELLUNG"],
            "Z_ERSTELLUNG is the referencing closing's creation timestamp",
        )
        // … while REF_* describes the REFERENCED closing (Z5, two days earlier).
        assertEquals(
            "5",
            ref["REF_Z_NR"],
            "REF_Z_NR must be the REFERENCED entry's own Kassenabschluss (5), not the reversal's (12)",
        )
        assertEquals(
            "2026-07-13T18:30:00",
            ref["REF_DATUM"],
            "REF_DATUM is „Zeitstempel des Kassenabschlusses, auf den referenziert wird\" — the " +
                "full ISO 8601 timestamp of Z5's creation, not a date and not the reversal's own closing",
        )

        // The RE_ENTRY shares the correctsHeaderId link but is a fresh Geschäftsvorfall, not a
        // Stornierung — asserting one would claim a reversal that never happened.
        assertEquals(
            0,
            readRows(zip, "references.csv").count { it["BON_ID"] == "KB-$reEntryId" },
            "the RE_ENTRY KB-$reEntryId must NOT carry a Bon_Referenzen row",
        )

        // Invariant 6 (referential closure): every REF_BON_ID resolves inside the same package.
        val bonIds = readRows(zip, "transactions.csv").mapNotNull { it["BON_ID"] }.toSet()
        assertTrue(
            "KB-$originalId" in bonIds,
            "REF_BON_ID KB-$originalId must resolve to a transactions.csv row of the SAME export " +
                "package; package holds $bonIds",
        )
    }

    @Test
    fun `an unresolvable REF_TYP Transaktion fails the export instead of emitting a blank REF_Z_NR (m9)`() {
        // The one genuinely unresolvable case: the corrected original has NO zBon link (a legacy
        // entry from before the link existed) AND its date falls outside every exported closing, so
        // `closingFor` cannot attribute it either. refZBon is then null, and the row would carry a
        // blank REF_Z_NR on a REF_TYP «Transaktion» — non-conformant (Anhang E p. 102). The export
        // must fail loud, naming the entry to fix, not ship a references.csv an importer mis-joins.
        val z12 =
            seedZBon(
                start = LocalDateTime(2026, 7, 15, 8, 0),
                end = LocalDateTime(2026, 7, 15, 18, 0),
                created = LocalDateTime(2026, 7, 15, 19, 45),
                nummer = 12L,
            )
        // Original: UNLINKED (no zBon) and dated five days before the window — closingFor misses it.
        val originalId =
            seedUnlinkedEntry(
                at = LocalDateTime(2026, 7, 10, 11, 0),
                belegNr = "2026-07-9001",
                entryType = KassenbuchEntryType.CASH_EXPENSE,
                amount = "-40.00",
            )
        seedEntry(
            closing = z12,
            at = LocalDateTime(2026, 7, 15, 9, 30),
            belegNr = "2026-07-9002",
            entryType = KassenbuchEntryType.CORRECTION_IN,
            amount = "40.00",
            correctsHeaderId = originalId,
            correctionRole = CorrectionRole.REVERSAL,
        )

        val ex =
            assertFailsWith<org.example.project.infrastructure.errors.PosException> {
                export("2026-07-14", "2026-07-15")
            }
        assertTrue(
            ex.message!!.contains(originalId.toString()) &&
                (ex.message!!.contains("REF_Z_NR") || ex.message!!.contains("Kassenabschluss")),
            "the failure must name the unresolvable original so an operator can link it; got: ${ex.message}",
        )
    }

    /** Like [seedEntry] but leaves the entry's zBon link null — a pre-link legacy original. */
    private fun seedUnlinkedEntry(
        at: LocalDateTime,
        belegNr: String,
        entryType: KassenbuchEntryType,
        amount: String,
    ): Long =
        transaction {
            val shop = ShopInfo.all().first()
            val entry =
                KassenbuchRepositoryImpl(testKassenbuchDocuments()).createEntry(
                    shop = shop,
                    employee = admin(),
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = entryType,
                            belegNr = belegNr,
                            vorzBetrag = amount,
                            belegDatum = "%02d.%02d.%d".format(at.dayOfMonth, at.monthNumber, at.year),
                            belegtext = "m9 unlinked probe $belegNr",
                            correctsHeaderId = null,
                            correctionRole = null,
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = amount, ustSatz = "19", kind = KassenbuchLineKind.STANDARD),
                        ),
                )
            // Deliberately NO entry.zBon assignment.
            entry.createdAt = at
            entry.id.value
        }

    @Test
    fun `the reversal references the corrected closing via its zBon link when that closing is OUTSIDE the range (BKOPF-6)`() {
        // Round-7 Task 10a — a SEPARATE guard for `original.zBon ?: closingFor(...)` (production
        // :2875). The cross-period test above cannot fail if the `original.zBon ?:` operand is
        // deleted, because BOTH operands resolve to Z5 there: the original's createdAt (2026-07-13)
        // falls inside Z5's window AND Z5 is in the export range, so `closingFor(zBons, ...)` returns
        // the same closing the link does. The link resolution is therefore untested by it.
        //
        // Here the corrected closing (Z3) is OUTSIDE the export range, so it is NOT among `zBons`
        // and `closingFor(zBons, original.createdAt)` returns null. Only the DIRECT `original.zBon`
        // link can still name the referenced closing. Delete that operand and REF_Z_NR goes empty
        // and REF_DATUM falls back to the original entry's own createdAt — both assertions below go
        // red. (Deliberately NOT asserting referential closure/invariant 6: an original outside the
        // range is dropped by the selection filter, so it is legitimately absent from
        // transactions.csv — which is exactly why this scenario needs its own test.)
        val z3 =
            seedZBon(
                start = LocalDateTime(2026, 7, 10, 8, 0),
                end = LocalDateTime(2026, 7, 10, 18, 0),
                created = LocalDateTime(2026, 7, 10, 18, 30),
                nummer = 3L,
            )
        val z12 =
            seedZBon(
                start = LocalDateTime(2026, 7, 15, 8, 0),
                end = LocalDateTime(2026, 7, 15, 18, 0),
                created = LocalDateTime(2026, 7, 15, 19, 45),
                nummer = 12L,
            )

        // Original: a 40.00 € Barausgabe in Z3, five days before the export window opens.
        val originalId =
            seedEntry(
                closing = z3,
                at = LocalDateTime(2026, 7, 10, 11, 0),
                belegNr = "2026-07-7001",
                entryType = KassenbuchEntryType.CASH_EXPENSE,
                amount = "-40.00",
            )
        // The correction is booked in Z12, inside the export window.
        val reversalId =
            seedEntry(
                closing = z12,
                at = LocalDateTime(2026, 7, 15, 9, 30),
                belegNr = "2026-07-7002",
                entryType = KassenbuchEntryType.CORRECTION_IN,
                amount = "40.00",
                correctsHeaderId = originalId,
                correctionRole = CorrectionRole.REVERSAL,
            )

        // Range covers ONLY Z12 — Z3 (created 2026-07-10) is excluded by createdAt.
        val zip = export("2026-07-14", "2026-07-15")

        val ref =
            readRows(zip, "references.csv")
                .single { it["BON_ID"] == "KB-$reversalId" }

        assertEquals("KB-$originalId", ref["REF_BON_ID"], "REF_BON_ID is the reversed Vorgang's BON_ID")
        // The referencing row itself belongs to Z12 (the reversal's own, in-range closing).
        assertEquals("12", ref["Z_NR"], "the reference row belongs to the reversal's own Kassenabschluss")
        // REF_* must describe Z3 — resolvable ONLY through `original.zBon`, since Z3 is out of range.
        assertEquals(
            "3",
            ref["REF_Z_NR"],
            "REF_Z_NR must be the corrected entry's own Kassenabschluss (3), read off `original.zBon`; " +
                "an empty value means the link resolution was dropped and only the window fallback ran",
        )
        assertEquals(
            "2026-07-10T18:30:00",
            ref["REF_DATUM"],
            "REF_DATUM must be Z3's creation timestamp (via the link), not the original entry's own " +
                "createdAt (2026-07-10T11:00:00) that the closingFor fallback would produce",
        )
    }

    @Test
    fun `an ordinary Kassenbuch entry gets no Bon_Referenzen row`() {
        val z5 =
            seedZBon(
                start = LocalDateTime(2026, 7, 13, 8, 0),
                end = LocalDateTime(2026, 7, 13, 18, 0),
                created = LocalDateTime(2026, 7, 13, 18, 30),
                nummer = 5L,
            )
        val plainId =
            seedEntry(
                closing = z5,
                at = LocalDateTime(2026, 7, 13, 10, 0),
                belegNr = "2026-07-8010",
                entryType = KassenbuchEntryType.CASH_DEPOSIT,
                amount = "100.00",
            )

        val zip = export("2026-07-13", "2026-07-13")

        assertEquals(
            0,
            readRows(zip, "references.csv").count { it["BON_ID"] == "KB-$plainId" },
            "a Geschäftsvorfall that reverses nothing must not reference anything",
        )
    }
}
