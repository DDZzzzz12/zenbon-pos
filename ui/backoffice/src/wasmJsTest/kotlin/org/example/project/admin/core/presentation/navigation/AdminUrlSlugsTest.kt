package org.example.project.admin.core.presentation.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminUrlSlugsTest {
    private val p = AdminUrlSlugs.ROUTE_PACKAGE

    @Test
    fun `an argument-free route is its kebab-case name`() {
        assertEquals("dashboard", AdminUrlSlugs.slugOf("${p}DashboardRoute", emptyMap()))
        assertEquals("z-bon-list", AdminUrlSlugs.slugOf("${p}ZBonListRoute", emptyMap()))
    }

    @Test
    fun `a path argument follows the name`() {
        assertEquals(
            "document-detail/42",
            AdminUrlSlugs.slugOf("${p}DocumentDetailRoute/{documentId}", mapOf("documentId" to "42")),
        )
    }

    @Test
    fun `query arguments appear only when given`() {
        val pattern = "${p}KassenbuchRoute?headerId={headerId}&manageBooks={manageBooks}"
        assertEquals("kassenbuch", AdminUrlSlugs.slugOf(pattern, emptyMap()))
        assertEquals("kassenbuch?headerId=7", AdminUrlSlugs.slugOf(pattern, mapOf("headerId" to "7")))
        assertEquals(
            "kassenbuch?headerId=7&manageBooks=true",
            AdminUrlSlugs.slugOf(pattern, mapOf("headerId" to "7", "manageBooks" to "true")),
        )
    }

    @Test
    fun `a missing path argument or a foreign route has no slug`() {
        assertNull(AdminUrlSlugs.slugOf("${p}DocumentDetailRoute/{documentId}", emptyMap()))
        assertNull(AdminUrlSlugs.slugOf("some.other.Thing", emptyMap()))
        assertNull(AdminUrlSlugs.slugOf("${p}NotARouteName", emptyMap()))
    }

    @Test
    fun `a slug maps back to the route string`() {
        assertEquals("${p}DashboardRoute", AdminUrlSlugs.toRoute("#dashboard"))
        assertEquals("${p}ZBonListRoute", AdminUrlSlugs.toRoute("z-bon-list"))
        assertEquals("${p}DocumentDetailRoute/42", AdminUrlSlugs.toRoute("#document-detail/42"))
        assertEquals("${p}SettingsRoute?section=PRINTERS", AdminUrlSlugs.toRoute("#settings?section=PRINTERS"))
    }

    @Test
    fun `every route name survives the round trip`() {
        val names =
            listOf(
                "Dashboard", "Products", "Tables", "FloorPlanEditor", "Staff", "TseAdmin", "TseFindings", "Terminals",
                "PermissionGroups", "ScheduledJobs", "Update", "Analytics", "DailyReport", "ZBonList", "AuditLog",
                "Settings", "Kassenbuch", "ZBonDetail", "FirstTerminalSetup", "Vouchers", "Printer", "Kassenmeldung",
                "DataExport", "Documents", "DocumentDetail", "Suppliers", "BankAccounts", "SupplierDetail",
                "InvoiceCapture", "InvoiceEdit", "Invoices", "SalesInvoices", "SalesInvoiceEditor", "SalesInvoiceEdit",
                "Debtors", "DebtorDetail", "DebtorCreate", "Allocations", "Contracts", "ContractDetail", "CustomExport",
                "ScannerDev",
            )
        for (name in names) {
            val route = "$p${name}Route"
            val slug = AdminUrlSlugs.slugOf(route, emptyMap())!!
            assertEquals(route, AdminUrlSlugs.toRoute(slug), "round trip of $name via '$slug'")
        }
    }

    @Test
    fun `the old long fragment is still accepted`() {
        val old = "${p}DocumentDetailRoute/1"
        assertEquals(old, AdminUrlSlugs.toRoute("#$old"))
    }

    @Test
    fun `garbage is not a route`() {
        assertNull(AdminUrlSlugs.toRoute(""))
        assertNull(AdminUrlSlugs.toRoute("#"))
        assertNull(AdminUrlSlugs.toRoute("#Has Spaces"))
        assertNull(AdminUrlSlugs.toRoute("#../x"))
    }
}
