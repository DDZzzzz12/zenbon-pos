package org.example.project.admin.core.presentation.navigation

import org.example.project.admin.core.data.LocalStorageKeyValueStore
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What each principal sees in the sidebar.
 *
 * Every destination now names its OWN capability rather than sharing a `canManageProducts` gate that
 * really meant "is a manager". A single shared gate cannot express a bookkeeper who should see
 * invoices, debtors and contracts but not the catalog or the terminals — and the sidebar is where an
 * operator first notices whether the model works.
 *
 * Two paths are exercised deliberately:
 *  - [storeWithRole] sets only the role, so `resolveAccessPolicy` takes its **fallback** leg. That is
 *    what an older server that reports no capabilities produces, and it must keep working.
 *  - [storeWithCapabilities] sets the reported set, which is the real path.
 */
class AdminNavItemTest {
    // LocalStorageKeyValueStore is the real wasm store backed by browser localStorage. Each case
    // writes BOTH keys before reading, so a value left over from a previous case cannot leak — which
    // matters more now that two keys decide the answer.
    private fun storeWithRole(role: String?): TokenStore =
        TokenStore(LocalStorageKeyValueStore()).apply {
            this.role = role
            this.capabilities = null
        }

    private fun storeWithCapabilities(
        role: String,
        held: Set<Capability>,
    ): TokenStore =
        TokenStore(LocalStorageKeyValueStore()).apply {
            this.role = role
            this.capabilities = held.map { it.name }.toSet()
        }

    @Test
    fun cashier_seesOnlyWhatTheServerActuallyAllows() {
        // CHANGED TWICE, and worth reading closely.
        //
        // First: previously DASHBOARD, PRODUCTS, STAFF, DOCUMENTS, ANALYTICS. A cashier LOST
        // products and staff — two management screens they could never use — and GAINED vouchers
        // and printer, both of which the server has always let them call. The old gate was stricter
        // than the API it fronted; that made the two agree.
        //
        // Now ANALYTICS goes too, and for the opposite reason: that gate was LOOSER than the API.
        // It required REPORT_READ, which a cashier holds, but every figure on the screen is the
        // whole shop's and the employee picker asks for somebody else's by name — both of which the
        // server refuses without REPORT_ALL_READ. A cashier was being shown a back-office screen
        // whose every control errored. The nav item's own comment already said "shop-wide
        // statistics"; that is a capability now rather than a description.
        //
        // A cashier keeps their own figures on the till's Reports screen, which is REPORT_READ.
        //
        // Third change (V130, decision D1): VOUCHERS and DOCUMENTS go as well. The cashier bundle no
        // longer holds VOUCHER_REPORT or DOCUMENT_READ (nothing at the till calls a route that needs
        // either), so the gates, which name exactly those capabilities, hide both.
        assertEquals(
            listOf(
                AdminNavItem.DASHBOARD,
                AdminNavItem.PRINTER,
            ),
            AdminNavItem.visibleFor(storeWithRole("CASHIER")),
        )
    }

    @Test
    fun terminalDevice_seesAlmostNothing() {
        assertEquals(
            listOf(AdminNavItem.DASHBOARD, AdminNavItem.PRINTER),
            AdminNavItem.visibleFor(storeWithRole("TERMINAL_DEVICE")),
        )
    }

    @Test
    fun unrecognisedRole_getsNothingAtAll() {
        // STRICTER THAN BEFORE, deliberately. The old gate ran the string through
        // `EmployeeRole.getRole()`, whose `else` branch resolves anything unrecognised to
        // TERMINAL_DEVICE — so a renamed or misspelled role still got a terminal's view.
        // `TokenStore.accessPolicy` resolves it to null instead, and a null role gates on nothing.
        //
        // Same reasoning as `StationClass.fromTerminalRole`: a value meaning "we could not identify
        // this" must not be one typo away from meaning a real principal. Only the dashboard, which
        // names no capability, survives.
        assertEquals(
            listOf(AdminNavItem.DASHBOARD),
            AdminNavItem.visibleFor(storeWithRole("A_ROLE_THAT_WAS_RENAMED")),
        )
    }

