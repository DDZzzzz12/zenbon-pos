package org.example.project.features.customexport

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.customexport.domain.catalogue.Cardinality
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportFieldType
import org.example.project.features.customexport.domain.catalogue.ExportTableRegistry
import org.example.project.features.customexport.domain.catalogue.ForbiddenExportTargets
import org.example.project.features.customexport.domain.catalogue.SchemaGraph
import org.example.project.features.kassenbuch.database.tables.CashBookTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchLineItemTable
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.rules.BillRules
import org.example.project.infrastructure.security.rules.CashBookRules
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the two fiscal subjects are, and what they deliberately are **not**.
 *
 * Both `bill` and `kassenbuch_header` already have a correct compliance export — DSFinV-K and the TSE
 * export for the one, DATEV XML and the Buchungsstapel for the other. A custom extract is a working
 * aid, and the risk this file guards is that it drifts into resembling the real thing: a reader has no
 * way to tell a plausible CSV from an authoritative one, so the distance has to be asserted rather
 * than remembered.
 */
@DisplayName("Kassenbelege and Kassenbuch, as catalogue subjects")
class FiscalSubjectsTest {
    /**
     * The Datenbereich is **Kassenbelege**, not "Rechnungen".
     *
     * The word was already doing two jobs in this catalogue — `Eingangsrechnungen` and
     * `Ausgangsrechnungen` — and the manual keeps the concepts apart in the operator's own vocabulary
     * (chapter 6, *"Beleg und formelle Rechnung"*). A third area called "Rechnungen" would name a
     * different thing with the same word beside the other two. Pinned so a later rename has to argue
     * with this sentence.
     */
    @Test
    fun `the bill subject is Kassenbelege, ranges over the closing time, and carries no shop`() {
        val subject = assertNotNull(ExportCatalogue.subject("bill"))

        assertEquals("Kassenbelege", subject.label)
        assertEquals(BillTable, subject.root)
        assertEquals(BillTable.endTime, subject.dateColumn)
        assertEquals(
            null,
            subject.shopColumn,
            "`bill` carries no shop of its own; the decision is written down in BillSubject's KDoc",
        )
        assertEquals(listOf("Ohne Schulungsbelege"), subject.toggles.map { it.label })
    }

    /**
     * `BILL_READ` is what `BillRules` requires of `GET /api/v1/bills`, and resolving it from the rule
     * module rather than restating the constant is the point: an export must never be an easier way to
     * read data than the screen showing it, and a silent widening here would change nothing visible.
     */
    @Test
    fun `the bill table is gated by the capability its own list route requires`() {
        val entry = assertNotNull(ExportTableRegistry.entryFor(BillTable))

        assertEquals(
            AccessLevel.Cap(entry.capability),
            BillRules.rules["/api/v1/bills" to HttpMethod.Get],
            "the registry entry's capability must be the one the bills list route itself carries",
        )
        assertEquals(Capability.BILL_READ, entry.capability, "which today is BILL_READ")
    }

    /**
     * The same for the cash book: `CASHBOOK_READ`, which `CashBookRules` requires of
     * `GET /api/v1/kassenbuch/entries`. `CASHBOOK_WRITE` and `CASHBOOK_CLOSE` are the posting rungs of
     * that ladder and have nothing to do with reading a list.
     */
    @Test
    fun `the cash-book tables are gated by CASHBOOK_READ`() {
        listOf(CashBookTable, KassenbuchHeaderTable, KassenbuchLineItemTable).forEach { table ->
            val entry = assertNotNull(ExportTableRegistry.entryFor(table), "unregistered: ${table.tableName}")
            assertEquals(Capability.CASHBOOK_READ, entry.capability, "on ${table.tableName}")
        }
        assertEquals(
            AccessLevel.Cap(Capability.CASHBOOK_READ),
            CashBookRules.rules["/api/v1/kassenbuch/entries" to HttpMethod.Get],
            "which is what the entries list route itself carries",
        )
    }

    @Test
    fun `the kassenbuch subject roots on the header and is scoped to the shop`() {
        val subject = assertNotNull(ExportCatalogue.subject("kassenbuch"))

        assertEquals("Kassenbuch", subject.label)
        assertEquals(KassenbuchHeaderTable, subject.root)
        assertEquals(KassenbuchHeaderTable.createdAt, subject.dateColumn)
        assertEquals(KassenbuchHeaderTable.shopId, subject.shopColumn)
    }

