package org.example.project.admin.features.supplier.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.components.AdminListScaffold
import org.example.project.admin.core.presentation.components.ListSortPanel
import org.example.project.admin.core.presentation.components.ListSortSelection
import org.example.project.admin.core.presentation.components.ListToggleFilterBar
import org.example.project.admin.core.presentation.components.ListTogglePanel
import org.example.project.admin.core.presentation.components.ListTogglePanelFilter
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.orDash
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveTable
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosContentRow
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.PosTableSort
import org.example.project.ui.components.filter.PosFilterSearch
import org.example.project.ui.components.filter.pluralResultKey
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.koin.compose.viewmodel.koinViewModel

/** Lower bound for the card switch; the table form also needs `requiredTableWidth(columns)` (weights and minWidths), whichever is larger wins. */
private val SUPPLIER_CARD_BELOW = 760.dp

/**
 * Lieferanten — the creditor master-data list.
 *
 * Chips, search and ordering all drive the ViewModel's **server-side** request rather than narrowing
 * the loaded rows, so a provisional creditor beyond the current page is still found. That is why
 * there is no client-side filtering or sorting anywhere in this file.
 *
 * The table is [PosAdaptiveTable] (cards on a phone) rather than `PosDataTable`, whose cells are plain strings: two
 * cells here are money — which must go through [PosMoneyText], never a pre-formatted string — and
 * one is a status chip.
 */
@Composable
fun SuppliersScreen(onOpen: (Long) -> Unit) {
    val viewModel: SuppliersViewModel = koinViewModel()
    val state = viewModel.state

    // LaunchedEffect, not VM init: adminApp VMs share one ViewModelStoreOwner and survive logout,
    // so init would show the previous session's suppliers.
    LaunchedEffect(Unit) { viewModel.load() }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.SuppliersTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    // Above the scaffold, so the panel and its draft survive a resize across the phone width.
    val filterPanel = rememberPosFilterPanelState(ListTogglePanelFilter(state.provisionalOnly, state.sortSelection()), FilterPanelKeys.SUPPLIERS)
    val toggleLabel = str(AdminStringKeys.SuppliersFilterProvisional)
    val sortPanel = supplierSortPanel(state, viewModel)
    ListTogglePanel(
        toggleLabel = toggleLabel,
        applied = state.provisionalOnly,
        onToggle = viewModel::setProvisionalOnly,
        filterPanel = filterPanel,
        sort = sortPanel,
    )

    AdminListScaffold(
        title = str(AdminStringKeys.SuppliersTitle),
        toolbar = {
            ListToggleFilterBar(
                search =
                    PosFilterSearch(
                        query = state.search,
                        onQueryChange = viewModel::updateSearch,
                        placeholder = str(AdminStringKeys.SuppliersSearchPlaceholder),
                    ),
                resultText = str(pluralResultKey(state.total, AdminStringKeys.SuppliersCountOne, AdminStringKeys.SuppliersCount), state.total),
                toggleLabel = toggleLabel,
                applied = state.provisionalOnly,
                onToggle = viewModel::setProvisionalOnly,
                filterPanel = filterPanel,
                sort = sortPanel,
            )
        },
        isLoading = state.isLoading,
        isEmpty = state.items.isEmpty() && !state.isLoading,
        empty = { SuppliersEmptyState(state = state, onReset = viewModel::resetFilters) },
    ) {
        PosAdaptiveTable(
            columns = supplierColumns(),
            rows = state.items.map { supplier -> supplierRow(supplier, onOpen) },
            cardBelow = SUPPLIER_CARD_BELOW,
            sort = PosTableSort(state.sort.columnIndex, state.sortAscending),
            onSortColumn = { index -> SupplierSortColumn.fromColumnIndex(index)?.let(viewModel::sortBy) },
            // The card form's sort lives in the filter panel; the table form still sorts by its headers.
            showSortChips = false,
        )
    }
}

private fun SuppliersState.sortSelection() = ListSortSelection(key = sort.columnIndex, ascending = sortAscending)

