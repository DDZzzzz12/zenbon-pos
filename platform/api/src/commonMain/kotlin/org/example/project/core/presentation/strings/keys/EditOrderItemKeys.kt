package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object EditOrderItemKeys {
    val title = StringKey("edit_order_item.title")
    val sideNotes = StringKey("edit_order_item.side_notes")
    val discount = StringKey("edit_order_item.discount")

    /**
     * Shown when the discount field does not hold a whole percent within the operator's cap.
     * {0} = that cap. Whole percents only because the persisted multiplier is DECIMAL(4,2).
     */
    val discountWholePercentOnly = StringKey("edit_order_item.discount_whole_percent_only")
    val takeaway = StringKey("edit_order_item.takeaway")
    val customPrice = StringKey("edit_order_item.custom_price")
    val unitPrice = StringKey("edit_order_item.unit_price")
    val quantity = StringKey("edit_order_item.quantity")
    val totalPrice = StringKey("edit_order_item.total_price")
}
