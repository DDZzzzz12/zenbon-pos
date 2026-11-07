package org.example.project.ui.screens.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.example.project.core.auth.LocalPermissionManager
import org.example.project.core.auth.LocalTemporarySessionManager
import org.example.project.core.browser.ServerBuildWatcher
import org.example.project.core.browser.ServerUpdateBanner
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.LaunchKeys
import org.example.project.core.presentation.strings.keys.MainScreenKeys
import org.example.project.core.presentation.strings.keys.TemporarySessionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.*
import org.example.project.core.utils.BackofficeUrl
import org.example.project.core.utils.LocalNotificationViewModel
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isAtLeastMedium
import org.example.project.core.utils.isCompact
import org.example.project.features.auth.presentation.components.ApprovalRequestDialog
import org.example.project.features.auth.presentation.components.TemporarySessionDialog
import org.example.project.features.auth.presentation.state.ApprovalDialogState
import org.example.project.features.auth.presentation.viewmodels.ApprovalRequestViewModel
import org.example.project.features.auth.presentation.viewmodels.TemporarySessionManager
import org.example.project.features.launch.presentation.TerminalColdResetBus
import org.example.project.features.launch.presentation.TerminalResetKind
import org.example.project.features.main.presentation.navigation.AppSettings
import org.example.project.features.main.presentation.navigation.Dashboard
import org.example.project.features.main.presentation.navigation.FinancialDocuments
import org.example.project.features.main.presentation.navigation.MainNavHost
import org.example.project.features.main.presentation.navigation.MainNotifications
import org.example.project.features.main.presentation.navigation.MainReservations
import org.example.project.features.main.presentation.navigation.MyProfile
import org.example.project.features.main.presentation.navigation.Reports
import org.example.project.features.main.presentation.viewmodels.MainViewModel
import org.example.project.features.notifications.presentation.components.NotificationPanel
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel
import org.example.project.ui.navigation.NavGroup
import org.example.project.ui.navigation.PosNavDestination
import org.example.project.ui.navigation.PosNavShell
import org.example.project.ui.navigation.rememberVisibleDestinations
import org.example.project.ui.screens.auth.components.TemporarySessionIndicator
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Main screen with adaptive navigation for POS system sections.
 *
 * Uses PosNavShell for responsive navigation:
 * - COMPACT (< 600dp): Bottom navigation bar
 * - MEDIUM (600-840dp): Navigation rail
 * - EXPANDED (> 840dp): Navigation sidebar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit,
    onBackOnDashboard: () -> Unit,
    viewModel: MainViewModel,
) {
    val windowSize = LocalWindowSizeClass.current
    val nestedNavController = rememberNavController()
    var isFullScreenRoute by remember { mutableStateOf(false) }
    val languageManager = LocalLanguageManager.current
    val currentLanguage by languageManager.currentLanguage

    val mainUiState by viewModel.uiState.collectAsState()

    // Create NotificationViewModel at MainScreen level to survive navigation changes
    val notificationViewModel: NotificationViewModel = koinViewModel()
    val notificationState by notificationViewModel.uiState.collectAsState()
    val authError by notificationViewModel.authError.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current

    // Approval Request ViewModel for manager-side approval workflow
    val approvalViewModel: ApprovalRequestViewModel = koinViewModel()
    val approvalUiState by approvalViewModel.uiState.collectAsState()

    // The station footer's "re-pair device" action. Same bus the Ktor auth plugin fires on a
    // terminal-auth failure, so there is exactly one teardown path back to pairing.
    val coldResetBus = koinInject<TerminalColdResetBus>()

    // "Backoffice öffnen": the web admin this server hosts, in the system browser. Compose's
    // LocalUriHandler covers desktop (Desktop.browse), Android (ACTION_VIEW) and the wasm preview;
    // if the platform cannot hand a URI to a browser (none installed, Android lock-task mode with
    // the launcher pinned) the address is shown so it can be typed on another device.
    val uriHandler = LocalUriHandler.current
    val localDb = koinInject<LocalDatabaseManager>()
    val backofficeUrl = remember(localDb) { BackofficeUrl.from(localDb.getApiBaseUrl()) }
    val backofficeTitle = str(LaunchKeys.openBackoffice)
    val backofficeFailed = str(LaunchKeys.backofficeOpenFailed, backofficeUrl)
    val onOpenBackoffice: () -> Unit = {
        // runCatching, not try/catch(Exception): on wasm a failed `window.open` surfaces as a
        // JsException, which is a Throwable but not an Exception, and would escape the catch.
        val opened = runCatching { uriHandler.openUri(backofficeUrl) }
        if (opened.isFailure) {
            overlay.showWarning(title = backofficeTitle, message = backofficeFailed)
        }
    }

    LaunchedEffect(Unit) {
        notificationViewModel.notificationEvents.collect { event ->
            when (event) {
                is NotificationUiEvent.ApprovalRequestReceived -> {
                    approvalViewModel.handleIncomingApprovalRequest(event.request)
                }
                else -> {}
            }
        }
    }

    // Create TemporarySessionManager at MainScreen level for privilege escalation
    val sessionManager: TemporarySessionManager = koinViewModel()
    val sessionState by sessionManager.uiState.collectAsState()

    // Track navigation changes for session auto-extension
    val navBackStackEntry by nestedNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    LaunchedEffect(currentRoute) {
        // Skip initial composition (when route is first set)
        if (currentRoute != null) {
            sessionManager.trackNavigationChange()
        }
    }

    // Create drawer controller implementation (kept for child screens via LocalDrawerController)
    val drawerController =
        remember {
            object : DrawerController {
                override fun toggleDrawer() {}

                override var isDrawerAvailable: Boolean = false
            }
        }

    NotificationStreamLifecycleEffect(notificationState.connectionState) {
        notificationViewModel.connectToNotifications(true)
    }

    LaunchedEffect(authError) {
        if (authError) {
            onLogout()
        }
    }

    // Track the loading notification ID so we dismiss the correct one
    var syncLoadingNotificationId by remember { mutableStateOf<String?>(null) }

    // Logout resets syncCompleted to false while this screen is still composed, which re-raises the
    // loading overlay just as the session ends; it must not outlive the screen onto the login screen.
    DisposableEffect(Unit) {
        onDispose {
            syncLoadingNotificationId?.let {
                overlay.dismissById(it)
                syncLoadingNotificationId = null
            }
        }
    }

    LaunchedEffect(mainUiState.syncCompleted) {
        if (!mainUiState.syncCompleted) {
            syncLoadingNotificationId =
                overlay.showLoading(
                    title = getString(MainScreenKeys.syncTitle, currentLanguage),
                    message = getString(MainScreenKeys.syncMessage, currentLanguage),
                )
        } else {
            syncLoadingNotificationId?.let {
                overlay.dismissById(it)
                syncLoadingNotificationId = null
            }
        }
    }

    // Show loading notification for temporary session authentication
    var sessionLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionState.isLoading) {
        if (sessionState.isLoading) {
            sessionLoadingId =
                overlay.showLoading(
                    title = getString(MainScreenKeys.authenticatingTitle, currentLanguage),
                    message = getString(MainScreenKeys.authenticatingMessage, currentLanguage),
                )
        } else {
            sessionLoadingId?.let {
                overlay.dismissById(it)
                sessionLoadingId = null
            }
        }
    }

    // A step-up that ran out (idle timeout, hard cap, or ended by the server): tell the operator why the
    // lent authority is gone and that a manager must sign in again. Same on the till and the station.
    LaunchedEffect(sessionState.approvalExpiredNotice) {
        if (sessionState.approvalExpiredNotice) {
            overlay.showWarning(
                title = getString(TemporarySessionKeys.approvalExpiredTitle, currentLanguage),
                message =
                    getString(
                        if (sessionState.approvalExpiredAtCap) {
                            TemporarySessionKeys.approvalExpiredMessage
                        } else {
                            TemporarySessionKeys.approvalIdleExpiredMessage
                        },
                        currentLanguage,
                    ),
            )
            sessionManager.consumeApprovalExpiredNotice()
        }
    }

    val permissionManager = LocalPermissionManager.current
    val employeeNumber by permissionManager.currentEmployeeNumber.collectAsState()

    // Shutdown dialog state (desktop only)
    var showShutdownDialog by remember { mutableStateOf(false) }

    // Provide DrawerController, NotificationViewModel, and TemporarySessionManager to child screens
    CompositionLocalProvider(
        LocalDrawerController provides drawerController,
        LocalNotificationViewModel provides notificationViewModel,
        LocalTemporarySessionManager provides sessionManager,
    ) {
        // Determine current destination from nav back stack route
        val currentDestination =
            remember(currentRoute) {
                when {
                    currentRoute == null -> PosNavDestination.DASHBOARD
                    currentRoute.contains("Dashboard") -> PosNavDestination.DASHBOARD
                    currentRoute.contains("MainReservations") || currentRoute.contains("Reservations") -> PosNavDestination.RESERVATIONS
                    currentRoute.contains("MainNotifications") || currentRoute.contains("Notifications") -> PosNavDestination.NOTIFICATIONS
                    currentRoute.contains("AppSettings") -> PosNavDestination.SETTINGS
                    currentRoute.contains("MyProfile") -> PosNavDestination.MY_PROFILE
                    else -> PosNavDestination.DASHBOARD // management screens show Dashboard as active
                }
            }

        val managementDests = rememberVisibleDestinations(NavGroup.MANAGEMENT)

        val activeManagementDest =
            remember(currentRoute) {
                when {
                    currentRoute == null -> null
                    currentRoute.contains("Reports") -> PosNavDestination.REPORTS
                    currentRoute.contains("FinancialDocuments") -> PosNavDestination.FINANCIAL_DOCS
                    else -> null
                }
            }

        // When elevated privileges are lost (e.g. a temporary manager session expires),
        // leave any management screen the now-downgraded role can no longer access and
        // return to the dashboard. Without this the screen stays mounted under the reduced
        // role and shows a blocking error dialog (e.g. Reports for a TERMINAL base role).
        LaunchedEffect(activeManagementDest, managementDests) {
            val active = activeManagementDest ?: return@LaunchedEffect
            if (active !in managementDests) {
                navigateToDestination(PosNavDestination.DASHBOARD, nestedNavController)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Busy = any screen other than the dashboard root is on top of the nested nav (an open
            // order, billing, reports ...); the banner reloads the page by itself only when idle.
            val buildWatcher = koinInject<ServerBuildWatcher>()
            val innerBusy by buildWatcher.innerBusy.collectAsState()
            ServerUpdateBanner(
                watcher = buildWatcher,
                busy = ServerBuildWatcher.isBusyRoute(currentRoute) || innerBusy,
            )
            Box(
                modifier = Modifier.fillMaxSize().weight(1f),
            ) {
                PosNavShell(
                    selectedDestination = currentDestination,
                    onDestinationSelected = { dest ->
                        navigateToDestination(dest, nestedNavController)
                    },
                    notificationUnreadCount = notificationState.unreadCount,
                    managementItems = managementDests,
                    onManagementItemClick = { dest -> navigateToDestination(dest, nestedNavController) },
                    sessionIndicator = {
                        TemporarySessionIndicator(
                            state = sessionState,
                            onExtendClick = { sessionManager.extendSession() },
                            onEndClick = { sessionManager.endSession() },
                            language = currentLanguage,
                            compact = windowSize.isAtLeastMedium(),
                        )
                    },
                    onLogout = onLogout,
                    onShutdown = { showShutdownDialog = true },
                    // A station's footer offers this instead of logout. The cold reset is the
                    // EXISTING teardown — TerminalLifecycleHandler clears the token store, user
                    // info, device token, terminal registration, PermissionManager and SessionState,
                    // then LaunchCoordinator.onColdReset() resolves back to Pairing.
                    onRepair = { coldResetBus.request(TerminalResetKind.COLD_OPERATOR_REPAIR) },
                    // Offered on the till too, and on both ungated — the panel has its own login.
                    onOpenBackoffice = onOpenBackoffice,
                    sessionState = sessionState,
                    sessionManager = sessionManager,
                    currentLanguage = currentLanguage,
                    showBottomBar = !isFullScreenRoute,
                ) {
                    MainNavHost(
                        navController = nestedNavController,
                        modifier = Modifier.fillMaxSize(),
                        onBackOnDashboard = onBackOnDashboard,
                        onNotificationsClick = {
                            navigateToDestination(
                                PosNavDestination.NOTIFICATIONS,
                                nestedNavController,
                            )
                        },
                        onLogout = onLogout,
                        onShutdown = { showShutdownDialog = true },
                        onFullScreenRoute = { isFullScreenRoute = it },
                    )
                }

                // Notification bottom sheet
                NotificationPanel(viewModel = notificationViewModel)

                // Approval Request Dialog (shown when manager receives approval request)
                approvalUiState.currentDialog?.let { dialogState ->
                    when (dialogState) {
                        is ApprovalDialogState.ShowRequest -> {
                            ApprovalRequestDialog(
                                request = dialogState.request,
                                isLoading = approvalUiState.isLoading,
                                onApprove = { requestId ->
                                    approvalViewModel.approveRequest(requestId) {
                                        notificationViewModel.removeNotification("approval_${dialogState.request.requestId}")
                                    }
                                },
                                onDeny = { requestId, reason ->
                                    approvalViewModel.denyRequest(requestId, reason) {
                                        notificationViewModel.removeNotification("approval_${dialogState.request.requestId}")
                                    }
                                },
                                onDismiss = { approvalViewModel.dismissDialog() },
                            )
                        }
                        is ApprovalDialogState.DenyConfirmation -> {
                            // DenyConfirmation is handled inside ApprovalRequestDialog itself
                        }
                    }
                }

                // Temporary Session Dialog (shown on top of drawer)
                val sessionErrorMessage =
                    sessionState.error?.let { err ->
                        str(err.messageKey, *err.messageArgs.toTypedArray())
                    }
                TemporarySessionDialog(
                    isVisible = sessionState.isDialogVisible,
                    isLoading = sessionState.isLoading,
                    errorMessage = sessionErrorMessage,
                    language = currentLanguage,
                    onDismiss = { sessionManager.hideDialog() },
                    onConfirm = { username, pin ->
                        sessionManager.createSession(username, pin)
                    },
                )
            }

            // Persistent TSE-offline banner — visible on all window sizes when NORMAL but offline.
            TseOfflineBanner(
                tseConnectionState = notificationState.tseConnectionState,
            )

            // Status Bar at bottom — visible for all screen sizes
            StatusBar(
                employeeNumber = employeeNumber,
                terminalId = mainUiState.terminalId,
                connectionState = notificationState.connectionState,
                tseConnectionState = notificationState.tseConnectionState,
                language = currentLanguage,
                compact = windowSize.isCompact(),
            )
        }
    }
}

private fun navigateToDestination(
    dest: PosNavDestination,
    navController: NavHostController,
) {
    val route: Any =
        when (dest) {
            PosNavDestination.DASHBOARD -> Dashboard
            PosNavDestination.RESERVATIONS -> MainReservations
            PosNavDestination.SETTINGS -> AppSettings
            PosNavDestination.REPORTS -> Reports
            PosNavDestination.MY_PROFILE -> MyProfile
            PosNavDestination.FINANCIAL_DOCS -> FinancialDocuments
            PosNavDestination.NOTIFICATIONS -> MainNotifications
        }
    navController.navigate(route) {
        popUpTo(Dashboard) {
            saveState = dest.group == NavGroup.MANAGEMENT
            inclusive = dest == PosNavDestination.DASHBOARD
        }
        restoreState = dest.group == NavGroup.MANAGEMENT
        launchSingleTop = true
    }
}
