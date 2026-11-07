package org.example.project.core.data.database

import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CursorStoreTest {
    @Test
    fun `cursor defaults to zero`() {
        val manager = FakeLocalDatabaseManager.create()
        assertEquals(0L, manager.getLastSeenSeq())
    }

    @Test
    fun `cursor round-trips`() {
        val manager = FakeLocalDatabaseManager.create()
        manager.setLastSeenSeq(5012L)
        assertEquals(5012L, manager.getLastSeenSeq())
    }

    @Test
    fun `cursor can be reset to zero`() {
        val manager = FakeLocalDatabaseManager.create()
        manager.setLastSeenSeq(999L)
        manager.setLastSeenSeq(0L)
        assertEquals(0L, manager.getLastSeenSeq())
    }
}
