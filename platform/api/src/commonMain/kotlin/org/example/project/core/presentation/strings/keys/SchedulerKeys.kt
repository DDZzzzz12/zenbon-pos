package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object SchedulerKeys {
    // Screen
    val screenTitle = StringKey("scheduler.screen_title")
    val emptyState = StringKey("scheduler.empty_state")

    // Columns
    val colNextRun = StringKey("scheduler.col_next_run")
    val colLastRun = StringKey("scheduler.col_last_run")
    val colStatus = StringKey("scheduler.col_status")

    // Actions
    val actionRunNow = StringKey("scheduler.action_run_now")
    val actionViewHistory = StringKey("scheduler.action_view_history")

    // Statuses
    val statusRunning = StringKey("scheduler.status_running")
    val statusSuccess = StringKey("scheduler.status_success")
    val statusFailed = StringKey("scheduler.status_failed")
    val statusSkipped = StringKey("scheduler.status_skipped")

    // Job switch state (enabled / disabled), distinct from the last run's result
    val stateActive = StringKey("scheduler.state_active")
    val stateInactive = StringKey("scheduler.state_inactive")

    // Execution trigger
    val triggerManual = StringKey("scheduler.trigger_manual")
    val triggerScheduled = StringKey("scheduler.trigger_scheduled")

    // Job types
    val jobTypeBackup = StringKey("scheduler.job_type_backup")
    val jobTypeDatevAutoExport = StringKey("scheduler.job_type_datev_auto_export")
    val jobTypeEmailReport = StringKey("scheduler.job_type_email_report")
    val jobTypeEntryLogRetention = StringKey("scheduler.job_type_entry_log_retention")
    val jobTypeContractInstalments = StringKey("scheduler.job_type_contract_instalments")
    val jobTypeReversalSweep = StringKey("scheduler.job_type_reversal_sweep")
    val jobTypeUpdateCheck = StringKey("scheduler.job_type_update_check")

    // Validation
    val loadMore = StringKey("scheduler.load_more")
}
