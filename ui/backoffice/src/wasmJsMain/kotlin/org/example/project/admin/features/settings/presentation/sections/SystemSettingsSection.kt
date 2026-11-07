package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canConfigureSystem
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.setup.StartPageTarget
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * System — the API-docs switch (`/swagger`) and the server-wide default page for `/`, saved together.
 *
 * Reachable only with `CONFIG_WRITE` (`SettingsSection.SYSTEM`); the server enforces the same
 * capability on the PUT. While `POS_API_DOCS` forces the docs on or off the switch shows the
 * effective state and is disabled. `pos` is offered only when the server lists it, i.e. while a
 * browser-station role is enabled.
 */
@Composable
fun SystemSettingsSection(viewModel: SystemSettingsViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val tokenStore = koinInject<TokenStore>()
    val canEdit = tokenStore.canConfigureSystem
    val state = viewModel.state
    val title = str(AdminStringKeys.SettingsSectionSystem)
    rememberDirtySource(dirty = state.isDirty && !state.startPageUnavailable, onDiscard = viewModel::refresh)
    val controlsEnabled = canEdit && !state.isLoading && !state.isSaving
    val focus = rememberPosFormFocus<SystemSettingsField>()
    LaunchedEffect(state.validationAttempt) {
        if (state.validationAttempt > 0) focus.revealFirst(SystemSettingsField.entries, state.fieldErrors)
    }

    PosErrorWatcher(
        error = state.error,
        title = title,
        onClear = viewModel::clearError,
        retryAction = { if (state.isDirty) viewModel.save() else viewModel.refresh() },
    )
    PosSuccessWatcher(
        message = state.successMessage?.let { str(it) },
        title = title,
        onClear = viewModel::clearSuccessMessage,
    )

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LocalPosLayout.current.screenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        ApiDocsCard(
            checked = if (state.apiDocsLocked) state.apiDocsEnabled else state.apiDocsSetting,
            locked = state.apiDocsLocked,
            devModeOn = state.apiDocsOnInDevMode,
            enabled = controlsEnabled && !state.apiDocsLocked,
            onCheckedChange = viewModel::setApiDocs,
        )
        StartPageCard(
            targets = state.availableTargets,
            selected = state.startPageDefault,
            enabled = controlsEnabled,
            onSelect = viewModel::setStartPageDefault,
            modifier = with(focus) { Modifier.formAnchor(SystemSettingsField.START_PAGE) },
        )
        PosButton(
            text = str(CommonKeys.save),
            onClick = viewModel::save,
            enabled = canEdit && state.canSave,
        )
    }
}

@Composable
private fun ApiDocsCard(
    checked: Boolean,
    locked: Boolean,
    devModeOn: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(AdminStringKeys.SettingsSystemApiDocs),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    // raw Material 3 by design: no Pos* switch component exists
                    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
                }
                Text(
                    text = str(if (locked) AdminStringKeys.SettingsSystemApiDocsLocked else AdminStringKeys.SettingsSystemApiDocsHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (devModeOn) {
                    Text(
                        text = str(AdminStringKeys.SettingsSystemApiDocsDevModeOn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StartPageCard(
    targets: List<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = str(AdminStringKeys.SettingsSystemStartPage), style = MaterialTheme.typography.bodyLarge)
                targets.forEach { target ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // raw Material 3 by design: no Pos* radio component exists
                        RadioButton(selected = target == selected, onClick = { onSelect(target) }, enabled = enabled)
                        Text(text = startPageLabel(target), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (selected !in targets) {
                    Text(
                        text = str(AdminStringKeys.SettingsSystemStartPageUnavailable, startPageLabel(selected)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = str(AdminStringKeys.SettingsSystemStartPageHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A target a newer server adds has no key yet — show its wire name rather than drop the row. */
@Composable
private fun startPageLabel(target: String): String =
    when (StartPageTarget.fromWire(target)) {
        StartPageTarget.NONE -> str(AdminStringKeys.SettingsSystemStartPageNone)
        StartPageTarget.ADMIN -> str(AdminStringKeys.SettingsSystemStartPageAdmin)
        StartPageTarget.POS -> str(AdminStringKeys.SettingsSystemStartPagePos)
        null -> target
    }
