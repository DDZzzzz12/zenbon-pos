package org.example.project.admin.features.terminal.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.example.project.admin.core.auth.holds
import org.example.project.admin.core.presentation.components.AdminListScaffoldPadding
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.components.AdminUnlockDialog
import org.example.project.admin.features.terminal.presentation.components.CreateTerminalDialog
import org.example.project.admin.features.terminal.presentation.components.DeactivateTerminalDialog
import org.example.project.admin.features.terminal.presentation.components.DeregisterTseClientDialog
import org.example.project.admin.features.terminal.presentation.components.PairingCodeQrDialog
import org.example.project.admin.features.terminal.presentation.components.PairingOptionsDialog
import org.example.project.admin.features.terminal.presentation.components.RevokeTerminalDialog
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.example.project.ui.guard.rememberDirtySource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TerminalsScreen(
    viewModel: TerminalsViewModel = koinViewModel(),
    tokenStore: TokenStore = koinInject(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val canManage = tokenStore.holds(Capability.TERMINAL_MANAGE)

    // At screen level: the draft lives in the view model, and the panel unmounts when the window
    // crosses the phone width. The phone Back button routes through the same guard below.
    rememberDirtySource(
        dirty = (viewModel.state as? TerminalsState.Data)?.isEditDirty == true,
        onDiscard = viewModel::cancelEdit,
    )
    // Above the layout branch, so a resize across a layout breakpoint keeps an open filter panel and its draft.
    val filterPanel =
        rememberPosFilterPanelState(
            (viewModel.state as? TerminalsState.Data)?.let(TerminalPanelFilter::of) ?: TerminalPanelFilter.NONE,
            FilterPanelKeys.TERMINALS,
        )
    var pendingRevoke by remember { mutableStateOf<TerminalDto.TerminalSummaryDto?>(null) }
    var pendingDeactivate by remember { mutableStateOf<TerminalDto.TerminalSummaryDto?>(null) }
    var pendingDeregister by remember { mutableStateOf<TerminalDto.TerminalSummaryDto?>(null) }

    val topLevelError = (viewModel.state as? TerminalsState.Error)?.error
    PosErrorWatcher(
        error = topLevelError,
        title = str(AdminStringKeys.TerminalsTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::refresh,
    )

    // Per-action failures (tse-register 409, revoke, pairing code) live on the Data state and must
    // be surfaced too — without this a disabled TSE fails silently (E2E-2026-09-15 N5).
    PosErrorWatcher(
        error = (viewModel.state as? TerminalsState.Data)?.actionError,
        title = str(AdminStringKeys.TerminalsTitle),
        onClear = viewModel::clearActionError,
    )

    Box(Modifier.fillMaxSize()) {
        when (val s = viewModel.state) {
            is TerminalsState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is TerminalsState.Error -> Box(Modifier.fillMaxSize()) // error surfaced via overlay
            is TerminalsState.Data ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // The width the 16dp-padded content really gets: the measure the layout switch uses.
                    val mode = TerminalsLayoutMode.forContentWidth((maxWidth - AdminListScaffoldPadding * 2).value)
                    val requests =
                        TerminalDialogRequests(
                            onDeregister = { pendingDeregister = s.selected },
                            onRevoke = { pendingRevoke = s.selected },
                            onDeactivate = { pendingDeactivate = s.selected },
                        )
                    when (mode) {
                        TerminalsLayoutMode.COMPACT -> TerminalsCompactLayout(s, canManage, viewModel, filterPanel, requests)
                        else -> TerminalsWideLayout(mode, s, canManage, viewModel, requests)
                    }
                }
        }

        // Outside the layout branch above, so a resize keeps an open panel and its draft.
        (viewModel.state as? TerminalsState.Data)?.let { TerminalsFilterPanel(it, viewModel, filterPanel) }

        viewModel.createDraft?.let { d ->
            CreateTerminalDialog(
                draft = d,
                onField = viewModel::onCreateField,
                onSubmit = viewModel::submitCreate,
                onDismiss = viewModel::closeCreate,
            )
        }
        viewModel.pairingOptions?.let { o ->
            PairingOptionsDialog(
                draft = o,
                onRegisterTseClient = viewModel::onRegisterTseClient,
                onConfirm = viewModel::confirmPairingCode,
                onDismiss = viewModel::closePairingOptions,
            )
        }
        viewModel.pairing?.let { p ->
            PairingCodeQrDialog(code = p.code, onDismiss = viewModel::dismissPairingCode)
        }
        viewModel.unlock?.let { u ->
            AdminUnlockDialog(
                draft = u,
                onPin = viewModel::onUnlockPin,
                onSubmit = viewModel::unlockAndRetry,
                onDismiss = viewModel::closeUnlock,
            )
        }
        pendingDeactivate?.let { t ->
            DeactivateTerminalDialog(
                terminal = t,
                onConfirm = {
                    viewModel.deactivate(t.id)
                    pendingDeactivate = null
                },
                onDismiss = { pendingDeactivate = null },
            )
        }
        pendingDeregister?.let { t ->
            DeregisterTseClientDialog(
                terminal = t,
                onConfirm = {
                    viewModel.deregisterTerminalTse(t.id)
                    pendingDeregister = null
                },
                onDismiss = { pendingDeregister = null },
            )
        }
        pendingRevoke?.let { t ->
            RevokeTerminalDialog(
                terminal = t,
                onConfirm = {
                    viewModel.revokeBinding(t.id)
                    pendingRevoke = null
                },
                onDismiss = { pendingRevoke = null },
            )
        }
    }
}
