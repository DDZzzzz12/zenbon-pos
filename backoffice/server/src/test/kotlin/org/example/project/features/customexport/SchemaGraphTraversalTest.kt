package org.example.project.features.customexport

import org.example.project.features.customexport.domain.catalogue.Cardinality
import org.example.project.features.customexport.domain.catalogue.SchemaGraph
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("Schema graph traversal")
class SchemaGraphTraversalTest {
    @Test
    fun `a registered incoming relation is reachable from its parent`() {
        val paths = SchemaGraph.pathsFrom(BusinessPartnerTable)
        val creditor = assertNotNull(paths.firstOrNull { it.target == SupplierTable })
        assertEquals(Cardinality.ONE, creditor.cardinality)
        assertEquals("Kreditor-Rolle", creditor.label)
        assertTrue(creditor.fields.any { it.key.endsWith(".creditor_no") })
    }

    /**
     * Default-deny is a traversal rule, not only a display rule. An unregistered table is not a hop
     * the walk may pass *through* — otherwise the registry would gate what an operator sees while
     * leaving what the query touches wide open.
     */
    @Test
    fun `the walk stops at an unregistered table`() {
        val reached =
            SchemaGraph
                .pathsFrom(BusinessPartnerTable)
                .flatMap { path -> path.hops.map { it.target } }
                .toSet()
        val unregistered = reached.filterNot { SchemaGraph.isRegistered(it) }
        assertTrue(unregistered.isEmpty(), "Walked through unregistered tables: $unregistered")
    }

    @Test
    fun `no path is longer than the depth limit`() {
        val tooDeep = SchemaGraph.pathsFrom(BusinessPartnerTable).filter { it.hops.size > SchemaGraph.MAX_DEPTH }
        assertTrue(tooDeep.isEmpty(), "Paths past the depth limit: ${tooDeep.map { it.key }}")
    }

    /** The path is the identity, so two routes to one table are two distinct sets of columns. */
    @Test
    fun `path keys are unique`() {
        val keys = SchemaGraph.pathsFrom(BusinessPartnerTable).map { it.key }
        assertEquals(keys.size, keys.toSet().size, "Duplicate path key: $keys")
    }

    /** Field keys carry the path, so a column reached two ways cannot collide in one export. */
    @Test
    fun `field keys are unique across all paths of a subject`() {
        val keys = SchemaGraph.pathsFrom(BusinessPartnerTable).flatMap { path -> path.fields.map { it.key } }
        assertEquals(keys.size, keys.toSet().size, "Duplicate field key: $keys")
    }

    /** Same input, same output — a definition must produce the same SQL on every run. */
    @Test
    fun `traversal order is stable`() {
        assertEquals(
            SchemaGraph.pathsFrom(BusinessPartnerTable).map { it.key },
            SchemaGraph.pathsFrom(BusinessPartnerTable).map { it.key },
        )
    }

    /**
     * A cyclic foreign-key graph must terminate. `business_partner.merged_into_partner_id` points at
     * `business_partner`, so this repo already has one — the walk must bound itself rather than
     * recurse until the stack gives out.
     */
    @Test
    fun `a self-referencing table does not hang the walk`() {
        assertTrue(SchemaGraph.pathsFrom(BusinessPartnerTable).isNotEmpty())
    }

    /**
     * A path that visits one table twice cannot be turned into SQL: `applyTo` folds hops on with
     * Exposed's `leftJoin`, which emits the bare table name, so the second visit puts the same table
     * in the FROM clause twice with no alias. The run would fail, not merely mislead — so the walk
     * must never produce such a path in the first place. The root counts: a path returning to it is
     * both unjoinable and empty, since it can only re-contribute the root's own columns.
     */
    @Test
    fun `a path never re-enters a table it already visited`() {
        SchemaGraph.pathsFrom(BusinessPartnerTable).forEach { path ->
            val visited = listOf(BusinessPartnerTable) + path.hops.map { it.target }
            assertEquals(
                visited.size,
                visited.toSet().size,
                "Path ${path.key} visits a table twice: ${visited.map { it.tableName }}",
            )
        }
    }

