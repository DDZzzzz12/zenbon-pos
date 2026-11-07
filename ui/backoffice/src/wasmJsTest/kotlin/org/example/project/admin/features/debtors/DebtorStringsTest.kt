package org.example.project.admin.features.debtors

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `TranslationRegistry.get` falls back to `key.id`, so an untranslated key renders as
 * `"admin.debtors.title"` on screen with nothing failing at compile time. This is the guard.
 */
class DebtorStringsTest {
    private val debtorKeys: List<StringKey> =
        listOf(
            AdminStringKeys.NavDebtors,
            AdminStringKeys.DebtorsTitle,
            AdminStringKeys.DebtorsEmpty,
            AdminStringKeys.DebtorsAddButton,
            AdminStringKeys.DebtorsFilterActive,
            AdminStringKeys.DebtorColumnInvoices,
            AdminStringKeys.DebtorColumnStatus,
            AdminStringKeys.DebtorStatusActive,
            AdminStringKeys.DebtorStatusInactive,
            AdminStringKeys.DebtorsCount,
            AdminStringKeys.DebtorsEmptyFiltered,
            AdminStringKeys.DebtorSelectPrompt,
            AdminStringKeys.DebtorInvoicesTitle,
            AdminStringKeys.DebtorInvoicesEmpty,
            AdminStringKeys.DebtorInvoicesOpenAll,
            AdminStringKeys.DebtorFieldName,
            AdminStringKeys.DebtorFieldVatId,
            AdminStringKeys.DebtorFieldCity,
            AdminStringKeys.DebtorFieldNumber,
            AdminStringKeys.DebtorFieldEmail,
            AdminStringKeys.DebtorsSearchPlaceholder,
        )

    @Test
    fun `every debtor key is translated in all three languages`() {
        val missing =
            debtorKeys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated debtor keys: $missing")
    }
}
