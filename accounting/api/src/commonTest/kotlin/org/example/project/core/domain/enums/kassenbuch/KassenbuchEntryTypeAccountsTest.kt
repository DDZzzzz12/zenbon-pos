package org.example.project.core.domain.enums.kassenbuch

import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the partition that let the hardcoded account tables be deleted.
 *
 * `KassenbuchEntryType` used to carry `getDefaultAccountNumber()` / `getDefaultCounterAccountNumber()`
 * — a fixed SKR03/SKR04 mix that also put the *contra* account in `Konto` for five entry types,
 * which the exporter's cash-flow sign then inverted (a cash purchase raised the till). Those tables
 * are gone; every entry type now resolves through the shop's mapping, via either the rate tables
 * ([KassenbuchEntryType.mappingSide]) or the special slots ([KassenbuchEntryType.specialBooking]).
 * If that partition ever develops a hole, a line silently becomes unexportable instead.
 */
class KassenbuchEntryTypeAccountsTest {
    @Test
    fun `every entry type resolves through exactly one axis`() {
        for (type in KassenbuchEntryType.entries) {
            val hasSide = type.mappingSide != null
            val hasSpecial = type.specialBooking != null
            assertTrue(
                hasSide != hasSpecial,
                "$type must have exactly one of mappingSide/specialBooking " +
                    "(mappingSide=${type.mappingSide}, specialBooking=${type.specialBooking})",
            )
        }
    }

    @Test
    fun `the SKR03 seed fills every special slot and validates`() {
        val seed = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())
        assertEquals(
            KassenbuchSpecialBooking.entries.toSet(),
            seed.special.keys,
            "SKR03 carries the previous constants forward, so no slot may be left unseeded",
        )
        assertEquals(emptyList(), seed.validate())
        assertEquals(1000, seed.kasseKonto)
    }

    @Test
    fun `the SKR04 seed fills every special slot and validates`() {
        val seed = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR04, emptyList())
        assertEquals(1600, seed.kasseKonto, "SKR04 Kasse is 1600, not SKR03's 1000")
        assertEquals(
            KassenbuchSpecialBooking.entries.toSet(),
            seed.special.keys,
            "SKR04 mirrors SKR03 one to one, so no slot may be left unseeded",
        )
        assertEquals(emptyList(), seed.validate())
        // The five slots seeded 2026-10-06 against DATEV SKR04 2026 (Art.-Nr. 11175).
        val special = seed.special
        assertEquals(9000, special[KassenbuchSpecialBooking.CASH_DEPOSIT]?.konto, "SKR04 9000 Saldenvorträge, Sachkonten")
        assertEquals(9000, special[KassenbuchSpecialBooking.CASH_WITHDRAWAL]?.konto, "SKR04 9000 Saldenvorträge, Sachkonten")
        assertEquals(9000, special[KassenbuchSpecialBooking.BALANCE_CARRY_FORWARD]?.konto, "SKR04 9000 Saldenvorträge, Sachkonten")
        assertEquals(4830, special[KassenbuchSpecialBooking.CASH_SURPLUS]?.konto, "SKR04 4830 Sonstige betriebliche Erträge")
        assertEquals(6300, special[KassenbuchSpecialBooking.CASH_SHORTAGE]?.konto, "SKR04 6300 Sonstige betriebliche Aufwendungen")
    }

    @Test
    fun `a mapping missing a special slot fails validation until it is filled again`() {
        val base = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR04, emptyList())
        val cleared = base.copy(special = base.special - KassenbuchSpecialBooking.CASH_SURPLUS)
        val errors = cleared.validate()
        assertTrue(
            errors.any { it.contains("special.CASH_SURPLUS") },
            "a missing slot must be named individually; got $errors",
        )
        val filled =
            cleared.copy(special = cleared.special + (KassenbuchSpecialBooking.CASH_SURPLUS to DatevAccountMappingDto.AccountCell(4830)))
        assertEquals(emptyList(), filled.validate())
    }

    /**
     * F60: the private accounts against DATEV's 2026 charts (SKR03 Art.-Nr. 11174, SKR04
     * Art.-Nr. 11175). SKR03 used to seed PRIVATE_WITHDRAWAL to 1890 — Privat*einlagen* — so every
     * withdrawal was booked as a deposit; PRIVATE_DEPOSIT sat on 9000, the Saldenvorträge.
     */
    @Test
    fun `private deposit and withdrawal seed DATEV's Privateinlagen and Privatentnahmen accounts`() {
        val skr03 = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList()).special
        assertEquals(1890, skr03[KassenbuchSpecialBooking.PRIVATE_DEPOSIT]?.konto, "SKR03 1890 Privateinlagen")
        assertEquals(1800, skr03[KassenbuchSpecialBooking.PRIVATE_WITHDRAWAL]?.konto, "SKR03 1800 Privatentnahmen allgemein")
        val skr04 = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR04, emptyList()).special
        assertEquals(2180, skr04[KassenbuchSpecialBooking.PRIVATE_DEPOSIT]?.konto, "SKR04 2180 Privateinlagen")
        assertEquals(2100, skr04[KassenbuchSpecialBooking.PRIVATE_WITHDRAWAL]?.konto, "SKR04 2100 Privatentnahmen allgemein")
    }

    /** F60: SKR03 2110 is Zinsaufwendungen and 6880 does not exist in SKR03 — neither is a cash difference. */
    @Test
    fun `SKR03 cash differences seed the generic sonstige betriebliche accounts`() {
        val skr03 = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList()).special
        assertEquals(8603, skr03[KassenbuchSpecialBooking.CASH_SURPLUS]?.konto, "SKR03 8603 Sonstige betriebliche Erträge")
        assertEquals(4900, skr03[KassenbuchSpecialBooking.CASH_SHORTAGE]?.konto, "SKR03 4900 Sonstige betriebliche Aufwendungen")
    }
}
