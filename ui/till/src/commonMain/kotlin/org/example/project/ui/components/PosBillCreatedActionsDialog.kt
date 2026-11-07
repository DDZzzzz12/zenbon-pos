package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.auth.LocalPermissionManager
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.docUtil.DocumentViewerPanel
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SalesInvoiceTillKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isStandingSale
import org.example.project.core.utils.isTipOnAccount
import org.example.project.features.dashboard.presentation.components.billing.SalesInvoiceBuyerDialog
import org.example.project.features.dashboard.presentation.components.billing.SettleReceivableDialog
import org.example.project.features.dashboard.presentation.viewmodel.BillReceiptViewModel
import org.example.project.money.Decimal
import org.example.project.ui.auth.holds
import org.koin.compose.viewmodel.koinViewModel

/**
 * Compact post-creation dialog shown after a bill is confirmed on the billing screens, and reused by
 * [org.example.project.ui.components.PosBillReceiptOverlay] to re-open a bill from the reports screen.
 *
 * It carries the till's whole Ausgangsrechnung surface: create-and-issue a §14 invoice for a settled
 * bill, re-open the archived invoice a bill already carries, and settle an open receivable at the
 * counter. Which of the three shows is driven by the bill's snapshot — a bill with no invoice offers
 * *Rechnung erstellen*; one that already carries an invoice offers *Rechnung anzeigen* and, while it
 * still owes money, *Forderung begleichen*. Every rule is re-enforced server-side; the gates here are
 * only the UI half of the both-levels guard (CLAUDE.md rule 8).
 *
 * [created] marks the just-billed confirmation (the billing screen); only it is titled "Rechnung
 * erfolgreich erstellt". Opened from Berichte for a bill that already stood, it reads neutrally.
 *
 * [onBillChanged] fires once an invoice is issued or a receivable settled, so a host listing bills
 * (Berichte) can re-read the bill and its list.
 *
 * [onStorno] and [onChangePayment] add *Stornieren* and *Zahlungsart ändern* to the menu. They are the
 * host's own gated callbacks — the very ones it hands the panel buttons — and null whenever the host
 * would hide those buttons (capability, order station, bill state, the just-billed confirmation), so
 * the menu never decides on its own who may Storno or change the tender.
 */
