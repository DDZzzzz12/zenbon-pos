package org.example.project.features.settings

import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.settings.presentation.components.datev.datevPaymentCellKey
import org.example.project.features.settings.presentation.components.datev.datevRateCellKey
import org.example.project.features.settings.presentation.components.datev.datevSpecialCellKey
import org.example.project.features.settings.presentation.components.datev.displayPaymentTypeName
import org.example.project.features.settings.presentation.components.datev.missingKontoCells
import org.example.project.features.settings.presentation.components.datev.missingKontoCount
import org.example.project.features.settings.presentation.state.DatevAccountMappingCellState
import org.example.project.features.settings.presentation.state.DatevAccountMappingPaymentRow
import org.example.project.features.settings.presentation.state.DatevAccountMappingState
import kotlin.test.Test
import kotlin.test.assertEquals

class DatevMappingMissingTest {
    private fun filled() = DatevAccountMappingCellState(konto = "8400")

    private fun fullState(): DatevAccountMappingState {
        val rates =
            mapOf(
                KassenbuchLineKind.STANDARD to mapOf("19" to filled(), "7" to filled(), "0" to filled()),
                KassenbuchLineKind.PFAND to mapOf("19" to filled()),
                KassenbuchLineKind.GUTSCHEIN to mapOf("19" to filled(), "7" to filled()),
            )
        return DatevAccountMappingState(
            income = rates,
            expense = rates,
            paymentTypes = listOf(DatevAccountMappingPaymentRow(1L, "BAR", false, filled())),
            special = KassenbuchSpecialBooking.entries.associateWith { filled() },
        )
    }

    @Test
    fun `a complete mapping has nothing missing`() {
        assertEquals(0, missingKontoCount(fullState()))
    }

    @Test
    fun `an empty state counts every cell as missing`() {
        // income 6 + expense 6 + 9 specials, no payment types
        assertEquals(6 + 6 + KassenbuchSpecialBooking.entries.size, missingKontoCount(DatevAccountMappingState()))
    }

    @Test
    fun `rate cells payment types and specials are all counted in display order`() {
        val full = fullState()
        val state =
            full.copy(
                income = full.income + (KassenbuchLineKind.STANDARD to mapOf("19" to filled(), "7" to DatevAccountMappingCellState(), "0" to filled())),
                expense = full.expense + (KassenbuchLineKind.GUTSCHEIN to mapOf("19" to filled(), "7" to DatevAccountMappingCellState(konto = " "))),
                paymentTypes = listOf(DatevAccountMappingPaymentRow(7L, "EC-KARTE", false, DatevAccountMappingCellState())),
                special = full.special + (KassenbuchSpecialBooking.GELDTRANSIT to DatevAccountMappingCellState()),
            )
        assertEquals(
            listOf(
                datevRateCellKey(true, KassenbuchLineKind.STANDARD, "7"),
                datevRateCellKey(false, KassenbuchLineKind.GUTSCHEIN, "7"),
                datevPaymentCellKey(7L),
                datevSpecialCellKey(KassenbuchSpecialBooking.GELDTRANSIT),
            ),
            missingKontoCells(state),
        )
    }

    @Test
    fun `a blank cell with automatic assignment off is not missing`() {
        val full = fullState()
        val state = full.copy(special = full.special + (KassenbuchSpecialBooking.CASH_SHORTAGE to DatevAccountMappingCellState(autoAssign = false)))
        assertEquals(0, missingKontoCount(state))
    }

    @Test
    fun `an all upper-case payment type name is capitalised per word`() {
        assertEquals("EC-Karte", displayPaymentTypeName("EC-KARTE"))
        assertEquals("Bar", displayPaymentTypeName("BAR"))
        assertEquals("Kredit Karte", displayPaymentTypeName("KREDIT KARTE"))
        assertEquals("Ärzte", displayPaymentTypeName("ÄRZTE"))
    }

    @Test
    fun `mixed case and caseless names are untouched`() {
        assertEquals("PayPal", displayPaymentTypeName("PayPal"))
        assertEquals("Bar", displayPaymentTypeName("Bar"))
        assertEquals("现金", displayPaymentTypeName("现金"))
        assertEquals("123", displayPaymentTypeName("123"))
        assertEquals("", displayPaymentTypeName(""))
    }
}
