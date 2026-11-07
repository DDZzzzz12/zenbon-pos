package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.layout.adminPanelMaxWidth
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.viewmodel.DatevSettingsViewModel
import org.example.project.ui.components.PosButton
import org.example.project.ui.screens.settings.components.PosKassenmeldungSection
import org.koin.compose.viewmodel.koinViewModel

/**
 * Einstellungen → Kassenmeldung.
 *
 * `PosKassenmeldungSection` is purely presentational — `(state, onExportPdf)`, no ViewModel — so
 * this wrapper owns the wiring. It mirrors how the till calls it from `PosSystemContent`; the rest
 * of that screen (terminal management, the desktop remote-server section) is deliberately not
 * reused here, since adminApp already owns terminals as its own destination.
 *
 * The destination owns the scroll: the card is a plain Column, and without a scroll host a long
 * report ran past the fold with no way to reach the export button.
 *
 * UI-FG-16: a page headline with the primary "PDF exportieren" beside it (the only action, so it is
 * the primary one), and the report capped at the panel width so a label and its value are not 630dp
 * apart at 1400.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KassenmeldungSection(viewModel: DatevSettingsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val report = state.kassenMeldungState
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.adminPanelMaxWidth().fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                AdminPageTitle(str(AdminStringKeys.SettingsSectionKassenmeldung))
                if (!report.isLoading && report.data != null) {
                    PosButton(
                        text = str(DatevSettingsKeys.kassenmeldungExportPdf),
                        icon = Icons.Default.PictureAsPdf,
                        onClick = { viewModel.exportKassenmeldungPdf() },
                    )
                }
            }
            PosKassenmeldungSection(state = report)
        }
    }
}