    @Test
    fun manager_seesManagerItemsButNotAdminOnlyItems() {
        val visible = AdminNavItem.visibleFor(storeWithRole("MANAGER"))
        assertTrue(AdminNavItem.TABLES in visible)
        assertTrue(AdminNavItem.TERMINALS in visible)
        assertTrue(AdminNavItem.AUDIT_LOG in visible)
        assertTrue(AdminNavItem.SCHEDULED_JOBS in visible)
        assertTrue(AdminNavItem.SETTINGS in visible)
        assertTrue(AdminNavItem.KASSENBUCH in visible)
        assertFalse(AdminNavItem.TSE in visible)
        assertFalse(AdminNavItem.TSE_FINDINGS in visible)
        assertFalse(AdminNavItem.PERMISSIONS in visible)
    }

    // `GatedDestination` in AdminApp applies the same predicate at the NavHost, so it is the only
    // thing between a URL fragment and an ADMIN-only screen. Pinned separately from the visibleFor()
    // cases, which would still pass if a gate were loosened but the item hidden some other way.
    @Test
    fun everyAdminOnlyItemIsClosedToAManager() {
        val manager = storeWithRole("MANAGER")
        listOf(AdminNavItem.TSE, AdminNavItem.TSE_FINDINGS, AdminNavItem.PERMISSIONS).forEach { item ->
            assertFalse(item.isVisibleTo(manager), "${item.name} must stay closed to a manager")
        }
    }

    @Test
    fun admin_seesEveryItem() {
        assertEquals(
            AdminNavItem.entries.toList(),
            AdminNavItem.visibleFor(storeWithRole("ADMIN")),
        )
    }

    @Test
    fun accountant_getsABookkeepersSidebar() {
        // THE POINT OF THE WHOLE TRACK. This principal carries role MANAGER — the assignment endpoint
        // does not touch the role — so gating on the role would hand them the full manager sidebar
        // and a row of screens that answer 403. The reported set is what decides instead.
        val accountant =
            storeWithCapabilities("MANAGER", CapabilityBundles.forGroup("ACCOUNTANT")!!)
        val visible = AdminNavItem.visibleFor(accountant)

        listOf(
            AdminNavItem.INVOICES,
            AdminNavItem.SALES_INVOICES,
            AdminNavItem.DEBTORS,
            AdminNavItem.SUPPLIERS,
            AdminNavItem.BANK,
            AdminNavItem.CONTRACTS,
            AdminNavItem.KASSENBUCH,
            AdminNavItem.DOCUMENTS,
            AdminNavItem.DATA_EXPORT,
            // Eigene Exporte. The accountant is who asks for a column the standard exports do not
            // carry, so CapabilityBundles hands ACCOUNTANT both custom-export tiers.
            AdminNavItem.CUSTOM_EXPORT,
            // V113. Withheld until then — ZBON_READ lists the daily closes, ZBON_MANAGE opens one,
            // and the audit groups held only the first. An auditor who cannot read a Z-Bon has no
            // reason to be logged in.
            AdminNavItem.Z_BONS,
        ).forEach { assertTrue(it in visible, "${it.name} is a bookkeeper's screen") }

        listOf(
            AdminNavItem.PRODUCTS,
            AdminNavItem.TABLES,
            AdminNavItem.TERMINALS,
            AdminNavItem.TSE,
            // Same capability as TSE, so a bookkeeper sees neither.
            AdminNavItem.TSE_FINDINGS,
            AdminNavItem.PERMISSIONS,
            AdminNavItem.SETTINGS,
            AdminNavItem.SCHEDULED_JOBS,
        ).forEach { assertFalse(it in visible, "${it.name} is not — an accountant books, they do not run the shop") }
    }

