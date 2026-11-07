package org.example.project.admin.features.supplier

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `TranslationRegistry.get` falls back to `key.id`, so an untranslated key renders as
 * `"admin.supplier.title"` on screen with nothing failing at compile time. This is the guard.
 */
class SupplierStringsTest {
    private val supplierKeys: List<StringKey> =
        listOf(
            AdminStringKeys.NavSuppliers,
            AdminStringKeys.SuppliersTitle,
            AdminStringKeys.SuppliersEmpty,
            AdminStringKeys.SuppliersFilterProvisional,
            AdminStringKeys.SuppliersColumnName,
            AdminStringKeys.SuppliersColumnVatId,
            AdminStringKeys.SuppliersColumnCreditorNo,
            AdminStringKeys.SuppliersColumnStatus,
            AdminStringKeys.SupplierStatusProvisional,
            AdminStringKeys.SupplierStatusConfirmed,
            AdminStringKeys.SupplierDetailTitle,
            AdminStringKeys.SupplierSectionIdentity,
            AdminStringKeys.SupplierSectionAddress,
            AdminStringKeys.SupplierSectionBooking,
            AdminStringKeys.SupplierSectionIbans,
            AdminStringKeys.SupplierFieldName,
            AdminStringKeys.SupplierFieldLegalName,
            AdminStringKeys.SupplierFieldVatId,
            AdminStringKeys.SupplierFieldTaxNumber,
            AdminStringKeys.SupplierFieldStreet,
            AdminStringKeys.SupplierFieldZip,
            AdminStringKeys.SupplierFieldCity,
            AdminStringKeys.SupplierFieldCountry,
            AdminStringKeys.SupplierFieldEmail,
            AdminStringKeys.SupplierFieldPhone,
            AdminStringKeys.SupplierFieldCreditorNo,
            AdminStringKeys.SupplierFieldAccountNo,
            AdminStringKeys.SupplierFieldBuCode,
            AdminStringKeys.SupplierFieldCostCenter,
            AdminStringKeys.SupplierFieldPaymentTerms,
            AdminStringKeys.SupplierFieldNotes,
            AdminStringKeys.SupplierNoIbans,
            AdminStringKeys.SupplierIbansExplainer,
            AdminStringKeys.SupplierProvisionalExplainer,
            AdminStringKeys.SupplierConfirmAction,
            AdminStringKeys.SupplierErrorNameRequired,
            AdminStringKeys.SupplierErrorVatIdTaken,
            AdminStringKeys.SuppliersSearchPlaceholder,
            AdminStringKeys.SuppliersCount,
            AdminStringKeys.SuppliersEmptyFiltered,
            AdminStringKeys.SuppliersEmptyHint,
            AdminStringKeys.CommonResetFilters,
        )

    @Test
    fun `every supplier key is translated in all three languages`() {
        val missing =
            supplierKeys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated supplier keys: $missing")
    }
}
