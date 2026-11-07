package org.example.project.features.supplier

import org.example.project.features.supplier.data.service.normalizeSupplierName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SupplierNameNormalizerTest {
    @Test
    fun `case and whitespace collapse`() {
        assertEquals(normalizeSupplierName("Metro AG"), normalizeSupplierName("METRO   ag"))
    }

    @Test
    fun `legal forms are stripped`() {
        assertEquals(
            normalizeSupplierName("Getränke Müller GmbH & Co. KG"),
            normalizeSupplierName("Getränke Müller"),
        )
    }

    @Test
    fun `intra-word punctuation is dropped`() {
        assertEquals(normalizeSupplierName("E.ON SE"), normalizeSupplierName("EON"))
    }

    @Test
    fun `separators become spaces`() {
        assertEquals(
            normalizeSupplierName("Getränke-Müller"),
            normalizeSupplierName("Getränke Müller"),
        )
        assertEquals(
            normalizeSupplierName("Metro Cash & Carry"),
            normalizeSupplierName("Metro Cash Carry"),
        )
    }

    @Test
    fun `umlauts are preserved, not folded`() {
        assertNotEquals(normalizeSupplierName("Müller"), normalizeSupplierName("Muller"))
    }

    @Test
    fun `distinct companies stay distinct`() {
        assertNotEquals(normalizeSupplierName("Metro AG"), normalizeSupplierName("Metro Cash Carry"))
    }

    @Test
    fun `a name that is only a legal form does not normalize to empty`() {
        assertEquals("gmbh", normalizeSupplierName("GmbH"))
    }
}
