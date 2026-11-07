package org.example.project.features.customexport

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.AggregateFunction
import org.example.project.features.customexport.domain.catalogue.Cardinality
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportFieldType
import org.example.project.features.customexport.domain.catalogue.ExportRelationPath
import org.example.project.features.customexport.domain.catalogue.ExportTableRegistry
import org.example.project.features.customexport.domain.catalogue.RelationHop
import org.example.project.features.customexport.domain.catalogue.SchemaGraph
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The graph attaches a registered table's aggregates to the **to-many** paths that reach it.
 *
 * Two namespaces, two lists, one rule each. A to-one path contributes columns and no aggregates: the
 * far row is already joinable, so a count of it is 0 or 1 and a sum of it is the value itself. A
 * to-many path contributes aggregates and no columns: joining it to read a column multiplies the
 * export's rows, which is the defect the whole design exists to prevent.
 *
 * Every key here is written out as a **literal**. A computed expectation — rebuilding the key from
 * the same path key and separator the production code uses — agrees with whatever the code produces,
 * including a wrong one, and these keys are stored in `export_definition`: getting one wrong breaks
 * saved exports rather than only this run.
 */
@DisplayName("Aggregate fields attach to to-many paths")
class AggregateFieldAssemblyTest {
    private val partnerPaths: List<ExportRelationPath> = SchemaGraph.pathsFrom(BusinessPartnerTable)

    private fun path(key: String): ExportRelationPath =
        assertNotNull(
            partnerPaths.firstOrNull { it.key == key },
            "no path '$key' from business_partner; found ${partnerPaths.map { it.key }}",
        )

    @Test
    fun `a to-many path carries aggregate fields and no column fields`() {
        val bankAccounts = path("supplier_bank_account:by:partner_id")

        assertEquals(Cardinality.MANY, bankAccounts.cardinality)
        assertEquals(SupplierBankAccountTable, bankAccounts.target)
        assertTrue(
            bankAccounts.fields.isEmpty(),
            "a to-many path must offer no column: ${bankAccounts.fields.map { it.key }}",
        )
        assertTrue(bankAccounts.aggregateFields.isNotEmpty())
    }

    @Test
    fun `a to-one path carries column fields and no aggregate fields`() {
        val creditorRole = path("supplier:by:partner_id")

        assertEquals(Cardinality.ONE, creditorRole.cardinality)
        assertEquals(SupplierTable, creditorRole.target)
        assertTrue(creditorRole.fields.any { it.key == "supplier:by:partner_id.creditor_no" })
        assertTrue(
            creditorRole.aggregateFields.isEmpty(),
            "an aggregate over a joinable row answers nothing new: ${creditorRole.aggregateFields.map { it.key }}",
        )
    }

    /**
     * The same rule where it can actually bite, on a hand-built path.
     *
     * No path in today's catalogue reaches an aggregate-declaring table to-one, so the assertion
     * above would still pass if the cardinality guard were deleted outright — it would be asserting
     * that a table with no aggregates declares no aggregates. This one builds the case the catalogue
     * does not yet contain: a [Cardinality.ONE] path ending at `supplier_bank_account`, which does
     * declare two. It must still offer none.
     */
    @Test
    fun `a to-one path to an aggregate-declaring table still offers no aggregate`() {
        val asIfToOne =
            ExportRelationPath(
                key = "supplier_bank_account:by:partner_id",
                label = "Bankverbindung",
                hops =
                    listOf(
                        RelationHop(
                            target = SupplierBankAccountTable,
                            from = BusinessPartnerTable.id,
                            to = SupplierBankAccountTable.partnerId,
                        ),
                    ),
                cardinality = Cardinality.ONE,
                fields = emptyList(),
                requiredCapabilities = setOf(Capability.CREDITOR_READ),
            )

        assertTrue(
            ExportTableRegistry.entryFor(SupplierBankAccountTable)?.aggregates?.isNotEmpty() == true,
            "this test proves nothing unless the target really does declare aggregates",
        )
        assertTrue(
            asIfToOne.aggregateFields.isEmpty(),
            "over a joinable row a count is 0 or 1 and a sum is the value itself: " +
                "${asIfToOne.aggregateFields.map { it.key }}",
        )
    }

