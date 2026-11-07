/**
 * Font size enumeration for supported font sizes.
 *
 * Defines the available font size options for the application.
 *
 * @property displayName Human-readable size name
 * @property scale Scale factor for text size
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.domain.enums

import kotlinx.serialization.Serializable

@Serializable
enum class FontSize(
    val displayName: String,
    val scale: Float,
) {
    SMALL("Small", 0.85f),
    MEDIUM("Medium", 1.0f),
    LARGE("Large", 1.15f),
    ;

    companion object {
        val DEFAULT = MEDIUM

        /**
         * Get all available font sizes.
         */
        fun getAvailableSizes(): List<FontSize> = entries
    }
}
