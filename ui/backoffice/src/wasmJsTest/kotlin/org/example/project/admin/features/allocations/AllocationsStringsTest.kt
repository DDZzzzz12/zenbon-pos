package org.example.project.admin.features.allocations

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `TranslationRegistry.get` falls back to `key.id`, so an untranslated key renders as
 * `"admin.allocations.title"` on screen with nothing failing at compile time. This is the guard.
 */
class AllocationsStringsTest {
    private val allocationKeys: List<StringKey> =
        listOf(
            AdminStringKeys.NavAllocations,
            AdminStringKeys.AllocationsTitle,
            AdminStringKeys.AllocationsFilterUnassigned,
            AdminStringKeys.AllocationsFilterAll,
            AdminStringKeys.AllocationsEmptyUnassigned,
            AdminStringKeys.AllocationsEmptyAll,
            AdminStringKeys.AllocationsColumnDate,
            AdminStringKeys.AllocationsColumnCounterparty,
            AdminStringKeys.AllocationsColumnRemittance,
            AdminStringKeys.AllocationsColumnAmount,
            AdminStringKeys.AllocationsColumnOpen,
            AdminStringKeys.AllocationsAssign,
            AdminStringKeys.AllocationsClose,
            AdminStringKeys.AllocationsRestOpen,
            AdminStringKeys.AllocationsAssigned,
            AdminStringKeys.AllocationsReverse,
            AdminStringKeys.AllocationsOpenInvoices,
            AdminStringKeys.AllocationsNoOpenInvoices,
            AdminStringKeys.AllocationsOpenSalesInvoices,
            AdminStringKeys.AllocationsNoOpenSalesInvoices,
            AdminStringKeys.AllocationsOpenInstalments,
            AdminStringKeys.AllocationsNoOpenInstalments,
            AdminStringKeys.AllocationsAmountLabel,
            AdminStringKeys.AllocationsOwnAccounts,
            AdminStringKeys.AllocationsNoOwnAccounts,
            AdminStringKeys.AllocationsCashEntries,
            AdminStringKeys.AllocationsNoCashEntries,
            AdminStringKeys.AllocationsTargetKindInvoice,
            AdminStringKeys.AllocationsTargetKindContractInstalment,
            AdminStringKeys.AllocationsTargetKindSalesInvoice,
            AdminStringKeys.AllocationsTargetKindExpenseNoDoc,
            AdminStringKeys.AllocationsTargetKindFee,
            AdminStringKeys.AllocationsTargetKindCashTransit,
            AdminStringKeys.AllocationsTargetKindInternalTransfer,
            AdminStringKeys.AllocationsBookingTextLabel,
            AdminStringKeys.AllocationsAccountNoLabel,
            AdminStringKeys.AllocationsBookingTextRequired,
            AdminStringKeys.AllocationsSuggestionStale,
            AdminStringKeys.AllocationsSuggestionsTitle,
            AdminStringKeys.AllocationsSuggestionAccept,
            AdminStringKeys.AllocationsSignalStructuredReference,
            AdminStringKeys.AllocationsSignalRemittanceText,
            AdminStringKeys.AllocationsSignalMandateOrCreditor,
            AdminStringKeys.AllocationsSignalIbanSingleOpen,
            AdminStringKeys.AllocationsSignalContractWindow,
            AdminStringKeys.AllocationsSignalSupplierNameAmountDate,
            AdminStringKeys.AllocationsSignalDebtorNameAmountDate,
            AdminStringKeys.AllocationsSignalSubsetSum,
            AdminStringKeys.AllocationsStrengthExact,
            AdminStringKeys.AllocationsStrengthStrong,
            AdminStringKeys.AllocationsStrengthMedium,
            AdminStringKeys.BookkeepingStartBanner,
            AdminStringKeys.BookkeepingStartTitle,
            AdminStringKeys.BookkeepingStartChange,
            AdminStringKeys.BookkeepingStartHint,
            AdminStringKeys.AllocationsSelectPrompt,
            AdminStringKeys.AllocationsCount,
        )

    @Test
    fun `every allocation key is translated in all three languages`() {
        val missing =
            allocationKeys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated allocation keys: $missing")
    }
}
