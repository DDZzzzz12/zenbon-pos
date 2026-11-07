package org.example.project.core.browser

import kotlin.test.Test
import kotlin.test.assertEquals

class IdleLockTest {
    @Test
    fun `locks after the configured idle time`() {
        var now = 0L
        var locked = 0
        val lock = IdleLock(minutes = 10, clock = { now }, onLock = { locked++ })
        lock.onInput()
        now = 9 * 60_000L
        lock.tick()
        assertEquals(0, locked)
        now = 10 * 60_000L
        lock.tick()
        assertEquals(1, locked)
    }

    @Test
    fun `input resets the timer`() {
        var now = 0L
        var locked = 0
        val lock = IdleLock(minutes = 10, clock = { now }, onLock = { locked++ })
        now = 9 * 60_000L
        lock.onInput()
        now = 18 * 60_000L
        lock.tick()
        assertEquals(0, locked)
        now = 19 * 60_000L
        lock.tick()
        assertEquals(1, locked)
    }

    @Test
    fun `locks once, not on every tick`() {
        var now = 0L
        var locked = 0
        val lock = IdleLock(minutes = 1, clock = { now }, onLock = { locked++ })
        now = 60_000L
        lock.tick()
        now = 75_000L
        lock.tick()
        assertEquals(1, locked)
        lock.onInput()
        now = 135_000L
        lock.tick()
        assertEquals(2, locked)
    }
}
