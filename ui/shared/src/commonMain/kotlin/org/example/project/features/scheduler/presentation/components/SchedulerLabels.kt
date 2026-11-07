package org.example.project.features.scheduler.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.scheduler.TriggerSource
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.SchedulerKeys
import org.example.project.core.presentation.strings.str

/** Localized labels shared by the scheduled-job card and the execution-history sheet. */

internal fun jobTypeKey(type: ScheduledJobType): StringKey =
    when (type) {
        ScheduledJobType.BACKUP -> SchedulerKeys.jobTypeBackup
        ScheduledJobType.DATEV_AUTO_EXPORT -> SchedulerKeys.jobTypeDatevAutoExport
        ScheduledJobType.EMAIL_REPORT -> SchedulerKeys.jobTypeEmailReport
        ScheduledJobType.ENTRY_LOG_RETENTION -> SchedulerKeys.jobTypeEntryLogRetention
        ScheduledJobType.CONTRACT_INSTALMENTS -> SchedulerKeys.jobTypeContractInstalments
        ScheduledJobType.REVERSAL_SWEEP -> SchedulerKeys.jobTypeReversalSweep
        ScheduledJobType.UPDATE_CHECK -> SchedulerKeys.jobTypeUpdateCheck
    }

internal fun statusKey(status: JobStatus): StringKey =
    when (status) {
        JobStatus.RUNNING -> SchedulerKeys.statusRunning
        JobStatus.SUCCESS -> SchedulerKeys.statusSuccess
        JobStatus.FAILED -> SchedulerKeys.statusFailed
        JobStatus.SKIPPED -> SchedulerKeys.statusSkipped
    }

internal fun triggerKey(source: TriggerSource): StringKey =
    when (source) {
        TriggerSource.MANUAL -> SchedulerKeys.triggerManual
        TriggerSource.SCHEDULED -> SchedulerKeys.triggerScheduled
    }

@Composable
internal fun jobTypeLabel(type: ScheduledJobType): String = str(jobTypeKey(type))

@Composable
internal fun statusLabel(status: JobStatus): String = str(statusKey(status))

@Composable
internal fun triggerLabel(source: TriggerSource): String = str(triggerKey(source))

@Composable
internal fun statusColor(status: JobStatus): Color =
    when (status) {
        JobStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        JobStatus.FAILED -> MaterialTheme.colorScheme.error
        JobStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        JobStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * Run time as "< 1 s", "42 s" or "1 min 5 s". The unit symbols are locale-neutral,
 * so no translation key (and no `String.format`) is needed.
 */
internal fun formatJobDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    if (totalSeconds < 1) return "< 1 s"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes == 0L -> "$seconds s"
        seconds == 0L -> "$minutes min"
        else -> "$minutes min $seconds s"
    }
}
