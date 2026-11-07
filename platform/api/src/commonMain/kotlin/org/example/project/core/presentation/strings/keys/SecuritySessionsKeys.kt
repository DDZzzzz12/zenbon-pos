package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/**
 * Localization keys for the Security / Sessions settings section.
 *
 * Covers the daily re-login policy controls and the revoke-all-sessions
 * (panic) action, all admin-only.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
object SecuritySessionsKeys {
    val sectionTitle = StringKey("security_sessions.section_title")
    val dailyResetSwitch = StringKey("security_sessions.daily_reset_switch")
    val dailyResetSwitchDescription = StringKey("security_sessions.daily_reset_switch_description")
    val resetTimeLabel = StringKey("security_sessions.reset_time_label")
    val resetTimeFormatHint = StringKey("security_sessions.reset_time_format_hint")
    val revokeAllButton = StringKey("security_sessions.revoke_all_button")
    val revokeAllConfirmMessage = StringKey("security_sessions.revoke_all_confirm_message")
    val savedConfirmation = StringKey("security_sessions.saved_confirmation")
    val revokeAllConfirmation = StringKey("security_sessions.revoke_all_confirmation")
    val noPermission = StringKey("security_sessions.no_permission")
    val revokeAllConfirmCurrentSession = StringKey("security_sessions.revoke_all_confirm_current_session")
}
