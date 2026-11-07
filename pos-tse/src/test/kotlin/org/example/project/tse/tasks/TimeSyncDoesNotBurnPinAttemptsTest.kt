package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormAuthenticationFailedException
import com.secureflashcard.wormapi.WormUserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * A wrong TimeAdmin PIN must cost exactly one attempt, not three.
 *
 * Scheduled time sync retries up to MAX_RETRY_ATTEMPTS, and the login failure used to be
 * swallowed at debug level — so a misconfigured PIN spent all three of the attempts the
 * device grants before TimeAdmin blocks, on the very first maintenance cycle, roughly ten
 * minutes after start. Unattended time sync would then be dead until someone unblocked it
 * with the PUK, and a TSE whose time cannot be set stops signing.
 */
class TimeSyncDoesNotBurnPinAttemptsTest {

    private val tseStatus = TseStatus()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun tearDown() {
        scope.cancel()
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    private fun commissionedWithWrongTimeAdminPin() {
        VirtualTseTestSupport.attachDevice("v2")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("TER-001")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        tseStatus.setTimeAdminPin("98765")
        assertTrue(tseStatus.detectHardware())

        tseStatus.getWormStore().tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )
        runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_ADMIN) }

        // Now misconfigure it, the way a wrong stored PIN would look to the task.
        tseStatus.setTimeAdminPin("00000")
    }

    /** The attempts the device grants TimeAdmin before it blocks. */
    private fun freshRetryBudget(): Int {
        // Read it from the device rather than hardcoding 3, then restore it with a good login
        // so the probe does not itself change what the test measures.
        val e = assertFailsWith<WormAuthenticationFailedException> {
            tseStatus.getWormStore().user_login(WormUserId.WORM_USER_TIME_ADMIN, "11111".toByteArray())
        }
        val budget = e.remainingRetries + 1
        tseStatus.getWormStore().user_login(WormUserId.WORM_USER_TIME_ADMIN, "98765".toByteArray())
        runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_TIME_ADMIN) }
        return budget
    }

    @Test
    fun `a rejected TimeAdmin PIN costs exactly one attempt`() = runTest {
        commissionedWithWrongTimeAdminPin()
        val budget = freshRetryBudget()
        // Without this the time is still valid and the sync skips out before ever logging in —
        // which is how the first draft of this test passed against the unfixed code.
        VirtualTseTestSupport.invalidateTime(tseStatus.getWormStore())

        TseMaintenanceTask(tseStatus, scope).performTimeSyncForTest()

        val failure = assertNotNull(tseStatus.getLastAuthFailure())
        assertEquals(
            budget - 1, failure.remainingRetries,
            "one attempt, not one per retry — the loop must stop at the first rejection",
        )
        assertTrue(!failure.pinBlocked, "a single cycle must not block TimeAdmin outright")
    }

    @Test
    fun `the auth failure is recorded rather than swallowed`() = runTest {
        commissionedWithWrongTimeAdminPin()
        VirtualTseTestSupport.invalidateTime(tseStatus.getWormStore())

        TseMaintenanceTask(tseStatus, scope).performTimeSyncForTest()

        val failure = assertNotNull(
            tseStatus.getLastAuthFailure(),
            "the rejection must be recorded so the operator can be told which PIN is wrong",
        )
        assertNotNull(failure.remainingRetries, "the device reports the real attempt count")
    }

    @Test
    fun `a correct TimeAdmin PIN still syncs the time`() = runTest {
        commissionedWithWrongTimeAdminPin()
        tseStatus.setTimeAdminPin("98765")
        VirtualTseTestSupport.invalidateTime(tseStatus.getWormStore())

        assertTrue(
            TseMaintenanceTask(tseStatus, scope).performTimeSyncForTest(),
            "the guard must not have broken the normal path",
        )
        tseStatus.getWormStore().info().use {
            assertTrue(it.hasValidTime())
            assertEquals(
                WormUserId.WORM_USER_UNAUTHENTICATED, it.loggedInUser(),
                "TimeAdmin must still be logged out afterwards",
            )
        }
    }
}
