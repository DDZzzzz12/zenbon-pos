/**
 * Table billing screen with adaptive layout using Pos design system components.
 *
 * Implements step-by-step billing flow for phone (compact) and two-column
 * layout for tablet/desktop (expanded).
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.screens.billing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.presentation.strings.keys.PromotionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.features.dashboard.presentation.state.BillingStep
import org.example.project.features.dashboard.presentation.viewmodel.TableBillingViewModel
import org.example.project.features.promotion.presentation.components.PromotionSelectionDialog
import org.example.project.money.Decimal
import org.example.project.ui.components.PosBillCreatedActionsDialog
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosFilledIconButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSuccessWatcher
import org.koin.compose.viewmodel.koinViewModel

/**
 * Main billing screen with adaptive layout using Pos design components.
 *
 * - Compact (phone): step-by-step flow with step indicator dots
 * - Expanded (tablet/desktop): two-column layout with items + payment side-by-side
 *
 * @param tableId Table ID to create bill for
 * @param onNavigateBack Callback to navigate back
 * @param viewModel Billing view model
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosTableBillingScreen(
    tableId: Long,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TableBillingViewModel = koinViewModel(),
) {
    val windowSize = LocalWindowSizeClass.current
    val uiState by viewModel.uiState.collectAsState()
    val currentLanguage = LocalLanguageManager.current.currentLanguage.value
    val overlay = LocalGlobalOverlayViewModel.current
    val isCompact = windowSize.isCompact()

    // ========== LaunchedEffects (copied from existing TableBillingScreen) ==========

    // Load data on first composition
    LaunchedEffect(tableId) {
        viewModel.loadTableData(tableId)
    }

    LaunchedEffect(uiState.sessionId) {
        viewModel.loadItems()
    }

    // Show loading notification for billing operation
    var billingLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            billingLoadingId =
                overlay.showLoading(
                    title =
                        getString(
                            BillingKeys.screenTitle,
                            currentLanguage,
                            uiState.tableNumber,
                            getString(BillingKeys.step2Label, currentLanguage),
                        ),
                    message = getString(BillingKeys.creatingBill, currentLanguage),
                )
        } else {
            billingLoadingId?.let {
                overlay.dismissById(it)
                billingLoadingId = null
            }
        }
    }

    PosErrorWatcher(
        error = uiState.error,
        title =
            getString(
                BillingKeys.screenTitle,
                currentLanguage,
                uiState.tableNumber,
                getString(BillingKeys.step2Label, currentLanguage),
            ),
        onClear = { viewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
        retryAction = { viewModel.loadTableData(tableId) },
    )

    // Handle success messages via global notification system
    PosSuccessWatcher(
        message = uiState.successMessage,
        title =
            getString(
                BillingKeys.screenTitle,
                currentLanguage,
                uiState.tableNumber,
                getString(BillingKeys.step2Label, currentLanguage),
            ),
        onClear = { viewModel.clearSuccessMessage() },
    )

    // Navigate back after receipt dismissal
    if (uiState.createdBill != null && !uiState.showReceiptDialog) {
        LaunchedEffect(Unit) {
            if (!viewModel.hasItemsLeft()) {
                onNavigateBack()
            } else {
                viewModel.resetBillingState()
                viewModel.loadTableData(tableId)
                viewModel.loadItems()
            }
        }
    }

    // BackHandler: step-dependent on phone
    BackHandler {
        if (!isCompact) {
            onNavigateBack()
        } else {
            when (uiState.currentStep) {
                BillingStep.SELECT_ITEMS -> onNavigateBack()
                BillingStep.REVIEW_SELECTION -> viewModel.goBackToItemSelection()
                BillingStep.ENTER_PAYMENTS -> viewModel.goToPreviousStep()
                BillingStep.RECEIPT -> { /* Receipt shown via panel */ }
            }
        }
    }

    var showPaymentTypePicker by remember { mutableStateOf(false) }
    var pickerAmount by remember { mutableStateOf(Decimal.ZERO) }

    val onEnterPressed: () -> Unit = {
        val inputAmount = viewModel.getNumpadAmount()
        val remaining = viewModel.getRemainingAmount()
        val amount = if (inputAmount > Decimal.ZERO) inputAmount else remaining
        if (amount > Decimal.ZERO) {
            if (inputAmount == Decimal.ZERO) {
                viewModel.setNumpadToRemaining()
            }
            pickerAmount = amount
            showPaymentTypePicker = true
        }
    }

    val isSplit =
        uiState.orderItems.any { it.isSelected } &&
            !uiState.orderItems.all { it.selectedQuantity == it.maxQuantity }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PosMarqueeText(
                            text =
                                if (!isCompact) {
                                    str(BillingKeys.screenTitle, uiState.tableNumber, str(BillingKeys.step2Label))
                                } else {
                                    val stepLabel =
                                        when (uiState.currentStep) {
                                            BillingStep.SELECT_ITEMS -> str(BillingKeys.step1Label)
                                            BillingStep.REVIEW_SELECTION -> str(BillingKeys.step2Label)
                                            BillingStep.ENTER_PAYMENTS -> str(BillingKeys.step3Label)
                                            BillingStep.RECEIPT -> str(BillingKeys.step4Label)
                                        }
                                    str(BillingKeys.screenTitle, uiState.tableNumber, stepLabel)
                                },
                        )
                        if (isSplit) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = str(BillingKeys.splitBadge),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    PosNavIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = str(BillingKeys.backButton),
                        onClick = {
                            if (!isCompact) {
                                onNavigateBack()
                            } else {
                                when (uiState.currentStep) {
                                    BillingStep.SELECT_ITEMS -> onNavigateBack()
                                    BillingStep.REVIEW_SELECTION -> viewModel.goBackToItemSelection()
                                    BillingStep.ENTER_PAYMENTS -> viewModel.goToPreviousStep()
                                    BillingStep.RECEIPT -> { /* Receipt shown via panel */ }
                                }
                            }
                        },
                    )
                },
            )
        },
    ) { paddingValues ->
        Box(modifier = modifier.fillMaxSize().padding(paddingValues)) {
            when {
                windowSize.isCompact() -> {
                    CompactBillingContent(
                        viewModel = viewModel,
                        onEnterPressed = onEnterPressed,
                        onConfirmBill = { viewModel.confirmBill() },
                        isSplit = isSplit,
                    )
                }

                else -> {
                    ExpandedBillingContent(
                        viewModel = viewModel,
                        onEnterPressed = onEnterPressed,
                        onConfirmBill = { viewModel.confirmBill() },
                        isSplit = isSplit,
                    )
                }
            }

            if (showPaymentTypePicker) {
                PaymentTypePickerDialog(
                    amount = pickerAmount,
                    paymentTypes = uiState.paymentTypes,
                    language = currentLanguage,
                    onPaymentConfirmed = { typeId, amountPaid ->
                        viewModel.addPayment(typeId, amountPaid)
                        viewModel.onNumpadClear()
                        showPaymentTypePicker = false
                    },
                    onDismiss = { showPaymentTypePicker = false },
                    isVoucherEnabled = uiState.voucherConfig?.enabled == true,
                    enabledVoucherSources = uiState.enabledVoucherSources,
                    isValidatingVoucher = uiState.isValidatingVoucher,
                    validatedVoucher = uiState.validatedVoucher,
                    voucherValidationError = uiState.voucherValidationError,
                    onVoucherConfirmed = { typeId, amountPaid, voucherNumber, sourceType ->
                        viewModel.addVoucherPayment(typeId, amountPaid, voucherNumber, sourceType)
                        viewModel.onNumpadClear()
                        showPaymentTypePicker = false
                    },
                    onValidateVoucher = viewModel::validateVoucher,
                    onClearVoucherValidation = viewModel::clearVoucherValidation,
                )
            }

            // Promotion selection dialog
            if (uiState.showPromotionDialog) {
                PromotionSelectionDialog(
                    billItems =
                        uiState.orderItems
                            .filter { it.isSelected }
                            .map { it.item },
                    appliedPromotions = uiState.selectedPromotions,
                    onDismiss = { viewModel.hidePromotionDialog() },
                    onPromotionSelected = { applyRequest, promotion ->
                        viewModel.addPromotion(applyRequest, promotion)
                    },
                )
            }

            // Post-creation actions dialog (full receipt is shown on the reports screen)
            PosBillCreatedActionsDialog(
                isVisible = uiState.showReceiptDialog,
                bill = uiState.createdBill,
                language = currentLanguage,
                onDismiss = { viewModel.dismissReceipt() },
                created = true,
            )
        }
    }
}

