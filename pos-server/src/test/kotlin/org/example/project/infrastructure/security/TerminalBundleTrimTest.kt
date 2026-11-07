package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage 2b: the enumerated behaviour change that closes defect D4.
 *
 * Stage 1 was equivalence-preserving by construction, so [CapabilityEquivalenceTest] could demand
 * zero diffs and treat any diff as a bug. Stage 2b is the opposite: the diffs *are* the deliverable.
 * This class owns the exact list, and the equivalence test asserts set **equality** against it —
 * not containment. That matters. An allowlist only catches diffs you did not intend; an equality
 * assertion also catches an intended diff that silently stopped happening, which is the shape a
 * regression here would take (someone puts `REPORT_READ` back in the terminal bundle and the hole
 * quietly reopens).
 *
 * Every entry is `TERMINAL_DEVICE` and every entry is `true -> false`. If a future edit to this
 * file adds a row for another role, or a `false -> true` row, that is a widening and needs its own
 * review — the shape assertions below refuse it outright.
 */
@DisplayName("Stage 2b terminal-bundle trim")
class TerminalBundleTrimTest {
    @Test
    fun `the trim removes exactly the routes it set out to remove, and no others`() {
        assertEquals(
            28,
            EXPECTED_DIFFS.size,
            "the expected-diff list changed size — a trim is a reviewed behaviour change, not an edit",
        )
        EXPECTED_DIFFS.forEach { diff ->
            assertTrue(
                diff.contains("role=TERMINAL_DEVICE"),
                "Stage 2b narrows the unattended device only; '$diff' touches another role",
            )
            assertTrue(
                diff.endsWith("legacy=true current=false"),
                "Stage 2b only ever removes reach; '$diff' grants something",
            )
        }
    }

    @Test
    fun `the till keeps everything the POS flows actually call`() {
        val terminal = CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE)

