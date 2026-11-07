package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/**
 * The software-update screen.
 *
 * The `state*` block is the heart of it. An update screen that says "something went wrong" is
 * useless to whoever is standing at the till at 03:00, so every distinguishable condition the API
 * can report gets its own sentence saying what happened and what to do about it — most sharply
 * [stateAuthFailedTitle], because a private repository answers 404 to an unauthorised caller and an
 * expired token would otherwise read exactly like "no releases".
 */
object UpdateKeys {
    // Screen
    val screenTitle = StringKey("update.screen_title")
    val screenSubtitle = StringKey("update.screen_subtitle")

    // Version block
    val currentVersion = StringKey("update.current_version")
    val availableVersion = StringKey("update.available_version")
    val releaseNotes = StringKey("update.release_notes")
    val lastChecked = StringKey("update.last_checked")
    val neverChecked = StringKey("update.never_checked")

    // Source block
    val sourceTitle = StringKey("update.source_title")
    val channel = StringKey("update.channel")
    val channelPublic = StringKey("update.channel_public")
    val channelPrivate = StringKey("update.channel_private")
    val channelPublicHint = StringKey("update.channel_public_hint")
    val channelPrivateHint = StringKey("update.channel_private_hint")
    val repository = StringKey("update.repository")

    // Token block — write-only by construction; the API returns a mask, never the value
    val tokenTitle = StringKey("update.token_title")
    val tokenHint = StringKey("update.token_hint")
    val tokenLabel = StringKey("update.token_label")
    val tokenStateNone = StringKey("update.token_state_none")
    val tokenStateSet = StringKey("update.token_state_set")
    val tokenStateUnreadable = StringKey("update.token_state_unreadable")
    val tokenSetAt = StringKey("update.token_set_at")
    val actionSaveToken = StringKey("update.action_save_token")
    val actionClearToken = StringKey("update.action_clear_token")

    // Auto-apply — off by default, and that has to be obvious
    val autoApplyLabel = StringKey("update.auto_apply_label")
    val autoApplyOnHint = StringKey("update.auto_apply_on_hint")
    val autoApplyOffHint = StringKey("update.auto_apply_off_hint")

    // Actions
    val actionCheck = StringKey("update.action_check")
    val actionStage = StringKey("update.action_stage")
    val actionInstall = StringKey("update.action_install")
    val manageDenied = StringKey("update.manage_denied")
    val applyStarted = StringKey("update.apply_started")

    // Download progress while a release is being staged
    val downloadPreparing = StringKey("update.download_preparing")
    val downloadingServer = StringKey("update.downloading_server")
    val downloadingServerNoTotal = StringKey("update.downloading_server_no_total")
    val downloadingAndroid = StringKey("update.downloading_android")
    val downloadingAndroidNoTotal = StringKey("update.downloading_android_no_total")

    // The situations
    val stateUpToDateTitle = StringKey("update.state_up_to_date_title")
    val stateUpToDateDetail = StringKey("update.state_up_to_date_detail")
    val stateNeverCheckedTitle = StringKey("update.state_never_checked_title")
    val stateNeverCheckedDetail = StringKey("update.state_never_checked_detail")
    val stateAvailableTitle = StringKey("update.state_available_title")
    val stateAvailableDetail = StringKey("update.state_available_detail")
    val stateStagedTitle = StringKey("update.state_staged_title")
    val stateStagedDetail = StringKey("update.state_staged_detail")
    val stateAuthFailedTitle = StringKey("update.state_auth_failed_title")
    val stateAuthFailedDetail = StringKey("update.state_auth_failed_detail")
    val stateTokenUnreadableTitle = StringKey("update.state_token_unreadable_title")
    val stateTokenUnreadableDetail = StringKey("update.state_token_unreadable_detail")
    val stateTokenKeyMissingTitle = StringKey("update.state_token_key_missing_title")
    val stateTokenKeyMissingDetail = StringKey("update.state_token_key_missing_detail")
    val stateUnreachableTitle = StringKey("update.state_unreachable_title")
    val stateUnreachableDetail = StringKey("update.state_unreachable_detail")
    val stateStagingFailedTitle = StringKey("update.state_staging_failed_title")
    val stateStagingFailedDetail = StringKey("update.state_staging_failed_detail")
    val stateRolledBackTitle = StringKey("update.state_rolled_back_title")
    val stateRolledBackDetail = StringKey("update.state_rolled_back_detail")

    // Preflight — one sentence per PreflightVerdict.Reason
    val stateBlockedTitle = StringKey("update.state_blocked_title")

    // The nightly counterpart. Same six reason sentences, one different frame around them: nobody
    // was watching, and tonight will go the same way unless the condition changes.
    val stateAutoBlockedTitle = StringKey("update.state_auto_blocked_title")
    val stateAutoBlockedDetail = StringKey("update.state_auto_blocked_detail")

    val blockedOpenBusiness = StringKey("update.blocked_open_business")
    val blockedOpenTableSession = StringKey("update.blocked_open_table_session")
    val blockedOpenTseWork = StringKey("update.blocked_open_tse_work")
    val blockedJobRunning = StringKey("update.blocked_job_running")
    val blockedInsufficientDisk = StringKey("update.blocked_insufficient_disk")
    val blockedUnknown = StringKey("update.blocked_unknown")
}
