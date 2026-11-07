package org.example.project.admin.features.debtors.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminDrillHost
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.components.AdminListScaffold
import org.example.project.admin.core.presentation.components.AdminListSkeleton
import org.example.project.admin.core.presentation.components.AdminRailRow
import org.example.project.admin.core.presentation.components.AdminSplitPane
import org.example.project.admin.core.presentation.components.ListToggleFilterBar
import org.example.project.admin.core.presentation.components.ListTogglePanel
import org.example.project.admin.core.presentation.components.ListTogglePanelFilter
import org.example.project.admin.core.presentation.format.asDisplayDay
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.salesinvoices.presentation.salesInvoiceStatusLabelKey
import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.orDash
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveTable
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosContentRow
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.filter.PosFilterSearch
import org.example.project.ui.components.filter.pluralResultKey
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.example.project.ui.guard.LocalLeaveGuard
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.layout.PaneSection
import org.example.project.ui.layout.PosPaneHost
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

/** `2027-01-31T00:00` &rarr; `2027-01-31`; a null date is a blank, not the word "null". */

/**
 * Debitoren — the customers we invoice, beside the invoices we issued them.
 *
 * The right pane is the point: Debitoren and Ausgangsrechnungen had no link at all, so answering
 * "what have we billed this customer" meant leaving here, opening the invoice list and reading it
 * for a name. The invoices are fetched per debtor through the list's `debtorId` filter rather than
 * narrowed from a page, because that page is newest-first and would hide a quiet customer's history
 * entirely.
 *
 * Editing happens **in the pane**, through the same form [DebtorDetailScreen] shows. That was not
 * possible while the form owned its own `verticalScroll` — nesting one inside the scaffold's crashes
 * on wasm — so the sections were split out into `DebtorDetailSections` and the scrolling left to
 * whichever caller has room for it. [DebtorDetailScreen] survives as a deep link and as the create
 * form; the list no longer navigates to it.
 */
