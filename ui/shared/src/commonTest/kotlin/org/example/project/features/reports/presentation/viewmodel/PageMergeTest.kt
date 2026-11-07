package org.example.project.features.reports.presentation.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class PageMergeTest {
    private data class Row(
        val id: Long,
        val label: String,
    )

    @Test
    fun dropsARowRepeatedByAnOffsetShiftAndKeepsTheShownOne() {
        // Page 0 showed bills 9..7; bill 10 closed, so page 1 now starts with 7 again.
        val shown = listOf(Row(9, "a"), Row(8, "b"), Row(7, "c"))
        val nextPage = listOf(Row(7, "c-again"), Row(6, "d"), Row(5, "e"))

        val merged = shown.appendPage(nextPage) { it.id }

        assertEquals(listOf(9L, 8L, 7L, 6L, 5L), merged.map { it.id })
        assertEquals("c", merged.single { it.id == 7L }.label)
    }

    @Test
    fun dropsDuplicatesInsideTheNewPage() {
        val merged = listOf(Row(1, "a")).appendPage(listOf(Row(2, "b"), Row(2, "b2"))) { it.id }

        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }

    @Test
    fun appendsAPageOfNewRowsUnchanged() {
        val merged = listOf(Row(3, "a")).appendPage(listOf(Row(2, "b"), Row(1, "c"))) { it.id }

        assertEquals(listOf(3L, 2L, 1L), merged.map { it.id })
    }
}
