package org.example.project.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.auth.LocalPermissionManager
import org.example.project.core.auth.LocalTemporarySessionManager
import org.example.project.core.auth.trackPrivilegedOperation
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.dashboard.presentation.components.billing.RebookDialog
import org.example.project.features.dashboard.presentation.components.billing.StornoRestoreConflictDialog
import org.example.project.features.dashboard.presentation.components.billing.StornoRestoreSessionToggle
import org.example.project.features.dashboard.presentation.viewmodel.BillReceiptViewModel
import org.example.project.ui.auth.holds
import org.example.project.ui.auth.rememberIsStation
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.screens.dashboard.TableBillingActions
import org.koin.compose.viewmodel.koinViewModel

/**
 * Overlay wrapper for PosBillDetailPanel that handles:
 * - Slide-in/out animation with scrim
 * - BillReceiptViewModel integration for print/delete operations
 * - Delete confirmation dialog
 * - Loading/error notification handling
 * - Manager permission checks
 *
 * Replaces the old BillReceiptPanel as a drop-in replacement.
 *
 * Related-bill links (Storno ↔ original) are tappable when the host passes
 * [onOpenLinkedBill]; the host then swaps [bill] for the linked one and passes [backTo]/[onBack] so
 * the panel offers "Zurück zu RE-…" — and the system back gesture goes back a bill before it closes
 * the panel. The receipt reprint path is untouched: actions always act on the bill on screen.
 *
 * A Zahlartwechsel (Storno + Neubuchung) books a NEW bill: [onRebooked] hands it to the host, which
 * swaps it in as [bill] (the original going onto the back trail), and the overlay then opens the
 * bill actions for it so the new receipt can be printed right away. A refused or failed rebook
 * calls [onRebookFailed] with the bill's id so the host re-reads it — it may have been stornoed
 * meanwhile, or the rebook may have committed before a TSE failure — and the dialog closes once
 * the bill on screen is no longer rebookable.
 *
 * A Storno (with or without re-opening the table) calls [onVoided] with the stornoed bill's id
 * before the panel closes, so the host reloads its list (reading the new Storno Beleg through it).
 * [onBillChanged] names a bill the host must re-read along with its list: a refused Storno (it may
 * have been stornoed or rebooked on another device), an invoice issued for it, or a receivable
 * settled at the till (which books a clearing Beleg).
 */