@Composable
fun DebtorsScreen(onCreate: () -> Unit) {
    val viewModel: DebtorsViewModel = koinViewModel()
    val state = viewModel.state
    val mayWrite = DebtorsAccess.of(LocalAccessPolicy.current).mayWrite

    /*
     * Which save the list has already re-read for.
     *
     * The reload makes the scaffold swap its content for a skeleton, which takes the edit pane out
     * of composition; on the way back its effects start again and would report the same save a
     * second time. This screen stays composed throughout, so the guard belongs here — remembering it
     * inside the pane would be reset by the very remount it has to survive.
     */
    var lastHandledSave by remember { mutableStateOf(0) }
    var detailOpen by remember { mutableStateOf(false) }
    val leaveGuard = LocalLeaveGuard.current

    // The edit draft lives in the app-wide DebtorDetailViewModel, so the guard watches it from the
    // screen, not from the pane: a resize across the phone width unmounts the pane while the draft
    // survives, and a registration that went with the pane would leave that draft unprotected.
    val detailViewModel: DebtorDetailViewModel = koinViewModel()
    rememberDirtySource(
        dirty = detailViewModel.state.isDirty && !detailViewModel.state.isDeleted,
        onDiscard = detailViewModel::discardDraft,
    )

    // LaunchedEffect, not VM init: adminApp VMs share one ViewModelStoreOwner and survive logout,
    // so init would show the previous session's debtors.
    LaunchedEffect(Unit) { viewModel.load() }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.DebtorsTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    // Above the drill host, so the panel and its draft survive a resize across the phone width. The
    // toggle applies through setActiveOnly exactly as the old chip did (no leave prompt: the draft of an
    // open edit lives in DebtorDetailViewModel and survives the reload, guarded by the source above).
    val filterPanel = rememberPosFilterPanelState(ListTogglePanelFilter(state.activeOnly), FilterPanelKeys.DEBTORS)
    val toggleLabel = str(AdminStringKeys.DebtorsFilterActive)
    ListTogglePanel(
        toggleLabel = toggleLabel,
        applied = state.activeOnly,
        onToggle = viewModel::setActiveOnly,
        filterPanel = filterPanel,
    )

    // Phone + open detail: the detail alone fills the screen; Back (in AdminSplitPane) restores the header.
    AdminDrillHost(detailOpen = detailOpen, hasSelection = state.selectedDebtor != null) { phoneDetail ->
        AdminListScaffold(
            title = str(AdminStringKeys.DebtorsTitle),
            showHeader = !phoneDetail,
            actions = {
                if (mayWrite) PosButton(text = str(AdminStringKeys.DebtorsAddButton), onClick = onCreate)
            },
            toolbar = {
                ListToggleFilterBar(
                    search =
                        PosFilterSearch(
                            query = state.search,
                            onQueryChange = viewModel::updateSearch,
                            placeholder = str(AdminStringKeys.DebtorsSearchPlaceholder),
                        ),
                    resultText = str(pluralResultKey(state.total, AdminStringKeys.DebtorsCountOne, AdminStringKeys.DebtorsCount), state.total),
                    toggleLabel = toggleLabel,
                    applied = state.activeOnly,
                    onToggle = viewModel::setActiveOnly,
                    filterPanel = filterPanel,
                )
            },
            isLoading = state.isLoading && !phoneDetail,
            isEmpty = state.items.isEmpty() && !state.isLoading && !phoneDetail,
            empty = {
                AdminListEmptyState(
                    message =
                        if (state.hasActiveFilter) {
                            str(AdminStringKeys.DebtorsEmptyFiltered)
                        } else {
                            str(AdminStringKeys.DebtorsEmpty)
                        },
                    icon = Icons.Default.AccountBox,
                    actionText =
                        when {
                            state.hasActiveFilter -> str(AdminStringKeys.CommonResetFilters)
                            mayWrite -> str(AdminStringKeys.DebtorsAddButton)
                            else -> null
                        },
                    onAction =
                        when {
                            state.hasActiveFilter -> viewModel::resetFilters
                            mayWrite -> onCreate
                            else -> null
                        },
                )
            },
        ) {
            AdminSplitPane(
                list = {
                    DebtorRail(
                        debtors = state.items,
                        selectedId = state.selectedDebtorId,
                        onSelect = { id ->
                            // Picking the debtor already open is not a leave; another one discards the edit, so ask.
                            val open = {
                                viewModel.selectDebtor(id)
                                detailOpen = true
                            }
                            if (id == state.selectedDebtorId) open() else leaveGuard.requestLeave(open)
                        },
                    )
                },
                detail = {
                    DebtorDetailPane(
                        debtor = state.selectedDebtor,
                        invoices = state.invoices,
                        isLoadingInvoices = state.isLoadingInvoices,
                        onSaved = { revision ->
                            if (revision > lastHandledSave) {
                                lastHandledSave = revision
                                viewModel.load()
                            }
                        },
                    )
                },
                detailOpen = detailOpen,
                onCloseDetail = { detailOpen = false },
                // Nothing is preselected (NEW-08): stacked, the list stands alone until a row is picked.
                detailEmpty = state.selectedDebtor == null,
            )
        }
    }
}

@Composable
private fun DebtorRail(
    debtors: List<DebtorDto.ResponseDto>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        debtors.forEach { debtor ->
            DebtorRailRow(
                debtor = debtor,
                selected = debtor.id == selectedId,
                onSelect = { onSelect(debtor.id) },
            )
        }
    }
}

