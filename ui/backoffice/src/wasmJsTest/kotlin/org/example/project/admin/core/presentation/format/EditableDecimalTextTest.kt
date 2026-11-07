package org.example.project.admin.core.presentation.format

import kotlin.test.Test
import kotlin.test.assertEquals

class EditableDecimalTextTest {
    @Test
    fun money_keeps_two_places_and_drops_storage_noise() {
        assertEquals("18.00", editableDecimalText("18.0000", 2))
        assertEquals("250.00", editableDecimalText("250.0000", 2))
        assertEquals("7.50", editableDecimalText("7.5000", 2))
        assertEquals("0.125", editableDecimalText("0.1250", 2))
        assertEquals("158780.32", editableDecimalText("158780.3200", 2))
        assertEquals("-3.00", editableDecimalText("-3", 2))
    }

    @Test
    fun quantity_drops_all_trailing_zeros() {
        assertEquals("40", editableDecimalText("40.0000", 0))
        assertEquals("1.5", editableDecimalText("1.5000", 0))
        assertEquals("0.125", editableDecimalText("0.1250", 0))
    }

    @Test
    fun unparseable_and_blank_come_back_unchanged() {
        assertEquals("", editableDecimalText("", 2))
        assertEquals("abc", editableDecimalText("abc", 2))
    }

    @Test
    fun currency_symbol_comes_from_the_money_formatter() {
        assertEquals("€", currencySymbolForDisplay("EUR"))
        assertEquals("€", currencySymbolForDisplay(""))
        assertEquals("CHF", currencySymbolForDisplay("CHF"))
    }
}