    @Test
    fun auditor_seesTheSameScreensAsAnAccountant() {
        // Deliberate sameness, and equality is the honest assertion. Navigation gates on the
        // capability needed to READ a screen, and ACCOUNTANT is AUDITOR plus writes — none of which
        // gates a nav entry — so the two differ in what they may DO once inside, not in what is
        // worth opening. Hiding a read-only auditor from screens they may legitimately read would be
        // gating on rank again.
        //
        // This used to be pinned as `accountant - CUSTOM_EXPORT`, because `CapabilityBundles` held
        // both custom-export tiers in ACCOUNTANT and neither in AUDITOR. The subtraction was written
        // to fail loudly if AUDITOR ever gained the read tier, and it did exactly that: AUDITOR now
        // holds CUSTOM_EXPORT_READ (the entry gates on it), the manage tier stays out, and the two
        // sidebars are identical again.
        val accountant = AdminNavItem.visibleFor(storeWithCapabilities("MANAGER", CapabilityBundles.forGroup("ACCOUNTANT")!!))
        val auditor = AdminNavItem.visibleFor(storeWithCapabilities("MANAGER", CapabilityBundles.forGroup("AUDITOR")!!))

        assertEquals(accountant, auditor)
    }

    @Test
    fun orderOnly_getsNoBackOffice() {
        val visible =
            AdminNavItem.visibleFor(storeWithCapabilities("CASHIER", CapabilityBundles.forGroup("ORDER_ONLY")!!))
        assertEquals(listOf(AdminNavItem.DASHBOARD, AdminNavItem.PRINTER), visible)
    }

    @Test
    fun aReportedSetOverridesTheRole() {
        // The fallback must never win over a real answer, in either direction.
        val narrowed = storeWithCapabilities("ADMIN", setOf(Capability.DOCUMENT_READ))
        assertEquals(listOf(AdminNavItem.DASHBOARD, AdminNavItem.DOCUMENTS), AdminNavItem.visibleFor(narrowed))
    }

    @Test
    fun everyItemNamesACapabilityOrIsDeliberatelyOpen() {
        // A new destination added without a capability is visible to everyone, including a terminal.
        // Only the dashboard is meant to be, so this makes the next addition a decision.
        val open = AdminNavItem.entries.filter { it.requiredCapability == null }
        assertEquals(listOf(AdminNavItem.DASHBOARD), open)
    }

    /**
     * Structural guards carried over from the role-era file: they are about the enum's shape, not
     * about who may see what, so the capability rewrite did not supersede them.
     *
     * The size assertion is the point — it fails when an entry is added without extending the map,
     * which is exactly how DEBTORS once shipped unmapped and left the suite red on dev.
     */
    @Test
    fun everyItemPointsAtItsOwnRoute() {
        val expected =
            mapOf(
                AdminNavItem.DASHBOARD to DashboardRoute::class,
                AdminNavItem.PRODUCTS to ProductsRoute::class,
                AdminNavItem.TABLES to TablesRoute::class,
                AdminNavItem.STAFF to StaffRoute::class,
                AdminNavItem.VOUCHERS to VouchersRoute::class,
                AdminNavItem.TERMINALS to TerminalsRoute::class,
                AdminNavItem.PRINTER to PrinterRoute::class,
                AdminNavItem.TSE to TseAdminRoute::class,
                AdminNavItem.TSE_FINDINGS to TseFindingsRoute::class,
                AdminNavItem.DOCUMENTS to DocumentsRoute::class,
                AdminNavItem.INVOICES to InvoicesRoute::class,
                AdminNavItem.SALES_INVOICES to SalesInvoicesRoute::class,
                AdminNavItem.DEBTORS to DebtorsRoute::class,
                AdminNavItem.ALLOCATIONS to AllocationsRoute::class,
                AdminNavItem.SUPPLIERS to SuppliersRoute::class,
                AdminNavItem.BANK to BankAccountsRoute::class,
                AdminNavItem.CONTRACTS to ContractsRoute::class,
                AdminNavItem.Z_BONS to ZBonListRoute::class,
                AdminNavItem.KASSENBUCH to KassenbuchRoute::class,
                AdminNavItem.ANALYTICS to AnalyticsRoute::class,
                AdminNavItem.DAILY_REPORT to DailyReportRoute::class,
                AdminNavItem.AUDIT_LOG to AuditLogRoute::class,
                AdminNavItem.KASSENMELDUNG to KassenmeldungRoute::class,
                AdminNavItem.DATA_EXPORT to DataExportRoute::class,
                AdminNavItem.CUSTOM_EXPORT to CustomExportRoute::class,
                AdminNavItem.SCHEDULED_JOBS to ScheduledJobsRoute::class,
                AdminNavItem.UPDATE to UpdateRoute::class,
                AdminNavItem.PERMISSIONS to PermissionGroupsRoute::class,
                AdminNavItem.SETTINGS to SettingsRoute::class,
            )
        assertEquals(AdminNavItem.entries.size, expected.size)
        AdminNavItem.entries.forEach { item ->
            assertEquals(expected[item], item.route::class, "wrong route for $item")
        }
    }

