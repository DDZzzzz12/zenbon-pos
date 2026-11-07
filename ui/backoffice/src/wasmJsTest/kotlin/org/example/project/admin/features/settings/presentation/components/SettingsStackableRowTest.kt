package org.example.project.admin.features.settings.presentation.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStackableRowTest {
    @Test
    fun aPhoneSizedContentWidthStacks() {
        assertTrue(shouldStackRow(350.dp)) // 390 window minus the 20dp page padding
        assertTrue(shouldStackRow(599.dp))
    }

    @Test
    fun aWideContentWidthStaysInOneRow() {
        assertFalse(shouldStackRow(600.dp))
        assertFalse(shouldStackRow(1000.dp))
    }
}
