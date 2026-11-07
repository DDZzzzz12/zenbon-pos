package org.example.project.features.tse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.example.project.features.tse.domain.session.TseAdminSessionReaper
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reaper must log the device Admin out exactly once, when the sitting lapses — not while
 * it is live, not before it ever started, and not again on every later poll.
 */
class TseAdminSessionReaperTest {
    private var unlocked = false
    private var logouts = 0

    private fun reaper() =
        TseAdminSessionReaper(
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            logoutAdmin = { logouts++ },
            isUnlocked = { unlocked },
        )

    @Test
    fun `logs out on the unlocked to locked edge, once`() =
        runTest {
            val reaper = reaper()

            unlocked = true
            reaper.reconcileOnce() // observe the sitting start
            assertEquals(0, logouts, "a live sitting must not be logged out")

            reaper.reconcileOnce() // still live
            assertEquals(0, logouts)

            unlocked = false // sitting lapsed
            reaper.reconcileOnce()
            assertEquals(1, logouts, "the lapse must trigger exactly one logout")

            reaper.reconcileOnce() // stays locked
            reaper.reconcileOnce()
            assertEquals(1, logouts, "a lapsed sitting must not be logged out again every poll")
        }

    @Test
    fun `a sitting that never unlocked is never logged out`() =
        runTest {
            val reaper = reaper()
            // unlocked stays false throughout.
            repeat(3) { reaper.reconcileOnce() }
            assertEquals(0, logouts, "nothing to log out if nobody ever unlocked")
        }

    @Test
    fun `a second sitting after a lapse is reaped on its own lapse`() =
        runTest {
            val reaper = reaper()

            unlocked = true
            reaper.reconcileOnce()
            unlocked = false
            reaper.reconcileOnce()
            assertEquals(1, logouts)

            // A fresh sitting: unlock, then lapse again.
            unlocked = true
            reaper.reconcileOnce()
            unlocked = false
            reaper.reconcileOnce()
            assertEquals(2, logouts, "each sitting's lapse is reaped independently")
        }
}
