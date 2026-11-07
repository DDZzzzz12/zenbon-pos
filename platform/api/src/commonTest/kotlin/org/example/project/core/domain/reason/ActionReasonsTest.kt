package org.example.project.core.domain.reason

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionReasonsTest {
    private val lists =
        mapOf(
            "cancelItems" to (ActionReasons.cancelItems to 6),
            "cancelTable" to (ActionReasons.cancelTable to 4),
            "transferTable" to (ActionReasons.transferTable to 5),
            "transferItems" to (ActionReasons.transferItems to 5),
        )

    @Test
    fun listsHaveTheAgreedSizes() {
        lists.forEach { (name, pair) -> assertEquals(pair.second, pair.first.size, name) }
    }

    @Test
    fun otherIsLastAndOnlyOnce() {
        lists.forEach { (name, pair) ->
            val list = pair.first
            assertTrue(list.last().isOther, "$name: last must be Other")
            assertEquals(1, list.count { it.isOther }, name)
        }
    }

    @Test
    fun storedTextsAreUniqueAndNonBlankExceptOther() {
        lists.forEach { (name, pair) ->
            val texts = pair.first.filterNot { it.isOther }.map { it.storedText }
            assertTrue(texts.all { it.isNotBlank() }, name)
            assertEquals(texts.size, texts.toSet().size, name)
            assertEquals("", pair.first.last().storedText, name)
        }
    }

    @Test
    fun cancelItemsStoredTextsAreTheGermanLabelsInOrder() {
        assertEquals(
            listOf(
                "Falscher Artikel erfasst",
                "Gast hat es sich anders überlegt",
                "Küchenfehler",
                "Nicht vorrätig",
                "Doppelt erfasst",
                "",
            ),
            ActionReasons.cancelItems.map { it.storedText },
        )
    }

    @Test
    fun excludedReasonsAreNotOffered() {
        val all = lists.values.flatMap { it.first }.map { it.storedText }
        assertTrue(all.none { it.contains("Wartezeit") || it.contains("Personalessen") || it.contains("ohne Bestellung") })
    }

    @Test
    fun maxLengthIs500() {
        assertEquals(500, ActionReasons.MAX_LENGTH)
    }
}
