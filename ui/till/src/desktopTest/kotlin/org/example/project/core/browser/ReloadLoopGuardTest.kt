package org.example.project.core.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReloadLoopGuardTest {
    private class MemoryStore(
        var stamp: Long? = null,
    ) : ReloadStampStore {
        override fun read() = stamp

        override fun write(epochMillis: Long) {
            stamp = epochMillis
        }
    }

    @Test
    fun `first automatic reload is allowed and stamped`() {
        val store = MemoryStore()
        assertTrue(ReloadLoopGuard(store, { 1_000 }).tryAcquire())
        assertEquals(1_000L, store.stamp)
    }

    @Test
    fun `a second reload within a minute is refused and does not move the stamp`() {
        val store = MemoryStore(stamp = 1_000)
        assertFalse(ReloadLoopGuard(store, { 30_000 }).tryAcquire())
        assertEquals(1_000L, store.stamp)
    }

    @Test
    fun `after a minute it is allowed again`() {
        val store = MemoryStore(stamp = 1_000)
        assertTrue(ReloadLoopGuard(store, { 61_000 }).tryAcquire())
        assertEquals(61_000L, store.stamp)
    }

    @Test
    fun `a stamp in the future (clock moved back) does not block forever`() {
        assertTrue(ReloadLoopGuard(MemoryStore(stamp = 900_000), { 1_000 }).tryAcquire())
    }

    @Test
    fun `watcher refuses the second automatic reload but still flags the update`() {
        val store = MemoryStore()
        var now = 0L
        val w = ServerBuildWatcher("1.1.0", reloadGuard = ReloadLoopGuard(store, { now }))
        w.onServerHello("1.1.1")
        assertTrue(w.consumeAutoReload(busy = false))
        now = 5_000
        assertFalse(w.consumeAutoReload(busy = false))
        assertTrue(w.updateAvailable.value)
    }

    @Test
    fun `busy does not consume the guard`() {
        val store = MemoryStore()
        val w = ServerBuildWatcher("1.1.0", reloadGuard = ReloadLoopGuard(store, { 0 }))
        w.onServerHello("1.1.1")
        assertFalse(w.consumeAutoReload(busy = true))
        assertEquals(null, store.stamp)
    }
}
