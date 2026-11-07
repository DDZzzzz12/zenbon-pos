package org.example.project.admin.features.allocations.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canWriteAccounting
import org.example.project.admin.core.presentation.belegfluss.InvoiceFlowStage
import org.example.project.admin.core.presentation.belegfluss.InvoiceFlowStrip
import org.example.project.admin.core.presentation.belegfluss.rememberInvoiceFlowCounts
import org.example.project.admin.core.presentation.components.AdminDrillHost
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.components.AdminListScaffold
import org.example.project.admin.core.presentation.components.AdminRailRow
import org.example.project.admin.core.presentation.components.AdminSplitPane
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.finance.presentation.BuchungsbeginnBanner
import org.example.project.admin.features.finance.presentation.BuchungsbeginnDialog
import org.example.project.admin.features.finance.presentation.BuchungsbeginnLine
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDate
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSideSheet
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.components.beleg.boldMarkupToAnnotated
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.guard.LocalLeaveGuard
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Column weights, shared by the header and every row so the two line up. */
private const val DATE_WEIGHT = 2f
private const val COUNTERPARTY_WEIGHT = 3f
private const val REMITTANCE_WEIGHT = 4f
private const val AMOUNT_WEIGHT = 2f
private const val OPEN_WEIGHT = 2f

/** The editor drawer on a small tablet. */
private val DRAWER_WIDTH = 520.dp

/** The queue rail. Wider than the default: a Verwendungszweck is long and is the thing matched on. */
private val QUEUE_RAIL_WIDTH = 340.dp

/** How much of a remittance the rail shows before eliding; the editor beside it carries the rest. */
private const val REMITTANCE_MAX_LINES = 2

/** Width of the terminal panel's short fields, so an amount and an account number line up. */
private val FIELD_WIDTH = 220.dp

/** `2026-03-01T00:00` &rarr; `2026-03-01`; the queue's dates arrive as ISO date-times. */
private const val DATE_LENGTH = 10

/**
 * Zuordnung — the daily queue of payments that still need assigning.
 *
 * Rows are laid out by hand rather than with `PosDataTable`, whose cells are plain strings: a row
 * carries two money amounts (which must go through [PosMoneyText], never a hand-formatted string)
 * and expands into an editor.
 *
 * The Belegfluss strip on top stands on *Zuordnen*; the other stages lead to the matching Rechnungen
 * bucket through [onOpenInvoices] (a bucket name, as `InvoicesRoute` takes it).
 */
