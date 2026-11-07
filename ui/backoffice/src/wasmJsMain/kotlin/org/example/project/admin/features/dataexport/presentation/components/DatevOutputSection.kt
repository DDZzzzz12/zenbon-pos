package org.example.project.admin.features.dataexport.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.format.asDisplayDay
import org.example.project.admin.features.dataexport.presentation.DatevOutputViewModel
import org.example.project.admin.features.reports.presentation.components.exportTypeKey
import org.example.project.core.domain.dto.datev.UnassignedSummary
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDateTime
import org.example.project.features.settings.presentation.components.datev.datevCellLabel
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveTable
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosContentRow
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.PosTextButton
import org.koin.compose.viewmodel.koinViewModel

/** The log has seven columns; below this width they cannot share a row legibly, so rows become cards. */
private val EXPORT_LOG_CARD_BELOW: Dp = 720.dp

/**
 * The DATEV hand-over: five ad-hoc exports, and the log of what has already been produced with a
 * re-download for each row. Sits under the DATEV configuration on the same page, so the settings
 * that shape an export and the button that runs one are in one place.
 *
 * The export log is a `PosAdaptiveTable`: `PosDataTable` renders string cells only and each row
 * carries a download button.
 */
@Composable
fun DatevOutputSection(
    modifier: Modifier = Modifier,
    viewModel: DatevOutputViewModel = koinViewModel(),
) {
    val state = viewModel.state
    val exportsDebtors = holds(Capability.DEBTOR_DATEV_EXPORT)
    LaunchedEffect(Unit) { viewModel.load() }

    PosErrorWatcher(
        error = state.error,
        title = str(MonthlyCloseKeys.sectionDatevExport),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load() },
    )

    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(MonthlyCloseKeys.sectionDatevExport),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PosSecondaryButton(
                    text = str(MonthlyCloseKeys.toolbarExportKassenbuch),
                    onClick = { viewModel.startExport("KASSENBUCH") },
                )
                PosSecondaryButton(
                    text = str(MonthlyCloseKeys.toolbarExportApLedger),
                    onClick = { viewModel.startExport("AP_LEDGER") },
                )
                PosSecondaryButton(
                    text = str(MonthlyCloseKeys.exportBuchungsstapelButton),
                    onClick = { viewModel.startExport("BUCHUNGSSTAPEL") },
                )
                PosSecondaryButton(
                    text = str(MonthlyCloseKeys.toolbarExportBuchungsstapelBelege),
                    onClick = { viewModel.startExport("BUCHUNGSSTAPEL_MIT_BELEGEN") },
                )
                PosSecondaryButton(
                    text = str(MonthlyCloseKeys.toolbarExportBoth),
                    onClick = { viewModel.startExport("ALL") },
                )
                // Ausgangsrechnungen (DATEV Debitoren) — runs a preflight first, then downloads.
                // Its routes are DEBTOR_DATEV_EXPORT, not the ACCOUNTING_READ the others ride on.
                if (exportsDebtors) {
                    PosSecondaryButton(
                        text = str(SettingsKeys.datevArExportTitle),
                        onClick = { viewModel.startExport("AR") },
                    )
                }
            }

            if (exportsDebtors) {
                Text(
                    text = str(SettingsKeys.datevArExportDescription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.exportWarnings.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = str(SettingsKeys.datevExportWarningsTitle),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        // The notices are the server's own sentences (the export log carries the same
                        // text); the heading above is what is localized.
                        state.exportWarnings.forEach { warning ->
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            PosTextButton(
                                text = str(CommonKeys.close),
                                onClick = { viewModel.dismissExportWarnings() },
                            )
                        }
                    }
                }
            }

            if (state.unassignedContra.isNotEmpty()) {
                UnassignedContraNotice(
                    notices = state.unassignedContra,
                    onDismiss = { viewModel.dismissUnassignedContra() },
                )
            }

            state.arBlockedInvoiceNumbers?.let { blocked ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = str(SettingsKeys.datevArExportBlocked, blocked.joinToString(", ")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            PosTextButton(
                                text = str(CommonKeys.close),
                                onClick = { viewModel.dismissArBlocked() },
                            )
                        }
                    }
                }
            }

            if (state.isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (state.log.isEmpty()) {
                Text(
                    text = str(MonthlyCloseKeys.exportLogEmpty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            } else {
                ExportLogTable(
                    log = state.log,
                    onDownload = { entry -> viewModel.downloadFromLog(entry.id, entry.fileName) },
                )
            }
        }
    }

    state.adHocDialog?.let { dialog ->
        AdHocExportDialog(
            state = dialog,
            onStartChange = viewModel::updateAdHocStart,
            onEndChange = viewModel::updateAdHocEnd,
            onExcludeKassenbuchChange = viewModel::updateAdHocExcludeKassenbuch,
            onConfirm = viewModel::confirmAdHocExport,
            onDismiss = viewModel::dismissAdHocDialog,
        )
    }
}

