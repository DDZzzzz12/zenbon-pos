package org.example.project.ui.screens.settings.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.domain.enums.ThemeMode
import org.example.project.ui.components.PosSegmentedButtonRow

/**
 * Segmented buttons for theme mode selection.
 *
 * Displays three options: Light, Dark, and System Default.
 */
@Composable
fun ThemeSegmentedButtons(
    selectedTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PosSegmentedButtonRow(
        options = ThemeMode.entries,
        selectedOption = selectedTheme,
        onOptionSelected = onThemeSelected,
        modifier = modifier,
        enabled = enabled,
        optionIcon = { theme -> getThemeIcon(theme) },
    ) { theme -> themeLabel(theme) }
}

/**
 * Get icon for theme mode.
 */
private fun getThemeIcon(theme: ThemeMode): ImageVector =
    when (theme) {
        ThemeMode.LIGHT -> Icons.Default.LightMode
        ThemeMode.DARK -> Icons.Default.DarkMode
        ThemeMode.SYSTEM -> Icons.Default.SettingsBrightness
    }

/** Localized theme name. [ThemeMode.displayName] is an English literal and must not reach the screen. */
@Composable
private fun themeLabel(theme: ThemeMode): String =
    when (theme) {
        ThemeMode.LIGHT -> str(SettingsKeys.themeLight)
        ThemeMode.DARK -> str(SettingsKeys.themeDark)
        ThemeMode.SYSTEM -> str(SettingsKeys.themeSystem)
    }
