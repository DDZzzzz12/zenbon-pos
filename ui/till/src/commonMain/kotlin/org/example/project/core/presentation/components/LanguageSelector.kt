package org.example.project.core.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.*
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.theme.LocalCjkFontFamily
import org.example.project.ui.theme.PosSpacing

/**
 * Language selector component with adaptive design.
 * Supports both compact button and expanded dropdown modes.
 */
@Composable
fun LanguageSelector(
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    compact: Boolean = false,
) {
    val windowSize = LocalWindowSizeClass.current
    val languageManager = LocalLanguageManager.current
    val currentLanguage by languageManager.currentLanguage
    var expanded by remember { mutableStateOf(false) }

    if (compact) {
        CompactLanguageSelector(
            currentLanguage = currentLanguage,
            onLanguageSelected = { languageManager.setLanguage(it) },
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = modifier,
        )
    } else {
        FullLanguageSelector(
            currentLanguage = currentLanguage,
            onLanguageSelected = { languageManager.setLanguage(it) },
            expanded = expanded,
            onExpandedChange = { expanded = it },
            showLabel = showLabel,
            modifier = modifier,
        )
    }
}

/**
 * Compact language selector - shows only the language code
 */
@Composable
private fun CompactLanguageSelector(
    currentLanguage: Language,
    onLanguageSelected: (Language) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowSizeClass.current
    Box(modifier = modifier) {
        // Current language indicator
        Surface(
            modifier =
                Modifier
                    .size(AdaptiveSize.getButtonHeight(windowSize))
                    .clickable { onExpandedChange(!expanded) },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            border =
                BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                ),
        ) {
            Box(
                contentAlignment = Alignment.Center,
            ) {
                // The code, not a flag emoji: the wasm canvas has no emoji font, so a flag drew as two
                // "NO GLYPH" boxes, and Windows has no flag glyphs either.
                Text(
                    text = currentLanguage.code.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }

        // Dropdown menu
        PosDropdown(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            Language.getAvailableLanguages().forEach { language ->
                LanguageDropdownItem(
                    language = language,
                    isSelected = language == currentLanguage,
                    onClick = {
                        onLanguageSelected(language)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

/**
 * Full language selector with label and dropdown
 */
@Composable
private fun FullLanguageSelector(
    currentLanguage: Language,
    onLanguageSelected: (Language) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowSizeClass.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (showLabel) {
            Text(
                text = getLanguageLabel(currentLanguage),
                style =
                    AdaptiveTypography.getAdaptiveTextStyle(
                        MaterialTheme.typography.labelSmall,
                        windowSize,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box {
            // Language selector button
            Surface(
                modifier =
                    Modifier
                        .clickable { onExpandedChange(!expanded) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
                border =
                    BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                    ),
            ) {
                Row(
                    modifier =
                        Modifier.padding(
                            horizontal = PosSpacing.sm,
                            vertical = PosSpacing.xs,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PosSpacing.xs),
                ) {
                    Text(
                        text = currentLanguage.code.uppercase(),
                        style =
                            AdaptiveTypography
                                .getAdaptiveTextStyle(
                                    MaterialTheme.typography.labelMedium,
                                    windowSize,
                                ).copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = str(AccessibilityKeys.selectLanguage),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Dropdown menu
            PosDropdown(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                Language.getAvailableLanguages().forEach { language ->
                    LanguageDropdownItem(
                        language = language,
                        isSelected = language == currentLanguage,
                        onClick = {
                            onLanguageSelected(language)
                            onExpandedChange(false)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Individual dropdown menu item for language selection.
 *
 * The native name is the label, the code is the supporting line, and selection is the item's own
 * check mark. There is deliberately no flag emoji: it needs an emoji font the wasm canvas lacks.
 */
@Composable
private fun LanguageDropdownItem(
    language: Language,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    PosDropdownItem(
        text = language.nativeName,
        supportingText = language.code.uppercase(),
        selected = isSelected,
        onClick = onClick,
        // The entry is written in Chinese whichever language is active, so it needs the CJK font
        // even under a German or English theme (null on platforms with system fallback).
        fontFamily = if (language == Language.CHINESE) LocalCjkFontFamily.current else null,
    )
}

/**
 * Get language label text
 */
private fun getLanguageLabel(language: Language): String =
    when (language) {
        Language.GERMAN -> "Sprache"
        Language.ENGLISH -> "Language"
        Language.CHINESE -> "语言"
    }
