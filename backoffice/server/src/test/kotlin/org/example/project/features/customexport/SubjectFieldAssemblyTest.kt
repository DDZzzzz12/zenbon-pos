package org.example.project.features.customexport

import org.example.project.features.customexport.domain.catalogue.Cardinality
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportField
import org.example.project.features.customexport.domain.catalogue.subjects.PartnerSubject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A subject no longer lists its fields; it declares a root and the fields are assembled from the
 * registry and the schema graph. That makes the assembly the thing worth asserting: it decides what an
 * operator is offered, and — because a field key is what a definition stores — what a saved export
 * still resolves to next year.
 */
@DisplayName("Subject field assembly")
class SubjectFieldAssemblyTest {
    @Test
    fun `a subject offers its root table's registered columns first, ungrouped`() {
        val first = PartnerSubject.subject.allFields.first()

        assertEquals("partner.name", first.key)
        assertNull(first.group, "a column of the subject's own table needs no section heading")
    }

    /**
     * The point of the whole slice: a column of a *related* table is reachable without the subject
     * naming it, and arrives carrying its table's heading so the picker can group it.
     */
    @Test
    fun `a subject also offers a related table's columns, grouped`() {
        val creditorNo =
            assertNotNull(
                PartnerSubject.subject.allFields.firstOrNull { it.label == "Kreditorennummer" },
                "the creditor role's number is not offered on the partner subject",
            )

        assertEquals("Kreditor-Rolle", creditorNo.group)
        assertTrue(
            creditorNo.key.endsWith(".creditor_no"),
            "a relation field's key is the path key plus the real column name, but was '${creditorNo.key}'",
        )
    }

    /**
     * Deriving the keys must not have renamed them.
     *
     * These four are stored in `export_definition_field` rows written before this refactor; a key that
     * changed shape here would break every one of them, and the breakage would show up as a definition
     * that saves fine and refuses to run.
     */
    @Test
    fun `root field keys are unchanged from slice 1`() {
        val keys = PartnerSubject.subject.allFields.map { it.key }

        listOf("partner.name", "partner.city", "partner.vat_id", "partner.created_at").forEach { key ->
            assertTrue(key in keys, "the stored key '$key' is no longer offered")
        }
    }

    /** A duplicate key would make a stored definition ambiguous about which column it meant. */
    @Test
    fun `every subject's field keys are unique`() {
        ExportCatalogue.subjects.forEach { subject ->
            val keys = subject.allOfferings.map { it.key }
            assertEquals(keys.size, keys.toSet().size, "duplicate field key in subject '${subject.key}'")
        }
    }

    /**
     * The to-many half of the same assembly, over `allOfferings` rather than `allFields`.
     *
     * Every key is a **literal**. Rebuilding them from the path key and the separator the production
     * code uses would agree with whatever that code produced, including a wrong key — and these are
     * what an `export_definition_field` row stores, so a wrong one breaks saved exports rather than
     * only this run. Both shapes are here on purpose: the bank accounts hang off the root directly,
     * the invoices are two hops away with the to-many edge second, which is what proves an aggregate
     * rides a multi-hop path and not only a direct child of the root.
     */
    @Test
    fun `a subject offers the aggregates of the to-many tables it can reach`() {
        val keys = PartnerSubject.subject.allOfferings.map { it.key }

        listOf(
            "supplier_bank_account:by:partner_id~list",
            "supplier_bank_account:by:partner_id~count",
            "supplier:by:partner_id.invoice:by:supplier_id~count@period",
            "supplier:by:partner_id.invoice:by:supplier_id~count",
            "supplier:by:partner_id.invoice:by:supplier_id~sum@period",
            "supplier:by:partner_id.invoice:by:supplier_id~sum",
            "supplier:by:partner_id.invoice:by:supplier_id~first@period",
            "supplier:by:partner_id.invoice:by:supplier_id~first",
            "supplier:by:partner_id.invoice:by:supplier_id~last@period",
            "supplier:by:partner_id.invoice:by:supplier_id~last",
        ).forEach { key ->
            assertTrue(key in keys, "the aggregate '$key' is not offered; offered: $keys")
        }
    }

