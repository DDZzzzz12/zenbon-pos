package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.provider.local.LocalTseAdminProvider
import org.example.project.features.tse.data.provider.local.LocalTseLifecycleManager
import org.example.project.features.tse.data.provider.local.TseCommissioningDevice
import org.example.project.features.tse.data.provider.local.TseCommissioningRequest
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The mode a TSE setup commissions in, and the mode it leaves configured.
 *
 * Found on a real Swissbit TSE: a fresh install is configured DISABLED, setup handed that stored
 * mode to `SetupOperation`, which commissions in NORMAL only — so every setup on a fresh install
 * failed with "mode need to be Normal: DISABLED" and the device stayed UNINITIALIZED. Earlier tests
 * only ever ran against a configuration already set to NORMAL.
 */
class TseSetupModeTest {
    private val repository = TerminalRepositoryImpl()
    private val lifecycleManager =
        LocalTseLifecycleManager(
            configSource = { error("setup reads the shop configuration, not the lifecycle's") },
            locate = { error("the device double stands in for the TSE") },
        )
    private val device = RecordingDevice()
    private var stored = TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "61023")
    private val writes = mutableListOf<TseConfigDto.TseConfig>()

    private val provider =
        LocalTseAdminProvider(
            lifecycleManager = lifecycleManager,
            terminalRepository = repository,
            capabilityResolver = TerminalCapabilityResolver(repository),
            commissioningDevice = device,
            readTseConfig = { stored },
            writeTseConfig = { config ->
                writes += config
                stored = config
            },
        )

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun terminal(): Terminal = transaction { repository.createUnbound(name = "Bar", role = "CASHIER", fiscal = true) }

    private fun setUp(): TseDto.AdminResultDto = runBlocking { provider.setup(BACK_OFFICE, credentials(terminal().clientId)) }

    @Test
    fun `a fresh install configured DISABLED is commissioned in NORMAL and saved as NORMAL`() {
        val result = setUp()

        assertTrue(result.success)
        assertEquals(TseMode.NORMAL, device.commissioned.single().mode, "setup commissions in NORMAL, not the stored DISABLED")
        assertEquals(TseMode.NORMAL, stored.mode, "the configured mode now agrees with the device, so signing is not refused")
        assertEquals("61023", stored.timeAdminPin, "only the mode changes")
        assertEquals(1, device.opened, "the device was opened: nothing opens it while the shop is DISABLED")
    }

    @Test
    fun `a shop already configured NORMAL is commissioned in NORMAL and its configuration is not rewritten`() {
        stored = stored.copy(mode = TseMode.NORMAL)

        assertTrue(setUp().success)

        assertEquals(TseMode.NORMAL, device.commissioned.single().mode)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a refused setup keeps the configured mode DISABLED and sets the live mode DISABLED`() {
        device.refusal = PosException(PosErrorCode.TSE_SETUP_FAILED, HttpStatusCode.InternalServerError, "TSE setup failed")

        val ex = assertFailsWith<PosException> { setUp() }

        assertEquals(PosErrorCode.TSE_SETUP_FAILED, ex.code)
        assertTrue(writes.isEmpty(), "nothing is saved as NORMAL for a TSE that was not commissioned")
        assertEquals(TseMode.DISABLED, stored.mode)
        assertEquals(TseMode.DISABLED, lifecycleManager.getTseStatus().getMode())
    }

    @Test
    fun `an unsuccessful result keeps the configured mode`() {
        device.result = TseDto.AdminResultDto(success = false, message = "no")

        val result = setUp()

        assertEquals(false, result.success)
        assertTrue(writes.isEmpty())
        assertEquals(TseMode.DISABLED, lifecycleManager.getTseStatus().getMode())
    }

    /** SIMULATION is an operator's deliberate choice; setup refuses rather than silently ending it. */
    @Test
    fun `a shop configured SIMULATION is refused before the TSE is touched`() {
        stored = stored.copy(mode = TseMode.SIMULATION)

        val ex = assertFailsWith<PosException> { setUp() }

        assertEquals(PosErrorCode.TSE_INVALID_PARAMETERS, ex.code)
        assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
        assertEquals(0, device.opened)
        assertTrue(device.commissioned.isEmpty())
        assertTrue(writes.isEmpty())
        assertEquals(TseMode.SIMULATION, stored.mode, "the mode is left as the operator chose it")
    }

    @Test
    fun `no TSE to open is refused with nothing written`() {
        device.present = false

        val ex = assertFailsWith<PosException> { setUp() }

        assertEquals(PosErrorCode.TSE_HARDWARE_NOT_FOUND, ex.code)
        assertTrue(device.commissioned.isEmpty())
        assertTrue(writes.isEmpty())
    }

    /** The device is opened before the "already commissioned" check, which cannot read a closed one. */
    @Test
    fun `a commissioned TSE is still refused on a shop configured DISABLED`() {
        device.alreadyCommissioned = true

        val ex = assertFailsWith<PosException> { setUp() }

        assertEquals(PosErrorCode.TSE_ALREADY_INITIALIZED, ex.code)
        assertTrue(device.commissioned.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a commissioned TSE whose mode could not be saved still reports success and says what to do`() {
        val failing =
            LocalTseAdminProvider(
                lifecycleManager = lifecycleManager,
                terminalRepository = repository,
                capabilityResolver = TerminalCapabilityResolver(repository),
                commissioningDevice = device,
                readTseConfig = { stored },
                writeTseConfig = { error("database unavailable") },
            )

        val result = runBlocking { failing.setup(BACK_OFFICE, credentials(terminal().clientId)) }

        assertTrue(result.success, "the TSE is commissioned; a retry would only be told so")
        assertTrue(result.message.contains("set the mode to NORMAL"), result.message)
    }

    /** The production `open()`: locates and opens the TSE although the shop is configured DISABLED. */
    @Test
    fun `setup opens a TSE that a DISABLED start never opened`() {
        val opened = mutableListOf<String>()
        val manager =
            LocalTseLifecycleManager(
                configSource = { error("opening for setup does not consult the configured mode") },
                locate = { "virtual-tse" },
            )

        assertTrue(
            manager.attachForCommissioning { mountPoint ->
                opened += mountPoint
                true
            },
        )
        assertEquals(listOf("virtual-tse"), opened)
    }

    @Test
    fun `setup reports an absent TSE instead of opening nothing`() {
        val manager = LocalTseLifecycleManager(configSource = { stored }, locate = { null })

        assertEquals(false, manager.attachForCommissioning { error("nothing to open") })
    }

    private class RecordingDevice : TseCommissioningDevice {
        val commissioned = mutableListOf<TseCommissioningRequest>()
        var opened = 0
        var present = true
        var alreadyCommissioned = false
        var refusal: PosException? = null
        var result = TseDto.AdminResultDto(success = true, message = "commissioned")

        override fun open(): Boolean {
            opened++
            return present
        }

        override suspend fun isCommissioned(): Boolean = alreadyCommissioned

        override fun commission(request: TseCommissioningRequest): TseDto.AdminResultDto {
            commissioned += request
            refusal?.let { throw it }
            return result
        }
    }

    private fun credentials(terminalId: String) =
        TseDto.SetupCredentialsRequestDto(
            adminPin = "80417",
            adminPuk = "804172",
            credentialSeed = "seed",
            terminalId = terminalId,
        )

    private companion object {
        const val BACK_OFFICE = "web-admin-console"
    }
}
