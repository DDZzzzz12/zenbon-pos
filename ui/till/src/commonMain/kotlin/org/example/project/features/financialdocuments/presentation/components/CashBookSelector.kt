package org.example.project.features.financialdocuments.presentation.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosSegmentedButtonRow

/**
 * Picks the one cash book the till's Kassenbuch list and summary cards are scoped to.
 *
 * Renders nothing for a shop with a single active book, so that screen looks exactly as it did
 * before cash books existed. Unlike adminApp's `CashBookTabs` there is no "all books" option: the
 * till shows the Kassenbestand card beside the list, and a balance is a per-book figure — a mixed
 * list next to it is what made Einnahmen/Ausgaben impossible to reconcile.
 *
 * @param books Active books in display order ([org.example.project.features.financialdocuments.presentation.state.KassenbuchState.selectableCashBooks])
 */
@Composable
fun CashBookSelector(
    books: List<CashBookDto.ResponseDto>,
    selectedBookId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (books.size < 2) return
    val selected = books.firstOrNull { it.id == selectedBookId } ?: books.first()

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = str(KassenbuchKeys.cashBookSelector),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosSegmentedButtonRow(
            options = books,
            selectedOption = selected,
            onOptionSelected = { onSelect(it.id) },
            optionLabel = { it.name },
        )
    }
}
