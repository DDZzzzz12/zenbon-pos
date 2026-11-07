package org.example.project.features.dashboard.presentation.components.billing

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.example.project.core.auth.LocalPermissionManager
import org.example.project.core.auth.LocalTemporarySessionManager
import org.example.project.core.auth.trackPrivilegedOperation
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.docUtil.DocumentViewerPanel
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SalesInvoiceTillKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.discountToPercentage
import org.example.project.core.utils.enumStrings.getName
import org.example.project.core.utils.formatReadableDateTime
import org.example.project.core.utils.getLocalizedName
import org.example.project.core.utils.isTipOnAccount
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.core.utils.toPercentage
import org.example.project.features.dashboard.presentation.viewmodel.BillReceiptViewModel
import org.example.project.money.Decimal
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.TseSigningNoteSection
import org.koin.compose.viewmodel.koinViewModel
import qrgenerator.qrkitpainter.rememberQrKitPainter

/**
 * Receipt dialog showing created bill details.
 *
 * Includes slide-in/slide-out animation for smooth presentation.
 *
 * @param isVisible Whether the dialog should be visible
 * @param bill Created bill response (can be null when dismissing)
 * @param tableNumber Table number for display
 * @param language Current UI language
 * @param onDismiss Callback when user closes dialog (called after exit animation)
 * @param created Whether this is a newly created bill (shows success header)
 * @param compact Whether to use compact layout for smaller screens
 */