@Composable
fun AllocationsScreen(onOpenInvoices: (String) -> Unit) {
    val viewModel: AllocationsViewModel = koinViewModel()
    val state = viewModel.state
    val bookkeeping = viewModel.bookkeeping
    val canWriteAccounting = koinInject<TokenStore>().canWriteAccounting

    // LaunchedEffect, not VM init: adminApp VMs share one ViewModelStoreOwner and survive logout,
    // so init would show the previous session's queue.
    LaunchedEffect(Unit) { viewModel.load() }

    // Reloaded when the queue's size moves: a booking changes what is still waiting to be assigned.
    val flowCounts = rememberInvoiceFlowCounts(refreshKey = state.total)

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.AllocationsTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    var detailOpen by remember { mutableStateOf(false) }
    val selectedRow = state.rows.firstOrNull { it.transactionId == state.expandedTransactionId }
    // A small tablet (Medium) keeps the queue as the page and opens the editor as a 520dp drawer over it.
    val medium = LocalPosLayout.current == PosLayout.Medium

    // At screen level: the draft lives in the view model and the editor pane unmounts at the phone
    // width. The phone Back button is asked centrally by AdminSplitPane (onCloseDetail below).
    val leaveGuard = LocalLeaveGuard.current
    rememberDirtySource(
        dirty = state.editorDirty,
        onDiscard = {
            detailOpen = false
            viewModel.closeEditor()
        },
    )

    // Phone + open detail: the editor alone fills the screen; Back (in AdminSplitPane) restores the header.
    AdminDrillHost(detailOpen = detailOpen, hasSelection = selectedRow != null) { phoneDetail ->
        AdminListScaffold(
            title = str(AdminStringKeys.AllocationsTitle),
            showHeader = !phoneDetail,
            toolbar = {
                Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.lg)) {
                    if (flowCounts != null) {
                        InvoiceFlowStrip(
                            counts = AllocationStripCounts.of(flowCounts, state.filter, state.total, state.rows),
                            selected = InvoiceFlowStage.ASSIGN,
                            // This is the Zuordnen stage; the others open their Rechnungen bucket.
                            onSelect = { stage -> if (stage != InvoiceFlowStage.ASSIGN) onOpenInvoices(stage.bucket.name) },
                        )
                    }
                    val startDate = bookkeeping.state.startDate
                    if (bookkeeping.state.showBanner) {
                        BuchungsbeginnBanner(
                            startDate = startDate.orEmpty(),
                            canChange = canWriteAccounting,
                            onChange = bookkeeping::startEditing,
                            onDismiss = bookkeeping::acknowledge,
                        )
                    } else if (bookkeeping.state.showCurrentDate) {
                        BuchungsbeginnLine(
                            startDate = startDate.orEmpty(),
                            canChange = canWriteAccounting,
                            onChange = bookkeeping::startEditing,
                        )
                    }
                }
            },
            filters = {
                FilterChips(
                    selected = state.filter,
                    onSelect = { filter ->
                        // Tapping the applied chip is not a request, so it must not prompt either.
                        if (filter != state.filter) leaveGuard.requestLeave { viewModel.selectFilter(filter) }
                    },
                )
            },
            resultCount = str(AdminStringKeys.AllocationsCount, state.rows.size),
            isLoading = state.isLoading && !phoneDetail,
            isEmpty = state.rows.isEmpty() && !state.isLoading && !phoneDetail,
            empty = {
                AdminListEmptyState(
                    message = str(emptyMessageKey(state.filter)),
                    icon = Icons.Default.CompareArrows,
                )
            },
        ) {
            AdminSplitPane(
                list = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.rows.forEach { row ->
                            QueueRailRow(
                                row = row,
                                selected = row.transactionId == state.expandedTransactionId,
                                enabled = !state.isLoading,
                                onSelect = {
                                    // In the drill a tap on the expanded row must only open it; side by side (detailOpen) it keeps the old toggle.
                                    val onlyReopens = !detailOpen && state.expandedTransactionId == row.transactionId
                                    val open = {
                                        if (!onlyReopens) viewModel.toggleExpanded(row.transactionId)
                                        detailOpen = true
                                    }

                                    // Switching rows (or collapsing the open one) drops the drafts, so it asks; re-opening discards nothing.
                                    if (onlyReopens) open() else leaveGuard.requestLeave(open)
                                },
                            )
                        }
                    }
                },
                railWidth = QUEUE_RAIL_WIDTH,
                detail = {
                    val selected = selectedRow
                    if (selected == null) {
                        AdminListEmptyState(message = str(AdminStringKeys.AllocationsSelectPrompt))
                    } else {
                        AllocationEditor(row = selected, state = state, viewModel = viewModel, compact = phoneDetail)
                    }
                },
                detailOpen = detailOpen,
                // No drill on Medium: the editor is the drawer below, so the pane never composes it.
                onCloseDetail =
                    if (medium) {
                        null
                    } else {
                        {
                            detailOpen = false
                            viewModel.closeEditor()
                        }
                    },
                detailEmpty = selectedRow == null || medium,
            )
        }
    }

    if (medium && detailOpen && selectedRow != null) {
        PosSideSheet(
            // The scrim and the × ask first when there are unsaved amounts, like every other leave.
            onDismiss = {
                leaveGuard.requestLeave {
                    detailOpen = false
                    viewModel.closeEditor()
                }
            },
            title = selectedRow.counterpartyName ?: selectedRow.counterpartyIban.orEmpty(),
            width = DRAWER_WIDTH,
        ) {
            AllocationEditor(row = selectedRow, state = state, viewModel = viewModel, compact = true)
        }
    }

    if (bookkeeping.state.isEditing) {
        BuchungsbeginnDialog(
            currentValue = bookkeeping.state.startDate.orEmpty(),
            isSaving = bookkeeping.state.isSaving,
            onSave = viewModel::saveBookkeepingStart,
            onDismiss = bookkeeping::cancelEditing,
        )
    }
}

