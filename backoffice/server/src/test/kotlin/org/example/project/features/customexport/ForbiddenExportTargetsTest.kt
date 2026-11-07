package org.example.project.features.customexport

import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ForbiddenExportTargets
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.terminal.database.tables.TerminalTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * No declared field may reach denied data.
 *
 * This walks the real catalogue, so it fails when someone *adds* a field into forbidden territory —
 * which is the whole point. A comment saying "do not export credentials" does not fail a build.
 */
@DisplayName("Forbidden export targets")
class ForbiddenExportTargetsTest {
    @Test
    fun `no catalogue field reaches a denied table or column`() {
        val violations =
            ExportCatalogue.subjects.flatMap { subject ->
                subject.allFields.mapNotNull { field ->
                    val table =
                        field.column.table.tableName
                            .lowercase()
                    val qualified = "$table.${field.column.name.lowercase()}"
                    when {
                        table in ForbiddenExportTargets.TABLES ->
                            "${subject.key}/${field.key} reads denied table '$table'"
                        ForbiddenExportTargets.deniesColumn(qualified) ->
                            "${subject.key}/${field.key} reads denied column '$qualified'"
                        else -> null
                    }
                }
            }

        if (violations.isNotEmpty()) {
            fail("Denied data is reachable through the export catalogue:\n" + violations.joinToString("\n"))
        }
    }

    /**
     * Proves the check can actually fail.
     *
     * A guard that has never rejected anything is indistinguishable from a guard that cannot. This
     * asserts the matching logic against a known-denied name rather than trusting that the catalogue
     * happening to be clean means the test works.
     */
    @Test
    fun `the denylist matches a known credential column`() {
        assertTrue("employees.password_hash" in ForbiddenExportTargets.COLUMNS)
        assertTrue(ForbiddenExportTargets.deniesColumn("employees.password_hash"))
        assertTrue("refresh_tokens" in ForbiddenExportTargets.TABLES)
    }

    /**
     * The prefix equivalent of the test above, and for the same reason.
     *
     * A prefix rule that matches nothing is the failure mode worth guarding: it looks like a control,
     * reports clean, and protects nothing. So assert it against two real TSE columns of
     * `sales_invoice` — neither of which is enumerated in [ForbiddenExportTargets.COLUMNS], so only
     * the prefix leg can be what denies them — and against a column of that same table which must
     * stay exportable, or a prefix that was accidentally too broad would also pass.
     */
    @Test
    fun `the denylist matches fiscal signature columns by prefix`() {
        assertTrue(ForbiddenExportTargets.deniesColumn("sales_invoice.tse_signature"))
        assertTrue(ForbiddenExportTargets.deniesColumn("sales_invoice.tse_qr_code_payload"))
        assertTrue("sales_invoice.tse_signature" !in ForbiddenExportTargets.COLUMNS)
        assertFalse(ForbiddenExportTargets.deniesColumn("sales_invoice.invoice_number"))
    }

    /**
     * The prefix must be the name Exposed actually reports, exactly as the enumerated entries must.
     *
     * A prefix spelled the Kotlin way (`sales_invoice.tse`) or against the wrong table would never
     * match, so resolve two real TSE columns through the same accessors the walk uses and assert the
     * denial fires on the resolved strings. This is also what will catch a *thirteenth* TSE column:
     * the loop below denies every one the table declares, so a future one is covered by construction
     * rather than by somebody remembering.
     */
    @Test
    fun `every declared TSE column of sales_invoice is denied by the prefix`() {
        val tseColumns =
            SalesInvoiceTable.columns
                .map { "${it.table.tableName.lowercase()}.${it.name.lowercase()}" }
                .filter { it.substringAfter('.').startsWith("tse_") }

        assertTrue(tseColumns.isNotEmpty(), "sales_invoice declares no tse_ column; has the table moved?")
        assertTrue("sales_invoice.tse_signature" in tseColumns)
        assertTrue("sales_invoice.tse_qr_code_payload" in tseColumns)
        tseColumns.forEach { assertTrue(ForbiddenExportTargets.deniesColumn(it), "not denied: $it") }
    }

    /**
     * The denylist strings must be the names Exposed actually reports.
     *
     * The check above compares `tableName` and `column.name`; if a denied entry were spelled the
     * Kotlin way (`passwordHash`) or with the wrong table (`terminal` for `terminals`) it would never
     * match anything and the guard would pass forever while protecting nothing. So resolve two real
     * denied columns through the same accessors the check uses, and assert the strings agree.
     */
    @Test
    fun `denied column names are the ones Exposed reports`() {
        assertEquals(
            "employees.password_hash",
            "${EmployeeTable.passwordHash.table.tableName}.${EmployeeTable.passwordHash.name}",
        )
        assertEquals(
            "terminals.device_token_hash",
            "${TerminalTable.deviceTokenHash.table.tableName}.${TerminalTable.deviceTokenHash.name}",
        )
        assertTrue(
            ForbiddenExportTargets.COLUMNS.containsAll(
                setOf("employees.password_hash", "terminals.device_token_hash"),
            ),
        )
        assertEquals(
            "sales_invoice.tse_signature",
            "${SalesInvoiceTable.tseSignature.table.tableName}.${SalesInvoiceTable.tseSignature.name}",
        )
    }
}
