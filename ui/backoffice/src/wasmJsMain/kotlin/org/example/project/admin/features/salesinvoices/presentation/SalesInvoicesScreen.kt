package org.example.project.admin.features.salesinvoices.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminDrillHost
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.components.AdminListScaffold
import org.example.project.admin.core.presentation.components.AdminRailRow
import org.example.project.admin.core.presentation.components.AdminSplitPane
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.format.asDisplayDay
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosDangerOutlinedButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSideSheet
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.beleg.BelegTone
import org.example.project.ui.components.beleg.PosFlowStep
import org.example.project.ui.components.beleg.PosFlowTimeline
import org.example.project.ui.components.beleg.PosStatusDot
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

/** `2027-01-31T00:00` &rarr; `2027-01-31`; a null date is a blank, not the word "null". */

/**
 * Ausgangsrechnungen — the invoices we issue, with the selected one's actions beside the list.
 *
 * **The pane placement is a bug fix, not a preference.** The actions used to be appended *below the
 * list*: selecting the fortieth row rendered them under the fortieth card, off-screen, with no
 * scroll — so the operator clicked and nothing appeared to happen. In a pane they are always in
 * view, whichever row was chosen.
 *
 * Every gate is unchanged. Issue and cancel still confirm first, because both are irreversible:
 * issuing consumes a §14 number that can never be reused, and cancelling consumes a second one for
 * the Gutschrift, so a mis-click leaves a permanent mark in the sequence. The disabled states still
 * mirror the server's rules — a disabled button and a 409 are both needed (project rule 8).
 *
 * The Belegfluss strip on top (Entwurf · Ausgestellt · Offen) is the bucket filter: the sub-filters of
 * *Ausgestellt* (Überfällig, Storniert, Gutschriften) are chips under it, and the next-step bar ends
 * the screen. [onOpenAllocations] leads to Zuordnung for an open invoice's payment.
 *
 * @param onOpenPdf hands the archived document id to the host, which opens it on the Belege detail
 *   screen. This screen deliberately does not render the PDF itself: one document view, with its
 *   download/print actions and data pane, serves every place an archived Beleg is opened from.
 */
