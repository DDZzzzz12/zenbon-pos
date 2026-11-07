package org.example.project.ui.components.filter

import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals

class PluralResultKeyTest {
    private val one = StringKey("one")
    private val other = StringKey("other")

    @Test
    fun exactlyOneIsSingular() {
        assertEquals(one, pluralResultKey(1, one, other))
        assertEquals(one, pluralResultKey(1L, one, other))
    }

    @Test
    fun zeroAndManyArePlural() {
        assertEquals(other, pluralResultKey(0, one, other))
        assertEquals(other, pluralResultKey(2, one, other))
        assertEquals(other, pluralResultKey(120L, one, other))
    }

    @Test
    fun aLowerBoundIsNeverSingular() {
        // "1+" means further pages exist: the exact-count rule keeps it plural.
        assertEquals(other, pluralResultKey(1, one, other, hasMore = true))
    }
}
