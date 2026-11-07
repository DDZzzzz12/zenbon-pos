package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H2 (audit 2026-07-13): Kassenbuch cash movements change the Vermögenszusammensetzung and
 * are „Beleg" with a signed Bar tender (official examples V_2_3_05/06: Privateinlage
 * +1000/Bar, Geldtransit −4200/Bar) — not AVSonstige with „Keine"/0,00. Purely
 * informational balance snapshots stay AVSonstige.
 */
@DisplayName("DSFinV-K Kassenbuch movements: Beleg + signed Bar tender (H2)")
class DsfinvkKassenbuchTenderTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())
    private val repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `Geldtransit exports Beleg with signed Bar tender and key-5 vat row`() {
        var bonId = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val entry =
                repository.createEntry(
                    shop = shop,
                    employee = admin,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_TRANSFER_OUT,
                            belegNr = "2026-07-0001",
                            vorzBetrag = "-4200.0",
                            belegDatum = "14.07.2026",
                            belegtext = "Bankeinzahlung",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "-4200.0", belegtext = "Bankeinzahlung"),
                        ),
                )
            bonId = "KB-${entry.id.value}"
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }

        val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == bonId }
        assertEquals("Beleg", kopf["BON_TYP"], "cash movement must be Beleg (Anhang B)")

        val pay = readRows(zip, "datapayment.csv").single { it["BON_ID"] == bonId }
        assertEquals("Bar", pay["ZAHLART_TYP"])
        assertEquals("-4200,00", pay["BASISWAEH_BETRAG"])

        val vat = readRows(zip, "transactions_vat.csv").single { it["BON_ID"] == bonId }
        assertEquals("5", vat["UST_SCHLUESSEL"])
        assertEquals("-4200,00", vat["BON_BRUTTO"])
    }

    @Test
    fun `a Geldtransit names what happened in BON_NAME while GV_TYP stays Geldtransit`() {
        var toTresor = ""
        var byHand = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            TestData.createZBonFullDay(shop, admin, nummer = 1L)

            fun transitOut(belegNr: String) =
                repository.createEntry(
                    shop = shop,
                    employee = admin,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_TRANSFER_OUT,
                            belegNr = belegNr,
                            vorzBetrag = "-500.00",
                            belegDatum = "14.07.2026",
                            belegtext = "Tageseinnahme",
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "-500.00", belegtext = "Tageseinnahme")),
                )

            // A transfer leg: the Ladenkasse's outgoing half of a move into the Tresor.
            val leg = transitOut("2026-07-0001")
            leg.counterpartyBook = CashBookRepositoryImpl().create(shop, "Tresor", isFiscal = false, datevAccount = "1010")
            toTresor = "KB-${leg.id.value}"
            // A transit booked by hand, with no counterpart book on record.
            byHand = "KB-${transitOut("2026-07-0002").id.value}"
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }
        val kopf = readRows(zip, "transactions.csv")

        // Pre-fix both read "Geldtransit Bank-Einzahlung": a Tresor move described as a bank deposit.
        assertEquals("Geldtransit an Tresor", kopf.single { it["BON_ID"] == toTresor }["BON_NAME"])
        assertEquals("Geldtransit (Abgang)", kopf.single { it["BON_ID"] == byHand }["BON_NAME"])
        assertTrue(kopf.none { it["BON_NAME"].orEmpty().contains("Bank") }, "no transit may claim a bank it never saw")

        // The mandated classification is untouched.
        val gvTypen = readRows(zip, "lines.csv").filter { it["BON_ID"] in setOf(toTresor, byHand) }.map { it["GV_TYP"] }
        assertEquals(listOf("Geldtransit", "Geldtransit"), gvTypen)
    }

    @Test
    fun `genuine OPENING_BALANCE exports Beleg + Bar tender + one Anfangsbestand businesscase (KABS-3)`() {
        var bonId = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            TestData.createZBonFullDay(shop, admin, nummer = 1L)
            // Guard-passing genuine opening fill seeded on a virgin book. Anhang C p. 56: the
            // Anfangsbestand is „als BON_TYP «Beleg» zu erfassen" with a Bar tender.
            val entry =
                repository.createEntry(
                    shop = shop,
                    employee = admin,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.OPENING_BALANCE,
                            belegNr = "2026-07-0002",
                            vorzBetrag = "200.0",
                            belegDatum = "16.07.2026",
                            belegtext = "Anfangsbestand",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "200.0", belegtext = "Anfangsbestand"),
                        ),
                )
            bonId = "KB-${entry.id.value}"
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }

        val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == bonId }
        assertEquals("Beleg", kopf["BON_TYP"], "genuine Anfangsbestand is a Beleg (Anhang C p. 56)")

        val pay = readRows(zip, "datapayment.csv").single { it["BON_ID"] == bonId }
        assertEquals("Bar", pay["ZAHLART_TYP"])
        assertEquals("200,00", pay["BASISWAEH_BETRAG"])

        val anfang = readRows(zip, "businesscases.csv").filter { it["GV_TYP"] == "Anfangsbestand" }
        assertEquals(1, anfang.size, "exactly one Anfangsbestand businesscases row")
        assertEquals("200,00", anfang.single()["Z_UMS_BRUTTO"])
        assertEquals("5", anfang.single()["UST_SCHLUESSEL"])
    }

    @Test
    fun `legacy CLOSING_BALANCE stays AVSonstige and never aggregates into businesscases (INV-F5)`() {
        var bonId = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            TestData.createZBonFullDay(shop, admin, nummer = 1L)
            // Direct-entity seed to bypass the new creation guard: only LEGACY rows can be
            // CLOSING_BALANCE. They must remain AVSonstige and must NOT inflate the closing as a
            // phantom Anfangsbestand.
            val entry =
                repository.createEntry(
                    shop = shop,
                    employee = admin,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CLOSING_BALANCE,
                            belegNr = "2026-07-0003",
                            vorzBetrag = "-150.0",
                            belegDatum = "16.07.2026",
                            belegtext = "Endbestand (legacy)",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "-150.0", belegtext = "Endbestand"),
                        ),
                )
            bonId = "KB-${entry.id.value}"
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }

        val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == bonId }
        assertEquals("AVSonstige", kopf["BON_TYP"], "legacy CLOSING_BALANCE stays AVSonstige")

        val anfang = readRows(zip, "businesscases.csv").filter { it["GV_TYP"] == "Anfangsbestand" }
        assertEquals(0, anfang.size, "AVSonstige snapshot never aggregates into businesscases")
    }

    @Test
    fun `KB voucher-redemption line is key-5 USt-0 in businesscases and lines_vat (KABS-4)`() {
        // Official example V_2_3_02 Bsp. 2.2: a Mehrzweckgutschein redemption is a non-taxable
        // Geschäftsvorfall (key 5 / USt 0) — the VAT already sat on the supply's Umsatz lines.
        // A GUTSCHEIN line inside a CASH_SALE must NOT compute a rate-carrying (Rabatt-like) USt.
        var bonId = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val entry =
                repository.createEntry(
                    shop = shop,
                    employee = admin,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = "2026-07-0004",
                            vorzBetrag = "-19.11",
                            belegDatum = "16.07.2026",
                            belegtext = "Gutscheineinlösung",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(
                                vorzBetrag = "-19.11",
                                ustSatz = "19",
                                belegtext = "Gutscheineinlösung",
                                kind = KassenbuchLineKind.GUTSCHEIN,
                            ),
                        ),
                )
            bonId = "KB-${entry.id.value}"
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }

        val gv =
            readRows(zip, "businesscases.csv")
                .single { it["GV_TYP"] == "MehrzweckgutscheinEinloesung" }
        assertEquals("5", gv["UST_SCHLUESSEL"], "redemption row is key 5 (Nicht steuerbar)")
        assertEquals("0,00", gv["Z_UST"], "no USt on a voucher redemption")
        assertEquals("-19,11", gv["Z_UMS_BRUTTO"])
        assertEquals("-19,11", gv["Z_UMS_NETTO"], "brutto == netto for a voucher redemption")

        val vat = readRows(zip, "lines_vat.csv").single { it["BON_ID"] == bonId }
        assertEquals("5", vat["UST_SCHLUESSEL"], "lines_vat redemption row is key 5")
        assertEquals("0,00", vat["POS_UST"], "no USt on the redemption line")
        assertEquals("-19,11", vat["POS_BRUTTO"])
        assertEquals("-19,11", vat["POS_NETTO"])
    }

    @Test
    fun `KB GUTSCHEIN line is key-5 in transactions_vat too, matching lines_vat (INV-1)`() {
        // Round-4 INV-1: lines_vat (KABS-4) and businesscases already bucket a KB
        // MehrzweckgutscheinEinloesung line to key 5 / USt 0; appendKassenbuchBonkopfUst did not,
        // so transactions_vat booked the redemption at the declared rate and collapsed the whole
        // entry into ONE key-1 row. Official example V_2_3_02 Bsp. 2.2 shows Bonkopf_USt carrying
        // BOTH the key-1 row AND the key-5 row.
        //
        // Reachable fixture: a CASH_SALE whose lines sum to 0.00 is rejected by
        // KassenbuchDto.validate(), so +60.00 STANDARD @19 % and −50.00 GUTSCHEIN @19 %
        // (header total +10.00, creatable through the real API).
        var bonId = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val entry =
                repository.createEntry(
                    shop = shop,
                    employee = admin,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = "2026-07-0005",
                            vorzBetrag = "10.00",
                            belegDatum = "16.07.2026",
                            belegtext = "Barverkauf mit Gutscheineinlösung",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(
                                vorzBetrag = "60.00",
                                ustSatz = "19",
                                belegtext = "Barverkauf",
                                kind = KassenbuchLineKind.STANDARD,
                            ),
                            KassenbuchDto.LineItemData(
                                vorzBetrag = "-50.00",
                                ustSatz = "19",
                                belegtext = "Gutscheineinlösung",
                                kind = KassenbuchLineKind.GUTSCHEIN,
                            ),
                        ),
                )
            bonId = "KB-${entry.id.value}"
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }

        val tv = readRows(zip, "transactions_vat.csv").filter { it["BON_ID"] == bonId }
        val lv = readRows(zip, "lines_vat.csv").filter { it["BON_ID"] == bonId }

        // POSITIVE presence assertion — asserting only "no wrong row" passes vacuously when the
        // entry is dropped from the export entirely.
        assertTrue(
            tv.any { it["UST_SCHLUESSEL"] == "5" },
            "transactions_vat must carry the key-5 redemption row, got $tv",
        )
        assertTrue(
            tv.any { it["UST_SCHLUESSEL"] == "1" },
            "transactions_vat must still carry the key-1 supply row, got $tv",
        )

        // The invariant that was broken: per-key agreement between the two producers.
        fun byKey(
            rows: List<Map<String, String>>,
            amt: String,
        ): Map<String?, java.math.BigDecimal> =
            rows
                .groupBy { it["UST_SCHLUESSEL"] }
                .mapValues { (_, r) -> r.fold(java.math.BigDecimal.ZERO) { a, x -> a + germanToBigDecimal(x[amt]!!) } }

        assertEquals(
            byKey(lv, "POS_BRUTTO"),
            byKey(tv, "BON_BRUTTO"),
            "Σ lines_vat POS_BRUTTO per key must equal transactions_vat BON_BRUTTO per key",
        )
        assertEquals(
            byKey(lv, "POS_NETTO"),
            byKey(tv, "BON_NETTO"),
            "Σ lines_vat POS_NETTO per key must equal transactions_vat BON_NETTO per key",
        )

        // Round-3 INV-F6 invariant must still hold: all three KB VAT producers agree to the cent.
        assertEquals(
            sumGerman(lv, "POS_NETTO"),
            sumGerman(tv, "BON_NETTO"),
            "transactions_vat and lines_vat must net identically (INV-F6)",
        )
        // The third KB VAT producer (businesscases aggregator) must agree PER KEY, not just in
        // total — a total-only check is exactly what let this finding hide. This fixture is the
        // only Vorgang in the export, so its aggregate rows are a per-key partition of it.
        val bc = readRows(zip, "businesscases.csv")
        assertEquals(
            byKey(lv, "POS_BRUTTO"),
            byKey(bc, "Z_UMS_BRUTTO"),
            "the KB businesscases aggregator must bucket per key identically (INV-F6)",
        )
        assertEquals(
            byKey(lv, "POS_NETTO"),
            byKey(bc, "Z_UMS_NETTO"),
            "the KB businesscases aggregator must net per key identically (INV-F6)",
        )
        // The concrete split this fixture must produce: 60,00 -> key 1 net 50,42; −50,00 -> key 5.
        assertEquals("50,42", tv.single { it["UST_SCHLUESSEL"] == "1" }["BON_NETTO"])
        assertEquals("9,58", tv.single { it["UST_SCHLUESSEL"] == "1" }["BON_UST"])
        assertEquals("-50,00", tv.single { it["UST_SCHLUESSEL"] == "5" }["BON_NETTO"])
        assertEquals("0,00", tv.single { it["UST_SCHLUESSEL"] == "5" }["BON_UST"])
    }

    @Test
    fun `correction of a Privateinlage nets inside Privateinlage, never DifferenzSollIst (KABS-5)`() {
        // Round-3 KABS-5: a correction is a Storno (CORRECTION_OUT / REVERSAL) + re-entry of an
        // arbitrary Geschäftsvorfall. Correcting a Privateinlage of +1000 down to +100 must net
        // INSIDE Privateinlage (1000 − 1000 + 100 = 100). Anhang C p. 59 reserves DifferenzSollIst
        // for the counted Soll/Ist difference at Kassensturz (CASH_SURPLUS/SHORTAGE), which have
        // no `corrects` link — so a correction must NOT surface a phantom DifferenzSollIst.
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            TestData.createZBonFullDay(shop, admin, nummer = 1L)

            // Original Privateinlage +1000 (PRIVATE_DEPOSIT -> Privateinlage, m6).
            val original =
                repository.createEntry(
                    shop = shop,
                    employee = admin,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.PRIVATE_DEPOSIT,
                            belegNr = "2026-07-1001",
                            vorzBetrag = "1000.0",
                            belegDatum = "16.07.2026",
                            belegtext = "Privateinlage",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "1000.0", belegtext = "Privateinlage"),
                        ),
                )

            // REVERSAL: Storno −1000, entryType CORRECTION_OUT, corrects the original.
            repository.createEntry(
                shop = shop,
                employee = admin,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CORRECTION_OUT,
                        belegNr = "2026-07-1002",
                        vorzBetrag = "-1000.0",
                        belegDatum = "16.07.2026",
                        belegtext = "Storno zu 2026-07-1001",
                        correctsHeaderId = original.id.value,
                        correctionRole = CorrectionRole.REVERSAL,
                    ),
                lineItemsData =
                    listOf(
                        KassenbuchDto.LineItemData(vorzBetrag = "-1000.0", belegtext = "Storno"),
                    ),
            )

            // RE_ENTRY: corrected Privateinlage +100 (a real PRIVATE_DEPOSIT booking, corrects original).
            repository.createEntry(
                shop = shop,
                employee = admin,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.PRIVATE_DEPOSIT,
                        belegNr = "2026-07-1003",
                        vorzBetrag = "100.0",
                        belegDatum = "16.07.2026",
                        belegtext = "Privateinlage (korrigiert)",
                        correctsHeaderId = original.id.value,
                        correctionRole = CorrectionRole.RE_ENTRY,
                    ),
                lineItemsData =
                    listOf(
                        KassenbuchDto.LineItemData(vorzBetrag = "100.0", belegtext = "Privateinlage"),
                    ),
            )
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }

        val privateinlage = readRows(zip, "businesscases.csv").filter { it["GV_TYP"] == "Privateinlage" }
        assertEquals(1, privateinlage.size, "exactly one Privateinlage businesscases row")
        assertEquals("100,00", privateinlage.single()["Z_UMS_BRUTTO"], "1000 − 1000 + 100 nets to 100")

        val diff = readRows(zip, "businesscases.csv").filter { it["GV_TYP"] == "DifferenzSollIst" }
        assertEquals(0, diff.size, "corrections must NOT surface a phantom DifferenzSollIst")
    }

    /** Parses a DSFinV-K decimal ("−50,00") into a BigDecimal. */
    private fun germanToBigDecimal(value: String): java.math.BigDecimal = java.math.BigDecimal(value.replace(".", "").replace(',', '.'))

    private fun sumGerman(
        rows: List<Map<String, String>>,
        column: String,
    ): java.math.BigDecimal = rows.fold(java.math.BigDecimal.ZERO) { acc, r -> acc + germanToBigDecimal(r[column]!!) }

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
