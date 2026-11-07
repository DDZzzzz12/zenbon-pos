package org.example.project.features.scheduler.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobNextRunDisplayTest {
    @Test
    fun `enabled job shows its next run`() {
        assertEquals(42L, visibleNextRun(true, 42L))
    }

    @Test
    fun `disabled job hides its placeholder next run`() {
        assertNull(visibleNextRun(false, 42L))
    }

    @Test
    fun `enabled job without next run stays empty`() {
        assertNull(visibleNextRun<String>(true, null))
    }
}
