package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object BillingKeys {
    // Screen Title
    val screenTitle = StringKey("billing.screen_title") // {0} = tableNumber, {1} = stepLabel

    // Navigation
    val backButton = StringKey("billing.back_button")

    // Loading and Error States
    val errorOccurred = StringKey("billing.error_occurred")

    // Step Indicator
    val step1Label = StringKey("billing.step1_label")
    val step2Label = StringKey("billing.step2_label")
    val step3Label = StringKey("billing.step3_label")
    val step4Label = StringKey("billing.step4_label")

    // Item Selection
    val selectedItemsCount = StringKey("billing.selected_items_count") // {0} = count
    val continueButton = StringKey("billing.continue_button")

    // Bill Summary
    val taxLabel = StringKey("billing.tax_label") // {0} = vatRate
    val totalLabel = StringKey("billing.total_label")

    // Payment Entry
    val paymentsTitle = StringKey("billing.payments_title")
    val paidLabel = StringKey("billing.paid_label")
    val remainingLabel = StringKey("billing.remaining_label")
    val tipPortionLabel = StringKey("billing.tip_portion_label") // {0} = tip amount; the overpayment kept as tip
    val removePaymentButton = StringKey("billing.remove_payment_button")

    // Review and Confirmation
    val editItemsButton = StringKey("billing.edit_items_button")
    val addPaymentsButton = StringKey("billing.add_payments_button")
    val confirmBillButton = StringKey("billing.confirm_bill_button")
    val noItemsSelected = StringKey("billing.no_items_selected")

    // Accessibility
    val decreaseAccessibility = StringKey("billing.decrease_accessibility")
    val increaseAccessibility = StringKey("billing.increase_accessibility")

    // Order Item Details
    val creatingBill = StringKey("billing.creating_bill")

    // 3-Column Layout - Order Overview
    val orderOverviewTitle = StringKey("billing.order_overview_title")
    val currentSelectionTitle = StringKey("billing.current_selection_title")
    val articleHeader = StringKey("billing.article_header")
    val unitPriceHeader = StringKey("billing.unit_price_header")
    val sumHeader = StringKey("billing.sum_header")
    val selectAllLabel = StringKey("billing.select_all_label")
    val voucherLabel = StringKey("billing.voucher_label")
    val itemsCountLabel = StringKey("billing.items_count_label") // {0} = count
    val splitBadge = StringKey("billing.split_badge")

    // Payment Panel (redesigned)
    val enterAmountLabel = StringKey("billing.enter_amount_label")
    val justNowLabel = StringKey("billing.just_now_label")
}
