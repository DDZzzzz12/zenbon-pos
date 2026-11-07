package org.example.project.ui.screens.financialdocs.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosSecondaryButton

@Composable
fun ExpandedCashBookColumn(
    kassenbuchState: KassenbuchState,
    kassenbuchViewModel: KassenbuchViewModel,
    language: Language,
    onCustomRangeClick: () -> Unit,
    searchTerm: String,
    onSearchTermChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowSizeClass.current

    PosCard(
        modifier = modifier,
        variant = PosCardVariant.Elevated,
    ) {
        body {
            Column(modifier = Modifier.fillMaxSize()) {
                // Section header
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = str(FinancialDocumentsKeys.cashBookLog),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
//                    Text(
//                        text = "${str(KassenbuchKeys.currentBalance)}: ${kassenbuchState.currentBalance} \u20AC",
//                        style = MaterialTheme.typography.bodySmall,
//                        color = MaterialTheme.colorScheme.onSurfaceVariant
//                    )
                    }

                    // Add new entry button
                    PosButton(
                        text = str(FinancialDocumentsKeys.addNewEntry),
                        onClick = { kassenbuchViewModel.showCreateDialog() },
                    )
                    PosSecondaryButton(
                        text = str(KassenbuchKeys.transferTitle),
                        onClick = { kassenbuchViewModel.showTransferDialog() },
                    )
                }

                // Book selector — renders nothing for a single-book shop
                CashBookSelector(
                    books = kassenbuchState.selectableCashBooks,
                    selectedBookId = kassenbuchState.selectedCashBookId,
                    onSelect = kassenbuchViewModel::selectCashBook,
                    modifier = Modifier.padding(horizontal = 16.dp),
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
                )

                HorizontalDivider()

                // KPI Summary Cards
                CashBookSummaryCards(
                    isCompact = windowSize.isCompact(),
                    kassenbuchState = kassenbuchState,
                    language = language,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        } // body
    }
}
