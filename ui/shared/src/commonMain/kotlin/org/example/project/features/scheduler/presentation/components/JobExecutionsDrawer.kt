package org.example.project.features.scheduler.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.scheduler.SchedulerDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.SchedulerKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.core.utils.formatEpochMillisShortDateTime
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
fun JobExecutionsDrawer(
    detail: SchedulerDto.ScheduledJobDetailDto,
    onRunNow: () -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
    touchTargetSize: Dp? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val job = detail.job

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: the job name gets the full width on its own line (beside the Run-now button a
            // long German name had ~200dp on a phone and broke mid-word, UI review follow-up 5).
            Text(
                text = jobTypeLabel(job.type),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // NEW-15: same value as the list row. A disabled job is never picked up by the
                    // scheduler (it selects `enabled = true` only), so its stored next run is not shown.
                    val nextRun = visibleNextRun(job.enabled, job.nextRunAt)?.let { formatEpochMillis(it) } ?: EMPTY_VALUE
                    Text(
                        text = "${str(SchedulerKeys.colNextRun)}: $nextRun",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    job.lastStatus?.let { status ->
                        Text(
                            text = "${str(SchedulerKeys.colStatus)}: ${statusLabel(status)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                PosButton(
                    text = str(SchedulerKeys.actionRunNow),
                    icon = Icons.Default.PlayArrow,
                    onClick = onRunNow,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                        ),
                )
            }

            HorizontalDivider()

            // Executions list
            if (detail.recentExecutions.isEmpty()) {
                Text(
                    text = str(SchedulerKeys.emptyState),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(detail.recentExecutions) { exec ->
                        ExecutionRow(exec, touchTargetSize)
                    }
                    item {
                        PosSecondaryButton(
                            text = str(SchedulerKeys.loadMore),
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun ExecutionRow(
    exec: SchedulerDto.JobExecutionResponseDto,
    touchTargetSize: Dp?,
) {
    var expanded by remember { mutableStateOf(false) }
    val finishedAt = exec.finishedAt
    val durationMs = if (finishedAt != null) finishedAt - exec.startedAt else null
    val durationText = durationMs?.let(::formatJobDuration) ?: EMPTY_VALUE

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatEpochMillis(exec.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${statusLabel(exec.status)} · $durationText · ${triggerLabel(exec.triggerSource)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(exec.status),
                    )
                }
                if (exec.resultMessage != null || exec.errorMessage != null) {
                    PosIconButton(
                        icon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        onClick = { expanded = !expanded },
                        size = touchTargetSize ?: 24.dp,
                        iconSize = if (touchTargetSize != null) 24.dp else 16.dp,
                    )
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                exec.resultMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                exec.errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun formatEpochMillis(epochMillis: Long): String = epochMillis.formatEpochMillisShortDateTime(LocalAppLanguage.current)
