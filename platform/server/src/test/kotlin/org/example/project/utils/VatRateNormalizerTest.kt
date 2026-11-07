package org.example.project.utils

import org.example.project.core.utils.datev.VatRateNormalizer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VatRateNormalizerTest {
    @Test
    fun `canonicalizes integer rates`() {
        assertEquals("19", VatRateNormalizer.normalize("19"))
        assertEquals("19", VatRateNormalizer.normalize("19,00"))
        assertEquals("19", VatRateNormalizer.normalize("19.0"))
        assertEquals("7", VatRateNormalizer.normalize(" 7 "))
        assertEquals("0", VatRateNormalizer.normalize("0"))
        assertEquals("0", VatRateNormalizer.normalize("0,00"))
    }

    @Test
    fun `keeps fractional rates with dot decimal`() {
        assertEquals("10.7", VatRateNormalizer.normalize("10,7"))
        assertEquals("10.7", VatRateNormalizer.normalize("10.70"))
        assertEquals("5.5", VatRateNormalizer.normalize("5,50"))
    }

    @Test
    fun `rejects non-numeric, negative and blank input`() {
        assertNull(VatRateNormalizer.normalize(null))
        assertNull(VatRateNormalizer.normalize(""))
        assertNull(VatRateNormalizer.normalize("  "))
        assertNull(VatRateNormalizer.normalize("abc"))
        assertNull(VatRateNormalizer.normalize("-7"))
    }
}
