package org.example.project.admin.features.voucher

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.admin.features.voucher.presentation.voucherInvalidationSourceKey
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server sends the enum name; an unmapped name must fall back to raw text, never be hidden. */
class VoucherInvalidationSourceLabelTest {
    private val keys: List<StringKey> =
        listOf(
            AdminStringKeys.VouchersInvalidationSourceBillStorno,
            AdminStringKeys.VouchersInvalidationSourceOrderCancelled,
        )

    @Test
    fun `known sources map to their key`() {
        assertEquals(AdminStringKeys.VouchersInvalidationSourceBillStorno, voucherInvalidationSourceKey("BILL_STORNO"))
        assertEquals(AdminStringKeys.VouchersInvalidationSourceOrderCancelled, voucherInvalidationSourceKey("ORDER_CANCELLED"))
    }

    @Test
    fun `unknown and empty sources map to null`() {
        assertNull(voucherInvalidationSourceKey("SOMETHING_NEW"))
        assertNull(voucherInvalidationSourceKey(""))
    }

    @Test
    fun `both keys are translated in all three languages`() {
        val missing =
            keys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated voucher invalidation-source keys: $missing")
    }
}