@Composable
fun BillReceiptPanel(
    modifier: Modifier = Modifier,
    isVisible: Boolean,
    bill: BillDto.BillResponseDto?,
    tableNumber: String,
    language: Language,
    onDismiss: () -> Unit,
    created: Boolean = false,
    compact: Boolean = false,
) {
    val viewModel: BillReceiptViewModel = koinViewModel()

    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var deletionReason by remember { mutableStateOf("") }
    var restoreSession by remember { mutableStateOf(true) }
    var showInvoiceDialog by remember { mutableStateOf(false) }
    var showSettleDialog by remember { mutableStateOf(false) }

    val billDeleted by viewModel.billDeleted.collectAsState()
    val stornoRestoreConflict by viewModel.stornoRestoreConflict.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val successMessage by viewModel.successMessage.collectAsState()

    val permissionManager = LocalPermissionManager.current
    val sessionManager = LocalTemporarySessionManager.current
    val overlay = LocalGlobalOverlayViewModel.current
    // One `isManagerOrHigher()` used to cover three separate actions here. Each now asks for the
    // capability its own endpoint names, so a group can hold one without the others:
    //   void a bill      -> DELETE /api/v1/bills/{id}          Cap(BILL_VOID)
    //   issue an invoice -> POST   /api/v1/sales-invoices      Cap(INVOICE_WRITE)
    //   settle/show it   -> the same sales-invoice surface     Cap(INVOICE_WRITE)
    val canVoidBill = holds(Capability.BILL_VOID)
    val canWriteInvoices = holds(Capability.INVOICE_WRITE)
    val isTrainingMode by permissionManager.isTrainingMode.collectAsState()

    // The UI half of the both-levels guard. Every condition is re-checked by
    // BillInvoiceabilityGuard on the server; `billIsCancelled` deliberately spells out the same
    // predicate the server's `isDeleted` uses, so the two cannot disagree about what the till offers.
    val billIsCancelled = bill?.deletingReason != null || bill?.deletedBillId != null
    // The snapshot predates an invoice issued from this panel until the host re-reads it (emu20 NOTE-q).
    val invoicedBills by viewModel.invoicedBills.collectAsState()
    val existingInvoiceId = bill?.salesInvoiceId ?: bill?.let { invoicedBills[it.billId] }
    // A tip paid "auf Rechnung" cannot go on an invoice that covers only the meal; the server refuses
    // it with SALES_INVOICE_TIP_ON_ACCOUNT on the same shared predicate.
    val tipOnAccount = bill?.isTipOnAccount() == true
    val canCreateInvoice = canWriteInvoices && !isTrainingMode && !billIsCancelled && existingInvoiceId == null && !tipOnAccount

    // The till may clear a receivable only on an ISSUED invoice that still owes something. "Issued"
    // is read from the presence of a §14 number; the open amount is the server's own figure. The
    // server re-checks origin, tender and overpayment, so this gate is the UI half only.
    val salesInvoiceOpen = bill?.salesInvoiceOpenAmount?.let { Decimal.parseOrNull(it) }
    val canSettleReceivable =
        canWriteInvoices &&
            !isTrainingMode &&
            !billIsCancelled &&
            existingInvoiceId != null &&
            bill?.salesInvoiceNumber != null &&
            salesInvoiceOpen != null &&
            salesInvoiceOpen > Decimal.ZERO

    // Show loading notification for bill operations (print/delete)
    var billOperationLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            billOperationLoadingId =
                overlay.showLoading(
                    title = getString(BillReceiptKeys.receiptTitle, language),
                    message = getString(BillReceiptKeys.processing, language),
                )
        } else {
            billOperationLoadingId?.let {
                overlay.dismissById(it)
                billOperationLoadingId = null
            }
        }
    }

    BackHandler {
        onDismiss()
    }

    // Handle bill deleted state
    LaunchedEffect(billDeleted) {
        if (billDeleted) {
            viewModel.resetDeletionState()
        }
    }

    PosErrorWatcher(
        error = error,
        title = str(CommonKeys.error),
        onClear = { viewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
    )

    // Only render if bill is not null
    bill?.let { billData ->
        // Delete confirmation dialog
        if (showDeleteConfirmation) {
            PosDialog(
                onDismiss = { showDeleteConfirmation = false },
                title = str(BillReceiptKeys.deleteConfirmationTitle),
                footer = {
                    PosTextButton(
                        text = getString(CommonKeys.cancel, language),
                        onClick = { showDeleteConfirmation = false },
                    )
                    PosDangerButton(
                        text = str(BillReceiptKeys.confirmDeleteButton),
                        onClick = {
                            // Track privileged operation for session auto-extension
                            trackPrivilegedOperation(sessionManager)
                            viewModel.deleteBill(billData.billId, deletionReason.takeIf { it.isNotBlank() }, restoreSession)
                            showDeleteConfirmation = false
                            deletionReason = ""
                            restoreSession = true
                        },
                    )
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(str(BillReceiptKeys.deleteConfirmationMessage, billData.billNumber))
                    Spacer(modifier = Modifier.height(8.dp))
                    PosTextField(
                        value = deletionReason,
                        onValueChange = { deletionReason = it },
                        label = str(BillReceiptKeys.deletionReasonLabel),
                        placeholder = str(BillReceiptKeys.deletionReasonPlaceholder),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        imeAction = ImeAction.Done,
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

        if (!compact) {
            // Scrim overlay that dismisses panel when clicked
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
                            ) {
                                // Dismiss panel when clicking on scrim
                                onDismiss()
                            },
                )
            }
        }

        // Container Box to position the panel from the right
        Box(modifier = modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = isVisible,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(if (compact) 1f else 0.6f),
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            ) {
                Scaffold { innerPadding ->

                    Surface(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 6.dp,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // Header with icon and title (if created)
                            if (created) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    tonalElevation = 0.dp,
                                ) {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp),
                                        )
                                        Text(
                                            text = str(BillReceiptKeys.successMessage),
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }

                            // Scrollable content
                            Column(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState())
                                        .padding(16.dp),
                            ) {
                                PosCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    variant = PosCardVariant.Tonal,
                                ) {
                                    body {
                                        Column {
                                            // Header info
                                            Text(
                                                text = str(BillReceiptKeys.dialogTitle),
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold,
                                            )

                                            Spacer(modifier = Modifier.height(8.dp))

                                            // Bill ID
//                        Row {
//                            Text(str(BillReceiptKeys.billIdLabel) + " ", fontWeight = FontWeight.Medium)
//                            Text(bill.billNumber, style = MaterialTheme.typography.bodySmall)
//                        }

                                            // Bill Number
                                            Row {
                                                Text(
                                                    str(BillReceiptKeys.billNumberLabel) + " ",
                                                    fontWeight = FontWeight.Medium,
                                                )
                                                Text(bill.billNumber)
                                            }

                                            // Table
                                            Row {
                                                Text(
                                                    str(BillReceiptKeys.tableLabel) + " ",
                                                    fontWeight = FontWeight.Medium,
                                                )
                                                Text(tableNumber)
                                            }

                                            // Employee Number
                                            Row {
                                                Text(
                                                    str(BillReceiptKeys.employeeNumber) + " ",
                                                    fontWeight = FontWeight.Medium,
                                                )
                                                Text(bill.employeeNumber)
                                            }

                                            // Start Time
                                            Row {
                                                Text(
                                                    str(BillReceiptKeys.startTimeLabel) + " ",
                                                    fontWeight = FontWeight.Medium,
                                                )
                                                Text(bill.startTime.formatReadableDateTime(language))
                                            }

                                            // End Time
                                            Row {
                                                Text(
                                                    str(BillReceiptKeys.endTimeLabel) + " ",
                                                    fontWeight = FontWeight.Medium,
                                                )
                                                Text(bill.endTime.formatReadableDateTime(language))
                                            }

                                            Spacer(modifier = Modifier.height(16.dp))
                                            HorizontalDivider()
                                            Spacer(modifier = Modifier.height(12.dp))

                                            // Items
                                            Text(
                                                text = str(BillReceiptKeys.itemsLabel),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))

                                            bill.orderItems.forEach { item ->
                                                PosCard(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    variant = PosCardVariant.Tonal,
                                                ) {
                                                    body {
                                                        Column {
                                                            // Product name and quantity
                                                            Row(modifier = Modifier.fillMaxWidth()) {
                                                                Text(
                                                                    text = "${item.quantity}× ${item.product.productName}",
                                                                    modifier = Modifier.weight(1f),
                                                                    fontWeight = FontWeight.Medium,
                                                                )
                                                                Text(
                                                                    item.finalPrice.toFormattedCurrency(),
                                                                    fontWeight = FontWeight.Bold,
                                                                )
                                                            }

                                                            // Unit price
                                                            Row(modifier = Modifier.fillMaxWidth()) {
                                                                Text(
                                                                    text = str(BillReceiptKeys.unitPriceLabel) + " ",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                )
                                                                Text(item.unitPrice.toFormattedCurrency(), style = MaterialTheme.typography.bodySmall)
                                                            }

                                                            // Extras (if any)
                                                            item.extras?.takeIf { it.isNotEmpty() }?.let { extras ->
                                                                Text(
                                                                    text =
                                                                        str(BillReceiptKeys.extrasLabel) + ": " +
                                                                            extras.joinToString(", ") { it.name },
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                )
                                                            }

                                                            // Side notes (if any)
                                                            item.sideNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                                                                Text(
                                                                    text = str(BillReceiptKeys.sideNotesLabel) + ": $notes",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.tertiary,
                                                                )
                                                            }

                                                            // Item status and badges
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement =
                                                                    Arrangement.spacedBy(
                                                                        4.dp,
                                                                    ),
                                                            ) {
                                                                Text(
                                                                    text =
                                                                        str(BillReceiptKeys.statusLabel) + ": " +
                                                                            ItemStatus
                                                                                .valueOf(
                                                                                    item.itemStatus.uppercase(),
                                                                                ).getName(language),
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                )

                                                                if (item.takeAway) {
                                                                    Text(
                                                                        "| ${str(BillReceiptKeys.takeawayLabel)}",
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                    )
                                                                }

                                                                if (item.transferred) {
                                                                    Text(
                                                                        "| ${str(BillReceiptKeys.transferredLabel)}",
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                    )
                                                                }

                                                                if (item.customPrice) {
                                                                    Text(
                                                                        "| ${str(BillReceiptKeys.customPriceLabel)}",
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                    )
                                                                }
                                                            }

                                                            // Discount (if applied)
                                                            if (item.discount > 0 && item.discount < 1) {
                                                                Text(
                                                                    text = str(BillReceiptKeys.discountLabel) + ": -${item.discount.discountToPercentage()}%",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.error,
                                                                )
                                                            }
                                                        } // end Column
                                                    } // end body
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(12.dp))
                                            HorizontalDivider()
                                            Spacer(modifier = Modifier.height(12.dp))

                                            // Financial summary
                                            Row(modifier = Modifier.fillMaxWidth()) {
                                                Text(
                                                    str(BillReceiptKeys.subtotalLabel),
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Text(bill.billAmount.toFormattedCurrency())
                                            }

                                            if (Decimal.parse(bill.discountAmount) != Decimal.ZERO) {
                                                Row(modifier = Modifier.fillMaxWidth()) {
                                                    Text(
                                                        str(BillReceiptKeys.discountLabel),
                                                        modifier = Modifier.weight(1f),
                                                        color = MaterialTheme.colorScheme.error,
                                                    )
                                                    Text(
                                                        "-${bill.discountAmount.toFormattedCurrency()}",
                                                        color = MaterialTheme.colorScheme.error,
                                                    )
                                                }
                                            }

                                            if (Decimal.parse(bill.tips) != Decimal.ZERO && bill.tipRecipient == TipRecipient.OWNER.name) {
                                                Row(modifier = Modifier.fillMaxWidth()) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(str(BillReceiptKeys.tipLabel))
                                                        Text(
                                                            text = "(${str(BillReceiptKeys.tipRecipientOwner)}, ${
                                                                getString(BillReceiptKeys.taxIncluded, language)
                                                            })",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                    Text(bill.tips.toFormattedCurrency())
                                                }
                                            }

                                            bill.vatDetails.forEach { vat ->
                                                Row(modifier = Modifier.fillMaxWidth()) {
                                                    Text(
                                                        str(BillReceiptKeys.taxLabel, vat.vatRate.toPercentage()),
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    Text(vat.vatAmount.toFormattedCurrency())
                                                }
                                            }

                                            if (Decimal.parse(bill.tips) != Decimal.ZERO && bill.tipRecipient == TipRecipient.EMPLOYEE.name) {
                                                Row(modifier = Modifier.fillMaxWidth()) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(str(BillReceiptKeys.tipLabel))
                                                        Text(
                                                            text = "(${str(BillReceiptKeys.tipRecipientEmployee)}, ${
                                                                getString(BillReceiptKeys.notTaxable, language)
                                                            })",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                    Text(bill.tips.toFormattedCurrency())
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))
                                            HorizontalDivider()
                                            Spacer(modifier = Modifier.height(8.dp))

                                            Row(modifier = Modifier.fillMaxWidth()) {
                                                Text(
                                                    str(BillReceiptKeys.totalLabel),
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Text(
                                                    bill.paidAmount.toFormattedCurrency(),
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(16.dp))
                                            HorizontalDivider()
                                            Spacer(modifier = Modifier.height(12.dp))

                                            // Payments
                                            Text(
                                                text = str(BillReceiptKeys.paymentsLabel),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))

                                            val deleted = bill.deletedBillId != null
                                            val language = LocalAppLanguage.current

                                            bill.payments.forEach { payment ->
                                                Row(modifier = Modifier.fillMaxWidth()) {
                                                    Text(payment.getLocalizedName(language), modifier = Modifier.weight(1f))
                                                    Text(payment.amount.toFormattedCurrency(deleted))
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))
                                            // Voucher Redemption (if present)
                                            bill.voucherRedeem?.takeIf { it.isNotEmpty() }?.let { vouchers ->
                                                Text(
                                                    text = str(BillReceiptKeys.voucherRedeemLabel),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))

                                                vouchers.forEach { voucher ->
                                                    PosCard(
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                        variant = PosCardVariant.Tonal,
                                                    ) {
                                                        body {
                                                            Column {
                                                                Row(modifier = Modifier.fillMaxWidth()) {
                                                                    Text(
                                                                        str(BillReceiptKeys.voucherCodeLabel) + " ",
                                                                        fontWeight = FontWeight.Medium,
                                                                    )
                                                                    Text(voucher.voucherNumber)
                                                                }
                                                                Row(modifier = Modifier.fillMaxWidth()) {
                                                                    Text(
                                                                        str(BillReceiptKeys.voucherAmountLabel) + " ",
                                                                        fontWeight = FontWeight.Medium,
                                                                    )
                                                                    Text(voucher.redeemedAmount.toFormattedCurrency())
                                                                }
                                                            } // end Column
                                                        } // end body
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(12.dp))
                                                HorizontalDivider()
                                                Spacer(modifier = Modifier.height(12.dp))
                                            }

                                            // Deleted Bill Info (if present)
                                            bill.deletedBillId?.let { deletedId ->
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Row {
                                                    Text(
                                                        str(BillReceiptKeys.deletedBillIdLabel) + " ",
                                                        fontWeight = FontWeight.Medium,
                                                    )
                                                    Text(deletedId, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }

                                            bill.deletingReason?.let { reason ->
                                                Row {
                                                    Text(
                                                        str(BillReceiptKeys.deletingReasonLabel) + " ",
                                                        fontWeight = FontWeight.Medium,
                                                    )
                                                    Text(reason)
                                                }
                                            }

                                            // TSE QR Code (DSFinV-K compliance)
                                            bill.tseQrCodeContent?.let { qrContent ->
                                                Column(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                ) {
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = str(BillReceiptKeys.tseTransactionIdLabel),
                                                        fontWeight = FontWeight.Medium,
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    val qrPainter = rememberQrKitPainter(data = qrContent)
                                                    Image(
                                                        painter = qrPainter,
                                                        contentDescription = str(AccessibilityKeys.tseQrCode),
                                                        modifier = Modifier.size(240.dp),
                                                    )
                                                }
                                            }

                                            // Nachsignierung: pending, signed late or abandoned, and why
                                            TseSigningNoteSection(bill.tseSigningNote)

                                            Text(
                                                text = str(BillReceiptKeys.thankYouMessage),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                            )
                                        } // end Column in body
                                    } // end body
                                }
                            }

                            // Bottom action buttons
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                tonalElevation = 3.dp,
                            ) {
                                Column {
                                    // Delete button (only show if action is provided)
                                    if (canVoidBill && !created) {
                                        PosDangerButton(
                                            text = str(BillReceiptKeys.deleteButton),
                                            onClick = { showDeleteConfirmation = true },
                                            enabled = !isLoading,
                                            isLoading = isLoading,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                        )
                                    }
                                    // Print button (only show if action is provided)
                                    PosSecondaryButton(
                                        text = str(BillReceiptKeys.printButton),
                                        onClick = { viewModel.printBill(bill.billId) },
                                        enabled = !isLoading,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    )

                                    // Say why the action is unavailable rather than leaving a
                                    // manager to guess at a disabled button.
                                    val invoiceUnavailableReason =
                                        when {
                                            existingInvoiceId != null ->
                                                str(
                                                    SalesInvoiceTillKeys.alreadyInvoicedHint,
                                                    bill.salesInvoiceNumber ?: "",
                                                )
                                            billIsCancelled -> str(SalesInvoiceTillKeys.notAvailableDeleted)
                                            isTrainingMode -> str(SalesInvoiceTillKeys.notAvailableTraining)
                                            tipOnAccount -> str(SalesInvoiceTillKeys.tipOnAccountMessage)
                                            else -> null
                                        }
                                    invoiceUnavailableReason?.let { reason ->
                                        Text(
                                            text = reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        )
                                    }

                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        // Invoice action: create one, or show the one this bill
                                        // already carries. Reprint serves the archived file.
                                        if (existingInvoiceId != null) {
                                            PosButton(
                                                text = str(SalesInvoiceTillKeys.showInvoiceButton),
                                                onClick = { viewModel.showInvoice(existingInvoiceId) },
                                                modifier = Modifier.weight(1f),
                                                enabled = !isLoading && canWriteInvoices,
                                            )
                                            if (canSettleReceivable) {
                                                PosButton(
                                                    text = str(SalesInvoiceTillKeys.settleReceivableButton),
                                                    onClick = {
                                                        viewModel.loadPaymentChangeData()
                                                        showSettleDialog = true
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    enabled = !isLoading,
                                                )
                                            }
                                        } else {
                                            PosButton(
                                                text =
                                                    if (compact) {
                                                        str(BillReceiptKeys.createInvoiceButtonCompact)
                                                    } else {
                                                        str(BillReceiptKeys.createInvoiceButton)
                                                    },
                                                onClick = { showInvoiceDialog = true },
                                                modifier = Modifier.weight(1f),
                                                enabled = !isLoading && canCreateInvoice,
                                            )
                                        }

                                        // Close button (always show)
                                        PosButton(
                                            text = getString(CommonKeys.close, language),
                                            onClick = onDismiss,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } // End Surface
            } // End AnimatedVisibility
        } // End Box
    } // End bill?.let

    // Buyer block for a §14 invoice over this bill. Amounts come from the bill snapshot.
    if (showInvoiceDialog && bill != null) {
        SalesInvoiceBuyerDialog(
            billId = bill.billId,
            onDismiss = { showInvoiceDialog = false },
            onConfirm = { request ->
                viewModel.createAndIssueInvoice(request)
                showInvoiceDialog = false
            },
        )
    }

    // Settle an open receivable at the counter (4c-2c).
    val settlePaymentTypes by viewModel.paymentTypes.collectAsState()
    if (showSettleDialog && existingInvoiceId != null && bill?.salesInvoiceOpenAmount != null) {
        SettleReceivableDialog(
            openAmount = bill.salesInvoiceOpenAmount ?: "",
            paymentTypes = settlePaymentTypes,
            onDismiss = { showSettleDialog = false },
            onConfirm = { request ->
                viewModel.settleReceivable(existingInvoiceId, request)
                showSettleDialog = false
            },
        )
    }

    // PDF Viewer Panel
    val generatedPdfBytes by viewModel.generatedPdfBytes.collectAsState()
    val issuedInvoiceNumber by viewModel.issuedInvoiceNumber.collectAsState()

    // Confirm the §14 number to the manager: it is the only moment the number is on screen before
    // the PDF opens, and the guest is usually still standing there.
    val issuedMessage = issuedInvoiceNumber?.let { str(SalesInvoiceTillKeys.issuedMessage, it) }
    LaunchedEffect(issuedMessage) {
        issuedMessage?.let { overlay.showSuccess(title = it, message = it) }
    }

    // Confirm a till settlement: the remaining open amount is what the manager needs to see.
    val settledOpenAmount by viewModel.settledOpenAmount.collectAsState()
    val settledMessage = settledOpenAmount?.let { str(SalesInvoiceTillKeys.settleSucceeded, it) }
    LaunchedEffect(settledMessage) {
        settledMessage?.let {
            overlay.showSuccess(title = it, message = it)
            viewModel.clearSettledOpenAmount()
        }
    }
    generatedPdfBytes?.let { pdfBytes ->
        DocumentViewerPanel(
            modifier = Modifier.fillMaxWidth(if (compact) 1f else 0.6f),
            documentBytes = pdfBytes,
            fileName =
                "${issuedInvoiceNumber ?: bill?.salesInvoiceNumber ?: bill?.billNumber ?: "invoice"}.pdf",
            onClose = {
                viewModel.clearGeneratedPdf()
            },
            isVisible = true,
        )
    }

    // Show success message
    successMessage?.let { message ->
        LaunchedEffect(message) {
            // Auto-dismiss after 3 seconds
            delay(3000)
            viewModel.clearSuccess()
        }
    }
}
