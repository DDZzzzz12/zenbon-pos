package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CountryCodeNormalizerTest {
    @Test
    fun `the setup wizard default Germany becomes DE`() {
        assertEquals("DE", CountryCodeNormalizer.toIso2("Germany"))
    }

    @Test
    fun `German and English names, codes and alpha-3 of the DACH countries all map`() {
        listOf("Deutschland", "germany", " DE ", "de", "DEU").forEach { assertEquals("DE", CountryCodeNormalizer.toIso2(it), it) }
        listOf("Österreich", "Oesterreich", "Austria", "AT", "AUT").forEach { assertEquals("AT", CountryCodeNormalizer.toIso2(it), it) }
        listOf("Schweiz", "Switzerland", "ch", "CHE").forEach { assertEquals("CH", CountryCodeNormalizer.toIso2(it), it) }
    }

    @Test
    fun `spacing, case and dots are formatting`() {
        assertEquals("US", CountryCodeNormalizer.toIso2("U.S.A."))
        assertEquals("GB", CountryCodeNormalizer.toIso2("  united   KINGDOM "))
        assertEquals("GB", CountryCodeNormalizer.toIso2("UK"))
    }

    @Test
    fun `an unassigned two-letter code or an unknown name is refused, never guessed`() {
        assertNull(CountryCodeNormalizer.toIso2("DX"))
        assertNull(CountryCodeNormalizer.toIso2("Germanyy"))
        assertNull(CountryCodeNormalizer.toIso2(""))
        assertNull(CountryCodeNormalizer.toIso2("   "))
        assertNull(CountryCodeNormalizer.toIso2(null))
    }

    @Test
    fun `any assigned alpha-2 code passes through upper-cased`() {
        assertEquals("JP", CountryCodeNormalizer.toIso2("jp"))
        assertEquals("NL", CountryCodeNormalizer.toIso2("NL"))
    }
}
