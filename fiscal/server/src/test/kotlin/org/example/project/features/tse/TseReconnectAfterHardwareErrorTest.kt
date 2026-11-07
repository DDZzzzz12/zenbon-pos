package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.data.provider.local.LocalTseLifecycleManager
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One bad Vorgang must not take the TSE down for the rest of the process.
 *
 * `TseStatus.handleHardwareError()` closes the `WormStore`, drops the connection and sets
 * `mountPoint = null`. Nothing ever put it back: `initialize()` runs at boot and on an operator
 * action, so after the first such error every signing attempt and every health check failed for the
 * process lifetime, and `TseSigningRetryService` skipped each cycle at *debug* level — the queue
 * stalled in silence.
 *
 * Observed live on 2026-09-20 immediately after the virtual TSE was commissioned: queue entry 65
 * raised `WORM_ERROR_CLIENT_NOT_REGISTERED` — a per-client *configuration* problem, not a hardware
 * fault — which was classified as a hardware IO error. Two Vorgänge had signed a moment earlier;
 * the remaining thirteen never did, and `GET /api/tse/status` answered
 * `503 TSE_INVALID_MOUNT_POINT — No mount point specified/found` from then on.
 *
 * [LocalTseLifecycleManager.reacquireIfLost] is the recovery path: when the shop is configured
 * NORMAL and the mount point has been lost, locate again.
 */
@DisplayName("TSE lifecycle — the mount point is re-acquired after a hardware error")
class TseReconnectAfterHardwareErrorTest {
    @Test
    fun `a lost mount point is located again`() {
        var locateCalls = 0
        val manager =
            LocalTseLifecycleManager(
                configSource = { normalConfig() },
                locate = {
                    locateCalls++
                    "virtual-tse"
                },
            )

        val reacquired = runBlocking { manager.reacquireIfLost(currentMountPoint = null, configuredMode = TseMode.NORMAL, attach = { true }) }

        assertTrue(reacquired, "a NORMAL shop with no mount point must try to find its TSE again")
        assertEquals(1, locateCalls)
    }

    @Test
    fun `a healthy connection is left alone`() {
        var locateCalls = 0
        val manager =
            LocalTseLifecycleManager(
                configSource = { normalConfig() },
                locate = {
                    locateCalls++
                    "virtual-tse"
                },
            )

        val reacquired = runBlocking { manager.reacquireIfLost(currentMountPoint = "virtual-tse", configuredMode = TseMode.NORMAL, attach = { true }) }

        assertTrue(!reacquired, "an attached TSE must not be re-probed on every health check")
        assertEquals(0, locateCalls, "re-probing a working device would close and reopen it needlessly")
    }

    @Test
    fun `a shop that is not running NORMAL never probes hardware`() {
        var locateCalls = 0
        val manager =
            LocalTseLifecycleManager(
                configSource = { normalConfig() },
                locate = {
                    locateCalls++
                    "virtual-tse"
                },
            )

        assertTrue(!runBlocking { manager.reacquireIfLost(currentMountPoint = null, configuredMode = TseMode.SIMULATION, attach = { true }) })
        assertTrue(!runBlocking { manager.reacquireIfLost(currentMountPoint = null, configuredMode = TseMode.DISABLED, attach = { true }) })
        assertEquals(0, locateCalls, "SIMULATION and DISABLED have no hardware to find")
    }

    @Test
    fun `a TSE that is still absent reports no recovery`() {
        val manager =
            LocalTseLifecycleManager(
                configSource = { normalConfig() },
                locate = { null },
            )

        assertTrue(
            !runBlocking { manager.reacquireIfLost(currentMountPoint = null, configuredMode = TseMode.NORMAL, attach = { true }) },
            "an unplugged TSE stays unplugged — recovery reports honestly, it does not pretend",
        )
    }

    /**
     * Found on a real Swissbit TSE 1.x (2026-10-10): plugged in while the server was already
     * running, it re-attached but stayed DEGRADED for good. Startup had found no device, so it
     * never configured the driver, and re-acquiring set neither the client nor the TimeAdmin PIN.
     * The power-loss recovery's self test then failed with "no client ID is configured" every two
     * minutes until someone restarted the server.
     */
    @Test
    fun `re-acquiring restores the registered client and the TimeAdmin PIN, as startup does`() {
        val manager =
            LocalTseLifecycleManager(
                configSource = { normalConfig() },
                locate = { "virtual-tse" },
                resolveClientId = { "TER-003" },
            )

        runBlocking { manager.reacquireIfLost(currentMountPoint = null, configuredMode = TseMode.NORMAL, attach = { true }) }

        assertEquals("TER-003", manager.getTseStatus().getClientId(), "the self test after a replug needs a registered client")
        assertEquals("654321", manager.getTseStatus().getTimeAdminPin(), "a blank PIN would cost a TimeAdmin login attempt")
    }

    @Test
    fun `with no registered terminal re-acquiring sets no client at all`() {
        val manager =
            LocalTseLifecycleManager(
                configSource = { normalConfig() },
                locate = { "virtual-tse" },
                resolveClientId = { null },
            )

        runBlocking { manager.reacquireIfLost(currentMountPoint = null, configuredMode = TseMode.NORMAL, attach = { true }) }

        assertNull(manager.getTseStatus().getClientId(), "never an unregistered or invented client")
    }

    @Test
    fun `a device that does not answer is not configured`() {
        var resolved = 0
        val manager =
            LocalTseLifecycleManager(
                configSource = { normalConfig() },
                locate = { "virtual-tse" },
                resolveClientId = {
                    resolved++
                    "TER-003"
                },
            )

        runBlocking { manager.reacquireIfLost(currentMountPoint = null, configuredMode = TseMode.NORMAL, attach = { false }) }

        assertEquals(0, resolved, "nothing to configure until the device answers; the next tick tries again")
        assertNull(manager.getTseStatus().getClientId())
    }

    private fun normalConfig() =
        org.example.project.core.domain.dto.tse.TseConfigDto.TseConfig(
            timeAdminPin = "654321",
            mode = TseMode.NORMAL,
        )
}
