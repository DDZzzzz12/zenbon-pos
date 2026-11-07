package org.example.project.admin.features.salesinvoices

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.admin.features.salesinvoices.presentation.formatInvoiceQuantity
import org.example.project.admin.features.salesinvoices.presentation.grossAmount
import org.example.project.admin.features.salesinvoices.presentation.salesInvoiceStatusLabelKey
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.money.Decimal
import org.example.project.ui.components.formatMoneyForDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** QA admin16 NOTE-3: the detail panel showed the raw status ("ISSUED") and no lines or VAT split. */
class SalesInvoiceDetailDisplayTest {
    @Test
    fun every_status_has_a_label_in_all_three_languages() {
        SalesInvoiceStatus.entries.forEach { status ->
            val key = assertNotNull(salesInvoiceStatusLabelKey(status.name), "no label for $status")
            listOf(EnglishAdminStrings.strings, GermanAdminStrings.strings, ChineseAdminStrings.strings).forEach { table ->
                assertTrue(table[key]?.isNotBlank() == true, "$status untranslated")
            }
        }
        assertEquals("Ausgestellt", GermanAdminStrings.strings[AdminStringKeys.SalesInvoicesStatusIssued])
    }

    @Test
    fun an_unknown_status_has_no_label_so_the_raw_value_shows() = assertNull(salesInvoiceStatusLabelKey("ARCHIVED"))

    @Test
    fun quantities_drop_trailing_zeros_and_use_a_decimal_comma() {
        assertEquals("2", formatInvoiceQuantity("2.000"))
        assertEquals("1,5", formatInvoiceQuantity("1.500"))
        assertEquals("0,125", formatInvoiceQuantity("0.125"))
        assertEquals("abc", formatInvoiceQuantity("abc"))
    }

    @Test
    fun a_vat_row_gross_is_net_plus_vat_exactly() {
        val row = SalesInvoiceDto.VatRowDto(vatRate = "19.00", vatCategoryCode = "S", taxableAmount = "10.0000", vatAmount = "1.9000")
        assertEquals(0, Decimal.parse("11.90").compareTo(Decimal.parse(row.grossAmount())))
        assertEquals("11,90 €", formatMoneyForDisplay(row.grossAmount(), "EUR"))
    }

    @Test
    fun an_unparseable_vat_part_falls_back_to_the_net() {
        val row = SalesInvoiceDto.VatRowDto(vatRate = "7.00", vatCategoryCode = "S", taxableAmount = "5.00", vatAmount = "x")
        assertEquals("5.00", row.grossAmount())
    }
}
