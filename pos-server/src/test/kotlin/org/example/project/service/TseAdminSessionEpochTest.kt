package org.example.project.service

import org.example.project.features.tse.domain.session.TseAdminSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TseAdminSessionEpochTest {
    @AfterTest fun tearDown() = TseAdminSession.clear()

    @Test
    fun `epoch is null when locked and set when unlocked`() {
        TseAdminSession.clear()
        assertNull(TseAdminSession.currentEpochIfUnlocked())
        TseAdminSession.unlock("123456")
        assertEquals(TseAdminSession.currentEpochIfUnlocked(), TseAdminSession.currentEpochIfUnlocked())
    }

    @Test
    fun `a new unlock changes the epoch`() {
        TseAdminSession.unlock("123456")
        val first = TseAdminSession.currentEpochIfUnlocked()
        TseAdminSession.unlock("123456")
        assertNotEquals(first, TseAdminSession.currentEpochIfUnlocked())
    }

    @Test
    fun `reading the pin (sliding window) does NOT change the epoch`() {
        TseAdminSession.unlock("123456")
        val before = TseAdminSession.currentEpochIfUnlocked()
        TseAdminSession.pinOrNull() // slides the window; must not bump epoch
        assertEquals(before, TseAdminSession.currentEpochIfUnlocked())
    }
}
