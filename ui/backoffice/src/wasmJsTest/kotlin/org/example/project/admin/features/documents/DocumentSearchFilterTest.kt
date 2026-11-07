package org.example.project.admin.features.documents

import kotlinx.datetime.LocalDate
import org.example.project.admin.features.documents.presentation.DocumentSearchFilter
import org.example.project.admin.features.documents.presentation.activeAdvancedFilters
import org.example.project.admin.features.documents.presentation.dateBounds
import org.example.project.admin.features.documents.presentation.periodLabel
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.features.reports.presentation.ReportsGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentSearchFilterTest {
    private val march = LocalDate(2026, 3, 12)

    private fun filter(
        granularity: ReportsGranularity = ReportsGranularity.MONTH,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
    ) = DocumentSearchFilter(
        anchor = march,
        granularity = granularity,
        customFrom = customFrom,
        customTo = customTo,
    )

    // The phone result line shows the period; an unpicked Zeitraum must say so, never a fake range.
    @Test
    fun customPeriodLabel_isTheRangeOrEmpty() {
        assertEquals(EMPTY_VALUE, filter(granularity = ReportsGranularity.CUSTOM).periodLabel(Language.GERMAN))
        assertEquals(
            "2026-01-05 – 2026-02-20",
            filter(ReportsGranularity.CUSTOM, LocalDate(2026, 1, 5), LocalDate(2026, 2, 20)).periodLabel(Language.GERMAN),
        )
    }

    // The documents endpoint parses date-only bounds, so the window's time component is stripped.
    @Test
    fun monthGranularity_yieldsCalendarMonthBounds() {
        assertEquals("2026-03-01" to "2026-03-31", filter().dateBounds())
    }

    @Test
    fun yearGranularity_yieldsCalendarYearBounds() {
        assertEquals(
            "2026-01-01" to "2026-12-31",
            filter(granularity = ReportsGranularity.YEAR).dateBounds(),
        )
    }

    @Test
    fun dayGranularity_yieldsTheAnchorItself() {
        assertEquals("2026-03-12" to "2026-03-12", filter(granularity = ReportsGranularity.DAY).dateBounds())
    }

    // 2026-03-12 is a Thursday; the ISO week snaps to its Monday–Sunday span regardless of anchor day.
    @Test
    fun weekGranularity_yieldsCalendarWeekBounds() {
        assertEquals(
            "2026-03-09" to "2026-03-15",
            filter(granularity = ReportsGranularity.WEEK).dateBounds(),
        )
    }

    @Test
    fun customWithBothEnds_yieldsThoseEnds() {
        assertEquals(
            "2024-03-01" to "2025-06-30",
            filter(
                granularity = ReportsGranularity.CUSTOM,
                customFrom = LocalDate(2024, 3, 1),
                customTo = LocalDate(2025, 6, 30),
            ).dateBounds(),
        )
    }

    // An empty custom range is the "show everything" state: no date filter is sent at all.
    @Test
    fun customWithNoRange_yieldsNoBounds() {
        assertNull(filter(granularity = ReportsGranularity.CUSTOM).dateBounds())
    }

    @Test
    fun blankAdvancedFieldsAreNotActive() {
        assertEquals(emptyList(), filter().copy(vendorName = "   ").activeAdvancedFilters())
    }

    @Test
    fun activeAdvancedFilters_listsOnlyPopulatedFields() {
        val f = filter().copy(vendorName = "ACME GmbH", minAmount = "100.00", bankName = null)
        assertEquals(
            listOf(
                DocumentSearchFilter.AdvancedField.VENDOR_NAME to "ACME GmbH",
                DocumentSearchFilter.AdvancedField.MIN_AMOUNT to "100.00",
            ),
            f.activeAdvancedFilters(),
        )
    }

    @Test
    fun clearing_removesOnlyThatField() {
        val f = filter().copy(vendorName = "ACME GmbH", invoiceNumber = "RE-1")
        val cleared = f.clear(DocumentSearchFilter.AdvancedField.VENDOR_NAME)
        assertNull(cleared.vendorName)
        assertEquals("RE-1", cleared.invoiceNumber)
    }
}
