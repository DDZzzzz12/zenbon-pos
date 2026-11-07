package org.example.project.admin.features.tables

import org.example.project.admin.features.tables.presentation.components.combinableCandidates
import org.example.project.core.data.models.RTable
import kotlin.test.Test
import kotlin.test.assertEquals

private fun table(
    id: Long,
    number: String,
    location: Long = 1,
    active: Boolean = true,
    virtual: Boolean = false,
) = RTable(
    id = id,
    tableNumber = number,
    seats = 4,
    locationTypeId = location,
    locationTypeName = "L$location",
    isVirtual = virtual,
    tableStatus = "FREE",
    currentSessionId = null,
    gridX = null,
    gridY = null,
    rotation = null,
    isActive = active,
)

class CombinableCandidatesTest {
    private val current = table(7, "7")

    @Test
    fun `only active real tables of the same location, never the table itself`() {
        val all = listOf(current, table(1, "1"), table(2, "2", location = 2), table(3, "3", active = false), table(4, "4", virtual = true), table(5, "5"))
        assertEquals(listOf(5L, 1L), combinableCandidates(current, all, emptySet()).map { it.id })
    }

    @Test
    fun `selected tables come first, then by closeness of the number`() {
        val all = listOf(current, table(1, "1"), table(5, "5"), table(6, "6"), table(10, "10"))
        assertEquals(listOf(1L, 6L, 5L, 10L), combinableCandidates(current, all, setOf(1L)).map { it.id })
    }
}
