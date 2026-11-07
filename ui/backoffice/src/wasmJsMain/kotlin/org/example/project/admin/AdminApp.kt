package org.example.project.admin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.bindToBrowserNavigation
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.example.project.admin.core.auth.accessPolicy
import org.example.project.admin.core.auth.canAdministerTse
import org.example.project.admin.core.auth.canManageProducts
import org.example.project.admin.core.auth.holds
import org.example.project.admin.core.presentation.components.AdminTopBarActions
import org.example.project.admin.core.presentation.components.LocalAdminTopBarActions
import org.example.project.admin.core.presentation.guard.UnsavedChangesHost
import org.example.project.admin.core.presentation.navigation.AdminNavGroup
import org.example.project.admin.core.presentation.navigation.AdminNavItem
import org.example.project.admin.core.presentation.navigation.AdminSidebar
import org.example.project.admin.core.presentation.navigation.AdminUrlSlugs
import org.example.project.admin.core.presentation.navigation.AllocationsRoute
import org.example.project.admin.core.presentation.navigation.AnalyticsRoute
import org.example.project.admin.core.presentation.navigation.AuditLogRoute
import org.example.project.admin.core.presentation.navigation.BankAccountsRoute
import org.example.project.admin.core.presentation.navigation.ContractDetailRoute
import org.example.project.admin.core.presentation.navigation.ContractsRoute
import org.example.project.admin.core.presentation.navigation.CustomExportRoute
import org.example.project.admin.core.presentation.navigation.DailyReportRoute
import org.example.project.admin.core.presentation.navigation.DashboardRoute
import org.example.project.admin.core.presentation.navigation.DataExportRoute
import org.example.project.admin.core.presentation.navigation.DebtorCreateRoute
import org.example.project.admin.core.presentation.navigation.DebtorDetailRoute
import org.example.project.admin.core.presentation.navigation.DebtorsRoute
import org.example.project.admin.core.presentation.navigation.DetailBackTarget
import org.example.project.admin.core.presentation.navigation.DocumentDetailRoute
import org.example.project.admin.core.presentation.navigation.DocumentsRoute
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.navigation.FirstTerminalSetupRoute
import org.example.project.admin.core.presentation.navigation.FloorPlanEditorRoute
import org.example.project.admin.core.presentation.navigation.InvoiceCaptureRoute
import org.example.project.admin.core.presentation.navigation.InvoiceEditRoute
import org.example.project.admin.core.presentation.navigation.InvoicesRoute
import org.example.project.admin.core.presentation.navigation.KassenbuchRoute
import org.example.project.admin.core.presentation.navigation.KassenmeldungRoute
import org.example.project.admin.core.presentation.navigation.NavGroupExpansion
import org.example.project.admin.core.presentation.navigation.PermissionGroupsRoute
import org.example.project.admin.core.presentation.navigation.PrinterRoute
import org.example.project.admin.core.presentation.navigation.ProductsRoute
import org.example.project.admin.core.presentation.navigation.SalesInvoiceEditRoute
import org.example.project.admin.core.presentation.navigation.SalesInvoiceEditorRoute
import org.example.project.admin.core.presentation.navigation.SalesInvoicesRoute
import org.example.project.admin.core.presentation.navigation.ScannerDevRoute
import org.example.project.admin.core.presentation.navigation.ScheduledJobsRoute
import org.example.project.admin.core.presentation.navigation.SettingsRoute
import org.example.project.admin.core.presentation.navigation.StaffRoute
import org.example.project.admin.core.presentation.navigation.SupplierDetailRoute
import org.example.project.admin.core.presentation.navigation.SuppliersRoute
import org.example.project.admin.core.presentation.navigation.TablesRoute
import org.example.project.admin.core.presentation.navigation.TerminalsRoute
import org.example.project.admin.core.presentation.navigation.TseAdminRoute
import org.example.project.admin.core.presentation.navigation.TseFindingsRoute
import org.example.project.admin.core.presentation.navigation.UpdateRoute
import org.example.project.admin.core.presentation.navigation.VouchersRoute
import org.example.project.admin.core.presentation.navigation.ZBonDetailRoute
import org.example.project.admin.core.presentation.navigation.ZBonListRoute
import org.example.project.admin.core.presentation.navigation.reportScreenName
import org.example.project.admin.core.presentation.strings.AdminLocale
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.selectAdminLanguage
import org.example.project.admin.core.presentation.strings.storedAdminLanguage
import org.example.project.admin.core.presentation.theme.AdminTheme
import org.example.project.admin.core.util.browser.installBrowserLeaveGuard
import org.example.project.admin.core.util.dom.BlockDomOverlayWhileVisible
import org.example.project.admin.core.util.dom.DomOverlayController
import org.example.project.admin.core.util.dom.LocalDomOverlayController
import org.example.project.admin.core.util.pdf.PdfPrint
import org.example.project.admin.features.allocations.presentation.AllocationsScreen
import org.example.project.admin.features.auditlog.presentation.AuditLogDestination
import org.example.project.admin.features.auth.domain.AdminSignOut
import org.example.project.admin.features.auth.presentation.AdminLoginScreen
import org.example.project.admin.features.bank.presentation.BankAccountsScreen
import org.example.project.admin.features.bootstrap.presentation.BootstrapSetupScreen
import org.example.project.admin.features.bootstrap.presentation.BootstrapSetupViewModel
import org.example.project.admin.features.bootstrap.presentation.BootstrapStatus
import org.example.project.admin.features.contracts.presentation.ContractDetailScreen
import org.example.project.admin.features.contracts.presentation.ContractsScreen
import org.example.project.admin.features.customexport.presentation.CustomExportScreen
import org.example.project.admin.features.dailyreport.presentation.DailyReportScreen
import org.example.project.admin.features.dashboard.presentation.AdminDashboardScreen
import org.example.project.admin.features.dashboard.presentation.checklist.ChecklistDestination
import org.example.project.admin.features.dashboard.presentation.showDailyReportLink
import org.example.project.admin.features.dashboard.presentation.showRecentCashEntries
import org.example.project.admin.features.dashboard.presentation.showsFiscalDayCards
import org.example.project.admin.features.debtors.presentation.DebtorDetailScreen
import org.example.project.admin.features.debtors.presentation.DebtorDetailViewModel
import org.example.project.admin.features.debtors.presentation.DebtorsScreen
import org.example.project.admin.features.documents.presentation.DocumentDetailScreen
import org.example.project.admin.features.documents.presentation.DocumentsScreen
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureScreen
import org.example.project.admin.features.documents.presentation.capture.InvoiceEditScreen
import org.example.project.admin.features.documents.presentation.scanner.ScannerScreen
import org.example.project.admin.features.firstterminal.presentation.FirstTerminalSetupScreen
import org.example.project.admin.features.firstterminal.presentation.FirstTerminalSetupState
import org.example.project.admin.features.firstterminal.presentation.FirstTerminalSetupViewModel
import org.example.project.admin.features.firstterminal.presentation.shouldLeaveWizardForTerminals
import org.example.project.admin.features.floorplan.presentation.FloorPlanEditorScreen
import org.example.project.admin.features.invoices.presentation.InvoicesScreen
import org.example.project.admin.features.issuereport.presentation.ReportProblemDialog
import org.example.project.admin.features.issuereport.presentation.ReportProblemLauncher
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchScreen
import org.example.project.admin.features.permissions.presentation.PermissionGroupsScreen
import org.example.project.admin.features.product.presentation.ProductScreen
import org.example.project.admin.features.reports.presentation.ReportsScreen
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoiceEditorScreen
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoicesScreen
import org.example.project.admin.features.settings.presentation.SettingsScreen
import org.example.project.admin.features.settings.presentation.sections.DataExportSection
import org.example.project.admin.features.settings.presentation.sections.KassenmeldungSection
import org.example.project.admin.features.settings.presentation.sections.PrinterSection
import org.example.project.admin.features.settings.presentation.toSection
import org.example.project.admin.features.staff.presentation.StaffScreen
import org.example.project.admin.features.supplier.presentation.SupplierDetailScreen
import org.example.project.admin.features.supplier.presentation.SuppliersScreen
import org.example.project.admin.features.tables.presentation.TablesScreen
import org.example.project.admin.features.terminal.presentation.TerminalsScreen
import org.example.project.admin.features.tse.presentation.TseAdminScreen
import org.example.project.admin.features.tse.presentation.TseFindingsScreen
import org.example.project.admin.features.update.presentation.UpdateScreen
import org.example.project.admin.features.voucher.presentation.VoucherScreen
import org.example.project.admin.features.zBon.closing.ZBonClosingAccess
import org.example.project.admin.features.zBon.presentation.ZBonHubScreen
import org.example.project.admin.features.zBon.presentation.ZBonListScreen
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.GlobalOverlayViewModel
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.error.LocalScreenName
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.ProvideWindowSizeClass
import org.example.project.core.utils.isAtLeastExpanded
import org.example.project.core.utils.isCompact
import org.example.project.features.employee.presentation.EmployeePickerViewModel
import org.example.project.features.scheduler.presentation.screens.ScheduledJobsScreen
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.auth.RequireCapability
import org.example.project.ui.auth.SessionState
import org.example.project.ui.components.LocalDecimalInputSeparator
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosNotificationHost
import org.example.project.ui.components.filter.LocalPosFilterPanelStore
import org.example.project.ui.components.filter.PosFilterPanelStore
import org.example.project.ui.guard.LeaveGuard
import org.example.project.ui.guard.LocalLeaveGuard
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private const val KEY_SIDEBAR_COLLAPSED = "admin.sidebar.collapsed"

