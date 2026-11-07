package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.TerminalsState
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosSearchBar

private val SEARCH_WIDTH = 360.dp

/**
 * Search and the sort menu above the terminal table (large and medium layouts; the compact layout
 * uses the Zenbon filter bar, `TerminalsFilterBar`). The status filter is the strip above, not chips.
 *
 * A FlowRow so the sort line drops under the search field on a narrow window instead of squeezing it.
 */
@Composable
fun TerminalsToolbar(
    search: String,
    shown: Int,
    total: Int,
    sort: TerminalsState.TerminalSort,
    sortAscending: Boolean,
    onSearch: (String) -> Unit,
    onSort: (TerminalsState.TerminalSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        PosSearchBar(
            query = search,
            onQueryChange = onSearch,
            placeholder = str(AdminStringKeys.TerminalsSearchPlaceholder),
            modifier = Modifier.width(SEARCH_WIDTH),
            onClear = { onSearch("") },
            clearContentDescription = str(AdminStringKeys.CommonCancel),
        )
        TerminalSortMenu(shown, total, sort, sortAscending, onSort)
    }
}