/** All six sortable columns, in column order, labelled as their table headers. */
@Composable
private fun supplierSortPanel(
    state: SuppliersState,
    viewModel: SuppliersViewModel,
): ListSortPanel =
    ListSortPanel(
        options =
            mapOf(
                SupplierSortColumn.NAME.columnIndex to str(AdminStringKeys.SuppliersColumnName),
                SupplierSortColumn.VAT_ID.columnIndex to str(AdminStringKeys.SuppliersColumnVatId),
                SupplierSortColumn.CREDITOR_NO.columnIndex to str(AdminStringKeys.SuppliersColumnCreditorNo),
                SupplierSortColumn.OPEN_TOTAL.columnIndex to str(AdminStringKeys.SuppliersColumnOpenTotal),
                SupplierSortColumn.YTD_SPEND.columnIndex to str(AdminStringKeys.SuppliersColumnYtdSpend),
                SupplierSortColumn.STATUS.columnIndex to str(AdminStringKeys.SuppliersColumnStatus),
            ),
        applied = state.sortSelection(),
        default = SuppliersState().sortSelection(),
        onSort = { selection ->
            SupplierSortColumn.fromColumnIndex(selection.key)?.let { viewModel.setSort(it, selection.ascending) }
        },
    )

/**
 * Two different empty states, because they mean different things: an archive with no creditors in it
 * at all, and a search that matched none of the ones there are. Only the second offers a way out.
 */
@Composable
private fun SuppliersEmptyState(
    state: SuppliersState,
    onReset: () -> Unit,
) {
    val filtered = state.hasActiveFilter
    AdminListEmptyState(
        message =
            if (filtered) {
                str(AdminStringKeys.SuppliersEmptyFiltered)
            } else {
                str(AdminStringKeys.SuppliersEmpty)
            },
        icon = Icons.Default.LocalShipping,
        hint = if (filtered) str(AdminStringKeys.SuppliersEmptyHint) else null,
        actionText = if (filtered) str(AdminStringKeys.CommonResetFilters) else null,
        onAction = if (filtered) onReset else null,
    )
}

/** Column order is the contract [SupplierSortColumn.columnIndex] depends on — do not reorder it. */
@Composable
private fun supplierColumns(): List<PosAdaptiveColumn> =
    listOf(
        PosAdaptiveColumn(
            PosTableColumn(title = str(AdminStringKeys.SuppliersColumnName), weight = 2.3f, sortable = true),
            PosCardRole.Title,
        ),
        PosAdaptiveColumn(
            PosTableColumn(title = str(AdminStringKeys.SuppliersColumnVatId), weight = 2f, sortable = true, minWidth = PosTableMin.Id, nowrap = true),
        ),
        PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.SuppliersColumnCreditorNo), weight = 2f, sortable = true, nowrap = true)),
        PosAdaptiveColumn(
            PosTableColumn(
                title = str(AdminStringKeys.SuppliersColumnOpenTotal),
                align = PosTableAlign.End,
                weight = 2f,
                sortable = true,
                minWidth = PosTableMin.Money,
                nowrap = true,
            ),
        ),
        PosAdaptiveColumn(
            PosTableColumn(
                title = str(AdminStringKeys.SuppliersColumnYtdSpend),
                align = PosTableAlign.End,
                weight = 2f,
                sortable = true,
                minWidth = PosTableMin.Money,
                nowrap = true,
            ),
        ),
        PosAdaptiveColumn(
            PosTableColumn(title = str(AdminStringKeys.SuppliersColumnStatus), weight = 2f, sortable = true, minWidth = PosTableMin.Pill),
            PosCardRole.Trailing,
        ),
    )

@Composable
private fun supplierRow(
    supplier: SupplierDto.ResponseDto,
    onOpen: (Long) -> Unit,
): PosContentRow =
    PosContentRow(
        cells =
            listOf(
                { Text(supplier.name, style = MaterialTheme.typography.bodyMedium) },
                {
                    Text(
                        supplier.vatId.orDash(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                {
                    Text(
                        supplier.creditorNo.orDash(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                {
                    PosMoneyText(
                        amount = supplier.openTotal ?: "0",
                        currencyCode = supplier.currency,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                {
                    PosMoneyText(
                        amount = supplier.ytdSpend ?: "0",
                        currencyCode = supplier.currency,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                { SupplierStatusChip(isProvisional = supplier.isProvisional) },
            ),
        onClick = { onOpen(supplier.id) },
    )

/**
 * A provisional creditor is a to-do, not a state of being, so it carries the warning colours and can
 * be found by eye down the column. It used to be the plain word „Vorläufig" in body text, which read
 * as ordinary data.
 */
@Composable
private fun SupplierStatusChip(isProvisional: Boolean) {
    if (isProvisional) {
        PosStatusChip(
            text = str(AdminStringKeys.SupplierStatusProvisional),
            backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
            textColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    } else {
        PosStatusChip(
            text = str(AdminStringKeys.SupplierStatusConfirmed),
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
