/**
 * Time format enumeration for supported time formats.
 *
 * Defines the available time format options for displaying times
 * throughout the application.
 *
 * @property pattern Time format pattern
 * @property displayName Human-readable format name
 * @property example Example of the format
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.domain.enums

import kotlinx.serialization.Serializable

@Serializable
enum class TimeFormat(
    val pattern: String,
    val displayName: String,
    val example: String,
) {
    HH_MM_24("HH:mm", "24-Hour (HH:mm)", "23:45"),
    HH_MM_SS_24("HH:mm:ss", "24-Hour with seconds", "23:45:30"),
    HH_MM_12("hh:mm a", "12-Hour (hh:mm AM/PM)", "11:45 PM"),
    HH_MM_SS_12("hh:mm:ss a", "12-Hour with seconds", "11:45:30 PM"),
    ;

    companion object {
        val DEFAULT = HH_MM_24

        /**
         * Get all available time formats.
         */
        fun getAvailableFormats(): List<TimeFormat> = entries

        /**
         * Get time format by pattern.
         *
         * @param pattern Time format pattern
         * @return TimeFormat enum or DEFAULT if not found
         */
        fun fromPattern(pattern: String): TimeFormat = entries.find { it.pattern == pattern } ?: DEFAULT
    }
}