@Composable
fun SalesInvoicesScreen(
    onOpenPdf: (Long) -> Unit,
    onCreate: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenAllocations: () -> Unit,
) {
    val viewModel: SalesInvoicesViewModel = koinViewModel()
    val state = viewModel.state
    val mayWrite = SalesInvoicesAccess.of(LocalAccessPolicy.current).mayWrite
    // Zuordnung is the ACCOUNTING_READ queue (GET /api/allocations/queue): offer the way there only to a reader.
    val mayAssign = LocalAccessPolicy.current.allows(Capability.ACCOUNTING_READ)

    // LaunchedEffect, not VM init: adminApp view models share one ViewModelStoreOwner and survive
    // logout, so init would show the previous session's invoices.
    LaunchedEffect(Unit) { viewModel.load() }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.SalesInvoicesTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    var detailOpen by remember { mutableStateOf(false) }
    // A small tablet (Medium) keeps the list as the page and opens the invoice as a 420dp drawer over it.
    val medium = LocalPosLayout.current == PosLayout.Medium

    val resultText = str(salesInvoicesResultLineKey(state), str(state.bucket.labelKey()), salesInvoicesResultCount(state))

    val nextStepBar: @Composable () -> Unit = {
        SalesNextStepBar(
            counts = state.bucketCounts,
            onOpenDrafts = { viewModel.selectBucket(SalesInvoiceBucket.DRAFTS) },
            onAssign = onOpenAllocations,
            canAssign = mayAssign,
        )
    }

    val invoiceDetail: @Composable () -> Unit = {
        val selected = state.selected
        if (selected == null) {
            AdminListEmptyState(message = str(AdminStringKeys.SalesInvoicesSelectPrompt))
        } else {
            SelectedInvoicePanel(
                state = state,
                selected = selected,
                onIssue = viewModel::issueSelected,
                onCancel = viewModel::cancelSelected,
                onEdit = { onEdit(selected.id) },
                onOpenPdf = { selected.documentId?.let(onOpenPdf) },
                onAssign = if (mayAssign) onOpenAllocations else null,
                onClose = {
                    // Same as Back: leave the detail as well as dropping the selection.
                    detailOpen = false
                    viewModel.clearSelection()
                },
            )
        }
    }

    // Phone + open detail: the detail alone fills the screen; Back (in AdminSplitPane) restores the header.
    AdminDrillHost(detailOpen = detailOpen, hasSelection = state.selected != null) { phoneDetail ->
        AdminListScaffold(
            title = str(AdminStringKeys.SalesInvoicesTitle),
            showHeader = !phoneDetail,
            actions = {
                // Not gated on the bucket. It used to render only under DRAFTS while the default bucket
                // is ISSUED, so an operator opening the screen saw no create affordance at all. Creating
                // an invoice is a property of the screen, not of the filter you happen to be looking
                // through.
                if (mayWrite) {
                    PosButton(
                        text = str(AdminStringKeys.SalesInvoicesActionCreate),
                        onClick = {
                            // The new draft lands in DRAFTS. Selecting it on the way out means the list is
                            // already showing the right bucket when the editor pops back.
                            viewModel.selectBucket(SalesInvoiceBucket.DRAFTS)
                            onCreate()
                        },
                    )
                }
            },
            filters = {
                Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.lg)) {
                    SalesFlowStrip(
                        counts = state.bucketCounts,
                        selected = SalesFlow.stageOf(state.bucket),
                        onSelect = { stage -> viewModel.selectBucket(stage.bucket) },
                    )
                    if (SalesFlow.stageOf(state.bucket) == SalesFlowStage.ISSUED) {
                        IssuedSubFilters(state = state, onSelect = viewModel::selectBucket)
                    }
                }
            },
            resultCount = resultText,
            isLoading = state.isLoading && !phoneDetail,
            isEmpty = state.items.isEmpty() && !state.isLoading && !phoneDetail,
            empty = {
                Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.xl)) {
                    AdminListEmptyState(
                        message = str(AdminStringKeys.SalesInvoicesEmpty),
                        icon = Icons.Default.Receipt,
                        actionText = if (mayWrite) str(AdminStringKeys.SalesInvoicesActionCreate) else null,
                        onAction =
                            if (mayWrite) {
                                {
                                    viewModel.selectBucket(SalesInvoiceBucket.DRAFTS)
                                    onCreate()
                                }
                            } else {
                                null
                            },
                    )
                    nextStepBar()
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.xl)) {
                AdminSplitPane(
                    list = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.items.forEach { row ->
                                SalesInvoiceRailRow(
                                    row = row,
                                    shownBucket = state.bucket,
                                    selected = row.id == state.selected?.id,
                                    onClick = {
                                        viewModel.select(row.id)
                                        detailOpen = true
                                    },
                                )
                            }
                        }
                    },
                    detail = { invoiceDetail() },
                    detailOpen = detailOpen,
                    // Back must also drop the selection, or the next list tap would flash the previous
                    // invoice (select() raises isLoading and the old `selected` is still in state).
                    onCloseDetail =
                        if (medium) {
                            null
                        } else {
                            {
                                detailOpen = false
                                viewModel.clearSelection()
                            }
                        },
                    detailEmpty = state.selected == null || medium,
                )
                // On a phone the detail page is the whole screen; the bar belongs to the list.
                if (!phoneDetail) nextStepBar()
            }
        }
    }

    if (medium && detailOpen && state.selected != null) {
        PosSideSheet(
            onDismiss = {
                detailOpen = false
                viewModel.clearSelection()
            },
            title = state.selected.invoiceNumber ?: str(AdminStringKeys.SalesInvoicesDraftNoNumber),
            width = 420.dp,
        ) {
            invoiceDetail()
        }
    }
}

