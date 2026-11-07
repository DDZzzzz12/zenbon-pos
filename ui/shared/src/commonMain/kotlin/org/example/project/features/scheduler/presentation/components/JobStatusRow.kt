package org.example.project.features.scheduler.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.presentation.strings.keys.BackupKeys
import org.example.project.core.presentation.strings.keys.SchedulerKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.ui.components.PosButton
import org.example.project.ui.theme.LocalPosColors

/**
 * Row width below which a caller that passes `stackBelow = JobStatusRowPhoneWidth` gets the "Run now"
 * button under the text instead of beside it. Measured on the row itself, not the window.
 */
val JobStatusRowPhoneWidth: Dp = 360.dp

/**
 * Reusable status row for a scheduled job, showing enabled state, last/next run, last status,
 * and a "Run now" button.
 *
 * Timestamps are accepted as ISO strings — formatting prettification is deferred. The last status
 * is shown localized ("Fehlgeschlagen"), not as the raw enum name ("FAILED") it used to print.
 *
 * @param stackBelow opt-in: when non-null and the row is narrower than this, the button moves under
 *   the text at full width. `null` (the default) keeps the single-row layout exactly, which is what
 *   the till gets.
 */
@Composable
fun JobStatusRow(
    enabled: Boolean,
    lastRunAt: String?,
    nextRunAt: String?,
    lastStatus: JobStatus?,
    onRunNow: (() -> Unit)?,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    stackBelow: Dp? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (stackBelow == null) {
            JobStatusBody(false, enabled, lastRunAt, nextRunAt, lastStatus, onRunNow, running)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                JobStatusBody(maxWidth < stackBelow, enabled, lastRunAt, nextRunAt, lastStatus, onRunNow, running)
            }
        }
    }
}

@Composable
private fun JobStatusBody(
    stacked: Boolean,
    enabled: Boolean,
    lastRunAt: String?,
    nextRunAt: String?,
    lastStatus: JobStatus?,
    onRunNow: (() -> Unit)?,
    running: Boolean,
) {
    if (stacked) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(status = lastStatus)
                JobStatusDetails(enabled, lastRunAt, nextRunAt, lastStatus, Modifier.weight(1f))
            }
            onRunNow?.let { RunNowButton(it, running, Modifier.fillMaxWidth()) }
        }
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status = lastStatus)
        JobStatusDetails(enabled, lastRunAt, nextRunAt, lastStatus, Modifier.weight(1f))
        onRunNow?.let { RunNowButton(it, running, Modifier) }
    }
}

@Composable
private fun JobStatusDetails(
    enabled: Boolean,
    lastRunAt: String?,
    nextRunAt: String?,
    lastStatus: JobStatus?,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = if (enabled) str(BackupKeys.JobEnabled) else str(BackupKeys.JobDisabled),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Text(
            text = "${str(BackupKeys.LastRunAt)}: ${lastRunAt ?: EMPTY_VALUE}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${str(BackupKeys.NextRunAt)}: ${visibleNextRun(enabled, nextRunAt) ?: EMPTY_VALUE}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lastStatus?.let { status ->
            Text(
                text = "${str(BackupKeys.Status)}: ${jobStatusLabel(status)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunNowButton(
    onClick: () -> Unit,
    running: Boolean,
    modifier: Modifier,
) {
    PosButton(
        text = str(BackupKeys.RunNow),
        icon = Icons.Default.PlayArrow,
        onClick = onClick,
        modifier = modifier,
        isLoading = running,
    )
}

@Composable
private fun StatusDot(status: JobStatus?) {
    val color =
        when (status) {
            JobStatus.SUCCESS -> LocalPosColors.current.posAvailable
            JobStatus.FAILED -> MaterialTheme.colorScheme.error
            JobStatus.RUNNING, JobStatus.SKIPPED, null -> MaterialTheme.colorScheme.outline
        }
    Box(
        modifier =
            Modifier
                .size(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = color,
            modifier = Modifier.size(12.dp),
        ) {}
    }
}

@Composable
private fun jobStatusLabel(status: JobStatus): String =
    when (status) {
        JobStatus.RUNNING -> str(SchedulerKeys.statusRunning)
        JobStatus.SUCCESS -> str(SchedulerKeys.statusSuccess)
        JobStatus.FAILED -> str(SchedulerKeys.statusFailed)
        JobStatus.SKIPPED -> str(SchedulerKeys.statusSkipped)
    }
