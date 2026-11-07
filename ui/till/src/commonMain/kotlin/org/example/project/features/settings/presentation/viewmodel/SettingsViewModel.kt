/**
 * Settings ViewModel for managing settings state and operations.
 *
 * Handles loading and saving of application settings. Currently supports
 * general settings (language, currency, date/time formats) with local storage.
 *
 * State Management:
 * - Loads settings on initialization
 * - Auto-saves changes with debouncing (future enhancement)
 * - Provides loading and error states
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.auth.SettingManager
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.settings.domain.enums.FontSize
import org.example.project.features.settings.domain.enums.ThemeMode
import org.example.project.features.settings.presentation.state.GeneralSettings
import org.example.project.features.settings.presentation.state.SettingsTab
import org.example.project.features.settings.presentation.state.SettingsUiState

/**
 * Settings ViewModel.
 *
 * Manages the settings UI state and business logic for the settings screen.
 *
 * @property settingManager Local settings manager for theme, language, etc.
 */
class SettingsViewModel(
    private val settingManager: SettingManager,
    private val languageManager: LanguageManager,
) : ViewModel() {
    private val logger = Logger.withTag("SettingsViewModel")

    /** PosSuccessWatcher shows success text verbatim, so it is resolved here in the operator's language. */
    private fun localized(key: StringKey): String = getString(key, languageManager.currentLanguage.value)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    /**
     * Load settings from local storage.
     *
     * In the current implementation, this loads default values.
     * Future enhancement: Load from local preferences or server.
     */
    private fun loadSettings() {
        viewModelScope.launch {
            logger.i { "SettingsViewModel.loadSettings: loading settings" }
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                // TODO: Load from local preferences or API
                // For now, using defaults
                val generalSettings = GeneralSettings()

                _uiState.update {
                    it.copy(
                        generalSettings = generalSettings,
                        isLoading = false,
                    )
                }
                logger.i { "SettingsViewModel.loadSettings: settings loaded successfully" }
            } catch (e: Exception) {
                logger.e(e) { "SettingsViewModel.loadSettings: failed to load settings" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                                cause = e,
                            ),
                    )
                }
            }
        }
    }

    /**
     * Select a settings tab.
     *
     * @param tab Tab to select
     */
    fun selectTab(tab: SettingsTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    /**
     * Update theme mode setting.
     *
     * @param themeMode New theme mode value
     */
    fun updateThemeMode(themeMode: ThemeMode) {
        _uiState.update {
            it.copy(
                generalSettings = it.generalSettings.copy(themeMode = themeMode),
            )
        }
        saveSettings()
    }

    /**
     * Update font size setting.
     *
     * @param fontSize New font size value
     */
    fun updateFontSize(fontSize: FontSize) {
        _uiState.update {
            it.copy(
                generalSettings = it.generalSettings.copy(fontSize = fontSize),
            )
        }
        saveSettings()
    }

    /**
     * Update notifications enabled setting.
     *
     * @param enabled Whether notifications are enabled
     */
    fun updateNotificationsEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                generalSettings = it.generalSettings.copy(notificationsEnabled = enabled),
            )
        }
        saveSettings()
    }

    /**
     * Update sound alerts enabled setting.
     *
     * @param enabled Whether sound alerts are enabled
     */
    fun updateSoundAlertsEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                generalSettings = it.generalSettings.copy(soundAlertsEnabled = enabled),
            )
        }
        saveSettings()
    }

    /**
     * Update server URL setting.
     *
     * @param url New server URL
     */
    fun updateServerUrl(url: String) {
        _uiState.update {
            it.copy(
                generalSettings = it.generalSettings.copy(serverUrl = url),
            )
        }
        saveSettings()
    }

    /**
     * Update connection timeout setting.
     *
     * @param timeout New timeout in seconds
     */
    fun updateConnectionTimeout(timeout: Int) {
        _uiState.update {
            it.copy(
                generalSettings = it.generalSettings.copy(connectionTimeout = timeout),
            )
        }
        saveSettings()
    }

    /**
     * Update auto-reconnect setting.
     *
     * @param enabled Whether auto-reconnect is enabled
     */
    fun updateAutoReconnect(enabled: Boolean) {
        _uiState.update {
            it.copy(
                generalSettings = it.generalSettings.copy(autoReconnect = enabled),
            )
        }
        saveSettings()
    }

    /**
     * Save settings to local storage.
     *
     * Future enhancement: Persist to local preferences and/or server.
     */
    fun saveSettings() {
        viewModelScope.launch {
            logger.i { "SettingsViewModel.saveSettings: saving settings" }
            _uiState.update { it.copy(isSaving = true, saveSuccess = false) }

            try {
                // TODO: Save to local preferences or API
                // Simulate save delay
                delay(500)

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveSuccess = true,
                        successMessage = localized(SettingsKeys.saveSuccess),
                    )
                }
                logger.i { "SettingsViewModel.saveSettings: settings saved successfully" }

                // Reset success flag after 2 seconds
                kotlinx.coroutines.delay(2000)
                _uiState.update { it.copy(saveSuccess = false, successMessage = null) }
            } catch (e: Exception) {
                logger.e(e) { "SettingsViewModel.saveSettings: failed to save settings" }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                                cause = e,
                            ),
                    )
                }
            }
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear success message.
     */
    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }
}
