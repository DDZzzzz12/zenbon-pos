package org.example.project.utils

import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatevAccountMappingDefaultsTest {
    private val paymentTypes =
        listOf(
            DatevAccountMappingDefaults.PaymentTypeSeed(id = 2L, isVoucher = false), // e.g. CARD
            DatevAccountMappingDefaults.PaymentTypeSeed(id = 5L, isVoucher = true), // GUTSCHEIN
        )

    @Test
    fun `skr03 seed matches spec table`() {
        val m = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, paymentTypes)
        assertEquals(1000, m.kasseKonto)
        assertEquals(
            8400,
            m.income
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("19")
                .konto,
        )
        assertEquals(
            8300,
            m.income
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("7")
                .konto,
        )
        assertEquals(
            8100,
            m.income
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("0")
                .konto,
        )
        assertEquals(
            8400,
            m.income
                .getValue(KassenbuchLineKind.PFAND)
                .getValue("19")
                .konto,
        )
        assertEquals(
            8720,
            m.income
                .getValue(KassenbuchLineKind.GUTSCHEIN)
                .getValue("19")
                .konto,
        )
        assertEquals(
            8710,
            m.income
                .getValue(KassenbuchLineKind.GUTSCHEIN)
                .getValue("7")
                .konto,
        )
        assertEquals(
            3400,
            m.expense
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("19")
                .konto,
        )
        assertEquals(
            3300,
            m.expense
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("7")
                .konto,
        )
        assertEquals(
            3200,
            m.expense
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("0")
                .konto,
        )
        assertEquals(
            3400,
            m.expense
                .getValue(KassenbuchLineKind.PFAND)
                .getValue("19")
                .konto,
        )
        assertEquals(
            3720,
            m.expense
                .getValue(KassenbuchLineKind.GUTSCHEIN)
                .getValue("19")
                .konto,
        )
        assertEquals(
            3710,
            m.expense
                .getValue(KassenbuchLineKind.GUTSCHEIN)
                .getValue("7")
                .konto,
        )
        assertEquals(1360, m.paymentTypes.getValue(2L).konto)
        assertEquals(1700, m.paymentTypes.getValue(5L).konto)
    }

    @Test
    fun `skr04 seed matches spec table`() {
        val m = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR04, paymentTypes)
        assertEquals(1600, m.kasseKonto)
        assertEquals(
            4400,
            m.income
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("19")
                .konto,
        )
        assertEquals(
            4300,
            m.income
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("7")
                .konto,
        )
        assertEquals(
            4100,
            m.income
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("0")
                .konto,
        )
        assertEquals(
            4400,
            m.income
                .getValue(KassenbuchLineKind.PFAND)
                .getValue("19")
                .konto,
        )
        assertEquals(
            4720,
            m.income
                .getValue(KassenbuchLineKind.GUTSCHEIN)
                .getValue("19")
                .konto,
        )
        assertEquals(
            4710,
            m.income
                .getValue(KassenbuchLineKind.GUTSCHEIN)
                .getValue("7")
                .konto,
        )
        assertEquals(
            5400,
            m.expense
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("19")
                .konto,
        )
        assertEquals(
            5300,
            m.expense
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("7")
                .konto,
        )
        assertEquals(
            5200,
            m.expense
                .getValue(KassenbuchLineKind.STANDARD)
                .getValue("0")
                .konto,
        )
        assertEquals(
            5400,
            m.expense
                .getValue(KassenbuchLineKind.PFAND)
                .getValue("19")
                .konto,
        )
        assertEquals(
            5720,
            m.expense
                .getValue(KassenbuchLineKind.GUTSCHEIN)
                .getValue("19")
                .konto,
        )
        assertEquals(
            5710,
            m.expense
                .getValue(KassenbuchLineKind.GUTSCHEIN)
                .getValue("7")
                .konto,
        )
        assertEquals(1460, m.paymentTypes.getValue(2L).konto)
        assertEquals(3500, m.paymentTypes.getValue(5L).konto)
    }

    /**
     * The special slots against DATEV's 2026 charts (SKR03 Art.-Nr. 11174, SKR04 Art.-Nr. 11175).
     * SKR04 mirrors SKR03 one to one; the five SKR04 cells seeded 2026-10-06 used to be blank.
     */
    @Test
    fun `both charts seed every special slot from DATEV's 2026 charts`() {
        val skr03 =
            mapOf(
                KassenbuchSpecialBooking.GELDTRANSIT to 1360,
                KassenbuchSpecialBooking.CASH_DEPOSIT to 9000,
                KassenbuchSpecialBooking.PRIVATE_DEPOSIT to 1890,
                KassenbuchSpecialBooking.CASH_WITHDRAWAL to 9000,
                KassenbuchSpecialBooking.PRIVATE_WITHDRAWAL to 1800,
                KassenbuchSpecialBooking.CASH_SURPLUS to 8603,
                KassenbuchSpecialBooking.CASH_SHORTAGE to 4900,
                KassenbuchSpecialBooking.BALANCE_CARRY_FORWARD to 9000,
                KassenbuchSpecialBooking.EMPLOYEE_TIP to 1590,
            )
        val skr04 =
            mapOf(
                KassenbuchSpecialBooking.GELDTRANSIT to 1460,
                KassenbuchSpecialBooking.CASH_DEPOSIT to 9000, // Saldenvorträge, Sachkonten
                KassenbuchSpecialBooking.PRIVATE_DEPOSIT to 2180,
                KassenbuchSpecialBooking.CASH_WITHDRAWAL to 9000,
                KassenbuchSpecialBooking.PRIVATE_WITHDRAWAL to 2100,
                KassenbuchSpecialBooking.CASH_SURPLUS to 4830, // Sonstige betriebliche Erträge
                KassenbuchSpecialBooking.CASH_SHORTAGE to 6300, // Sonstige betriebliche Aufwendungen
                KassenbuchSpecialBooking.BALANCE_CARRY_FORWARD to 9000,
                KassenbuchSpecialBooking.EMPLOYEE_TIP to 1370,
            )
        listOf(Kontenrahmen.SKR03 to skr03, Kontenrahmen.SKR04 to skr04).forEach { (chart, expected) ->
            val m = DatevAccountMappingDefaults.seed(chart, paymentTypes)
            assertEquals(expected, m.special.mapValues { it.value.konto }, "$chart")
            m.special.values.forEach { assertNull(it.bu, "$chart: no BU key on a special slot") }
            assertEquals(emptyList(), m.validate(), "$chart seed must be storable as-is")
        }
    }

    @Test
    fun `no BU keys are seeded and NON_CASH_PAYMENT has no rate cells`() {
        val m = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, paymentTypes)
        (m.income.values + m.expense.values).flatMap { it.values }.forEach { assertNull(it.bu) }
        m.paymentTypes.values.forEach { assertNull(it.bu) }
        assertNull(m.income[KassenbuchLineKind.NON_CASH_PAYMENT])
        assertNull(m.expense[KassenbuchLineKind.NON_CASH_PAYMENT])
    }
}
