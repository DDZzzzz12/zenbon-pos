package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/**
 * String keys for the auto-backup section in the Backup settings UI and the
 * shared [JobStatusRow] composable.
 */
object BackupKeys {
    val AutoBackupSection = StringKey("backup.auto_section")
    val AutoEnabled = StringKey("backup.auto_enabled")
    val Cadence = StringKey("backup.cadence")
    val CadenceDaily = StringKey("backup.cadence_daily")
    val CadenceWeekly = StringKey("backup.cadence_weekly")
    val Retention = StringKey("backup.retention")
    val RunHour = StringKey("backup.run_hour")
    val MaxUploadSize = StringKey("backup.max_upload_size")
    val RunNow = StringKey("backup.run_now")
    val LastRunAt = StringKey("backup.last_run_at")
    val NextRunAt = StringKey("backup.next_run_at")
    val Status = StringKey("backup.status")
    val JobEnabled = StringKey("backup.job_enabled")
    val JobDisabled = StringKey("backup.job_disabled")
    val CopyTo = StringKey("backup.copy_to")
    val CopyToHint = StringKey("backup.copy_to_hint")
    val CopyRetention = StringKey("backup.copy_retention")
    val CopyPathInvalid = StringKey("backup.copy_path_invalid")
    val CopyLast = StringKey("backup.copy_last")
    val CopyNever = StringKey("backup.copy_never")
    val CopyStatusOk = StringKey("backup.copy_status_ok")
    val CopyStatusFailed = StringKey("backup.copy_status_failed")
    val CopyStatusNotMounted = StringKey("backup.copy_status_not_mounted")
    val CopyStatusNoTarget = StringKey("backup.copy_status_no_target")
    val CopyWarning = StringKey("backup.copy_warning")
}
