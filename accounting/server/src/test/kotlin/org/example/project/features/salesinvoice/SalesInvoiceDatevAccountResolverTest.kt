package org.example.project.features.salesinvoice

import org.example.project.core.domain.dto.datev.CellRef
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.enums.datev.DatevMappingSide
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.salesinvoice.data.service.SalesInvoiceDatevAccountResolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [SalesInvoiceDatevAccountResolver] — pure, no DB.
 *
 * Fixture mapping mirrors the shape [org.example.project.features.kassenbuch.data.service.KassenbuchAccountResolver]
 * already relies on: `income[STANDARD]["19"]` (canonical [org.example.project.core.utils.datev.VatRateNormalizer]
 * key — "19.00" normalizes to "19").
 */
class SalesInvoiceDatevAccountResolverTest {
    companion object {
        private const val STANDARD_RATE_RAW = "19.00"
        private const val STANDARD_RATE_NORMALIZED = "19"

        private val mapping =
            DatevAccountMappingDto.MappingDto(
                kontenrahmen = Kontenrahmen.SKR03,
                kasseKonto = 1000,
                income =
                    mapOf(
                        KassenbuchLineKind.STANDARD to
                            mapOf(
                                STANDARD_RATE_NORMALIZED to
                                    DatevAccountMappingDto.AccountCell(konto = 8400, bu = null),
                            ),
                    ),
            )
    }

    // ---- personenkonto ----

    @Test
    fun `personenkonto prefers the booking value over debtorNo and Sammeldebitor`() {
        val result =
            SalesInvoiceDatevAccountResolver.personenkonto(
                bookingBp = "70001",
                debtorNo = "70002",
                sammeldebitor = "70009",
            )
        assertEquals("70001", result)
    }

    @Test
    fun `personenkonto falls back to debtorNo when booking value is absent`() {
        val result =
            SalesInvoiceDatevAccountResolver.personenkonto(
                bookingBp = null,
                debtorNo = "70002",
                sammeldebitor = "70009",
            )
        assertEquals("70002", result)
    }

    @Test
    fun `personenkonto falls back to Sammeldebitor when booking and debtorNo are both absent`() {
        val result =
            SalesInvoiceDatevAccountResolver.personenkonto(
                bookingBp = null,
                debtorNo = null,
                sammeldebitor = "70009",
            )
        assertEquals("70009", result)
    }

    @Test
    fun `personenkonto blank booking and debtorNo values are treated as absent`() {
        val result =
            SalesInvoiceDatevAccountResolver.personenkonto(
                bookingBp = "  ",
                debtorNo = "",
                sammeldebitor = "70009",
            )
        assertEquals("70009", result)
    }

    @Test
    fun `personenkonto is null when booking, debtorNo and Sammeldebitor are all absent`() {
        val result =
            SalesInvoiceDatevAccountResolver.personenkonto(
                bookingBp = null,
                debtorNo = null,
                sammeldebitor = null,
            )
        assertNull(result)
    }

    // ---- erloeskonto ----

    @Test
    fun `erloeskonto prefers the booking value over the mapping`() {
        val result =
            SalesInvoiceDatevAccountResolver.erloeskonto(
                bookingRev = "8100",
                rate = STANDARD_RATE_RAW,
                mapping = mapping,
            )
        assertEquals("8100", result)
    }

    @Test
    fun `erloeskonto falls back to the mapping by normalized rate when booking value is absent`() {
        val result =
            SalesInvoiceDatevAccountResolver.erloeskonto(
                bookingRev = null,
                rate = STANDARD_RATE_RAW,
                mapping = mapping,
            )
        assertEquals("8400", result)
    }

    @Test
    fun `erloeskonto is null for an unmapped rate with no booking value`() {
        val result =
            SalesInvoiceDatevAccountResolver.erloeskonto(
                bookingRev = null,
                rate = "7.00",
                mapping = mapping,
            )
        assertNull(result)
    }

    @Test
    fun `erloeskonto is null when the mapping itself is absent and there is no booking value`() {
        val result =
            SalesInvoiceDatevAccountResolver.erloeskonto(
                bookingRev = null,
                rate = STANDARD_RATE_RAW,
                mapping = null,
            )
        assertNull(result)
    }

    // ---- buCode ----

    @Test
    fun `buCode prefers the booking value over the mapping cell`() {
        val result =
            SalesInvoiceDatevAccountResolver.buCode(
                bookingBu = "9",
                rate = STANDARD_RATE_RAW,
                vatCategoryCode = null,
                mapping = mapping,
            )
        assertEquals("9", result)
    }

    @Test
    fun `buCode falls back to the mapping cell bu when the booking value is absent`() {
        val mappingWithBu =
            mapping.copy(
                income =
                    mapOf(
                        KassenbuchLineKind.STANDARD to
                            mapOf(
                                STANDARD_RATE_NORMALIZED to
                                    DatevAccountMappingDto.AccountCell(konto = 8400, bu = "3"),
                            ),
                    ),
            )
        val result =
            SalesInvoiceDatevAccountResolver.buCode(
                bookingBu = null,
                rate = STANDARD_RATE_RAW,
                vatCategoryCode = null,
                mapping = mappingWithBu,
            )
        assertEquals("3", result)
    }

    @Test
    fun `buCode is null (Automatikkonto) when booking, mapping cell and vatCategoryCode all miss`() {
        val result =
            SalesInvoiceDatevAccountResolver.buCode(
                bookingBu = null,
                rate = STANDARD_RATE_RAW,
                vatCategoryCode = null,
                mapping = mapping,
            )
        assertNull(result)
    }

    @Test
    fun `buCode is null for a K (Kleinunternehmer) vatCategoryCode with no booking or mapping value`() {
        val result =
            SalesInvoiceDatevAccountResolver.buCode(
                bookingBu = null,
                rate = STANDARD_RATE_RAW,
                vatCategoryCode = "K",
                mapping = mapping,
            )
        assertNull(result)
    }

    // ---- „Konto automatisch zuordnen" switched off ----

    @Test
    fun `a switched-off revenue cell yields no account and no BU but is named, not unmapped`() {
        val off =
            mapping.copy(
                income =
                    mapOf(
                        KassenbuchLineKind.STANDARD to
                            mapOf(STANDARD_RATE_NORMALIZED to DatevAccountMappingDto.AccountCell(konto = 8400, bu = "3", autoAssign = false)),
                    ),
            )
        assertNull(SalesInvoiceDatevAccountResolver.erloeskonto(null, STANDARD_RATE_RAW, off))
        assertNull(SalesInvoiceDatevAccountResolver.buCode(null, STANDARD_RATE_RAW, null, off))
        assertEquals(
            CellRef(side = DatevMappingSide.INCOME, kind = KassenbuchLineKind.STANDARD, rate = STANDARD_RATE_NORMALIZED),
            SalesInvoiceDatevAccountResolver.unassignedCell(null, STANDARD_RATE_RAW, off),
        )
        // A revenue account on the booking itself is not automatic, so it still wins.
        assertEquals("8338", SalesInvoiceDatevAccountResolver.erloeskonto("8338", STANDARD_RATE_RAW, off))
        assertNull(SalesInvoiceDatevAccountResolver.unassignedCell("8338", STANDARD_RATE_RAW, off))
        // An ON cell is never reported.
        assertNull(SalesInvoiceDatevAccountResolver.unassignedCell(null, STANDARD_RATE_RAW, mapping))
    }
}
