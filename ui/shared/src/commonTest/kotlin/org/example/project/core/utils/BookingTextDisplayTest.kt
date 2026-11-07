package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class BookingTextDisplayTest {
    @Test
    fun `the technical tip payout tag is not shown`() {
        assertEquals("Trinkgeld-Auszahlung Anna", "TA:Trinkgeld-Auszahlung Anna".shownBookingText())
    }

    @Test
    fun `any other booking text is shown as stored`() {
        assertEquals("Einkauf Büro", "Einkauf Büro".shownBookingText())
        assertEquals("Reise TA:Hotel", "Reise TA:Hotel".shownBookingText())
    }
}
