package org.example.project.features.customexport

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customer.database.tables.CustomerTable
import org.example.project.features.customexport.domain.catalogue.Cardinality
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportField
import org.example.project.features.customexport.domain.catalogue.ExportFieldType
import org.example.project.features.customexport.domain.catalogue.ExportRelationPath
import org.example.project.features.customexport.domain.catalogue.ExportTableRegistry
import org.example.project.features.customexport.domain.catalogue.RelationHop
import org.example.project.features.customexport.domain.catalogue.SchemaGraph
import org.example.project.features.customexport.domain.catalogue.subjects.PartnerSubject
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.voucher.database.tables.VoucherTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three Stammdaten subjects — Produkte, Kunden, Gutscheine — and what registering their tables
 * did to the subjects that already existed.
 *
 * Every key here is a **literal**. Rebuilding one from the path key and separator the production code
 * uses would agree with whatever that code produces, including a wrong key, and these are what an
 * `export_definition_field` row stores: a wrong one breaks saved exports rather than only this run.
 */
@DisplayName("Stammdaten subjects: Produkte, Kunden, Gutscheine")
class StammdatenSubjectsTest {
    /**
     * `CatalogRules` gives `GET /api/products` `CATALOG_READ`, so the export costs the same.
     *
     * The root's own columns carry no group — a group heading is what separates a *related* table's
     * columns from the subject's own, and labelling the subject's own would put "Produkt" over the
     * product export's own price column.
     */
    @Test
    fun `the Produkte subject is offered under CATALOG_READ with its own columns ungrouped`() {
        val subject = assertNotNull(ExportCatalogue.subject("produkt"))
        assertEquals("Produkte", subject.label)
        assertEquals(Capability.CATALOG_READ, assertNotNull(ExportTableRegistry.entryFor(ProductTable)).capability)

        val own = subject.allFields.filter { it.group == null }
        assertEquals(
            listOf(
                "produkt.product_number",
                "produkt.name",
                "produkt.product_code",
                "produkt.price",
                "produkt.product_type",
                "produkt.is_active",
                "produkt.updated_at",
            ),
            own.map { it.key },
        )
        assertTrue(own.all { it.capability == Capability.CATALOG_READ })
    }

    /**
     * MONEY is a security claim — it skips the CSV formula guard and the renderer accepts only a
     * `BigDecimal` — so the type is pinned against the column rather than left to be inferred from
     * the column merely being numeric.
     */
    @Test
    fun `the product price is MONEY and the category arrives by name`() {
        val subject = assertNotNull(ExportCatalogue.subject("produkt"))
        val price = assertNotNull(subject.allFields.firstOrNull { it.key == "produkt.price" })
        assertEquals(ExportFieldType.MONEY, price.type)

        val category = assertNotNull(subject.allFields.firstOrNull { it.key == "product_category:via:category_id.name" })
        assertEquals("Warengruppe", category.group)
        assertEquals(
            emptyList(),
            subject.allFields.filter { it.key.endsWith(".category_id") },
            "the category is offered by name; a bare foreign-key id tells an operator nothing",
        )
    }

    /**
     * `product` has no `created_at` — only `updated_at` — so there is no column a Zeitraum could
     * honestly constrain. `CustomExportServiceImpl` refuses to store a date range against a subject
     * with no `dateColumn`, which is the behaviour this pins: the alternative is a range silently
     * applied to "last edited" while the operator believes it means "added".
     */
    @Test
    fun `the Produkte subject declares no date column because the table has no created_at`() {
        val subject = assertNotNull(ExportCatalogue.subject("produkt"))
        assertNull(subject.dateColumn)
        assertTrue(
            ProductTable.columns.none { it.name == "created_at" },
            "product gained a created_at; the subject should now declare it as its dateColumn",
        )
    }

    /**
     * `CUSTOMER_LIST`, not `CUSTOMER_READ`, and the difference is the whole point.
     *
     * `CustomerRules` reserves `CUSTOMER_READ` for looking one person up — till work — and gives the
     * unrestricted `GET /api/customers` `CUSTOMER_LIST`, because walking the whole base is back-office
     * work with GDPR weight. An export is enumeration by construction. Registering it under the
     * narrower capability would hand a terminal login the customer database in one CSV.
     */
    @Test
    fun `the Kunden subject costs the enumeration capability, not the lookup one`() {
        val entry = assertNotNull(ExportTableRegistry.entryFor(CustomerTable))
        assertEquals(Capability.CUSTOMER_LIST, entry.capability)

        val subject = assertNotNull(ExportCatalogue.subject("kunde"))
        assertEquals("Kunden", subject.label)
        assertEquals(CustomerTable.createdAt, subject.dateColumn)

        val visibleToTheTill =
            ExportCatalogue
                .visibleTo(setOf(Capability.CUSTOM_EXPORT_READ, Capability.CUSTOMER_READ, Capability.CUSTOMER_WRITE))
                .flatMap { it.fields }
        assertTrue(
            visibleToTheTill.none { it.key.startsWith("kunde.") },
            "holding the till's lookup capability must not offer the whole customer base: $visibleToTheTill",
        )
    }

