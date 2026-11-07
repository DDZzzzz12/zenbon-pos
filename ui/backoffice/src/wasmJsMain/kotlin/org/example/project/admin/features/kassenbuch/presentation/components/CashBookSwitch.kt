package org.example.project.admin.features.kassenbuch.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.kassenbuch.presentation.cashBookOptionLabel
import org.example.project.admin.features.kassenbuch.presentation.visibleCashBooks
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.filter.PosFilterPicker
import org.example.project.ui.theme.PosSpacing

/**
 * The phone form of [CashBookTabs]: one line with a dropdown naming the current book (with its own
 * balance, as the chip does) and, when allowed, an icon button opening the book management.
 *
 * A dropdown rather than a segmented row because the number of books is the shop's data and each
 * label carries a balance ("Ladenkasse · 1.234,56 €"): two books already overflow a 390dp segmented
 * row in German and Chinese, and wrapping chips or a scrolling strip are not allowed on a phone.
 *
 * @param onManageBooks `null` hides the management action, exactly as on [CashBookTabs].
 */
@Composable
fun CashBookSwitch(
    books: List<CashBookDto.ResponseDto>,
    selectedBookId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    onManageBooks: (() -> Unit)? = null,
) {
    val visibleBooks = remember(books) { visibleCashBooks(books) }
    val allBooks = str(AdminStringKeys.KassenbuchAllBooks)
    val selected = books.firstOrNull { it.id == selectedBookId }
    val value =
        when {
            selectedBookId == null -> allBooks
            selected != null -> cashBookOptionLabel(selected)
            // A book deactivated while it was selected: still named, never shown as "Alle Kassen".
            else -> str(AdminStringKeys.KassenbuchUnknownBook, selectedBookId.toString())
        }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
        // Bottom, not centred: the picker carries a caption above its box, and the 48dp icon button
        // lines up with the (equally 48dp) box only when both sit on the row's bottom edge.
        verticalAlignment = Alignment.Bottom,
    ) {
        PosFilterPicker(label = str(AdminStringKeys.KassenbuchColBook), value = value, modifier = Modifier.weight(1f)) { close ->
            PosDropdownItem(
                text = allBooks,
                selected = selectedBookId == null,
                minHeight = adminTouchSize(40.dp),
                onClick = {
                    close()
                    onSelect(null)
                },
            )
            visibleBooks.forEach { book ->
                PosDropdownItem(
                    text = cashBookOptionLabel(book),
                    selected = book.id == selectedBookId,
                    minHeight = adminTouchSize(40.dp),
                    onClick = {
                        close()
                        onSelect(book.id)
                    },
                )
            }
        }
        if (onManageBooks != null) {
            PosIconButton(
                icon = Icons.Default.Settings,
                contentDescription = str(AdminStringKeys.KassenbuchManageBooks),
                onClick = onManageBooks,
                size = adminTouchSize(40.dp),
            )
        }
    }
}
