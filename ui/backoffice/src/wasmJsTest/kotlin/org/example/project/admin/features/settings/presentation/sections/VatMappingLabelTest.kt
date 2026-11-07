package org.example.project.admin.features.settings.presentation.sections

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.presentation.strings.translations.ChineseCommonStrings
import org.example.project.core.presentation.strings.translations.EnglishCommonStrings
import org.example.project.core.presentation.strings.translations.GermanCommonStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A product type with a blank label rendered an unlabelled card (VOUCHER: the shared key is "" on purpose). */
class VatMappingLabelTest {
    @Test
    fun everyProductTypeHasANonBlankLabelInAllThreeLanguages() {
        val missing =
            ProductType.entries.flatMap { type ->
                val key = type.vatMappingLabelKey()
                listOf(
                    "de" to (GermanAdminStrings.strings[key] ?: GermanCommonStrings.all[key]),
                    "en" to (EnglishAdminStrings.strings[key] ?: EnglishCommonStrings.all[key]),
                    "zh" to (ChineseAdminStrings.strings[key] ?: ChineseCommonStrings.all[key]),
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: $type" }
            }
        assertTrue(missing.isEmpty(), "Blank VAT-mapping labels: $missing")
    }

    @Test
    fun voucherUsesTheAdminKeyNotTheBlankSharedOne() {
        assertEquals(AdminStringKeys.SettingsVatMappingTypeVoucher, ProductType.VOUCHER.vatMappingLabelKey())
    }
}
