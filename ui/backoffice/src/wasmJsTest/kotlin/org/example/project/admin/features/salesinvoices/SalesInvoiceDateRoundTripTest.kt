package org.example.project.admin.features.salesinvoices

import org.example.project.admin.features.salesinvoices.presentation.dateOnly
import org.example.project.admin.features.salesinvoices.presentation.withMidnight
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The editor stores `yyyy-MM-ddTHH:mm`; the date field speaks plain dates.
 *
 * The midnight suffix is therefore stripped on the way in and put back on the way out, and the pair
 * has to survive a round trip — a date the operator picked must reach the server as the date they
 * saw, not one hour or one day off it.
 */
class SalesInvoiceDateRoundTripTest {
    @Test
    fun `a stored datetime shows as its date and goes back unchanged`() {
        val stored = "2026-08-16T00:00"

        val shown = stored.dateOnly()

        assertEquals("2026-08-16", shown)
        assertEquals(stored, shown.withMidnight())
    }

    /**
     * Clearing the field must clear the value, not produce `"T00:00"`.
     *
     * A bare suffix is not an empty date but a broken one: the server would reject it as
     * unparseable rather than read it as "not stated", and the operator would be told their invoice
     * is invalid for a field they deliberately left blank.
     */
    @Test
    fun `a cleared field stays empty rather than becoming a bare time`() {
        assertEquals("", "".withMidnight())
        assertEquals("", "   ".trim().withMidnight())
    }

    @Test
    fun `a date the picker wrote needs no suffix of its own`() {
        assertEquals("2026-09-10T00:00", "2026-09-10".withMidnight())
    }

    /** A value that never carried a time is already its own date. */
    @Test
    fun `a plain date survives dateOnly untouched`() {
        assertEquals("2026-09-10", "2026-09-10".dateOnly())
        assertEquals("", "".dateOnly())
    }
}