/**
 * The sub-filters of the Ausgestellt stage: *Alle ausgestellt*, *Überfällig*, *Storniert* and
 * *Gutschriften*, each with its server total. They wrap rather than overflow: a single `Row` pushed
 * the last chip off the edge silently on a phone, which is how a bucket stops being used.
 */
@Composable
private fun IssuedSubFilters(
    state: SalesInvoicesState,
    onSelect: (SalesInvoiceBucket) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
    ) {
        SalesFlow.ISSUED_BUCKETS.forEach { bucket ->
            val label = if (bucket == SalesInvoiceBucket.ISSUED) str(AdminStringKeys.BelegflowChipAllIssued) else str(bucket.labelKey())
            val count = state.bucketCounts[bucket]
            FilterChip(
                selected = bucket == state.bucket,
                onClick = { onSelect(bucket) },
                label = { Text(label) },
                trailingIcon =
                    if (count != null) {
                        { Text(count.toString()) }
                    } else {
                        null
                    },
                modifier = Modifier.heightIn(min = adminTouchSize(32.dp)),
            )
        }
    }
}

@Composable
private fun SalesInvoiceRailRow(
    row: SalesInvoiceDto.ListItemDto,
    shownBucket: SalesInvoiceBucket,
    selected: Boolean,
    onClick: () -> Unit,
) {
    AdminRailRow(selected = selected, onClick = onClick) {
        Text(
            text = row.invoiceNumber ?: str(AdminStringKeys.SalesInvoicesDraftNoNumber),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(text = row.buyerName, style = MaterialTheme.typography.bodyMedium)
        val status = SalesFlow.statusOf(row, shownBucket)
        PosStatusDot(label = str(status.labelKey), tone = status.tone)
        Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.issueDate.asDisplayDay(LocalAppLanguage.current),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Money never reaches the screen as a raw server string: "28.3000" is what the :platform-money
            // module exists to stop an operator reading as a total.
            row.taxInclusiveTotal?.let {
                PosMoneyText(
                    amount = it,
                    currencyCode = row.currency,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The selected invoice and what may be done to it.
 *
 * Each action is gated on the state's own predicate as well as the server's rule — a disabled button
 * and a 409 are both needed: the first tells the operator what is possible, the second is what makes
 * it true (project rule 8).
 */
@Composable
private fun SelectedInvoicePanel(
    state: SalesInvoicesState,
    selected: SalesInvoiceDto.ResponseDto,
    onIssue: () -> Unit,
    onCancel: (String) -> Unit,
    onEdit: () -> Unit,
    onOpenPdf: () -> Unit,
    onAssign: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val mayWrite = SalesInvoicesAccess.of(LocalAccessPolicy.current).mayWrite
    var confirmingIssue by remember { mutableStateOf(false) }
    var confirmingCancel by remember { mutableStateOf(false) }

    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = selected.invoiceNumber ?: str(AdminStringKeys.SalesInvoicesDraftNoNumber),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                PosStatusChip(
                    text = salesInvoiceStatusLabelKey(selected.status)?.let { str(it) } ?: selected.status,
                    // UI-D-20: an issued invoice gets a state colour too, not grey on grey.
                    backgroundColor =
                        when (selected.status) {
                            "CANCELLED" -> MaterialTheme.colorScheme.errorContainer
                            "ISSUED" -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    textColor =
                        when (selected.status) {
                            "CANCELLED" -> MaterialTheme.colorScheme.onErrorContainer
                            "ISSUED" -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }

            Text(text = selected.buyerName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${str(AdminStringKeys.SalesInvoicesServiceDate)}: ${selected.serviceDate.asDisplayDay(LocalAppLanguage.current)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PaymentLine(selected = selected, onAssign = onAssign)
            InvoiceLinesSection(selected)
            AllowancesSection(selected)
            InvoiceVatAndTotalsSection(selected)
            InvoiceHistory(selected)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Issue, edit and cancel are INVOICE_WRITE on the server; hidden for a read-only group (FO-03).
                // UI-D-20: only the actions the STATUS allows are shown (a draft: issue + edit; an issued
                // invoice: storno), each still disabled while a mutation runs; the server refuses the rest
                // with a 409 either way (rule 8). Storno is the outlined destructive style (SYS-20), and
                // closing the panel says "Schließen", never a second "cancel" beside the storno.
                if (mayWrite) {
                    if (selected.status == "DRAFT") {
                        PosButton(
                            text = str(AdminStringKeys.SalesInvoicesActionIssue),
                            onClick = { confirmingIssue = true },
                            enabled = state.canIssueSelected,
                        )
                        PosSecondaryButton(
                            text = str(AdminStringKeys.SalesInvoicesActionEdit),
                            onClick = onEdit,
                            enabled = !state.isMutating,
                        )
                    }
                    if (selected.status == "ISSUED") {
                        PosDangerOutlinedButton(
                            text = str(AdminStringKeys.SalesInvoicesActionCancel),
                            onClick = { confirmingCancel = true },
                            enabled = state.canCancelSelected,
                        )
                    }
                }
                if (state.canOpenPdf) {
                    PosSecondaryButton(text = str(AdminStringKeys.SalesInvoicesActionOpenPdf), onClick = onOpenPdf)
                }
                PosSecondaryButton(text = str(CommonKeys.close), onClick = onClose)
            }
        }
    }

    if (confirmingIssue) {
        PosDialog(
            onDismiss = { confirmingIssue = false },
            title = str(AdminStringKeys.SalesInvoicesActionIssue),
            kind = PosDialogKind.Confirm,
            primary =
                PosDialogAction(
                    text = str(AdminStringKeys.SalesInvoicesActionIssue),
                    onClick = {
                        confirmingIssue = false
                        onIssue()
                    },
                ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(AdminStringKeys.SalesInvoicesIssueConfirm), style = MaterialTheme.typography.bodyMedium)
                IssueSummaryCard(buyerName = selected.buyerName, gross = selected.taxInclusiveTotal, currency = selected.currency)
            }
        }
    }

    if (confirmingCancel) {
        CancelConfirmDialog(
            onDismiss = { confirmingCancel = false },
            onConfirm = { reason ->
                confirmingCancel = false
                onCancel(reason)
            },
        )
    }
}

/**
 * What an issued invoice still owes: its status as dot + word, the open amount with its due date and
 * — while something is open and the principal may read the Zuordnung queue — the link there.
 * Absent for a draft, a Gutschrift and an invoice whose receivable was never tracked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentLine(
    selected: SalesInvoiceDto.ResponseDto,
    onAssign: (() -> Unit)?,
) {
    if (selected.status != "ISSUED" || selected.invoiceTypeCode == "381") return
    val open = selected.openAmount ?: return
    val hasOpen = SalesFlow.hasOpenAmount(open)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasOpen) {
            PosStatusDot(label = str(AdminStringKeys.SalesInvoicesBucketOpen), tone = BelegTone.Warning)
            val money = formatMoneyForDisplay(open, selected.currency)
            val due = selected.dueDate?.asDisplayDay(LocalAppLanguage.current)
            Text(
                text =
                    if (due ==
                        null
                    ) {
                        str(AdminStringKeys.BelegflowSalesPaymentOpen, money)
                    } else {
                        str(AdminStringKeys.BelegflowSalesPaymentOpenDue, money, due)
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onAssign != null) {
                PosTextButton(text = str(AdminStringKeys.BelegflowActionAssignPayment), onClick = onAssign, height = adminTouchSize(40.dp))
            }
        } else {
            PosStatusDot(label = str(AdminStringKeys.BelegflowStageDone), tone = BelegTone.Success)
        }
    }
}

/** The Verlauf: drafted, issued, archived as PDF, paid — a check for each step reached. */
@Composable
private fun InvoiceHistory(selected: SalesInvoiceDto.ResponseDto) {
    Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
        Text(
            text = str(AdminStringKeys.BelegflowHistoryTitle),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val history = SalesFlow.history(selected.status, selected.documentId, selected.openAmount)
        val next = history.indexOfFirst { !it.second }
        PosFlowTimeline(
            steps =
                history.mapIndexed { index, (step, done) ->
                    PosFlowStep(label = str(step.labelKey), done = done, current = index == next)
                },
        )
    }
}

/**
 * The promotions a from-bill invoice shows as EN 16931 allowances (BG-20) — the "Nachlass" block of
 * its PDF: each reason with its rate and what it took off the total. Absent when there is none.
 */
@Composable
private fun AllowancesSection(selected: SalesInvoiceDto.ResponseDto) {
    if (selected.allowances.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${str(AdminStringKeys.SalesInvoicesAllowances)}:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        selected.allowances.forEach { allowance ->
            Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
                Text(text = allowance.label(), style = MaterialTheme.typography.bodySmall)
                PosMoneyText(
                    amount = allowance.effectOnTotal(),
                    currencyCode = selected.currency,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Focus key of the cancel dialog's reason field. */
private const val REASON_FIELD = "reason"

/** The reason is mandatory (at most 60 characters) and printed on the Gutschrift. */
@Composable
private fun CancelConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    // Validate on press (UI-E2-06): the button is never greyed out for a missing reason.
    var attempt by remember { mutableStateOf(0) }
    val reasonTooLong = reason.trim().length > SalesInvoiceDto.CancelRequestDto.MAX_REASON_LENGTH
    val reasonError =
        when {
            reasonTooLong -> str(AdminStringKeys.DocumentCancelReasonTooLong)
            attempt > 0 && reason.isBlank() -> str(CommonKeys.fieldRequired)
            else -> null
        }
    val focus = rememberPosFormFocus<String>()
    LaunchedEffect(attempt) { if (attempt > 0) focus.revealFirst(listOf(REASON_FIELD), mapOf(REASON_FIELD to true)) }

    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.SalesInvoicesActionCancel),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.SalesInvoicesActionCancel),
                onClick = { if (SalesInvoiceDto.CancelRequestDto.isValidReason(reason)) onConfirm(reason.trim()) else attempt++ },
            ),
        destructive = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(str(AdminStringKeys.SalesInvoicesCancelConfirm), style = MaterialTheme.typography.bodyMedium)
            PosTextField(
                value = reason,
                onValueChange = { reason = it },
                label = requiredLabel(str(AdminStringKeys.SalesInvoicesCancelReason)),
                isError = reasonError != null,
                errorMessage = reasonError,
                supportingText = {
                    Text(str(AdminStringKeys.BelegflowCounter, reason.trim().length, SalesInvoiceDto.CancelRequestDto.MAX_REASON_LENGTH))
                },
                singleLine = false,
                minLines = 3,
                modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(REASON_FIELD) },
            )
        }
    }
}

private fun SalesInvoiceBucket.labelKey() =
    when (this) {
        SalesInvoiceBucket.DRAFTS -> AdminStringKeys.SalesInvoicesBucketDrafts
        SalesInvoiceBucket.ISSUED -> AdminStringKeys.SalesInvoicesBucketIssued
        SalesInvoiceBucket.OPEN -> AdminStringKeys.SalesInvoicesBucketOpen
        SalesInvoiceBucket.OVERDUE -> AdminStringKeys.SalesInvoicesBucketOverdue
        SalesInvoiceBucket.CREDIT_NOTES -> AdminStringKeys.SalesInvoicesBucketCreditNotes
        SalesInvoiceBucket.CANCELLED -> AdminStringKeys.SalesInvoicesBucketCancelled
    }
