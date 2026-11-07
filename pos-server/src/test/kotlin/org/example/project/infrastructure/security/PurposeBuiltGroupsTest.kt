package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.auth.Assignability
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage 3's three purpose-built groups, and the properties that are the point of each.
 *
 * These groups ship as **rows**: no route rule changed, no authorization code changed. That is the
 * claim the capability model has been making since Stage 1, and this is where it either holds or it
 * does not — under the old design each of these shapes would have needed a new `EmployeeRole`, which
 * is on the wire and in the JWT and therefore a client release.
 */
@DisplayName("Stage 3 purpose-built groups")
class PurposeBuiltGroupsTest {
    private fun group(code: String): Set<Capability> = CapabilityBundles.forGroup(code) ?: error("no seeded group with code '$code'")

    /**
     * Rules gated by [capability], across every module.
     *
     * Derived from the live registry rather than restated, which is the entire value of the
     * read-only assertion below: a hand-curated list of "auditor-safe" capabilities rots the moment
     * someone puts a write route behind one of them, and rots silently.
     */
    private fun rulesGatedBy(capability: Capability): List<Pair<String, HttpMethod>> =
        RoutePermissions.modules
            .flatMap { module -> module.rules.entries }
            .filter { (_, level) -> level is AccessLevel.Cap && level.capability == capability }
            .map { (key, _) -> key }

    @Test
    fun `AUDITOR can reach nothing but GETs`() {
        // Read-shaped POSTs, each exempted individually and only after reading the handler — the
        // same mechanism ReadWriteSanityTest uses. A blanket "POSTs are fine" would make this test
        // worthless; the value is that adding a genuinely mutating route forces a decision here.
        val exemptions =
            setOf(
                // Credentials in the body, so it cannot be a GET. Looks nothing up but the caller.
                "/api/employees/authenticate" to HttpMethod.Post,
                // Same shape: reads the caller's own time entries, authenticating from the body.
                "/api/timetracking/my-entries-auth" to HttpMethod.Post,
                // Reads a date range out of the body and streams a zip back. Checked the handler:
                // `generateExport` returns bytes and persists nothing. This is the single route an
                // auditor account exists to call, so exempting it is not a concession.
                "/api/v1/dsfinvk/export" to HttpMethod.Post,
                // POST only to keep the voucher code out of the URL; already exempted for the same
                // reason in ReadWriteSanityTest.
                "/api/vouchers/validate" to HttpMethod.Post,
                // Re-printing an existing Z-Bon. Checked the handler: it reads the report and hands
                // it to the printer — no fiscal record is created or altered. What it does write is a
                // print_job_history row, which logs the ACTION rather than recording business, and an
                // auditor asking for a paper copy of a daily close is precisely what an audit is.
                //
                // Reachable only because V113 gave the audit groups ZBON_MANAGE so they could open a
                // Z-Bon at all; ZBON_MANAGE gates the detail, history and PDF reads alongside it.
                "/api/z-bon/{id}/print" to HttpMethod.Post,
            )

        val writes =
            group("AUDITOR")
                .flatMap { capability -> rulesGatedBy(capability).map { capability to it } }
                .filter { (_, rule) -> rule.second != HttpMethod.Get && rule !in exemptions }

        assertTrue(
            writes.isEmpty(),
            buildString {
                appendLine("AUDITOR is meant to be provably read-only, but reaches ${writes.size} non-GET route(s):")
                writes.sortedBy { it.second.first }.forEach { (capability, rule) ->
                    appendLine("  ${rule.second.value} ${rule.first}  via $capability")
                }
                appendLine("Either drop the capability from the AUDITOR bundle, or split it the way")
                appendLine("Stage 2b split VOUCHER_READ, so the auditor keeps the read half.")
            },
        )
    }

    @Test
    fun `AUDITOR cannot change even its own credentials`() {
        // Deliberate, and the reason it is asserted rather than left implicit: SELF_WRITE is the one
        // write it would be natural to grant out of politeness, and granting it would end the
        // "writes nothing at all" property that makes the group worth having.
        assertFalse(Capability.SELF_WRITE in group("AUDITOR"))
        assertTrue(Capability.SELF_READ in group("AUDITOR"))
    }

    @Test
    fun `AUDITOR keeps the one export an audit is actually for`() {
        assertTrue(
            Capability.DSFINVK_EXPORT in group("AUDITOR"),
            "an auditor who cannot run the DSFinV-K export has no reason to have an account",
        )
    }

    @Test
    fun `ACCOUNTANT holds no RESERVED capability`() {
        // Fiscal finality is the operator's button, not the bookkeeper's: ZBON_CLOSE, MONTHLY_CLOSE,
        // CASHBOOK_CLOSE and DOCUMENT_DELETE are all RESERVED. Asserted against the enum rather than
        // listed by name so a capability marked RESERVED later is caught without editing this test.
        val reserved = group("ACCOUNTANT").filter { it.assignability == Assignability.RESERVED }
        assertEquals(emptyList(), reserved, "ACCOUNTANT must hold no RESERVED capability")
    }