    /**
     * `beleg_datum` is a `varchar(10)` holding "01.02.2026", so the Zeitraum cannot range over it and
     * `created_at` is the filter column. Asserted because the wrong choice is the plausible one: a
     * subject author reaching for the field named "Datum" would get a column no comparison can order,
     * and the filter would silently match nothing.
     */
    @Test
    fun `the Belegdatum is offered as text and is not what the Zeitraum ranges over`() {
        val subject = assertNotNull(ExportCatalogue.subject("kassenbuch"))
        val belegDatum = assertNotNull(subject.allFields.firstOrNull { it.key == "kassenbuch.beleg_datum" })

        assertEquals("Belegdatum", belegDatum.label)
        assertEquals(
            ExportFieldType.TEXT,
            belegDatum.type,
            "it is a German date string in a varchar, and declaring it DATE would misstate the column",
        )
        assertEquals(KassenbuchHeaderTable.createdAt, subject.dateColumn)
    }

    /**
     * Neither table carries TSE signature material inline — that was the finding, and it is worth
     * stating in a test rather than in a commit message.
     *
     * Each declares exactly two `tse_`-prefixed columns, `tse_transaction_id` and
     * `tse_signing_status`, and the signature itself lives in `tse_transaction`, which
     * [ForbiddenExportTargets.TABLES] denies and nothing registers. The prefixes were added anyway, so
     * a future `bill.tse_signature` fails a build instead of needing a reviewer to notice; this asserts
     * both halves — that the prefix denies every such column the tables declare today, and that it did
     * not accidentally grow wide enough to deny an ordinary one.
     */
    @Test
    fun `every declared TSE column of bill and kassenbuch_header is denied by prefix`() {
        listOf(BillTable, KassenbuchHeaderTable).forEach { table ->
            val tse =
                table.columns
                    .map { "${it.table.tableName.lowercase()}.${it.name.lowercase()}" }
                    .filter { it.substringAfter('.').startsWith("tse_") }

            assertTrue(tse.isNotEmpty(), "${table.tableName} declares no tse_ column; has the table moved?")
            tse.forEach { assertTrue(ForbiddenExportTargets.deniesColumn(it), "not denied: $it") }
        }
        assertTrue(ForbiddenExportTargets.deniesColumn("bill.tse_transaction_id"))
        assertTrue(ForbiddenExportTargets.deniesColumn("kassenbuch_header.tse_signing_status"))
        assertTrue(!ForbiddenExportTargets.deniesColumn("bill.bill_number"), "the prefix is not too broad")
    }

    /**
     * The Kassenbuch export is not a Buchungsstapel.
     *
     * `bu`, `gegenkonto`, `kost1`, `kost2` and `waehrung` are exactly the DATEV posting fields, and a
     * CSV carrying them beside Belegnummer, Belegdatum and Betrag would be an EXTF row in all but the
     * metadata line. Leaving them out is the decision this asserts; the real DATEV exports already
     * exist and are gated separately.
     */
    @Test
    fun `the Kassenbuch entry offers no DATEV posting field`() {
        val entry = assertNotNull(ExportTableRegistry.entryFor(KassenbuchHeaderTable))
        val registered = entry.columns.map { it.column.name }

        listOf("bu", "gegenkonto", "kost1", "kost2", "waehrung", "nachricht").forEach {
            assertTrue(it !in registered, "'$it' is a DATEV posting field and must not be a custom-export column")
        }
        assertTrue("beleg_nr" in registered, "and the working columns are there, or this proves nothing")
        assertTrue("total_amount" in registered)
    }

    /**
     * The two to-many tables contribute aggregates and never columns, which is what keeps one bill and
     * one cash-book entry to one row each.
     */
    @Test
    fun `the VAT split and the line items are aggregate-only`() {
        val bill = assertNotNull(ExportCatalogue.subject("bill"))
        val kassenbuch = assertNotNull(ExportCatalogue.subject("kassenbuch"))

        assertTrue(
            bill.allFields.none { it.column.table == BillVatDetailTable },
            "bill_vat_detail must contribute no joinable column: ${bill.allFields.map { it.key }}",
        )
        assertTrue(
            bill.allAggregates.any { it.key == "bill_vat_detail:by:bill_id~count" },
            "but it must be reachable as an aggregate, or the assertion above passes by absence",
        )
        assertTrue(kassenbuch.allFields.none { it.column.table == KassenbuchLineItemTable })
        assertEquals(
            listOf("kassenbuch_line_item:by:header_id~count", "kassenbuch_line_item:by:header_id~sum"),
            kassenbuch.allAggregates.filter { it.key.startsWith("kassenbuch_line_item") }.map { it.key },
            "one count and one sum, and neither twinned: a line item has no date of its own, so the " +
                "table declares no periodColumn and there is no (Zeitraum)/(gesamt) pair to offer",
        )
    }

