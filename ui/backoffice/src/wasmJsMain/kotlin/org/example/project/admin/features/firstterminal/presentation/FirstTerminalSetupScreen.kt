package org.example.project.admin.features.firstterminal.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.components.PairingQrAddressSection
import org.example.project.admin.features.terminal.presentation.components.PairingTseRegistrationChoice
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.displayMessage
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.settings.presentation.components.tse.TseSetupDialog
import org.example.project.features.settings.presentation.state.TseSetupTerminalOption
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField
import org.koin.compose.viewmodel.koinViewModel

/**
 * New-server first-terminal setup screen (Terminal Phase RB3 Part B). Reached post-first-login on a
 * fresh server (auto-navigated from the dashboard) or via the dashboard banner. Fully skippable — a
 * manager can add terminals later under the Terminals screen.
 */
@Composable
fun FirstTerminalSetupScreen(
    onDismiss: () -> Unit,
    viewModel: FirstTerminalSetupViewModel = koinViewModel(),
) {
    val state = viewModel.state

    // Re-read the TSE mode on every entry (the VM is app-scoped; the mode may have changed since).
    LaunchedEffect(Unit) { viewModel.loadTseMode() }

    // Drive the fiscal-only registration and code issuance as each step is reached. A failed
    // registration waits for the operator (retry / register later) instead of re-firing.
    LaunchedEffect(state.step) {
        when (state.step) {
            FirstTerminalStep.REGISTER_TSE ->
                if (!state.tseClientRegistered && !state.busy && !state.showAdminUnlockDialog && state.registerError == null) {
                    viewModel.registerTse()
                }
            FirstTerminalStep.ISSUE_CODE ->
                if (state.pairingCode == null && !state.busy) viewModel.issuePairingCode()
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The phone top bar already names the screen.
            if (!LocalWindowSizeClass.current.isCompact()) {
                Text(str(AdminStringKeys.FirstTerminalTitle), style = MaterialTheme.typography.headlineSmall)
            }

            PosErrorWatcher(
                error = state.error,
                title = str(AdminStringKeys.FirstTerminalTitle),
                onClear = viewModel::clearError,
            )

            when (state.step) {
                FirstTerminalStep.COMMISSION_TSE -> CommissionStep(viewModel, state.busy, state.tseDisabled)
                FirstTerminalStep.CREATE_TERMINAL -> {
                    // tseModeChecked gates the Create button: a re-probe in flight leaves tseDisabled
                    // holding a stale value from an earlier visit, and Step 2 offers its choices by it.
                    CreateStep(viewModel, state.terminalName, state.terminalRole, state.busy, state.tseModeChecked)
                }
                FirstTerminalStep.REGISTER_TSE -> {
                    val registerError = state.registerError
                    if (registerError != null) {
                        RegisterFailedStep(
                            error = registerError,
                            onRetry = viewModel::registerTse,
                            onDefer = viewModel::deferTseRegistration,
                        )
                    } else {
                        ProgressStep(
                            AdminStringKeys.FirstTerminalRegisterHeading,
                            AdminStringKeys.FirstTerminalRegisterProgress,
                        )
                    }
                }
                FirstTerminalStep.ISSUE_CODE -> {
                    val options = state.pairingOptions
                    if (options != null) {
                        PairingTseRegistrationChoice(draft = options, onRegisterTseClient = viewModel::onRegisterTseClient)
                        PosButton(
                            text = str(AdminStringKeys.TerminalPairingIssue),
                            onClick = viewModel::confirmIssuePairingCode,
                            enabled = !state.busy && !state.showAdminUnlockDialog,
                        )
                    } else {
                        ProgressStep(
                            AdminStringKeys.FirstTerminalIssueHeading,
                            AdminStringKeys.FirstTerminalIssueProgress,
                        )
                    }
                }
                FirstTerminalStep.DONE ->
                    DoneStep(
                        code = state.pairingCode.orEmpty(),
                        rootFingerprint = state.pairingRootFingerprint,
                        serverUrl = state.serverUrl,
                        note =
                            when {
                                state.createdTerminal?.fiscal != true -> null
                                state.tseDisabled -> AdminStringKeys.FirstTerminalTseDisabledNote
                                state.tseRegistrationDeferred -> AdminStringKeys.FirstTerminalRegisterDeferredNote
                                else -> null
                            },
                        // TSE disabled is expected/neutral (same tone as the Step 2 NoteCard); a
                        // deferred registration is an outstanding action, so it stays a warning.
                        noteIsWarning = state.tseRegistrationDeferred,
                        onFinish = onDismiss,
                    )
            }

            if (state.step != FirstTerminalStep.DONE) {
                PosSecondaryButton(text = str(AdminStringKeys.FirstTerminalSkip), onClick = onDismiss)
            }
        }
    }

    val createdTerminal = state.createdTerminal
    if (state.showTseSetupDialog && createdTerminal != null) {
        // The same dialog the TSE settings use (TimeAdmin PIN included); its terminal is fixed to the
        // one just created, which setup registers as the TSE's first client.
        val terminalOption = TseSetupTerminalOption(clientId = createdTerminal.terminalId, name = createdTerminal.name)
        TseSetupDialog(
            language = LocalAppLanguage.current,
            isSubmitting = state.busy,
            initialTimeAdminPin = "",
            errorMessage = null,
            terminals = listOf(terminalOption),
            isLoadingTerminals = false,
            selectedTerminalId = terminalOption.clientId,
            onSelectTerminal = {},
            onConfirm = { pin, puk, seed, timeAdminPin -> viewModel.commissionTse(pin, puk, seed, timeAdminPin) },
            onDismiss = viewModel::hideTseSetup,
        )
    }

    if (state.showAdminUnlockDialog) {
        AdminUnlockDialog(
            pin = state.adminUnlockPin,
            unlocking = state.unlocking,
            onPinChange = viewModel::updateAdminUnlockPin,
            onUnlock = viewModel::unlockAndRetryRegister,
            onDismiss = viewModel::hideAdminUnlockDialog,
        )
    }
}

