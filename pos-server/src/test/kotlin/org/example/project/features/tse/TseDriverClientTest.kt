package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.provider.local.TseDriverClient
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which register the in-process TSE driver runs its unattended work for — the one answer used at
 * startup, after re-acquiring the device and for every self test.
 *
 * There used to be two copies, both falling back to *any* active terminal. On TSE 1.x a self test
 * against an unregistered client fails on the device as `WORM_ERROR_CLIENT_NOT_REGISTERED`, which
 * the driver classifies as a hardware error and tears the connection down — so the fallback did
 * strictly worse than having no client, which refuses before any device call.
 */
@DisplayName("TSE driver client — a registered active terminal, never a guess")
class TseDriverClientTest {
    private val repository = TerminalRepositoryImpl()
    private val driverClient = TseDriverClient(repository)

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun terminal(
        registered: Boolean,
        active: Boolean = true,
    ): Terminal =
        transaction {
            repository.createUnbound(name = "Kasse", role = "CASHIER", fiscal = true).also {
                it.tseClientRegistered = registered
                it.isActive = active
            }
        }

    @Test
    fun `an active terminal the TSE knows is chosen over an earlier unregistered one`() {
        terminal(registered = false)
        val registered = terminal(registered = true)

        assertEquals(registered.clientId, runBlocking { driverClient.resolve() })
    }

    @Test
    fun `only unregistered terminals means no client, not a fallback`() {
        terminal(registered = false)

        assertNull(runBlocking { driverClient.resolve() })
    }

    @Test
    fun `a registered but deactivated terminal is not used`() {
        terminal(registered = true, active = false)

        assertNull(runBlocking { driverClient.resolve() })
    }
}
