/**
 * ViewModel for table billing screen.
 *
 * Manages billing flow with quantity-aware item selection, payment entry,
 * and step-by-step navigation for phone devices.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.auth.SettingManager
import org.example.project.core.data.domain.service.ClientOrderService
import org.example.project.core.data.provider.PaymentTypeDataProvider
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.PromotionApiClient
import org.example.project.core.data.remote.VoucherApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PromotionKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.dashboard.presentation.state.*
import org.example.project.features.dashboard.presentation.state.BillingTotals
import org.example.project.features.dashboard.presentation.state.toSelectionRow
import org.example.project.features.promotion.domain.PromotionTerms
import org.example.project.features.promotion.domain.blocksPromotionCombination
import org.example.project.features.promotion.domain.estimateItemPromotionDeduction
import org.example.project.money.Currency
import org.example.project.money.CurrencyFormatException
import org.example.project.money.Decimal
import org.example.project.money.MoneyRounding
import org.example.project.money.Percentage
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * ViewModel for managing table billing operations.
 *
 * Coordinates data loading, item selection with quantities, payment management,
 * and bill creation following the Result pattern for error handling.
 */
class TableBillingViewModel(
    private val rTableDataProvider: RTableDataProvider,
    private val clientOrderService: ClientOrderService,
    private val paymentTypeDataProvider: PaymentTypeDataProvider,
    private val billApiClient: BillApiClient,
    private val voucherApiClient: VoucherApiClient,
    private val settingManager: SettingManager,
    private val promotionApiClient: PromotionApiClient,
    private val languageManager: LanguageManager,
) : ViewModel() {
    private val logger = Logger.withTag("TableBillingViewModel")

    /** PosSuccessWatcher shows success text verbatim, so it is resolved here in the operator's language. */
    private fun localized(key: StringKey): String = getString(key, languageManager.currentLanguage.value)

    private val _uiState = MutableStateFlow(TableBillingUiState())
    val uiState: StateFlow<TableBillingUiState> = _uiState.asStateFlow()

    // ========== INITIALIZATION ==========

    /**
     * Load table data, session ID, order items, and payment types.
     *
     * @param tableId ID of the table to create bill for
     */
    fun loadTableData(tableId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, tableId = tableId) }

            val tableResult = runCatchingApi { rTableDataProvider.getRTableById(tableId) }
            val table =
                tableResult.getOrElse {
                    tableResult.onError { uiError ->
                        _uiState.update { it.copy(isLoading = false, error = uiError) }
                    }
                    return@launch
                }
            if (table == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                            ),
                    )
                }
                return@launch
            }
            val sessionId = table.currentSessionId
            if (sessionId == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                            ),
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(tableNumber = table.tableNumber, sessionId = sessionId) }

            val paymentTypes =
                runCatchingApi { paymentTypeDataProvider.getActivePaymentTypes() }
                    .getOrNull() ?: emptyList()

            val voucherConfig = settingManager.loadVoucherConfig()
            val enabledSources =
                if (voucherConfig.enabled) {
                    voucherConfig.sources.filter { it.enabled }
                } else {
                    emptyList()
                }

            _uiState.update {
                it.copy(
                    paymentTypes = paymentTypes,
                    voucherConfig = voucherConfig,
                    enabledVoucherSources = enabledSources,
                    isLoading = false,
                )
            }
        }
    }

    fun loadItems() {
        viewModelScope.launch {
            val sessionId = _uiState.value.sessionId ?: return@launch
            runCatchingApi { clientOrderService.getOrderItemsForTable(sessionId) }
                .onSuccess { data ->
                    val orderItems = data.map { it.toSelectionRow() }
                    _uiState.update { it.copy(orderItems = orderItems) }
                }.onError { uiError ->
                    logger.e { "TableBillingViewModel.loadItems: failed to load order items - $uiError" }
                    _uiState.update {
                        it.copy(error = uiError)
                    }
                }
        }
    }

    fun reset() {
        _uiState.update { TableBillingUiState() }
    }

    /**
     * Clears billing-specific state (payments, created bill, step)
     * while preserving table context (tableId, sessionId, tableNumber)
     * so data can be reloaded without a visual flash.
     */
    fun resetBillingState() {
        _uiState.update { state ->
            state.copy(
                currentStep = BillingStep.SELECT_ITEMS,
                paymentMode = BillingPaymentMode.NUMPAD,
                numpadInput = "",
                payments = emptyList(),
                tipAmount = Decimal.ZERO,
                selectedItemsTotal = Decimal.ZERO,
                taxBreakdown = emptyList(),
                subtotal = Decimal.ZERO,
                taxTotal = Decimal.ZERO,
                billTotal = Decimal.ZERO,
                grandTotal = Decimal.ZERO,
                paidAmount = Decimal.ZERO,
                remainingAmount = Decimal.ZERO,
                selectedPromotions = emptyList(),
                showPromotionDialog = false,
                isValidatingVoucher = false,
                validatedVoucher = null,
                voucherValidationError = null,
                showAddPaymentDialog = false,
                showReceiptDialog = false,
                createdBill = null,
                error = null,
                successMessage = null,
            )
        }
    }

    // ========== STEP 1: ITEM SELECTION ==========

    /**
     * Update quantity for a specific order item.
     *
     * @param itemIds List of order item IDs (may be multiple for same product)
     * @param quantity New quantity (0 to maxQuantity)
     */
    fun updateItemQuantity(
        itemIds: List<Long>,
        quantity: Int,
    ) {
        _uiState.update { state ->
            val updatedItems =
                state.orderItems.map { item ->
                    if (item.orderItemIds == itemIds) {
                        val clampedQty = quantity.coerceIn(0, item.maxQuantity)
                        item.copy(
                            selectedQuantity = clampedQty,
                            totalPrice = item.unitPrice * Decimal.of(clampedQty),
                        )
                    } else {
                        item
                    }
                }
            state.copy(orderItems = updatedItems)
        }
        calculateTotals()
    }

    /**
     * Check if any items are selected.
     */
    fun hasSelectedItems(): Boolean = _uiState.value.orderItems.any { it.isSelected }

    fun hasItemsLeft(): Boolean {
        val items = _uiState.value.orderItems
        if (items.isEmpty()) return false

        if (items.any { item -> !item.isSelected }) return true

        if (items.any { item -> item.selectedQuantity < item.maxQuantity }) return true

        return false
    }

    /**
     * Select all items with their full quantities.
     */
    fun selectAllItems() {
        _uiState.update { state ->
            val updatedItems =
                state.orderItems.map { item ->
                    item.copy(
                        selectedQuantity = item.maxQuantity,
                        totalPrice = item.unitPrice * Decimal.of(item.maxQuantity),
                    )
                }
            state.copy(orderItems = updatedItems)
        }
        calculateTotals()
    }

    /**
     * Clear all item selections.
     */
    fun clearSelection() {
        _uiState.update { state ->
            val updatedItems =
                state.orderItems.map { item ->
                    item.copy(selectedQuantity = 0, totalPrice = Decimal.ZERO)
                }
            state.copy(orderItems = updatedItems)
        }
        calculateTotals()
    }

    /**
     * Navigate to review step (phone only).
     */
    fun goToReviewStep() {
        if (hasSelectedItems()) {
            _uiState.update { it.copy(currentStep = BillingStep.REVIEW_SELECTION) }
        }
    }

    // ========== STEP 2: REVIEW SELECTION ==========

    /**
     * Get summary of selected items for display.
     */
    fun getSelectedItemsSummary(): List<SelectedItemSummary> =
        _uiState.value.orderItems
            .filter { it.isSelected }
            .map { item ->
                SelectedItemSummary(
                    productName = item.item.product.productName,
                    selectedQuantity = item.selectedQuantity,
                    unitPrice = item.unitPrice,
                    extras = item.item.extras?.map { it.name } ?: emptyList(),
                    sideNotes = item.item.sideNotes,
                    totalPrice = item.totalPrice,
                    vatRate = Decimal.parse(item.item.vatRate),
                )
            }

    /**
     * Go back to item selection step.
     */
    fun goBackToItemSelection() {
        _uiState.update { it.copy(currentStep = BillingStep.SELECT_ITEMS) }
    }

    /**
     * Navigate to previous step in the billing flow.
     * Does nothing if already on the first step.
     */
    fun goToPreviousStep() {
        _uiState.update { state ->
            val previousStep =
                when (state.currentStep) {
                    BillingStep.SELECT_ITEMS -> BillingStep.SELECT_ITEMS // Already first step
                    BillingStep.REVIEW_SELECTION -> BillingStep.SELECT_ITEMS
                    BillingStep.ENTER_PAYMENTS -> BillingStep.REVIEW_SELECTION
                    BillingStep.RECEIPT -> BillingStep.ENTER_PAYMENTS
                }
            state.copy(currentStep = previousStep)
        }
    }

    /**
     * Navigate to payment entry step.
     */
    fun goToPaymentStep() {
        _uiState.update { it.copy(currentStep = BillingStep.ENTER_PAYMENTS) }
    }

    // ========== NUMPAD / PAYMENT MODE ==========

    /**
     * Toggle between NUMPAD and PAYMENT_LIST modes.
     */
    fun togglePaymentMode() {
        _uiState.update { state ->
            state.copy(
                paymentMode =
                    when (state.paymentMode) {
                        BillingPaymentMode.NUMPAD -> BillingPaymentMode.PAYMENT_LIST
                        BillingPaymentMode.PAYMENT_LIST -> BillingPaymentMode.NUMPAD
                    },
            )
        }
    }

    /**
     * Append a digit or dot to the numpad input.
     * Enforces max 1 dot and max 2 decimal places.
     */
    fun onNumpadKey(key: String) {
        _uiState.update { state ->
            val current = state.numpadInput
            val newInput =
                when {
                    key == "." && current.contains(".") -> current // Already has dot
                    key == "." && current.isEmpty() -> "0." // Start with "0."
                    key != "." && current.contains(".") -> {
                        val decimals = current.substringAfter(".")
                        if (decimals.length >= 2) current else current + key // Max 2 decimal places
                    }
                    else -> current + key
                }
            state.copy(numpadInput = newInput)
        }
    }

    /**
     * Remove last character from numpad input.
     */
    fun onNumpadBackspace() {
        _uiState.update { state ->
            state.copy(numpadInput = state.numpadInput.dropLast(1))
        }
    }

    /**
     * Clear numpad input.
     */
    fun onNumpadClear() {
        _uiState.update { state -> state.copy(numpadInput = "") }
    }

    /**
     * Parse current numpad input to Decimal.
     * Returns ZERO if input is blank or invalid.
     */
    fun getNumpadAmount(): Decimal = BillingMoneyMath.parseInput(_uiState.value.numpadInput) ?: Decimal.ZERO

    /**
     * Set numpad display to the remaining amount.
     * Used for auto-fill when user presses Enter with empty input.
     */
    fun setNumpadToRemaining() {
        val remaining = _uiState.value.remainingAmount
        if (remaining <= Decimal.ZERO) return
        _uiState.update { it.copy(numpadInput = remaining.toPlainString()) }
    }

    /**
     * Add a quick payment using the current numpad input amount.
     * Clears numpad after adding.
     */
    fun addQuickPayment(paymentTypeId: Long) {
        val amount = BillingMoneyMath.parseInput(_uiState.value.numpadInput) ?: return
        if (amount <= Decimal.ZERO) return

        addPayment(paymentTypeId, amount)
        _uiState.update { it.copy(numpadInput = "") }
    }

    /**
     * Add a payment for the exact remaining amount.
     */
    fun addExactPayment(paymentTypeId: Long) {
        val remaining = _uiState.value.remainingAmount
        if (remaining <= Decimal.ZERO) return
        addPayment(paymentTypeId, remaining)
        _uiState.update { it.copy(numpadInput = "") }
    }

    // ========== STEP 3: PAYMENT ENTRY ==========

    /**
     * Update tip amount.
     *
     * @param tip Tip amount (must be >= 0)
     */
    fun updateTipAmount(tip: Decimal) {
        _uiState.update { it.copy(tipAmount = tip.coerceAtLeast(Decimal.ZERO)) }
        calculateTotals()
    }

    /**
     * Show add payment dialog.
     */
    fun showAddPaymentDialog(preSelectVoucher: Boolean = false) {
        _uiState.update { it.copy(showAddPaymentDialog = true, preSelectVoucher = preSelectVoucher) }
    }

    /**
     * Dismiss add payment dialog.
     */
    fun dismissAddPaymentDialog() {
        _uiState.update { it.copy(showAddPaymentDialog = false, preSelectVoucher = false) }
    }

    /**
     * Add a payment entry.
     *
     * @param paymentTypeId Payment type ID
     * @param amount Payment amount (must be > 0)
     */
    @OptIn(ExperimentalUuidApi::class)
    fun addPayment(
        paymentTypeId: Long,
        amount: Decimal,
    ) {
        if (amount <= Decimal.ZERO) {
            logger.w { "TableBillingViewModel.addPayment: attempted to add payment with invalid amount: $amount" }
            return
        }

        val paymentType = _uiState.value.paymentTypes.find { it.id == paymentTypeId }
        if (paymentType == null) {
            logger.w { "TableBillingViewModel.addPayment: payment type not found: $paymentTypeId" }
            return
        }

        val existPayment = _uiState.value.payments.find { it.paymentTypeId == paymentTypeId }

        if (existPayment != null) {
            _uiState.update { state ->
                state.copy(
                    payments =
                        state.payments.map { payment ->
                            if (payment.paymentTypeId == paymentTypeId) {
                                payment.copy(amount = payment.amount + amount)
                            } else {
                                payment
                            }
                        },
                    showAddPaymentDialog = false,
                )
            }
        } else {
            val payment =
                PaymentEntry(
                    id = Uuid.random().toString(),
                    paymentTypeId = paymentTypeId,
                    paymentTypeName = paymentType.getName(languageManager.currentLanguage.value),
                    amount = amount,
                )

            _uiState.update { state ->
                state.copy(
                    payments = state.payments + payment,
                    showAddPaymentDialog = false,
                )
            }
        }
        calculateTotals()
    }

    /**
     * Remove a payment entry.
     *
     * @param paymentId ID of payment to remove
     */
    fun removePayment(paymentId: String) {
        _uiState.update { state ->
            state.copy(payments = state.payments.filter { it.id != paymentId })
        }
        calculateTotals()
    }

    // ========== VOUCHER VALIDATION AND PAYMENT ==========

    /**
     * Validate a voucher code and requested amount.
     *
     * Calls the API to verify the voucher exists, is active, and has sufficient balance.
     * Stores validation result in UI state for display.
     *
     * @param voucherNumber Voucher code to validate (e.g., "G2025-0001")
     * @param requestedAmount Amount to redeem (optional, defaults to remaining balance)
     * @param sourceType Voucher source type for tracking
     */
    fun validateVoucher(
        voucherNumber: String,
        requestedAmount: Decimal?,
        sourceType: VoucherSourceType,
    ) {
        if (voucherNumber.isBlank()) {
            _uiState.update {
                it.copy(voucherValidationError = "Voucher number is required")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isValidatingVoucher = true,
                    voucherValidationError = null,
                    validatedVoucher = null,
                )
            }

            val request =
                VoucherDto.ValidateVoucherRequestDto(
                    voucherNumber = voucherNumber.trim().uppercase(),
                    requestedAmount = requestedAmount?.toPlainString(),
                )

            runCatchingApi { voucherApiClient.validateVoucher(request) }
                .onSuccess { response ->
                    if (response.isValid && response.isActive) {
                        val validated =
                            ValidatedVoucherInfo(
                                voucherNumber = response.voucherNumber,
                                originalValue = Decimal.parse(response.originalValue),
                                remainingBalance = Decimal.parse(response.remainingValue),
                                source = sourceType,
                                canRedeemAmount =
                                    if (response.canRedeemRequested) {
                                        requestedAmount ?: Decimal.parse(response.remainingValue)
                                    } else {
                                        Decimal.parse(response.remainingValue)
                                    },
                                expiryDate = response.expiryDate,
                            )

                        _uiState.update {
                            it.copy(
                                isValidatingVoucher = false,
                                validatedVoucher = validated,
                                voucherValidationError = null,
                            )
                        }

                        logger.i {
                            "TableBillingViewModel.validateVoucher: voucher validated ${response.voucherNumber}, balance=${response.remainingValue}"
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isValidatingVoucher = false,
                                voucherValidationError = response.validationMessage,
                            )
                        }
                        logger.w { "TableBillingViewModel.validateVoucher: validation failed - ${response.validationMessage}" }
                    }
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            isValidatingVoucher = false,
                            voucherValidationError = "Failed to validate voucher",
                        )
                    }
                    logger.e { "TableBillingViewModel.validateVoucher: error - $uiError" }
                }
        }
    }

    /**
     * Clear voucher validation state.
     */
    fun clearVoucherValidation() {
        _uiState.update {
            it.copy(
                validatedVoucher = null,
                voucherValidationError = null,
                isValidatingVoucher = false,
            )
        }
    }

    /**
     * Add a voucher payment with voucher information.
     *
     * Creates a PaymentEntry with voucher metadata for later extraction during bill creation.
     * Supports both validated (INTERNAL) and non-validated (LEGACY/EXTERNAL) vouchers.
     *
     * @param paymentTypeId Payment type ID for vouchers
     * @param redeemAmount Amount to redeem from voucher
     * @param voucherNumber Voucher number/code
     * @param voucherSource Voucher source type (INTERNAL, LEGACY, EXTERNAL, etc.)
     */
    @OptIn(ExperimentalUuidApi::class)
    fun addVoucherPayment(
        paymentTypeId: Long,
        redeemAmount: Decimal,
        voucherNumber: String,
        voucherSource: VoucherSourceType,
    ) {
        if (redeemAmount <= Decimal.ZERO) {
            logger.w { "TableBillingViewModel.addVoucherPayment: attempted to add voucher payment with invalid amount: $redeemAmount" }
            _uiState.update {
                it.copy(
                    error =
                        UiError.Unknown(
                            titleKey = CommonKeys.unknownErrorTitle,
                            messageKey = CommonKeys.unknownErrorMessage,
                        ),
                )
            }
            return
        }

        if (voucherNumber.isBlank()) {
            logger.w { "TableBillingViewModel.addVoucherPayment: attempted to add voucher payment without voucher number" }
            _uiState.update {
                it.copy(
                    error =
                        UiError.Unknown(
                            titleKey = CommonKeys.unknownErrorTitle,
                            messageKey = CommonKeys.unknownErrorMessage,
                        ),
                )
            }
            return
        }

        val paymentType = _uiState.value.paymentTypes.find { it.id == paymentTypeId }
        if (paymentType == null) {
            logger.w { "TableBillingViewModel.addVoucherPayment: payment type not found: $paymentTypeId" }
            return
        }

        // For validated vouchers (INTERNAL), use validated info
        // For non-validated vouchers (LEGACY/EXTERNAL), use provided info
        val validated = _uiState.value.validatedVoucher
        val payment =
            if (validated != null && voucherSource == VoucherSourceType.INTERNAL) {
                PaymentEntry(
                    id = Uuid.random().toString(),
                    paymentTypeId = paymentTypeId,
                    paymentTypeName = paymentType.getName(languageManager.currentLanguage.value),
                    amount = redeemAmount,
                    voucherNumber = validated.voucherNumber,
                    voucherOriginalValue = validated.originalValue,
                    voucherRemainingBalance = validated.remainingBalance,
                    voucherSource = validated.source,
                )
            } else {
                // Non-validated voucher (LEGACY/EXTERNAL)
                PaymentEntry(
                    id = Uuid.random().toString(),
                    paymentTypeId = paymentTypeId,
                    paymentTypeName = paymentType.getName(languageManager.currentLanguage.value),
                    amount = redeemAmount,
                    voucherNumber = voucherNumber,
                    voucherOriginalValue = null, // Unknown for non-validated
                    voucherRemainingBalance = null, // Unknown for non-validated
                    voucherSource = voucherSource,
                )
            }

        _uiState.update { state ->
            state.copy(
                payments = state.payments + payment,
                showAddPaymentDialog = false,
                validatedVoucher = null, // Clear after use
                voucherValidationError = null,
            )
        }

        calculateTotals()
        logger.i {
            "TableBillingViewModel.addVoucherPayment: added voucher payment $voucherNumber (${voucherSource.name}), amount=${redeemAmount.toPlainString()}"
        }
    }

    /**
     * Check if payment is complete (paid amount matches grand total).
     */
    fun isPaymentComplete(): Boolean {
        val state = _uiState.value
        return state.remainingAmount == Decimal.ZERO && state.payments.isNotEmpty()
    }

    /**
     * Get remaining amount to be paid.
     */
    fun getRemainingAmount(): Decimal = _uiState.value.remainingAmount

    // ========== PROMOTIONS ==========

    /**
     * Show the promotion selection dialog.
     */
    fun showPromotionDialog() {
        _uiState.update { it.copy(showPromotionDialog = true) }
    }

    /**
     * Hide the promotion selection dialog.
     */
    fun hidePromotionDialog() {
        _uiState.update { it.copy(showPromotionDialog = false) }
    }

    /**
     * Add a promotion to the current bill.
     *
     * Calculates a client-side deduction estimate for display, then recalculates totals.
     *
     * @param applyRequest The promotion apply request (promotionId + optional orderItemId)
     * @param promotion The full promotion response DTO for metadata
     */
    fun addPromotion(
        applyRequest: PromotionDto.PromotionApplyRequestDto,
        promotion: PromotionDto.PromotionResponseDto,
    ) {
        val state = _uiState.value
        val alreadyApplied = state.selectedPromotions.filter { it.promotionId == applyRequest.promotionId }

        // Mirror of the server guard: a non-combinable promotion may not share a bill with another,
        // distinct promotion. The dialog already disables such a row, so reaching here means a stale
        // promotion list - surface it rather than sending a bill the server will reject with 409.
        if (blocksPromotionCombination(applyRequest.promotionId, promotion.combinable, state.selectedPromotions)) {
            logger.w { "TableBillingViewModel.addPromotion: ${promotion.name} cannot be combined with the promotions already applied" }
            _uiState.update {
                it.copy(
                    error =
                        UiError.Unknown(
                            titleKey = CommonKeys.unknownErrorTitle,
                            messageKey = PromotionKeys.notCombinableError,
                        ),
                )
            }
            return
        }

        // BILL_WIDE: max 1 per bill
        if (promotion.scope == "BILL_WIDE" && alreadyApplied.isNotEmpty()) {
            logger.w { "TableBillingViewModel.addPromotion: BILL_WIDE promotion ${promotion.name} already applied" }
            return
        }

        // ITEM_SPECIFIC + !isMultiUse: max 1 per bill
        if (promotion.scope == "ITEM_SPECIFIC" && !promotion.isMultiUse && alreadyApplied.isNotEmpty()) {
            logger.w { "TableBillingViewModel.addPromotion: non-multi-use ITEM_SPECIFIC promotion ${promotion.name} already applied" }
            return
        }

        // ITEM_SPECIFIC + isMultiUse: check same item not already applied
        if (promotion.scope == "ITEM_SPECIFIC" && promotion.isMultiUse) {
            val sameItem = alreadyApplied.any { it.orderItemId == applyRequest.orderItemId }
            if (sameItem) {
                logger.w { "TableBillingViewModel.addPromotion: promotion ${promotion.name} already applied to item ${applyRequest.orderItemId}" }
                return
            }
        }

        val deductedAmount =
            calculatePromotionDeduction(
                PromotionTerms(promotion.discountType, promotion.discountValue, promotion.scope, applyRequest.orderItemId),
                state,
                state.selectedPromotions,
            )

        val applied =
            AppliedPromotion(
                promotionId = applyRequest.promotionId,
                promotionName = promotion.name,
                discountType = promotion.discountType,
                discountValue = promotion.discountValue,
                scope = promotion.scope,
                combinable = promotion.combinable,
                orderItemId = applyRequest.orderItemId,
                deductedAmount = deductedAmount,
            )

        _uiState.update { current ->
            current.copy(
                selectedPromotions = reestimate(current.selectedPromotions + applied, current),
                showPromotionDialog = false,
            )
        }
        calculateTotals()
        logger.i {
            "TableBillingViewModel.addPromotion: applied ${promotion.name} (${promotion.discountType}: ${promotion.discountValue}), estimated deduction=$deductedAmount"
        }
    }

    /**
     * Remove a promotion from the current bill by promotion ID.
     */
    fun removePromotion(promotionId: Long) {
        _uiState.update { current ->
            current.copy(
                selectedPromotions = reestimate(current.selectedPromotions.filter { it.promotionId != promotionId }, current),
            )
        }
        calculateTotals()
    }

    /**
     * Re-estimates [promotions], each against the ones worked out before it. A promotion's deduction
     * depends on what the earlier ones left (an item's rest, the bill's rest), so adding or removing
     * one must re-run the rest — otherwise a promotion capped by the removed one keeps its capped
     * amount and the till asks for more than the server will bill.
     *
     * The work order is the server's (`BillServiceImpl`: ITEM_SPECIFIC first, then BILL_WIDE, the
     * pick order within each), not the order the cashier picked them in: a 10 % bill promotion
     * works on the bill left after the item promotions whichever was picked first. The returned list
     * keeps the pick order, so the review screen lists them as they were chosen.
     */
    private fun reestimate(
        promotions: List<AppliedPromotion>,
        state: TableBillingUiState,
    ): List<AppliedPromotion> {
        val workOrder = promotions.indices.sortedBy { if (promotions[it].scope == "ITEM_SPECIFIC") 0 else 1 }
        val worked = mutableListOf<AppliedPromotion>()
        val byIndex = HashMap<Int, AppliedPromotion>()
        for (index in workOrder) {
            val applied = promotions[index]
            val terms = PromotionTerms(applied.discountType, applied.discountValue, applied.scope, applied.orderItemId)
            val estimated = applied.copy(deductedAmount = calculatePromotionDeduction(terms, state, worked))
            worked += estimated
            byIndex[index] = estimated
        }
        return promotions.indices.map { byIndex.getValue(it) }
    }

    /**
     * Calculate an estimated promotion deduction for display purposes.
     *
     * The server recalculates the real deduction during bill creation.
     * This estimate must match the server's calculation to avoid payment validation failures.
     */
    private fun calculatePromotionDeduction(
        promotion: PromotionTerms,
        state: TableBillingUiState,
        prior: List<AppliedPromotion>,
    ): Decimal {
        // ITEM_SPECIFIC on a known item: the shared estimate (the server's per-item price and cap).
        estimateItemPromotionDeduction(promotion, state.orderItems, prior)?.let { return it }

        val billTotal = state.billTotal
        val priorDeductions = prior.fold(Decimal.ZERO) { acc, p -> acc + p.deductedAmount }

        // BILL_WIDE: bounded by the bill total left after prior deductions
        val remaining = (billTotal - priorDeductions).coerceAtLeast(Decimal.ZERO)
        return when (promotion.discountType) {
            "FIXED_AMOUNT" -> {
                val value = Decimal.parseOrNull(promotion.discountValue) ?: Decimal.ZERO
                if (value < remaining) value else remaining
            }
            "PERCENTAGE" -> {
                val percentage = Percentage(Decimal.parseOrNull(promotion.discountValue) ?: Decimal.ZERO)
                val deduction = remaining.times(percentage.toFraction(), 2, MoneyRounding.HALF_UP)
                if (deduction < remaining) deduction else remaining
            }
            else -> Decimal.ZERO
        }
    }

    // ========== STEP 4: BILL CREATION ==========

    /**
     * Confirm and create the bill.
     */
    fun confirmBill() {
        // An invalid configured currency blocks billing: refuse to submit stale totals.
        if (resolveCurrency() == null) return

        if (!isPaymentComplete()) {
            _uiState.update {
                it.copy(
                    error =
                        UiError.Unknown(
                            titleKey = CommonKeys.unknownErrorTitle,
                            messageKey = CommonKeys.unknownErrorMessage,
                        ),
                )
            }
            return
        }

        if (!hasSelectedItems()) {
            _uiState.update {
                it.copy(
                    error =
                        UiError.Unknown(
                            titleKey = CommonKeys.unknownErrorTitle,
                            messageKey = CommonKeys.unknownErrorMessage,
                        ),
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val request = createBillRequest()

            runCatchingApi { billApiClient.createBill(request) }
                .onSuccess { bill ->
                    logger.i { "TableBillingViewModel.confirmBill: bill created successfully ${bill.billNumber}" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            createdBill = bill,
                            currentStep = BillingStep.RECEIPT,
                            showReceiptDialog = true,
                            error = null,
                            successMessage = localized(BillReceiptKeys.successMessage),
                        )
                    }
                }.onError { uiError ->
                    logger.e { "TableBillingViewModel.confirmBill: failed to create bill - $uiError" }
                    _uiState.update {
                        it.copy(isLoading = false, error = uiError)
                    }
                }
        }
    }

    /**
     * Dismiss receipt dialog (triggers navigation back).
     */
    fun dismissReceipt() {
        _uiState.update { it.copy(showReceiptDialog = false) }
    }

    // ========== CALCULATIONS ==========

    /**
     * Resolve the configured currency, routing an invalid code to the billing
     * error path. Never defaults silently.
     */
    private fun resolveCurrency(): Currency? =
        try {
            Currency.parse(settingManager.defaultCurrency.value)
        } catch (e: CurrencyFormatException) {
            logger.e { "TableBillingViewModel.resolveCurrency: invalid configured currency '${settingManager.defaultCurrency.value}' - $e" }
            _uiState.update {
                it.copy(
                    error =
                        UiError.Unknown(
                            titleKey = CommonKeys.unknownErrorTitle,
                            messageKey = BillingKeys.errorOccurred,
                            cause = e,
                        ),
                )
            }
            null
        }

    /**
     * Calculate all financial totals (subtotal, tax, grand total, remaining).
     *
     * All calculations use Decimal for precision.
     */
    private fun calculateTotals() {
        val state = _uiState.value
        val selectedItems = state.orderItems.filter { it.isSelected }

        // Server contract: net = gross / (1 + rate), HALF_UP 2dp; vat = gross - net
        val currency = resolveCurrency() ?: return

        // Calculate subtotal (sum of all selected item prices - already includes tax)
        val subtotal: Decimal = selectedItems.fold(Decimal.ZERO) { acc, item -> acc + item.totalPrice }

        // Calculate tax breakdown by VAT rate
        val taxBreakdown =
            selectedItems
                .groupBy { it.item.vatRate }
                .map { (vatRate, items) ->
                    val totalWithTax: Decimal = items.fold(Decimal.ZERO) { acc, item -> acc + item.totalPrice }
                    val vatRateDecimal: Decimal = Decimal.parse(vatRate)

                    val split = BillingMoneyMath.vatSplit(totalWithTax, vatRateDecimal, currency)
                    val taxableAmount: Decimal = split.net.amount
                    val taxAmount: Decimal = split.vat.amount

                    TaxBreakdown(
                        vatRate = vatRateDecimal,
                        taxableAmount = taxableAmount,
                        taxAmount = taxAmount,
                    )
                }

        val taxTotal: Decimal = taxBreakdown.fold(Decimal.ZERO) { acc, b -> acc + b.taxAmount }

        // One derivation of every money figure, so the payment header's "Gesamt" cannot drift from
        // the "Verbleibend" printed beside it (E2E-2026-09-20 #9). Prices already include tax.
        val totals =
            BillingTotals.of(
                subtotal = subtotal,
                promotionDeduction = state.selectedPromotions.fold(Decimal.ZERO) { acc, p -> acc + p.deductedAmount },
                paidAmount = state.payments.fold(Decimal.ZERO) { acc, p -> acc + p.amount },
            )

        _uiState.update {
            it.copy(
                selectedItemsTotal = subtotal,
                taxBreakdown = taxBreakdown,
                subtotal = subtotal - taxTotal,
                taxTotal = taxTotal,
                billTotal = totals.grossTotal,
                grandTotal = totals.payableTotal,
                paidAmount = totals.paidAmount,
                remainingAmount = totals.remainingAmount,
                tipAmount = totals.tipAmount,
            )
        }
    }

    /**
     * Create bill request DTO from current state.
     */
    private fun createBillRequest(): BillDto.CreateBillRequestDto {
        val state = _uiState.value

        // Collect order item IDs based on selected quantities
        val orderItemIds = mutableListOf<Long>()
        state.orderItems.filter { it.isSelected }.forEach { item ->
            // For items with partial quantities, we need to handle the split
            // For now, we include all IDs if any quantity is selected
            // TODO: Server-side may need to handle partial quantities differently
            orderItemIds.addAll(item.orderItemIds.take(item.selectedQuantity))
        }

        // Separate voucher payments from regular payments
        val voucherPayments = state.payments.filter { it.isVoucherPayment }
        val regularPayments = state.payments.filter { !it.isVoucherPayment }

        // Build regular payment requests
        val paymentRequests =
            state.payments.map { payment ->
                PaymentDto.PaymentRequest(
                    amount = payment.amount.toPlainString().trim(),
                    paymentTypeId = payment.paymentTypeId,
                )
            }

        // Build voucher redeem requests
        val voucherRedeemRequests =
            voucherPayments.map { payment ->
                VoucherDto.RedeemVoucherRequestDto(
                    voucherNumber = payment.voucherNumber ?: throw IllegalStateException("Voucher number missing"),
                    redeemedAmount = payment.amount.toPlainString().trim(),
                    voucherSource = payment.voucherSource ?: throw IllegalStateException("Voucher source missing"),
                )
            }

        // Build promotion apply requests
        val promotionRequests =
            state.selectedPromotions.map {
                PromotionDto.PromotionApplyRequestDto(
                    promotionId = it.promotionId,
                    orderItemId = it.orderItemId,
                )
            }

        return BillDto.CreateBillRequestDto(
            tableSessionId = state.sessionId ?: throw IllegalStateException("No session ID"),
            orderItems = orderItemIds,
            billAmount = state.billTotal.toPlainString(),
            tips = state.tipAmount.toPlainString(),
            payments = paymentRequests,
            voucherRedeem = voucherRedeemRequests,
            promotions = promotionRequests,
        )
    }

    /**
     * Clear any error messages.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear success message.
     */
    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }
}
