package org.example.project.features.employees.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.EmployeeManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField

/**
 * Language preference selector component for employee settings.
 *
 * Displays a dropdown menu allowing users to select their preferred
 * language (German, English, or Chinese) for the application interface.
 *
 * @param selectedLanguageCode Currently selected language code ("de", "en", "zh")
 * @param onLanguageSelected Callback when a language is selected
 * @param language Current display language for string localization
 * @param modifier Modifier for layout customization
 * @param enabled Whether the selector is enabled for interaction
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePreferenceSelector(
    selectedLanguageCode: String,
    onLanguageSelected: (String) -> Unit,
    language: Language,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    // Map language codes to display names
    val languageOptions = Language.entries

    // Get current selection display name based on selectedLanguageCode
    val currentLanguageDisplay =
        remember(selectedLanguageCode) {
            languageOptions.find { it.code == selectedLanguageCode } ?: Language.GERMAN
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Label
        Text(
            text = str(EmployeeManagementKeys.languagePreferenceLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Dropdown selector
        Box {
            PosPickerField(
                value = currentLanguageDisplay.nativeName,
                onClick = { expanded = true },
                enabled = enabled,
                label = str(EmployeeManagementKeys.languageLabel),
                modifier = Modifier.fillMaxWidth(),
            )

            PosDropdown(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                languageOptions.forEach { language ->
                    PosDropdownItem(
                        text = language.nativeName,
                        selected = language.code == currentLanguageDisplay.code,
                        onClick = {
                            onLanguageSelected(language.code)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
