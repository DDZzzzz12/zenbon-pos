package org.example.project.admin.features.kassenbuch.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.kassenbuch.presentation.cashBookOptionLabel
import org.example.project.admin.features.kassenbuch.presentation.visibleCashBooks
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.presentation.strings.str
import org.example.project.ui.theme.PosSpacing

/**
 * The cash-book selector above the Kassenbuch table on wider windows (a phone gets [CashBookSwitch]): a leading "Alle Kassen" chip, then one chip
 * per active book carrying its own balance, then the optional book-management action.
 *
 * Balances are shown per book and never summed — the books are separate ledgers (the fiscal
 * Ladenkasse plus any Tresor/Nebenkasse), so a total across them would be a number with no
 * bookkeeping meaning.
 *
 * Deactivated books get no chip: they cannot receive new bookings, and a closed-out book with a
 * zero balance is noise in a selector. Their historical entries still resolve to a name, because
 * the *caller* keeps the full [books] list for that lookup — only this row filters.
 *
 * Laid out as a wrapping [FlowRow] rather than a `TabRow` so the trailing action can sit
 * beside the chips without pretending to be a tab, and so a shop with many books wraps onto further
 * lines instead of compressing or scrolling — the "manage books" action therefore stays visible on a
 * phone, where a scrolling strip hid it off the edge.
 *
 * Each chip formats its balance in **that book's own** [CashBookDto.ResponseDto.currency] rather
 * than one code passed down for the row. The screen used to guess the code from the first loaded
 * entry and fall back to `"EUR"`, which rendered a guessed symbol for any book with no entries in
 * the viewed period.
 *
 * @param onManageBooks `null` hides the management action — it is hidden below MANAGER, where the
 *   dialog's mutations would be refused by the server anyway.
 */
@Composable
fun CashBookTabs(
    books: List<CashBookDto.ResponseDto>,
    selectedBookId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    onManageBooks: (() -> Unit)? = null,
) {
    val visibleBooks = remember(books) { visibleCashBooks(books) }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selectedBookId == null,
            onClick = { onSelect(null) },
            label = { Text(str(AdminStringKeys.KassenbuchAllBooks)) },
        )
        visibleBooks.forEach { book ->
            FilterChip(
                selected = selectedBookId == book.id,
                onClick = { onSelect(book.id) },
                label = { Text(cashBookOptionLabel(book)) },
            )
        }
        if (onManageBooks != null) {
            TextButton(onClick = onManageBooks) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Text(str(AdminStringKeys.KassenbuchManageBooks), modifier = Modifier.padding(start = PosSpacing.sm))
            }
        }
    }
}
