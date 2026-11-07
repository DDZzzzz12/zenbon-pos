package org.example.project.core.browser

import kotlin.test.Test
import kotlin.test.assertEquals

class SingleTabGuardTest {
    private var now = 0L

    private fun guard(
        bus: FakeTabBus,
        id: Long,
    ) = SingleTabGuard(bus.join(), clock = { now }, tabId = id)

    @Test
    fun `first tab owns the session`() {
        val a = guard(FakeTabBus(), 1)
        a.start()
        assertEquals(TabState.Owner, a.state.value)
    }

    @Test
    fun `a second tab that hears the owner becomes Duplicate`() {
        val bus = FakeTabBus()
        val a = guard(bus, 5)
        a.start()
        now = 2_000
        a.tick()
        // Opened later with a LOWER id: the settled owner must still keep the session.
        val b = guard(bus, 3)
        b.start()
        assertEquals(TabState.Duplicate, b.state.value)
        assertEquals(TabState.Owner, a.state.value)
    }

    @Test
    fun `a duplicate takes over when the owner stops heartbeating`() {
        val bus = FakeTabBus()
        val a = guard(bus, 1)
        a.start()
        now = 2_000
        a.tick()
        val b = guard(bus, 2)
        b.start()
        assertEquals(TabState.Duplicate, b.state.value)
        now = 7_000
        b.tick()
        assertEquals(TabState.Duplicate, b.state.value)
        now = 8_000
        b.tick()
        assertEquals(TabState.Owner, b.state.value)
    }
}
