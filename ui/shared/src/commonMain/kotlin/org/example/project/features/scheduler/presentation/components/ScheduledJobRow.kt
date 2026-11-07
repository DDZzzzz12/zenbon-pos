package org.example.project.features.scheduler.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.scheduler.SchedulerDto
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.SchedulerKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.core.utils.formatEpochMillisShortDateTime
import org.example.project.ui.components.PosIconButton

@Composable
fun ScheduledJobRow(
    dto: SchedulerDto.ScheduledJobResponseDto,
    onRunNow: () -> Unit,
    onViewHistory: () -> Unit,
    modifier: Modifier = Modifier,
    touchTargetSize: Dp? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = maxWidth < COMPACT_CARD_WIDTH
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    JobTitleLine(dto, compact)
                    Text(
                        text = "${str(SchedulerKeys.colLastRun)}: ${formatEpochMillis(dto.lastRunAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${str(SchedulerKeys.colNextRun)}: ${formatEpochMillis(visibleNextRun(dto.enabled, dto.nextRunAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    dto.lastStatus?.let { status ->
                        Text(
                            text = "${str(SchedulerKeys.colStatus)}: ${statusLabel(status)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor(status),
                        )
                    }
                }

                PosIconButton(
                    icon = Icons.Default.PlayArrow,
                    contentDescription = str(SchedulerKeys.actionRunNow),
                    onClick = onRunNow,
                    size = touchTargetSize ?: 40.dp,
                )
                PosIconButton(
                    icon = Icons.Default.History,
                    contentDescription = str(SchedulerKeys.actionViewHistory),
                    onClick = onViewHistory,
                    size = touchTargetSize ?: 40.dp,
                )
            }
        }
    }
}

/** Job name plus its enabled/disabled label; when [compact] the label wraps under the name. */
@Composable
private fun JobTitleLine(
    dto: SchedulerDto.ScheduledJobResponseDto,
    compact: Boolean,
) {
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            JobTitle(dto.type, Modifier)
            JobStateLabel(dto.enabled)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JobTitle(dto.type, Modifier.weight(1f, fill = false))
            Spacer(modifier = Modifier.width(8.dp))
            JobStateLabel(dto.enabled)
        }
    }
}

@Composable
private fun JobTitle(
    type: ScheduledJobType,
    modifier: Modifier,
) {
    Text(
        text = jobTypeLabel(type),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Whether the job is switched on. A plain label: the last run's result is shown on the Status line. */
@Composable
private fun JobStateLabel(enabled: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = str(if (enabled) SchedulerKeys.stateActive else SchedulerKeys.stateInactive),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private val COMPACT_CARD_WIDTH = 400.dp

@Composable
private fun formatEpochMillis(epochMillis: Long?): String = epochMillis?.formatEpochMillisShortDateTime(LocalAppLanguage.current) ?: EMPTY_VALUE
