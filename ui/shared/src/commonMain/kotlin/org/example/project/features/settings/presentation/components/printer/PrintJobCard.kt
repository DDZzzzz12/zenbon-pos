/**
 * Print Job Card component.
 *
 * Displays a single print job from the history with:
 * - Print type (BILL, ORDER, ZBON)
 * - Document ID
 * - Status (SUCCESS, FAILED, PENDING) with visual indicator
 * - Printer location name
 * - Timestamp
 * - Employee who printed
 * - Error message (if failed)
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.components.printer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.PrinterSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardStatus
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.PosSpacing

/**
 * Print job card component.
 *
 * @param printJob Print job history data
 * @param language Current app language
 * @param modifier Optional modifier
 * @param onClick Optional callback when card is clicked
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrintJobCard(
    printJob: PrinterDto.PrintJobHistoryDto,
    language: Language,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    phoneLayout: Boolean = false,
) {
    val cardStatus =
        when (printJob.status) {
            PrinterDto.PrintJobStatus.SUCCESS -> PosCardStatus.Completed
            PrinterDto.PrintJobStatus.FAILED -> PosCardStatus.Error
            PrinterDto.PrintJobStatus.PENDING -> PosCardStatus.Pending
        }

    PosCard(
        modifier = modifier,
        onClick = onClick,
        status = cardStatus,
        variant = PosCardVariant.Tonal,
    ) {
        body {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Status indicator icon
                StatusIndicator(status = printJob.status)

                // Content column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
                ) {
                    // Print type + Document ID
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrintTypeBadge(printType = printJob.printType, language = language)
                        Text(
                            text = printJob.documentId,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Printer location (if available)
                    printJob.printerLocationName?.let { locationName ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PosAutoSizeText(
                                text = locationName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Timestamp + Employee
                    if (phoneLayout) {
                        // Wraps to a second line instead of starving the second autosizing text.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            PrintJobTimestamp(printJob, language)
                            PrintJobEmployee(printJob)
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PrintJobTimestamp(printJob, language)
                            PrintJobEmployee(printJob)
                        }
                    }

                    // Error message (if failed)
                    if (printJob.status == PrinterDto.PrintJobStatus.FAILED) {
                        printJob.errorMessage?.let { errorMsg ->
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Text(
                                        text = errorMsg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
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
 * Status indicator icon with color coding.
 */
@Composable
private fun StatusIndicator(status: PrinterDto.PrintJobStatus) {
    val (icon, color) =
        when (status) {
            PrinterDto.PrintJobStatus.SUCCESS -> Icons.Default.CheckCircle to LocalPosColors.current.posAvailable
            PrinterDto.PrintJobStatus.FAILED -> Icons.Default.Error to MaterialTheme.colorScheme.error
            PrinterDto.PrintJobStatus.PENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.tertiary
        }

    Icon(
        imageVector = icon,
        contentDescription = str(printJobStatusKey(status)),
        tint = color,
        modifier = Modifier.size(24.dp),
    )
}

/**
 * Print type badge.
 */
@Composable
private fun PrintTypeBadge(
    printType: PrinterDto.PrintType,
    language: Language,
) {
    val label = str(printTypeKey(printType))
    val color =
        when (printType) {
            PrinterDto.PrintType.BILL -> MaterialTheme.colorScheme.primaryContainer
            PrinterDto.PrintType.ORDER -> MaterialTheme.colorScheme.tertiaryContainer
            PrinterDto.PrintType.ZBON -> MaterialTheme.colorScheme.secondaryContainer
            PrinterDto.PrintType.TEST_PRINT -> MaterialTheme.colorScheme.surfaceVariant
            PrinterDto.PrintType.REFERENCE_PRINT -> MaterialTheme.colorScheme.surfaceContainerHighest
        }

    Surface(
        color = color,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color =
                when (printType) {
                    PrinterDto.PrintType.BILL -> MaterialTheme.colorScheme.onPrimaryContainer
                    PrinterDto.PrintType.ORDER -> MaterialTheme.colorScheme.onTertiaryContainer
                    PrinterDto.PrintType.ZBON -> MaterialTheme.colorScheme.onSecondaryContainer
                    PrinterDto.PrintType.TEST_PRINT -> MaterialTheme.colorScheme.onSurfaceVariant
                    PrinterDto.PrintType.REFERENCE_PRINT -> MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

/**
 * Format LocalDateTime to display string.
 *
 * `internal`, not private, so `PrintJobTimestampFormatTest` can call it. Worth the widened
 * visibility: this function crashed the whole print-history screen once (issue #3). It read
 * `String.format("%02d", dateTime.month)` — `month` is a `kotlinx.datetime.Month`, not an `Int`, so
 * the JVM formatter threw `IllegalFormatConversionException` while composing the first card, and the
 * screen died before a row was ever visible. Nothing exercised it but the running app.
 */
internal fun formatDateTime(
    dateTime: LocalDateTime,
    language: Language,
): String {
    // Format as: "16.12.2025 14:30" (German) or "12/16/2025 2:30 PM" (English/Chinese)
    val monthNumber = dateTime.month.number
    return when (language) {
        // padStart rather than String.format: the latter is a JVM-only stdlib API and does not
        // exist on wasm. Matches the idiom shared/core/utils/FormatUtils.kt already uses for dates.
        Language.GERMAN -> {
            val d = dateTime.day.toString().padStart(2, '0')
            val mo = monthNumber.toString().padStart(2, '0')
            val h = dateTime.hour.toString().padStart(2, '0')
            val mi = dateTime.minute.toString().padStart(2, '0')
            "$d.$mo.${dateTime.year} $h:$mi"
        }
        Language.ENGLISH, Language.CHINESE -> {
            val hour12 =
                if (dateTime.hour == 0) {
                    12
                } else if (dateTime.hour > 12) {
                    dateTime.hour - 12
                } else {
                    dateTime.hour
                }
            val amPm = if (dateTime.hour < 12) "AM" else "PM"
            val mo = monthNumber.toString().padStart(2, '0')
            val d = dateTime.day.toString().padStart(2, '0')
            val mi = dateTime.minute.toString().padStart(2, '0')
            // hour12 is deliberately unpadded — the original format string used %d, not %02d.
            "$mo/$d/${dateTime.year} $hour12:$mi $amPm"
        }
    }
}

@Composable
private fun PrintJobTimestamp(
    printJob: PrinterDto.PrintJobHistoryDto,
    language: Language,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosAutoSizeText(
            text = formatDateTime(printJob.printedAt, language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrintJobEmployee(printJob: PrinterDto.PrintJobHistoryDto) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosAutoSizeText(
            text = printJob.printedBy.takeUnless { it.isBlank() || it.equals("Unknown", ignoreCase = true) } ?: str(PrinterSettingsKeys.printedByUnknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
