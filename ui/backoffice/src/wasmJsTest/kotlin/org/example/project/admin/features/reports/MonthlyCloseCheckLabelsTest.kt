package org.example.project.admin.features.reports

import org.example.project.admin.features.reports.presentation.components.checkLabelKey
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The server sends check ids; `checkLabelKey` maps them and falls back to `StringKey(id)`, and
 * `TranslationRegistry.get` falls back to `key.id` again — two silent fallbacks, so a missing
 * branch or a missing translation reaches the operator as raw developer text with nothing failing
 * at compile time. Both were live defects. This is the guard.
 *
 * The ids are the ones the `monthlyclose/data/service/checks` package emits.
 */
class MonthlyCloseCheckLabelsTest {
    private val serverCheckIds =
        listOf(
            "zbon_chain_integrity",
            "no_bills_outside_zbon",
            "vat_consistent",
            "no_open_bills",
            "all_tse_signed",
            "all_cash_documented",
            "datev_exported",
            "no_possible_double_sign",
            "tse_tanr_continuity",
            "no_simulated_signatures",
        )

    @Test
    fun `every server check id maps to a key that is not the raw id fallback`() {
        val unmapped = serverCheckIds.filter { checkLabelKey(it) == StringKey(it) }
        assertTrue(unmapped.isEmpty(), "checkLabelKey has no branch for: $unmapped")
    }

    @Test
    fun `every mapped check key is translated in all three languages`() {
        val translations = defaultTranslations()
        val missing =
            serverCheckIds.flatMap { id ->
                val key = checkLabelKey(id)
                Language.entries
                    .filter { language -> translations[language]?.get(key).isNullOrBlank() }
                    .map { language -> "$language:$id" }
            }
        assertTrue(missing.isEmpty(), "untranslated check labels would render as their key id: $missing")
    }

    @Test
    fun `an unknown id still resolves without throwing`() {
        assertTrue(checkLabelKey("brand_new_server_check") == StringKey("brand_new_server_check"))
    }
}
