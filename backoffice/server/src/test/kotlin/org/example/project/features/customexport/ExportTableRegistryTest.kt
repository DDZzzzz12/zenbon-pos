package org.example.project.features.customexport

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.auth.database.tables.RefreshTokenTable
import org.example.project.features.customexport.domain.catalogue.ExportTableRegistry
import org.example.project.features.customexport.domain.catalogue.ForbiddenExportTargets
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Export table registry")
class ExportTableRegistryTest {
    @Test
    fun `a registered table resolves with its label and capability`() {
        val entry = assertNotNull(ExportTableRegistry.entryFor(SupplierTable))
        assertEquals("Kreditor-Rolle", entry.label)
        assertEquals(Capability.CREDITOR_READ, entry.capability)
        assertTrue(entry.columns.any { it.column == SupplierTable.creditorNo })
    }

    /**
     * Default-deny. An unregistered table contributes nothing — this is what stops a table added
     * next month from becoming exportable because nobody remembered a denylist.
     */
    @Test
    fun `an unregistered table does not resolve`() {
        assertNull(ExportTableRegistry.entryFor(RefreshTokenTable))
    }

    @Test
    fun `a registered column carries a german label`() {
        val entry = assertNotNull(ExportTableRegistry.entryFor(BusinessPartnerTable))
        val vatId = assertNotNull(entry.columns.firstOrNull { it.column == BusinessPartnerTable.vatId })
        assertEquals("USt-IdNr.", vatId.label)
    }

    /**
     * The allowlist and the denylist must never both claim a table: two controls disagreeing about
     * one table is worse than either alone, because which wins depends on call order.
     */
    @Test
    fun `no table is both registered and denied`() {
        val overlap =
            ExportTableRegistry.entries
                .map { it.table.tableName.lowercase() }
                .filter { it in ForbiddenExportTargets.TABLES }
        assertTrue(overlap.isEmpty(), "Registered and denied: $overlap")
    }

    /**
     * A registered column may not be a denied column of an otherwise-allowed table.
     *
     * Goes through [ForbiddenExportTargets.deniesColumn] rather than [ForbiddenExportTargets.COLUMNS]
     * so the prefix rule applies here too — a check honouring half the denylist would report a clean
     * registry while a `sales_invoice.tse_*` column sat in it.
     */
    @Test
    fun `no registered column is denied`() {
        val violations =
            ExportTableRegistry.entries.flatMap { entry ->
                entry.columns
                    .map { "${it.column.table.tableName.lowercase()}.${it.column.name.lowercase()}" }
                    .filter { ForbiddenExportTargets.deniesColumn(it) }
            }
        assertTrue(violations.isEmpty(), "Registered denied columns: $violations")
    }

    /**
     * The outgoing invoice is gated by INVOICE_READ, which is what `GET /api/v1/sales-invoices`
     * requires — an export must never be an easier way to read data than the screen showing it.
     *
     * Pinned rather than left implicit because the neighbouring choice is wrong and plausible:
     * `DEBTOR_READ` gates the debtor *role*, and a party's bookkeeping role is not the invoices we
     * issued them. A silent swap to it would widen who can export revenue without changing a
     * single label.
     */
    @Test
    fun `the outgoing invoice resolves under INVOICE_READ`() {
        val entry = assertNotNull(ExportTableRegistry.entryFor(SalesInvoiceTable))
        assertEquals("Ausgangsrechnung", entry.label)
        assertEquals(Capability.INVOICE_READ, entry.capability)
        assertTrue(entry.columns.any { it.column == SalesInvoiceTable.invoiceNumber })
        assertEquals(SalesInvoiceTable.issueDate, entry.periodColumn)
    }

    /**
     * Not one registered column of `sales_invoice` is TSE signature material.
     *
     * `no registered column is denied` above already covers this through
     * [ForbiddenExportTargets.deniesColumn], but only as long as the prefix stays on the denylist.
     * This states the rule directly against the table that carries the block, so deleting the prefix
     * costs two failures rather than one — and so the reason is written next to the table it is
     * about: the columns are a §6 KassenSichV Beleg's signature, and a CSV holding them would
     * resemble fiscal evidence without being it.
     */
    @Test
    fun `no registered column of the outgoing invoice is TSE signature material`() {
        val entry = assertNotNull(ExportTableRegistry.entryFor(SalesInvoiceTable))
        val tse = entry.columns.map { it.column.name.lowercase() }.filter { it.startsWith("tse_") }
        assertTrue(tse.isEmpty(), "TSE columns registered on sales_invoice: $tse")
    }

    /** Two columns of one table may not claim the same key, or a stored definition is ambiguous. */
    @Test
    fun `column keys are unique within a table`() {
        ExportTableRegistry.entries.forEach { entry ->
            val keys = entry.columns.map { it.column.name }
            assertEquals(keys.size, keys.toSet().size, "Duplicate column in ${entry.table.tableName}: $keys")
        }
    }
}
