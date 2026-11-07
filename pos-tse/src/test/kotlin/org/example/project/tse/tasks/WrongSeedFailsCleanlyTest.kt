package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormInitializationState
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * A mistyped credential seed must fail cleanly, and cost no more than it has to.
 *
 * The seed derives the device's initial credentials, so a wrong-but-correct-length seed
 * derives a wrong PUK. The device forbids any login until the PUK has been changed
 * (WormStore.user_login), so the PUK change is the very first credential operation and there
 * is no cheaper way to discover a bad seed — the attempt itself spends one of the device's
 * PUK tries. That cost is inherent and cannot be dodged.
 *
 * What the code can and must guarantee is that it costs no *more* than that: exactly one PUK
 * attempt per operator attempt, with no internal retry turning a single typo into a blocked
 * PUK, and the device left untouched so the right seed still works.
 */
class WrongSeedFailsCleanlyTest {

    private val tseStatus = TseStatus()

    private val realSeed = "SwissbitSwissbit"

    /** Same length, same charset, wrong device — what a transcription error produces. */
    private val typoSeed = "SwissbitSwissblt"

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    private fun connect() {
        // v1: the hand-rolled certified sequence is the one whose order we control. TSE 2.0
        // commissions through the vendor helper, which orders its own steps internally.
        VirtualTseTestSupport.attachDevice("v1")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("TER-001")
        tseStatus.setTimeAdminPin("98765")
        assertTrue(tseStatus.detectHardware())
    }

    private fun setUpWith(seed: String): Boolean =
        TseInitializationManager(tseStatus).setUp(
            adminPin = "54321",
            adminPuk = "888888",
            credentialSeed = seed,
            clientId = "TER-001",
        )

    @Test
    fun `a wrong seed is refused and leaves the device untouched`() {
        connect()

        assertFalse(setUpWith(typoSeed), "a seed that is not this device's must not commission it")
        tseStatus.getWormStore().info().use {
            assertEquals(
                WormInitializationState.WORM_INIT_UNINITIALIZED, it.initializationState(),
                "a failed setup must leave the device commissionable with the right seed",
            )
        }
    }

    @Test
    fun `a wrong seed costs exactly one PUK attempt, not several`() {
        connect()
        val before = VirtualTseTestSupport.pukRemainingRetries(tseStatus.getWormStore())

        setUpWith(typoSeed)

        // The cost is inherent — one attempt — but it must be exactly one. The failure returns
        // straight out of setUp with no internal retry loop; anything more would be the code,
        // not the operator, walking the device toward a blocked PUK.
        assertEquals(
            before - 1,
            VirtualTseTestSupport.pukRemainingRetries(tseStatus.getWormStore()),
            "setup must not retry a wrong seed internally",
        )
    }

    @Test
    fun `the failure records the remaining attempts so the operator can be warned`() {
        connect()

        setUpWith(typoSeed)

        val failure = tseStatus.getLastAuthFailure()
        assertTrue(
            failure?.remainingRetries != null,
            "the operator needs to know how many attempts are left before the PUK blocks",
        )
    }

    @Test
    fun `the correct seed still commissions the device`() {
        connect()

        assertTrue(setUpWith(realSeed), "the guard must not have broken commissioning")
        tseStatus.getWormStore().info().use {
            assertEquals(WormInitializationState.WORM_INIT_INITIALIZED, it.initializationState())
        }
    }
}
