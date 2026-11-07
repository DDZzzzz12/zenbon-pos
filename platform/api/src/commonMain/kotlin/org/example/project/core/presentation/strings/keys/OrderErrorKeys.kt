package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/**
 * Localized refusals of the order-writing path (create order, cancel/transfer items), mapped from
 * their `PosErrorCode` in `UiErrorMapping`. Unmapped, these reached the till and the order station
 * as the server's raw English detail (emulator pass 2026-09-27, E-5).
 */
object OrderErrorKeys {
    val trainingModeMismatchTitle = StringKey("order_error.training_mode_mismatch_title")
    val trainingModeMismatch = StringKey("order_error.training_mode_mismatch")
    val virtualTableNotOrderable = StringKey("order_error.virtual_table_not_orderable")
    val sessionAlreadyClosedTitle = StringKey("order_error.session_already_closed_title")
    val sessionAlreadyClosed = StringKey("order_error.session_already_closed")
    val customPriceNotAllowed = StringKey("order_error.custom_price_not_allowed")
    val discountNotAllowed = StringKey("order_error.discount_not_allowed")
}