        // Checked against the composeApp call sites, not assumed. CUSTOMER_WRITE in particular:
        // TableOperationsViewModel's CustomerPanelEvent.CreateCustomer runs inside the table-session
        // flow a terminal reaches, so trimming it would have broken a live till.
        listOf(
            Capability.BILL_READ,
            Capability.CARD_TERMINAL_READ,
            Capability.CATALOG_READ,
            Capability.CUSTOMER_READ,
            Capability.CUSTOMER_WRITE,
            Capability.ORDER_READ,
            Capability.ORDER_WRITE,
            Capability.PAYROLL_READ,
            Capability.PRINTER_WRITE,
            Capability.TABLE_READ,
            Capability.TABLE_WRITE,
            Capability.VOUCHER_READ,
            Capability.VOUCHER_WRITE,
            Capability.ZBON_READ,
        ).forEach { assertTrue(it in terminal, "$it is till work and must stay in TERMINAL_DEVICE") }
    }

    @Test
    fun `the four capabilities the trim removes are gone from the device and kept by every human`() {
        val terminal = CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE)
        val trimmed =
            listOf(
                Capability.CUSTOMER_LIST,
                Capability.DOCUMENT_READ,
                Capability.REPORT_READ,
                Capability.VOUCHER_REPORT,
            )

        trimmed.forEach { assertFalse(it in terminal, "$it must not be reachable from an unattended device") }

        // Moved up rather than deleted. Stage 2b kept a cashier's reach byte-identical; V130 (D1) then
        // trimmed DOCUMENT_READ and VOUCHER_REPORT from CASHIER, which no till flow calls, so those
        // two now start at MANAGER. CUSTOMER_LIST and REPORT_READ stay with the cashier.
        listOf(EmployeeRole.CASHIER, EmployeeRole.MANAGER, EmployeeRole.ADMIN).forEach { role ->
            val bundle = CapabilityBundles.forRole(role)
            listOf(Capability.CUSTOMER_LIST, Capability.REPORT_READ).forEach {
                assertTrue(it in bundle, "$role lost $it — the trim was meant to spare humans")
            }
        }
        listOf(EmployeeRole.MANAGER, EmployeeRole.ADMIN).forEach { role ->
            val bundle = CapabilityBundles.forRole(role)
            listOf(Capability.DOCUMENT_READ, Capability.VOUCHER_REPORT).forEach {
                assertTrue(it in bundle, "$role lost $it — the cashier trim must not reach the back office")
            }
        }
    }

    @Test
    fun `the cashier holds neither document reading nor voucher reporting`() {
        val cashier = CapabilityBundles.forRole(EmployeeRole.CASHIER)
        assertFalse(Capability.DOCUMENT_READ in cashier, "D1: documents are back-office")
        assertFalse(Capability.VOUCHER_REPORT in cashier, "D1: shop-wide voucher reporting is back-office")
        // The till's own voucher work survives, and the terminal is a subset of the cashier.
        assertTrue(Capability.VOUCHER_READ in cashier && Capability.VOUCHER_WRITE in cashier)
        assertTrue(cashier.containsAll(CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE)))
    }

    @Test
    fun `the split capabilities separate till lookup from back-office reporting`() {
        fun capOf(
            path: String,
            method: HttpMethod,
        ) = (RoutePermissions.findRule(path, method) as AccessLevel.Cap).capability

        // Vouchers: validate and one voucher's history are till work; the rollups are not.
        assertEquals(Capability.VOUCHER_READ, capOf("/api/vouchers/validate", HttpMethod.Post))
        assertEquals(
            Capability.VOUCHER_READ,
            capOf("/api/vouchers/number/ABC/redemption-history", HttpMethod.Get),
        )
        listOf("/api/vouchers/statistics", "/api/vouchers/created-by-date", "/api/vouchers/redeemed-by-date")
            .forEach { assertEquals(Capability.VOUCHER_REPORT, capOf(it, HttpMethod.Get), it) }

        // Customers: looking one up is till work, enumerating the base is not.
        assertEquals(Capability.CUSTOMER_READ, capOf("/api/customers/search", HttpMethod.Get))
        assertEquals(Capability.CUSTOMER_READ, capOf("/api/customers/1", HttpMethod.Get))
        assertEquals(Capability.CUSTOMER_WRITE, capOf("/api/customers", HttpMethod.Post))
        assertEquals(Capability.CUSTOMER_LIST, capOf("/api/customers", HttpMethod.Get))
        assertEquals(Capability.CUSTOMER_LIST, capOf("/api/customer-addresses", HttpMethod.Get))
        assertEquals(
            Capability.CUSTOMER_READ,
            capOf("/api/customer-addresses/customer/1", HttpMethod.Get),
            "the till reads one customer's addresses when attaching them to a table session",
        )
    }

    companion object {
        /**
         * Every verdict Stage 2b changes, as `CapabilityEquivalenceTest` formats them.
         *
         * Generated by running that test against the trimmed bundle and pasting its failure output —
         * derived from the code, not hand-predicted. An earlier hand-prediction of this list was
         * wrong in five of seven entries, which is why it is captured this way.
         *
         * `/api/documents/export/datev-ap` is worth singling out: before this trim, an unattended
         * terminal could export the DATEV accounts-payable ledger.
         */
        val EXPECTED_DIFFS: Set<String> =
            setOf(
                // CUSTOMER_LIST — enumerating the customer base.
                "GET /api/customer-addresses role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/customers role=TERMINAL_DEVICE legacy=true current=false",
                // DOCUMENT_READ — the whole document management screen, incl. the DATEV AP export.
                "GET /api/documents role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/documents/1 role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/documents/1/file role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/documents/1/invoice role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/documents/1/linked role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/documents/1/metadata role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/documents/document-types role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/documents/export/datev-ap role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/documents/export/datev-ap/statistics role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/documents/statistics role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/documents/unlinked role=TERMINAL_DEVICE legacy=true current=false",
                // REPORT_READ — shop-wide sales, financial and catalog statistics.
                "GET /api/statistics/catalog role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/statistics/financial role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/statistics/promotions role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/statistics/sales/bills role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/statistics/sales/by-employee role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/statistics/sales/by-table role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/statistics/sales/by-table/1/sessions role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/statistics/sales/canceled-items role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/statistics/sales/overview role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/statistics/sales/payments role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/statistics/sales/sessions role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/statistics/sales/trends role=TERMINAL_DEVICE legacy=true current=false",
                // VOUCHER_REPORT — shop-wide voucher reporting.
                "GET /api/vouchers/created-by-date role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/vouchers/redeemed-by-date role=TERMINAL_DEVICE legacy=true current=false",
                "GET /api/vouchers/statistics role=TERMINAL_DEVICE legacy=true current=false",
            )
    }
}
