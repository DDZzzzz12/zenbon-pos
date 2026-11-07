package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormExportTarCallback
import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterEach

/**
 * Reproduces the trap directly at the SDK level: on TSE 2.0, leaving TimeAdmin logged in
 * after a time sync blocks every later export and entry read, because TimeAdmin is barred
 * from both. Scheduled maintenance runs several times a day, so without the logout the
 * device spends almost all of its time unable to export.
 */
class TimeSyncLogoutTest {

    private val adminPuk = "888888".toByteArray()
    private val adminPin = "54321".toByteArray()
    private val timeAdminPin = "98765".toByteArray()
    private val seed = "SwissbitSwissbit".toByteArray()
    private val clientId = "POS-TERMINAL-1"

    private fun discardingCallback() = object : WormExportTarCallback() {
        override fun onNewData(data: ByteArray): Int = 0
    }

    @AfterEach
    fun tearDown() = VirtualTseTestSupport.detachDevice()

    @Test
    fun `time sync followed by the mandated logout leaves the device able to export`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.tse_setup_ext(seed, adminPuk, adminPin, timeAdminPin, clientId)

            // The sequence TseMaintenanceTask must perform.
            store.user_login(WormUserId.WORM_USER_TIME_ADMIN, timeAdminPin)
            store.tse_updateTime(System.currentTimeMillis() / 1000)
            store.user_logout(WormUserId.WORM_USER_TIME_ADMIN)

            store.info().use { info ->
                assertEquals(
                    WormUserId.WORM_USER_UNAUTHENTICATED,
                    info.loggedInUser(),
                    "TimeAdmin must not be the resting user after a time sync",
                )
            }

            // The consequence that matters: exports still work.
            store.export_tar(discardingCallback())
        }
    }

    @Test
    fun `leaving TimeAdmin logged in blocks exports - the bug this guards against`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.tse_setup_ext(seed, adminPuk, adminPin, timeAdminPin, clientId)

            // The old sequence: login, update, and NO logout.
            store.user_login(WormUserId.WORM_USER_TIME_ADMIN, timeAdminPin)
            store.tse_updateTime(System.currentTimeMillis() / 1000)

            val failure = runCatching { store.export_tar(discardingCallback()) }.exceptionOrNull()
            assertNotNull(
                failure,
                "an export with TimeAdmin still logged in must fail — if this ever passes, " +
                    "the v2 authorization rule has regressed and the logout is no longer load-bearing",
            )
        }
    }
}
