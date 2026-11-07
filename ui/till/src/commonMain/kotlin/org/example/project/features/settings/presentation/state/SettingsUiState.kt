/**
 * Settings UI state for managing application settings.
 *
 * Contains all settings data and loading states for the settings screen.
 * Supports general settings (appearance, notifications) and business settings
 * (VAT, payment types) with future expansion for tax, DATEV, and system settings.
 *
 * @property selectedTab Currently selected settings tab
 * @property generalSettings General application settings
 * @property isLoading Whether settings are being loaded
 * @property errorMessage Error message if loading failed
 * @property isSaving Whether settings are being saved
 * @property saveSuccess Whether last save was successful
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.state

import org.example.project.core.presentation.error.UiError
import org.example.project.features.settings.domain.enums.FontSize
import org.example.project.features.settings.domain.enums.ThemeMode

/**
 * General settings data class.
 *
 * Contains appearance and notification settings that all users can access.
 */
data class GeneralSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    val fontSize: FontSize = FontSize.DEFAULT,
    // Notifications
    val notificationsEnabled: Boolean = true,
    val soundAlertsEnabled: Boolean = true,
    // API Connection (Mobile only)
    val serverUrl: String = "",
    val connectionTimeout: Int = 30,
    val autoReconnect: Boolean = true,
)

/**
 * Settings UI state.
 *
 * Manages the complete settings state including loading, saving,
 * and error states.
 */
data class SettingsUiState(
    val selectedTab: SettingsTab = SettingsTab.GENERAL,
    val generalSettings: GeneralSettings = GeneralSettings(),
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val successMessage: String? = null,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
) {
    /**
     * Check if there are any errors.
     */
    val hasError: Boolean
        get() = error != null

    /**
     * Check if UI should be disabled (loading or saving).
     */
    val isUiDisabled: Boolean
        get() = isLoading || isSaving
}
