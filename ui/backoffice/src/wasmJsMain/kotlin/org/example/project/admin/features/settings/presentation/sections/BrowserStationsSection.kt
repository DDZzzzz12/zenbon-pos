package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.browser.window
import org.example.project.admin.core.auth.canConfigureSystem
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Browser-Stationen — which terminal roles may be paired from, and run in, a web browser.
 *
 * Two switches (Kasse, Bestellstation) saved together. Reachable only with `CONFIG_WRITE`
 * (`SettingsSection.BROWSER_STATIONS`); the server enforces the same capability on the PUT.
 *
 * Over plain `http:` on a non-loopback host a warning is shown: the setting still works, but PINs and
 * sales then cross the network unencrypted. `isSecureContext` is deliberately not the test — a
 * LAN-IP page is exactly the case the warning is for.
 */
@Composable
fun BrowserStationsSection(viewModel: BrowserStationsViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val tokenStore = koinInject<TokenStore>()
    val canEdit = tokenStore.canConfigureSystem
    val state = viewModel.state
    val title = str(AdminStringKeys.SettingsSectionBrowserStations)
    rememberDirtySource(dirty = state.isDirty, onDiscard = viewModel::refresh)
    val unsecured = remember { isUnsecuredRemoteConnection(window.location.protocol, window.location.hostname) }

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
        Text(
            text = str(AdminStringKeys.SettingsBrowserStationsDescription),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (unsecured) {
            Text(
                text = str(AdminStringKeys.SettingsBrowserStationsInsecureHint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        BROWSER_CAPABLE_ROLES.forEach { role ->
            RoleSwitch(
                label = str(roleLabel(role)),
                checked = role.name in state.enabledRoles,
                onCheckedChange = { viewModel.setRoleEnabled(role, it) },
                enabled = canEdit && !state.isLoading && !state.isSaving,
            )
        }
        PosButton(
            text = str(CommonKeys.save),
            onClick = viewModel::save,
            enabled = canEdit && state.canSave,
        )
    }
}

private fun roleLabel(role: TerminalRole): StringKey =
    when (role) {
        TerminalRole.CASHIER -> AdminStringKeys.TerminalRoleCashier
        else -> AdminStringKeys.TerminalRoleOrderStation
    }

@Composable
private fun RoleSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                // raw Material 3 by design: no Pos* switch component exists
                Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            }
        }
    }
}
