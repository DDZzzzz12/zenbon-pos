package org.example.project.admin.features.reports

import org.example.project.admin.features.reports.presentation.components.checklistDetailKey
import org.example.project.admin.features.reports.presentation.components.checklistDetailText
import org.example.project.admin.features.reports.presentation.components.exportTypeKey
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * admin16 NOTE-1: the checklist printed the server's English sentences ("Missing full-period
 * export for: KASSENBUCH", "Note: per-rate cross-check skipped; …") on a German screen. The client
 * now renders them from `detailCode` + `detailArgs`; these pin that every code the server can send
 * has text in all three languages, and that the args are localized rather than passed through.
 */
class MonthlyCloseDetailTextTest {
    @BeforeTest
    fun registerTranslations() {
        TranslationRegistry.initialize(defaultTranslations())
    }

    private fun item(
        code: String?,
        args: List<String> = emptyList(),
        detail: String = "server english",
    ) = MonthlyCloseDto.ChecklistItemDto(
        id = "datev_exported",
        status = MonthlyCloseDto.ChecklistStatus.FAILED,
        detail = detail,
        affectedCount = 0,
        jumpTo = MonthlyCloseDto.SectionId.DATEV_EXPORT,
        detailCode = code,
        detailArgs = args,
    )

    @Test
    fun everyDetailCodeHasATemplateInAllThreeLanguages() {
        val translations = defaultTranslations()
        val missing =
            MonthlyCloseDetailCodes.ALL.flatMap { code ->
                val key = checklistDetailKey(code) ?: return@flatMap listOf("unmapped:$code")
                Language.entries
                    .filter { translations[it]?.get(key).isNullOrBlank() }
                    .map { "$it:$code" }
            }
        assertTrue(missing.isEmpty(), "detail codes without localized text: $missing")
    }

    @Test
    fun everyExportTypeHasANameInAllThreeLanguages() {
        val translations = defaultTranslations()
        val missing =
            MonthlyCloseDetailCodes.EXPORT_TYPES.flatMap { type ->
                val key = exportTypeKey(type) ?: return@flatMap listOf("unmapped:$type")
                Language.entries
                    .filter { translations[it]?.get(key).isNullOrBlank() }
                    .map { "$it:$type" }
            }
        assertTrue(missing.isEmpty(), "export types without a localized name: $missing")
    }

    // NOTE-22 (admin18): the DATEV export log printed the server's DatevExportType constant raw.
    @Test
    fun everyExportLogTypeHasANameInAllThreeLanguages() {
        val translations = defaultTranslations()
        val logTypes = listOf("KASSENBUCH", "AP_LEDGER", "AR_LEDGER", "BUCHUNGSSTAPEL", "BUCHUNGSSTAPEL_MIT_BELEGEN")
        val missing =
            logTypes.flatMap { type ->
                val key = exportTypeKey(type) ?: return@flatMap listOf("unmapped:$type")
                Language.entries
                    .filter { translations[it]?.get(key).isNullOrBlank() || translations[it]?.get(key) == type }
                    .map { "$it:$type" }
            }
        assertTrue(missing.isEmpty(), "export-log types without a localized name: $missing")
    }

    @Test
    fun missingDatevExportNamesTheTypeInTheOperatorsLanguage() {
        val text =
            checklistDetailText(
                item(MonthlyCloseDetailCodes.DATEV_MISSING_PERIOD, listOf("KASSENBUCH", "AP_LEDGER")),
                Language.GERMAN,
                "EUR",
            )
        assertEquals("Kein DATEV-Export deckt den ganzen Zeitraum ab für: Kassenbuch, Eingangsrechnungen", text)
        assertFalse("KASSENBUCH" in text)
    }

    @Test
    fun weeklyCodeKeepsTheCountAndLocalizesTheTypes() {
        val text =
            checklistDetailText(
                item(MonthlyCloseDetailCodes.DATEV_MISSING_WEEKS, listOf("3", "KASSENBUCH")),
                Language.ENGLISH,
                "EUR",
            )
        assertEquals("Calendar weeks without a complete DATEV export: 3 (Cash book)", text)
    }

    @Test
    fun vatDeltaFormatsItsAmountsAsMoney() {
        val text =
            checklistDetailText(
                item(MonthlyCloseDetailCodes.VAT_DELTA, listOf("1.5", "100.00", "98.50")),
                Language.GERMAN,
                "EUR",
            )
        assertEquals("USt-Abweichung 1,50 € (Belege: 100,00 €, Z-Bons: 98,50 €)", text)
    }

    @Test
    fun noPlaceholderSurvivesForAnyCodeInAnyLanguage() {
        val args = listOf("1", "KASSENBUCH", "2.00")
        val leftovers =
            MonthlyCloseDetailCodes.ALL.flatMap { code ->
                Language.entries.mapNotNull { language ->
                    val text = checklistDetailText(item(code, args), language, "EUR")
                    if (Regex("""\{\d}""").containsMatchIn(text)) "$language:$code -> $text" else null
                }
            }
        assertTrue(leftovers.isEmpty(), "unfilled placeholders: $leftovers")
    }

    @Test
    fun anOlderServerWithoutACodeFallsBackToItsText() {
        assertEquals("server english", checklistDetailText(item(null), Language.GERMAN, "EUR"))
    }

    @Test
    fun anUnknownCodeFromANewerServerFallsBackToItsText() {
        assertEquals("server english", checklistDetailText(item("brand_new_code"), Language.GERMAN, "EUR"))
    }
}