@Composable
fun PosBillCreatedActionsDialog(
    isVisible: Boolean,
    bill: BillDto.BillResponseDto?,
    language: Language,
    onDismiss: () -> Unit,
    created: Boolean = false,
    onBillChanged: () -> Unit = {},
    onStorno: (() -> Unit)? = null,
    onChangePayment: (() -> Unit)? = null,
) {
    if (!isVisible || bill == null) return

    val viewModel: BillReceiptViewModel = koinViewModel()
    val permissionManager = LocalPermissionManager.current
    val overlay = LocalGlobalOverlayViewModel.current
    // Every action behind this flag is sales-invoice work — create one from the bill, open it,
    // settle its receivable — so it asks for the capability that gates those routes rather than for
    // a rank. Verdict-identical: INVOICE_WRITE sits in MANAGER and above and in no lesser group.
    val isManager = holds(Capability.INVOICE_WRITE)

    // The UI half of the reprint guard (CLAUDE.md rule 8). `POST /api/v1/bills/{billId}/print` is
    // `Cap(PRINTER_WRITE)`, so the control asks the same question the route does — and asks it
    // explicitly rather than relying on "whoever got here must be allowed", which is how the two
    // levels drift apart. Every floor group holds it; the back-office groups do not.
    val canPrintReceipt = holds(Capability.PRINTER_WRITE)
    val isTrainingMode by permissionManager.isTrainingMode.collectAsState()

    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var showInvoiceDialog by remember { mutableStateOf(false) }
    var showSettleDialog by remember { mutableStateOf(false) }

    // The UI half of the both-levels guard. Every condition is re-checked by BillInvoiceabilityGuard
    // on the server, and the sale-only actions use the same shared `isStandingSale` predicate the
    // server refuses with (BILL_NOT_A_STANDING_SALE), so the two cannot disagree about what the till
    // offers. Derived from the bill's relations, never from a role.
    val isStandingSale = bill.isStandingSale()
    // The snapshot predates an invoice issued from this very dialog; the view model remembers it.
    val invoicedBills by viewModel.invoicedBills.collectAsState()
    val existingInvoiceId = bill.salesInvoiceId ?: invoicedBills[bill.billId]
    // A tip paid "auf Rechnung" cannot go on an invoice that covers only the meal: the button is
    // disabled with the reason below it, and the server refuses with SALES_INVOICE_TIP_ON_ACCOUNT.
    val tipOnAccount = bill.isTipOnAccount()
    val canCreateInvoice = isManager && !isTrainingMode && existingInvoiceId == null && !tipOnAccount

    // The till may clear a receivable only on an ISSUED invoice that still owes something. "Issued"
    // is read from the presence of a §14 number; the open amount is the server's own figure. The
    // server re-checks origin, tender and overpayment, so this gate is the UI half only.
    val salesInvoiceOpen = bill.salesInvoiceOpenAmount?.let { Decimal.parseOrNull(it) }
    val canSettleReceivable =
        isManager &&
            !isTrainingMode &&
            isStandingSale &&
            existingInvoiceId != null &&
            bill.salesInvoiceNumber != null &&
            salesInvoiceOpen != null &&
            salesInvoiceOpen > Decimal.ZERO

    var loadingId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            loadingId?.let {
                overlay.dismissById(it)
                loadingId = null
            }
        }
    }
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

    // Surface the server's own detail (and a copyable traceId for 5xx faults) rather than the
    // generic "something went wrong" — an invoice or settlement fault is exactly where the operator
    // needs to read the reason out to support.
    PosErrorWatcher(
        error = error,
        title = str(BillReceiptKeys.receiptTitle),
        onClear = { viewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
    )

    // Confirm the §14 number to the manager: it is the only moment the number is on screen before the
    // PDF opens, and the guest is usually still standing there.
    val issuedInvoiceNumber by viewModel.issuedInvoiceNumber.collectAsState()
    val issuedMessage = issuedInvoiceNumber?.let { str(SalesInvoiceTillKeys.issuedMessage, it) }
    LaunchedEffect(issuedMessage) {
        issuedMessage?.let {
            overlay.showSuccess(title = it, message = it)
            onBillChanged()
        }
    }

    // Confirm a till settlement: the remaining open amount is what the manager needs to see.
    val settledOpenAmount by viewModel.settledOpenAmount.collectAsState()
    val settledMessage = settledOpenAmount?.let { str(SalesInvoiceTillKeys.settleSucceeded, it) }
    LaunchedEffect(settledMessage) {
        settledMessage?.let {
            overlay.showSuccess(title = it, message = it)
            viewModel.clearSettledOpenAmount()
            onBillChanged()
        }
    }

    PosDialog(
        onDismiss = onDismiss,
        title = str(billActionsTitleKey(bill, created), bill.billNumber),
        titleIcon = Icons.Default.CheckCircle,
        showCloseButton = false,
        footer = {
            PosButton(
                text = str(CommonKeys.close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${str(BillReceiptKeys.billNumberLabel)} ${bill.billNumber}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            PosSecondaryButton(
                text = str(BillReceiptKeys.printButton),
                onClick = { viewModel.printBill(bill.billId) },
                enabled = !isLoading && canPrintReceipt,
                modifier = Modifier.fillMaxWidth(),
            )

            if (bewirtungsbelegOffered(bill)) {
                PosSecondaryButton(
                    text = str(BillReceiptKeys.printBewirtungsbeleg),
                    onClick = { viewModel.printBill(bill.billId, bewirtungsbeleg = true) },
                    enabled = !isLoading && canPrintReceipt,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Invoice action: create one, re-open the one this bill already carries, or settle its
            // open receivable. Reprint serves the archived file, never a re-render.
            if (existingInvoiceId != null) {
                PosSecondaryButton(
                    text = str(SalesInvoiceTillKeys.showInvoiceButton),
                    onClick = { viewModel.showInvoice(existingInvoiceId) },
                    enabled = !isLoading && isManager,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (canSettleReceivable) {
                    PosSecondaryButton(
                        text = str(SalesInvoiceTillKeys.settleReceivableButton),
                        onClick = {
                            viewModel.loadPaymentChangeData()
                            showSettleDialog = true
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (createInvoiceOffered(bill.copy(salesInvoiceId = existingInvoiceId))) {
                PosSecondaryButton(
                    text = str(BillReceiptKeys.createInvoiceButton),
                    onClick = { showInvoiceDialog = true },
                    enabled = !isLoading && canCreateInvoice,
                    modifier = Modifier.fillMaxWidth(),
                )
                // A disabled button says why, like the tip-on-account case: without INVOICE_WRITE the
                // session cannot issue an invoice at all (the server answers 403 on the same capability).
                createInvoiceBlockedReason(isManager = isManager, tipOnAccount = tipOnAccount)?.let { reason ->
                    Text(
                        text = str(reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Storno and Zahlartwechsel — offered exactly where the panel offers them (see KDoc).
            onStorno?.let {
                PosSecondaryButton(
                    text = str(BillReceiptKeys.deleteButton),
                    onClick = it,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            onChangePayment?.let {
                PosSecondaryButton(
                    text = str(BillReceiptKeys.changePaymentButton),
                    onClick = it,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // Buyer block for a §14 invoice over this bill. Amounts come from the bill snapshot server-side.
    if (showInvoiceDialog) {
        SalesInvoiceBuyerDialog(
            billId = bill.billId,
            onDismiss = { showInvoiceDialog = false },
            onConfirm = { request ->
                viewModel.createAndIssueInvoice(request)
                showInvoiceDialog = false
            },
        )
    }

    // Settle an open receivable at the counter (4c-2c). The dialog filters the receivable tenders
    // out of its picker; the server rejects one anyway.
    val settlePaymentTypes by viewModel.paymentTypes.collectAsState()
    if (showSettleDialog && existingInvoiceId != null && bill.salesInvoiceOpenAmount != null) {
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

    // The archived invoice PDF — shown after create-and-issue or "Rechnung anzeigen". Never a
    // re-render, so it matches the copy the guest walks out with.
    val generatedPdfBytes by viewModel.generatedPdfBytes.collectAsState()
    generatedPdfBytes?.let { pdfBytes ->
        DocumentViewerPanel(
            modifier = Modifier.fillMaxWidth(),
            isVisible = true,
            documentBytes = pdfBytes,
            fileName =
                "${issuedInvoiceNumber ?: bill.salesInvoiceNumber ?: bill.billNumber}.pdf",
            onClose = { viewModel.clearGeneratedPdf() },
        )
    }
}

/**
 * The dialog's title for [bill]. A Neubuchung confirms the Zahlartwechsel and names the new Beleg
 * (`{0}`) only when [created] — the dialog that opens right after the rebook; opened later from
 * Berichte via *Aktionen* it reads *Beleg* (emu21 NOTE-z). A Storno Beleg or a cancelled original is
 * no bill that was just created, so it reads neutrally as *Beleg*. A sale or clearing keeps the
 * ordinary billing title only when [created] — the just-billed confirmation; opened again from
 * Berichte it reads *Beleg* too.
 */
internal fun billActionsTitleKey(
    bill: BillDto.BillResponseDto,
    created: Boolean,
): StringKey =
    when {
        bill.relations.kind == BillKind.STORNO -> BillReceiptKeys.receiptTitle
        !bill.isStandingSale() && bill.relations.kind != BillKind.RECEIVABLE_CLEARING -> BillReceiptKeys.receiptTitle
        bill.relations.kind == BillKind.REBOOKING -> if (created) BillReceiptKeys.rebookSuccess else BillReceiptKeys.receiptTitle
        created -> BillReceiptKeys.successMessage
        else -> BillReceiptKeys.receiptTitle
    }

/**
 * Whether the dialog shows *Bewirtungsbeleg* for [bill]: only for a standing sale. A Zahlartwechsel
 * correction, a Storno bill, a cancelled original and a receivable clearing document no meal the
 * guest could deduct; the server refuses the print for them with `BILL_NOT_A_STANDING_SALE`. The
 * plain receipt reprint stays offered for every bill.
 */
internal fun bewirtungsbelegOffered(bill: BillDto.BillResponseDto): Boolean = bill.isStandingSale()

/**
 * Whether the dialog shows *Rechnung erstellen* for [bill]: a standing sale that carries no invoice
 * yet. Capability and training mode only enable or disable the button; the kind of bill decides
 * whether it exists at all. The server refuses the others with `BILL_NOT_A_STANDING_SALE`.
 */
internal fun createInvoiceOffered(bill: BillDto.BillResponseDto): Boolean = bill.isStandingSale() && bill.salesInvoiceId == null

/**
 * Why *Rechnung erstellen* is disabled, or null when nothing the operator can read explains it. The
 * missing capability (`INVOICE_WRITE`, MANAGER and above) is named first: no Zahlartwechsel fixes
 * that, so pointing a cashier at the tip-on-account remedy would send them the wrong way.
 */
internal fun createInvoiceBlockedReason(
    isManager: Boolean,
    tipOnAccount: Boolean,
): StringKey? =
    when {
        !isManager -> SalesInvoiceTillKeys.createInvoiceNeedsCapability
        tipOnAccount -> SalesInvoiceTillKeys.tipOnAccountMessage
        else -> null
    }