    /**
     * Offering the aggregates must not have opened the column door behind them.
     *
     * `supplier_bank_account` is UNIQUE(partner_id, iban), so one company is several rows: a column of
     * it could only be read by joining, which would emit that partner once per IBAN with every other
     * cell duplicated and nothing in the file to say the row count is not a partner count.
     */
    @Test
    fun `and still no ordinary column of a to-many table`() {
        val columns = PartnerSubject.subject.allOfferings.filterIsInstance<ExportField>()

        assertTrue(
            columns.none { it.key.startsWith("supplier_bank_account") },
            "a to-many table may contribute aggregates only: ${columns.map { it.key }}",
        )
        assertEquals(
            PartnerSubject.subject.allFields.map { it.key },
            columns.map { it.key },
            "allFields is exactly the column half of allOfferings — a caller after joinable columns " +
                "must get the same list either way",
        )
    }

    /** The two halves are disjoint, so neither can be counted twice or silently stand in for the other. */
    @Test
    fun `allOfferings is the disjoint union of the columns and the aggregates`() {
        ExportCatalogue.subjects.forEach { subject ->
            val columnKeys = subject.allFields.map { it.key }.toSet()
            val aggregateKeys = subject.allAggregates.map { it.key }.toSet()

            assertEquals(emptySet(), columnKeys intersect aggregateKeys, "in subject '${subject.key}'")
            assertEquals(columnKeys + aggregateKeys, subject.allOfferings.map { it.key }.toSet())
        }
    }

    /**
     * A to-many path joined naively multiplies the export's rows, duplicating every other column with
     * nothing in the file to say so. Such a path must never be *offered*, because an offered field
     * gets ticked and saved.
     */
    @Test
    fun `no to-many path is offered`() {
        ExportCatalogue.subjects.forEach { subject ->
            val toMany = subject.paths.filter { it.cardinality != Cardinality.ONE }
            assertTrue(toMany.isEmpty(), "subject '${subject.key}' offers to-many paths: ${toMany.map { it.key }}")
        }
    }

    /**
     * The complement, and the guard that keeps an aggregate path out of the FROM clause.
     *
     * `aggregatePaths` exists to be read by the subquery builder and by nothing that joins. A to-one
     * path leaking into it would be aggregated over a row that is already joinable; a to-many path
     * leaking into `paths` or into `alwaysJoined` is the row multiplication itself. The `alwaysJoined`
     * half is asserted here as well as in the subject's `init`, because the `init` only ever fires for
     * the subjects that exist today and this runs over whatever the catalogue grows next.
     */
    @Test
    fun `every aggregate path is to-many and is never joined`() {
        ExportCatalogue.subjects.forEach { subject ->
            assertTrue(
                subject.aggregatePaths.all { it.cardinality == Cardinality.MANY },
                "subject '${subject.key}' aggregates over a joinable path",
            )
            val bothWays = subject.aggregatePaths.map { it.key }.toSet() intersect subject.paths.map { it.key }.toSet()
            assertEquals(emptySet(), bothWays, "a path is either joined or aggregated, never both")
            val alwaysJoined = subject.alwaysJoined.map { it.key }.toSet()
            assertTrue(
                subject.aggregatePaths.none { it.key in alwaysJoined },
                "subject '${subject.key}' always-joins a to-many path, multiplying every row of every run",
            )
        }
    }

    /**
     * A filter reads its table's columns whether or not the operator ticked one, so that table has to
     * be in the query before the filter is built — which a path joined on demand cannot promise.
     */
    @Test
    fun `every table a toggle reads is always joined`() {
        val invoice = assertNotNull(ExportCatalogue.subject("invoice"))

        assertTrue(
            invoice.alwaysJoined.any { it.target.tableName == "invoice_booking" },
            "'Nur offene' reads invoice_booking.paid_at, so that path must be joined on every run",
        )
    }
}