    /**
     * Pins the whole result for today's registry, which holds nineteen tables — `business_partner`,
     * `supplier`, `debtor`, `supplier_bank_account`, `invoice`, `invoice_booking`, the four
     * outgoing-invoice tables `sales_invoice`, `sales_invoice_booking`, `sales_invoice_line` and
     * `sales_invoice_vat_breakdown`, the four Stammdaten tables `product`, `product_category`,
     * `customers` and `voucher`, and the five fiscal ones `bill`, `bill_vat_detail`, `cash_book`,
     * `kassenbuch_header` and `kassenbuch_line_item`.
     *
     * **If this fails, read the diff before assuming a bug.** Registering a further table legitimately
     * adds paths, and this list has already been updated once for exactly that — `debtor` and
     * `supplier_bank_account` joined the registry and brought a path each. What the test is really
     * here to catch is the opposite: paths *reappearing* that the joinability rule should have removed
     * — the two directions of `business_partner.merged_into_partner_id`, and the two-hop routes that
     * walk back into a table already on the path.
     *
     * Cardinality is pinned alongside the key, because it is what decides whether a path is offered
     * and a silent flip is the failure this whole design exists to prevent. Three of the five are
     * to-many and are found but never offered as columns; `ExportSubject.paths` filters them out and
     * `aggregatePaths` picks them up instead:
     *
     * - `supplier_bank_account` is UNIQUE(partner_id, iban) — many IBANs per company.
     * - a creditor has many invoices.
     * - a debtor has many outgoing invoices.
     *
     * `debtor` is to-one only because the business-partner migration made `debtor.partner_id` unique. `invoice_booking` does
     * not appear because reaching it from the partner would take three hops.
     *
     * Registering the outgoing-invoice tables added **one** path, and only one: a debtor has many
     * `sales_invoice` rows (`debtor_id` carries a plain index), so the AR mirror of the two-hop AP
     * route appears and is to-many for the same reason. The other three AR tables hang off
     * `sales_invoice`, which is already two hops from the partner, so they are past [SchemaGraph.MAX_DEPTH]
     * — exactly as `invoice_booking` is on the AP side.
     *
     * Registering the Stammdaten tables added exactly one more, and it is the register-once property
     * working rather than a regression: `debtor.customer_id` points at `customers`, so the loyalty
     * record is now two hops from the partner and **to-one** — a debtor row holds one `customer_id`.
     * `product`, `product_category` and `voucher` add nothing here; nothing on a partner's routes
     * reaches them, which is why the list grew by one and not by four.
     *
     * Registering the five fiscal tables added **nothing at all**, and that is the depth limit rather
     * than luck: `bill` hangs off `sales_invoice`, which is already two hops from the partner, so it
     * and its VAT split are past [SchemaGraph.MAX_DEPTH] — the same reason `invoice_booking` never
     * appears. The Kassenbuch tables are not connected to a partner by any foreign-key route at all.
     * The list below is therefore unchanged by that slice, and this sentence is here so a reader who
     * expected a change does not go looking for a bug.
     */
    @Test
    fun `the current registry yields exactly six paths from the partner root`() {
        assertEquals(
            listOf(
                "supplier:by:partner_id" to Cardinality.ONE,
                "supplier_bank_account:by:partner_id" to Cardinality.MANY,
                "debtor:by:partner_id" to Cardinality.ONE,
                "supplier:by:partner_id.invoice:by:supplier_id" to Cardinality.MANY,
                "debtor:by:partner_id.customers:via:customer_id" to Cardinality.ONE,
                "debtor:by:partner_id.sales_invoice:by:debtor_id" to Cardinality.MANY,
            ),
            SchemaGraph.pathsFrom(BusinessPartnerTable).map { it.key to it.cardinality },
        )
    }

    /** Every table on a path must be held, not only the one the field lives on. */
    @Test
    fun `a path requires the capability of every table it crosses`() {
        val creditor = assertNotNull(SchemaGraph.pathsFrom(BusinessPartnerTable).firstOrNull { it.target == SupplierTable })
        assertTrue(creditor.requiredCapabilities.isNotEmpty())
    }
}
