/**
 * Print Job Side Panel component.
 *
 * Displays print job history with date filtering functionality.
 * Used in tablet/desktop layout as a persistent side panel, and on phone
 * as a modal dialog.
 *
 * Features:
 * - Date range filtering (Today, Yesterday, Last 7 days, Last 30 days, Custom)
 * - Optional filter by status (SUCCESS/FAILED/PENDING), shown by its localized label
 * - Optional filter by print type (BILL/ORDER/ZBON/...), shown by its localized label
 * - Real-time list of print jobs
 * - Empty state handling
 * - Loading state
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.screens.settings.components
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PrinterSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.components.common.DateFilterSelector
import org.example.project.features.settings.presentation.components.common.DateRangeOption
import org.example.project.features.settings.presentation.components.common.getDateRangeLabel
import org.example.project.features.settings.presentation.components.printer.PrintJobCard
import org.example.project.features.settings.presentation.components.printer.printJobStatusKey
import org.example.project.features.settings.presentation.components.printer.printTypeKey
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.theme.LocalPosColors

/**
 * Print job side panel with date filtering.
 *
 * Receives data and callbacks from ViewModel via PrinterSettingsContent.
 *
 * @param language Current app language
 * @param printJobs List of print jobs to display
 * @param isLoading Whether data is currently loading
 * @param statusFilter Current status filter value
 * @param printTypeFilter Current print type filter value
 * @param selectedDateRange Current date range selection
 * @param onStatusFilterChange Callback when status filter changes
 * @param onPrintTypeFilterChange Callback when print type filter changes
 * @param onDateRangeChange Callback when date range changes
 * @param onRefresh Callback to refresh data
 * @param locationId Active printer-location filter (null = all locations)
 * @param locationName Display name of the filtered location
 * @param onClearLocation When set, a chip is shown that names the filtered location (or "All") and clears it; null on the phone sheet, whose header already names the location
 * @param modifier Optional modifier
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrintJobSidePanel(
    language: Language,
    printJobs: List<PrinterDto.PrintJobHistoryDto>,
    isLoading: Boolean,
    statusFilter: PrinterDto.PrintJobStatus?,
    printTypeFilter: PrinterDto.PrintType?,
    selectedDateRange: DateRangeOption,
    onStatusFilterChange: (PrinterDto.PrintJobStatus?) -> Unit,
    onPrintTypeFilterChange: (PrinterDto.PrintType?) -> Unit,
    onDateRangeChange: (DateRangeOption) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    phoneLayout: Boolean = false,
    locationId: Long? = null,
    locationName: String? = null,
    onClearLocation: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosAutoSizeText(
                text = str(PrinterSettingsKeys.printJobHistoryTitle),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            PosIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = str(CommonKeys.refresh),
                onClick = onRefresh,
                size = if (phoneLayout) 48.dp else 40.dp,
                iconSize = 20.dp,
            )
        }

        // Date filter selector
        DateFilterSelector(
            selectedRange = selectedDateRange,
            onRangeSelected = { option, _ ->
                onDateRangeChange(option)
            },
            language = language,
        )

        // Additional filters: wraps instead of squeezing the last chip to "Dr…" when a location
        // chip is active (NEW-16).
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Location filter (wide layout): "All" when unfiltered, the location name when filtered
            if (onClearLocation != null) {
                FilterChip(
                    selected = locationId != null,
                    onClick = { if (locationId != null) onClearLocation() },
                    label = {
                        PosAutoSizeText(
                            text = locationName ?: str(CommonKeys.all),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    leadingIcon =
                        if (locationId != null) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            null
                        },
                )
            }

            // Status filter - cycle through values on click
            FilterChip(
                selected = statusFilter != null,
                onClick = {
                    val nextStatus = cycleStatusFilter(statusFilter)
                    onStatusFilterChange(nextStatus)
                },
                label = {
                    PosAutoSizeText(
                        text = statusFilter?.let { str(printJobStatusKey(it)) } ?: str(PrinterSettingsKeys.status),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                leadingIcon =
                    if (statusFilter != null) {
                        {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
            )

            // Print type filter - cycle through values on click
            FilterChip(
                selected = printTypeFilter != null,
                onClick = {
                    val nextType = cyclePrintTypeFilter(printTypeFilter)
                    onPrintTypeFilterChange(nextType)
                },
                label = {
                    PosAutoSizeText(
                        text = printTypeFilter?.let { str(printTypeKey(it)) } ?: str(PrinterSettingsKeys.printType),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                leadingIcon =
                    if (printTypeFilter != null) {
                        {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
            )
        }

        HorizontalDivider()

        // Print jobs list
        if (isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (printJobs.isEmpty()) {
            // Empty state
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Text(
                        text = str(PrinterSettingsKeys.noHistory),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text =
                            str(
                                emptyHistoryDescriptionKey(statusFilter, printTypeFilter, locationId),
                                getDateRangeLabel(selectedDateRange, language),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            // Print jobs list
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(printJobs, key = { it.id }) { printJob ->
                    PrintJobCard(
                        printJob = printJob,
                        language = language,
                        phoneLayout = phoneLayout,
                    )
                }
            }
        }

        // Footer summary
        if (printJobs.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Success count
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint =
                                    LocalPosColors.current.posAvailable,
                            )
                            Text(
                                text = printJobs.count { it.status == PrinterDto.PrintJobStatus.SUCCESS }.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }

                        // Failed count
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = printJobs.count { it.status == PrinterDto.PrintJobStatus.FAILED }.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }

                    Text(
                        text = str(PrinterSettingsKeys.printJobsTotal, printJobs.size.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Cycles through PrintJobStatus values: null -> SUCCESS -> FAILED -> PENDING -> null
 */
private fun cycleStatusFilter(current: PrinterDto.PrintJobStatus?): PrinterDto.PrintJobStatus? =
    when (current) {
        null -> PrinterDto.PrintJobStatus.SUCCESS
        PrinterDto.PrintJobStatus.SUCCESS -> PrinterDto.PrintJobStatus.FAILED
        PrinterDto.PrintJobStatus.FAILED -> PrinterDto.PrintJobStatus.PENDING
        PrinterDto.PrintJobStatus.PENDING -> null
    }

/**
 * Cycles through PrintType values: null -> BILL -> ORDER -> ZBON -> null
 */
private fun cyclePrintTypeFilter(current: PrinterDto.PrintType?): PrinterDto.PrintType? =
    when (current) {
        null -> PrinterDto.PrintType.BILL
        PrinterDto.PrintType.BILL -> PrinterDto.PrintType.ORDER
        PrinterDto.PrintType.ORDER -> PrinterDto.PrintType.ZBON
        PrinterDto.PrintType.ZBON -> PrinterDto.PrintType.TEST_PRINT
        PrinterDto.PrintType.TEST_PRINT -> PrinterDto.PrintType.REFERENCE_PRINT
        PrinterDto.PrintType.REFERENCE_PRINT -> null
    }
