/**
 * Nested navigation for Dashboard screens.
 *
 * Handles navigation between dashboard main view and table-related screens
 * (orders, details, billing, history, transfer).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.example.project.core.browser.ServerBuildWatcher
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.presentation.error.LocalScreenName
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.utils.isDesktop
import org.example.project.ui.auth.RequireCapability
import org.example.project.ui.auth.rememberIsStation
import org.example.project.ui.screens.billing.PosTableBillingScreen
import org.example.project.ui.screens.dashboard.PosDashboardScreen
import org.example.project.ui.screens.dashboard.TableBillingActions
import org.example.project.ui.screens.dashboard.history.PosTableHistoryScreen
import org.example.project.ui.screens.order.PosTableOrderScreen
import org.example.project.ui.screens.reservation.ReservationListScreen
import org.koin.compose.koinInject

/**
 * Dashboard navigation host with nested table screens.
 */
@Composable
fun DashboardNavHost(
    modifier: Modifier = Modifier,
    onBackOnDashboard: () -> Unit,
    onNotificationsClick: () -> Unit = {},
    onFullScreenRoute: (Boolean) -> Unit = {},
) {
    val navController = rememberNavController()

    // Report full-screen routes (order/billing) to parent for hiding bottom bar
    val dashboardBackStack by navController.currentBackStackEntryAsState()
    val dashboardRoute = dashboardBackStack?.destination?.route
    LaunchedEffect(dashboardRoute) {
        val isFullScreen =
            dashboardRoute != null &&
                (
                    dashboardRoute.contains("TableOrder") ||
                        dashboardRoute.contains("TableBilling")
                )
        onFullScreenRoute(isFullScreen)
    }

    // A screen above the dashboard grid holds work in progress: an automatic update reload must wait.
    val serverBuildWatcher = koinInject<ServerBuildWatcher>()
    LaunchedEffect(dashboardRoute) {
        serverBuildWatcher.setInnerBusy(isDashboardWorkInProgress(dashboardRoute))
    }
    DisposableEffect(Unit) { onDispose { serverBuildWatcher.setInnerBusy(false) } }

    // Unified notification navigation — billing requests navigate to TableBilling, others to DashboardMain
    val platformNotificationService = koinInject<PlatformNotificationService>()
    val pendingNavigation by platformNotificationService.pendingNavigation.collectAsState()
    LaunchedEffect(pendingNavigation) {
        val nav = pendingNavigation ?: return@LaunchedEffect
        when (nav.type) {
            "billing_request" -> {
                val tableId = nav.entityId?.toLongOrNull() ?: return@LaunchedEffect
                navController.navigate(TableBilling(tableId = tableId)) {
                    launchSingleTop = true
                }
            }
            else -> {
                navController.navigate(DashboardMain) {
                    popUpTo(DashboardMain) { inclusive = true }
                }
            }
        }
        platformNotificationService.consumePendingNavigation()
    }

    // Publish the destination so every reported error records the screen it happened on, without
    // each watcher call site having to pass it.
    val currentRoute =
        navController
            .currentBackStackEntryAsState()
            .value
            ?.destination
            ?.route
            ?.substringAfterLast('.')
    CompositionLocalProvider(LocalScreenName provides currentRoute) {
        NavHost(
            navController = navController,
            startDestination = DashboardMain,
            modifier = modifier,
        ) {
            composable<DashboardMain> {
                PosDashboardScreen(
                    navController = navController,
                    onBackOnDashboard = onBackOnDashboard,
                    onNotificationsClick = onNotificationsClick,
                )
            }

            composable<TableOrder> { backStackEntry ->
                // Leaving the order screen is only allowed through the top-left back arrow: a stray
                // system Back (Android) must not drop a half-built order. Enabled everywhere but the
                // desktop, where the handler would swallow Esc, which the search field uses to clear.
                BackHandler(enabled = !isDesktop()) { }
                val tableOrder = backStackEntry.toRoute<TableOrder>()
                PosTableOrderScreen(
                    tableId = tableOrder.tableId,
                    employeeNumber = tableOrder.employeeNumber,
                    onNavigateBack = {
                        navController.navigate(DashboardMain) {
                            popUpTo(DashboardMain) {
                                inclusive = true
                            }
                        }
                    },
                )
            }

            composable<TableBilling> { backStackEntry ->
                BackHandler { navController.popBackStack() }
                val tableBilling = backStackEntry.toRoute<TableBilling>()
                val toDashboard = {
                    navController.navigate(DashboardMain) { popUpTo(DashboardMain) { inclusive = true } }
                    Unit
                }

                // BILL_WRITE, the capability `POST /api/v1/bills` names and the one
                // `PosTableActionGrid` already asks for on the "create bill" action.
                //
                // The gate belongs HERE, not only on that action: the screen has a second door. The
                // `"billing_request"` branch above navigates straight to this destination from a
                // notification, and the server addresses that notification to the table session's
                // OWNER employee — which, for a table an unattended order station opened under
                // TABLE_WRITE, is the station itself. A waiter tapping "Rechnung anfordern" would
                // otherwise drop the station into the full payment/split/tender screen, whose every
                // call the server then refuses. Gating the destination closes both doors at once.
                //
                // And never under the order station's shared account, whatever the capability set
                // says (OS-b): a step-up at the station replaces the policy with the lent set, so the
                // capability alone is not the whole answer. The server refuses the same way
                // (`BillingAuthority`).
                val billingOffered = TableBillingActions.billingOffered(rememberIsStation())
                if (!billingOffered) {
                    LaunchedEffect(Unit) { toDashboard() }
                } else {
                    RequireCapability(Capability.BILL_WRITE, onDenied = toDashboard) {
                        PosTableBillingScreen(
                            tableId = tableBilling.tableId,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }
            }

            composable<TableOrderHistory> { backStackEntry ->
                BackHandler { navController.popBackStack() }
                val tableOrderHistory = backStackEntry.toRoute<TableOrderHistory>()
                PosTableHistoryScreen(
                    tableId = tableOrderHistory.tableId,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable<Reservations> {
                BackHandler {
                    navController.navigate(DashboardMain) {
                        popUpTo(DashboardMain) { inclusive = true }
                    }
                }
                ReservationListScreen(
                    onNavigateBack = {
                        navController.navigate(DashboardMain) {
                            popUpTo(DashboardMain) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

/** True for any inner dashboard route other than the grid itself; null (not yet resolved) is the grid. */
internal fun isDashboardWorkInProgress(route: String?): Boolean = route != null && !route.contains("DashboardMain")