@Composable
private fun FilterChips(
    selected: AllocationDto.QueueFilter,
    onSelect: (AllocationDto.QueueFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AllocationDto.QueueFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(str(filterLabelKey(filter))) },
            )
        }
    }
}

/**
 * One payment in the queue.
 *
 * Replaces the weighted six-column row whose editor expanded **in place**, pushing every row below
 * it down the page — in a queue worked top to bottom, that reflowed the list on every single item.
 * The editor now lives in a fixed pane beside the queue and the list never moves.
 *
 * It also retires the 148 dp action column. That width was pinned because a weighted share fell
 * below the button's own German label and Compose clipped it silently; with the action gone, the
 * Verwendungszweck gets the room it always needed.
 */
@Composable
private fun QueueRailRow(
    row: AllocationDto.QueueRowDto,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    AdminRailRow(selected = selected, onClick = onSelect, enabled = enabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = row.counterpartyName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                // The remittance is what an operator actually matches on, so it gets room to be read
                // rather than a share of a row it was always losing.
                Text(
                    text =
                        listOfNotNull(
                            row.valueDate.formatShortDate(LocalAppLanguage.current),
                            row.remittance?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = REMITTANCE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.reversalIndicator) {
                    Text(
                        text = str(AdminStringKeys.AllocationsReversalBadge),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = LocalPosColors.current.posOccupied,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PosMoneyText(
                    amount = row.amount,
                    currencyCode = row.currency,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = str(AdminStringKeys.AllocationsColumnOpen).lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PosMoneyText(
                        amount = row.openAmount,
                        currencyCode = row.currency,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllocationEditor(
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
    compact: Boolean,
) {
    // Reversing an assignment puts the payment and the invoice back into their open lists, so it asks first.
    var reversing by remember { mutableStateOf<AllocationDto.AllocationRowDto?>(null) }
    reversing?.let { allocation ->
        PosDialog(
            onDismiss = { reversing = null },
            title = str(AdminStringKeys.BelegflowReverseTitle),
            kind = PosDialogKind.Confirm,
            primary =
                PosDialogAction(
                    text = str(AdminStringKeys.BelegflowReverseConfirm),
                    onClick = {
                        reversing = null
                        viewModel.reverse(allocation.id)
                    },
                ),
            destructive = true,
        ) {
            Text(
                text =
                    boldMarkupToAnnotated(
                        str(
                            AdminStringKeys.BelegflowReverseBody,
                            formatMoneyForDisplay(allocation.amount, row.currency),
                            allocationTargetName(allocation),
                        ),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = if (compact) 0.dp else 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaymentHeader(row)

        // A returned debit is not queue work of its own: the Stornolauf voids the allocation of the
        // debit it reverses, which is why that debit is back in the queue. Saying so here is the
        // operator's only notice of it besides the audit log.
        if (row.reversalIndicator) {
            Text(
                text = str(AdminStringKeys.AllocationsReversalHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (row.allocations.isEmpty()) {
            Text(str(AdminStringKeys.AllocationsAssigned), style = MaterialTheme.typography.labelLarge)
            Text(
                text = str(AdminStringKeys.BelegflowAssignedNone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.allocations.isNotEmpty()) {
            Text(str(AdminStringKeys.AllocationsAssigned), style = MaterialTheme.typography.labelLarge)
            row.allocations.forEach { allocation ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // What the allocation settled, in the operator's words. Falling straight
                        // through to the wire constant renders a fee as "FEE" — a name from the
                        // database, not an answer to "what was this payment for?".
                        text = allocationTargetName(allocation),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PosMoneyText(amount = allocation.amount, currencyCode = row.currency)
                    if (holds(Capability.ACCOUNTING_WRITE)) {
                        PosSecondaryButton(
                            text = str(AdminStringKeys.AllocationsReverse),
                            onClick = { reversing = allocation },
                            enabled = !state.isSaving,
                        )
                    }
                }
            }
        }

        // Above Rest offen and the candidates: it is the answer to the question the row was opened
        // to ask. Renders nothing when there is nothing to propose.
        SuggestionList(
            items = state.suggestions,
            isSaving = state.isSaving,
            onAccept = viewModel::acceptSuggestion,
        )

        Text(str(AdminStringKeys.BelegflowTargetCaption), style = MaterialTheme.typography.labelLarge)
        TargetKindChips(selected = state.targetKind, onSelect = viewModel::selectTargetKind)

        when (state.targetKind) {
            "INVOICE" -> OpenInvoicePanel(row = row, state = state, viewModel = viewModel)
            "CONTRACT_INSTALMENT" -> OpenInstalmentPanel(row = row, state = state, viewModel = viewModel)
            "SALES_INVOICE" -> OpenSalesInvoicePanel(row = row, state = state, viewModel = viewModel)
            "EXPENSE_NO_DOC", "FEE" -> TerminalTargetPanel(row = row, state = state, viewModel = viewModel)
            "INTERNAL_TRANSFER" -> OwnAccountPanel(row = row, state = state, viewModel = viewModel)
            "CASH_TRANSIT" -> CashEntryPanel(row = row, state = state, viewModel = viewModel)
            else -> Unit
        }
        if (state.targetKind in AllocationSelection.PICKED_KINDS) {
            AssignSummaryBar(row = row, state = state, viewModel = viewModel)
        }
    }
}

/**
 * "Zuordnung x von y, Rest z" and the one *Zuordnen*: books every candidate that carries an amount as a
 * single batch ([AllocationsViewModel.allocateSelected]). The button is the same rule the batch applies —
 * something is selected and it does not exceed the payment's open amount — and needs ACCOUNTING_WRITE.
 * A selection above the open amount shows the remainder in the error colour instead of a tick.
 */
@Composable
private fun AssignSummaryBar(
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
) {
    val picked = AllocationSelection.selected(state.targetKind, state, row.openAmount)
    val total = AllocationSelection.total(picked)
    val rest = AllocationSelection.remaining(row.openAmount, total)
    val over = AllocationSelection.overPayment(state.targetKind, state, row.openAmount)
    val settled = rest != null && rest.signum == 0 && picked.isNotEmpty()
    val text =
        str(
            AdminStringKeys.BelegflowAssignSummary,
            formatMoneyForDisplay(total.toPlainString(), row.currency),
            formatMoneyForDisplay(row.openAmount, row.currency),
            rest?.let { formatMoneyForDisplay(it.toPlainString(), row.currency) }.orEmpty(),
        )
    // The refusal is explained in the bar itself, in the error colour, and Zuordnen stays disabled.
    val shown =
        if (over) str(AdminStringKeys.BelegflowSumExceeds, formatMoneyForDisplay(row.openAmount, row.currency)) else text
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(PosSpacing.xl),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (settled || over) {
            Icon(
                imageVector = if (over) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (over) MaterialTheme.colorScheme.error else LocalPosColors.current.posAvailable,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = boldMarkupToAnnotated(shown),
            style = MaterialTheme.typography.bodyMedium,
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (holds(Capability.ACCOUNTING_WRITE)) {
            PosButton(
                text = str(AdminStringKeys.AllocationsAssign),
                icon = Icons.Default.Check,
                onClick = { viewModel.allocateSelected(row.transactionId) },
                enabled = !state.isSaving && AllocationSelection.canAssign(picked, row.openAmount),
            )
        }
    }
}

/**
 * What an allocation settled, in the operator's words. Falling straight through to the wire constant
 * renders a fee as "FEE" — a name from the database, not an answer to "what was this payment for?".
 */
@Composable
private fun allocationTargetName(allocation: AllocationDto.AllocationRowDto): String =
    allocation.invoiceNumber
        ?: allocation.salesInvoiceNumber
        ?: allocation.contractName
        ?: allocation.bookingText
        ?: targetKindLabel(allocation.targetKind)

/**
 * Which payment is being allocated, as the design's transaction card: counterparty and amount, the
 * booking date with the Verwendungszweck beneath, then what is still *Rest offen*.
 */
@Composable
private fun PaymentHeader(row: AllocationDto.QueueRowDto) {
    val language = LocalAppLanguage.current
    Column(
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(PosSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.counterpartyName ?: row.counterpartyIban.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            PosMoneyText(
                amount = row.amount,
                currencyCode = row.currency,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.End,
            )
        }
        Text(
            text = listOfNotNull(row.bookingDate.formatShortDate(language), row.remittance?.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(str(AdminStringKeys.AllocationsRestOpen), style = MaterialTheme.typography.bodyMedium)
            PosMoneyText(
                amount = row.openAmount,
                currencyCode = row.currency,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** The seven kinds in the order an operator meets them, commonest first. */
private val TARGET_KINDS =
    listOf("INVOICE", "CONTRACT_INSTALMENT", "SALES_INVOICE", "EXPENSE_NO_DOC", "FEE", "CASH_TRANSIT", "INTERNAL_TRANSFER")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetKindChips(
    selected: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TARGET_KINDS.forEach { kind ->
            FilterChip(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                // TARGET_KINDS is a fixed literal list, so every one of them has a label; the
                // fallback is there so a future entry cannot silently render nothing.
                label = { Text(targetKindLabel(kind)) },
            )
        }
    }
}

@Composable
private fun OpenInvoicePanel(
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(str(AdminStringKeys.AllocationsOpenInvoices), style = MaterialTheme.typography.labelLarge)
        if (state.openInvoices.isEmpty()) {
            Text(
                text = str(AdminStringKeys.AllocationsNoOpenInvoices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.openInvoices.forEach { invoice ->
            OpenInvoiceRow(invoice = invoice, row = row, state = state, viewModel = viewModel)
        }
    }
}

/**
 * The instalments a contract's schedule still shows as unpaid — the rent or leasing debit the
 * matcher did not recognise.
 */
@Composable
private fun OpenInstalmentPanel(
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(str(AdminStringKeys.AllocationsOpenInstalments), style = MaterialTheme.typography.labelLarge)
        if (state.openInstalments.isEmpty()) {
            Text(
                text = str(AdminStringKeys.AllocationsNoOpenInstalments),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.openInstalments.forEach { instalment ->
            OpenInstalmentRow(instalment = instalment, row = row, state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun OpenInstalmentRow(
    instalment: AllocationDto.OpenInstalmentDto,
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
) {
    val key = draftKey("CONTRACT_INSTALMENT", instalment.instalmentId)
    OpenItemRow(
        // The supplier comes first: two contracts can carry the same name across suppliers, and
        // an operator picks by what the payment is *for*, not by a contract id.
        label =
            listOfNotNull(
                instalment.supplierName,
                instalment.contractName,
                // The wire carries an ISO date-time; the due date is a date, and rendering
                // "2026-06-01T00:00" beside one reads as a bug.
                instalment.dueDate.formatShortDate(LocalAppLanguage.current),
            ).joinToString(" · "),
        openAmount = instalment.openAmount,
        currency = instalment.currency,
        sub = null,
        draft = state.amountDrafts[key].orEmpty(),
        onDraftChange = { viewModel.setAmount(key, it) },
        onToggle = { toggleCandidate("CONTRACT_INSTALMENT", key, instalment.openAmount, row, state, viewModel, it) },
    )
}

/**
 * A target with no candidate to pick: a bank fee, or an expense whose receipt does not exist.
 *
 * The booking text is the only record of what an undocumented expense was — so it is required for
 * `EXPENSE_NO_DOC`, and the button stays disabled without it, matching the server's 400.
 */
@Composable
private fun TerminalTargetPanel(
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
) {
    val kind = state.targetKind
    val key = draftKey(kind, null)
    val textRequired = kind == "EXPENSE_NO_DOC"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PosTextField(
            value = state.amountDrafts[key].orEmpty(),
            onValueChange = { viewModel.setAmount(key, it) },
            label = str(AdminStringKeys.AllocationsAmountLabel),
            textFieldType = PosTextFieldType.Price,
            modifier = Modifier.widthIn(max = FIELD_WIDTH).fillMaxWidth(),
        )
        PosTextField(
            value = state.bookingTextDraft,
            onValueChange = viewModel::setBookingText,
            label = str(AdminStringKeys.AllocationsBookingTextLabel),
            supportingText =
                if (textRequired) {
                    {
                        Text(
                            text = str(AdminStringKeys.AllocationsBookingTextRequired),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    null
                },
            modifier = Modifier.fillMaxWidth(),
        )
        PosTextField(
            value = state.accountNoDraft,
            onValueChange = viewModel::setAccountNo,
            label = str(AdminStringKeys.AllocationsAccountNoLabel),
            modifier = Modifier.widthIn(max = FIELD_WIDTH).fillMaxWidth(),
        )
        if (holds(Capability.ACCOUNTING_WRITE)) {
            PosButton(
                text = str(AdminStringKeys.AllocationsAssign),
                onClick = { viewModel.allocateTerminal(row.transactionId, kind) },
                // The same rule the ViewModel applies before dispatching, and the server before writing.
                enabled = !state.isSaving && viewModel.terminalDraftFor(kind, row.openAmount) != null,
            )
        }
    }
}

/**
 * An *Eigenübertrag*: money moved between two of our own accounts.
 *
 * Every account is offered, including the payment's own: a bank transaction carries the
 * counterparty's IBAN, not ours, so which of our accounts it was booked on is not knowable here.
 * Guessing — by matching the counterparty IBAN, say — would hide the right answer as often as the
 * wrong one, so the choice is left with the operator.
 */
@Composable
private fun OwnAccountPanel(
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
) {
    CounterLegPanel(
        titleKey = AdminStringKeys.AllocationsOwnAccounts,
        emptyKey = AdminStringKeys.AllocationsNoOwnAccounts,
        isEmpty = state.bankAccounts.isEmpty(),
        kind = "INTERNAL_TRANSFER",
        row = row,
        state = state,
        viewModel = viewModel,
        onAssign = { viewModel.allocateTransfer(row.transactionId) },
    ) {
        state.bankAccounts.forEach { account ->
            CounterLegRow(
                selected = state.selectedCounterId == account.id,
                onSelect = { viewModel.selectCounter(account.id) },
                label = listOfNotNull(account.bankName, account.iban, account.accountHolder).joinToString(" · "),
            )
        }
    }
}

/**
 * A *Geldtransit*: till cash deposited at the bank, whose counter-leg is the Kassenbuch entry that
 * recorded the cash leaving the till.
 *
 * The list is every recent entry, in the book's own order — **not** narrowed to deposits and
 * withdrawals. Which entry pairs with this payment follows from direction, amount and date together,
 * and a type filter is a fragment of that rule that would quietly withhold a legitimate entry.
 */
@Composable
private fun CashEntryPanel(
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
) {
    CounterLegPanel(
        titleKey = AdminStringKeys.AllocationsCashEntries,
        emptyKey = AdminStringKeys.AllocationsNoCashEntries,
        isEmpty = state.cashEntries.isEmpty(),
        kind = "CASH_TRANSIT",
        row = row,
        state = state,
        viewModel = viewModel,
        onAssign = { viewModel.allocateTransit(row.transactionId) },
    ) {
        state.cashEntries.forEach { entry ->
            CounterLegRow(
                selected = state.selectedCounterId == entry.headerId,
                onSelect = { viewModel.selectCounter(entry.headerId) },
                label = listOf(entry.belegNr, entry.belegtext, entry.belegDatum.take(DATE_LENGTH)).joinToString(" · "),
                // The entry's own currency, not the payment's: the two legs are separate bookings and
                // a cash book may be kept in another currency than the account.
                amount = entry.totalAmount,
                currencyCode = entry.waehrung,
            )
        }
    }
}

/**
 * The frame both counter-leg pickers share: candidates, one amount, one button.
 *
 * A single amount field rather than one per candidate, because exactly one counter-leg is named per
 * booking — the draft is keyed on the kind alone for the same reason.
 */
@Composable
private fun CounterLegPanel(
    titleKey: StringKey,
    emptyKey: StringKey,
    isEmpty: Boolean,
    kind: String,
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
    onAssign: () -> Unit,
    candidates: @Composable () -> Unit,
) {
    val key = draftKey(kind, null)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(str(titleKey), style = MaterialTheme.typography.labelLarge)
        if (isEmpty) {
            Text(
                text = str(emptyKey),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        candidates()
        PosTextField(
            value = state.amountDrafts[key].orEmpty(),
            onValueChange = { viewModel.setAmount(key, it) },
            label = str(AdminStringKeys.AllocationsAmountLabel),
            textFieldType = PosTextFieldType.Price,
            modifier = Modifier.widthIn(max = FIELD_WIDTH).fillMaxWidth(),
        )
        if (holds(Capability.ACCOUNTING_WRITE)) {
            PosButton(
                text = str(AdminStringKeys.AllocationsAssign),
                onClick = onAssign,
                // The same rule the ViewModel applies before dispatching, and the server before writing:
                // no counter-leg, no booking.
                enabled = !state.isSaving && viewModel.counterLegDraftFor(kind, row.openAmount) != null,
            )
        }
    }
}

/**
 * One candidate counter-leg. The radio makes the current choice visible without a colour cue.
 *
 * The **row** is the one click target, tagged [Role.RadioButton] so a screen reader announces it as
 * one of a set rather than as unlabelled clickable text. The radio itself takes `onClick = null` —
 * a separately clickable control inside an already-clickable row is a second, redundant stop in the
 * traversal order that does exactly what its parent does.
 */
@Composable
private fun CounterLegRow(
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
    amount: String? = null,
    currencyCode: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        if (amount != null && currencyCode != null) {
            PosMoneyText(amount = amount, currencyCode = currencyCode)
        }
    }
}

@Composable
private fun OpenInvoiceRow(
    invoice: AllocationDto.OpenInvoiceDto,
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
) {
    val key = draftKey("INVOICE", invoice.invoiceId)
    OpenItemRow(
        label = listOfNotNull(invoice.supplierName, invoice.invoiceNumber).joinToString(" · "),
        openAmount = invoice.openAmount,
        currency = invoice.currency,
        sub = invoice.dueDate?.let { str(AdminStringKeys.BelegflowDueOn, it.formatShortDate(LocalAppLanguage.current)) },
        draft = state.amountDrafts[key].orEmpty(),
        onDraftChange = { viewModel.setAmount(key, it) },
        onToggle = { toggleCandidate("INVOICE", key, invoice.openAmount, row, state, viewModel, it) },
    )
}

/**
 * The case-C (STANDALONE) invoices a credit payment can settle — the AR mirror of
 * [OpenInvoicePanel]. The label uses `buyer_name` (the snapshot, not the debtor record), so a
 * one-off guest invoice still reads correctly.
 */
@Composable
private fun OpenSalesInvoicePanel(
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(str(AdminStringKeys.AllocationsOpenSalesInvoices), style = MaterialTheme.typography.labelLarge)
        if (state.openSalesInvoices.isEmpty()) {
            Text(
                text = str(AdminStringKeys.AllocationsNoOpenSalesInvoices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.openSalesInvoices.forEach { invoice ->
            OpenSalesInvoiceRow(invoice = invoice, row = row, state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun OpenSalesInvoiceRow(
    invoice: AllocationDto.OpenSalesInvoiceDto,
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
) {
    val key = draftKey("SALES_INVOICE", invoice.salesInvoiceId)
    OpenItemRow(
        label = listOfNotNull(invoice.debtorName, invoice.invoiceNumber).joinToString(" · "),
        openAmount = invoice.openAmount,
        currency = invoice.currency,
        sub = invoice.dueDate?.let { str(AdminStringKeys.BelegflowDueOn, it.formatShortDate(LocalAppLanguage.current)) },
        draft = state.amountDrafts[key].orEmpty(),
        onDraftChange = { viewModel.setAmount(key, it) },
        onToggle = { toggleCandidate("SALES_INVOICE", key, invoice.openAmount, row, state, viewModel, it) },
    )
}

/** Below this list width an open item stacks: label and amount on top, the amount field full width below. */
private val OPEN_ITEM_WIDE_MIN = 560.dp
private val OPEN_AMOUNT_WIDTH = 110.dp
private val OPEN_INPUT_WIDTH = 140.dp

/**
 * One open item (invoice, sales invoice or instalment) of the Zuordnung editor: a tick box, the label
 * with its due date beneath, what it still owes in bold and the amount to put on it. Ticking fills in
 * the amount ([toggleCandidate]); typing one ticks it. There is no per-row button: all ticked items are
 * booked together by the [AssignSummaryBar]. Rows are separated by a divider so a field's floating label
 * can only belong to its own row.
 *
 * The tick box shows beside the label on a wide list only; on a narrow one the amount field alone
 * selects (the design's phone form).
 */
@Composable
private fun OpenItemRow(
    label: String,
    sub: String?,
    openAmount: String,
    currency: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val writable = holds(Capability.ACCOUNTING_WRITE)
    // Why an amount is refused is said under the field: above what this item still owes.
    val tooHigh = AllocationSelection.exceeds(draft, openAmount)
    val maxMessage = if (tooHigh) str(AdminStringKeys.BelegflowAmountMax, formatMoneyForDisplay(openAmount, currency)) else null
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            val amountField: @Composable (Modifier) -> Unit = { fieldModifier ->
                PosTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    label = str(AdminStringKeys.AllocationsAmountLabel),
                    textFieldType = PosTextFieldType.Price,
                    enabled = writable,
                    isError = tooHigh,
                    errorMessage = maxMessage,
                    modifier = fieldModifier,
                )
            }
            val title: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    sub?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            val owes: @Composable (Modifier) -> Unit = { m ->
                PosMoneyText(
                    amount = openAmount,
                    currencyCode = currency,
                    modifier = m,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.End,
                )
            }
            if (maxWidth >= OPEN_ITEM_WIDE_MIN) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.isNotBlank(), onCheckedChange = onToggle, enabled = writable)
                    title(Modifier.weight(1f))
                    owes(Modifier.width(OPEN_AMOUNT_WIDTH))
                    amountField(Modifier.width(OPEN_INPUT_WIDTH))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        title(Modifier.weight(1f))
                        owes(Modifier)
                    }
                    amountField(Modifier.fillMaxWidth())
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Ticks or unticks a candidate. Ticking fills in its open amount, capped by what the payment has left
 * after the other drafts ([AllocationSelection.prefill]); unticking empties the box. Nothing is booked
 * here — only the summary bar's *Zuordnen* sends.
 */
private fun toggleCandidate(
    kind: String,
    key: String,
    candidateOpen: String,
    row: AllocationDto.QueueRowDto,
    state: AllocationsState,
    viewModel: AllocationsViewModel,
    checked: Boolean,
) {
    if (!checked) {
        viewModel.setAmount(key, "")
        return
    }
    val others = AllocationSelection.total(AllocationSelection.selected(kind, state, row.openAmount).filter { it.key != key })
    viewModel.setAmount(key, AllocationSelection.prefill(candidateOpen, row.openAmount, others).orEmpty())
}

/**
 * The label for a target kind, or **null** when we have no wording for it.
 *
 * A `when` over the wire strings rather than an enum, so the compiler cannot make it exhaustive: a
 * kind a newer server introduced must not crash the editor. Null rather than a stand-in key, because
 * every key we own names a *different* kind — labelling an unknown kind with one of them states
 * something false, where the raw wire value at least says what the row actually is and can be
 * grepped for.
 */
private fun targetKindLabelKey(kind: String): StringKey? =
    when (kind) {
        "INVOICE" -> AdminStringKeys.AllocationsTargetKindInvoice
        "CONTRACT_INSTALMENT" -> AdminStringKeys.AllocationsTargetKindContractInstalment
        "SALES_INVOICE" -> AdminStringKeys.AllocationsTargetKindSalesInvoice
        "EXPENSE_NO_DOC" -> AdminStringKeys.AllocationsTargetKindExpenseNoDoc
        "FEE" -> AdminStringKeys.AllocationsTargetKindFee
        "CASH_TRANSIT" -> AdminStringKeys.AllocationsTargetKindCashTransit
        "INTERNAL_TRANSFER" -> AdminStringKeys.AllocationsTargetKindInternalTransfer
        else -> null
    }

/**
 * The kind's wording, falling back to the raw wire value for a kind we have no wording for.
 *
 * `"CASH_TRANSIT"` on screen is poor, but it is what the row *is*; the alternative — reusing one of
 * our other labels — reads as a confident, wrong answer.
 */
@Composable
private fun targetKindLabel(kind: String): String {
    val key = targetKindLabelKey(kind) ?: return kind
    return str(key)
}

private fun filterLabelKey(filter: AllocationDto.QueueFilter): StringKey =
    when (filter) {
        AllocationDto.QueueFilter.NICHT_ZUGEORDNET -> AdminStringKeys.AllocationsFilterUnassigned
        AllocationDto.QueueFilter.ALLE -> AdminStringKeys.AllocationsFilterAll
    }

private fun emptyMessageKey(filter: AllocationDto.QueueFilter): StringKey =
    when (filter) {
        AllocationDto.QueueFilter.NICHT_ZUGEORDNET -> AdminStringKeys.AllocationsEmptyUnassigned
        AllocationDto.QueueFilter.ALLE -> AdminStringKeys.AllocationsEmptyAll
    }
