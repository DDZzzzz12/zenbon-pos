package org.example.project.core.presentation.strings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.translations.ChineseCommonStrings
import org.example.project.core.presentation.strings.translations.ChineseDashboardStrings
import org.example.project.core.presentation.strings.translations.ChineseFeaturesStrings
import org.example.project.core.presentation.strings.translations.ChineseRulesStrings
import org.example.project.core.presentation.strings.translations.ChineseSettingsStrings
import org.example.project.core.presentation.strings.translations.EnglishCommonStrings
import org.example.project.core.presentation.strings.translations.EnglishDashboardStrings
import org.example.project.core.presentation.strings.translations.EnglishFeaturesStrings
import org.example.project.core.presentation.strings.translations.EnglishRulesStrings
import org.example.project.core.presentation.strings.translations.EnglishSettingsStrings
import org.example.project.core.presentation.strings.translations.GermanCommonStrings
import org.example.project.core.presentation.strings.translations.GermanDashboardStrings
import org.example.project.core.presentation.strings.translations.GermanFeaturesStrings
import org.example.project.core.presentation.strings.translations.GermanRulesStrings
import org.example.project.core.presentation.strings.translations.GermanSettingsStrings

/**
 * The complete built-in translation set (Common + Dashboard + Settings + Features + Rules)
 * for every supported language. Shared by all clients so there is a single source of UI text;
 * apps may merge additional module-specific maps on top before calling
 * [TranslationRegistry.initialize].
 */
fun defaultTranslations(): Map<Language, Map<StringKey, String>> =
    mapOf(
        Language.GERMAN to
            GermanCommonStrings.all + GermanDashboardStrings.all + GermanSettingsStrings.all + GermanFeaturesStrings.all +
            GermanRulesStrings.all,
        Language.ENGLISH to
            EnglishCommonStrings.all + EnglishDashboardStrings.all + EnglishSettingsStrings.all + EnglishFeaturesStrings.all +
            EnglishRulesStrings.all,
        Language.CHINESE to
            ChineseCommonStrings.all + ChineseDashboardStrings.all + ChineseSettingsStrings.all + ChineseFeaturesStrings.all +
            ChineseRulesStrings.all,
    )
