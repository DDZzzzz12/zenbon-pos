package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.TerminalsState.TerminalSort
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosTextButton

/**
 * The result line of the wide layouts ("9 von 9 · sortiert nach Terminal ↑") as a menu: it opens the
 * four sort keys (Terminal, Name, Rolle, Zuletzt gesehen), so the Name sort the merged table column no
 * longer carries stays reachable. Choosing the active key again flips the direction, which is what
 * `TerminalsViewModel.onSort` does.
 */
@Composable
fun TerminalSortMenu(
    shown: Int,
    total: Int,
    sort: TerminalSort,
    sortAscending: Boolean,
    onSort: (TerminalSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val arrow = if (sortAscending) "↑" else "↓"
    Box(modifier) {
        PosTextButton(
            text = "${str(AdminStringKeys.TerminalsResultSorted, shown.toString(), total.toString(), terminalSortLabel(sort))} $arrow",
            onClick = { expanded = true },
            height = adminTouchSize(40.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
            TerminalSort.entries.forEach { key ->
                PosDropdownItem(
                    text = terminalSortLabel(key) + if (key == sort) " $arrow" else "",
                    selected = key == sort,
                    onClick = {
                        expanded = false
                        onSort(key)
                    },
                    minHeight = adminTouchSize(40.dp),
                )
            }
        }
    }
}