@Composable
private fun CommissionStep(
    viewModel: FirstTerminalSetupViewModel,
    busy: Boolean,
    tseDisabled: Boolean,
) {
    // DISABLED is the fresh-install mode and setup commissions from it, so Step 2 stays; only here
    // may the operator go on without a TSE.
    if (tseDisabled) NoteCard(AdminStringKeys.FirstTerminalCommissionDisabledNote)
    PosCard(Modifier.fillMaxWidth()) {
        body {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(AdminStringKeys.FirstTerminalCommissionHeading), style = MaterialTheme.typography.titleMedium)
                Text(
                    str(AdminStringKeys.FirstTerminalCommissionIntro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PosButton(text = str(AdminStringKeys.FirstTerminalCommissionButton), onClick = viewModel::showTseSetup, enabled = !busy)
                PosSecondaryButton(text = str(AdminStringKeys.FirstTerminalTseAlreadyButton), onClick = viewModel::skipTseCommission)
                if (tseDisabled) {
                    PosSecondaryButton(
                        text = str(AdminStringKeys.FirstTerminalContinueWithoutTse),
                        onClick = viewModel::continueWithoutTse,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateStep(
    viewModel: FirstTerminalSetupViewModel,
    name: String,
    role: TerminalRole,
    busy: Boolean,
    tseModeChecked: Boolean,
) {
    PosCard(Modifier.fillMaxWidth()) {
        body {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(AdminStringKeys.FirstTerminalCreateHeading), style = MaterialTheme.typography.titleMedium)
                PosTextField(name, viewModel::updateTerminalName, str(AdminStringKeys.FirstTerminalNameLabel), enabled = !busy)
                RolePicker(role, enabled = !busy, onSelect = viewModel::updateTerminalRole)
                PosButton(
                    text = str(AdminStringKeys.FirstTerminalCreateButton),
                    onClick = viewModel::createTerminal,
                    // tseModeChecked: a re-probe in flight must not let a quick click through on a
                    // stale tseDisabled (see FirstTerminalSetupViewModel.createTerminal()).
                    enabled = !busy && tseModeChecked,
                    isLoading = busy,
                )
            }
        }
    }
}

@Composable
private fun RolePicker(
    selected: TerminalRole,
    enabled: Boolean,
    onSelect: (TerminalRole) -> Unit,
) {
    // First terminal is fiscal by default; offer the two fiscal roles.
    val options = listOf(TerminalRole.CASHIER, TerminalRole.ORDER_STATION)
    var expanded by remember { mutableStateOf(false) }
    Box {
        PosPickerField(
            value = str(roleKey(selected)),
            onClick = { if (enabled) expanded = true },
            label = str(AdminStringKeys.FirstTerminalRoleLabel),
        )
        PosDropdown(expanded, { expanded = false }) {
            options.forEach { r ->
                PosDropdownItem(text = str(roleKey(r)), selected = r == selected, onClick = {
                    onSelect(r)
                    expanded = false
                })
            }
        }
    }
}

private fun roleKey(role: TerminalRole) =
    if (role == TerminalRole.CASHIER) AdminStringKeys.FirstTerminalRoleCashier else AdminStringKeys.FirstTerminalRoleOrderStation

@Composable
private fun ProgressStep(
    heading: StringKey,
    progress: StringKey,
) {
    PosCard(Modifier.fillMaxWidth()) {
        body {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(heading), style = MaterialTheme.typography.titleMedium)
                Text(str(progress), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NoteCard(text: StringKey) {
    PosCard(Modifier.fillMaxWidth()) {
        body {
            Text(str(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RegisterFailedStep(
    error: UiError,
    onRetry: () -> Unit,
    onDefer: () -> Unit,
) {
    PosCard(Modifier.fillMaxWidth()) {
        body {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(AdminStringKeys.FirstTerminalRegisterHeading), style = MaterialTheme.typography.titleMedium)
                Text(
                    error.displayMessage(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    str(AdminStringKeys.FirstTerminalRegisterFailedHint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PosButton(text = str(CommonKeys.retry), onClick = onRetry)
                PosSecondaryButton(text = str(AdminStringKeys.FirstTerminalRegisterLaterButton), onClick = onDefer)
            }
        }
    }
}

@Composable
private fun DoneStep(
    code: String,
    rootFingerprint: String?,
    serverUrl: String,
    note: StringKey?,
    noteIsWarning: Boolean,
    onFinish: () -> Unit,
) {
    PosCard(Modifier.fillMaxWidth()) {
        body {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(str(AdminStringKeys.FirstTerminalDoneHeading), style = MaterialTheme.typography.titleMedium)
                Text(
                    str(AdminStringKeys.FirstTerminalDoneMessage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (note != null) {
                    val noteColor = if (noteIsWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(str(note), style = MaterialTheme.typography.bodyMedium, color = noteColor)
                }
                Text("${str(AdminStringKeys.FirstTerminalCodeLabel)}: $code", style = MaterialTheme.typography.headlineSmall)
                if (code.isNotBlank()) {
                    PairingQrAddressSection(
                        code = code,
                        initialServerUrl = serverUrl,
                        qrContentDescription = str(AdminStringKeys.FirstTerminalQrCaption),
                        rootFingerprint = rootFingerprint,
                    )
                    Text(
                        str(AdminStringKeys.FirstTerminalQrCaption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PosButton(text = str(AdminStringKeys.FirstTerminalFinish), onClick = onFinish)
            }
        }
    }
}

@Composable
private fun AdminUnlockDialog(
    pin: String,
    unlocking: Boolean,
    onPinChange: (String) -> Unit,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.FirstTerminalUnlockTitle),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.FirstTerminalUnlockButton),
                onClick = onUnlock,
                enabled = pin.isNotBlank(),
                isLoading = unlocking,
            ),
    ) {
        Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PosTextField(pin, onPinChange, str(AdminStringKeys.FirstTerminalUnlockPin))
        }
    }
}
