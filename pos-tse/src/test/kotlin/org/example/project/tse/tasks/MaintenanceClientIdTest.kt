package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * A TSE client is a Kasse under KassenSichV/DSFinV-K, and the SDK defines `clientId` as the
 * "serial number of the system where the TSE is physically plugged in". A synthetic
 * "CLIENT-FOR-MAINTENANCE" is neither, yet it was registered on real devices and appears in
 * `listRegisteredClients()` and the exported TAR a Prüfer reads.
 *
 * It existed only because the v1 self test needs *a* registered client and the code reached
 * for `getClientId()`, which nothing in production ever set.
 */
class MaintenanceClientIdTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    private fun commission(store: WormStore) {
        store.tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )
        store.tse_updateTime(System.currentTimeMillis() / 1000)
    }

    @Test
    fun `no client id is assumed before configuration arrives`() {
        // Must not be a usable-looking synthetic ID: an unconfigured driver should have no
        // client, not a fake one it might register on a fiscal device.
        assertNull(TseStatus().getClientId())
    }

    @Test
    fun `the configured client is used and no synthetic one is registered`() {
        VirtualTseTestSupport.attachDevice("v1")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        assertTrue(tseStatus.detectHardware())
        commission(tseStatus.getWormStore())
        tseStatus.setClientId("TER-001")

        assertEquals("TER-001", tseStatus.getClientId())
        assertFalse(
            tseStatus.getWormStore().tse_listRegisteredClients().any { it.contains("MAINTENANCE") },
            "no synthetic maintenance client may be registered on the device",
        )
    }

    @Test
    fun `a v1 self test needs no Admin login and no client lookup`() {
        VirtualTseTestSupport.attachDevice("v1")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        assertTrue(tseStatus.detectHardware())
        commission(tseStatus.getWormStore())
        tseStatus.setClientId("TER-001")
        runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_ADMIN) }

        // The point: with nobody logged in, the self test still runs. Anything that listed
        // the device's clients first would fail here — listRegisteredClients requires Admin
        // on TSE 1.x.
        tseStatus.getWormStore().tse_runSelfTest(tseStatus.getClientId())
    }
}
