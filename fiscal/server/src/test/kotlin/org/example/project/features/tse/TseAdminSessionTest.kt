package org.example.project.features.tse

import org.example.project.features.tse.domain.session.TseAdminSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Holds the operator-supplied Admin PIN in memory for the duration of an admin sitting.
 * Never persisted, never logged — which is the whole point of the change.
 */
class TseAdminSessionTest {
    @AfterTest
    fun tearDown() = TseAdminSession.clear()

    @Test
    fun `no pin before unlock`() {
        TseAdminSession.clear()
        assertNull(TseAdminSession.pinOrNull())
        assertFalse(TseAdminSession.isUnlocked())
    }

    @Test
    fun `unlock makes the pin available`() {
        TseAdminSession.unlock("54321")
        assertEquals("54321", TseAdminSession.pinOrNull())
        assertTrue(TseAdminSession.isUnlocked())
    }

    @Test
    fun `clear removes it`() {
        TseAdminSession.unlock("54321")
        TseAdminSession.clear()
        assertNull(TseAdminSession.pinOrNull())
    }

    @Test
    fun `expires after the idle timeout`() {
        TseAdminSession.unlock("54321", nowMs = 1_000L)

        // No intermediate read: any read would extend the window, which is exactly what the
        // next test asserts. Here the session must simply go idle and die.
        assertNull(
            TseAdminSession.pinOrNull(nowMs = 1_000L + TseAdminSession.IDLE_TIMEOUT_MS + 1),
            "an idle session must expire",
        )
    }

    @Test
    fun `an expired session stays expired`() {
        TseAdminSession.unlock("54321", nowMs = 1_000L)
        TseAdminSession.pinOrNull(nowMs = 1_000L + TseAdminSession.IDLE_TIMEOUT_MS + 1)

        // Expiry must clear the held PIN, not merely refuse to hand it out once.
        assertNull(TseAdminSession.pinOrNull(nowMs = 1_000L + TseAdminSession.IDLE_TIMEOUT_MS + 2))
        assertFalse(TseAdminSession.isUnlocked(nowMs = 1_000L + TseAdminSession.IDLE_TIMEOUT_MS + 2))
    }

    @Test
    fun `use extends the window`() {
        TseAdminSession.unlock("54321", nowMs = 1_000L)
        // Reading at 10 min keeps it alive; without extension it would be dead by 20.
        assertEquals("54321", TseAdminSession.pinOrNull(nowMs = 1_000L + 600_000L))
        assertEquals("54321", TseAdminSession.pinOrNull(nowMs = 1_000L + 1_200_000L))
    }

    @Test
    fun `a status read does not extend the window`() {
        // T9-7: the reaper polls isUnlocked() every 60 s and the back office polls the status
        // route. If either slid the idle timer, a sitting would never lapse.
        val start = 1_000L
        TseAdminSession.unlock("54321", nowMs = start)
        var t = start
        while (t < start + TseAdminSession.IDLE_TIMEOUT_MS) {
            assertTrue(TseAdminSession.isUnlocked(nowMs = t))
            t += 60_000L
        }
        assertFalse(
            TseAdminSession.isUnlocked(nowMs = start + TseAdminSession.IDLE_TIMEOUT_MS + 1),
            "polling the status must not keep an idle sitting alive",
        )
        assertNull(TseAdminSession.pinOrNull(nowMs = start + TseAdminSession.IDLE_TIMEOUT_MS + 2))
    }

    @Test
    fun `a status read after real use measures idle time from that use`() {
        TseAdminSession.unlock("54321", nowMs = 1_000L)
        assertEquals("54321", TseAdminSession.pinOrNull(nowMs = 1_000L + 600_000L))
        assertTrue(TseAdminSession.isUnlocked(nowMs = 1_000L + 600_000L + TseAdminSession.IDLE_TIMEOUT_MS))
        assertFalse(TseAdminSession.isUnlocked(nowMs = 1_000L + 600_000L + TseAdminSession.IDLE_TIMEOUT_MS + 1))
    }

    @Test
    fun `the idle window is shorter than the TSE 2_0 device logout`() {
        // Otherwise the server could believe a session is live after the device dropped it.
        val deviceLogoutMs = 40 * 60 * 1000L
        assertTrue(
            TseAdminSession.IDLE_TIMEOUT_MS < deviceLogoutMs,
            "the session must not outlive the device session it is used with",
        )
    }

    @Test
    fun `toString never leaks the pin`() {
        TseAdminSession.unlock("54321")
        assertFalse(
            TseAdminSession.toString().contains("54321"),
            "the PIN must never be printable — this object can reach logs and stack traces",
        )
    }
}
