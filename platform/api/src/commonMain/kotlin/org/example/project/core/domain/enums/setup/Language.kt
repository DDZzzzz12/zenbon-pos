/**
 * Language enumeration for supported languages.
 *
 * Defines the available languages in the POS system with their
 * ISO language codes and native names for display purposes.
 *
 * @property code ISO 639-1 language code
 * @property nativeName Language name in its native script
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.setup

import kotlinx.serialization.Serializable

/**
 * Supported languages in the POS system.
 */
@Serializable
enum class Language(
    val code: String,
    val nativeName: String,
) {
    /** German language */
    GERMAN("de", "Deutsch"),

    /** English language */
    ENGLISH("en", "English"),

    /** Chinese language */
    CHINESE("zh", "中文"),
    ;

    companion object {
        /** Default language for the system */
        val DEFAULT = GERMAN

        /**
         * Get all available languages.
         *
         * @return List of all supported languages
         */
        fun getAvailableLanguages(): List<Language> = entries

        /**
         * Get language from ISO language code.
         *
         * @param code ISO 639-1 language code (e.g., "de", "en", "zh")
         * @return Language matching the code, or DEFAULT if not found
         */
        fun fromCode(code: String): Language = entries.find { it.code == code } ?: DEFAULT
    }
}
