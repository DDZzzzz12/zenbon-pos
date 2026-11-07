package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object DashboardKeys {
    // Table Status Labels
    val statusFree = StringKey("dashboard.status_free")
    val statusOccupied = StringKey("dashboard.status_occupied")
    val statusReserved = StringKey("dashboard.status_reserved")

    // Empty State
    val noTablesAvailable = StringKey("dashboard.no_tables_available")
    val noTablesForType = StringKey("dashboard.no_tables_for_type") // {0} = tableType

    // Floor Plan
    val occupiedLabel = StringKey("dashboard.occupied_label")
    val floorPlanError = StringKey("dashboard.floor_plan_error")
    val successTitle = StringKey("dashboard.success_title")

    // Phone Table List
    val seatsLabel = StringKey("dashboard.seats_label")
    val tableLabel = StringKey("dashboard.table_label")
    val employeeLabel = StringKey("dashboard.employee_label") // {0} = number

    // Floor Plan Top Bar
    val floorPlanTitle = StringKey("dashboard.floor_plan_title")

    // Table Detail Panel
    val guestsLabel = StringKey("dashboard.guests_label")
    val currentOrderLabel = StringKey("dashboard.current_order_label")
    val createBillAction = StringKey("dashboard.create_bill_action")
    val tableDetailsAction = StringKey("dashboard.table_details_action")
    val transferAction = StringKey("dashboard.transfer_action")
    val historyAction = StringKey("dashboard.history_action")
    val deleteTableAction = StringKey("dashboard.delete_table_action")

    // Table Detail Screen
    val actionsLabel = StringKey("dashboard.actions_label")
    val newOrderAction = StringKey("dashboard.new_order_action")
    val assignCustomerAction = StringKey("dashboard.assign_customer_action")
    val orderTotalLabel = StringKey("dashboard.order_total_label")
    val selectedTotalLabel = StringKey("dashboard.selected_total_label")
}
