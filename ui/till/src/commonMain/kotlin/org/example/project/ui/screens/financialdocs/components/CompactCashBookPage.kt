package org.example.project.ui.screens.financialdocs.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.FilterBar
import org.example.project.core.presentation.strings.keys.FinancialDocumentsKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.financialdocuments.presentation.components.CashBookSelector
import org.example.project.features.financialdocuments.presentation.components.CashBookSummaryCards
import org.example.project.features.financialdocuments.presentation.components.KassenbuchLogTable
import org.example.project.features.financialdocuments.presentation.state.KassenbuchState
import org.example.project.features.financialdocuments.presentation.viewmodel.KassenbuchViewModel

@Composable
fun CompactCashBookPage(
    kassenbuchState: KassenbuchState,
    kassenbuchViewModel: KassenbuchViewModel,
    language: Language,
    onCustomRangeClick: () -> Unit,
    searchTerm: String,
    onSearchTermChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowSizeClass.current

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Book selector — renders nothing for a single-book shop
            CashBookSelector(
                books = kassenbuchState.selectableCashBooks,
                selectedBookId = kassenbuchState.selectedCashBookId,
                onSelect = kassenbuchViewModel::selectCashBook,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )

            // KPI Summary Cards
            CashBookSummaryCards(
                isCompact = windowSize.isCompact(),
                kassenbuchState = kassenbuchState,
                language = language,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // FilterBar: date range + search
            FilterBar(
                selectedDateRange = kassenbuchState.selectedDateRange,
                customDateRange = kassenbuchState.customDateRange,
                onDateRangeSelected = { dateRange ->
                    kassenbuchViewModel.selectDateRange(dateRange)
                },
                onCustomRangeClick = onCustomRangeClick,
                language = language,
                searchTerm = searchTerm,
                onSearchTermChanged = onSearchTermChanged,
                searchPlaceholder = str(FinancialDocumentsKeys.searchEntriesPlaceholder),
            )

            HorizontalDivider()

            // Kassenbuch Log Table
            KassenbuchLogTable(
                entries = kassenbuchState.entries,
                language = language,
                isLoading = kassenbuchState.isLoading,
                searchTerm = searchTerm,
                onSearchTermChanged = onSearchTermChanged,
                onEntryClick = { entry ->
                    kassenbuchViewModel.fetchEntryDetail(entry.headerId)
                },
                modifier = Modifier.weight(1f),
                isCompact = true,
            )
        }

        // Geldtransit FAB — sits above the add-entry FAB so the two do not overlap.
        SmallFloatingActionButton(
            onClick = { kassenbuchViewModel.showTransferDialog() },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 80.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = str(KassenbuchKeys.transferTitle),
                modifier = Modifier.size(18.dp),
            )
        }

        // Add Entry FAB
        FloatingActionButton(
            onClick = { kassenbuchViewModel.showCreateDialog() },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
