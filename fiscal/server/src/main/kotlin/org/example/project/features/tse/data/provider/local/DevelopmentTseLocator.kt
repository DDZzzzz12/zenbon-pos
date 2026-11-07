package org.example.project.features.tse.data.provider.local

import org.example.project.tse.utils.TseLocator
import org.slf4j.LoggerFactory

/**
 * Finds the TSE, including the in-memory one `:pos-server:runVirtual` attaches.
 *
 * `runVirtual` puts `:pos-tse`'s `virtualWorm` classes on the classpath in place of `WormAPI.jar`
 * and sets `-Dtse.virtual.device=present`. That property is read only by the virtual `WormStore`,
 * one layer below the layer that decides whether a device exists at all: discovery went through
 * [TseLocator.findAndValidateTse], which locates a TSE purely by scanning `/mnt`, `/mount` and
 * `/media` for marker files. macOS has neither directory and both sit on the read-only system
 * volume, so a virtual run yielded NORMAL-but-unreachable and every signed path degraded to the
 * queue (E2E-2026-09-20 #1).
 *
 * **Why this cannot fake a TSE in production.** The synthetic mount point is handed to the same
 * [TseLocator.validateTseHardware] as any real one, which opens a `WormStore` there and reads the
 * serial. In a shipped build that is the real Swissbit SDK, which cannot open `virtual-tse`, so
 * validation fails and the result is `null` — a missing TSE, never a fabricated one. The flag on
 * its own conjures nothing; the virtual device only exists when the `virtualWorm` classes were
 * deliberately substituted at build time, which `packageForDeployment` never does.
 */
object DevelopmentTseLocator {
    private val logger = LoggerFactory.getLogger(DevelopmentTseLocator::class.java)

    /**
     * Mount point handed to the virtual `WormStore`, which keys its in-memory devices by path and
     * accepts any string. Deliberately not a filesystem path: nothing should ever create it.
     */
    const val VIRTUAL_MOUNT_POINT = "virtual-tse"

    private const val DEVICE_PRESENCE_PROPERTY = "tse.virtual.device"

    /** Mirrors `VirtualWormRegistry.isDevicePresent()`, which this module cannot see. */
    fun virtualDevicePresent(propertyValue: String? = System.getProperty(DEVICE_PRESENCE_PROPERTY)): Boolean =
        propertyValue.equals("present", ignoreCase = true)

    /**
     * @param validate opens the candidate and reads its serial — the real gate, for virtual and
     *   hardware alike.
     * @param scan the filesystem marker-file scan, which validates its own hit.
     * @return the mount point to use, or null when there is no usable TSE.
     */
    fun locate(
        virtualDevicePresent: Boolean = virtualDevicePresent(),
        validate: (String) -> Boolean = TseLocator::validateTseHardware,
        scan: () -> String? = TseLocator::findAndValidateTse,
    ): String? {
        if (!virtualDevicePresent) return scan()

        logger.warn(
            "tse.virtual.device=present — probing the VIRTUAL TSE at '$VIRTUAL_MOUNT_POINT'. " +
                "This is a development run; a packaged build has the real SDK and will not validate it.",
        )
        return VIRTUAL_MOUNT_POINT.takeIf(validate)
    }
}