@Composable
fun AdminApp() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader
            .Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .build()
    }
    CompositionLocalProvider(
        LocalAppLanguage provides AdminLocale.language,
        // Money/percent editors show the operator's separator ("12,90" in German); the value they
        // hold and send stays the canonical dot decimal (admin21 NOTE-e21).
        LocalDecimalInputSeparator provides if (AdminLocale.language == Language.GERMAN) ',' else '.',
    ) {
        AdminTheme {
            Surface(Modifier.fillMaxSize()) {
                val session = koinInject<SessionState>()
                val globalOverlayViewModel: GlobalOverlayViewModel = koinViewModel()
                val currentNotification by globalOverlayViewModel.currentNotification.collectAsState()
                val keyValueStore = koinInject<KeyValueStore>()
                // Same shared-instance reasoning as globalOverlayViewModel below: every EmployeePicker
                // in the app resolves to this one instance via koinViewModel(), so its roster must be
                // invalidated here too, or a re-login keeps showing the previous session's employees.
                val employeePickerViewModel: EmployeePickerViewModel = koinViewModel()

                // The language switcher writes only to AdminLocale, which is in-memory, so
                // without this a refresh silently reverts to German. Restored above the session
                // gate so the login screen comes up in the chosen language too.
                LaunchedEffect(Unit) {
                    storedAdminLanguage(keyValueStore)?.let { AdminLocale.language = it }
                }

                // The overlay VM lives above the session boundary, so clear any queued overlays
                // when the user signs out. Otherwise session A toasts can render over session B.
                LaunchedEffect(session.status) {
                    if (session.status == SessionStatus.UNAUTHENTICATED) {
                        globalOverlayViewModel.clearAll()
                        employeePickerViewModel.reset()
                    }
                }

                val domOverlayController = remember { DomOverlayController() }
                val koinTokenStore = koinInject<TokenStore>()

                // The gate every `CapabilityGate` / `RequireCapability` below reads. Without this
                // they resolve the local's default — which DENIES EVERYTHING — so a shared-ui gate
                // in adminApp would silently hide its content from every principal, manager
                // included. Compiles perfectly; renders nothing.
                //
                // Keyed on the epoch, not on the token store: the store is a singleton, so a memo
                // keyed on it never recomputes, and capabilities change mid-session on a silent
                // token refresh. Reading `tokenStore.capabilities` as the key instead would re-parse
                // the stored set on every recomposition, which is what the epoch exists to avoid.
                val accessPolicy = remember(session.capabilityEpoch) { koinTokenStore.accessPolicy }

                CompositionLocalProvider(
                    LocalGlobalOverlayViewModel provides globalOverlayViewModel,
                    LocalDomOverlayController provides domOverlayController,
                    LocalAccessPolicy provides accessPolicy,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        when (session.status) {
                            // Every page load starts here: validation lets the client's cookie refresh
                            // resume the session, or falls through to login when there is none.
                            SessionStatus.CHECKING -> {
                                val authApiClient = koinInject<AuthApiClient>()
                                LaunchedEffect(Unit) {
                                    // The shared client throws on a rejected session, so the startup
                                    // gate owns its own failure policy: anything other than a clean
                                    // `valid` means fall through to login. Do NOT refresh manually —
                                    // it double-fires with the Auth plugin's own refresh, and on wasm
                                    // the failed-refresh path throws ClosedByteChannelException, which
                                    // would otherwise leave startup stuck on this spinner.
                                    val valid =
                                        try {
                                            authApiClient.validateToken().valid
                                        } catch (e: Throwable) {
                                            Logger.withTag("session").w(e) { "startup validation failed → login" }
                                            false
                                        }
                                    if (valid) {
                                        session.markLoggedIn()
                                    } else {
                                        // No cookie, or one the server refused: fall through to login without
                                        // a notice — the page cannot tell "never signed in" from "expired".
                                        session.logout()
                                    }
                                }
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                            SessionStatus.AUTHENTICATED -> AuthenticatedApp(session, koinInject())
                            SessionStatus.UNAUTHENTICATED -> BootstrapGate()
                        }

                        PosNotificationHost(
                            notification = currentNotification,
                            onDismiss = globalOverlayViewModel::dismissCurrent,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pre-auth gate (Terminal Phase RB3 Part A). On a freshly-deployed server (`isInitialized=false`)
 * shows the owner/shop bootstrap wizard BEFORE login; once initialized (or on a probe failure)
 * falls through to the login screen. Only reached when there is no session (no or rejected refresh
 * cookie), so it never delays a returning admin.
 */
@Composable
private fun BootstrapGate() {
    val vm: BootstrapSetupViewModel = koinViewModel()
    LaunchedEffect(Unit) { vm.evaluateBootstrap() }
    when (vm.state.status) {
        BootstrapStatus.PROBING ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        BootstrapStatus.NEEDS_BOOTSTRAP -> BootstrapSetupScreen(vm)
        BootstrapStatus.INITIALIZED -> AdminLoginScreen()
    }
}

/** Opens the destination a loaded page's URL fragment names, on top of the start destination. A bad one is ignored. */
private fun openFragmentDestination(
    nav: NavHostController,
    fragment: String,
) {
    val route = AdminUrlSlugs.toRoute(fragment) ?: return
    try {
        nav.navigate(route)
    } catch (e: IllegalArgumentException) {
        Logger.withTag("nav").w(e) { "ignoring unknown URL fragment '$fragment'" }
    }
}

/** Fraction of the phone's width the navigation drawer takes (capped at 360dp). */
private const val PHONE_DRAWER_WIDTH_FRACTION = 0.85f

@OptIn(ExperimentalBrowserHistoryApi::class)
@Composable
private fun AuthenticatedApp(
    session: SessionState,
    tokenStore: TokenStore = koinInject(),
) {
    val nav = rememberNavController()
    // One guard for the whole authenticated app: forms register as dirty sources, every leave asks it.
    val leaveGuard = remember { LeaveGuard() }
    val current by nav.currentBackStackEntryAsState()
    // Open filter panels and their drafts, kept ABOVE the compact/wide shell switch (like the guard):
    // crossing 600dp used to rebuild every destination, and a panel looked up here by its screen key survived
    // that. The NavHost is now moved between the shells instead (see `navHost` below), so this is a second
    // line rather than the only one. Remembered inside the authenticated app, so a sign-out discards it. A real navigation (the
    // route changes, a resize does not) drops every panel except the screen now showing.
    val filterPanelStore = remember { PosFilterPanelStore() }
    // Any sign-out (manual or forced) disposes the authenticated app: drop the hidden print frame and the blob
    // URL of the last printed document with it, instead of leaving them in the page for the next user.
    DisposableEffect(Unit) { onDispose { PdfPrint.releaseNow() } }
    val currentRoute = current?.destination?.route
    LaunchedEffect(currentRoute) { FilterPanelKeys.onRouteChanged(filterPanelStore, currentRoute) }

    // Gives every destination a URL fragment, pushes browser history as the back stack grows, and
    // restores the destination on reload. Bound inside AuthenticatedApp rather than AdminApp so a
    // deep link opened while signed out still lands on the login/bootstrap gate — the binding does
    // not exist until the session is AUTHENTICATED.
    //
    // The fragment is a short readable slug (`#document-detail/42`, see `AdminUrlSlugs`), not the library's
    // default route class name. A custom `getBackStackEntryRoute` hands URL parsing back to us, so the
    // incoming fragment is read here: once, before the binding starts, because the binding rewrites the
    // fragment to the current destination at once. A fragment edited by hand in the address bar of a
    // loaded page is not followed (reload it); hashchange also fires on Back/Forward, where acting on it
    // would fight the history the binding restores.
    //
    // The call suspends for as long as the binding lives (it collects popstate + the back stack),
    // so LaunchedEffect is the right host: it cancels when the session ends and this leaves the
    // composition. `nav` is remembered above the compact/wide layout branch, so switching layouts
    // does not rebind. The NavHost below sets `navController.graph` during composition, which is
    // before this effect is dispatched, so the initial fragment always finds a graph to resolve.
    // Declared above the binding on purpose: effects run in order, and the guard's capture-phase
    // `popstate` listener must be registered before the binding's, or it cannot intercept Back/Forward.
    DisposableEffect(leaveGuard) {
        val uninstall = installBrowserLeaveGuard(leaveGuard)
        onDispose { uninstall() }
    }
    LaunchedEffect(nav) {
        openFragmentDestination(nav, window.location.hash)
        nav.bindToBrowserNavigation(getBackStackEntryRoute = AdminUrlSlugs::slugOf)
    }

    val firstTerminalVm: FirstTerminalSetupViewModel = koinViewModel()
    val debtorDetailVm: DebtorDetailViewModel = koinViewModel()
    val firstTerminalState = firstTerminalVm.state
    val canManage = tokenStore.canManageProducts

    // Probe once per authenticated session for "fresh server, no terminal yet".
    LaunchedEffect(Unit) { if (canManage) firstTerminalVm.evaluateFirstRun() }

    // One-shot auto-navigation into the skippable flow when the probe finds no terminal.
    LaunchedEffect(firstTerminalState.needsFirstTerminal, firstTerminalState.autoPrompted) {
        if (canManage && firstTerminalState.needsFirstTerminal && !firstTerminalState.autoPrompted) {
            firstTerminalVm.markAutoPrompted()
            nav.navigate(FirstTerminalSetupRoute)
        }
    }

    val keyValueStore: KeyValueStore = koinInject()
    val scope = rememberCoroutineScope()

    // "Report a problem" is launched from the user menu, which sits in the sidebar or the drawer and so is
    // rebuilt when the window crosses 600dp. Its open flag lives here instead, above the shell switch, and the
    // one dialog is hosted outside it below, so an open report and its draft survive a resize (R9f).
    val problemReport = remember { ReportProblemLauncher() }

    // toRoute<SettingsRoute>() throws when the current destination is something else, so the
    // hasRoute guard is load-bearing, not defensive.
    val onSettings = current?.destination?.hasRoute(SettingsRoute::class) == true
    val currentSection = if (onSettings) current?.toRoute<SettingsRoute>()?.section else null
    val policy = LocalAccessPolicy.current

    val navigateTo: (Any) -> Unit = { route ->
        leaveGuard.requestLeave {
            nav.navigate(route) {
                launchSingleTop = true
                popUpTo(DashboardRoute)
            }
        }
    }
    val signOut: AdminSignOut = koinInject()
    // Resets naturally on sign-out: AuthenticatedApp leaves composition once AdminSignOut's
    // `finally` completes, so this composable (and the flag) is torn down with it.
    var signingOut by remember { mutableStateOf(false) }
    val signOutNow: () -> Unit = signOutNow@{
        // A double-click on "Log out" must not launch AdminSignOut twice.
        if (signingOut) return@signOutNow
        signingOut = true
        // Signing out is an in-memory flip, not a reload, so without this the signed-out user's
        // fragment survives into the next sign-in on the same tab and the rebound controller
        // navigates straight back into their last destination.
        window.location.hash = ""
        firstTerminalVm.reset()
        debtorDetailVm.reset()
        leaveGuard.clear()
        // Never `session.logout()` alone: the refresh token is an HttpOnly cookie that survives it,
        // so a reload would sign straight back in. AdminSignOut ends the server session first.
        //
        // `scope` belongs to this composable and is cancelled when it leaves composition — which
        // only happens once the local sign-out in AdminSignOut's `finally` has run, i.e. after the
        // server call finished, failed or timed out. A concurrent failed refresh can sign out
        // earlier and cancel it; the `finally` still runs, and that server session is gone anyway.
        scope.launch { signOut() }
    }

    // Signing out with unsaved edits asks first; a forced sign-out (rejected refresh) cannot, and
    // goes straight through `session.logout()`.
    val onLogoutAction: () -> Unit = { leaveGuard.requestLeave(signOutNow) }
    val onLanguageSelected: (Language) -> Unit = { language ->
        selectAdminLanguage(keyValueStore, language)
    }

    // Resolved here, where str() is callable: onDenied fires from a LaunchedEffect, which is not a
    // composable scope.
    val overlay = LocalGlobalOverlayViewModel.current
    val deniedTitle = str(AdminStringKeys.NavAccessDeniedTitle)
    val deniedMessage = str(AdminStringKeys.NavAccessDenied)

    // `inclusive = true` so the refused destination does not linger on the back stack: a plain
    // popUpTo would leave it one Back press away, and the history binding would keep its fragment
    // in the address bar.
    //
    // The toast is what stops the redirect being invisible. Bouncing a hand-typed URL silently is
    // defensible; bouncing a click the app itself offered is not, and this says why either way.
    val onDenied: () -> Unit = {
        overlay.showError(title = deniedTitle, message = deniedMessage)
        nav.navigate(DashboardRoute) {
            launchSingleTop = true
            popUpTo(DashboardRoute) { inclusive = true }
        }
    }

    // `remember`ed because NavGroupExpansion holds Compose snapshot state: a fresh instance per
    // recomposition would re-seed from localStorage every frame and lose in-session toggles.
    val expansion = remember(keyValueStore) { NavGroupExpansion(keyValueStore) }

    // Eleven routes — detail and editor screens — belong to no nav item, so a bare lookup
    // returns null for them and every group would collapse WHILE the operator is working inside
    // one. `groupForDestination` maps those; the sticky fallback then covers anything added
    // later and not yet mapped.
    //
    // The fallback is the belt, not the braces: on a cold start — a bookmarked deep link, or an
    // F5, which is how this app navigates — there IS no previous group, so the map is the only
    // thing that works. Both are needed. Deliberately NOT persisted: a stored "last group"
    // would make a fresh deep link inherit context from a session that has ended.
    var lastKnownGroup by remember { mutableStateOf<AdminNavGroup?>(null) }
    val resolvedGroup = AdminNavItem.groupForDestination(current?.destination)
    LaunchedEffect(resolvedGroup) { if (resolvedGroup != null) lastKnownGroup = resolvedGroup }
    val activeGroup = resolvedGroup ?: lastKnownGroup
    // Read by the one movable NavHost below, which outlives the composition that created it.
    val currentCanManage by rememberUpdatedState(canManage)
    val currentFirstTerminalState by rememberUpdatedState(firstTerminalState)
    val currentOnDenied by rememberUpdatedState(onDenied)

    CompositionLocalProvider(LocalLeaveGuard provides leaveGuard, LocalPosFilterPanelStore provides filterPanelStore) {
        ProvideWindowSizeClass {
            val windowSize = LocalWindowSizeClass.current

            // null = the user has not chosen, so the window size decides. Once they toggle, their
            // choice sticks across reloads and stops tracking the window.
            var storedCollapsed by remember {
                mutableStateOf(keyValueStore.getString(KEY_SIDEBAR_COLLAPSED)?.toBooleanStrictOrNull())
            }
            val collapsed = storedCollapsed ?: !windowSize.isAtLeastExpanded()

            // ONE NavHost for both shells, moved between them rather than recreated: crossing 600dp (a resize,
            // a tablet rotation) keeps every destination's composition — scroll positions, dialogs, an open
            // PDF at its page and zoom — and does not re-run a screen's load. Both call sites below sit in
            // this same composition (ModalNavigationDrawer's content is a plain Box), which movable content needs.
            val navHost =
                remember(nav, firstTerminalVm, tokenStore) {
                    movableContentOf {
                        AdminNavHost(nav, currentCanManage, firstTerminalVm, currentFirstTerminalState, tokenStore) { currentOnDenied() }
                    }
                }

            if (windowSize.isCompact()) {
                // Remembered per compact period, not above the shell switch: M3 re-derives an existing drawer
                // state's value from its last pixel offset (the anchor closest to it) when the drawer is laid out
                // again, so a state carried across a wide period could come back OPEN although nobody opened it
                // (browser passes 2 and 3, N3). A fresh state has no offset and always starts closed.
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                // Below 600dp there is no room for a permanent sidebar, so the same one moves into a
                // drawer reached from a hamburger. Collapsed mode is pointless there — the drawer is
                // already the "hidden" state — so it renders expanded and the toggle just closes it.
                //
                // The document scanner's camera <video> is a DOM element and draws above the canvas, including
                // above this drawer and its scrim, so an open drawer over the scanner would be invisible.
                // currentValue as well as targetValue: the target is already Closed while the drawer is still
                // sliding shut, and the video must not reappear over it before it has left.
                if (drawerState.targetValue == DrawerValue.Open || drawerState.currentValue == DrawerValue.Open) {
                    BlockDomOverlayWhileVisible()
                }
                val topBarActions = remember { AdminTopBarActions() }
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(modifier = Modifier.fillMaxWidth(PHONE_DRAWER_WIDTH_FRACTION).widthIn(max = 360.dp)) {
                            AdminSidebar(
                                inDrawer = true,
                                currentDestination = current?.destination,
                                currentSection = currentSection,
                                tokenStore = tokenStore,
                                collapsed = false,
                                expansion = expansion,
                                activeGroup = activeGroup,
                                onToggleCollapse = { scope.launch { drawerState.close() } },
                                onNavigate = { route ->
                                    scope.launch { drawerState.close() }
                                    navigateTo(route)
                                },
                                onLogout = onLogoutAction,
                                onLanguageSelected = onLanguageSelected,
                                onReportProblem = {
                                    // The dialog opens once the drawer is closed, even if a gesture interrupts the animation.
                                    scope.launch { problemReport.openAfter { drawerState.close() } }
                                },
                            )
                        }
                    },
                ) {
                    CompositionLocalProvider(LocalAdminTopBarActions provides topBarActions) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PosIconButton(
                                    icon = Icons.Default.Menu,
                                    contentDescription = str(AdminStringKeys.NavOpenMenu),
                                    onClick = { scope.launch { drawerState.open() } },
                                    size = 48.dp,
                                    iconSize = 24.dp,
                                )
                                AdminNavItem.titleKeyForDestination(current?.destination, currentSection, policy)?.let { titleKey ->
                                    Text(
                                        text = str(titleKey),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } ?: Spacer(Modifier.weight(1f))
                                topBarActions.content?.invoke()
                            }
                            Box(Modifier.weight(1f)) {
                                navHost()
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    AdminSidebar(
                        currentDestination = current?.destination,
                        currentSection = currentSection,
                        tokenStore = tokenStore,
                        collapsed = collapsed,
                        expansion = expansion,
                        activeGroup = activeGroup,
                        onToggleCollapse = {
                            val next = !collapsed
                            storedCollapsed = next
                            keyValueStore.setString(KEY_SIDEBAR_COLLAPSED, next.toString())
                        },
                        onNavigate = navigateTo,
                        onLogout = onLogoutAction,
                        onLanguageSelected = onLanguageSelected,
                        onReportProblem = problemReport::open,
                    )
                    Box(Modifier.weight(1f)) {
                        navHost()
                    }
                }
            }
            // Hosted outside the branch for both shells, so a window resize neither closes it nor drops its draft.
            if (problemReport.isOpen) {
                ReportProblemDialog(
                    screen = reportScreenName(current?.destination, currentSection),
                    onDismiss = problemReport::close,
                )
            }
        }

        // Outside the compact/wide branch, like the problem dialog, so a resize cannot strand an open prompt.
        UnsavedChangesHost(leaveGuard)
    }
}

/**
 * Renders [content] only when [item]'s gate passes, otherwise redirects to the dashboard.
 *
 * Before browser-history binding, hiding a sidebar item was sufficient to hide its destination —
 * the sidebar was the only way in. A URL fragment is a second door, so the same gate has to run
 * here too, or the client half of CLAUDE.md rule 8 is missing for every destination reachable by
 * URL. The server still enforces every endpoint; this stops the screen being presented at all.
 */
@Composable
private fun GatedDestination(
    item: AdminNavItem,
    tokenStore: TokenStore,
    onDenied: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Delegates to shared-ui's `RequireCapability`, which composeApp's three destinations use too —
    // one gating mechanism across both clients rather than two implementations of one rule.
    //
    // The staleness memo this used to carry is gone with it: `LocalAccessPolicy` is provided at the
    // composition root keyed on `SessionState.capabilityEpoch`, so a silent token refresh
    // recomposes the subtree and the gate re-asks. Keeping a second epoch-keyed memo here would be
    // the same answer computed twice.
    //
    // An item declaring NO capability is open to every signed-in principal (the dashboard), which
    // `RequireCapability` cannot express — it takes one capability, not a nullable one.
    val required = item.requiredCapability
    if (required == null) {
        content()
    } else {
        RequireCapability(required, onDenied = onDenied, content = content)
    }
}

/**
 * The authenticated destination graph. Extracted so the compact (drawer) and wide (permanent
 * sidebar) layouts share one definition instead of duplicating every route.
 *
 * Every destination that the sidebar gates is wrapped in [GatedDestination] with the same
 * [AdminNavItem], so one mechanism owns role visibility for both doors into a screen. Three routes
 * have no nav item of their own and borrow the gate of the only place they can be entered from:
 * `FloorPlanEditorRoute` from [AdminNavItem.TABLES] and `FirstTerminalSetupRoute` from
 * [AdminNavItem.TERMINALS]. `DashboardRoute` stays ungated — it is open to everyone who can sign in.
 *
 * `KassenbuchRoute` is gated by [AdminNavItem.KASSENBUCH] like any other sidebar destination. It was
 * briefly ungated, inherited from the `KassenbuchEntryRoute` it replaced — defensible while it showed
 * one dashboard entry, not once it opened the whole cash book, every book and every period.
 */
@Composable
private fun AdminNavHost(
    nav: NavHostController,
    canManage: Boolean,
    firstTerminalVm: FirstTerminalSetupViewModel,
    firstTerminalState: FirstTerminalSetupState,
    tokenStore: TokenStore,
    onDenied: () -> Unit,
) {
    // Every in-screen navigation goes through the leave guard, so a form with unsaved edits asks
    // before it is left. `onDenied` (a capability bounce), the first-terminal auto-prompt and the
    // scanner dev hook deliberately use the raw controller.
    val leaveGuard = LocalLeaveGuard.current
    val go: (Any) -> Unit = { route ->
        leaveGuard.requestLeave {
            nav.navigate(route)
            // Marks the entry as opened from inside the app, so its Back returns to where it came from.
            nav.currentBackStackEntry?.savedStateHandle?.set(DetailBackTarget.REACHED_IN_APP, true)
        }
    }

    // The closing dialog's way to the setting it asks for: the Kassenbuch with the book management open.
    // Offered only to someone who can open the Kassenbuch and designate a till (the picker needs TERMINAL_MANAGE).
    val signingSettingsAction: (() -> Unit)? =
        if (AdminNavItem.KASSENBUCH.isVisibleTo(tokenStore) && tokenStore.holds(Capability.TERMINAL_MANAGE)) {
            { go(KassenbuchRoute(manageBooks = true)) }
        } else {
            null
        }
    val back: () -> Unit = {
        leaveGuard.requestLeave {
            val current = nav.currentBackStackEntry
            val parent =
                DetailBackTarget.parentToOpen(
                    current = current?.destination,
                    previous = nav.previousBackStackEntry?.destination,
                    reachedInApp = current?.savedStateHandle?.get<Boolean>(DetailBackTarget.REACHED_IN_APP) == true,
                )
            if (parent == null || current == null) {
                nav.popBackStack()
            } else {
                // A deep-linked detail (loaded straight from its URL): Back opens its list, not Übersicht.
                nav.navigate(parent.route) { popUpTo(current.destination.id) { inclusive = true } }
            }
        }
    }

    // Publish the destination so every reported error records the screen it happened on, without
    // each watcher call site having to pass it.
    val currentRoute =
        nav
            .currentBackStackEntryAsState()
            .value
            ?.destination
            ?.route
            ?.substringAfterLast('.')

    // Dev-only door to the unwired scanner: `?scanner=dev` on the panel URL, once signed in.
    LaunchedEffect(Unit) {
        if (window.location.search.contains("scanner=dev")) nav.navigate(ScannerDevRoute)
    }
    CompositionLocalProvider(LocalScreenName provides currentRoute) {
        NavHost(nav, startDestination = DashboardRoute) {
            composable<DashboardRoute> {
                AdminDashboardScreen(
                    showAuthorizations = tokenStore.holds(Capability.SESSION_READ),
                    showFiscalDay = showsFiscalDayCards(tokenStore.accessPolicy),
                    showOpenWork =
                        tokenStore.holds(Capability.ACCOUNTING_READ) &&
                            tokenStore.holds(Capability.INVOICE_READ),
                    showTseHealth =
                        tokenStore.holds(Capability.TSE_ADMIN) &&
                            tokenStore.holds(Capability.TSE_QUEUE_ADMIN),
                    showRestoredPaths = tokenStore.holds(Capability.BACKUP_RESTORE),
                    zBonClosingAccess = ZBonClosingAccess.from { capability -> tokenStore.holds(capability) },
                    onOpenZBon = { zbonId -> go(ZBonDetailRoute(zbonId)) },
                    onOpenSigningSettings = signingSettingsAction,
                    onOpenDailyReport =
                        if (showDailyReportLink(tokenStore.accessPolicy)) {
                            { go(DailyReportRoute) }
                        } else {
                            null
                        },
                    showsShopFigures = tokenStore.holds(Capability.REPORT_ALL_READ),
                    showRecentCashEntries = showRecentCashEntries(tokenStore.accessPolicy),
                    onOpenInvoices = { bucket -> go(InvoicesRoute(bucket)) },
                    onOpenAllocations = { go(AllocationsRoute) },
                    // Same treatment as the checklist's TSE row (topContent): a cashier sees the latest
                    // entries but is not offered their click, because the cash book is MANAGER-only.
                    // Offering it would navigate into a gated route that bounces straight back.
                    onEntryClick =
                        if (AdminNavItem.KASSENBUCH.isVisibleTo(tokenStore)) {
                            { headerId -> go(KassenbuchRoute(headerId)) }
                        } else {
                            null
                        },
                    onOpenKassenbuch =
                        if (AdminNavItem.KASSENBUCH.isVisibleTo(tokenStore)) {
                            { go(KassenbuchRoute()) }
                        } else {
                            null
                        },
                    // Replaces FirstTerminalBanner: the checklist covers the terminal gap plus the
                    // five other things that can block trading, and emits nothing once all pass.
                    showChecklist = canManage,
                    // MANAGER+ sees the card, but commissioning a TSE is ADMIN-only — the row
                    // renders unclickable for a manager instead of bouncing off GatedDestination.
                    canAdministerTse = tokenStore.canAdministerTse,
                    onChecklistNavigate = { item ->
                        when (item.destination) {
                            // Straight to the guided first-terminal flow when nothing is
                            // paired yet — that is what the operator actually needs, not the
                            // empty Terminals list.
                            ChecklistDestination.TERMINALS ->
                                go(
                                    if (firstTerminalState.needsFirstTerminal) {
                                        FirstTerminalSetupRoute
                                    } else {
                                        TerminalsRoute
                                    },
                                )
                            ChecklistDestination.TSE -> go(TseAdminRoute)
                            ChecklistDestination.PRODUCTS -> go(ProductsRoute)
                            ChecklistDestination.TABLES -> go(TablesRoute)
                            ChecklistDestination.SETTINGS ->
                                go(
                                    SettingsRoute(
                                        item.settingsSection?.name
                                            ?: SettingsRoute.DEFAULT_SECTION,
                                    ),
                                )
                            // A row the operator's role cannot act on anywhere; the card renders
                            // it without a click affordance, so this branch is unreachable.
                            null -> Unit
                        }
                    },
                )
            }
            composable<KassenbuchRoute> { backStackEntry ->
                // The gate wraps only the screen, so the deep-linked headerId is unaffected: anyone the
                // gate admits still lands on the preselected booking.
                val route = backStackEntry.toRoute<KassenbuchRoute>()
                GatedDestination(AdminNavItem.KASSENBUCH, tokenStore, onDenied) {
                    KassenbuchScreen(
                        preselectedHeaderId = route.headerId,
                        openBookManagement = route.manageBooks,
                        onOpenDocument = { documentId -> go(DocumentDetailRoute(documentId)) },
                    )
                }
            }
            composable<ZBonListRoute> {
                GatedDestination(AdminNavItem.Z_BONS, tokenStore, onDenied) {
                    ZBonListScreen(
                        onZBonClick = { zbonId -> go(ZBonDetailRoute(zbonId)) },
                        onOpenSigningSettings = signingSettingsAction,
                        access = ZBonClosingAccess.from { capability -> tokenStore.holds(capability) },
                    )
                }
            }
            composable<DocumentsRoute> {
                GatedDestination(AdminNavItem.DOCUMENTS, tokenStore, onDenied) {
                    DocumentsScreen(
                        onOpenDocument = { id -> go(DocumentDetailRoute(id)) },
                        onOpenInvoices = { bucket -> go(InvoicesRoute(bucket)) },
                        onOpenAllocations = { go(AllocationsRoute) },
                    )
                }
            }
            composable<DocumentDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DocumentDetailRoute>()
                GatedDestination(AdminNavItem.DOCUMENTS, tokenStore, onDenied) {
                    DocumentDetailScreen(
                        documentId = route.documentId,
                        onBack = { back() },
                        onCapture = { id -> go(InvoiceCaptureRoute(id)) },
                        onOpenInvoice = { id -> go(InvoiceEditRoute(id)) },
                    )
                }
            }
            composable<ScannerDevRoute> {
                // Same gate as the Belege screens the scanner will eventually feed.
                GatedDestination(AdminNavItem.DOCUMENTS, tokenStore, onDenied) {
                    ScannerScreen(
                        onResult = { file -> println("scanner result: ${file.fileName} ${file.mimeType} ${file.bytes.size} bytes") },
                        onCancel = { back() },
                    )
                }
            }
            composable<InvoicesRoute> { entry ->
                val route = entry.toRoute<InvoicesRoute>()
                GatedDestination(AdminNavItem.INVOICES, tokenStore, onDenied) {
                    InvoicesScreen(
                        initialBucket = route.bucket,
                        onCapture = { id -> go(InvoiceCaptureRoute(id)) },
                        onOpenInvoice = { id -> go(InvoiceEditRoute(id)) },
                        onOpenAllocations = { go(AllocationsRoute) },
                    )
                }
            }
            composable<SalesInvoicesRoute> {
                GatedDestination(AdminNavItem.SALES_INVOICES, tokenStore, onDenied) {
                    SalesInvoicesScreen(
                        // Reuse the Belege detail view rather than adding a second PDF surface: it already
                        // shows the archived document beside its data.
                        onOpenPdf = { documentId -> go(DocumentDetailRoute(documentId)) },
                        onCreate = { go(SalesInvoiceEditorRoute) },
                        onEdit = { invoiceId -> go(SalesInvoiceEditRoute(invoiceId)) },
                        onOpenAllocations = { go(AllocationsRoute) },
                    )
                }
            }
            composable<SalesInvoiceEditorRoute> {
                GatedDestination(AdminNavItem.SALES_INVOICES, tokenStore, onDenied) {
                    SalesInvoiceEditorScreen(invoiceId = null, onBack = { back() })
                }
            }
            composable<SalesInvoiceEditRoute> { backStackEntry ->
                GatedDestination(AdminNavItem.SALES_INVOICES, tokenStore, onDenied) {
                    val route = backStackEntry.toRoute<SalesInvoiceEditRoute>()
                    SalesInvoiceEditorScreen(invoiceId = route.invoiceId, onBack = { back() })
                }
            }
            composable<DebtorsRoute> {
                GatedDestination(AdminNavItem.DEBTORS, tokenStore, onDenied) {
                    DebtorsScreen(onCreate = { go(DebtorCreateRoute) })
                }
            }
            // Kept although the list no longer navigates here: it edits in its own pane now. This
            // stays reachable as a deep link — a browser URL naming one debtor — and is the same
            // screen DebtorCreateRoute renders with a null id.
            composable<DebtorDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DebtorDetailRoute>()
                GatedDestination(AdminNavItem.DEBTORS, tokenStore, onDenied) {
                    DebtorDetailScreen(
                        debtorId = route.debtorId,
                        onBack = { back() },
                    )
                }
            }
            composable<DebtorCreateRoute> {
                GatedDestination(AdminNavItem.DEBTORS, tokenStore, onDenied) {
                    DebtorDetailScreen(
                        debtorId = null,
                        onBack = { back() },
                        // NEW-20: the new debtor replaces the create form in the back stack, so Back
                        // returns to the list. Raw navigate: the save re-pinned pristine (rule 11d).
                        onCreated = { id ->
                            nav.navigate(DebtorDetailRoute(id)) { popUpTo<DebtorCreateRoute> { inclusive = true } }
                        },
                    )
                }
            }
            composable<AllocationsRoute> {
                GatedDestination(AdminNavItem.ALLOCATIONS, tokenStore, onDenied) {
                    AllocationsScreen(onOpenInvoices = { bucket -> go(InvoicesRoute(bucket)) })
                }
            }
            composable<BankAccountsRoute> {
                GatedDestination(AdminNavItem.BANK, tokenStore, onDenied) {
                    BankAccountsScreen()
                }
            }
            composable<ContractsRoute> {
                GatedDestination(AdminNavItem.CONTRACTS, tokenStore, onDenied) {
                    ContractsScreen(onOpen = { id -> go(ContractDetailRoute(id)) })
                }
            }
            composable<ContractDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ContractDetailRoute>()
                GatedDestination(AdminNavItem.CONTRACTS, tokenStore, onDenied) {
                    ContractDetailScreen(
                        contractId = route.contractId,
                        onBack = { back() },
                    )
                }
            }
            composable<SuppliersRoute> {
                GatedDestination(AdminNavItem.SUPPLIERS, tokenStore, onDenied) {
                    SuppliersScreen(onOpen = { id -> go(SupplierDetailRoute(id)) })
                }
            }
            composable<SupplierDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SupplierDetailRoute>()
                GatedDestination(AdminNavItem.SUPPLIERS, tokenStore, onDenied) {
                    SupplierDetailScreen(
                        supplierId = route.supplierId,
                        onBack = { back() },
                    )
                }
            }
            composable<InvoiceCaptureRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<InvoiceCaptureRoute>()
                GatedDestination(AdminNavItem.DOCUMENTS, tokenStore, onDenied) {
                    InvoiceCaptureScreen(documentId = route.documentId, onBack = { back() }, onAssign = { go(AllocationsRoute) }, onOpenInvoices = { bucket ->
                        go(InvoicesRoute(bucket))
                    })
                }
            }
            composable<InvoiceEditRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<InvoiceEditRoute>()
                GatedDestination(AdminNavItem.INVOICES, tokenStore, onDenied) {
                    InvoiceEditScreen(invoiceId = route.invoiceId, onBack = { back() }, onAssign = { go(AllocationsRoute) }, onOpenInvoices = { bucket ->
                        go(InvoicesRoute(bucket))
                    })
                }
            }
            composable<ZBonDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ZBonDetailRoute>()
                GatedDestination(AdminNavItem.Z_BONS, tokenStore, onDenied) {
                    ZBonHubScreen(
                        zbonId = route.zbonId,
                        onBack = { back() },
                    )
                }
            }
            composable<ProductsRoute> {
                GatedDestination(AdminNavItem.PRODUCTS, tokenStore, onDenied) { ProductScreen() }
            }
            composable<TablesRoute> {
                GatedDestination(AdminNavItem.TABLES, tokenStore, onDenied) {
                    TablesScreen(onEditFloorPlan = { go(FloorPlanEditorRoute) })
                }
            }
            composable<FloorPlanEditorRoute> {
                GatedDestination(AdminNavItem.TABLES, tokenStore, onDenied) {
                    FloorPlanEditorScreen(onBack = { back() })
                }
            }
            composable<StaffRoute> {
                GatedDestination(AdminNavItem.STAFF, tokenStore, onDenied) { StaffScreen() }
            }
            composable<AnalyticsRoute> {
                GatedDestination(AdminNavItem.ANALYTICS, tokenStore, onDenied) { ReportsScreen() }
            }
            composable<DailyReportRoute> {
                GatedDestination(AdminNavItem.DAILY_REPORT, tokenStore, onDenied) { DailyReportScreen() }
            }
            composable<TseAdminRoute> {
                GatedDestination(AdminNavItem.TSE, tokenStore, onDenied) {
                    TseAdminScreen(onOpenTerminals = { go(TerminalsRoute) })
                }
            }
            composable<TseFindingsRoute> {
                GatedDestination(AdminNavItem.TSE_FINDINGS, tokenStore, onDenied) { TseFindingsScreen() }
            }
            composable<TerminalsRoute> {
                GatedDestination(AdminNavItem.TERMINALS, tokenStore, onDenied) { TerminalsScreen() }
            }
            composable<PermissionGroupsRoute> {
                GatedDestination(AdminNavItem.PERMISSIONS, tokenStore, onDenied) { PermissionGroupsScreen() }
            }
            composable<ScheduledJobsRoute> {
                GatedDestination(AdminNavItem.SCHEDULED_JOBS, tokenStore, onDenied) {
                    ScheduledJobsScreen(
                        isCompact = false,
                        // The phone top bar already names the screen (same condition as the drawer at ~440).
                        showTitle = !LocalWindowSizeClass.current.isCompact(),
                        touchTargetSize = 48.dp,
                    )
                }
            }
            composable<UpdateRoute> {
                GatedDestination(AdminNavItem.UPDATE, tokenStore, onDenied) { UpdateScreen() }
            }
            composable<AuditLogRoute> {
                GatedDestination(AdminNavItem.AUDIT_LOG, tokenStore, onDenied) { AuditLogDestination() }
            }
            composable<SettingsRoute> { backStackEntry ->
                GatedDestination(AdminNavItem.SETTINGS, tokenStore, onDenied) {
                    SettingsScreen(section = backStackEntry.toRoute<SettingsRoute>().toSection())
                }
            }
            composable<VouchersRoute> {
                GatedDestination(AdminNavItem.VOUCHERS, tokenStore, onDenied) { VoucherScreen() }
            }
            composable<PrinterRoute> {
                GatedDestination(AdminNavItem.PRINTER, tokenStore, onDenied) { PrinterSection() }
            }
            composable<KassenmeldungRoute> {
                GatedDestination(AdminNavItem.KASSENMELDUNG, tokenStore, onDenied) {
                    KassenmeldungSection()
                }
            }
            composable<DataExportRoute> {
                GatedDestination(AdminNavItem.DATA_EXPORT, tokenStore, onDenied) { DataExportSection() }
            }
            composable<CustomExportRoute> {
                // `koinViewModel()` inside the composable, not hoisted above the NavHost: that scopes
                // the view model to this back-stack entry, so leaving the screen drops the draft
                // instead of carrying it into the next visit.
                GatedDestination(AdminNavItem.CUSTOM_EXPORT, tokenStore, onDenied) { CustomExportScreen() }
            }
            composable<FirstTerminalSetupRoute> {
                GatedDestination(AdminNavItem.TERMINALS, tokenStore, onDenied) {
                    // A deep link reaches this route although terminals exist: probe (idempotent)
                    // and hand over to Terminals rather than offer a "first" terminal (FO-09).
                    LaunchedEffect(Unit) { firstTerminalVm.evaluateFirstRun() }
                    LaunchedEffect(firstTerminalVm.state) {
                        if (firstTerminalVm.state.shouldLeaveWizardForTerminals()) {
                            nav.navigate(TerminalsRoute) {
                                popUpTo<FirstTerminalSetupRoute> { inclusive = true }
                            }
                        }
                    }
                    FirstTerminalSetupScreen(
                        viewModel = firstTerminalVm,
                        onDismiss = { back() },
                    )
                }
            }
        }
    }
}
