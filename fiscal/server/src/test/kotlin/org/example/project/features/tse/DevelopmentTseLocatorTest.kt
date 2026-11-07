package org.example.project.features.tse

import org.example.project.features.tse.data.provider.local.DevelopmentTseLocator
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:pos-server:runVirtual` must actually produce a TSE.
 *
 * The task puts `:pos-tse`'s `virtualWorm` classes on the classpath in place of `WormAPI.jar` and
 * sets `-Dtse.virtual.device=present`, but that property is read only by the virtual `WormStore`
 * — one layer *below* the layer that decides whether a device exists at all. Device discovery went
 * through `TseLocator.findAndValidateTse()`, which finds a TSE purely by scanning `/mnt`, `/mount` and
 * `/media` for marker files. macOS has neither directory and both sit on the
 * read-only system volume, so `runVirtual` yielded NORMAL-but-unreachable and every signed path
 * degraded to the queue (E2E-2026-09-20 #1).
 *
 * The synthetic mount point is still handed to the SAME hardware validation, which is what keeps
 * this out of production: with the real Swissbit SDK on the classpath nothing can open
 * "virtual-tse", validation fails, and the result is null — a missing TSE, never a fake one.
 */
@DisplayName("Development TSE locator — a virtual device is located, a real build is unaffected")
class DevelopmentTseLocatorTest {
    @Test
    fun `without the virtual-device property the filesystem scan decides`() {
        var validated = false

        val located =
            DevelopmentTseLocator.locate(
                virtualDevicePresent = false,
                validate = {
                    validated = true
                    true
                },
                scan = { "/mnt/SWISSBIT" },
            )

        assertEquals("/mnt/SWISSBIT", located)
        assertTrue(!validated, "the real path validates inside the scan; this must not double-validate")
    }

    @Test
    fun `with a virtual device present the synthetic mount point is located`() {
        var scanned = false

        val located =
            DevelopmentTseLocator.locate(
                virtualDevicePresent = true,
                validate = { true },
                scan = {
                    scanned = true
                    null
                },
            )

        assertEquals(DevelopmentTseLocator.VIRTUAL_MOUNT_POINT, located)
        assertTrue(!scanned, "a virtual run must not depend on /mnt existing")
    }

    /**
     * The production safety property: the flag alone can never conjure a TSE. Without the virtual
     * `WormStore` on the classpath the real SDK cannot open the synthetic path, so validation
     * fails and the server sees no device — exactly as it would with no TSE plugged in.
     */
    @Test
    fun `a virtual mount point that does not validate yields no device`() {
        val located =
            DevelopmentTseLocator.locate(
                virtualDevicePresent = true,
                validate = { false },
                scan = { "/mnt/SWISSBIT" },
            )

        assertNull(located, "an unvalidated mount point must never be reported as a TSE")
    }

    @Test
    fun `the property is read case-insensitively and defaults to absent`() {
        assertTrue(!DevelopmentTseLocator.virtualDevicePresent(null))
        assertTrue(!DevelopmentTseLocator.virtualDevicePresent("absent"))
        assertTrue(DevelopmentTseLocator.virtualDevicePresent("present"))
        assertTrue(DevelopmentTseLocator.virtualDevicePresent("PRESENT"))
    }
}
