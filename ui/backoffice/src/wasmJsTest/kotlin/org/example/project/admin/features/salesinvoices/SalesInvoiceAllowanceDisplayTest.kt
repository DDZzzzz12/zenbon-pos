package org.example.project.admin.features.salesinvoices

import org.example.project.admin.features.salesinvoices.presentation.effectOnTotal
import org.example.project.admin.features.salesinvoices.presentation.label
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals

class SalesInvoiceAllowanceDisplayTest {
    private fun allowance(
        gross: String,
        isCharge: Boolean = false,
        rate: String = "19.00",
    ) = SalesInvoiceDto.AllowanceChargeDto(
        position = 1,
        isCharge = isCharge,
        amount = "1.94",
        vatAmount = "0.36",
        grossAmount = gross,
        vatCategoryCode = "S",
        vatRate = rate,
        reason = "Aktion: Happy Hour",
        reasonCode = "95",
    )

    private fun assertMoney(
        expected: String,
        actual: String,
    ) = assertEquals(0, Decimal.parse(expected).compareTo(Decimal.parse(actual)), "expected $expected, got $actual")

    @Test
    fun an_allowance_takes_its_gross_off_the_total() = assertMoney("-2.30", allowance("2.3000").effectOnTotal())

    @Test
    fun a_reversed_allowance_on_a_credit_note_gives_it_back() = assertMoney("2.30", allowance("-2.3000").effectOnTotal())

    @Test
    fun a_charge_adds_its_gross() = assertMoney("2.30", allowance("2.30", isCharge = true).effectOnTotal())

    @Test
    fun the_label_names_the_reason_and_the_rate() {
        assertEquals("Aktion: Happy Hour (19 %)", allowance("2.30").label())
        assertEquals("Aktion: Happy Hour (7 %)", allowance("2.30", rate = "7.00").label())
        assertEquals("Aktion: Happy Hour (10 %)", allowance("2.30", rate = "10").label())
    }
}
