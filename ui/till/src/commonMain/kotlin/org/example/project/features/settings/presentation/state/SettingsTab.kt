
package org.example.project.features.settings.presentation.state

/**
 * Settings tab enumeration.
 *
 * Defines the available settings tabs with role-based access control.
 *
 * @property displayNameKey Key for localized display name
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
enum class SettingsTab(
    val displayNameKey: String,
) {
    GENERAL("general"),
    PRINTER("printer"),
    TSE("tse"),
}
