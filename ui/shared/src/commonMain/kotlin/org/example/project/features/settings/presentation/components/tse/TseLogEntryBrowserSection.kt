/**
 * TSE Log Entry Browser section component.
 *
 * Provides a collapsible section for browsing TSE log entries
 * with navigation (First/Last/Go to ID) and expandable entry cards
 * showing parsed TR-03153 fields and raw hex view.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.tseLogEntryTypeLabel
import org.example.project.core.utils.isCompact
import org.example.project.features.settings.presentation.components.common.SettingsSectionHeader
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosHorizontalScroller
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import kotlin.time.Instant

/**
 * Collapsible section for browsing TSE log entries.
 *
 * @param language Current language for localization
 * @param logEntries Current browse result with entries
 * @param isLoading Whether entries are being loaded
 * @param isExpanded Whether section is expanded
 * @param isPerforming Whether an operation is in progress
 * @param onToggleExpand Callback to toggle section expansion
 * @param onBrowseFirst Callback to load first entries
 * @param onBrowseLast Callback to load last entries
 * @param onBrowseById Callback to load entries starting from a specific ID
 * @param windowSize Current window size class
 */
@Composable
fun TseLogEntryBrowserSection(
    language: Language,
    logEntries: TseDto.LogEntryBrowseResultDto?,
    isLoading: Boolean,
    isExpanded: Boolean,
    isPerforming: Boolean,
    onToggleExpand: () -> Unit,
    onBrowseFirst: () -> Unit,
    onBrowseLast: () -> Unit,
    onBrowseById: (Long) -> Unit,
    onExportForAnalysis: () -> Unit,
    windowSize: WindowSizeClass,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
        onClick = onToggleExpand,
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Section header with expand/collapse toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        SettingsSectionHeader(
                            title = str(TseSettingsKeys.logEntryBrowserSection),
                            icon = Icons.AutoMirrored.Filled.Article,
                        )
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Expandable content
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HorizontalDivider()

                        // Navigation buttons
                        LogEntryNavigationBar(
                            language = language,
                            isLoading = isLoading,
                            isPerforming = isPerforming,
                            onBrowseFirst = onBrowseFirst,
                            onBrowseLast = onBrowseLast,
                            onBrowseById = onBrowseById,
                            windowSize = windowSize,
                        )

                        // Loading indicator
                        if (isLoading) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }

                        // Entry list
                        if (!isLoading && logEntries != null) {
                            if (logEntries.entries.isEmpty()) {
                                Text(
                                    text = str(TseSettingsKeys.noLogEntries),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                // Entry count info
                                logEntries.totalCount?.let { total ->
                                    Text(
                                        text = "${str(TseSettingsKeys.totalEntries)}: $total",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                // Export the raw entries so the exact ASN.1 DER bytes can be
                                // shared for offline analysis (e.g. to validate the parser against
                                // a real TSE 2.0 capture), alongside the parsed fields below.
                                PosSecondaryButton(
                                    text = str(TseSettingsKeys.exportLogForAnalysis),
                                    onClick = onExportForAnalysis,
                                    icon = Icons.Default.Download,
                                    enabled = !isPerforming,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                // Entry cards
                                logEntries.entries.forEach { entry ->
                                    LogEntryCard(
                                        language = language,
                                        entry = entry,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Navigation bar with First/Last/Go to ID buttons.
 */
@Composable
private fun LogEntryNavigationBar(
    language: Language,
    isLoading: Boolean,
    isPerforming: Boolean,
    onBrowseFirst: () -> Unit,
    onBrowseLast: () -> Unit,
    onBrowseById: (Long) -> Unit,
    windowSize: WindowSizeClass,
) {
    var goToIdText by remember { mutableStateOf("") }
    val enabled = !isLoading && !isPerforming
    val isCompact = windowSize.isCompact()

    if (isCompact) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PosSecondaryButton(
                    text = str(TseSettingsKeys.firstEntries),
                    onClick = onBrowseFirst,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                PosSecondaryButton(
                    text = str(TseSettingsKeys.lastEntries),
                    onClick = onBrowseLast,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PosTextField(
                    value = goToIdText,
                    onValueChange = { goToIdText = it.filter { c -> c.isDigit() } },
                    label = str(TseSettingsKeys.entryId),
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Search,
                    modifier = Modifier.weight(1f),
                )
                PosIconButton(
                    icon = Icons.Default.Search,
                    contentDescription = str(TseSettingsKeys.goToId),
                    onClick = {
                        goToIdText.toLongOrNull()?.let { onBrowseById(it) }
                    },
                    enabled = enabled && goToIdText.toLongOrNull() != null,
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosSecondaryButton(
                text = str(TseSettingsKeys.firstEntries),
                onClick = onBrowseFirst,
                enabled = enabled,
            )
            PosSecondaryButton(
                text = str(TseSettingsKeys.lastEntries),
                onClick = onBrowseLast,
                enabled = enabled,
            )
            PosTextField(
                value = goToIdText,
                onValueChange = { goToIdText = it.filter { c -> c.isDigit() } },
                label = str(TseSettingsKeys.entryId),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Search,
                modifier = Modifier.weight(1f),
            )
            PosIconButton(
                icon = Icons.Default.Search,
                contentDescription = str(TseSettingsKeys.goToId),
                onClick = {
                    goToIdText.toLongOrNull()?.let { onBrowseById(it) }
                },
                enabled = enabled && goToIdText.toLongOrNull() != null,
            )
        }
    }
}

/**
 * Expandable card for a single log entry.
 *
 * Shows collapsed summary (ID, Type, Timestamp) and expands to show
 * parsed TR-03153 fields and raw hex view toggle.
 */
@Composable
private fun LogEntryCard(
    language: Language,
    entry: TseDto.LogEntryDto,
) {
    var isExpanded by remember { mutableStateOf(false) }
    var showHex by remember { mutableStateOf(false) }

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
        onClick = { isExpanded = !isExpanded },
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Collapsed summary row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "#${entry.id}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = tseLogEntryTypeLabel(entry.type, language),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        entry.parsed?.processType?.let { pt ->
                            PosAutoSizeText(
                                text = pt,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Expanded details
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))

                        // Parsed TR-03153 fields
                        entry.parsed?.let { parsed ->
                            parsed.operationType?.let {
                                EntryDetailRow(str(TseSettingsKeys.operationType), it)
                            }
                            parsed.eventOrigin?.let {
                                EntryDetailRow(str(TseSettingsKeys.eventOrigin), it)
                            }
                            parsed.eventTriggeredByUser?.let {
                                EntryDetailRow(str(TseSettingsKeys.eventTriggeredByUser), it)
                            }
                            parsed.authRole?.let {
                                EntryDetailRow(str(TseSettingsKeys.authRole), it)
                            }
                            parsed.authenticationResult?.let {
                                EntryDetailRow(str(TseSettingsKeys.authenticationResult), it)
                            }
                            parsed.remainingRetries?.let {
                                EntryDetailRow(str(TseSettingsKeys.remainingRetries), it.toString())
                            }
                            parsed.clientId?.let {
                                EntryDetailRow(str(TseSettingsKeys.clientId), it)
                            }
                            parsed.processType?.let {
                                EntryDetailRow(str(TseSettingsKeys.processType), it)
                            }
                            parsed.transactionNumber?.let {
                                EntryDetailRow(str(TseSettingsKeys.transactionNumber), "#$it")
                            }
                            parsed.signatureCounter?.let {
                                EntryDetailRow(str(TseSettingsKeys.signatureCounter), it.toString())
                            }
                            parsed.logTime?.let {
                                EntryDetailRow(str(TseSettingsKeys.logTime), formatLogTime(it))
                            }
                            parsed.signatureAlgorithm?.let {
                                EntryDetailRow(str(TseSettingsKeys.signatureAlgorithm), it)
                            }
                            parsed.serialNumberHex?.let {
                                EntryDetailRow(str(TseSettingsKeys.serialNumber), it.take(16) + if (it.length > 16) "..." else "")
                            }
                        }

                        // Process data text (if available)
                        entry.processDataText?.let { text ->
                            if (text.isNotBlank()) {
                                EntryDetailRow(str(TseSettingsKeys.processData), text)
                            }
                        }

                        // Hex view toggle
                        Spacer(Modifier.height(4.dp))
                        PosTextButton(
                            text = str(TseSettingsKeys.hexView),
                            onClick = { showHex = !showHex },
                        )

                        AnimatedVisibility(
                            visible = showHex,
                            enter = expandVertically(),
                            exit = shrinkVertically(),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Log message hex
                                Text(
                                    text = "${str(TseSettingsKeys.logMessage)} (${entry.logMessageSize} bytes):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                PosHorizontalScroller(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = entry.logMessageHex.chunked(64).joinToString("\n"),
                                        style =
                                            MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                    )
                                }

                                // Process data hex
                                if (entry.processDataHex.isNotEmpty()) {
                                    Text(
                                        text = "${str(TseSettingsKeys.processData)} (${entry.processDataSize} bytes):",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    PosHorizontalScroller(Modifier.fillMaxWidth()) {
                                        Text(
                                            text = entry.processDataHex.chunked(64).joinToString("\n"),
                                            style =
                                                MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                        )
                                    }
                                }

                                // Event data hex (v3 / TSE 2.0 system logs only)
                                entry.parsed?.eventDataHex?.let { eventHex ->
                                    Text(
                                        text = "${str(TseSettingsKeys.eventData)}:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    PosHorizontalScroller(Modifier.fillMaxWidth()) {
                                        Text(
                                            text = eventHex.chunked(64).joinToString("\n"),
                                            style =
                                                MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Label-value row for entry detail display.
 */
@Composable
private fun EntryDetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Format Unix timestamp to readable date/time string.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
private fun formatLogTime(unixSeconds: Long): String =
    try {
        val instant = Instant.fromEpochSeconds(unixSeconds)
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        "${localDateTime.date} ${localDateTime.hour.toString().padStart(
            2,
            '0',
        )}:${localDateTime.minute.toString().padStart(2, '0')}:${localDateTime.second.toString().padStart(2, '0')}"
    } catch (e: Exception) {
        unixSeconds.toString()
    }