    /** No period column, so no ambiguity to disclose: one field per aggregate, bare label. */
    @Test
    fun `a table without a period column yields exactly one field per aggregate`() {
        val bankAccounts = path("supplier_bank_account:by:partner_id")

        assertEquals(
            listOf(
                "supplier_bank_account:by:partner_id~list",
                "supplier_bank_account:by:partner_id~count",
            ),
            bankAccounts.aggregateFields.map { it.key },
        )
        assertEquals(listOf("IBANs", "Anzahl Bankverbindungen"), bankAccounts.aggregateFields.map { it.label })
        assertTrue(
            bankAccounts.aggregateFields.none { it.periodScoped },
            "an IBAN is not an event; there is nothing for a Zeitraum to constrain",
        )
    }

    /**
     * The interesting one: the invoice aggregates are reached from the **partner** root over two
     * hops, the second of which is the to-many edge. It proves aggregates ride a multi-hop path, not
     * only a direct child of the root.
     */
    @Test
    fun `a table with a period column yields a twin per aggregate over a two-hop path`() {
        val invoices = path("supplier:by:partner_id.invoice:by:supplier_id")

        assertEquals(Cardinality.MANY, invoices.cardinality)
        assertEquals(InvoiceTable, invoices.target)
        assertEquals(2, invoices.hops.size)
        assertEquals(
            listOf(
                "supplier:by:partner_id.invoice:by:supplier_id~count@period",
                "supplier:by:partner_id.invoice:by:supplier_id~count",
                "supplier:by:partner_id.invoice:by:supplier_id~sum@period",
                "supplier:by:partner_id.invoice:by:supplier_id~sum",
                "supplier:by:partner_id.invoice:by:supplier_id~first@period",
                "supplier:by:partner_id.invoice:by:supplier_id~first",
                "supplier:by:partner_id.invoice:by:supplier_id~last@period",
                "supplier:by:partner_id.invoice:by:supplier_id~last",
            ),
            invoices.aggregateFields.map { it.key },
        )
    }

    @Test
    fun `the twin says in its label and its flag which of the two it is`() {
        val invoices = path("supplier:by:partner_id.invoice:by:supplier_id")
        val sums = invoices.aggregateFields.filter { it.aggregate.key == "sum" }

        assertEquals(2, sums.size)
        assertEquals(listOf("Rechnungssumme (Zeitraum)", "Rechnungssumme (gesamt)"), sums.map { it.label })
        assertEquals(listOf(true, false), sums.map { it.periodScoped })
        assertEquals("supplier:by:partner_id.invoice:by:supplier_id~sum@period", sums.first().key)
        assertEquals("supplier:by:partner_id.invoice:by:supplier_id~sum", sums.last().key)
    }

    /**
     * Type, capability and table come from the owning entry, exactly as a column's do — and the
     * **group comes from the path**, not the entry, so two routes to one table cannot put both sets
     * of aggregates under one heading.
     */
    @Test
    fun `an aggregate field carries the owning table's capability, group and table`() {
        val invoices = path("supplier:by:partner_id.invoice:by:supplier_id")
        val count = invoices.aggregateFields.first { it.key.endsWith("~count") }

        assertEquals(AggregateFunction.COUNT, count.aggregate.function)
        assertEquals(ExportFieldType.NUMBER, count.type)
        assertEquals(Capability.CREDITOR_READ, count.capability)
        assertEquals(
            "Kreditor-Rolle → Eingangsrechnung",
            count.group,
            "the path's heading, which names the route; the table's own label is only its last hop",
        )
        assertEquals(invoices.label, count.group, "an aggregate sits under exactly its path's heading")
        assertEquals(InvoiceTable, count.table)
        assertEquals(invoices.key, count.path.key)
    }

    /**
     * The `~` separator's whole job, asserted across the catalogue rather than on one path.
     *
     * Both kinds share one `capabilityRequirements()` map keyed by field key. If an aggregate key
     * could ever equal a column key, the save check and the run check could resolve one stored key to
     * two different definitions — the exact disagreement that map exists to make impossible.
     */
    @Test
    fun `no aggregate key equals any column key anywhere in the catalogue`() {
        val columnKeys = mutableSetOf<String>()
        val aggregateKeys = mutableSetOf<String>()
        ExportCatalogue.subjects.forEach { subject ->
            columnKeys += subject.allFields.map { it.key }
            SchemaGraph.pathsFrom(subject.root).forEach { path ->
                columnKeys += path.fields.map { it.key }
                aggregateKeys += path.aggregateFields.map { it.key }
            }
        }

        assertTrue(aggregateKeys.isNotEmpty(), "the disjointness claim is vacuous with no aggregate keys")
        assertEquals(
            emptySet(),
            columnKeys intersect aggregateKeys,
            "a stored key must name exactly one kind of field",
        )
    }
}
