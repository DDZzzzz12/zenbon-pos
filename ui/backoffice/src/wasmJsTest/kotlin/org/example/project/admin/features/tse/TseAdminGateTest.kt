package org.example.project.admin.features.tse

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.tse.presentation.TseAdminGate
import org.example.project.core.network.error.PosApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TseAdminGateTest {
    @Test
    fun unlock_correctPin_opensSitting() =
        runTest {
            val fake = FakeTseSettingsApiClient()
            val gate = TseAdminGate(fake)
            assertTrue(gate.unlock("1234"))
            assertTrue(gate.status())
        }

    @Test
    fun unlock_wrongPin_staysLocked() =
        runTest {
            val fake = FakeTseSettingsApiClient()
            val gate = TseAdminGate(fake)
            assertEquals(false, gate.unlock("0000"))
            assertEquals(false, gate.status())
        }

    @Test
    fun runUnlockingIfNeeded_locked_unlocksAndRetriesOnce() =
        runTest {
            val fake = FakeTseSettingsApiClient() // starts locked
            val gate = TseAdminGate(fake)
            val result = gate.runUnlockingIfNeeded(providePin = { "1234" }) { fake.signingOp() }
            assertEquals("ok", result)
            assertEquals(listOf("1234"), fake.unlockPins) // exactly one unlock
        }

    @Test
    fun runUnlockingIfNeeded_alreadyUnlocked_doesNotUnlock() =
        runTest {
            val fake = FakeTseSettingsApiClient().apply { unlocked = true }
            val gate = TseAdminGate(fake)
            val result = gate.runUnlockingIfNeeded(providePin = { "1234" }) { fake.signingOp() }
            assertEquals("ok", result)
            assertTrue(fake.unlockPins.isEmpty()) // block succeeded first try
        }

    @Test
    fun runUnlockingIfNeeded_pinDeclined_rethrowsLocked() =
        runTest {
            val fake = FakeTseSettingsApiClient()
            val gate = TseAdminGate(fake)
            assertFailsWith<PosApiException> {
                gate.runUnlockingIfNeeded(providePin = { null }) { fake.signingOp() } // operator cancels
            }
            Unit
        }

    @Test
    fun runUnlockingIfNeeded_nonLockedError_notRetried() =
        runTest {
            val fake = FakeTseSettingsApiClient()
            val gate = TseAdminGate(fake)
            var calls = 0
            assertFailsWith<IllegalStateException> {
                gate.runUnlockingIfNeeded(providePin = { "1234" }) {
                    calls++
                    throw IllegalStateException("boom")
                }
            }
            assertEquals(1, calls) // no retry for a non-423 failure
            assertTrue(fake.unlockPins.isEmpty())
            Unit
        }
}
