package org.example.project.export.datevxml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatevXmlFieldValidatorTest {
    @Test
    fun `MAX_BU_CODE allows 4-digit codes per XSD p10033`() {
        // XSD type p10033 allows 0-9999 (up to 4 digits)
        assertEquals(4, DatevXmlFieldValidator.MAX_BU_CODE)
    }

    @Test
    fun `requireValidAmount parses positive decimal`() {
        val result = DatevXmlFieldValidator.requireValidAmount("1190.00", "test")
        assertEquals("1190.00", result.toPlainString())
    }

    @Test
    fun `requireValidAmount parses negative decimal for credit notes`() {
        val result = DatevXmlFieldValidator.requireValidAmount("-1190.00", "test")
        assertEquals("-1190.00", result.toPlainString())
    }

    @Test
    fun `requireValidAmount parses comma-separated decimal`() {
        val result = DatevXmlFieldValidator.requireValidAmount("1190,50", "test")
        assertEquals("1190.50", result.toPlainString())
    }

    @Test
    fun `requireValidAmount rejects zero amount`() {
        assertFailsWith<IllegalArgumentException> {
            DatevXmlFieldValidator.requireValidAmount("0.00", "test")
        }
    }

    @Test
    fun `requireValidAmount rejects unparseable string`() {
        assertFailsWith<IllegalArgumentException> {
            DatevXmlFieldValidator.requireValidAmount("abc", "test")
        }
    }
}
