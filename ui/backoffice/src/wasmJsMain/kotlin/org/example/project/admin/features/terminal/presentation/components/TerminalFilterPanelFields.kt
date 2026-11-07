package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.TerminalPanelFilter
import org.example.project.admin.features.terminal.presentation.TerminalsState.TerminalFilter
import org.example.project.admin.features.terminal.presentation.TerminalsState.TerminalSort
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.filter.PosFilterDraft
import org.example.project.ui.components.filter.PosFilterPicker
import org.example.project.ui.components.filter.PosFilterSortField

/**
 * The Terminals filter panel body: the status (where [showStatus]: on a phone, or once the draft
 * changed it) and the sort key with its direction. Both edit [draft] only.
 */
@Composable
fun TerminalFilterPanelFields(
    draft: PosFilterDraft<TerminalPanelFilter>,
    showStatus: Boolean,
) {
    if (showStatus) StatusField(draft)
    SortField(draft)
}

@Composable
private fun StatusField(draft: PosFilterDraft<TerminalPanelFilter>) {
    val selected = draft.value.status
    PosFilterPicker(label = str(CommonKeys.status), value = str(terminalFilterLabel(selected))) { close ->
        TerminalFilter.entries.forEach { option ->
            PosDropdownItem(
                text = str(terminalFilterLabel(option)),
                selected = option == selected,
                minHeight = adminTouchSize(40.dp),
                onClick = {
                    close()
                    draft.value = draft.value.copy(status = option)
                },
            )
        }
    }
}

@Composable
private fun SortField(draft: PosFilterDraft<TerminalPanelFilter>) {
    PosFilterSortField(
        keys = TerminalSort.entries,
        selectedKey = draft.value.sort,
        ascending = draft.value.sortAscending,
        keyLabel = { terminalSortLabel(it) },
        // Changing the key keeps the direction.
        onChange = { sort, ascending -> draft.value = draft.value.copy(sort = sort, sortAscending = ascending) },
    )
}

/** The status chip's own label, as in the bar's quick chips and the table layout's toolbar. */
fun terminalFilterLabel(filter: TerminalFilter) =
    when (filter) {
        TerminalFilter.ALL -> AdminStringKeys.TerminalFilterAll
        TerminalFilter.ACTIVE -> AdminStringKeys.TerminalFilterActive
        TerminalFilter.INACTIVE -> AdminStringKeys.TerminalFilterInactive
        TerminalFilter.TSE_MISSING -> AdminStringKeys.TerminalFilterTseMissing
        TerminalFilter.PAIRING_OPEN -> AdminStringKeys.TerminalFilterPairingOpen
    }

/** The sort key's column header label, as in the table and the former sort chips. */
@Composable
fun terminalSortLabel(sort: TerminalSort): String =
    when (sort) {
        TerminalSort.TERMINAL_ID -> str(AdminStringKeys.TerminalColumnTerminal)
        TerminalSort.NAME -> str(AdminStringKeys.TerminalColumnName)
        TerminalSort.ROLE -> str(AdminStringKeys.TerminalColumnRole)
        TerminalSort.LAST_SEEN -> str(AdminStringKeys.TerminalColumnLastSeen)
    }
