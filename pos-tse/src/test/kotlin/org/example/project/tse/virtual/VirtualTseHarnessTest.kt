package org.example.project.tse.virtual

import com.secureflashcard.wormapi.WormInitializationState
import com.secureflashcard.wormapi.WormStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.AfterEach

/** Verifies the harness itself: presence toggling, isolation between tests. */
class VirtualTseHarnessTest {

    @AfterEach
    fun tearDown() = VirtualTseTestSupport.detachDevice()

    @Test
    fun `no device attached by default`() {
        VirtualTseTestSupport.detachDevice()
        assertFailsWith<Exception> { WormStore(VirtualTseTestSupport.MOUNT_POINT) }
        Unit
    }

    @Test
    fun `attached device starts uninitialized`() {
        VirtualTseTestSupport.attachDevice()
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.info().use { info ->
                assertEquals(WormInitializationState.WORM_INIT_UNINITIALIZED, info.initializationState())
            }
        }
    }

    @Test
    fun `each test gets a fresh device`() {
        // If state leaked from the previous test, this device would already be set up.
        VirtualTseTestSupport.attachDevice()
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.info().use { info ->
                assertEquals(WormInitializationState.WORM_INIT_UNINITIALIZED, info.initializationState())
                assertEquals(0L, info.registeredClients())
            }
        }
    }
}
