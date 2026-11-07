package org.example.project.tse.virtual

import com.secureflashcard.wormapi.WormInitializationState
import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * `tse_setup_ext` is the SDK 6.0 helper that orders setup correctly for the attached
 * generation. It replaces the ten hand-rolled steps that fail on TSE 2.0, where
 * registerClient and runSelfTest both require an already-initialized device.
 */
class VirtualTseSetupExtTest {

    private val adminPuk = "888888".toByteArray()
    private val adminPin = "54321".toByteArray()
    private val timeAdminPin = "98765".toByteArray()
    private val seed = "SwissbitSwissbit".toByteArray()
    private val clientId = "POS-TERMINAL-1"

    @AfterEach
    fun tearDown() = VirtualTseTestSupport.detachDevice()

    @Test
    fun `needs setup is true on a fresh device and false afterwards`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            assertTrue(store.tse_needs_setup(), "a fresh device needs setup")

            store.tse_setup_ext(seed, adminPuk, adminPin, timeAdminPin, clientId)

            assertFalse(store.tse_needs_setup(), "a set-up device does not need setup")
        }
    }

    @Test
    fun `setup_ext initializes, registers the client and sets the time`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.tse_setup_ext(seed, adminPuk, adminPin, timeAdminPin, clientId)

            store.info().use { info ->
                assertEquals(WormInitializationState.WORM_INIT_INITIALIZED, info.initializationState())
                assertEquals(1L, info.registeredClients())
                assertTrue(info.hasValidTime(), "v2 setup sets the time")
                assertTrue(info.hasPassedSelfTest())
            }
        }
    }

    @Test
    fun `setup_ext leaves Admin logged in on v2`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.tse_setup_ext(seed, adminPuk, adminPin, timeAdminPin, clientId)
            store.info().use { info ->
                assertEquals(WormUserId.WORM_USER_ADMIN, info.loggedInUser())
            }
        }
    }

    @Test
    fun `setup_ext works on v1 too`() {
        VirtualTseTestSupport.attachDevice("v1")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.tse_setup_ext(seed, adminPuk, adminPin, timeAdminPin, clientId)
            store.info().use { info ->
                assertEquals(WormInitializationState.WORM_INIT_INITIALIZED, info.initializationState())
            }
        }
    }

    @Test
    fun `derived credentials expose the per-user PUKs and Logger PIN`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.user_deriveInitialCredentials(seed).use { creds ->
                assertTrue(creds.adminPuk().isNotEmpty())
                assertTrue(creds.timeAdminPuk().isNotEmpty(), "TSE 2.0 adds a TimeAdmin PUK")
                assertTrue(creds.loggerPuk().isNotEmpty(), "TSE 2.0 adds a Logger PUK")
                assertTrue(creds.loggerPin().isNotEmpty(), "TSE 2.0 adds a Logger PIN")
            }
        }
    }

    @Test
    fun `change_puk_ext changes the PUK of the selected user`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            val initialTimeAdminPuk = store.user_deriveInitialCredentials(seed).use { it.timeAdminPuk() }

            store.user_change_puk_ext(WormUserId.WORM_USER_TIME_ADMIN, initialTimeAdminPuk, "777777".toByteArray())

            store.info().use { info ->
                assertTrue(info.hasChangedTimeAdminPuk())
                assertFalse(info.hasChangedAdminPuk(), "only the selected user's PUK changes")
            }
        }
    }
}