    @Test
    fun `the Kunden subject offers its own columns ungrouped`() {
        val subject = assertNotNull(ExportCatalogue.subject("kunde"))

        assertEquals(
            listOf(
                "kunde.customer_number",
                "kunde.first_name",
                "kunde.last_name",
                "kunde.email",
                "kunde.phone",
                "kunde.marketing_consent",
                "kunde.last_purchase_at",
                "kunde.is_active",
                "kunde.created_at",
            ),
            subject.allFields.filter { it.group == null }.map { it.key },
        )
    }

    /**
     * Register once, reachable everywhere — the property the partner subject already demonstrates for
     * invoices, now shown for the loyalty record.
     *
     * `debtor.customer_id` is a foreign key into `customers`, so registering that table gave
     * **Geschäftspartner** a "Kunde" group with no line written in [PartnerSubject]. The path is
     * to-one in this direction (a debtor holds one `customer_id`), so it contributes ordinary columns
     * rather than aggregates, and seeing them costs every capability on the path — a partner export
     * reaching the loyalty record crosses `business_partner` and `debtor` to get there.
     */
    @Test
    fun `registering the customer table enriched the partner subject with a Kunde group`() {
        val path =
            assertNotNull(
                SchemaGraph
                    .pathsFrom(PartnerSubject.subject.root)
                    .firstOrNull { it.key == "debtor:by:partner_id.customers:via:customer_id" },
                "found: ${SchemaGraph.pathsFrom(PartnerSubject.subject.root).map { it.key }}",
            )
        assertEquals(Cardinality.ONE, path.cardinality)
        assertEquals("Debitor-Rolle → Kunde", path.label, "the heading names the route the loyalty record is reached by")
        assertEquals(
            setOf(Capability.CREDITOR_READ, Capability.DEBTOR_READ, Capability.CUSTOMER_LIST),
            path.requiredCapabilities,
        )

        val keys = PartnerSubject.subject.allFields.map { it.key }
        listOf(
            "debtor:by:partner_id.customers:via:customer_id.customer_number",
            "debtor:by:partner_id.customers:via:customer_id.first_name",
            "debtor:by:partner_id.customers:via:customer_id.last_name",
            "debtor:by:partner_id.customers:via:customer_id.email",
        ).forEach { key -> assertTrue(key in keys, "the partner subject does not offer '$key'; offered: $keys") }

        assertEquals(
            PartnerSubject.subject.capabilityRequirements()["debtor:by:partner_id.customers:via:customer_id.email"],
            path.requiredCapabilities,
            "a relation field's requirement is its whole path's, or a caller could read it one hop short",
        )
    }

    /**
     * `VOUCHER_REPORT`, not `VOUCHER_READ`, for the reason `VoucherRules` already writes down: the
     * till validates one code, while `/statistics` and the two by-date rollups are shop-wide reporting
     * a terminal has no business reading. An export of every voucher with its remaining value is that
     * reporting, in a file.
     */
    @Test
    fun `the Gutscheine subject costs the reporting capability, not the till's`() {
        val entry = assertNotNull(ExportTableRegistry.entryFor(VoucherTable))
        assertEquals(Capability.VOUCHER_REPORT, entry.capability)

        val subject = assertNotNull(ExportCatalogue.subject("gutschein"))
        assertEquals("Gutscheine", subject.label)
        assertEquals(VoucherTable.createdAt, subject.dateColumn)
        assertEquals(
            listOf(
                "gutschein.voucher_number",
                "gutschein.status",
                "gutschein.original_value",
                "gutschein.remaining_value",
                "gutschein.created_at",
                "gutschein.fully_redeemed_at",
                "gutschein.invalidated_at",
            ),
            subject.allFields.filter { it.group == null }.map { it.key },
        )

        val visibleToTheTill =
            ExportCatalogue
                .visibleTo(setOf(Capability.CUSTOM_EXPORT_READ, Capability.VOUCHER_READ, Capability.VOUCHER_WRITE))
                .flatMap { it.fields }
        assertTrue(
            visibleToTheTill.none { it.key.startsWith("gutschein.") },
            "the till's voucher capability must not open shop-wide voucher reporting: $visibleToTheTill",
        )
    }

    /** Both voucher amounts are `decimal(10,2)`, so MONEY is a claim the schema actually supports. */
    @Test
    fun `both voucher values are MONEY`() {
        val subject = assertNotNull(ExportCatalogue.subject("gutschein"))
        val money = subject.allFields.filter { it.type == ExportFieldType.MONEY }.map { it.key }
        assertEquals(listOf("gutschein.original_value", "gutschein.remaining_value"), money)
    }

