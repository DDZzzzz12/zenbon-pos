/**
 * UI state for table billing screen.
 *
 * Manages state for quantity-aware item selection, payment entry,
 * and step-by-step billing flow on phone devices.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.state

import org.example.project.core.data.models.PaymentType
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.models.setup.VoucherConfig
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.error.UiError
import org.example.project.money.Decimal

/**
 * Complete UI state for the billing screen.
 *
 * Supports both phone (step-by-step) and tablet/desktop (single screen) layouts
 * with quantity-aware order item selection.
 */
data class TableBillingUiState(
    // Table information
    val tableId: Long = 0,
    val tableNumber: String = "",
    val sessionId: Long? = null,
    // Step management (phone only - compact layout)
    val currentStep: BillingStep = BillingStep.SELECT_ITEMS,
    // Payment mode (numpad vs payment list toggle)
    val paymentMode: BillingPaymentMode = BillingPaymentMode.NUMPAD,
    val numpadInput: String = "",
    // Order items with quantity selection capability
    val orderItems: List<OrderItemForSelection> = emptyList(),
    // Payment types available
    val paymentTypes: List<PaymentType> = emptyList(),
    // Payments entered by user
    val payments: List<PaymentEntry> = emptyList(),
    // Tip amount (optional)
    val tipAmount: Decimal = Decimal.ZERO,
    // Financial summary
    val selectedItemsTotal: Decimal = Decimal.ZERO,
    val taxBreakdown: List<TaxBreakdown> = emptyList(),
    val subtotal: Decimal = Decimal.ZERO,
    val taxTotal: Decimal = Decimal.ZERO,
    val billTotal: Decimal = Decimal.ZERO, // subtotal + tax (before tip)
    val grandTotal: Decimal = Decimal.ZERO, // what the bill comes to after promotions, WITHOUT the tip (F65)
    val paidAmount: Decimal = Decimal.ZERO, // sum of all payments
    val remainingAmount: Decimal = Decimal.ZERO, // grand total - paid amount, floored at zero
    // Voucher configuration (loaded from shop config)
    val voucherConfig: VoucherConfig? = null,
    val enabledVoucherSources: List<VoucherSource> = emptyList(),
    // Voucher validation state (for current validation attempt)
    val isValidatingVoucher: Boolean = false,
    val validatedVoucher: ValidatedVoucherInfo? = null,
    val voucherValidationError: String? = null,
    // Promotion state
    val selectedPromotions: List<AppliedPromotion> = emptyList(),
    val showPromotionDialog: Boolean = false,
    // UI state
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val successMessage: String? = null,
    val showAddPaymentDialog: Boolean = false,
    val preSelectVoucher: Boolean = false,
    val showReceiptDialog: Boolean = false,
    val createdBill: BillDto.BillResponseDto? = null,
) {
    /**
     * Total promotional deduction, computed from applied promotions.
     * This is a client-side estimate for display; the server calculates the real amount.
     */
    val promotionTotal: Decimal get() = selectedPromotions.fold(Decimal.ZERO) { acc, p -> acc + p.deductedAmount }

    /**
     * Number of articles selected for this bill — see [selectedArticleCount]. Both the selection
     * footer and the review panel print this, so they cannot disagree.
     */
    val selectedArticleCount: Int get() = orderItems.selectedArticleCount()
}

/**
 * Billing flow steps for phone layout.
 *
 * Desktop/tablet shows all steps in a single screen.
 */
enum class BillingStep {
    SELECT_ITEMS, // Step 1: Pick items and quantities
    REVIEW_SELECTION, // Step 2: Review selected items with summary (kept for backward compat)
    ENTER_PAYMENTS, // Step 3: Add payments and tip
    RECEIPT, // Step 4: Show created bill receipt
}

/**
 * Payment mode toggle for the payment column.
 */
enum class BillingPaymentMode {
    NUMPAD, // Show numpad for quick amount entry
    PAYMENT_LIST, // Show payment list with edit/remove
}

/**
 * Order item wrapper with quantity selection state.
 *
 * Supports selecting partial quantities from order items (e.g., 2 out of 3 pizzas).
 * Used by both TableBillingScreen and TableOrderHistoryScreen for item selection.
 */
data class OrderItemForSelection(
    val item: OrderItemDto.OrderItemResponseDto,
    val maxQuantity: Int, // From item.quantity
    val selectedQuantity: Int = 0, // User selected (0 to maxQuantity)
    val unitPrice: Decimal, // Price per single item
    val totalPrice: Decimal, // unitPrice × selectedQuantity
) {
    /**
     * Whether this item is currently selected (has quantity > 0).
     */
    val isSelected: Boolean
        get() = selectedQuantity > 0

    /**
     * Get unique identifier combining all order item IDs.
     *
     * Multiple order items can represent the same product with same extras,
     * so we need to track all IDs.
     */
    val orderItemIds: List<Long>
        get() = item.orderItemIds
}

