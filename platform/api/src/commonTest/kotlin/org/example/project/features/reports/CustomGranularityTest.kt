package org.example.project.features.reports

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setup.Language
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.formatAnchor
import org.example.project.features.reports.presentation.isCurrentPeriod
import org.example.project.features.reports.presentation.stepped
import org.example.project.features.reports.presentation.windowFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CustomGranularityTest {
    private val anchor = LocalDate(2026, 3, 12)
    private val now = LocalDateTime(2026, 3, 12, 10, 30)

    // CUSTOM has no anchored period, so windowFor cannot answer. It must fail loudly rather
    // than silently returning a wrong window — Berichte never passes CUSTOM, and Documents
    // holds its own from/to instead of calling this.
    @Test
    fun windowFor_custom_throws() {
        assertFailsWith<IllegalStateException> {
            ReportsGranularity.CUSTOM.windowFor(anchor, now)
        }
        Unit
    }

    @Test
    fun stepped_custom_throws() {
        assertFailsWith<IllegalStateException> {
            ReportsGranularity.CUSTOM.stepped(anchor, -1)
        }
        Unit
    }

    // These two are reached by the shared stepper before the caller can branch, so they must
    // return a harmless value rather than throw.
    @Test
    fun isCurrentPeriod_custom_isFalse() {
        assertFalse(ReportsGranularity.CUSTOM.isCurrentPeriod(anchor, anchor))
    }

    @Test
    fun formatAnchor_custom_isEmpty() {
        assertEquals("", ReportsGranularity.CUSTOM.formatAnchor(anchor, Language.GERMAN))
    }

    // The reports screen must keep exactly its four segments.
    @Test
    fun reportingGranularities_excludeCustom() {
        assertEquals(
            listOf(
                ReportsGranularity.DAY,
                ReportsGranularity.WEEK,
                ReportsGranularity.MONTH,
                ReportsGranularity.YEAR,
            ),
            ReportsGranularity.reporting,
        )
    }
}
