package org.example.project.admin.core.presentation.strings

import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations

/**
 * Registers translations with the shared registry: the shared [defaultTranslations]
 * (Common/Dashboard/Settings/Features/Rules) merged with admin-specific overrides/additions.
 * Must be called once at startup before any `str` call.
 */
object AdminTranslations {
    fun init() {
        val base = defaultTranslations()
        TranslationRegistry.initialize(
            mapOf(
                Language.ENGLISH to base[Language.ENGLISH].orEmpty() + EnglishAdminStrings.strings,
                Language.GERMAN to base[Language.GERMAN].orEmpty() + GermanAdminStrings.strings,
                Language.CHINESE to base[Language.CHINESE].orEmpty() + ChineseAdminStrings.strings,
            ),
        )
    }
}
