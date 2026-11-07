package org.example.project.admin.features.terminal.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.features.terminal.presentation.components.TerminalDetailPanel

/**
 * The one wiring of [TerminalDetailPanel] to the view model, shared by the side panel, the drawer and
 * the compact detail page so all three show the same content and the same state-dependent actions.
 */
@Composable
internal fun SelectedTerminalPanel(
    data: TerminalsState.Data,
    canManage: Boolean,
    viewModel: TerminalsViewModel,
    requests: TerminalDialogRequests,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    TerminalDetailPanel(
        terminal = data.selected,
        edit = data.edit,
        dirty = data.isEditDirty,
        inactiveBrowserStation = data.selected?.let(data::isInactiveBrowserStation) == true,
        canManage = canManage,
        busy = data.busyTerminalId == data.selectedId,
        generatingCode = data.generatingCodeForId == data.selectedId,
        onGenerateCode = { data.selectedId?.let(viewModel::openPairingOptions) },
        onRegisterTse = { data.selectedId?.let(viewModel::registerTerminalTse) },
        onDeregisterTse = requests.onDeregister,
        onRevoke = requests.onRevoke,
        onDeactivate = requests.onDeactivate,
        onReactivate = { data.selected?.let { viewModel.reactivate(it.id) } },
        onBeginEdit = viewModel::beginEdit,
        onEditField = viewModel::onEditField,
        onSave = viewModel::saveEdit,
        onCancelEdit = viewModel::cancelEdit,
        modifier = modifier,
        embedded = embedded,
        onClose = onClose,
    )
}