    @Test
    fun noTwoItemsShareARoute() {
        assertEquals(
            AdminNavItem.entries.size,
            AdminNavItem.entries
                .map { it.route::class }
                .distinct()
                .size,
        )
    }

    @Test
    fun adminGetsEveryGroupInDeclarationOrder() {
        // .toList(), not .toSet(): groupedFor's contract is that groups render in declaration
        // order, so a set comparison would pass on a reordering refactor.
        assertEquals(
            AdminNavGroup.entries.toList(),
            AdminNavItem.groupedFor(storeWithRole("ADMIN")).keys.toList(),
        )
    }

    @Test
    fun theFinancialGroupIsSplitByCadence() {
        val grouped = AdminNavItem.groupedFor(storeWithRole("ADMIN"))

        assertEquals(
            listOf(
                AdminNavItem.DOCUMENTS,
                AdminNavItem.INVOICES,
                AdminNavItem.SALES_INVOICES,
                AdminNavItem.ALLOCATIONS,
            ),
            grouped[AdminNavGroup.DOCUMENT_FLOW],
            "Belegfluss is the daily capture pipeline, in pipeline order",
        )
        assertEquals(
            listOf(
                AdminNavItem.SUPPLIERS,
                AdminNavItem.DEBTORS,
                AdminNavItem.BANK,
                AdminNavItem.CONTRACTS,
            ),
            grouped[AdminNavGroup.MASTER_DATA],
            "Stammdaten is what you configure once",
        )
        assertEquals(
            listOf(
                AdminNavItem.DAILY_REPORT,
                AdminNavItem.Z_BONS,
                AdminNavItem.KASSENBUCH,
                AdminNavItem.ANALYTICS,
            ),
            grouped[AdminNavGroup.CASH_CLOSING],
            "Kasse & Abschluss runs today first, then the closed period and its evaluation",
        )
    }

    @Test
    fun noGroupIsLargerThanFourItems() {
        // The drawer defaults to one group expanded. A group taller than the viewport would
        // reintroduce exactly the scrolling this taxonomy exists to remove.
        val grouped = AdminNavItem.groupedFor(storeWithRole("ADMIN"))
        grouped.forEach { (group, items) ->
            assertTrue(items.size <= 4, "$group holds ${items.size} items; keep groups scannable")
        }
    }

