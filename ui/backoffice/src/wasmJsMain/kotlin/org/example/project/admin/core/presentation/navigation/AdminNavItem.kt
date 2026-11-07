package org.example.project.admin.core.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import org.example.project.admin.core.auth.holds
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.SettingsSection
import org.example.project.core.auth.AccessPolicy
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.UpdateKeys
import kotlin.reflect.KClass

/**
 * Every sidebar destination, in render order within its [group]. This is the single source of
 * truth that replaces the eleven hand-written `item(…)` lambdas in `AdminApp.kt`.
 *
 * [requiredCapability] is the client half of CLAUDE.md rule 8 — it only hides controls; the server
 * enforces the same rule on every route. Null means every principal sees the item.
 *
 * **Each destination names its OWN capability**, taken from the server rule for the data that
 * screen exists to show. They used to share `canManageProducts`, which was really "is a manager" —
 * and a single shared gate cannot express a bookkeeper who should see invoices, debtors and
 * contracts but not the catalog or the terminals. That is the whole reason the capability model
 * exists, and the sidebar is where an operator actually sees it.
 *
 * [route] is the destination's route instance. Match it against the current back-stack entry with
 * `destination.hasRoute(item.route::class)` — that works for both `object` routes and the
 * `SettingsRoute` data class.
 *
 * [SETTINGS] is the only item the sidebar renders specially: it is a tree node whose children are
 * the `SettingsSection` entries. It still lives here so it is grouped and gated like everything
 * else.
 */
