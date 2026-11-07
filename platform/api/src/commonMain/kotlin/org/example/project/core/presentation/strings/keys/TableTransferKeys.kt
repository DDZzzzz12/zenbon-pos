package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object TableTransferKeys {
    val title = StringKey("table_transfer.title")

    // getCancel delegates to CommonStrings — skipped
    val selectTargetTable = StringKey("table_transfer.select_target_table")
    val selectEmployee = StringKey("table_transfer.select_employee")
    val noTablesAvailable = StringKey("table_transfer.no_tables_available")
    val searchTable = StringKey("table_transfer.search_table")
    val noSearchResults = StringKey("table_transfer.no_search_results")

    // Empty state of a search-driven picker before anything has been typed. Distinct from
    // [noTablesAvailable], which claims there is nothing to transfer to — false when the list is
    // merely unsearched.
    val searchToSeeTables = StringKey("table_transfer.search_to_see_tables")
    val noEmployeeResults = StringKey("table_transfer.no_employee_results")
    val moveOrderSubtitle = StringKey("table_transfer.move_order_subtitle") // {0} = tableNumber
    val transferToButton = StringKey("table_transfer.transfer_to_button") // {0} = tableNumber
    val transferToPlaceholder = StringKey("table_transfer.transfer_to_placeholder")
    val selected = StringKey("table_transfer.selected")
    val changeEmployeeTitle = StringKey("table_transfer.change_employee_title")
    val changeEmployeeSubtitle = StringKey("table_transfer.change_employee_subtitle") // {0} = tableNumber
    val transferToEmployee = StringKey("table_transfer.transfer_to_employee") // {0} = employeeName
    val includeTableTransfer = StringKey("table_transfer.include_table_transfer")
}
