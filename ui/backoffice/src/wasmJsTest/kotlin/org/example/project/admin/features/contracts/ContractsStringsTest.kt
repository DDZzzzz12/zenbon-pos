package org.example.project.admin.features.contracts

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `TranslationRegistry.get` falls back to `key.id`, so an untranslated key renders as
 * `"admin.contracts.title"` on screen with nothing failing at compile time. This is the guard —
 * and it only checks the keys it is given, so a new one must be added to this list too.
 */
class ContractsStringsTest {
    private val contractKeys: List<StringKey> =
        listOf(
            AdminStringKeys.NavContracts,
            AdminStringKeys.ContractsTitle,
            AdminStringKeys.ContractsEmpty,
            AdminStringKeys.ContractsShowInactive,
            AdminStringKeys.ContractsNew,
            AdminStringKeys.ContractsColumnName,
            AdminStringKeys.ContractsColumnSupplier,
            AdminStringKeys.ContractsColumnInterval,
            AdminStringKeys.ContractsColumnExpected,
            AdminStringKeys.ContractsColumnNextDue,
            AdminStringKeys.ContractsColumnLastPayment,
            AdminStringKeys.ContractIntervalMonthly,
            AdminStringKeys.ContractIntervalQuarterly,
            AdminStringKeys.ContractIntervalSemiannual,
            AdminStringKeys.ContractIntervalAnnual,
            AdminStringKeys.ContractDetailTitle,
            AdminStringKeys.ContractCreateTitle,
            AdminStringKeys.ContractSectionBasics,
            AdminStringKeys.ContractSectionSchedule,
            AdminStringKeys.ContractSectionAmounts,
            AdminStringKeys.ContractSectionBooking,
            AdminStringKeys.ContractFieldName,
            AdminStringKeys.ContractFieldDescription,
            AdminStringKeys.ContractFieldSupplier,
            AdminStringKeys.ContractFieldInterval,
            AdminStringKeys.ContractFieldDueDay,
            AdminStringKeys.ContractFieldStartDate,
            AdminStringKeys.ContractFieldEndDate,
            AdminStringKeys.ContractFieldNoticePeriod,
            AdminStringKeys.ContractFieldExpectedAmount,
            AdminStringKeys.ContractFieldTolerancePct,
            AdminStringKeys.ContractFieldToleranceAbs,
            AdminStringKeys.ContractFieldAccountNo,
            AdminStringKeys.ContractFieldBuCode,
            AdminStringKeys.ContractFieldCostCenter,
            AdminStringKeys.ContractFieldExpectsInvoice,
            AdminStringKeys.ContractFieldActive,
            AdminStringKeys.ContractExpectsInvoiceHint,
            AdminStringKeys.ContractToleranceHint,
            AdminStringKeys.ContractDueDayHint,
            AdminStringKeys.ContractErrorNameRequired,
            AdminStringKeys.ContractErrorDueDay,
            AdminStringKeys.ContractErrorInterval,
            AdminStringKeys.ContractErrorAmount,
            AdminStringKeys.ContractErrorDate,
            AdminStringKeys.ContractErrorSupplier,
            AdminStringKeys.ContractInstalmentsTitle,
            AdminStringKeys.ContractInstalmentsEmpty,
            AdminStringKeys.ContractInstalmentsUnsaved,
            AdminStringKeys.ContractInstalmentsHint,
            AdminStringKeys.ContractInstalmentDue,
            AdminStringKeys.ContractInstalmentExpected,
            AdminStringKeys.ContractInstalmentOpen,
            AdminStringKeys.ContractInstalmentInvoice,
            AdminStringKeys.ContractInstalmentNoInvoice,
            AdminStringKeys.ContractStatusOpen,
            AdminStringKeys.ContractStatusPaid,
            AdminStringKeys.ContractStatusPartial,
            AdminStringKeys.ContractStatusOverdue,
            AdminStringKeys.ContractStatusWaived,
            AdminStringKeys.ContractsColumnAlerts,
            AdminStringKeys.ContractsAlertSummary,
            AdminStringKeys.ContractsGroupOverdue,
            AdminStringKeys.ContractsGroupThisWeek,
            AdminStringKeys.ContractsGroupThisMonth,
            AdminStringKeys.ContractsGroupLater,
            AdminStringKeys.ContractsGroupInactive,
            AdminStringKeys.ContractsCount,
            AdminStringKeys.ContractAlertsTitle,
            AdminStringKeys.ContractAlertPaymentMissing,
            AdminStringKeys.ContractAlertInvoiceMissing,
            AdminStringKeys.ContractAlertAmountOffBand,
            AdminStringKeys.ContractAlertActual,
            AdminStringKeys.ContractsSearchPlaceholder,
            AdminStringKeys.ContractsEmptyFiltered,
        )

    @Test
    fun `every contract key is translated in all three languages`() {
        val missing =
            contractKeys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated contract keys: $missing")
    }
}
