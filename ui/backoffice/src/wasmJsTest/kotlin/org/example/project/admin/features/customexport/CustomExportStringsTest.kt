package org.example.project.admin.features.customexport

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `TranslationRegistry.get` falls back to `key.id`, so an untranslated key renders as
 * `"admin.customexport.title"` on screen with nothing failing at compile time. This is the guard.
 */
class CustomExportStringsTest {
    private val customExportKeys: List<StringKey> =
        listOf(
            AdminStringKeys.CustomExportTitle,
            AdminStringKeys.CustomExportNewDefinition,
            AdminStringKeys.CustomExportName,
            AdminStringKeys.CustomExportSubject,
            AdminStringKeys.CustomExportFields,
            AdminStringKeys.CustomExportSelectedFields,
            AdminStringKeys.CustomExportHeading,
            AdminStringKeys.CustomExportMoveUp,
            AdminStringKeys.CustomExportMoveDown,
            AdminStringKeys.CustomExportRemove,
            AdminStringKeys.CustomExportDateFrom,
            AdminStringKeys.CustomExportDateTo,
            AdminStringKeys.CustomExportDateUnbounded,
            AdminStringKeys.CustomExportFilters,
            AdminStringKeys.CustomExportRun,
            AdminStringKeys.CustomExportEmpty,
            AdminStringKeys.CustomExportNoFieldsSelected,
            AdminStringKeys.CustomExportBroken,
            AdminStringKeys.CustomExportBrokenExplainer,
            AdminStringKeys.CustomExportTruncated,
            AdminStringKeys.CustomExportDeleteConfirm,
            AdminStringKeys.CustomExportSubjectFixed,
            AdminStringKeys.CustomExportSelectPrompt,
            AdminStringKeys.CustomExportAggregateLimit,
            AdminStringKeys.CustomExportFieldFilter,
            AdminStringKeys.CustomExportFieldFilterEmpty,
        )

    @Test
    fun `every custom export key is translated in all three languages`() {
        val missing =
            customExportKeys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated custom export keys: $missing")
    }

    /**
     * The picker calls `str(key, used, cap)`. A translation missing either placeholder renders a
     * sentence with no numbers in it — and the whole point of the hint is the numbers.
     */
    @Test
    fun `the aggregate limit hint carries both count placeholders`() {
        listOf(
            "de" to GermanAdminStrings.strings[AdminStringKeys.CustomExportAggregateLimit],
            "en" to EnglishAdminStrings.strings[AdminStringKeys.CustomExportAggregateLimit],
            "zh" to ChineseAdminStrings.strings[AdminStringKeys.CustomExportAggregateLimit],
        ).forEach { (language, text) ->
            val hint = text.orEmpty()
            assertTrue(hint.contains("{0}"), "Aggregate hint has no {0} placeholder in $language: $text")
            assertTrue(hint.contains("{1}"), "Aggregate hint has no {1} placeholder in $language: $text")
        }
    }

    @Test
    fun `the truncation notice carries the row count placeholder`() {
        // The screen calls str(key, rowCount) — there is no String.format on wasm, so a translation
        // that drops {0} silently renders a sentence with no number in it.
        listOf(
            "de" to GermanAdminStrings.strings[AdminStringKeys.CustomExportTruncated],
            "en" to EnglishAdminStrings.strings[AdminStringKeys.CustomExportTruncated],
            "zh" to ChineseAdminStrings.strings[AdminStringKeys.CustomExportTruncated],
        ).forEach { (language, text) ->
            assertTrue(text?.contains("{0}") == true, "Truncation notice has no {0} placeholder in $language: $text")
        }
    }
}
