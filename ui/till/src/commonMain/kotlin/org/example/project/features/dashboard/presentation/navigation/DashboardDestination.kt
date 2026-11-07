package org.example.project.features.dashboard.presentation.navigation

import kotlinx.serialization.Serializable

/**
 * Dashboard navigation routes.
 */
@Serializable
object DashboardMain

@Serializable
data class TableOrder(
    val tableId: Long,
    val employeeNumber: String? = null,
)

@Serializable
data class TableBilling(
    val tableId: Long,
)

@Serializable
data class TableOrderHistory(
    val tableId: Long,
)

@Serializable
object Reservations
