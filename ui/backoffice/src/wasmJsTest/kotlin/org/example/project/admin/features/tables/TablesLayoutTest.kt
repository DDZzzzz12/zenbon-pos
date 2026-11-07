package org.example.project.admin.features.tables

import androidx.compose.ui.unit.dp
import org.example.project.admin.features.tables.presentation.TablesLayout
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Tische layout follows the content width: inline grid, read-only table, list rows. */
class TablesLayoutTest {
    @Test
    fun `wide from 720dp`() {
        assertEquals(TablesLayout.WIDE, TablesLayout.forWidth(720.dp))
        assertEquals(TablesLayout.WIDE, TablesLayout.forWidth(1200.dp))
    }

    @Test
    fun `medium between 560 and 720dp`() {
        assertEquals(TablesLayout.MEDIUM, TablesLayout.forWidth(719.dp))
        assertEquals(TablesLayout.MEDIUM, TablesLayout.forWidth(560.dp))
    }

    @Test
    fun `compact below 560dp`() {
        assertEquals(TablesLayout.COMPACT, TablesLayout.forWidth(559.dp))
        assertEquals(TablesLayout.COMPACT, TablesLayout.forWidth(358.dp))
    }
}
