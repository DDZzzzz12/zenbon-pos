package org.example.project.tse.operations

import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.signing.TseTransactionStartOperation
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * A missed maintenance window must not cost a sale.
 *
 * Scheduled maintenance stays primary — a self test can take up to 60 seconds and invalidates
 * the time afterwards, so it belongs at quiet moments, never mid-sale. But when signing does
 * meet a stale TSE, remediate and retry once rather than failing the transaction. This is the
 * vendor's own remedy for the equivalent shared-LAN-TSE case: update the time and repeat the
 * failed transaction.
 */
class SigningRecoversFromStaleTseTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    private fun commissioned(generation: String): WormStore {
        VirtualTseTestSupport.attachDevice(generation)
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("TER-001")
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        assertTrue(tseStatus.detectHardware())
        val store = tseStatus.getWormStore()
        store.tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )
        store.tse_updateTime(System.currentTimeMillis() / 1000)
        runCatching { store.user_logout(WormUserId.WORM_USER_ADMIN) }
        return store
    }

    @Test
    fun `signing recovers when the TSE time has gone stale on TSE 2_0`() {
        val store = commissioned("v2")
        VirtualTseTestSupport.invalidateTime(store)

        // Must succeed: set the time and retry rather than failing the sale.
        val result = TseTransactionStartOperation(
            command = "signorders",
            clientId = "TER-001",
        ).execute(tseStatus)

        assertTrue(result.transactionNumber > 0, "signing should recover from a stale time")
    }

    @Test
    fun `signing recovers when the TSE time has gone stale on TSE 1_x`() {
        val store = commissioned("v1")
        VirtualTseTestSupport.invalidateTime(store)

        val result = TseTransactionStartOperation(
            command = "signorders",
            clientId = "TER-001",
        ).execute(tseStatus)

        assertTrue(result.transactionNumber > 0, "signing should recover from a stale time")
    }

    @Test
    fun `recovery does not leave TimeAdmin logged in`() {
        val store = commissioned("v2")
        VirtualTseTestSupport.invalidateTime(store)

        TseTransactionStartOperation(
            command = "signorders",
            clientId = "TER-001",
        ).execute(tseStatus)

        // A resting TimeAdmin would block every later export.
        store.info().use {
            assertTrue(
                it.loggedInUser() != WormUserId.WORM_USER_TIME_ADMIN,
                "TimeAdmin must not be left logged in by the recovery path",
            )
        }
    }
}
