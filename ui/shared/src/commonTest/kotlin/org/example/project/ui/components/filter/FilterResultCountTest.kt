package org.example.project.ui.components.filter

import kotlin.test.Test
import kotlin.test.assertEquals

class FilterResultCountTest {
    @Test
    fun countIsPlainWhenEverythingIsLoaded() {
        assertEquals("12", filterResultCount(12, hasMore = false))
    }

    @Test
    fun countIsMarkedOpenEndedWhenMorePagesExist() {
        assertEquals("50+", filterResultCount(50, hasMore = true))
    }

    @Test
    fun zeroStaysZero() {
        assertEquals("0", filterResultCount(0, hasMore = false))
    }
}
