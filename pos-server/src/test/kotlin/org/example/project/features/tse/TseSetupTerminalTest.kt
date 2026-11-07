package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.provider.local.LocalTseAdminProvider
import org.example.project.features.tse.data.provider.local.LocalTseLifecycleManager
import org.example.project.features.tse.data.provider.local.TseSetupTerminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which terminal a TSE setup registers as the device's first client (owner decision 2026-10-04).
 *
 * Found on the real Swissbit TSE: setup resolved the client from the caller's device only, so the
 * back office — a browser, not a terminal — answered `404 Terminal not found for deviceId=…` and
 * could never commission the TSE. The operator now chooses the terminal; without a choice the
 * caller's own terminal is used, as an older till sends it.
 */
class TseSetupTerminalTest {
    private val repository = TerminalRepositoryImpl()
    private val setupTerminal = TseSetupTerminal(repository)

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun unbound(
        fiscal: Boolean = true,
        active: Boolean = true,
    ): Terminal =
        transaction {
            repository.createUnbound(name = "Bar", role = "CASHIER", fiscal = fiscal).also { it.isActive = active }
        }

    private fun paired(deviceId: String): Terminal = transaction { repository.create(deviceId, null, null, null, null) }

    private fun refusal(
        deviceId: String,
        chosen: String?,
    ): PosException = assertFailsWith<PosException> { runBlocking { setupTerminal.resolve(deviceId, chosen) } }

    @Test
    fun `a chosen active fiscal terminal is used even though no device is paired to it`() {
        val terminal = unbound()

        val clientId = runBlocking { setupTerminal.resolve(BACK_OFFICE, terminal.clientId) }

        assertEquals(terminal.clientId, clientId)
    }

    @Test
    fun `the choice wins over the caller's own terminal`() {
        paired("till-1")
        val chosen = unbound()

        assertEquals(chosen.clientId, runBlocking { setupTerminal.resolve("till-1", chosen.clientId) })
    }

    @Test
    fun `without a choice the caller's own terminal is used, as an older till sends it`() {
        val till = paired("till-1")

        assertEquals(till.clientId, runBlocking { setupTerminal.resolve("till-1", null) })
    }

    @Test
    fun `without a choice a caller that is no terminal is told to choose one`() {
        val ex = refusal(BACK_OFFICE, null)

        assertEquals(PosErrorCode.TSE_SETUP_TERMINAL_REQUIRED, ex.code)
        assertEquals(HttpStatusCode.NotFound, ex.httpStatus)
        assertTrue(ex.message.orEmpty().contains("Choose the terminal"), ex.message)
    }

    @Test
    fun `an unknown chosen terminal is a 404`() {
        val ex = refusal(BACK_OFFICE, "TER-999")

        assertEquals(PosErrorCode.TERMINAL_NOT_FOUND, ex.code)
        assertEquals(HttpStatusCode.NotFound, ex.httpStatus)
    }

    @Test
    fun `a deactivated terminal is refused`() {
        val ex = refusal(BACK_OFFICE, unbound(active = false).clientId)

        assertEquals(PosErrorCode.TERMINAL_DEACTIVATED, ex.code)
        assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
    }

    @Test
    fun `a non-fiscal terminal is refused - it never signs`() {
        val ex = refusal(BACK_OFFICE, unbound(fiscal = false).clientId)

        assertEquals(PosErrorCode.TERMINAL_NOT_FISCAL, ex.code)
        assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
    }

    @Test
    fun `the caller's own terminal must be usable too`() {
        val till = paired("till-1")
        transaction { Terminal.findById(till.id)!!.isActive = false }

        assertEquals(PosErrorCode.TERMINAL_DEACTIVATED, refusal("till-1", null).code)
    }

    @Test
    fun `a blank chosen terminal is a 400`() {
        val ex = refusal(BACK_OFFICE, "  ")

        assertEquals(PosErrorCode.VALIDATION_FAILED, ex.code)
        assertEquals(HttpStatusCode.BadRequest, ex.httpStatus)
    }

    @Test
    fun `a successful setup flags the terminal as TSE-registered, as tse-register does`() {
        val terminal = unbound()
        assertFalse(transaction { Terminal.findById(terminal.id)!!.tseClientRegistered })

        val staleDevice = runBlocking { setupTerminal.markRegistered(terminal.clientId) }

        assertTrue(transaction { Terminal.findById(terminal.id)!!.tseClientRegistered })
        assertNull(staleDevice, "an unpaired terminal has no cached capability to invalidate")
    }

    @Test
    fun `flagging a paired terminal names its device so the capability cache is invalidated`() {
        val till = paired("till-1")

        assertEquals("till-1", runBlocking { setupTerminal.markRegistered(till.clientId) })
    }

    @Test
    fun `a factory reset un-flags every terminal, as the TSE no longer lists any client`() {
        val first = unbound()
        val second = paired("till-2")
        runBlocking {
            setupTerminal.markRegistered(first.clientId)
            setupTerminal.markRegistered(second.clientId)
        }

        runBlocking { setupTerminal.markAllUnregistered() }

        assertFalse(transaction { Terminal.findById(first.id)!!.tseClientRegistered })
        assertFalse(transaction { Terminal.findById(second.id)!!.tseClientRegistered })
    }

    /** The provider refuses an unusable terminal before it reads the config or touches the TSE. */
    @Test
    fun `setup refuses an unusable terminal without touching the TSE`() {
        val provider =
            LocalTseAdminProvider(
                lifecycleManager =
                    LocalTseLifecycleManager(
                        configSource = { error("the TSE configuration must not be read") },
                        locate = { error("the TSE must not be located") },
                    ),
                terminalRepository = repository,
                capabilityResolver = TerminalCapabilityResolver(repository),
            )
        val nonFiscal = unbound(fiscal = false)

        listOf(
            null to PosErrorCode.TSE_SETUP_TERMINAL_REQUIRED,
            "TER-999" to PosErrorCode.TERMINAL_NOT_FOUND,
            nonFiscal.clientId to PosErrorCode.TERMINAL_NOT_FISCAL,
        ).forEach { (chosen, code) ->
            val ex =
                assertFailsWith<PosException> {
                    runBlocking { provider.setup(BACK_OFFICE, credentials(chosen)) }
                }
            assertEquals(code, ex.code, "chosen=$chosen")
        }
    }

    private fun credentials(terminalId: String?) =
        TseDto.SetupCredentialsRequestDto(
            adminPin = "80417",
            adminPuk = "804172",
            credentialSeed = "seed",
            terminalId = terminalId,
        )

    private companion object {
        /** The web back office's synthetic device id — a browser, never a terminal. */
        const val BACK_OFFICE = "web-admin-console"
    }
}
