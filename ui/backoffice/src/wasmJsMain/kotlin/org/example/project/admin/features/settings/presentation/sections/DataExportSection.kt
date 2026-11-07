package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canBrowseServerDirectories
import org.example.project.admin.core.presentation.strings.AdminLocale
import org.example.project.admin.features.dataexport.presentation.components.DatevOutputSection
import org.example.project.admin.features.dataexport.presentation.components.DsfinvkExportPanel
import org.example.project.core.auth.AccessPolicy
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.auth.CapabilityGate
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.layout.PaneSection
import org.example.project.ui.screens.settings.PANE_DATEV_OUTPUT
import org.example.project.ui.screens.settings.PANE_DSFINVK
import org.example.project.ui.screens.settings.PosExportContent
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.koinInject

/**
 * Einstellungen → Datenexport.
 *
 * Supplies the two things the shared content cannot resolve on wasm: the current language, and
 * whether the signed-in user may manage backups.
 *
 * The backup block is gated on [canBrowseServerDirectories] (`BACKUP_CREATE`), the capability
 * `BackupRules` puts on every backup read — not on `SESSION_REVOKE` as before, which was a different
 * capability that merely happened to share holders. The server enforces the same rule (CLAUDE.md
 * rule 8), and the ViewModel no longer issues the backup calls without it (QA admin19 NOTE-h).
 *
 * `isCompact = false` because the settings destination always renders full-width here.
 *
 * [DatevOutputSection] sits below the configuration it is shaped by: the account mapping and export
 * path above it decide what a DATEV file contains, and the buttons below it produce one. It is
 * gated on `ACCOUNTING_READ`, what `AccountingRules` puts on the export log and the ad-hoc export.
 *
 * **Only a principal who can configure DATEV (`CONFIG_WRITE`) gets the configuration.** The
 * destination's sidebar entry is gated on `DSFINVK_EXPORT` (T5-5), which the auditor group holds;
 * rendering [PosExportContent] for them started a ViewModel that loads the DATEV configuration,
 * the backup settings and the scheduled-job status on its own — calls answered with 403 and shown
 * as error dialogs, beside a form they may not save. Everyone else gets [ExportOnlyContent]: the
 * export panels their capabilities reach, and nothing that loads configuration.
 *
 * [DsfinvkExportPanel] follows it, gated on `DSFINVK_EXPORT` — the capability this destination's
 * sidebar entry names and `DsfinvkRules` puts on every `/api/v1/dsfinvk` route. The export's only
 * other home is the TSE screen, behind `TSE_ADMIN`, so before T5-5 an auditor was shown a Datenexport
 * item that did not contain the one export it was gated for.
 *
 * The two output panels are passed *into* [PosExportContent] as pane sections: it owns the destination's
 * only `verticalScroll` and fills the viewport, so a sibling below it would be laid out past the bottom
 * edge of a parent that does not scroll.
 */
@Composable
fun DataExportSection() {
    if (!showsDatevConfiguration(LocalAccessPolicy.current)) {
        ExportOnlyContent()
        return
    }
    val tokenStore = koinInject<TokenStore>()
    PosExportContent(
        language = AdminLocale.language,
        isCompact = false,
        canManageBackups = tokenStore.canBrowseServerDirectories,
        outputSections = exportOutputSections(),
    )
}

/**
 * The gated output panels as pane sections. Each keeps its [CapabilityGate] (rule 8: the UI guard is
 * not replaced by the pane filter); the filter only decides whether a chip exists.
 */
@Composable
private fun exportOutputSections(): List<PaneSection> =
    exportOutputPanes(LocalAccessPolicy.current).map { pane ->
        when (pane) {
            ExportOutputPane.DATEV_OUTPUT ->
                PaneSection(PANE_DATEV_OUTPUT, str(MonthlyCloseKeys.sectionDatevExport)) {
                    CapabilityGate(Capability.ACCOUNTING_READ) { DatevOutputSection(modifier = Modifier.fillMaxWidth()) }
                }
            ExportOutputPane.DSFINVK ->
                PaneSection(PANE_DSFINVK, str(DatevSettingsKeys.dsfinvkTitle)) {
                    CapabilityGate(Capability.DSFINVK_EXPORT) { DsfinvkExportPanel() }
                }
        }
    }

/** Which output panes this principal gets: `ACCOUNTING_READ` -> DATEV output, `DSFINVK_EXPORT` -> DSFinV-K. */
internal fun exportOutputPanes(policy: AccessPolicy): List<ExportOutputPane> =
    buildList {
        if (policy.allows(Capability.ACCOUNTING_READ)) add(ExportOutputPane.DATEV_OUTPUT)
        if (policy.allows(Capability.DSFINVK_EXPORT)) add(ExportOutputPane.DSFINVK)
    }

/** The export panels alone, for a principal who may export but not configure DATEV. */
@Composable
private fun ExportOnlyContent() {
    // The only verticalScroll on this path — PosExportContent, which owns it on the other, is not
    // composed here.
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(LocalPosLayout.current.screenPadding),
    ) {
        ExportPanels()
    }
}

@Composable
private fun ExportPanels() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CapabilityGate(Capability.ACCOUNTING_READ) { DatevOutputSection(modifier = Modifier.fillMaxWidth()) }
        CapabilityGate(Capability.DSFINVK_EXPORT) { DsfinvkExportPanel() }
    }
}

/**
 * Whether Datenexport shows the DATEV configuration — `CONFIG_WRITE`, what `ConfigRules` puts on
 * saving the DATEV XML settings and the account mapping. Not the page's own `DSFINVK_EXPORT` gate: a
 * principal can be allowed to export without being allowed to configure (T5-5).
 */
internal fun showsDatevConfiguration(policy: AccessPolicy): Boolean = policy.allows(Capability.CONFIG_WRITE)
