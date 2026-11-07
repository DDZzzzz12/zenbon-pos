/**
 * Date format enumeration for supported date formats.
 *
 * Defines the available date format options for displaying dates
 * throughout the application.
 *
 * @property pattern Date format pattern
 * @property displayName Human-readable format name
 * @property example Example of the format
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.domain.enums

import kotlinx.serialization.Serializable

@Serializable
enum class DateFormat(
    val pattern: String,
    val displayName: String,
    val example: String,
) {
    DD_MM_YYYY("dd.MM.yyyy", "DD.MM.YYYY", "31.12.2025"),
    MM_DD_YYYY("MM/dd/yyyy", "MM/DD/YYYY", "12/31/2025"),
    YYYY_MM_DD("yyyy-MM-dd", "YYYY-MM-DD", "2025-12-31"),
    DD_MMM_YYYY("dd MMM yyyy", "DD MMM YYYY", "31 Dec 2025"),
    ;

    companion object {
        val DEFAULT = DD_MM_YYYY

        /**
         * Get all available date formats.
         */
        fun getAvailableFormats(): List<DateFormat> = entries

        /**
         * Get date format by pattern.
         *
         * @param pattern Date format pattern
         * @return DateFormat enum or DEFAULT if not found
         */
        fun fromPattern(pattern: String): DateFormat = entries.find { it.pattern == pattern } ?: DEFAULT
    }
}
