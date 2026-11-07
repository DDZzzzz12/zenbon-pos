package org.example.project.admin.features.dataexport.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminLocale
import org.example.project.admin.features.dataexport.presentation.DsfinvkExportViewModel
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.screens.settings.components.PosDsfinvkExportSection
import org.example.project.ui.screens.settings.components.SettingsSectionLabel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The DSFinV-K export on Compliance › Datenexport — the shared-ui [PosDsfinvkExportSection] the TSE
 * screen also renders, driven by [DsfinvkExportViewModel] instead of the TSE view model.
 *
 * Here so a principal holding `DSFINVK_EXPORT` without `TSE_ADMIN` (the auditor group) can reach the
 * export its route gate allows (T5-5). The caller gates it on `DSFINVK_EXPORT`.
 */
@Composable
fun DsfinvkExportPanel(
    modifier: Modifier = Modifier,
    viewModel: DsfinvkExportViewModel = koinViewModel(),
) {
    val title = str(DatevSettingsKeys.dsfinvkTitle)
    PosErrorWatcher(error = viewModel.error, title = title, onClear = viewModel::clearError)
    PosSuccessWatcher(
        message = viewModel.savedPath?.let { str(DatevSettingsKeys.exportSuccess) },
        title = title,
        onClear = viewModel::clearSaved,
    )

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val phone = maxWidth < PaneBreakpoints.PHONE_MAX
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsSectionLabel(icon = Icons.Default.Description, title = title)
            PosDsfinvkExportSection(
                language = AdminLocale.language,
                state = viewModel.state,
                onDateRangeChange = viewModel::updateDateRange,
                onToggleTseTar = viewModel::toggleIncludeTseTar,
                onValidate = viewModel::validate,
                onLoadStatistics = viewModel::loadStatistics,
                onExport = viewModel::export,
                phoneLayout = phone,
            )
        }
    }
}
