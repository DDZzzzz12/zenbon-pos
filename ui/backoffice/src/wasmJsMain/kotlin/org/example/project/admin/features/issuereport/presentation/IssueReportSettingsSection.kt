package org.example.project.admin.features.issuereport.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.project.admin.core.util.formatAdminInstant
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.IssueReportKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.PosTextField
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.viewmodel.koinViewModel

/**
 * Settings → Problem reports: where "Report a problem" files its issues, and the token it uses.
 *
 * Reachable only with `ISSUE_REPORT_MANAGE` (`SettingsSection.ISSUE_REPORTS`); the ViewModel and
 * the server both refuse every action without it as well.
 *
 * The token is write-only: this section can store one and remove one, and shows only the masked
 * last characters the API returns, so an operator can tell *which* token is stored.
 *
 * Alternatively the Administrator can explicitly choose to reuse the update token (a switch saved
 * with the target). It is offered only while the server reports that token stored and readable, and
 * while it is on the own-token input is disabled with a note on scope and host. A save refused for
 * the update token's host rule is explained inline above the save button (no toast, which would cut
 * the sentence off).
 */
@Composable
fun IssueReportSettingsSection(viewModel: IssueReportSettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    // The token draft is not touched by load(), so Discard clears it first, then reloads the target.
    rememberDirtySource(
        dirty = state.hasUnsavedEdits,
        onDiscard = {
            viewModel.onTokenDraft("")
            viewModel.load()
        },
    )

    val title = str(IssueReportKeys.settingsSectionTitle)
    // Retry repeats the failed action (a failed save retries the save), and is absent when it cannot.
    PosErrorWatcher(
        error = state.error,
        title = title,
        onClear = viewModel::clearError,
        retryAction = if (state.retryable) viewModel::retry else null,
    )
    PosSuccessWatcher(message = state.successMessage?.let { str(it) }, title = title, onClear = viewModel::clearSuccessMessage)

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LocalPosLayout.current.screenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            text = str(IssueReportKeys.settingsDescription),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TargetCard(state, viewModel)
        TokenCard(state, viewModel)
    }
}

@Composable
private fun TargetCard(
    state: IssueReportSettingsState,
    viewModel: IssueReportSettingsViewModel,
) {
    val editable = state.canManage && !state.isBusy && state.settings != null
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PosTextField(
                    value = state.owner,
                    onValueChange = viewModel::onOwner,
                    label = str(IssueReportKeys.ownerLabel),
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                )
                PosTextField(
                    value = state.repo,
                    onValueChange = viewModel::onRepo,
                    label = str(IssueReportKeys.repoLabel),
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                )
                PosTextField(
                    value = state.apiBaseUrl,
                    onValueChange = viewModel::onApiBaseUrl,
                    label = str(IssueReportKeys.apiBaseUrlLabel),
                    enabled = editable,
                    keyboardType = KeyboardType.Uri,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // raw Material 3 by design: no Pos* switch component exists
                    Switch(checked = state.isPublic, onCheckedChange = viewModel::onPublic, enabled = editable)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(str(IssueReportKeys.publicToggleLabel), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            str(IssueReportKeys.publicToggleHint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                UseUpdateTokenRow(state, viewModel)
                state.settingsSaveError?.let { SaveRefusalNote(it) }
                PosButton(
                    text = str(IssueReportKeys.saveSettings),
                    onClick = viewModel::saveSettings,
                    enabled = state.canSaveSettings,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenCard(
    state: IssueReportSettingsState,
    viewModel: IssueReportSettingsViewModel,
) {
    val settings = state.settings
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(IssueReportKeys.tokenTitle), style = MaterialTheme.typography.titleSmall)
                Text(
                    str(IssueReportKeys.tokenHelp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(tokenStateText(settings), style = MaterialTheme.typography.bodyMedium)
                settings?.tokenSetAt?.let {
                    Text(
                        str(IssueReportKeys.tokenSetAt, formatAdminInstant(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.tokenKeyMissing) {
                    Text(
                        str(IssueReportKeys.tokenKeyMissing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.useUpdateToken) {
                    Text(
                        str(IssueReportKeys.useUpdateTokenActiveNote),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                PosTextField(
                    value = state.tokenDraft,
                    onValueChange = viewModel::onTokenDraft,
                    label = str(IssueReportKeys.tokenLabel),
                    enabled = state.ownTokenEditable,
                    keyboardType = KeyboardType.Password,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosButton(
                        text = str(IssueReportKeys.saveToken),
                        onClick = viewModel::saveToken,
                        enabled = state.canSaveToken,
                    )
                    PosSecondaryButton(
                        text = str(IssueReportKeys.clearToken),
                        onClick = viewModel::clearToken,
                        enabled = state.canClearToken,
                    )
                }
            }
        }
    }
}

/** A refused save, in full and wrapped, right above the save button it refers to. */
@Composable
private fun SaveRefusalNote(error: UiError) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
    ) {
        Text(
            str(error.messageKey, *error.messageArgs.toTypedArray()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun UseUpdateTokenRow(
    state: IssueReportSettingsState,
    viewModel: IssueReportSettingsViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // raw Material 3 by design: no Pos* switch component exists
        Switch(checked = state.useUpdateToken, onCheckedChange = viewModel::onUseUpdateToken, enabled = state.canToggleUpdateToken)
        Column(modifier = Modifier.weight(1f)) {
            Text(str(IssueReportKeys.useUpdateTokenLabel), style = MaterialTheme.typography.bodyMedium)
            Text(
                str(IssueReportKeys.useUpdateTokenHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            updateTokenUnavailableReason(state.settings?.updateTokenState)?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Why the switch cannot be turned on; null while the update token is usable. */
@Composable
private fun updateTokenUnavailableReason(updateTokenState: IssueReportDto.TokenState?): String? =
    when (updateTokenState) {
        IssueReportDto.TokenState.NONE -> str(IssueReportKeys.useUpdateTokenUnavailableNone)
        IssueReportDto.TokenState.UNREADABLE -> str(IssueReportKeys.useUpdateTokenUnavailableUnreadable)
        else -> null
    }

@Composable
private fun tokenStateText(settings: IssueReportDto.SettingsDto?): String =
    when (settings?.tokenState) {
        IssueReportDto.TokenState.SET -> str(IssueReportKeys.tokenStateSet, settings.tokenPreview.orEmpty())
        IssueReportDto.TokenState.UNREADABLE -> str(IssueReportKeys.tokenStateUnreadable)
        else -> str(IssueReportKeys.tokenStateNone)
    }
