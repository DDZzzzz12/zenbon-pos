package org.example.project.ui.screens.dashboard

import androidx.navigation.NavHostController
import org.example.project.features.dashboard.presentation.navigation.TableBilling
import org.example.project.features.dashboard.presentation.navigation.TableOrder
import org.example.project.features.dashboard.presentation.navigation.TableOrderHistory

/**
 * Centralized navigation helper for dashboard-level routes.
 * Keeps navigation logic out of composables for clarity.
 */
object PosDashboardNavHelper {
    fun navigateToTableOrder(
        navController: NavHostController,
        tableId: Long,
        employeeNumber: String? = null,
    ) {
        navController.navigate(TableOrder(tableId = tableId, employeeNumber = employeeNumber)) {
            launchSingleTop = true
        }
    }

    fun navigateToTableBilling(
        navController: NavHostController,
        tableId: Long,
    ) {
        navController.navigate(TableBilling(tableId = tableId)) {
            launchSingleTop = true
        }
    }

    fun navigateToTableHistory(
        navController: NavHostController,
        tableId: Long,
    ) {
        navController.navigate(TableOrderHistory(tableId = tableId)) {
            launchSingleTop = true
        }
    }
}