    /**
     * The period column is the **issue** date, and the aggregates twin because of it.
     *
     * `fully_redeemed_at` and `invalidated_at` are null until something happens to the row, so
     * scoping by either would drop every voucher still in circulation — exactly the population an
     * outstanding-liability figure is about. `created_at` is the only date every voucher has.
     *
     * `sum` reduces the **remaining** value: what is left on the cards is the debt. Summing
     * `original_value` would report a liability already partly settled as still open.
     */
    @Test
    fun `the voucher aggregates sum what is left and are scoped by the issue date`() {
        val entry = assertNotNull(ExportTableRegistry.entryFor(VoucherTable))

        assertEquals(VoucherTable.createdAt, entry.periodColumn)
        assertEquals(listOf("count", "sum", "first", "last"), entry.aggregates.map { it.key })

        val sum = assertNotNull(entry.aggregates.firstOrNull { it.key == "sum" })
        assertEquals(VoucherTable.remainingValue, sum.column)
        assertEquals(ExportFieldType.MONEY, sum.type)
    }

    /**
     * The twins, on a **hand-built** to-many path.
     *
     * Nothing registered points at `voucher` today — its own foreign keys lead to `order_item` and
     * `zbon`, neither of them registered — so no subject reaches the table as a to-many relation and
     * the twinning could not otherwise be observed at all. The path below is the one a future
     * registration would produce: `order_item → voucher`, followed backwards, which is to-many because
     * `voucher.order_item_id` carries no unique index of its own.
     *
     * Keys are literals, as everywhere else here, because they are what a definition would store.
     */
    @Test
    fun `each voucher aggregate is twinned into a Zeitraum and a gesamt field`() {
        val asToMany =
            ExportRelationPath(
                key = "voucher:by:order_item_id",
                label = "Gutschein",
                hops =
                    listOf(
                        RelationHop(
                            target = VoucherTable,
                            from = OrderItemTable.id,
                            to = VoucherTable.orderItem,
                        ),
                    ),
                cardinality = Cardinality.MANY,
                fields = emptyList(),
                requiredCapabilities = setOf(Capability.VOUCHER_REPORT),
            )

        assertEquals(
            listOf(
                "voucher:by:order_item_id~count@period",
                "voucher:by:order_item_id~count",
                "voucher:by:order_item_id~sum@period",
                "voucher:by:order_item_id~sum",
                "voucher:by:order_item_id~first@period",
                "voucher:by:order_item_id~first",
                "voucher:by:order_item_id~last@period",
                "voucher:by:order_item_id~last",
            ),
            asToMany.aggregateFields.map { it.key },
        )
        assertEquals(
            listOf(
                "Anzahl Gutscheine (Zeitraum)",
                "Anzahl Gutscheine (gesamt)",
                "Offener Gutscheinwert (Zeitraum)",
                "Offener Gutscheinwert (gesamt)",
                "Erster Gutschein (Zeitraum)",
                "Erster Gutschein (gesamt)",
                "Letzter Gutschein (Zeitraum)",
                "Letzter Gutschein (gesamt)",
            ),
            asToMany.aggregateFields.map { it.label },
        )
        assertEquals(listOf(true, false), asToMany.aggregateFields.filter { it.key.contains("~sum") }.map { it.periodScoped })
    }

    /**
     * A customer's outgoing invoices are reached only as **aggregates**, never as columns.
     *
     * `debtor.customer_id` carries a plain index, so one loyalty record may back several debtor roles
     * and the relation is to-many in this direction. Joining it would emit one customer once per
     * invoice with every other cell duplicated and nothing in the file to say the row count is not a
     * customer count — which is why the same table that contributes columns to the *partner* subject
     * contributes only a revenue figure here.
     */
    @Test
    fun `a customer's outgoing invoices arrive as aggregates and never as columns`() {
        val subject = assertNotNull(ExportCatalogue.subject("kunde"))

        assertTrue(
            subject.allOfferings.filterIsInstance<ExportField>().none { it.key.startsWith("debtor:by:customer_id") },
            "a to-many path may contribute aggregates only",
        )
        val keys = subject.allAggregates.map { it.key }
        listOf(
            "debtor:by:customer_id.sales_invoice:by:debtor_id~sum@period",
            "debtor:by:customer_id.sales_invoice:by:debtor_id~sum",
            "debtor:by:customer_id.sales_invoice:by:debtor_id~count@period",
            "debtor:by:customer_id.sales_invoice:by:debtor_id~count",
        ).forEach { key -> assertTrue(key in keys, "the Kunden subject does not offer '$key'; offered: $keys") }
    }
}
