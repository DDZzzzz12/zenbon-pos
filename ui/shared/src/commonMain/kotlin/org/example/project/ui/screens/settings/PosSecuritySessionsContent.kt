package org.example.project.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SecuritySessionsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.viewmodel.SecuritySessionsViewModel
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDangerOutlinedButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosTextField
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.screens.settings.components.SettingsContentHeader
import org.example.project.ui.screens.settings.components.SettingsSectionLabel
import org.example.project.ui.screens.settings.components.SettingsToggleCard
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Security / Sessions settings section (admin-only).
 *
 * Renders the daily re-login policy controls (switch + HH:mm reset time),
 * a Save action, and the destructive "Log out all devices now" panic button.
 * Non-admins see a no-permission message instead of any editable control.
 *
 * @param isAdmin whether the current user may administer session policy. Hoisted to the caller
 *   rather than read from a permission provider: the till resolves it from `LocalPermissionManager`,
 *   which is SQLDelight-backed and cannot cross to wasm, while adminApp resolves it from `TokenStore`.
 *   Both only hide controls — the server enforces the rule (CLAUDE.md rule 8).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
@Composable
fun PosSecuritySessionsContent(
    language: Language,
    isCompact: Boolean,
    isAdmin: Boolean,
    viewModel: SecuritySessionsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current

    // Inert in the till (no guard provider above); adminApp's guard asks before the draft is lost.
    rememberDirtySource(dirty = isAdmin && uiState.isDirty, onDiscard = viewModel::load)

    LaunchedEffect(isAdmin) {
        if (isAdmin) viewModel.load()
    }

    // Surface save/revoke confirmations and errors via the global notification system.
    LaunchedEffect(uiState.saved) {
        if (uiState.saved) {
            overlay.showSuccess(
                title = getString(SecuritySessionsKeys.sectionTitle, language),
                message = getString(SecuritySessionsKeys.savedConfirmation, language),
                displayMode = DisplayMode.TOAST,
            )
            viewModel.clearSaved()
        }
    }
    LaunchedEffect(uiState.revoked) {
        if (uiState.revoked) {
            overlay.showSuccess(
                title = getString(SecuritySessionsKeys.sectionTitle, language),
                message = getString(SecuritySessionsKeys.revokeAllConfirmation, language),
                displayMode = DisplayMode.TOAST,
            )
            viewModel.clearRevoked()
        }
    }
    // The typed UiError goes to the shared watcher, not the string overload: only the typed path
    // attaches the error to the notification, which is what lets the host render the traceId, the
    // server's detail and the error code — and what records the failure with ClientErrorReporter.
    //
    // No displayMode override: the bus escalates 5xx and validation failures to the dialog and
    // toasts the routine rest. A 403 here (a non-admin reaching the section) is routine and
    // already handled by hiding the controls, and the revoke-all *success* is itself a toast, so
    // a routine failure toasting is symmetric with it. Nothing on this screen is fiscal.
    PosErrorWatcher(
        error = uiState.error,
        title = str(SecuritySessionsKeys.sectionTitle),
        onClear = viewModel::clearError,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        if (!isCompact) {
            SettingsContentHeader(
                icon = Icons.Default.Security,
                title = str(SecuritySessionsKeys.sectionTitle),
            )
        }

        if (!isAdmin) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = str(SecuritySessionsKeys.noPermission),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        AdminContent(
            uiState = uiState,
            onDailyResetEnabledChange = viewModel::setDailyResetEnabled,
            onDailyResetTimeChange = viewModel::setDailyResetTime,
            isTimeValid = viewModel.isTimeValid(uiState.dailyResetTime),
            onSave = viewModel::save,
            onRevokeAll = viewModel::revokeAll,
        )
    }
}

@Composable
private fun AdminContent(
    uiState: org.example.project.features.settings.presentation.viewmodel.SecuritySessionsUiState,
    onDailyResetEnabledChange: (Boolean) -> Unit,
    onDailyResetTimeChange: (String) -> Unit,
    isTimeValid: Boolean,
    onSave: () -> Unit,
    onRevokeAll: () -> Unit,
) {
    var showRevokeDialog by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PosSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Daily re-login policy
        SettingsSectionLabel(
            icon = Icons.Default.Schedule,
            title = str(SecuritySessionsKeys.sectionTitle),
        )

        SettingsToggleCard(
            title = str(SecuritySessionsKeys.dailyResetSwitch),
            description = str(SecuritySessionsKeys.dailyResetSwitchDescription),
            checked = uiState.dailyResetEnabled,
            onCheckedChange = onDailyResetEnabledChange,
            modifier = Modifier.fillMaxWidth(),
        )

        PosTextField(
            value = uiState.dailyResetTime,
            onValueChange = onDailyResetTimeChange,
            label = str(SecuritySessionsKeys.resetTimeLabel),
            enabled = uiState.dailyResetEnabled,
            isError = uiState.dailyResetEnabled && !isTimeValid,
            errorMessage =
                if (uiState.dailyResetEnabled && !isTimeValid) {
                    str(SecuritySessionsKeys.resetTimeFormatHint)
                } else {
                    null
                },
            placeholder = str(SecuritySessionsKeys.resetTimeFormatHint),
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )

        PosButton(
            text = str(CommonKeys.save),
            onClick = onSave,
            enabled = !uiState.isSaving && (!uiState.dailyResetEnabled || isTimeValid),
            isLoading = uiState.isSaving,
        )

        // Panic: revoke all sessions
        SettingsSectionLabel(
            icon = Icons.AutoMirrored.Filled.Logout,
            title = str(SecuritySessionsKeys.revokeAllButton),
        )

        PosDangerOutlinedButton(
            text = str(SecuritySessionsKeys.revokeAllButton),
            icon = Icons.AutoMirrored.Filled.Logout,
            onClick = { showRevokeDialog = true },
            enabled = !uiState.isRevoking,
            isLoading = uiState.isRevoking,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showRevokeDialog) {
        PosDialog(
            onDismiss = { showRevokeDialog = false },
            title = str(SecuritySessionsKeys.revokeAllButton),
            titleIcon = Icons.AutoMirrored.Filled.Logout,
            kind = PosDialogKind.Confirm,
            primary =
                PosDialogAction(
                    text = str(SecuritySessionsKeys.revokeAllButton),
                    onClick = {
                        showRevokeDialog = false
                        onRevokeAll()
                    },
                ),
            destructive = true,
        ) {
            Text(
                text = str(SecuritySessionsKeys.revokeAllConfirmMessage),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = str(SecuritySessionsKeys.revokeAllConfirmCurrentSession),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
