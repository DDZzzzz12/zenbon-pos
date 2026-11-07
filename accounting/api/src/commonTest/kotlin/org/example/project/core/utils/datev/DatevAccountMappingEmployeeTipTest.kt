package org.example.project.core.utils.datev

import org.example.project.core.domain.dto.datev.DatevAccountMappingDto.AccountCell
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** F51: the employee-tip slot's defaults, its read-side backfill and the kind that selects it. */
class DatevAccountMappingEmployeeTipTest {
    @Test
    fun `both charts seed Durchlaufende Posten for employee tips`() {
        assertEquals(1590, DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList()).special[KassenbuchSpecialBooking.EMPLOYEE_TIP]?.konto)
        assertEquals(1370, DatevAccountMappingDefaults.seed(Kontenrahmen.SKR04, emptyList()).special[KassenbuchSpecialBooking.EMPLOYEE_TIP]?.konto)
    }

    @Test
    fun `a mapping stored without the slot gets its chart's default`() {
        listOf(Kontenrahmen.SKR03 to 1590, Kontenrahmen.SKR04 to 1370).forEach { (chart, konto) ->
            val seed = DatevAccountMappingDefaults.seed(chart, emptyList())
            val stored = seed.copy(special = seed.special - KassenbuchSpecialBooking.EMPLOYEE_TIP)
            val read = DatevAccountMappingDefaults.withBackfilledDefaults(stored)
            assertEquals(konto, read.special[KassenbuchSpecialBooking.EMPLOYEE_TIP]?.konto, "$chart")
            assertEquals(stored.special, read.special - KassenbuchSpecialBooking.EMPLOYEE_TIP, "nothing else may change")
        }
    }

    @Test
    fun `the backfill never overrides an operator's account and leaves a complete mapping untouched`() {
        val seed = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())
        val own = seed.copy(special = seed.special + (KassenbuchSpecialBooking.EMPLOYEE_TIP to AccountCell(1755, "40")))
        assertSame(own, DatevAccountMappingDefaults.withBackfilledDefaults(own))
    }

    @Test
    fun `only the employee-tip slot is backfilled - a stored mapping's other gaps are never filled`() {
        assertEquals(setOf(KassenbuchSpecialBooking.EMPLOYEE_TIP), DatevAccountMappingDefaults.BACKFILLED_SPECIAL_SLOTS)
        // Both seeds now fill all nine slots, but a stored mapping is never rewritten: a slot the
        // seed carries and the stored mapping lacks stays missing (and `validate()` names it).
        Kontenrahmen.entries.forEach { chart ->
            val seed = DatevAccountMappingDefaults.seed(chart, emptyList())
            assertEquals(KassenbuchSpecialBooking.entries.toSet(), seed.special.keys, "$chart seeds every slot")
            val read = DatevAccountMappingDefaults.withBackfilledDefaults(seed.copy(special = emptyMap()))
            assertEquals(setOf(KassenbuchSpecialBooking.EMPLOYEE_TIP), read.special.keys, "$chart")
            assertTrue(read.validate().any { it.contains("special.CASH_SURPLUS") }, "$chart: ${read.validate()}")
        }
    }

    @Test
    fun `the EMPLOYEE_TIP kind is system-only, rate-less and selects the special slot`() {
        val kind = KassenbuchLineKind.EMPLOYEE_TIP
        assertEquals(KassenbuchSpecialBooking.EMPLOYEE_TIP, kind.specialBooking)
        assertEquals(false, kind.allowedInManualEntry)
        assertEquals(false, kind.requiresVatRate)
        assertEquals(false, kind.resolvesByPaymentType)
    }

    @Test
    fun `a rate-table cell for a special-booking kind is refused`() {
        val seed = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())
        val bad = seed.copy(income = seed.income + (KassenbuchLineKind.EMPLOYEE_TIP to mapOf("0" to AccountCell(8100))))
        assertTrue(bad.validate().any { it.contains("EMPLOYEE_TIP is mapped via special") }, "got ${bad.validate()}")
    }
}
