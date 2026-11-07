package org.example.project.features.matching

import org.example.project.features.matching.data.service.normalizeInvoiceReference
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The key both sides of signals 1 and 2 are compared on.
 *
 * Everything here is a real way a German payer mangles an invoice number on a transfer: a `RE`
 * prefix, a `Nr.`, spaces and slashes inserted for readability, leading zeros dropped by hand.
 *
 * The danger runs the other way too — normalise too hard and two different invoices collide, which
 * is a *wrong* suggestion rather than a missing one. The last two tests guard that edge.
 */
@DisplayName("Invoice reference normaliser")
class InvoiceReferenceNormalizerTest {
    @Test
    fun `case and surrounding whitespace do not matter`() {
        assertEquals(normalizeInvoiceReference("re-2026-0001"), normalizeInvoiceReference("  RE-2026-0001 "))
    }

    @Test
    fun `the RE RG and Nr prefixes are stripped`() {
        val bare = normalizeInvoiceReference("2026-0001")
        assertEquals(bare, normalizeInvoiceReference("RE 2026-0001"))
        assertEquals(bare, normalizeInvoiceReference("RG-2026-0001"))
        assertEquals(bare, normalizeInvoiceReference("Nr. 2026-0001"))
        assertEquals(bare, normalizeInvoiceReference("Rechnung 2026-0001"))
    }

    @Test
    fun `separators are removed`() {
        val bare = normalizeInvoiceReference("20260001")
        assertEquals(bare, normalizeInvoiceReference("2026-0001"))
        assertEquals(bare, normalizeInvoiceReference("2026/0001"))
        assertEquals(bare, normalizeInvoiceReference("2026 0001"))
        assertEquals(bare, normalizeInvoiceReference("2026.0001"))
    }

    @Test
    fun `leading zeros are dropped`() {
        assertEquals(normalizeInvoiceReference("RE-4711"), normalizeInvoiceReference("RE-0004711"))
    }

    @Test
    fun `an empty or punctuation-only reference normalises to null`() {
        assertEquals(null, normalizeInvoiceReference(""))
        assertEquals(null, normalizeInvoiceReference("   "))
        assertEquals(null, normalizeInvoiceReference("- / ."))
        assertEquals(null, normalizeInvoiceReference("RE"), "a prefix with nothing after it identifies nothing")
        assertEquals(null, normalizeInvoiceReference(null))
    }

    @Test
    fun `two genuinely different numbers stay different`() {
        assertNotEquals(normalizeInvoiceReference("RE-2026-0001"), normalizeInvoiceReference("RE-2026-0002"))
        assertNotEquals(normalizeInvoiceReference("2026-1"), normalizeInvoiceReference("2026-10"))
    }

    @Test
    fun `a letter suffix is kept`() {
        assertNotEquals(
            normalizeInvoiceReference("RE-2026-0001"),
            normalizeInvoiceReference("RE-2026-0001A"),
            "a corrected invoice is a different invoice",
        )
    }
}
