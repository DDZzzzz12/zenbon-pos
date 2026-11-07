package org.example.project.tse.virtualworm

import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry backing the virtual WormAPI test double (`virtualWorm` source set).
 *
 * Device presence is controlled by the [DEVICE_PRESENCE_PROPERTY] system property:
 *
 * - unset or `"absent"` (default): `WormStore(mountPoint)` throws
 *   WORM_ERROR_NO_WORM_CARD — the exact behavior of the real SDK on a machine whose
 *   native library loads but has no TSE attached. This keeps the existing
 *   "fails gracefully without hardware" tests meaningful on every OS, including macOS
 *   where no native WormAPI exists at all.
 * - `"present"`: a [VirtualWormDevice] is created (and reused) per mount point, giving
 *   tests a connectable in-memory TSE with realistic initialization/login/transaction
 *   semantics. Devices persist for the JVM lifetime; flipping the property back to
 *   absent clears them, so a test enabling presence should reset the property in
 *   teardown (`System.clearProperty`) to hand the next test a clean slate.
 *
 * Real-hardware runs never see these classes: when the `TSE_MOUNT_POINT` environment
 * variable is set, `pos-tse/build.gradle.kts` keeps the real `lib/WormAPI.jar` on the
 * test classpath instead of this source set.
 */
object VirtualWormRegistry {

    const val DEVICE_PRESENCE_PROPERTY = "tse.virtual.device"

    const val DEVICE_GENERATION_PROPERTY = "tse.virtual.generation"

    /**
     * Arms a host-side failure, named by its [WormError] constant (e.g. `WORM_ERROR_NO_WORM_CARD`).
     *
     * The virtual device fails in exactly one way on its own — absent at construction — so every
     * failure a test can reach naturally is an answer from the card. That leaves the complementary
     * case unprovable: that a genuine loss of the device STILL resets the connection. Since R2-6
     * made that distinction load-bearing (an answer keeps the connection, a host error drops it),
     * the case that must keep working needs a test as much as the case that changed, or a
     * classification error in the other direction would pass the suite unnoticed.
     *
     * Sticky until cleared, like the presence property, and read through a system property because
     * this source set is on the test RUNTIME classpath only — a test cannot import this object.
     */
    const val FAULT_PROPERTY = "tse.virtual.fault"

    private val devices = ConcurrentHashMap<String, VirtualWormDevice>()

    private val bannerLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isDevicePresent(): Boolean =
        System.getProperty(DEVICE_PRESENCE_PROPERTY, "absent").equals("present", ignoreCase = true)

    /**
     * Generation the next created device will use. Read per [open] so a test can attach
     * a v1 device and a v2 device in successive tests without restarting the JVM.
     */
    fun configuredGeneration(): VirtualWormDevice.Generation =
        when (System.getProperty(DEVICE_GENERATION_PROPERTY, "v1").lowercase()) {
            "v2", "2", "2.0", "tse2" -> VirtualWormDevice.Generation.TSE_V2
            else -> VirtualWormDevice.Generation.TSE_V1
        }

    /**
     * Called by the virtual `WormStore` constructor. Throws WORM_ERROR_NO_WORM_CARD
     * when no virtual device is present (the default).
     */
    fun open(mountPoint: String?): VirtualWormDevice {
        if (!isDevicePresent()) {
            devices.clear()
            throw WormException(WormError.WORM_ERROR_NO_WORM_CARD.swigValue())
        }
        // An armed fault also fails re-acquisition, so a test can prove the POS does NOT reconnect
        // while the stick is gone. The device map is kept: clearing the fault is re-plugging the
        // same, still-commissioned stick, not attaching a factory-fresh one.
        raiseArmedFault()
        if (bannerLogged.compareAndSet(false, true)) {
            val generation = if (configuredGeneration() == VirtualWormDevice.Generation.TSE_V2) "2.0" else "1.x"
            // System.out with an explicit flush, not System.err: Gradle's JavaExec does
            // not surface stderr in the captured run output, and a warning nobody sees
            // is worthless for something this consequential.
            System.out.println(
                """
                ****************************************************************
                *  VIRTUAL TSE ACTIVE - NOT A CERTIFIED SECURITY DEVICE        *
                *  Emulating TSE $generation. Signatures are simulated and have  *
                *  NO FISCAL VALIDITY. Development and testing only.           *
                ****************************************************************
                """.trimIndent(),
            )
            System.out.flush()
        }
        return devices.computeIfAbsent(mountPoint ?: "") { VirtualWormDevice(it, configuredGeneration()) }
    }

    /**
     * Throws the armed fault, if any. Called from:
     * - [VirtualWormDevice.login] — the choke point every privileged admin operation passes through;
     * - `transactionStart`/`transactionUpdate`/`transactionFinish` — the signing path, which never
     *   logs in, so without these a hardware loss mid-sale could not be simulated;
     * - [open] — so re-acquiring the device fails until the fault is cleared ("stick unplugged").
     *
     * Test-only by construction: this source set is swapped onto the `:pos-tse:test` runtime
     * classpath (and `:pos-server:runVirtual`) only, never packaged into `pos-tse.jar`.
     *
     * An unknown or unset property name is not a fault: a typo must not silently disarm the test,
     * so the name is resolved strictly and an unrecognised one throws [IllegalArgumentException].
     */
    fun raiseArmedFault() {
        val name = System.getProperty(FAULT_PROPERTY)?.takeIf { it.isNotBlank() } ?: return
        val error =
            WormError.entries.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("Unknown WormError armed in $FAULT_PROPERTY: $name")
        throw WormException(error.swigValue())
    }

    /** Drops all virtual devices (fresh factory state on next open). */
    fun reset() {
        devices.clear()
    }
}
