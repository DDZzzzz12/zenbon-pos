package org.example.project.tse.virtual

import com.secureflashcard.wormapi.WormStore

/**
 * Controls the virtual WormAPI device from tests.
 *
 * The `virtualWorm` source set is on the test *runtime* classpath only, so
 * `VirtualWormRegistry` cannot be imported here — adding it to the compile
 * classpath would put a second `com.secureflashcard.wormapi.WormStore` beside the
 * one in `lib/WormAPI.jar`. Everything below therefore goes through system
 * properties and plain `WormStore` calls, which need no compile dependency.
 *
 * These helpers are inert when the real SDK is on the classpath (a host with
 * `TSE_MOUNT_POINT` set): the properties are simply ignored by the real library.
 */
object VirtualTseTestSupport {

    const val PRESENCE_PROPERTY = "tse.virtual.device"
    const val GENERATION_PROPERTY = "tse.virtual.generation"

    const val MOUNT_POINT = "/mnt/tse"

    const val FAULT_PROPERTY = "tse.virtual.fault"

    /**
     * Makes device calls fail host-side, as if the TSE had gone away: every login (so every
     * privileged admin operation), every `transaction_start`/`_update`/`_finish` (the signing
     * path, which never logs in) and every `WormStore(...)` construction (so re-acquisition fails
     * until the fault is cleared — the stick is unplugged, not just flaky).
     *
     * [error] is a `WormError` constant name, e.g. `WORM_ERROR_NO_WORM_CARD` (code 2) or
     * `WORM_ERROR_TIMEOUT` (4) — both below `0x1000`, which is the SDK's own boundary between
     * "the host or the connection failed" and "the card answered". Without this the virtual
     * device can only produce card answers, so the half of the R2-6 rule that must KEEP working
     * — a real hardware loss still drops the connection — would go untested.
     *
     * Sticky: [clearFault] in teardown, which [detachDevice] also does.
     */
    fun armHostFault(error: String) = System.setProperty(FAULT_PROPERTY, error)

    fun clearFault(): Unit = System.clearProperty(FAULT_PROPERTY).let { }

    /** Attaches a fresh virtual TSE of the given generation ("v1" or "v2"). */
    fun attachDevice(generation: String = "v1") {
        detachDevice()
        System.setProperty(GENERATION_PROPERTY, generation)
        System.setProperty(PRESENCE_PROPERTY, "present")
    }

    /**
     * Detaches and discards every virtual device.
     *
     * `VirtualWormRegistry.open` clears its device map whenever presence is off, so
     * constructing one store while absent is what actually drops the state. The
     * constructor throws WORM_ERROR_NO_WORM_CARD by design — that is the reset.
     */
    fun detachDevice() {
        clearFault()
        System.setProperty(PRESENCE_PROPERTY, "absent")
        runCatching { WormStore(MOUNT_POINT).close() }
        System.clearProperty(PRESENCE_PROPERTY)
        System.clearProperty(GENERATION_PROPERTY)
    }

    /**
     * Changes the Admin PUK from its seed-derived initial value, the one prerequisite the real
     * SDK places on any login: "Before any user can be logged in, the initial PUK must have
     * been changed" (WormStore.user_login). Leaves the initial derived PINs in place, so a test
     * can then log in with them exactly as on hardware. Use before logging in on a device that
     * has not been through full setup.
     */
    fun enableLogin(store: WormStore) {
        store.user_deriveInitialCredentials("SwissbitSwissbit".toByteArray()).use { creds ->
            store.user_change_puk(creds.adminPuk(), "888888".toByteArray())
        }
    }

    /** PUK attempts the device will still grant. Probing the PUK is what spends it. */
    fun pukRemainingRetries(store: WormStore): Int {
        val device = store.javaClass.getDeclaredField("device").apply { isAccessible = true }.get(store)
        return device.javaClass.getDeclaredMethod("pukRemainingRetriesForTest")
            .apply { isAccessible = true }.invoke(device) as Int
    }

    /**
     * Marks the self test as due, so the startup self-test path is reachable.
     *
     * Setup leaves the self test passed, so without this checkSelfTestPassed() short-circuits
     * and everything downstream of it is untestable.
     */
    fun expireSelfTest(store: WormStore) {
        val device = store.javaClass.getDeclaredField("device").apply { isAccessible = true }.get(store)
        device.javaClass.getDeclaredMethod("expireSelfTestForTest")
            .apply { isAccessible = true }.invoke(device)
    }

    /** Forces the device's time back to invalid, so the stale-TSE recovery path is reachable. */
    fun invalidateTime(store: WormStore) {
        val device = store.javaClass.getDeclaredField("device").apply { isAccessible = true }.get(store)
        device.javaClass.getDeclaredMethod("invalidateTimeForTest")
            .apply { isAccessible = true }.invoke(device)
    }

    /**
     * Overrides the device's free-space figure so the store-full path is reachable.
     *
     * Real TSEs fill up over months of trading; without a hook the v2 reserve could not
     * be tested at all. Reflective for the same reason as everything else here —
     * `VirtualWormDevice` is not on the test compile classpath.
     */
    fun setSimulatedFreeBytes(store: WormStore, freeBytes: Long) {
        val deviceField = store.javaClass.getDeclaredField("device").apply { isAccessible = true }
        val device = deviceField.get(store)
        device.javaClass
            .getDeclaredMethod("setSimulatedFreeBytes", java.lang.Long.TYPE)
            .apply { isAccessible = true }
            .invoke(device, freeBytes)
    }
}
