package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class TaxNumberFormatTest {
    @Test
    fun `surrounding whitespace is not part of the identity`() {
        assertEquals("1234567890", TaxNumberFormat.canonical("  1234567890 "))
    }

    @Test
    fun `the tax office's grouping slashes are formatting, so both spellings are one key`() {
        assertEquals(
            TaxNumberFormat.canonical("1234567890"),
            TaxNumberFormat.canonical("12/345/67890"),
        )
    }

    @Test
    fun `spaces and dots group the same number the same way`() {
        assertEquals("1234567890", TaxNumberFormat.canonical("12 345 67890"))
        assertEquals("1234567890", TaxNumberFormat.canonical("12.345.67890"))
    }

    @Test
    fun `letters survive, upper-cased, because not every tax number is digits`() {
        assertEquals("GB12AB34", TaxNumberFormat.canonical("gb12-ab34"))
    }

    @Test
    fun `a value that is only separators canonicalizes to empty, which callers read as absent`() {
        assertEquals("", TaxNumberFormat.canonical(" // "))
    }
}
