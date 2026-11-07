package org.example.project.features.main.presentation.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.utils.rememberLanguage
import org.example.project.features.dashboard.presentation.navigation.DashboardNavHost
import org.example.project.ui.auth.RequireCapability
import org.example.project.ui.screens.employees.components.CashierProfileView
import org.example.project.ui.screens.financialdocs.PosFinancialDocsScreen
import org.example.project.ui.screens.notifications.PosNotificationScreen
import org.example.project.ui.screens.reports.PosReportsScreen
import org.example.project.ui.screens.reservation.PosReservationScreen
import org.example.project.ui.screens.settings.PosSettingsScreen
import org.koin.compose.koinInject

/**
 * Nested navigation host for main POS sections.
 *
 * Manages navigation between Dashboard, Sales, Reports,
 * and other main application screens within the authenticated area.
 *
 * @param navController Navigation controller for this nav host
 * @param modifier Modifier for customization
 */
@Composable
fun MainNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onBackOnDashboard: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onLogout: () -> Unit = {},
    onShutdown: () -> Unit = {},
    onFullScreenRoute: (Boolean) -> Unit = {},
) {
    // Unified notification navigation — handles both in-app panel clicks and system notification taps
    val platformNotificationService = koinInject<PlatformNotificationService>()
    val pendingNavigation by platformNotificationService.pendingNavigation.collectAsState()

    LaunchedEffect(pendingNavigation) {
        val nav = pendingNavigation ?: return@LaunchedEffect
        when (nav.type) {
            "billing_request" -> {
                val currentRoute = navController.currentDestination?.route
                if (currentRoute != null && !currentRoute.contains("Dashboard")) {
                    navController.navigate(Dashboard) {
                        popUpTo(Dashboard) { inclusive = true }
                    }
                }
            }
            else -> {
                val currentRoute = navController.currentDestination?.route
                if (currentRoute != null && !currentRoute.contains("Dashboard")) {
                    navController.navigate(Dashboard) {
                        popUpTo(Dashboard) { inclusive = true }
                    }
                }
                platformNotificationService.consumePendingNavigation()
            }
        }
        // billing_request navigation is consumed at the DashboardNavHost level after navigating to TableBilling
    }

    // Main navigation content
    NavHost(
        navController = navController,
        startDestination = Dashboard,
        modifier = modifier.fillMaxSize(),
    ) {
        composable<Dashboard> {
            // Dashboard has its own nested navigation for table operations
            DashboardNavHost(
                modifier = Modifier.then(modifier),
                onBackOnDashboard = onBackOnDashboard,
                onNotificationsClick = onNotificationsClick,
                onFullScreenRoute = onFullScreenRoute,
            )
        }

        composable<MainReservations> {
            BackHandler {
                navController.navigate(Dashboard) {
                    popUpTo(Dashboard) { inclusive = true }
                }
            }
            val toDashboard = {
                navController.navigate(Dashboard) { popUpTo(Dashboard) { inclusive = true } }
                Unit
            }

            // RESERVATION_READ, matching PosNavDestination.RESERVATIONS. An ACCOUNTANT does not hold
            // it, and GET /api/reservations answers 403 for that group, so without this the screen
            // mounts and fails rather than handing the user back to the dashboard.
            RequireCapability(Capability.RESERVATION_READ, onDenied = toDashboard) {
                PosReservationScreen(
                    onNavigateBack = { navController.navigateUp() },
                    modifier = Modifier.then(modifier),
                )
            }
        }

        composable<Reports> {
            BackHandler {
                navController.navigate(Dashboard) {
                    popUpTo(Dashboard) { inclusive = true }
                }
            }
            // Gated on the SAME capability the menu entry uses (PosNavDestination.REPORTS), so the
            // two doors into this screen cannot disagree. It used to exclude TERMINAL_DEVICE by
            // role, which said the same thing for the four role groups and the wrong thing for any
            // purpose-built one.
            //
            // `role` is still passed down: PosReportsScreen uses it to pick a LAYOUT VARIANT, not
            // to decide access.
            val currenLanguage by rememberLanguage()
            val toDashboard = {
                navController.navigate(Dashboard) { popUpTo(Dashboard) { inclusive = true } }
                Unit
            }

            RequireCapability(Capability.REPORT_READ, onDenied = toDashboard) {
                PosReportsScreen(
                    language = currenLanguage,
                    modifier = Modifier.then(modifier),
                )
            }
        }

        composable<MyProfile> {
            BackHandler {
                navController.navigate(Dashboard) {
                    popUpTo(Dashboard) { inclusive = true }
                }
            }
            val toDashboard = {
                navController.navigate(Dashboard) { popUpTo(Dashboard) { inclusive = true } }
                Unit
            }

            RequireCapability(Capability.SELF_READ, onDenied = toDashboard) {
                CashierProfileView(
                    onNavigateBack = { navController.navigateUp() },
                )
            }
        }

        composable<AppSettings> {
            BackHandler {
                navController.navigate(Dashboard) {
                    popUpTo(Dashboard) { inclusive = true }
                }
            }
            val currentLanguage by rememberLanguage()
            val toDashboard = {
                navController.navigate(Dashboard) { popUpTo(Dashboard) { inclusive = true } }
                Unit
            }

            // CONFIG_READ, matching PosNavDestination.SETTINGS. The screen self-gates its TABS on
            // CONFIG_WRITE, which is a narrower question and leaves the GENERAL tab reachable to a
            // principal who may not read configuration at all.
            RequireCapability(Capability.CONFIG_READ, onDenied = toDashboard) {
                PosSettingsScreen(
                    language = currentLanguage,
                    modifier = Modifier.then(modifier),
                    onLogout = onLogout,
                    onShutdown = onShutdown,
                )
            }
        }

        composable<FinancialDocuments> {
            BackHandler {
                navController.navigate(Dashboard) {
                    popUpTo(Dashboard) { inclusive = true }
                }
            }
            val currentLanguage by rememberLanguage()
            val toDashboard = {
                navController.navigate(Dashboard) { popUpTo(Dashboard) { inclusive = true } }
                Unit
            }

            // CASHBOOK_READ, matching PosNavDestination.FINANCIAL_DOCS. The role check this replaces
            // was the live half of a real bug: an AUDITOR holds CASHBOOK_READ but may carry role
            // CASHIER, so the menu offered this destination and arriving here rendered NOTHING —
            // a blank screen with no message and no way back but the system gesture.
            RequireCapability(Capability.CASHBOOK_READ, onDenied = toDashboard) {
                PosFinancialDocsScreen(
                    language = currentLanguage,
                    modifier = Modifier.then(modifier),
                )
            }
        }

        composable<MainNotifications> {
            BackHandler {
                navController.navigate(Dashboard) {
                    popUpTo(Dashboard) { inclusive = true }
                }
            }
            val toDashboard = {
                navController.navigate(Dashboard) { popUpTo(Dashboard) { inclusive = true } }
                Unit
            }

            // NOTIFICATION_READ, matching PosNavDestination.NOTIFICATIONS.
            RequireCapability(Capability.NOTIFICATION_READ, onDenied = toDashboard) {
                PosNotificationScreen(
                    modifier = Modifier.then(modifier),
                )
            }
        }
    }
}