enum class AdminNavItem(
    val route: Any,
    val icon: ImageVector,
    val labelKey: StringKey,
    val group: AdminNavGroup,
    val requiredCapability: Capability?,
) {
    DASHBOARD(
        DashboardRoute,
        Icons.Default.Dashboard,
        AdminStringKeys.NavDashboard,
        AdminNavGroup.PINNED,
        null, // the landing screen; every principal has one
    ),
    PRODUCTS(
        ProductsRoute,
        Icons.Default.Inventory2,
        AdminStringKeys.NavProducts,
        AdminNavGroup.OPERATIONS,
        Capability.CATALOG_MANAGE, // the product editor
    ),
    TABLES(
        TablesRoute,
        Icons.Default.TableRestaurant,
        AdminStringKeys.NavTables,
        AdminNavGroup.OPERATIONS,
        Capability.CATALOG_MANAGE, // floor and room setup is shop configuration
    ),
    STAFF(
        StaffRoute,
        Icons.Default.Groups,
        AdminStringKeys.NavStaff,
        AdminNavGroup.OPERATIONS,
        Capability.EMPLOYEE_READ, // GET /api/employees/summary
    ),
    VOUCHERS(
        VouchersRoute,
        Icons.Default.ConfirmationNumber,
        AdminStringKeys.SettingsSectionVouchers,
        AdminNavGroup.OPERATIONS,
        Capability.VOUCHER_REPORT, // the back-office half of the Stage 2b voucher split
    ),

    // Leads Kasse & Abschluss because it is the one screen here about the day in progress, and the
    // one reached most often; the three below it are the closed period. REPORT_ALL_READ, the same
    // gate as ANALYTICS further down: this is the same shop-wide takings data, itemised, so a
    // CASHIER holding only REPORT_READ would get a screen whose every control the server refuses.
    DAILY_REPORT(
        DailyReportRoute,
        Icons.AutoMirrored.Filled.ReceiptLong,
        AdminStringKeys.NavDailyReport,
        AdminNavGroup.CASH_CLOSING,
        Capability.REPORT_ALL_READ,
    ),

    Z_BONS(
        ZBonListRoute,
        Icons.Default.Receipt,
        AdminStringKeys.NavZBons,
        AdminNavGroup.CASH_CLOSING,
        Capability.ZBON_MANAGE, // the back-office Z-Bon list, not the till read
    ),

    // Every Kassenbuch endpoint is MANAGER server-side, the same as the Z-Bon list above it, so it
    // carries the same gate. `KassenbuchRoute()` is a data class with a `headerId` default — the
    // sidebar navigates to the no-selection instance; the dashboard supplies its own.
    KASSENBUCH(
        KassenbuchRoute(),
        Icons.Default.MenuBook,
        AdminStringKeys.NavKassenbuch,
        AdminNavGroup.CASH_CLOSING,
        Capability.CASHBOOK_READ, // the cash ledger
    ),

    // REPORT_ALL_READ, not REPORT_READ. The old comment already said "shop-wide statistics" — that
    // is now a capability rather than a description. Every figure on this screen is the whole shop's,
    // and the employee picker asks for somebody else's by name; a CASHIER holds REPORT_READ, so
    // gating on it offered a back-office screen whose every control the server refuses.
    ANALYTICS(
        AnalyticsRoute,
        Icons.Default.Assessment,
        AdminStringKeys.NavAnalytics,
        AdminNavGroup.CASH_CLOSING,
        Capability.REPORT_ALL_READ,
    ),

    DOCUMENTS(
        DocumentsRoute,
        Icons.Default.FolderOpen,
        AdminStringKeys.NavDocuments,
        AdminNavGroup.DOCUMENT_FLOW,
        Capability.DOCUMENT_READ, // the DMS
    ),

    // The capture pipeline over the archive: GET /api/invoices and the review PUT are both
    // MANAGER server-side, so the entry carries the same gate as SUPPLIERS below.
    INVOICES(
        InvoicesRoute(),
        Icons.Default.ReceiptLong,
        AdminStringKeys.NavInvoices,
        AdminNavGroup.DOCUMENT_FLOW,
        Capability.INVOICE_READ, // incoming invoices
    ),

    // Ausgangsrechnungen: every /api/v1/sales-invoices route is MANAGER server-side, so the entry
    // carries the same gate INVOICES does — a non-manager seeing it would only earn a 403.
    SALES_INVOICES(
        SalesInvoicesRoute,
        Icons.Default.Receipt,
        AdminStringKeys.NavSalesInvoices,
        AdminNavGroup.DOCUMENT_FLOW,
        Capability.INVOICE_READ, // GET /api/v1/sales-invoices
    ),

    // Every /api/allocations endpoint is MANAGER server-side, so a non-manager seeing the entry
    // would only earn a 403 on arrival — hence the same gate INVOICES carries.
    ALLOCATIONS(
        AllocationsRoute,
        Icons.Default.CompareArrows,
        AdminStringKeys.NavAllocations,
        AdminNavGroup.DOCUMENT_FLOW,
        Capability.ACCOUNTING_READ, // GET /api/allocations/queue
    ),

    // Every /api/suppliers endpoint is MANAGER server-side, so a non-manager seeing the entry would
    // only earn a 403 on arrival — hence the same gate Z_BONS and KASSENBUCH carry.
    SUPPLIERS(
        SuppliersRoute,
        Icons.Default.LocalShipping,
        AdminStringKeys.NavSuppliers,
        AdminNavGroup.MASTER_DATA,
        Capability.CREDITOR_READ, // GET /api/suppliers
    ),

    // Debitoren: every /api/debtors route is MANAGER server-side, same gate as SALES_INVOICES above.
    DEBTORS(
        DebtorsRoute,
        Icons.Default.AccountBox,
        AdminStringKeys.NavDebtors,
        AdminNavGroup.MASTER_DATA,
        Capability.DEBTOR_READ, // GET /api/debtors
    ),

    // Same reasoning as SUPPLIERS above: every /api/bank-accounts endpoint is MANAGER server-side.
    BANK(
        BankAccountsRoute,
        Icons.Default.AccountBalance,
        AdminStringKeys.NavBank,
        AdminNavGroup.MASTER_DATA,
        Capability.CREDITOR_READ, // GET /api/bank-accounts
    ),

    // Same reasoning again: every /api/contracts endpoint is MANAGER server-side, so a non-manager
    // seeing the entry would only earn a 403 on arrival.
    CONTRACTS(
        ContractsRoute,
        Icons.Default.EventRepeat,
        AdminStringKeys.NavContracts,
        AdminNavGroup.MASTER_DATA,
        Capability.CONTRACT_READ, // GET /api/contracts
    ),

    TERMINALS(
        TerminalsRoute,
        Icons.Default.PointOfSale,
        AdminStringKeys.NavTerminals,
        AdminNavGroup.DEVICES,
        Capability.TERMINAL_MANAGE, // station administration, not the till read
    ),

    PRINTER(
        PrinterRoute,
        Icons.Default.Print,
        AdminStringKeys.SettingsSectionPrinter,
        AdminNavGroup.DEVICES,
        Capability.PRINTER_WRITE, // printer configuration
    ),
    TSE(
        TseAdminRoute,
        Icons.Default.Security,
        AdminStringKeys.NavTse,
        AdminNavGroup.DEVICES,
        Capability.TSE_ADMIN, // RESERVED — admin only by construction
    ),

    // The POS-side reconciliation of the device's transaction numbers, and the register that
    // explains them. Sits beside TSE rather than in Compliance: it is about one device's numbering,
    // and Compliance already holds four items.
    TSE_FINDINGS(
        TseFindingsRoute,
        Icons.AutoMirrored.Filled.FactCheck,
        AdminStringKeys.NavTseFindings,
        AdminNavGroup.DEVICES,
        Capability.TSE_ADMIN, // the capability GET /api/tse/findings requires
    ),

    AUDIT_LOG(
        AuditLogRoute,
        Icons.Default.History,
        AdminStringKeys.NavAuditLog,
        AdminNavGroup.COMPLIANCE,
        Capability.AUDIT_READ, // GET /api/audit/transactions
    ),
    KASSENMELDUNG(
        KassenmeldungRoute,
        Icons.Default.Description,
        AdminStringKeys.SettingsSectionKassenmeldung,
        AdminNavGroup.COMPLIANCE,
        Capability.KASSEN_MELDUNG_READ, // the §146a registration report
    ),
    DATA_EXPORT(
        DataExportRoute,
        Icons.Default.Download,
        AdminStringKeys.SettingsSectionDataExport,
        AdminNavGroup.COMPLIANCE,
        Capability.DSFINVK_EXPORT, // the DSFinV-K export
    ),

    // Eigene Exporte is emphatically NOT a compliance export — nothing here is prescribed by the
    // Finanzamt, and the screen's own copy says so. It sits in this group because the group is where
    // an operator goes to get data OUT of the system, and because the alternative, Betrieb, already
    // holds four items and this taxonomy caps a group at four so the drawer never scrolls.
    //
    // CUSTOM_EXPORT_READ, not _MANAGE: a principal who may run the exports somebody else defined has
    // every reason to reach the screen. The building controls hide separately behind
    // `canManageCustomExports`, and the run re-checks each field against the runner's own
    // capabilities server-side.
    CUSTOM_EXPORT(
        CustomExportRoute,
        Icons.Default.TableChart,
        AdminStringKeys.CustomExportTitle,
        AdminNavGroup.COMPLIANCE,
        Capability.CUSTOM_EXPORT_READ, // GET /api/custom-exports
    ),
    SCHEDULED_JOBS(
        ScheduledJobsRoute,
        Icons.Default.Schedule,
        AdminStringKeys.NavScheduledJobs,
        AdminNavGroup.SYSTEM,
        Capability.SCHEDULED_JOB_READ, // GET /api/scheduled-jobs
    ),

    // UPDATE_READ, not UPDATE_MANAGE. A manager's shop is the one that restarts, so they should see
    // that a release is waiting; installing it swaps the binary and migrates the live fiscal
    // database, which is why the three actions on the screen carry the narrower gate instead.
    UPDATE(
        UpdateRoute,
        Icons.Default.SystemUpdateAlt,
        UpdateKeys.screenTitle,
        AdminNavGroup.SYSTEM,
        Capability.UPDATE_READ, // GET /api/update/status
    ),

    // Who may do what. PERMISSION_ADMIN is RESERVED, so no group edit can confer it — which is
    // exactly why this destination can be offered to whoever holds it without that becoming a way
    // to acquire it.
    PERMISSIONS(
        PermissionGroupsRoute,
        Icons.Default.AdminPanelSettings,
        AdminStringKeys.NavPermissions,
        AdminNavGroup.SYSTEM,
        Capability.PERMISSION_ADMIN, // GET /api/admin/permission-groups
    ),
    SETTINGS(
        SettingsRoute(),
        Icons.Default.Settings,
        AdminStringKeys.NavSettings,
        AdminNavGroup.SYSTEM,
        Capability.CONFIG_WRITE, // the settings tree EDITS configuration; sections gate themselves further
    ),
    ;

    /**
     * Whether this destination should appear for the current principal.
     *
     * Falls through to visible when the item names no capability. Where the server has not reported
     * capabilities at all — an older server — `TokenStore.accessPolicy` falls back to the role, so
     * the console degrades to its previous behaviour rather than to an empty sidebar.
     */
    fun isVisibleTo(tokenStore: TokenStore): Boolean = requiredCapability == null || tokenStore.holds(requiredCapability)

    /** [isVisibleTo] against a bare policy, which is what the phone top bar has to hand. */
    fun isVisibleTo(policy: AccessPolicy): Boolean = requiredCapability == null || policy.allows(requiredCapability)

    companion object {
        /** Entries whose [gate] passes, in declaration order. */
        fun visibleFor(tokenStore: TokenStore): List<AdminNavItem> = entries.filter { it.isVisibleTo(tokenStore) }

        /**
         * [visibleFor] bucketed by group. `groupBy` preserves encounter order and omits groups
         * with no surviving members, so the sidebar never renders a header over nothing.
         */
        fun groupedFor(tokenStore: TokenStore): Map<AdminNavGroup, List<AdminNavItem>> = visibleFor(tokenStore).groupBy { it.group }

        /**
         * The phone top bar's title. A settings destination is titled by its SECTION (the tree's own
         * "Einstellungen" label said nothing about which of the 12 pages was open); any other nav item
         * by its own label; a detail or editor screen by the nav item it is reached from.
         */
        fun titleKeyForDestination(
            destination: NavDestination?,
            currentSection: String?,
            policy: AccessPolicy,
        ): StringKey? {
            if (destination == null) return null
            if (destination.hasRoute(SettingsRoute::class)) return SettingsSection.titleKeyFor(currentSection, policy)
            return titleKeyForMatch(policy) { route -> destination.hasRoute(route) }
        }

        /**
         * The title of the nav item (or detail parent) whose route [matches]; null when the principal
         * may not see that item, because its destination bounces them and a title would name a
         * screen they cannot open.
         */
        internal fun titleKeyForMatch(
            policy: AccessPolicy,
            matches: (KClass<*>) -> Boolean,
        ): StringKey? {
            entries.firstOrNull { matches(it.route::class) }?.let { item ->
                return item.labelKey.takeIf { item.isVisibleTo(policy) }
            }
            val parent = DETAIL_ROUTE_PARENTS.entries.firstOrNull { (route, _) -> matches(route) }?.value ?: return null
            if (!parent.isVisibleTo(policy)) return null
            return DETAIL_ROUTE_TITLES.entries.firstOrNull { (route, _) -> matches(route) }?.value ?: parent.labelKey
        }

        /**
         * Detail screens the phone top bar names by their own title rather than their parent's: the
         * floor-plan editor drew "Tische" in the top bar and its own "← Grundriss" header beneath it,
         * two titles on two baselines (UI review nit). It keeps its parent's visibility gate.
         */
        internal val DETAIL_ROUTE_TITLES: Map<KClass<*>, StringKey> =
            mapOf(FloorPlanEditorRoute::class to AdminStringKeys.FloorPlanTitle)

        /** The label of the nav item whose route [matches]; null for detail/editor screens. */
        internal fun labelKeyForRoute(matches: (KClass<*>) -> Boolean): StringKey? = entries.firstOrNull { matches(it.route::class) }?.labelKey

        /**
         * The nav item whose route [matches]: the item's own route first, else the parent item of a
         * detail or editor screen ([DETAIL_ROUTE_PARENTS]). Null for a route with neither.
         */
        internal fun itemForRoute(matches: (KClass<*>) -> Boolean): AdminNavItem? =
            entries.firstOrNull { matches(it.route::class) }
                ?: DETAIL_ROUTE_PARENTS.entries.firstOrNull { (route, _) -> matches(route) }?.value

        /** [itemForRoute] for a back-stack destination; null-safe. */
        fun itemForDestination(destination: NavDestination?): AdminNavItem? {
            if (destination == null) return null
            return itemForRoute { route -> destination.hasRoute(route) }
        }

        /**
         * The group a route belongs to: the exact nav item's group, else the detail-screen group
         * ([DETAIL_ROUTE_GROUPS]), else the group of the parent item ([DETAIL_ROUTE_PARENTS]).
         */
        internal fun groupForRoute(matches: (KClass<*>) -> Boolean): AdminNavGroup? {
            entries.firstOrNull { matches(it.route::class) }?.let { return it.group }
            DETAIL_ROUTE_GROUPS.entries.firstOrNull { (route, _) -> matches(route) }?.let { return it.value }
            return itemForRoute(matches)?.group
        }

        /**
         * The group a destination belongs to, including destinations that are not themselves nav
         * items. Detail and editor screens have no sidebar entry of their own, so
         * matching only nav-item routes returns null for them and collapses the entire drawer while
         * the operator is working inside one of its groups.
         */
        fun groupForDestination(destination: NavDestination?): AdminNavGroup? {
            if (destination == null) return null
            return groupForRoute { route -> destination.hasRoute(route) }
        }

        /** Screens reached FROM a nav destination, mapped to the group that owns them. */
        internal val DETAIL_ROUTE_GROUPS: Map<KClass<*>, AdminNavGroup> =
            mapOf(
                FloorPlanEditorRoute::class to AdminNavGroup.OPERATIONS,
                DocumentDetailRoute::class to AdminNavGroup.DOCUMENT_FLOW,
                InvoiceCaptureRoute::class to AdminNavGroup.DOCUMENT_FLOW,
                InvoiceEditRoute::class to AdminNavGroup.DOCUMENT_FLOW,
                SalesInvoiceEditorRoute::class to AdminNavGroup.DOCUMENT_FLOW,
                SalesInvoiceEditRoute::class to AdminNavGroup.DOCUMENT_FLOW,
                SupplierDetailRoute::class to AdminNavGroup.MASTER_DATA,
                DebtorDetailRoute::class to AdminNavGroup.MASTER_DATA,
                DebtorCreateRoute::class to AdminNavGroup.MASTER_DATA,
                ContractDetailRoute::class to AdminNavGroup.MASTER_DATA,
                ZBonDetailRoute::class to AdminNavGroup.CASH_CLOSING,
            )

        /**
         * The nav item each detail or editor screen is reached from — only used to TITLE the screen.
         * Separate from [DETAIL_ROUTE_GROUPS], which names a group directly: [FirstTerminalSetupRoute]
         * has a parent but no entry there, and still opens its parent's group (and selects the
         * parent item) through the parent fallback in [groupForRoute] and [itemForRoute].
         */
        internal val DETAIL_ROUTE_PARENTS: Map<KClass<*>, AdminNavItem> =
            mapOf(
                FloorPlanEditorRoute::class to TABLES,
                DocumentDetailRoute::class to DOCUMENTS,
                InvoiceCaptureRoute::class to INVOICES,
                InvoiceEditRoute::class to INVOICES,
                SalesInvoiceEditorRoute::class to SALES_INVOICES,
                SalesInvoiceEditRoute::class to SALES_INVOICES,
                SupplierDetailRoute::class to SUPPLIERS,
                DebtorDetailRoute::class to DEBTORS,
                DebtorCreateRoute::class to DEBTORS,
                ContractDetailRoute::class to CONTRACTS,
                ZBonDetailRoute::class to Z_BONS,
                FirstTerminalSetupRoute::class to TERMINALS,
            )
    }
}
