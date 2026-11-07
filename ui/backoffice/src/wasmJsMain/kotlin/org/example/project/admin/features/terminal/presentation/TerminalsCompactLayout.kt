package org.example.project.admin.features.terminal.presentation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.components.LocalAdminTopBarActions
import org.example.project.admin.core.presentation.components.RegisterTopBarAction
import org.example.project.admin.core.presentation.layout.adminListMaxWidth
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.components.TerminalChangedMarker
import org.example.project.admin.features.terminal.presentation.components.TerminalEditFields
import org.example.project.admin.features.terminal.presentation.components.TerminalStatusStrip
import org.example.project.admin.features.terminal.presentation.components.TerminalsCardList
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.guard.LocalLeaveGuard
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/** Touch target of the compact pages' buttons. */
private val COMPACT_TOUCH = 48.dp

/**
 * The compact layout (content under 600dp): the list, then the selected terminal's **detail page**
 * (back, title, edit icon), then its **edit page** (back, form, a bottom bar with Geändert / Abbrechen /
 * Speichern). One page at a time, the same local drill the screen always had on a phone; Back clears
 * the selection, which also discards an unsaved edit (guarded by the leave guard, like picking another
 * row on the wide layout). The phone's own top bar belongs to the shell, so the page headers here are
 * in-page rows.
 */
@Composable
internal fun TerminalsCompactLayout(
    data: TerminalsState.Data,
    canManage: Boolean,
    viewModel: TerminalsViewModel,
    filterPanel: PosFilterPanelState<TerminalPanelFilter>,
    requests: TerminalDialogRequests,
) {
    // Outside the page branch, so the list keeps its offset when the detail closes.
    val listScroll = rememberScrollState()
    val terminal = data.selected
    when {
        terminal != null && data.edit != null -> CompactEditPage(terminal, data.edit, data.isEditDirty, viewModel)
        terminal != null -> CompactDetailPage(terminal, data, canManage, viewModel, requests)
        else -> CompactListPage(data, canManage, viewModel, filterPanel, listScroll)
    }
}

@Composable
private fun CompactListPage(
    data: TerminalsState.Data,
    canManage: Boolean,
    viewModel: TerminalsViewModel,
    filterPanel: PosFilterPanelState<TerminalPanelFilter>,
    listScroll: ScrollState,
) {
    val leaveGuard = LocalLeaveGuard.current
    val screenPadding = LocalPosLayout.current.screenPadding
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(listScroll).padding(vertical = screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.adminListMaxWidth().fillMaxWidth()) {
            CompactListHeader(data, canManage, viewModel, Modifier.padding(horizontal = screenPadding))
            if (data.all.isEmpty()) {
                TerminalsEmptyState(canManage, viewModel::openCreate, Modifier.padding(horizontal = screenPadding))
                return@Column
            }
            // The strip counts over every terminal; "Inaktiv" is picked in the Filter panel on a phone.
            TerminalStatusStrip(
                counts = data.statusCounts,
                selected = data.filter,
                onSelect = viewModel::onFilter,
                compact = true,
                modifier = Modifier.padding(horizontal = screenPadding, vertical = PosSpacing.md),
            )
            TerminalsFilterBar(data, viewModel, filterPanel)
            if (data.visible.isEmpty()) {
                Box(Modifier.padding(horizontal = screenPadding)) { TerminalsNoMatches(data, viewModel) }
            } else {
                TerminalsCardList(
                    terminals = data.visible,
                    onSelect = { id -> leaveGuard.requestLeave { viewModel.select(id) } },
                    isInactiveBrowserStation = data::isInactiveBrowserStation,
                )
            }
        }
    }
}

/** "Terminal erstellen" at touch size; the refresh sits in the phone top bar ([RegisterTopBarAction]). */
@Composable
private fun CompactListHeader(
    data: TerminalsState.Data,
    canManage: Boolean,
    viewModel: TerminalsViewModel,
    modifier: Modifier = Modifier,
) {
    val refreshLabel = str(CommonKeys.refresh)
    RegisterTopBarAction {
        PosIconButton(Icons.Filled.Refresh, refreshLabel, viewModel::refresh, size = 48.dp, iconSize = 24.dp)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
        AdminPageTitle(str(AdminStringKeys.TerminalsTitle))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
            if (canManage) {
                PosButton(
                    text = str(AdminStringKeys.TerminalCreate),
                    icon = Icons.Default.Add,
                    onClick = viewModel::openCreate,
                    height = COMPACT_TOUCH,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    text = terminalCountLabel(data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            // The phone top bar owns the refresh (as in the design); inline only where the shell has no bar.
            if (LocalAdminTopBarActions.current == null) {
                PosIconButton(Icons.Filled.Refresh, str(CommonKeys.refresh), viewModel::refresh, size = COMPACT_TOUCH)
            }
        }
    }
}

@Composable
private fun CompactDetailPage(
    terminal: TerminalDto.TerminalSummaryDto,
    data: TerminalsState.Data,
    canManage: Boolean,
    viewModel: TerminalsViewModel,
    requests: TerminalDialogRequests,
) {
    val leaveGuard = LocalLeaveGuard.current
    // A fresh scroll position per terminal: a detail starts at its top.
    val scroll = key(terminal.id) { rememberScrollState() }
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        CompactTopRow(
            title = terminal.terminalId,
            onBack = { leaveGuard.requestLeave { viewModel.select(null) } },
            editAction = if (canManage) viewModel::beginEdit else null,
        )
        Column(Modifier.adminListMaxWidth().fillMaxWidth().padding(horizontal = LocalPosLayout.current.screenPadding)) {
            SelectedTerminalPanel(data = data, canManage = canManage, viewModel = viewModel, requests = requests, embedded = true)
        }
    }
}

@Composable
private fun CompactEditPage(
    terminal: TerminalDto.TerminalSummaryDto,
    edit: TerminalEditDraft,
    dirty: Boolean,
    viewModel: TerminalsViewModel,
) {
    val leaveGuard = LocalLeaveGuard.current
    Column(Modifier.fillMaxSize()) {
        CompactTopRow(
            title = str(AdminStringKeys.TerminalEditTitle, terminal.terminalId),
            onBack = { leaveGuard.requestLeave { viewModel.cancelEdit() } },
            editAction = null,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalPosLayout.current.screenPadding, vertical = PosSpacing.lg),
        ) {
            TerminalEditFields(edit, terminal.fiscal, viewModel::onEditField)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = LocalPosLayout.current.screenPadding, vertical = PosSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
        ) {
            if (dirty) TerminalChangedMarker(Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            PosSecondaryButton(
                text = str(AdminStringKeys.CommonCancel),
                onClick = viewModel::cancelEdit,
                enabled = !edit.saving,
                height = COMPACT_TOUCH,
            )
            PosButton(
                text = str(AdminStringKeys.CommonSave),
                onClick = viewModel::saveEdit,
                enabled = !edit.saving,
                isLoading = edit.saving,
                height = COMPACT_TOUCH,
            )
        }
    }
}

/** Back arrow, the page title and (detail page, TERMINAL_MANAGE only) the edit icon, 48dp targets. */
@Composable
private fun CompactTopRow(
    title: String,
    onBack: () -> Unit,
    editAction: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PosIconButton(Icons.AutoMirrored.Filled.ArrowBack, str(CommonKeys.back), onBack, size = COMPACT_TOUCH)
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        editAction?.let { PosIconButton(Icons.Filled.Edit, str(AdminStringKeys.CommonEdit), it, size = COMPACT_TOUCH) }
    }
}
