package org.example.project.tse.virtual

import com.secureflashcard.wormapi.WormStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/** The generation switch must be observable through the same API production code uses. */
class VirtualTseGenerationTest {

    @AfterEach
    fun tearDown() = VirtualTseTestSupport.detachDevice()

    @Test
    fun `v1 device reports isTSEv2 false and a 1_x software version`() {
        VirtualTseTestSupport.attachDevice("v1")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.info().use { info ->
                assertFalse(info.isTSEv2())
                assertEquals(1L, info.softwareVersion() shr 16, "major version should be 1")
            }
        }
    }

    @Test
    fun `v2 device reports isTSEv2 true and a 2_x software version`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.info().use { info ->
                assertTrue(info.isTSEv2())
                assertEquals(2L, info.softwareVersion() shr 16, "major version should be 2")
            }
        }
    }
}
