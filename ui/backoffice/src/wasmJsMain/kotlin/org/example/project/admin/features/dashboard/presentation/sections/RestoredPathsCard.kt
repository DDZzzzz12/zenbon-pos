package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.domain.enums.setting.RestoredPathDecision
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSecondaryButton
import org.koin.compose.viewmodel.koinViewModel

/**
 * "Nach der Wiederherstellung: Speicherpfade prüfen" — shown at the top of the dashboard while a
 * restored backup's folder paths of another installation await a decision, and composed only for a
 * principal holding `BACKUP_RESTORE`. Absent in the normal case: unlike the TSE card this is not a
 * standing status but an action item, and an always-visible "all fine" line would bury it.
 *
 * @param refreshTick incremented by the screen's refresh button; the card reloads on every change.
 */
@Composable
fun RestoredPathsCard(
    refreshTick: Int,
    modifier: Modifier = Modifier,
    viewModel: RestoredPathsViewModel = koinViewModel(),
) {
    LaunchedEffect(refreshTick) { viewModel.refresh() }

    val state = viewModel.state
    val title = str(AdminStringKeys.RestoredPathsTitle)
    PosErrorWatcher(error = state.error, title = title, onClear = viewModel::clearError, retryAction = viewModel::refresh)

    if (state.justResolved) {
        Text(
            text = str(AdminStringKeys.RestoredPathsResolved),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        return
    }
    if (state.paths.isEmpty()) return

    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(text = str(AdminStringKeys.RestoredPathsIntro), style = MaterialTheme.typography.bodyMedium)
                state.paths.forEach { path ->
                    RestoredPathRow(
                        path = path,
                        enabled = state.deciding == null,
                        onDecide = { decision -> viewModel.decide(path.setting, decision) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RestoredPathRow(
    path: RestoredPathsDto.PathDto,
    enabled: Boolean,
    onDecide: (RestoredPathDecision) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = str(settingLabel(path.setting)), style = MaterialTheme.typography.labelLarge)
            Text(
                text = str(AdminStringKeys.RestoredPathsConfigured, path.configuredPath),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text =
                    if (path.defaultPath.isBlank()) {
                        str(AdminStringKeys.RestoredPathsDefaultNone)
                    } else {
                        str(AdminStringKeys.RestoredPathsDefault, path.defaultPath)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The document path offers no "keep": the restore put the files in the default folder,
            // and the server refuses KEEP for it (400).
            if (!path.keepAllowed) {
                Text(
                    text = str(AdminStringKeys.RestoredPathsDocumentsDefaultOnly),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (path.keepAllowed) {
            PosSecondaryButton(
                text = str(AdminStringKeys.RestoredPathsKeep),
                onClick = { onDecide(RestoredPathDecision.KEEP) },
                enabled = enabled,
            )
        }
        PosButton(
            text = str(AdminStringKeys.RestoredPathsUseDefault),
            onClick = { onDecide(RestoredPathDecision.USE_DEFAULT) },
            enabled = enabled,
        )
    }
}

private fun settingLabel(setting: RestoredPathSetting) =
    when (setting) {
        RestoredPathSetting.DATEV_EXPORT_PATH -> AdminStringKeys.RestoredPathsSettingDatev
        RestoredPathSetting.BACKUP_PATH -> AdminStringKeys.RestoredPathsSettingBackup
        RestoredPathSetting.BACKUP_COPY_PATH -> AdminStringKeys.RestoredPathsSettingBackupCopy
        RestoredPathSetting.DOCUMENT_STORAGE_PATH -> AdminStringKeys.RestoredPathsSettingDocuments
    }
