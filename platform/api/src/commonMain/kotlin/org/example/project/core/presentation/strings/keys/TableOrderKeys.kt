package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object TableOrderKeys {
    val tableTitle = StringKey("table_order.table_title") // {0} = tableNumber
    val tableSubtitle = StringKey("table_order.table_subtitle") // {0} = locationType, {1} = seats
    val backButton = StringKey("table_order.back_button")
    val emptyOrderTitle = StringKey("table_order.empty_order_title")
    val tableNotFound = StringKey("table_order.table_not_found")
    val each = StringKey("table_order.each")
    val submittingOrder = StringKey("table_order.submitting_order")
    val reviewOrder = StringKey("table_order.review_order")
    val submitOrder = StringKey("table_order.submit_order")
    val searchByNameOrSku = StringKey("table_order.search_by_name_or_sku")
    val currentOrder = StringKey("table_order.current_order")
    val clearAll = StringKey("table_order.clear_all")
    val clearAllConfirmTitle = StringKey("table_order.clear_all_confirm_title")
    val clearAllConfirmMessage = StringKey("table_order.clear_all_confirm_message")
    val noItemsYet = StringKey("table_order.no_items_yet")
    val itemCountOne = StringKey("table_order.item_count_one")
    val itemCountMany = StringKey("table_order.item_count_many")
}
