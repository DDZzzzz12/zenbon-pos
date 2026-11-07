package org.example.project.core.utils.datev

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.datev.CellRef
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto.AccountCell
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto.MappingDto
import org.example.project.core.domain.dto.datev.UnassignedBooking
import org.example.project.core.domain.dto.datev.UnassignedSummary
import org.example.project.core.domain.enums.datev.DatevMappingSide
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * „Konto automatisch zuordnen": the per-cell switch, how a mapping stored before it existed reads,
 * and what [MappingDto.validate] demands of an ON vs an OFF cell.
 */
class DatevAccountMappingAutoAssignTest {
    private val seed = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())

    /** What the save route writes: `Json.encodeToString` with the library defaults (`encodeDefaults = false`). */
    private val storeJson = Json

    /** What `ShopConfigurationManager` reads with (the server's AppJson). */
    private val readJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }

    /**
     * A DATEV_ACCOUNT_MAPPING blob exactly as the save route stored it before the switch existed —
     * no `autoAssign` key anywhere, `bu` present only where set (that build's `encodeDefaults = false`
     * omitted a null BU).
     */
    private val preSwitchBlob =
        """
        {"kontenrahmen":"SKR03","kasseKonto":1000,
         "income":{"STANDARD":{"19":{"konto":8400},"7":{"konto":8300},"0":{"konto":8100}},
                   "PFAND":{"19":{"konto":8400}},
                   "GUTSCHEIN":{"19":{"konto":8720},"7":{"konto":8710,"bu":"2"}}},
         "expense":{"STANDARD":{"19":{"konto":3400},"7":{"konto":3300},"0":{"konto":3200}}},
         "paymentTypes":{"2":{"konto":1360},"5":{"konto":1700}},
         "special":{"GELDTRANSIT":{"konto":1360},"CASH_DEPOSIT":{"konto":9000},"PRIVATE_DEPOSIT":{"konto":1890},
                    "CASH_WITHDRAWAL":{"konto":9000},"PRIVATE_WITHDRAWAL":{"konto":1800},"CASH_SURPLUS":{"konto":8603},
                    "CASH_SHORTAGE":{"konto":4900},"BALANCE_CARRY_FORWARD":{"konto":9000},"EMPLOYEE_TIP":{"konto":1590}}}
        """.trimIndent()

    @Test
    fun `a mapping stored before the switch existed reads with every cell ON`() {
        val read = readJson.decodeFromString(MappingDto.serializer(), preSwitchBlob)
        val cells = read.income.values.flatMap { it.values } + read.expense.values.flatMap { it.values } + read.paymentTypes.values + read.special.values
        assertEquals(20, cells.size)
        assertTrue(cells.all { it.autoAssign }, "$cells")
        assertEquals(AccountCell(8710, "2"), read.income.getValue(KassenbuchLineKind.GUTSCHEIN).getValue("7"))
        assertTrue(read.validate().isEmpty(), "${read.validate()}")
    }

    @Test
    fun `an all-ON mapping stores byte-identically to before - no autoAssign key is written`() {
        val stored = storeJson.encodeToString(MappingDto.serializer(), seed)
        assertFalse("autoAssign" in stored, stored)
        assertEquals(seed, readJson.decodeFromString(MappingDto.serializer(), stored))
    }

    @Test
    fun `a switched-off cell round-trips, empty or not`() {
        val off =
            seed.copy(
                special =
                    seed.special +
                        (KassenbuchSpecialBooking.CASH_SURPLUS to AccountCell(konto = null, autoAssign = false)) +
                        (KassenbuchSpecialBooking.CASH_SHORTAGE to AccountCell(konto = 4900, bu = "3", autoAssign = false)),
            )
        val stored = storeJson.encodeToString(MappingDto.serializer(), off)
        assertTrue("\"autoAssign\":false" in stored, stored)
        assertEquals(off, readJson.decodeFromString(MappingDto.serializer(), stored))
    }

    @Test
    fun `a switched-off cell may be empty and its stale account is not checked`() {
        val off =
            seed.copy(
                income = mapOf(KassenbuchLineKind.STANDARD to mapOf("7" to AccountCell(konto = null, autoAssign = false))),
                expense = mapOf(KassenbuchLineKind.STANDARD to mapOf("19" to AccountCell(konto = 0, bu = "12345", autoAssign = false))),
                paymentTypes = mapOf(3L to AccountCell(konto = null, autoAssign = false)),
                special = KassenbuchSpecialBooking.entries.associateWith { AccountCell(konto = null, autoAssign = false) },
            )
        assertEquals(emptyList(), off.validate())
        assertEquals(emptyList(), off.autoAssignedCellsWithoutAccount())
    }

    @Test
    fun `an ON cell must still carry an account`() {
        val emptyOn =
            seed.copy(
                income = mapOf(KassenbuchLineKind.STANDARD to mapOf("7" to AccountCell(konto = null))),
                special = seed.special + (KassenbuchSpecialBooking.CASH_SURPLUS to AccountCell(konto = null)),
            )
        val errors = emptyOn.validate()
        assertTrue(errors.any { it.startsWith("income.STANDARD.7:") }, "$errors")
        assertTrue(errors.any { it.startsWith("special.CASH_SURPLUS:") }, "$errors")
        assertEquals(listOf("income.STANDARD.7", "special.CASH_SURPLUS"), emptyOn.autoAssignedCellsWithoutAccount())
    }

    @Test
    fun `a missing special slot is still refused - switching off is a decision, absence is not`() {
        val gap = seed.copy(special = seed.special - KassenbuchSpecialBooking.CASH_SURPLUS)
        assertTrue(gap.validate().any { it.contains("special.CASH_SURPLUS") })
    }

    @Test
    fun `the export notice groups switched-off lines per cell`() {
        val seven = CellRef(side = DatevMappingSide.INCOME, kind = KassenbuchLineKind.STANDARD, rate = "7")
        val surplus = CellRef(special = KassenbuchSpecialBooking.CASH_SURPLUS)
        val summary = UnassignedSummary.of(listOf(seven, surplus, seven))
        assertEquals(3, summary?.lineCount)
        assertEquals(
            listOf(UnassignedBooking(seven, 2), UnassignedBooking(surplus, 1)),
            summary?.bookings,
        )
        assertEquals(null, UnassignedSummary.of(emptyList()))
    }
}
