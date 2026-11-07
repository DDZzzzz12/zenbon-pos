package org.example.project.features.kassenbuch

import org.example.project.features.kassenbuch.data.service.DatevBelegfeld1
import org.example.project.features.kassenbuch.data.service.DatevBelegfeld1.Book
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DatevBelegfeld1Test {
    private val allowed = Regex("[A-Za-z0-9$&%*+\\-/]*")

    @Test
    fun `the fiscal book keeps its plain number`() {
        assertEquals("2026-05-0001", DatevBelegfeld1.of("2026-05-0001", Book(id = 1, name = "Ladenkasse", isFiscal = true)))
    }

    @Test
    fun `two non-fiscal books with the same letter stay distinct through their id`() {
        val a = DatevBelegfeld1.of("2026-05-0001", Book(id = 2, name = "Tresor", isFiscal = false))
        val b = DatevBelegfeld1.of("2026-05-0001", Book(id = 3, name = "Trinkgeldkasse", isFiscal = false))
        assertEquals("T2-2026-05-0001", a)
        assertEquals("T3-2026-05-0001", b)
        assertNotEquals(a, b)
    }

    @Test
    fun `the prefix uses only DATEV-safe characters`() {
        val value = DatevBelegfeld1.of("2026-05-0001", Book(id = 7, name = "Übergabe Kasse", isFiscal = false))
        assertEquals("B7-2026-05-0001", value, "umlauts are skipped, not transliterated")
        assertEquals("K8-2026-05-0001", DatevBelegfeld1.of("2026-05-0001", Book(id = 8, name = "ÄÖÜ 123", isFiscal = false)))
        assertTrue(allowed.matches(value))
    }

    @Test
    fun `an over-long value is capped at 36 keeping the prefix and the counter`() {
        val longNr = "X".repeat(30) + "-0042"
        val value = DatevBelegfeld1.of(longNr, Book(id = 12345, name = "Tresor", isFiscal = false))
        assertEquals(DatevBelegfeld1.MAX_LENGTH, value.length)
        assertTrue(value.startsWith("T12345-"))
        assertTrue(value.endsWith("-0042"))
    }
}
