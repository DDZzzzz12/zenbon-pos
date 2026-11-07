package org.example.project.tse.operations.admin.setup

import com.secureflashcard.wormapi.WormInitializationState
import com.secureflashcard.wormapi.WormStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.example.project.tse.operations.admin.NativeInfoHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: the pre-flight read `startedTransactions()` from a WormInformation handle already
 * closed by the `initializationState()` read — a use-after-free on the real JNI SDK, right before
 * an irreversible command.
 */
class DecommissionOperationTest {

    private fun handle(state: WormInitializationState, started: Long): NativeInfoHandle {
        lateinit var h: NativeInfoHandle
        h = NativeInfoHandle {
            every { initializationState() } answers { h.live(state) }
            every { startedTransactions() } answers { h.live(started) }
        }
        return h
    }

    private fun storeFor(h: NativeInfoHandle): WormStore = mockk(relaxed = true) {
        every { info() } returns h.info
    }

    @Test
    fun `decommissions an idle initialized TSE without touching a closed handle`() {
        val h = handle(WormInitializationState.WORM_INIT_INITIALIZED, started = 0)
        val store = storeFor(h)

        val result = DecommissionOperation(adminPin = "12345").execute(NativeInfoHandle.tseStatusFor(store))

        assertEquals(0, h.accessAfterClose, "no getter may run on a closed native handle")
        assertEquals(1, h.closeCount, "the info handle must be released exactly once")
        assertTrue(result.success)
        verify(exactly = 1) { store.tse_decommission() }
    }

    @Test
    fun `refuses while transactions are open and reads the count from a live handle`() {
        val h = handle(WormInitializationState.WORM_INIT_INITIALIZED, started = 2)
        val store = storeFor(h)

        val result = DecommissionOperation(adminPin = "12345").execute(NativeInfoHandle.tseStatusFor(store))

        assertEquals(0, h.accessAfterClose)
        assertEquals(1, h.closeCount)
        assertFalse(result.success)
        assertTrue(result.message.contains("2 unfinished"), result.message)
        verify(exactly = 0) { store.tse_decommission() }
    }

    @Test
    fun `refuses an uninitialized TSE`() {
        val h = handle(WormInitializationState.WORM_INIT_UNINITIALIZED, started = 0)
        val store = storeFor(h)

        val result = DecommissionOperation(adminPin = "12345").execute(NativeInfoHandle.tseStatusFor(store))

        assertEquals(0, h.accessAfterClose)
        assertEquals(1, h.closeCount)
        assertFalse(result.success)
        verify(exactly = 0) { store.tse_decommission() }
    }
}