/**
 * Lines an export wrote without a contra account because their mapping cell's „Konto automatisch
 * zuordnen" switch is off: how many, which bookings, and the DATEV import step it requires.
 */
@Composable
private fun UnassignedContraNotice(
    notices: List<Pair<String, UnassignedSummary>>,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            notices.forEach { (type, summary) ->
                val typeLabel = exportTypeKey(type)?.let { str(it) } ?: type
                Text(
                    text = "$typeLabel: ${str(SettingsKeys.datevExportUnassignedTitle, summary.lineCount)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                val bookings = summary.bookings.map { "${datevCellLabel(it.cell)} (${it.lineCount})" }
                Text(
                    text = str(SettingsKeys.datevExportUnassignedBody, bookings.joinToString(", ")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PosTextButton(text = str(CommonKeys.close), onClick = onDismiss)
            }
        }
    }
}

/**
 * The export log as a table when the card is wide, and as one card per export when it is not: type
 * as the title, the download button on the trailing edge, every other column a `label: value` line.
 * Nothing is dropped and the download stays on every row.
 */
@Composable
private fun ExportLogTable(
    log: List<DatevExportLogDto>,
    onDownload: (DatevExportLogDto) -> Unit,
) {
    val language = LocalAppLanguage.current
    val style = MaterialTheme.typography.bodySmall
    val columns =
        listOf(
            PosAdaptiveColumn(PosTableColumn(str(MonthlyCloseKeys.colExportType), weight = 1.2f), PosCardRole.Title),
            PosAdaptiveColumn(PosTableColumn(str(MonthlyCloseKeys.colPeriod), weight = 1.55f, minWidth = 128.dp, nowrap = true)),
            PosAdaptiveColumn(PosTableColumn(str(MonthlyCloseKeys.colExportedAt), weight = 1.55f, minWidth = PosTableMin.DateTime, nowrap = true)),
            PosAdaptiveColumn(PosTableColumn(str(MonthlyCloseKeys.colSource), weight = 1.2f)),
            PosAdaptiveColumn(PosTableColumn(str(MonthlyCloseKeys.colRowCount), align = PosTableAlign.End, weight = 1.2f)),
            PosAdaptiveColumn(PosTableColumn(str(MonthlyCloseKeys.colFileHash), align = PosTableAlign.End, weight = 1.55f, minWidth = 128.dp, nowrap = true)),
            PosAdaptiveColumn(PosTableColumn("", align = PosTableAlign.End, weight = 1.2f), PosCardRole.Trailing),
        )
    val downloadLabel = str(MonthlyCloseKeys.downloadButton)
    val sourceAuto = str(MonthlyCloseKeys.sourceAuto)
    val sourceManual = str(MonthlyCloseKeys.sourceManual)
    val rows =
        log.map { entry ->
            val sourceLabel = if (entry.source.equals("AUTO", ignoreCase = true)) sourceAuto else sourceManual
            val typeLabel = exportTypeKey(entry.exportType)?.let { str(it) } ?: entry.exportType
            val hashIsLong = entry.fileHash.length > HASH_PREVIEW_LENGTH
            PosContentRow(
                cells =
                    listOf(
                        { Text(typeLabel, style = style, fontWeight = FontWeight.Medium) },
                        {
                            Text(
                                "${entry.periodStart.asDisplayDay(language)} – ${entry.periodEnd.asDisplayDay(language)}",
                                style = style,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        {
                            Text(
                                entry.exportedAt.formatShortDateTime(language),
                                style = style,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        { Text(sourceLabel, style = style) },
                        { Text(entry.rowCount.toString(), style = style) },
                        {
                            Text(
                                if (hashIsLong) {
                                    entry.fileHash.take(HASH_PREVIEW_LENGTH) + "…"
                                } else {
                                    entry.fileHash
                                },
                                style = style,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        {
                            PosIconButton(
                                icon = Icons.Filled.Download,
                                contentDescription = downloadLabel,
                                onClick = { onDownload(entry) },
                                size = 48.dp,
                            )
                        },
                    ),
            )
        }
    PosAdaptiveTable(columns = columns, rows = rows, cardBelow = EXPORT_LOG_CARD_BELOW)
}

/** How many leading characters of the file hash the history table shows. */
private const val HASH_PREVIEW_LENGTH = 8
