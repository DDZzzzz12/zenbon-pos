package org.example.project.ui.components

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.utils.printLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * QA emu22 NOTE-gg: the print and the screen label of a chosen option are two spellings of one
 * structure. Paper and PDF print „Groß  (1,50)“ (German comma since F63, no currency — CP850 has
 * no €); a screen shows „Groß (+1,50 €)“. Same quantity
 * prefix, same zero/unparseable suppression — only the delta's spelling differs.
 */
class SelectedOptionLabelTest {
    private fun option(
        delta: String,
        quantity: Int = 1,
    ) = OrderItemDto.SelectedOptionDto(optionId = 1, name = "Groß", priceDelta = delta, quantity = quantity)

    @Test
    fun `print uses the receipt's German comma`() {
        assertEquals("Groß  (1,50)", option("1.5000").printLabel())
        assertEquals("2× Groß  (-0,50)", option("-0.5", quantity = 2).printLabel())
    }

    @Test
    fun `screen uses the money format with a sign, the currency and single spacing`() {
        assertEquals("Groß (+1,50 €)", option("1.5000").screenLabel("EUR"))
        assertEquals("2× Groß (-0,50 €)", option("-0.5", quantity = 2).screenLabel("EUR"))
    }

    @Test
    fun `both suppress a zero or unparseable delta identically`() {
        listOf("0.0000", "", "abc").forEach { delta ->
            assertEquals("Groß", option(delta).printLabel())
            assertEquals("Groß", option(delta).screenLabel("EUR"))
        }
    }
}
