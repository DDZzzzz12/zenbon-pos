package org.example.project.tse.tasks

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
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * On TSE 2.0 only one user may be logged in, so a scheduled time-sync that logs in TimeAdmin
 * would evict an operator's active Admin session. Since Admin is itself permitted to set the
 * time, maintenance reuses an Admin login when it finds one — no TimeAdmin login, no eviction.
 *
 * The device is the source of truth: `info().loggedInUser()` reports the live session on 2.0
 * (and always UNAUTHENTICATED on 1.x, which has no single-user constraint, so 1.x simply keeps
 * the TimeAdmin path). None of this needs the server's in-memory admin session.
 */
class MaintenanceReusesAdminLoginTest {

    private val tseStatus = TseStatus()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun tearDown() {
        scope.cancel()
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    private fun commission(generation: String) {
        VirtualTseTestSupport.attachDevice(generation)
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("TER-001")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        tseStatus.setTimeAdminPin("98765")
        assertTrue(tseStatus.detectHardware())
        // setup_ext leaves Admin logged in — the exact state an operator's admin op leaves.
        tseStatus.getWormStore().tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )
    }

    @Test
    fun `time-sync reuses an active Admin login on TSE 2_0 and does not evict it`() = runTest {
        commission("v2")
        assertEquals(
            WormUserId.WORM_USER_ADMIN,
            tseStatus.getWormStore().info().use { it.loggedInUser() },
            "precondition: Admin is logged in",
        )
        VirtualTseTestSupport.invalidateTime(tseStatus.getWormStore())

        TseMaintenanceTask(tseStatus, scope).performTimeSyncForTest()

        tseStatus.getWormStore().info().use {
            assertTrue(it.hasValidTime(), "the time must have been set")
            // The tell: reuse leaves Admin logged in. The old TimeAdmin path would have
            // evicted Admin (single-user) and then logged TimeAdmin out — UNAUTHENTICATED.
            assertEquals(
                WormUserId.WORM_USER_ADMIN, it.loggedInUser(),
                "the operator's Admin session must survive the maintenance time-sync",
            )
        }
    }

    @Test
    fun `time-sync uses TimeAdmin when nobody is logged in, and logs out after`() = runTest {
        commission("v2")
        runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_ADMIN) }
        VirtualTseTestSupport.invalidateTime(tseStatus.getWormStore())

        TseMaintenanceTask(tseStatus, scope).performTimeSyncForTest()

        tseStatus.getWormStore().info().use {
            assertTrue(it.hasValidTime())
            assertEquals(
                WormUserId.WORM_USER_UNAUTHENTICATED, it.loggedInUser(),
                "with no Admin to reuse, TimeAdmin runs and must be logged out afterwards",
            )
        }
    }

    @Test
    fun `on TSE 1_x it always takes the TimeAdmin path`() = runTest {
        commission("v1")
        tseStatus.getWormStore().tse_updateTime(System.currentTimeMillis() / 1000)
        runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_ADMIN) }
        VirtualTseTestSupport.invalidateTime(tseStatus.getWormStore())

        // loggedInUser() is always UNAUTHENTICATED on 1.x, so reuse never triggers — but 1.x
        // allows multiple logins, so there is no eviction to avoid. Just prove it still syncs.
        assertTrue(TseMaintenanceTask(tseStatus, scope).performTimeSyncForTest())
        tseStatus.getWormStore().info().use { assertTrue(it.hasValidTime()) }
    }
}
