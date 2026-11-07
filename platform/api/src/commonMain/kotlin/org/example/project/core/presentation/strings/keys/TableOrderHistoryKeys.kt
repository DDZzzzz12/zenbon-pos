package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object TableOrderHistoryKeys {
    // Navigation & Header
    val title = StringKey("table_order_history.title")

    // Tabs
    val ordersTab = StringKey("table_order_history.orders_tab")
    val billsTab = StringKey("table_order_history.bills_tab")

    // Empty States
    val noOrders = StringKey("table_order_history.no_orders")
    val noBills = StringKey("table_order_history.no_bills")

    // Order Card
    val orderNumber = StringKey("table_order_history.order_number")
    val orderTime = StringKey("table_order_history.order_time")
    val orderTotal = StringKey("table_order_history.order_total")
    val orderCreatedBy = StringKey("table_order_history.order_created_by") // {0} = employeeId

    // Bill Section
    val billNumber = StringKey("table_order_history.bill_number") // {0} = billNumber
    val tip = StringKey("table_order_history.tip")
    val employeeNumber = StringKey("table_order_history.employee_number")

    // Order Item Status Badges
    val takeaway = StringKey("table_order_history.takeaway")
    val transferred = StringKey("table_order_history.transferred")
    val customPrice = StringKey("table_order_history.custom_price")

    // Item Status Labels
    val statusOpen = StringKey("table_order_history.status_open")
    val statusPaid = StringKey("table_order_history.status_paid")
    val statusCancelled = StringKey("table_order_history.status_cancelled")

    // Transfer History
    val transferFromTable = StringKey("table_order_history.transfer_from_table")
    val transferToTable = StringKey("table_order_history.transfer_to_table")
    val transferredIn = StringKey("table_order_history.transferred_in")
    val transferredOut = StringKey("table_order_history.transferred_out")
    val transferredBy = StringKey("table_order_history.transferred_by")
    val transferSummary = StringKey("table_order_history.transfer_summary")
}