// ========== COMPACT LAYOUT (Phone — Step-by-Step) ==========

/**
 * Compact (phone) billing layout with step-by-step flow and step indicator dots.
 */
@Composable
private fun CompactBillingContent(
    viewModel: TableBillingViewModel,
    onEnterPressed: () -> Unit,
    onConfirmBill: () -> Unit,
    isSplit: Boolean,
) {
    val uiState by viewModel.uiState.collectAsState()
//    val totalSteps = 3 // SELECT_ITEMS, REVIEW_SELECTION, ENTER_PAYMENTS
//    val currentStepIndex = when (uiState.currentStep) {
//        BillingStep.SELECT_ITEMS -> 0
//        BillingStep.REVIEW_SELECTION -> 1
//        BillingStep.ENTER_PAYMENTS -> 2
//        BillingStep.RECEIPT -> 2
//    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Step indicator dots
//        StepIndicator(
//            totalSteps = totalSteps,
//            currentStep = currentStepIndex,
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(horizontal = 16.dp, vertical = 8.dp)
//        )

        // Step content
        when (uiState.currentStep) {
            BillingStep.SELECT_ITEMS -> {
                // Item list
                PosBillingItemList(
                    orderItems = uiState.orderItems,
                    onItemQuantityChange = { itemIds, qty ->
                        viewModel.updateItemQuantity(itemIds, qty)
                    },
                    onSelectAll = { viewModel.selectAllItems() },
                    onClearSelection = { viewModel.clearSelection() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )

                // Footer: selected summary + Next button
                HorizontalDivider()
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text =
                                str(
                                    BillingKeys.selectedItemsCount,
                                    uiState.selectedArticleCount,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = uiState.selectedItemsTotal.toFormattedCurrency(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    PosButton(
                        text = str(BillingKeys.continueButton),
                        onClick = { viewModel.goToReviewStep() },
                        enabled = viewModel.hasSelectedItems(),
                    )
                }
            }

            BillingStep.REVIEW_SELECTION -> {
                val selectedItems = viewModel.getSelectedItemsSummary()

                // Review panel
                PosBillingReviewPanel(
                    selectedItems = selectedItems,
                    articleCount = uiState.selectedArticleCount,
                    taxBreakdown = uiState.taxBreakdown,
                    subtotal = uiState.subtotal,
                    taxTotal = uiState.taxTotal,
                    billTotal = uiState.billTotal,
                    isSplit = isSplit,
                    appliedPromotions = uiState.selectedPromotions,
                    promotionTotal = uiState.promotionTotal,
                    onRemovePromotion = { viewModel.removePromotion(it) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )

                // Footer: Back/Promotion/Pay buttons
                HorizontalDivider()
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PosIconButton(
                        icon = Icons.Filled.Edit,
                        contentDescription = str(BillingKeys.editItemsButton),
                        onClick = { viewModel.goBackToItemSelection() },
                        size = 48.dp,
                        modifier = Modifier.weight(0.5f).fillMaxWidth(),
                    )
                    PosIconButton(
                        icon = Icons.Filled.LocalOffer,
                        contentDescription = str(PromotionKeys.applyPromotion),
                        onClick = { viewModel.showPromotionDialog() },
                        size = 48.dp,
                        modifier = Modifier.weight(0.5f).fillMaxWidth(),
                    )

                    Spacer(Modifier.weight(1f))
                    PosFilledIconButton(
                        icon = Icons.Filled.Payment,
                        contentDescription = str(BillingKeys.addPaymentsButton),
                        onClick = { viewModel.goToPaymentStep() },
                        size = 48.dp,
                        iconSize = 48.dp,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }

            BillingStep.ENTER_PAYMENTS -> {
                // Payment panel
                PosBillingPaymentPanel(
                    numpadInput = uiState.numpadInput,
                    paidAmount = uiState.paidAmount,
                    remainingAmount = uiState.remainingAmount,
                    totalAmount = uiState.grandTotal,
                    payments = uiState.payments,
                    onNumpadKey = viewModel::onNumpadKey,
                    onNumpadBackspace = viewModel::onNumpadBackspace,
                    onNumpadClear = viewModel::onNumpadClear,
                    onEnterPressed = onEnterPressed,
                    onRemovePayment = viewModel::removePayment,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    tipAmount = uiState.tipAmount,
                )

                // Confirm Bill button at bottom when payment is complete
                if (viewModel.isPaymentComplete()) {
                    HorizontalDivider()
                    PosButton(
                        text = str(BillingKeys.confirmBillButton),
                        onClick = onConfirmBill,
                        enabled = viewModel.isPaymentComplete() && viewModel.hasSelectedItems(),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            BillingStep.RECEIPT -> {
                // Receipt shown via BillReceiptPanel overlay
            }
        }
    }
}

// ========== EXPANDED LAYOUT (Tablet/Desktop — 2 Columns) ==========

/**
 * Expanded (tablet/desktop) billing layout with two columns:
 * Left: item selection + review summary
 * Right: payment panel (~380dp)
 */
@Composable
private fun ExpandedBillingContent(
    viewModel: TableBillingViewModel,
    onEnterPressed: () -> Unit,
    onConfirmBill: () -> Unit,
    isSplit: Boolean,
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedItems = viewModel.getSelectedItemsSummary()

    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // LEFT COLUMN: Item selection + review summary below
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
        ) {
            // Item selection list
            PosBillingItemList(
                orderItems = uiState.orderItems,
                onItemQuantityChange = { itemIds, qty ->
                    viewModel.updateItemQuantity(itemIds, qty)
                },
                onSelectAll = { viewModel.selectAllItems() },
                onClearSelection = { viewModel.clearSelection() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // Tax breakdown review at bottom of left column
            if (uiState.taxBreakdown.isNotEmpty()) {
                HorizontalDivider()
                PosBillingReviewPanel(
                    selectedItems = selectedItems,
                    articleCount = uiState.selectedArticleCount,
                    taxBreakdown = uiState.taxBreakdown,
                    subtotal = uiState.subtotal,
                    taxTotal = uiState.taxTotal,
                    billTotal = uiState.billTotal,
                    isSplit = isSplit,
                    appliedPromotions = uiState.selectedPromotions,
                    promotionTotal = uiState.promotionTotal,
                    onRemovePromotion = { viewModel.removePromotion(it) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(0.6f),
                )
                PosSecondaryButton(
                    text = str(PromotionKeys.applyPromotion),
                    onClick = { viewModel.showPromotionDialog() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        // Vertical divider
        VerticalDivider(modifier = Modifier.fillMaxHeight().padding(horizontal = 4.dp))

        // RIGHT COLUMN: Payment panel (~380dp)
        Column(
            modifier =
                Modifier
                    .width(380.dp)
                    .fillMaxHeight(),
        ) {
            PosBillingPaymentPanel(
                numpadInput = uiState.numpadInput,
                paidAmount = uiState.paidAmount,
                remainingAmount = uiState.remainingAmount,
                totalAmount = uiState.grandTotal,
                payments = uiState.payments,
                onNumpadKey = viewModel::onNumpadKey,
                onNumpadBackspace = viewModel::onNumpadBackspace,
                onNumpadClear = viewModel::onNumpadClear,
                onEnterPressed = onEnterPressed,
                onRemovePayment = viewModel::removePayment,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                tipAmount = uiState.tipAmount,
            )

            // Confirm Bill button
            HorizontalDivider()
            PosButton(
                text = str(BillingKeys.confirmBillButton),
                onClick = onConfirmBill,
                enabled = viewModel.isPaymentComplete() && viewModel.hasSelectedItems(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
            )
        }
    }
}

// ========== Step Indicator ==========

/**
 * Simple step indicator with filled/empty circles.
 *
 * @param totalSteps Total number of steps
 * @param currentStep Zero-based index of current step
 */
@Composable
private fun StepIndicator(
    totalSteps: Int,
    currentStep: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalSteps) { index ->
            val isCurrent = index <= currentStep
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
            )
            if (index < totalSteps - 1) {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}
