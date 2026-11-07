package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.terminal.data.repository.TerminalPairingCodeRepositoryImpl
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.data.service.BrowserStationPolicy
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.data.service.TerminalServiceImpl
import org.example.project.features.terminal.database.tables.TerminalPairingCodeTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.models.TerminalPairingCode
import org.example.project.features.tse.data.service.TseTerminalFiscalGateway
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F42: pairing registers the terminal's TSE client — but only for a code the back office issued
 * with `registerTseClient`. The fake device behaves like `LocalTseAdminProvider`: listing and
 * registering clients both need the Admin login, so they answer 423 while the sitting is locked.
 */
@DisplayName("Pairing registers the TSE client only when the code asks for it")
class TerminalPairingTseRegistrationTest {
    private var mode = TseMode.NORMAL
    private var initialized = true
    private val knownClients = mutableListOf<String>()
    private var registerFailure: PosException? = null

    /** Every TSE method the service called, in order. */
    private val tseCalls = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        TseAdminSession.clear()
    }

    @AfterEach
    fun teardown() {
        TseAdminSession.clear()
        TestDatabase.teardown()
    }

    private fun locked() = PosException(PosErrorCode.TSE_ADMIN_LOCKED, HttpStatusCode.Locked, "locked")

    private val device: TseAdminProvider =
        Proxy.newProxyInstance(
            TseAdminProvider::class.java.classLoader,
            arrayOf(TseAdminProvider::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "toString" -> return@InvocationHandler "FakeDevice"
                    "hashCode" -> return@InvocationHandler 0
                    "equals" -> return@InvocationHandler false
                }
                tseCalls += method.name
                when (method.name) {
                    "isInitialized" -> initialized
                    "listClients" -> {
                        if (!TseAdminSession.isUnlocked()) throw locked()
                        TseDto.ClientListResultDto(true, knownClients.toList(), knownClients.size)
                    }
                    "registerClient" -> {
                        if (!TseAdminSession.isUnlocked()) throw locked()
                        registerFailure?.let { throw it }
                        val clientId = args!![0] as String
                        knownClients += clientId
                        TseDto.ClientRegistrationResultDto(true, clientId)
                    }
                    else -> throw UnsupportedOperationException("TseAdminProvider.${method.name} is not used here")
                }
            },
        ) as TseAdminProvider

    private val config: IShopConfigurationManager =
        Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTseMode" -> mode
                "toString" -> "FakeConfig"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("IShopConfigurationManager.${method.name} is not used here")
            }
        } as IShopConfigurationManager

    private val service: TerminalServiceImpl by lazy {
        val repository = TerminalRepositoryImpl()
        TerminalServiceImpl(
            repository = repository,
            pairingCodeRepository = TerminalPairingCodeRepositoryImpl(),
            fiscalGateway = TseTerminalFiscalGateway(device),
            configManager = config,
            capabilityResolver = TerminalCapabilityResolver(repository),
            browserStationPolicy = BrowserStationPolicy(config),
        )
    }

    private fun terminal(
        fiscal: Boolean = true,
        registered: Boolean = false,
    ): Long =
        transaction {
            TerminalRepositoryImpl()
                .createUnbound("Till", "CASHIER", fiscal)
                .also { it.tseClientRegistered = registered }
                .id.value
        }

    private fun clientIdOf(id: Long): String = transaction { Terminal.findById(id)!!.clientId }

    private fun isRegistered(id: Long): Boolean = transaction { Terminal.findById(id)!!.tseClientRegistered }

    private fun boundDevice(id: Long): String? = transaction { Terminal.findById(id)!!.deviceId }

    private fun storedCode(code: String): TerminalPairingCode = transaction { TerminalPairingCode.find { TerminalPairingCodeTable.code eq code }.single() }

    private fun issue(
        id: Long,
        registerTseClient: Boolean,
    ): String = runBlocking { service.generatePairingCode("emp", id, registerTseClient) }.code

    private fun pair(
        code: String,
        deviceId: String = "device-1",
    ): TerminalDto.PairDeviceResponseDto = runBlocking { service.pairDevice(TerminalDto.PairDeviceRequestDto(code = code, deviceId = deviceId)) }

    private fun refusal(block: () -> Unit): PosException = assertFailsWith<PosException> { block() }

    // ---- registerTseClient = false: today's pairing, untouched ---------------------------------

    @Test
    fun `without the flag pairing makes no TSE call and needs no unlock`() {
        val id = terminal()

        val code = issue(id, registerTseClient = false)
        pair(code)

        assertEquals(emptyList(), tseCalls, "a code that registers nothing must not reach the TSE")
        assertEquals("device-1", boundDevice(id))
        assertFalse(isRegistered(id), "the terminal stays unregistered; TSE registrieren is the manual path")
        assertNull(storedCode(code).adminSessionEpoch, "a code that registers nothing is bound to no sitting")
    }

    @Test
    fun `without the flag an unlocked sitting no longer binds the code`() {
        TseAdminSession.unlock("123456")
        val id = terminal()

        val code = issue(id, registerTseClient = false)
        TseAdminSession.clear()
        pair(code)

        assertEquals("device-1", boundDevice(id), "the sitting ending must not kill a code that registers nothing")
        assertEquals(emptyList(), tseCalls)
    }

    // ---- registerTseClient = true: refused where it cannot be honoured -------------------------

    @Test
    fun `the flag is refused while the TSE is in simulation`() {
        mode = TseMode.SIMULATION
        val id = terminal()

        val e = refusal { issue(id, registerTseClient = true) }

        assertEquals(PosErrorCode.TSE_NOT_OPERATIONAL, e.code)
        assertEquals(HttpStatusCode.Conflict, e.httpStatus)
    }

    @Test
    fun `the flag is refused while the TSE is disabled`() {
        mode = TseMode.DISABLED
        val id = terminal()

        val e = refusal { issue(id, registerTseClient = true) }

        assertEquals(PosErrorCode.TSE_DISABLED, e.code)
    }

    @Test
    fun `the flag is refused while the TSE is not commissioned`() {
        initialized = false
        val id = terminal()

        val e = refusal { issue(id, registerTseClient = true) }

        assertEquals(PosErrorCode.TSE_NOT_OPERATIONAL, e.code)
    }

    @Test
    fun `the flag is refused for a non-fiscal terminal`() {
        val id = terminal(fiscal = false)

        val e = refusal { issue(id, registerTseClient = true) }

        assertEquals(PosErrorCode.TERMINAL_NOT_FISCAL, e.code)
        assertEquals(emptyList(), tseCalls)
    }

    @Test
    fun `the flag for a client the TSE does not list needs the admin unlock`() {
        val id = terminal()

        val e = refusal { issue(id, registerTseClient = true) }

        assertEquals(PosErrorCode.TSE_ADMIN_LOCKED, e.code)
        assertEquals(HttpStatusCode.Locked, e.httpStatus)
    }

    @Test
    fun `the flag for a terminal recorded as registered needs no unlock`() {
        val id = terminal(registered = true)

        val code = issue(id, registerTseClient = true)

        assertNull(storedCode(code).adminSessionEpoch)
    }

    // ---- registerTseClient = true: pairing registers ------------------------------------------

    @Test
    fun `a client the TSE already lists is only flagged, never registered again`() {
        TseAdminSession.unlock("123456")
        val id = terminal()
        knownClients += clientIdOf(id)

        val code = issue(id, registerTseClient = true)
        pair(code)

        assertFalse("registerClient" in tseCalls, "a listed client needs no second registration")
        assertTrue(isRegistered(id))
        assertNull(storedCode(code).adminSessionEpoch, "a listed client needs no sitting")
    }

    @Test
    fun `a client the TSE does not list is registered while pairing`() {
        TseAdminSession.unlock("123456")
        val id = terminal()

        val code = issue(id, registerTseClient = true)
        // Negative control: issuing the code registered nothing and flipped no flag — only pairing does.
        assertFalse("registerClient" in tseCalls)
        assertFalse(isRegistered(id))
        assertNotNull(storedCode(code).adminSessionEpoch, "a code that registers is bound to the sitting")

        pair(code)

        assertTrue("registerClient" in tseCalls)
        assertEquals(listOf(clientIdOf(id)), knownClients)
        assertTrue(isRegistered(id))
        assertEquals("device-1", boundDevice(id))
    }

    @Test
    fun `a flagged client the TSE lost is registered again while pairing`() {
        TseAdminSession.unlock("123456")
        val id = terminal(registered = true) // e.g. after a factory reset of the TSE

        val code = issue(id, registerTseClient = true)
        pair(code)

        assertTrue("registerClient" in tseCalls, "the device, not the flag, decides")
        assertEquals(listOf(clientIdOf(id)), knownClients)
    }

    @Test
    fun `a failed registration binds nothing and leaves the code usable`() {
        TseAdminSession.unlock("123456")
        val id = terminal()
        val code = issue(id, registerTseClient = true)
        registerFailure = PosException(PosErrorCode.TSE_OPERATION_REJECTED, HttpStatusCode.Conflict, "card said no")

        val e = refusal { pair(code) }

        assertEquals(PosErrorCode.PAIRING_TSE_REGISTRATION_FAILED, e.code)
        assertEquals(HttpStatusCode.FailedDependency, e.httpStatus)
        assertNull(boundDevice(id), "nothing may be bound when the TSE refused")
        assertFalse(isRegistered(id))
        assertNull(storedCode(code).consumedAt, "the code must stay unconsumed")

        registerFailure = null
        pair(code)

        assertEquals("device-1", boundDevice(id), "the same code pairs once the TSE accepts")
        assertTrue(isRegistered(id))
    }

    @Test
    fun `a code whose sitting ended is refused before the TSE is asked`() {
        TseAdminSession.unlock("123456")
        val id = terminal()
        val code = issue(id, registerTseClient = true)
        TseAdminSession.clear()
        tseCalls.clear()

        val e = refusal { pair(code) }

        assertEquals(PosErrorCode.PAIRING_CODE_EXPIRED, e.code)
        assertEquals(emptyList(), tseCalls)
        assertNull(boundDevice(id))
    }

    @Test
    fun `the flag is refused at pairing when the TSE left NORMAL mode since`() {
        TseAdminSession.unlock("123456")
        val id = terminal()
        val code = issue(id, registerTseClient = true)
        mode = TseMode.SIMULATION

        val e = refusal { pair(code) }

        assertEquals(PosErrorCode.PAIRING_TSE_REGISTRATION_FAILED, e.code)
        assertNull(boundDevice(id))
    }

    @Test
    fun `the pairing status reports availability only for a commissioned NORMAL TSE`() {
        assertTrue(runBlocking { service.getTsePairingStatus() }.available)

        initialized = false
        assertFalse(runBlocking { service.getTsePairingStatus() }.available)

        initialized = true
        mode = TseMode.SIMULATION
        assertFalse(runBlocking { service.getTsePairingStatus() }.available)
    }
}