    /**
     * `DETAIL_ROUTE_GROUPS` is what keeps `NavGroupExpansion`'s active-group detection working on
     * the eleven detail/editor screens that have no sidebar entry of their own (an invoice, a
     * Z-Bon, a contract, a debtor, …). Without an entry there, opening one of those screens resolves
     * `activeGroup` to null and collapses the whole drawer while the operator is working inside it.
     *
     * Constructing a real `NavDestination` is impractical from a unit test, so this pins the map's
     * completeness directly instead: every route declared in `AdminRoutes.kt` is either a nav item's
     * own route, the deliberately-excluded `FirstTerminalSetupRoute` (a full-screen setup wizard, not
     * a screen inside a group), or present in `DETAIL_ROUTE_GROUPS`. Miss one and this goes red.
     */
    @Test
    fun everyNonNavRouteExceptFirstTerminalSetupIsInTheDetailRouteGroupMap() {
        val allRoutes =
            setOf(
                DashboardRoute::class,
                ProductsRoute::class,
                TablesRoute::class,
                FloorPlanEditorRoute::class,
                StaffRoute::class,
                TseAdminRoute::class,
                TseFindingsRoute::class,
                TerminalsRoute::class,
                PermissionGroupsRoute::class,
                ScheduledJobsRoute::class,
                UpdateRoute::class,
                AnalyticsRoute::class,
                ZBonListRoute::class,
                AuditLogRoute::class,
                SettingsRoute::class,
                KassenbuchRoute::class,
                ZBonDetailRoute::class,
                FirstTerminalSetupRoute::class,
                VouchersRoute::class,
                PrinterRoute::class,
                KassenmeldungRoute::class,
                DataExportRoute::class,
                CustomExportRoute::class,
                DocumentsRoute::class,
                DocumentDetailRoute::class,
                SuppliersRoute::class,
                BankAccountsRoute::class,
                SupplierDetailRoute::class,
                InvoiceCaptureRoute::class,
                InvoiceEditRoute::class,
                InvoicesRoute::class,
                SalesInvoicesRoute::class,
                SalesInvoiceEditorRoute::class,
                SalesInvoiceEditRoute::class,
                DebtorsRoute::class,
                DebtorDetailRoute::class,
                DebtorCreateRoute::class,
                AllocationsRoute::class,
                ContractsRoute::class,
                ContractDetailRoute::class,
            )
        val navItemRoutes = AdminNavItem.entries.map { it.route::class }.toSet()
        val expectedDetailRoutes = allRoutes - navItemRoutes - FirstTerminalSetupRoute::class

        assertEquals(expectedDetailRoutes, AdminNavItem.DETAIL_ROUTE_GROUPS.keys)
    }

    @Test
    fun everyNavItemIsTitledByItsOwnLabel() {
        AdminNavItem.entries.forEach { item ->
            assertEquals(item.labelKey, AdminNavItem.labelKeyForRoute { it == item.route::class }, "$item")
        }
    }

    @Test
    fun aDetailScreenHasNoTitleOfItsOwn() {
        assertNull(AdminNavItem.labelKeyForRoute { it == DebtorDetailRoute::class })
        assertNull(AdminNavItem.labelKeyForRoute { false })
    }

    @Test
    fun everyDetailRouteSelectsItsParentItem() {
        AdminNavItem.DETAIL_ROUTE_PARENTS.forEach { (route, parent) ->
            assertEquals(parent, AdminNavItem.itemForRoute { it == route }, "$route")
        }
    }

    @Test
    fun everyNavItemSelectsItself() {
        AdminNavItem.entries.forEach { item ->
            assertEquals(item, AdminNavItem.itemForRoute { it == item.route::class }, "$item")
        }
    }

    @Test
    fun anUnknownRouteSelectsNothing() {
        assertNull(AdminNavItem.itemForRoute { it == String::class })
        assertNull(AdminNavItem.groupForRoute { it == String::class })
    }

    @Test
    fun theFirstTerminalSetupOpensTheTerminalsGroup() {
        assertEquals(AdminNavItem.TERMINALS.group, AdminNavItem.groupForRoute { it == FirstTerminalSetupRoute::class })
    }

    @Test
    fun everyDetailGroupRouteKeepsItsDeclaredGroup() {
        AdminNavItem.DETAIL_ROUTE_GROUPS.forEach { (route, group) ->
            assertEquals(group, AdminNavItem.groupForRoute { it == route }, "$route")
        }
    }
}
