package org.example.project.admin.features.terminal.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.components.TerminalDrawer
import org.example.project.admin.features.terminal.presentation.components.TerminalStatusStrip
import org.example.project.admin.features.terminal.presentation.components.TerminalsTable
import org.example.project.admin.features.terminal.presentation.components.TerminalsToolbar
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.guard.LocalLeaveGuard

/** Width of the side panel on the large layout. */
private val SIDE_PANEL_WIDTH = 360.dp

/**
 * The large and medium layouts: header, status strip, search + sort, then the table. Large puts the
 * [SIDE_PANEL_WIDTH] detail panel beside it (read and edit in the same panel); medium keeps the table
 * alone and opens the selected terminal in a drawer with a scrim. Closing, or picking another row,
 * goes through the leave guard because it drops an unsaved edit.
 */
@Composable
internal fun TerminalsWideLayout(
    mode: TerminalsLayoutMode,
    data: TerminalsState.Data,
    canManage: Boolean,
    viewModel: TerminalsViewModel,
    requests: TerminalDialogRequests,
) {
    val leaveGuard = LocalLeaveGuard.current
    val closePanel = { leaveGuard.requestLeave { viewModel.select(null) } }
    Box(Modifier.fillMaxSize()) {
        // Large: the detail panel is a full-height column beside everything else (header and status strip
        // included), as in the design; medium has the table column alone and a drawer for the detail.
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                TerminalsWideHeader(data, canManage, viewModel::openCreate)
                if (data.all.isEmpty()) {
                    TerminalsEmptyState(canManage, viewModel::openCreate, Modifier.fillMaxSize())
                    return@Column
                }
                TerminalStatusStrip(
                    counts = data.statusCounts,
                    selected = data.filter,
                    onSelect = viewModel::onFilter,
                    compact = false,
                    modifier = Modifier.padding(top = 20.dp),
                )
                TerminalsToolbar(
                    search = data.search,
                    shown = data.visible.size,
                    total = data.total,
                    sort = data.sort,
                    sortAscending = data.sortAscending,
                    onSearch = viewModel::onSearch,
                    onSort = viewModel::onSort,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                    if (data.visible.isEmpty()) {
                        TerminalsNoMatches(data, viewModel)
                    } else {
                        TerminalsTable(
                            terminals = data.visible,
                            selectedId = data.selectedId,
                            sort = data.sort,
                            sortAscending = data.sortAscending,
                            onSort = viewModel::onSort,
                            onSelect = { id -> leaveGuard.requestLeave { viewModel.select(id) } },
                            isInactiveBrowserStation = data::isInactiveBrowserStation,
                            wide = mode == TerminalsLayoutMode.LARGE,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            if (mode == TerminalsLayoutMode.LARGE && data.all.isNotEmpty()) {
                VerticalDivider()
                // Fixed width, not a weight: the panel holds a form whose fields should not stretch
                // with the window, and the table is what should absorb the remaining space.
                SelectedTerminalPanel(
                    data = data,
                    canManage = canManage,
                    viewModel = viewModel,
                    requests = requests,
                    modifier = Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight(),
                    onClose = closePanel.takeIf { data.selected != null },
                )
            }
        }
        if (mode == TerminalsLayoutMode.MEDIUM && data.selected != null) {
            TerminalDrawer(onDismiss = closePanel) {
                SelectedTerminalPanel(data = data, canManage = canManage, viewModel = viewModel, requests = requests, onClose = closePanel)
            }
        }
    }
}

/** Title with the total/active counts under it, and the primary "Terminal erstellen" (TERMINAL_MANAGE only). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalsWideHeader(
    data: TerminalsState.Data,
    canManage: Boolean,
    onCreate: () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AdminPageTitle(str(AdminStringKeys.TerminalsTitle))
            Text(
                text = terminalCountLabel(data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canManage) {
            PosButton(text = str(AdminStringKeys.TerminalCreate), icon = Icons.Default.Add, onClick = onCreate, height = adminTouchSize(40.dp))
        }
    }
}

@Composable
internal fun terminalCountLabel(data: TerminalsState.Data): String =
    "${str(AdminStringKeys.TerminalsTotal, data.total.toString())}  ·  ${str(AdminStringKeys.TerminalsActive, data.activeCount.toString())}"
