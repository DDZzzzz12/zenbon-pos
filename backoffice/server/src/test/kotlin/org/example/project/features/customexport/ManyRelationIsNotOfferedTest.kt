package org.example.project.features.customexport

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.Cardinality
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportField
import org.example.project.features.customexport.domain.catalogue.ExportTableRegistry
import org.example.project.features.customexport.domain.catalogue.SchemaGraph
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A registered table whose relation is to-many is **found** by the walk and **never offered**.
 *
 * `supplier_bank_account` is the first table registered without being exportable, and the pair of
 * assertions here is the point: registration is what lets the graph see a path at all, and
 * [org.example.project.features.customexport.domain.catalogue.ExportSubject.paths] is what decides
 * whether an operator may tick a *column* of it. Collapsing the two — refusing to register the table
 * until the aggregate slice could offer it — would have left that slice rediscovering a path this one
 * already mapped.
 *
 * Why it must stay hidden: `supplier_bank_account` is UNIQUE(partner_id, iban), so a company with
 * three known IBANs is three rows. LEFT joining it would emit that partner three times with every
 * other column duplicated, and nothing in the CSV would say the row count is not a partner count.
 * The recipient reconciles against it and is wrong. A to-many relation belongs in a correlated
 * subquery, which leaves the row count alone — and, since the aggregate slice, that is exactly how it
 * is offered. So the claim this class defends is no longer "the table is unreachable" but the sharper
 * one: **it contributes no ordinary column, in any subject, to any caller.**
 */
@DisplayName("A to-many relation is discovered but not offered")
class ManyRelationIsNotOfferedTest {
    /** Both role capabilities: whatever hides the bank account, it is not a missing capability. */
    private val everyRole =
        setOf(Capability.CUSTOM_EXPORT_READ, Capability.CREDITOR_READ, Capability.DEBTOR_READ)

    /** The table name as it appears inside a field key, via `SchemaGraph.segmentOf`. */
    private val bankAccount = SupplierBankAccountTable.tableName.lowercase()

    @Test
    fun `the table is registered`() {
        val entry =
            assertNotNull(
                ExportTableRegistry.entryFor(SupplierBankAccountTable),
                "the rest of this class proves nothing if the table is simply absent from the registry",
            )
        assertEquals("Bankverbindung", entry.label)
    }

    /**
     * The exposure half. Checked across **every** subject, not only the partner one: a table hidden
     * on one root and offered on another would be exactly the leak, and a per-subject assertion
     * would not see it.
     *
     * Narrowed to the **column** namespace now that the aggregate slice offers this same table as
     * correlated subqueries. The property being guarded has not moved: a to-many table contributes no
     * ordinary column, because an ordinary column can only be read by joining. What changed is that
     * "no key mentions the table" stopped expressing it — `~count` mentions it and is fine. Selecting
     * by type rather than by spelling is what keeps this honest: [ExportField] *is* the column kind,
     * so the assertion cannot be satisfied by a rename.
     */
    @Test
    fun `no subject offers a bank-account column to a caller holding every capability`() {
        val offered =
            ExportCatalogue
                .visibleTo(everyRole)
                .flatMap { it.fields }
                .filterIsInstance<ExportField>()
                .filter { it.key.contains(bankAccount) }

        assertTrue(
            offered.isEmpty(),
            "a to-many relation must never be tickable as a column; offered: ${offered.map { it.key }}",
        )
    }

    /**
     * And the aggregates of that same table **are** offered to that same caller.
     *
     * Without this the test above could be passed by a catalogue that had simply stopped reaching
     * `supplier_bank_account` at all — which is the state this class was written in and no longer
     * describes. The literal keys are what a definition stores, so they are spelled out rather than
     * rebuilt from the path key.
     */
    @Test
    fun `the same caller is offered its aggregates instead`() {
        val offered =
            ExportCatalogue
                .visibleTo(everyRole)
                .flatMap { it.fields }
                .map { it.key }

        assertTrue("supplier_bank_account:by:partner_id~list" in offered, "offered: $offered")
        assertTrue("supplier_bank_account:by:partner_id~count" in offered, "offered: $offered")
    }

    /**
     * The same, one level down: the subject's own column list, before any capability filtering.
     *
     * `allFields` is the column half by type, so this cannot be satisfied by an aggregate sneaking in
     * under a column-shaped key; the companion assertion pins that the whole offering does carry the
     * aggregates, so the pair still distinguishes "aggregated" from "absent".
     */
    @Test
    fun `no subject's column list carries one either`() {
        val offered =
            ExportCatalogue.subjects
                .flatMap { it.allFields }
                .filter { it.key.contains(bankAccount) }

        assertTrue(offered.isEmpty(), "offered: ${offered.map { it.key }}")
        assertTrue(
            ExportCatalogue.subjects.flatMap { it.allAggregates }.any { it.table == SupplierBankAccountTable },
            "the table must be reachable as an aggregate, or the assertion above is passed by absence",
        )
    }

    /**
     * The discovery half. The path exists, carries its columns, and is marked [Cardinality.MANY] —
     * which is what the aggregate slice will pick it out by.
     */
    @Test
    fun `the graph finds the path and marks it to-many`() {
        val path =
            assertNotNull(
                SchemaGraph.pathsFrom(BusinessPartnerTable).firstOrNull { it.target == SupplierBankAccountTable },
                "registering the table must make its relation discoverable, or the later slice has nothing to build on",
            )

        assertEquals(Cardinality.MANY, path.cardinality)
        assertEquals("Bankverbindung", path.label)
        // Not columns: a to-many path contributes none, because reading one would mean joining the
        // path. Its data arrives as aggregates instead, which are correlated subqueries and leave the
        // row count alone. `AggregateFieldAssemblyTest` holds the shape of these.
        assertTrue(path.fields.isEmpty(), "a to-many path must contribute no column field: ${path.fields.map { it.key }}")
        assertTrue(
            path.aggregateFields.any { it.key.endsWith("~list") },
            "the path carries the aggregates over the columns the subquery will read",
        )
    }

    /**
     * The mechanism, stated where a future reader will look for it: it is the *cardinality* filter
     * that hides the path, not an absent registration and not a capability.
     */
    @Test
    fun `it is the cardinality filter that removes it`() {
        val subject = assertNotNull(ExportCatalogue.subject("partner"))

        assertTrue(SchemaGraph.pathsFrom(subject.root).any { it.target == SupplierBankAccountTable })
        assertTrue(subject.paths.none { it.target == SupplierBankAccountTable })
        assertTrue(subject.paths.all { it.cardinality == Cardinality.ONE })
    }
}