/**
 * The one way to turn a grouped order line into a selectable row.
 *
 * [OrderItemForSelection.unitPrice] is the price the guest is charged for ONE unit — the DTO's
 * `chargedUnitPrice`, which already carries priced choice options and the line discount — NOT the
 * bare catalogue `unitPrice`. Screens fold `unitPrice × quantity` for their totals, so reading the
 * catalogue price there made the total disagree with the very rows above it: a table holding
 * 37,50 € showed 29,50 €, short by the option deltas (E2E-2026-09-20 #2). `TableBillingViewModel`
 * had it right and `PosTableDetailViewModel` did not; both go through here now so they cannot
 * drift apart again.
 *
 * `totalPrice` is the SELECTED total and therefore starts at zero — it is not the line total.
 */
fun OrderItemDto.OrderItemResponseDto.toSelectionRow(): OrderItemForSelection =
    OrderItemForSelection(
        item = this,
        maxQuantity = quantity,
        selectedQuantity = 0,
        unitPrice = Decimal.parse(chargedUnitPrice),
        totalPrice = Decimal.ZERO,
    )

/**
 * The one definition of "how many Artikel are selected": the sum of the selected UNITS.
 *
 * A row is a grouped order line (e.g. 2 × Espresso from two orders), so counting selected rows
 * undercounts. The selection footer used to count rows ("Ausgewählt: 4 Artikel") while the review
 * panel summed quantities ("6 Artikel") for the same table (OBS-3); both read this now.
 */
fun List<OrderItemForSelection>.selectedArticleCount(): Int = sumOf { it.selectedQuantity }

/**
 * Re-applies a selection to freshly loaded rows, keeping only the units that still exist.
 *
 * The table panel reloads when the table changes underneath it — an approved Storno, a transfer,
 * another waiter's order. A unit that was cancelled or moved away drops out of the selection, so
 * it cannot be cancelled or transferred a second time (E2E-2026-09-20 round 2, R2-3); a unit that
 * is still there stays selected, so a request still awaiting a manager stays visible (#6).
 *
 * Units of one grouped line are interchangeable, so only the COUNT of surviving ids matters.
 */
fun List<OrderItemForSelection>.withSurvivingSelection(selectedIds: Set<Long>): List<OrderItemForSelection> =
    map { row ->
        val kept = row.orderItemIds.count { it in selectedIds }.coerceAtMost(row.maxQuantity)
        row.copy(selectedQuantity = kept, totalPrice = row.unitPrice * Decimal.of(kept))
    }

/**
 * Summary representation of a selected item for display.
 *
 * Used in the review step to show what will be billed.
 */
data class SelectedItemSummary(
    val productName: String,
    val selectedQuantity: Int,
    val unitPrice: Decimal,
    val extras: List<String>,
    val sideNotes: String? = null,
    val totalPrice: Decimal,
    val vatRate: Decimal,
)

/**
 * Payment entry made by user.
 *
 * Supports multiple payments of different types for split payment.
 * For voucher payments, includes additional metadata for redemption tracking.
 */
data class PaymentEntry(
    val id: String, // Unique identifier for this payment
    val paymentTypeId: Long, // Payment type ID
    val paymentTypeName: String, // Display name (e.g., "Cash", "Card", "Voucher")
    val amount: Decimal, // Payment amount
    // Voucher-specific fields (null for non-voucher payments)
    val voucherNumber: String? = null, // Voucher code (e.g., "G2025-0001")
    val voucherOriginalValue: Decimal? = null, // Original voucher value
    val voucherRemainingBalance: Decimal? = null, // Remaining balance before this redemption
    val voucherSource: VoucherSourceType? = null, // Source type (INTERNAL, LEGACY, etc.)
) {
    /**
     * Check if this is a voucher payment.
     */
    val isVoucherPayment: Boolean
        get() = voucherNumber != null
}

/**
 * Tax breakdown by VAT rate.
 *
 * Groups items by tax rate and shows taxable amount and tax.
 */
data class TaxBreakdown(
    val vatRate: Decimal, // e.g., 0.19, 0.07 (fraction)
    val taxableAmount: Decimal, // Amount before tax
    val taxAmount: Decimal, // Tax portion
)

/**
 * A promotion applied to the current bill.
 *
 * Tracks the promotion metadata and a client-side estimate of the deduction
 * for display purposes. The server recalculates the actual deduction on bill creation.
 */
data class AppliedPromotion(
    val promotionId: Long,
    val promotionName: String,
    val discountType: String,
    val discountValue: String,
    val scope: String,
    /**
     * Whether this promotion may share the bill with OTHER promotions. Mirrors
     * `PromotionResponseDto.combinable`; kept on the applied entry so the till can evaluate the
     * combination rule without re-reading the promotion catalogue. Not to be confused with
     * `isMultiUse`, which governs repeating the same promotion on one bill.
     */
    val combinable: Boolean = true,
    val orderItemId: Long? = null,
    val deductedAmount: Decimal = Decimal.ZERO,
)

/**
 * Information about a validated voucher ready for redemption.
 *
 * Contains voucher details retrieved from the server after successful validation.
 * Used to display voucher information to the user before confirming the payment.
 */
data class ValidatedVoucherInfo(
    val voucherNumber: String, // Voucher code (e.g., "G2025-0001")
    val originalValue: Decimal, // Original voucher value
    val remainingBalance: Decimal, // Current remaining balance
    val source: VoucherSourceType, // Source type (INTERNAL, LEGACY, etc.)
    val canRedeemAmount: Decimal, // Maximum amount that can be redeemed
    val expiryDate: String? = null, // Optional expiry date (ISO format)
)