    @Test
    fun `ACCOUNTANT extends AUDITOR rather than diverging from it`() {
        assertTrue(
            group("ACCOUNTANT").containsAll(group("AUDITOR")),
            "an accountant should see everything an auditor sees: ${group("AUDITOR") - group("ACCOUNTANT")}",
        )
        assertTrue(
            Capability.INVOICE_WRITE in group("ACCOUNTANT") && Capability.INVOICE_WRITE !in group("AUDITOR"),
            "the difference between the two groups is the booking writes",
        )
    }

    @Test
    fun `ACCOUNTANT does not run the shop`() {
        listOf(Capability.EMPLOYEE_WRITE, Capability.CATALOG_MANAGE, Capability.CONFIG_WRITE)
            .forEach { assertFalse(it in group("ACCOUNTANT"), "$it is operator work, not bookkeeping") }
    }

    @Test
    fun `ORDER_ONLY is a strict subset of the unattended terminal`() {
        val orderOnly = group("ORDER_ONLY")
        val terminal = CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE)

        assertTrue(
            terminal.containsAll(orderOnly),
            "ORDER_ONLY reaches past the unattended device: ${orderOnly - terminal}",
        )
        assertTrue(orderOnly.size < terminal.size, "ORDER_ONLY that equals TERMINAL_DEVICE restricts nothing")
    }

    @Test
    fun `ORDER_ONLY can take an order and do nothing else`() {
        val held = group("ORDER_ONLY")

        // What order-taking actually touches. Tables because orders are taken against a session,
        // customers because that flow attaches one, printers because the order goes to the kitchen,
        // promotions because they change what the line costs.
        listOf(
            Capability.CATALOG_READ,
            Capability.CUSTOMER_READ,
            Capability.CUSTOMER_WRITE,
            Capability.ORDER_READ,
            Capability.ORDER_WRITE,
            Capability.PRINTER_WRITE,
            Capability.PROMOTION_READ,
            Capability.TABLE_READ,
            Capability.TABLE_WRITE,
        ).forEach { assertTrue(it in held, "$it is needed to take an order") }

        // The original request in one assertion: no access at all beyond orders.
        listOf(
            Capability.BILL_READ,
            Capability.BILL_WRITE,
            Capability.CARD_TERMINAL_READ,
            Capability.PAYROLL_READ,
            Capability.REPORT_READ,
            Capability.RESERVATION_READ,
            Capability.VOUCHER_READ,
            Capability.ZBON_READ,
        ).forEach { assertFalse(it in held, "$it goes beyond taking and viewing orders") }
    }

    @Test
    fun `the price and discount authorities are held by exactly the people who held them before`() {
        // The swap from `role.isManagerOrHigher()` to capabilities must not narrow anything, so the
        // two capabilities belong to MANAGER and ADMIN and to nobody else. If a later stage widens
        // this deliberately it should edit the assertion, not discover the change in the field.
        val holders =
            CapabilityBundles
                .definitions()
                .filter { Capability.PRICE_OVERRIDE in it.capabilities || Capability.DISCOUNT_GRANT in it.capabilities }
                .map { it.code }
                .toSet()

        assertEquals(setOf("MANAGER", "ADMIN"), holders)

        // Separable, which is the whole point of splitting one flag into two capabilities: a future
        // group can hold one without the other. Nothing asserts they must always travel together.
        assertTrue(Capability.PRICE_OVERRIDE in CapabilityBundles.forRole(EmployeeRole.MANAGER))
        assertTrue(Capability.DISCOUNT_GRANT in CapabilityBundles.forRole(EmployeeRole.MANAGER))
    }

    @Test
    fun `every group definition has usable display metadata`() {
        // Seeded straight into permission_group and shown in the Stage 6 editor. A blank name would
        // surface as an unnamed row in a picker and nothing else would catch it.
        CapabilityBundles.definitions().forEach { definition ->
            assertTrue(definition.name.isNotBlank(), "${definition.code} has no display name")
            assertTrue(definition.description.isNotBlank(), "${definition.code} has no description")
            assertTrue(
                definition.description.length <= 500,
                "${definition.code}'s description exceeds the column width (500)",
            )
            assertTrue(definition.capabilities.isNotEmpty(), "${definition.code} confers nothing")
        }
    }

    @Test
    fun `group codes are unique`() {
        val codes = CapabilityBundles.definitions().map { it.code }
        assertEquals(codes.size, codes.toSet().size, "duplicate group code: the seed would collide on the unique index")
    }
}
