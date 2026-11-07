package org.example.project.admin.features.bank

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `TranslationRegistry.get` falls back to `key.id`, so an untranslated key renders as
 * `"admin.bank.title"` on screen with nothing failing at compile time. This is the guard.
 */
class BankStringsTest {
    private val bankKeys: List<StringKey> =
        listOf(
            AdminStringKeys.NavBank,
            AdminStringKeys.BankTitle,
            AdminStringKeys.BankEmpty,
            AdminStringKeys.BankColumnBank,
            AdminStringKeys.BankColumnIban,
            AdminStringKeys.BankColumnStatements,
            AdminStringKeys.BankColumnGaps,
            AdminStringKeys.BankAddAccount,
            AdminStringKeys.BankCreateTitle,
            AdminStringKeys.BankFieldIban,
            AdminStringKeys.BankFieldBic,
            AdminStringKeys.BankFieldBankName,
            AdminStringKeys.BankFieldAccountHolder,
            AdminStringKeys.BankFieldLedgerAccount,
            AdminStringKeys.BankCreateSubmit,
            AdminStringKeys.BankErrorIbanRequired,
            AdminStringKeys.BankErrorIbanTaken,
            AdminStringKeys.BankUnassignedTitle,
            AdminStringKeys.BankUnassignedExplainer,
            AdminStringKeys.BankUnassignedNone,
            AdminStringKeys.BankAssignAction,
            AdminStringKeys.BankAssignTitle,
            AdminStringKeys.BankResolveAction,
            AdminStringKeys.BankResolveNone,
            AdminStringKeys.BankResolveDone,
            AdminStringKeys.BankGapsTitle,
            AdminStringKeys.BankGapsExplainer,
            AdminStringKeys.BankNoGaps,
            AdminStringKeys.BankStatementUploadTitle,
            AdminStringKeys.BankStatementUploadExplainer,
            AdminStringKeys.BankImportAction,
            AdminStringKeys.BankSelectAccountPrompt,
            AdminStringKeys.BankStatementsTitle,
            AdminStringKeys.BankStatementsEmpty,
            AdminStringKeys.BankColumnStatementNumber,
            AdminStringKeys.BankColumnPeriod,
            AdminStringKeys.BankColumnOpeningBalance,
            AdminStringKeys.BankColumnClosingBalance,
            AdminStringKeys.BankColumnTransactions,
            AdminStringKeys.BankContinuityTitle,
            AdminStringKeys.BankContinuityComplete,
            AdminStringKeys.BankContinuityGaps,
            AdminStringKeys.BankStatementMissing,
            AdminStringKeys.BankUnassignedBanner,
            AdminStringKeys.BankAccountGapBadge,
            AdminStringKeys.BankBookingsEmpty,
            AdminStringKeys.BankBookingsShowing,
            AdminStringKeys.BankBookingOpen,
            AdminStringKeys.BankBookingPartial,
            AdminStringKeys.BankBookingAssigned,
            AdminStringKeys.BankBookingDuplicate,
        )

    @Test
    fun `every bank key is translated in all three languages`() {
        val missing =
            bankKeys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated bank keys: $missing")
    }
}