@Composable
fun PosBillReceiptOverlay(
    isVisible: Boolean,
    bill: BillDto.BillResponseDto?,
    tableNumber: String,
    language: Language,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    created: Boolean = false,
    compact: Boolean = false,
    onOpenLinkedBill: ((BillDto.BillLinkDto) -> Unit)? = null,
    backTo: BillDto.BillLinkDto? = null,
    onBack: (() -> Unit)? = null,
    onRebooked: (BillDto.BillResponseDto) -> Unit = {},
    onRebookFailed: (billId: Long) -> Unit = {},
    onVoided: (billId: Long) -> Unit = {},
    onBillChanged: (billId: Long) -> Unit = {},
) {
    val viewModel: BillReceiptViewModel = koinViewModel()
    val permissionManager = LocalPermissionManager.current
    val sessionManager = LocalTemporarySessionManager.current
    val overlay = LocalGlobalOverlayViewModel.current
    // DELETE /api/v1/bills/{id} is Cap(BILL_VOID) — the TSE-signed reversal. Gating the control on
    // the capability the route names is the point: a purpose-built group granted BILL_VOID now sees
    // the button, and `isManagerOrHigher()` could never say that.
    //
    // It also needs a session that bills at all (OS-b): a step-up at an order station lends
    // BILL_VOID, and the station account still does not Storno — the server refuses it on the same
    // `BillingAuthority` answer.
    val billingOffered = TableBillingActions.billingOffered(rememberIsStation())
    val canVoidBill = billingOffered && holds(Capability.BILL_VOID)
    // POST /api/v1/bills/{id}/rebook is Cap(BILL_CHANGE_PAYMENT); the station mask holds no BILL_*,
    // so the Zahlartwechsel button goes with it — the server refuses the call on the same answer.
    val canChangePayment = billingOffered && holds(Capability.BILL_CHANGE_PAYMENT)
    // Lowering the tip in a Zahlartwechsel takes back booked money, so it needs the Storno
    // capability too; the server refuses it with BILL_REBOOK_TIP_DECREASE_FORBIDDEN otherwise.
    val mayLowerRebookTip = canVoidBill

    val billDeleted by viewModel.billDeleted.collectAsState()
    val stornoRestoreConflict by viewModel.stornoRestoreConflict.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val rebookedBill by viewModel.rebookedBill.collectAsState()
    val rebookFailedBillId by viewModel.rebookFailedBillId.collectAsState()
    val stornoFailedBillId by viewModel.stornoFailedBillId.collectAsState()
    val paymentTypesState by viewModel.paymentTypes.collectAsState()
    val voucherConfig by viewModel.voucherConfig.collectAsState()
    val enabledVoucherSources by viewModel.enabledVoucherSources.collectAsState()
    val isValidatingVoucher by viewModel.isValidatingVoucher.collectAsState()
    val validatedVoucher by viewModel.validatedVoucher.collectAsState()
    val voucherValidationError by viewModel.voucherValidationError.collectAsState()

    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var deletionReason by remember { mutableStateOf("") }
    var restoreSession by remember { mutableStateOf(true) }
    var showActionsDialog by remember { mutableStateOf(false) }
    // The actions dialog opened by a just-booked Zahlartwechsel confirms it in its title; opened later
    // through *Aktionen* it is the ordinary bill-actions dialog (emu21 NOTE-z).
    var actionsAfterRebook by remember { mutableStateOf(false) }
    var showChangePayment by remember { mutableStateOf(false) }

    // A Zahlartwechsel booked a new bill: close the dialog, let the host show the new bill, and
    // offer its receipt straight away through the ordinary bill actions.
    LaunchedEffect(rebookedBill) {
        val newBill = rebookedBill ?: return@LaunchedEffect
        showChangePayment = false
        onRebooked(newBill)
        viewModel.clearRebookedBill()
        actionsAfterRebook = true
        showActionsDialog = true
    }

    // A refused or failed rebook: the host re-reads the bill, so a stale original stops offering it.
    LaunchedEffect(rebookFailedBillId) {
        val billId = rebookFailedBillId ?: return@LaunchedEffect
        onRebookFailed(billId)
        viewModel.clearRebookFailed()
    }

    // A refused Storno: the bill may already be stornoed elsewhere — the host re-reads it and its list.
    LaunchedEffect(stornoFailedBillId) {
        val billId = stornoFailedBillId ?: return@LaunchedEffect
        onBillChanged(billId)
        viewModel.clearStornoFailed()
    }

    // The re-read bill is no longer rebookable (stornoed, rebooked, invoiced): close its dialog.
    LaunchedEffect(bill?.billId, bill?.isRebookable) {
        if (bill?.isRebookable == false) showChangePayment = false
    }

    // Loading notification
    var loadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            loadingId =
                overlay.showLoading(
                    title = getString(BillReceiptKeys.receiptTitle, language),
                    message = getString(BillReceiptKeys.processing, language),
                )
        } else {
            loadingId?.let {
                overlay.dismissById(it)
                loadingId = null
            }
        }
    }

    PosErrorWatcher(
        error = error,
        title = str(CommonKeys.error),
        onClear = { viewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
    )

    // Bill stornoed → the host reloads its list, then the panel closes
    LaunchedEffect(billDeleted) {
        if (billDeleted) {
            viewModel.resetDeletionState()
            (viewModel.lastVoidedBillId ?: bill?.billId)?.let(onVoided)
            onDismiss()
        }
    }

    BackHandler(enabled = isVisible) {
        if (backTo != null && onBack != null) onBack() else onDismiss()
    }

    if (bill == null) return

    // ONE gate per bill control, handed to BOTH places that offer it — the panel button and the
    // Aktionen menu entry — so the two can never disagree about who may Storno or change the tender.
    // isDeletable is false for a closed day, an already-storno'd bill or one with an issued invoice,
    // which the server rejects with 409 — hide the control rather than offer one that always errors.
    // Neither is offered on the just-billed confirmation ([created]).
    val openStorno: (() -> Unit)? =
        if (canVoidBill && !created && bill.isDeletable) {
            { showDeleteConfirmation = true }
        } else {
            null
        }
    val openChangePayment: (() -> Unit)? =
        if (canChangePayment && bill.isRebookable && !created) {
            {
                viewModel.loadPaymentChangeData()
                showChangePayment = true
            }
        } else {
            null
        }

    // Zahlartwechsel dialog
    if (showChangePayment) {
        RebookDialog(
            bill = bill,
            language = language,
            paymentTypes = paymentTypesState,
            voucherConfig = voucherConfig,
            enabledVoucherSources = enabledVoucherSources,
            isValidatingVoucher = isValidatingVoucher,
            validatedVoucher = validatedVoucher,
            voucherValidationError = voucherValidationError,
            mayLowerTip = mayLowerRebookTip,
            onValidateVoucher = viewModel::validateVoucher,
            onClearVoucherValidation = viewModel::clearVoucherValidation,
            onConfirm = { entries, tip ->
                trackPrivilegedOperation(sessionManager)
                viewModel.rebookBill(bill, entries, tip, mayLowerRebookTip)
            },
            onDismiss = { showChangePayment = false },
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirmation) {
        PosDialog(
            onDismiss = { showDeleteConfirmation = false },
            title = str(BillReceiptKeys.deleteConfirmationTitle),
            showCloseButton = false,
            footer = {
                PosSecondaryButton(
                    text = str(CommonKeys.cancel),
                    onClick = { showDeleteConfirmation = false },
                    modifier = Modifier.weight(1f),
                )
                PosButton(
                    text = str(BillReceiptKeys.confirmDeleteButton),
                    onClick = {
                        trackPrivilegedOperation(sessionManager)
                        viewModel.deleteBill(bill.billId, deletionReason.takeIf { it.isNotBlank() }, restoreSession)
                        showDeleteConfirmation = false
                        deletionReason = ""
                        restoreSession = true
                    },
                    modifier = Modifier.weight(1f),
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PosTextField(
                    value = deletionReason,
                    onValueChange = { deletionReason = it },
                    label = str(BillReceiptKeys.deletionReasonLabel),
                )
                StornoRestoreSessionToggle(
                    checked = restoreSession,
                    onCheckedChange = { restoreSession = it },
                )
            }
        }
    }

    // The server refused to re-open the table: new guests sit there. Offer the plain Storno.
    if (stornoRestoreConflict != null) {
        StornoRestoreConflictDialog(
            onConfirm = {
                trackPrivilegedOperation(sessionManager)
                viewModel.confirmStornoWithoutRestore()
            },
            onDismiss = { viewModel.dismissStornoRestoreConflict() },
        )
    }

    // Actions dialog (print receipt, bewirtungsbeleg, invoice, Storno, Zahlartwechsel)
    PosBillCreatedActionsDialog(
        isVisible = showActionsDialog,
        bill = bill,
        language = language,
        onDismiss = {
            showActionsDialog = false
            actionsAfterRebook = false
        },
        created = created || actionsAfterRebook,
        onBillChanged = { onBillChanged(bill.billId) },
        // The same gated callbacks as the panel buttons below; the menu closes before the dialog opens.
        onStorno =
            openStorno?.let { open ->
                {
                    showActionsDialog = false
                    open()
                }
            },
        onChangePayment =
            openChangePayment?.let { open ->
                {
                    showActionsDialog = false
                    open()
                }
            },
    )

    // Scrim (non-compact only)
    if (!compact) {
        AnimatedVisibility(
            visible = isVisible,
            modifier = modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onDismiss() },
            )
        }
    }

    // Panel
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isVisible,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(if (compact) 1f else 0.6f),
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            Surface(
                tonalElevation = 6.dp,
            ) {
                PosBillDetailPanel(
                    bill = bill,
                    tableNumber = tableNumber,
                    modifier = Modifier.fillMaxSize(),
                    showSuccessHeader = created,
                    isLoading = isLoading,
                    // Storno / Zahlartwechsel live ONLY in the Aktionen menu now (wired into the
                    // actions dialog below) — the panel no longer offers them as separate buttons.
                    onActions = {
                        actionsAfterRebook = false
                        showActionsDialog = true
                    },
                    onClose = onDismiss,
                    onOpenLinkedBill = onOpenLinkedBill,
                    backTo = backTo,
                    onBack = onBack,
                )
            }
        }
    }
}
