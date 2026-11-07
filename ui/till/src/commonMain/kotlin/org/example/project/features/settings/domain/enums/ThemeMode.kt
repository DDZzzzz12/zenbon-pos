/**
 * Theme mode enumeration for supported theme modes.
 *
 * Defines the available theme options for the application.
 *
 * @property displayName Human-readable theme name
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.domain.enums

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode(
    val displayName: String,
) {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System"),
    ;

    companion object {
        val DEFAULT = SYSTEM

        /**
         * Get all available theme modes.
         */
        fun getAvailableModes(): List<ThemeMode> = entries
    }
}
