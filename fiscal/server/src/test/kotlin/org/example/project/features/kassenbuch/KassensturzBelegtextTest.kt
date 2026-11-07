package org.example.project.features.kassenbuch

import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.kassenbuch.data.service.KassensturzBelegtext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FO-23: a long book name is shortened visibly; the entry type always survives the 60-char limit. */
class KassensturzBelegtextTest {
    @Test
    fun `a short book name is kept whole`() {
        assertEquals(
            "Kassensturz Ladenkasse: Kassendifferenz Plus",
            KassensturzBelegtext.of("Ladenkasse", KassenbuchEntryType.CASH_SURPLUS),
        )
    }

    @Test
    fun `a long book name is shortened with an ellipsis and the entry type survives`() {
        val text =
            KassensturzBelegtext.of(
                "Nebenkasse für Sonderverkäufe bei Veranstaltungen Sommer",
                KassenbuchEntryType.CASH_SHORTAGE,
            )

        assertTrue(text.length <= KassensturzBelegtext.MAX_LENGTH, "length ${text.length}: $text")
        assertTrue(text.endsWith(": Kassendifferenz Minus"), text)
        assertTrue(text.startsWith("Kassensturz Nebenkasse"), text)
        assertTrue(text.contains("…"), "truncation must be visible: $text")
    }

    @Test
    fun `a name that exactly fits is not marked as truncated`() {
        val room = KassensturzBelegtext.MAX_LENGTH - "Kassensturz : Kassendifferenz Plus".length
        val name = "A".repeat(room)

        val text = KassensturzBelegtext.of(name, KassenbuchEntryType.CASH_SURPLUS)

        assertEquals(KassensturzBelegtext.MAX_LENGTH, text.length)
        assertTrue(!text.contains("…"), text)
    }

    @Test
    fun `a name one char too long is truncated to exactly the limit`() {
        val room = KassensturzBelegtext.MAX_LENGTH - "Kassensturz : Kassendifferenz Plus".length
        val text = KassensturzBelegtext.of("B".repeat(room + 1), KassenbuchEntryType.CASH_SURPLUS)

        assertEquals(KassensturzBelegtext.MAX_LENGTH, text.length)
        assertTrue(text.endsWith("…: Kassendifferenz Plus"), text)
    }
}