@Composable
private fun DebtorRailRow(
    debtor: DebtorDto.ResponseDto,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    AdminRailRow(selected = selected, onClick = onSelect) {
        Text(text = debtor.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = listOfNotNull(debtor.city, debtor.debtorNo).joinToString(" · ").orDash(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!debtor.isActive) {
                PosStatusChip(
                    text = str(AdminStringKeys.DebtorStatusInactive),
                    // tertiaryContainer, not surfaceVariant: that is the unselected row's own fill, so the
                    // chip was invisible on every inactive row but the selected one.
                    backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                    textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

/**
 * The selected customer's record — editable in place — then what we have billed them.
 *
 * The form is [DebtorEditPane], the same one the full screen shows. It used to be a read-only
 * summary with a button that navigated away, because the full screen scrolls itself and nesting that
 * inside the scaffold's scroll crashes on wasm; splitting the sections out of the screen removed
 * that constraint.
 *
 * `key(debtor.id)` matters: without it the pane keeps its `savePending` flag across a change of
 * customer, so a save for one debtor could announce itself as a save for the next.
 */
@Composable
private fun DebtorDetailPane(
    debtor: DebtorDto.ResponseDto?,
    invoices: List<SalesInvoiceDto.ListItemDto>,
    isLoadingInvoices: Boolean,
    onSaved: (savedRevision: Int) -> Unit,
) {
    if (debtor == null) {
        Text(
            text = str(AdminStringKeys.DebtorSelectPrompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    PosPaneHost(
        sections =
            listOf(
                PaneSection(id = "edit", title = str(AdminStringKeys.DebtorSectionDetails)) {
                    key(debtor.id) {
                        DebtorEditPane(debtorId = debtor.id, onSaved = onSaved)
                    }
                },
                PaneSection(id = "invoices", title = str(AdminStringKeys.DebtorInvoicesTitle)) {
                    DebtorInvoicesCard(invoices = invoices, isLoading = isLoadingInvoices)
                },
            ),
    )
}

@Composable
private fun DebtorInvoicesCard(
    invoices: List<SalesInvoiceDto.ListItemDto>,
    isLoading: Boolean,
) {
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Text(str(AdminStringKeys.DebtorInvoicesTitle), style = MaterialTheme.typography.titleMedium)
            when {
                isLoading -> AdminListSkeleton(rows = 3)
                invoices.isEmpty() ->
                    Text(
                        text = str(AdminStringKeys.DebtorInvoicesEmpty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else ->
                    PosAdaptiveTable(
                        columns = invoiceColumns(),
                        rows = invoices.map { invoiceRow(it) },
                    )
            }
        }
    }
}

@Composable
private fun invoiceColumns(): List<PosAdaptiveColumn> =
    listOf(
        PosAdaptiveColumn(
            PosTableColumn(title = str(AdminStringKeys.SalesInvoicesColumnNumber), weight = 2f, minWidth = PosTableMin.Id, nowrap = true),
            PosCardRole.Title,
        ),
        PosAdaptiveColumn(
            PosTableColumn(title = str(AdminStringKeys.SalesInvoicesColumnIssueDate), weight = 2f, minWidth = PosTableMin.Date, nowrap = true),
            PosCardRole.Detail,
        ),
        PosAdaptiveColumn(
            PosTableColumn(
                title = str(AdminStringKeys.SalesInvoicesColumnTotal),
                align = PosTableAlign.End,
                weight = 2f,
                minWidth = PosTableMin.Money,
                nowrap = true,
            ),
            PosCardRole.Trailing,
        ),
        PosAdaptiveColumn(
            PosTableColumn(title = str(AdminStringKeys.DebtorColumnStatus), weight = 2f, minWidth = PosTableMin.Pill),
            PosCardRole.Detail,
        ),
    )

@Composable
private fun invoiceRow(invoice: SalesInvoiceDto.ListItemDto): PosContentRow =
    PosContentRow(
        cells =
            listOf(
                {
                    Text(
                        text = invoice.invoiceNumber ?: str(AdminStringKeys.SalesInvoicesDraftNoNumber),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                {
                    Text(
                        invoice.issueDate.asDisplayDay(LocalAppLanguage.current),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                {
                    invoice.taxInclusiveTotal?.let {
                        PosMoneyText(
                            amount = it,
                            currencyCode = invoice.currency,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                { InvoiceStatusChip(invoice.status) },
            ),
    )

/**
 * The lifecycle status as a chip. Cancelled carries the error colours because a Gutschrift against
 * a customer is something an operator scanning this list needs to see, not read.
 */
@Composable
private fun InvoiceStatusChip(status: String) {
    val cancelled = status == "CANCELLED"
    PosStatusChip(
        text = salesInvoiceStatusLabelKey(status)?.let { str(it) } ?: status,
        backgroundColor =
            if (cancelled) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        textColor =
            if (cancelled) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}