    /**
     * What registering `employees` did to this subject, and what was then taken back out.
     *
     * The history is the point. `kassenbuch_header.employee_id` is a foreign key, so registering the
     * staff tables put the Personal root one hop from a cash-book entry and `sales_invoice` — which
     * declares aggregates and a period column — two hops away by *two* routes, `issued_by` and
     * `cancelled_by`. This subject silently gained sixteen cells about the **revenue of the person who
     * booked each entry**, and an earlier revision of this test pinned them as the new truth.
     *
     * Pinning them was right; keeping them was not. A cash-book export listing Personalnummer, Vorname,
     * Nachname and Umsatz (Zeitraum) is the same per-employee league table the Personal subject was
     * made to stop offering — a different Datenbereich, the same file, and the same Leistungs- und
     * Verhaltenskontrolle under § 87 Abs. 1 Nr. 6 BetrVG. So they are now suppressed by
     * `ExportTable.aggregatesAreMonitoring` on the `employees` entry, and this test asserts the
     * suppression from the *fiscal* side: the same literal keys, on the same subject, now absent from
     * what is offered and present in what is withheld.
     *
     * The capability assertion moved with them. It is made against a **withheld** cell here, and it
     * still matters: `suppressedAggregates` is what the runner reads to refuse a stored definition by
     * name, so a cell that fell out of the path machinery would be reported to the operator as an
     * unknown field instead of as a decision. `MonitoringAggregateSuppressionTest` holds the same rule
     * across the whole catalogue; this one keeps the cash book's own route named where a reader of the
     * fiscal subjects will find it.
     */
    @Test
    fun `the cash book withholds the booking employee's invoice aggregates`() {
        val kassenbuch = assertNotNull(ExportCatalogue.subject("kassenbuch"))

        val functions = listOf("count", "sum", "first", "last")
        val foreignKeys = listOf("issued_by", "cancelled_by")
        val perHead =
            functions.flatMap { function ->
                foreignKeys.flatMap { fk ->
                    listOf(
                        "employees:via:employee_id.sales_invoice:by:$fk~$function@period",
                        "employees:via:employee_id.sales_invoice:by:$fk~$function",
                    )
                }
            }

        assertEquals(
            emptyList(),
            kassenbuch.allAggregates.map { it.key }.filter { it.startsWith("employees:") },
            "not one figure about the booking employee's own output may be offered here",
        )
        assertEquals(
            perHead.sorted(),
            kassenbuch.suppressedAggregates.map { it.key }.sorted(),
            "the sixteen derivable cells stay named, so a stored definition can be refused with a reason",
        )
        assertEquals(
            setOf(Capability.CASHBOOK_READ, Capability.EMPLOYEE_READ, Capability.INVOICE_READ),
            // Named in full, not "the first path through employees": `employees:via:employee_id.bill:by:employee_id`
            // is also a to-many path through the staff table, and it carries BILL_READ and no
            // aggregates at all — `bill` declares none, which is the only reason Ausgangsrechnungen
            // never gained per-head figures of its own.
            kassenbuch.aggregatePaths
                .first { it.key == "employees:via:employee_id.sales_invoice:by:issued_by" }
                .requiredCapabilities,
            "the path still costs every capability on it — the suppression is about the figure, not access",
        )
    }

    /**
     * The pairwise restriction registering `cash_book` brings with it, pinned at the catalogue level.
     *
     * `kassenbuch_header` holds two foreign keys into one table, so the graph derives two to-one paths
     * that reach one table. They no longer share a heading — `ExportRelationLabels` names the Geldtransit
     * counterparty — but the pairwise restriction is unchanged: both are legitimate and either works
     * alone; what cannot work is both in one
     * export, and `FiscalSubjectRunTest` proves the refusal. Pinned here because the pair is easy to
     * lose — a schema change dropping `counterparty_book_id` would remove a whole block from the
     * picker, and this says so out loud.
     */
    @Test
    fun `the cash book is reachable by two distinct to-one routes`() {
        val paths = SchemaGraph.pathsFrom(KassenbuchHeaderTable).filter { it.target == CashBookTable }

        assertEquals(
            listOf("cash_book:via:cash_book_id", "cash_book:via:counterparty_book_id"),
            paths.map { it.key },
            "the entry's own book, and the other side of a Geldtransit",
        )
        assertTrue(paths.all { it.cardinality == Cardinality.ONE })
        assertEquals(
            listOf("Kasse", "Gegenkasse (Geldtransit)"),
            paths.map { it.label },
            "two routes to one table must not share a picker heading, or the operator ticks a column " +
                "from each and the run is refused for a choice that looked unambiguous",
        )
    }
}
