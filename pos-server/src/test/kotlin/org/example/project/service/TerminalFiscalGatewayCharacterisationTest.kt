package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.domain.models.Orders
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
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Characterisation of every point at which `TerminalServiceImpl` reaches the TSE, the TSE admin
 * sitting or the ecr signing state — pinned BEFORE those calls move behind `TerminalFiscalGateway`
 * (ZenBon restructuring B2-1a, Task 11). Each refusal is pinned by exact code, HTTP status and
 * message, and each TSE path by the exact sequence of device calls, so the extraction can be shown
 * to change neither what is refused nor in which order the device is asked.
 *
 * The fake device behaves like `LocalTseAdminProvider`: listing and registering clients need the
 * Admin login and answer 423 while the sitting is locked.
 */
@DisplayName("TerminalServiceImpl — TSE client lifecycle characterisation")
class TerminalFiscalGatewayCharacterisationTest {
    private var mode = TseMode.NORMAL
    private var initialized = true
    private val knownClients = mutableListOf<String>()
    private var listResult: TseDto.ClientListResultDto? = null
    private var registerFailure: PosException? = null
    private var registerResult: TseDto.ClientRegistrationResultDto? = null
    private var deregisterResult: TseDto.AdminResultDto? = null
    private var startedTransactions = 0

    /** Every TSE method the service called, in order, with its first argument when it has one. */
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
                val firstArg = args?.firstOrNull()?.takeIf { it is String }
                tseCalls += if (firstArg != null) "${method.name}($firstArg)" else method.name
                when (method.name) {
                    "isInitialized" -> initialized
                    "listClients" -> {
                        if (!TseAdminSession.isUnlocked()) throw locked()
                        listResult ?: TseDto.ClientListResultDto(true, knownClients.toList(), knownClients.size)
                    }
                    "registerClient" -> {
                        if (!TseAdminSession.isUnlocked()) throw locked()
                        registerFailure?.let { throw it }
                        val clientId = args!![0] as String
                        registerResult ?: run {
                            knownClients += clientId
                            TseDto.ClientRegistrationResultDto(true, clientId)
                        }
                    }
                    "deregisterClient" -> deregisterResult ?: TseDto.AdminResultDto(true, "deregistered")
                    "listTransactions" ->
                        TseDto.TransactionListResultDto(true, List(startedTransactions) { it.toLong() }, startedTransactions)
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

    /** The only place this class builds the service — the extraction changes how, not what. */
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

    // ---- fixtures -------------------------------------------------------------------------------

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

    private fun rawTerminal(
        clientId: String,
        active: Boolean,
        boundTo: String?,
        registered: Boolean,
    ): Long =
        transaction {
            val now = currentTimeStamp()
            Terminal
                .new {
                    this.deviceId = boundTo
                    this.clientId = clientId
                    this.registeredAt = now
                    this.lastSeenAt = now
                    this.isActive = active
                    this.tseClientRegistered = registered
                    if (boundTo != null) {
                        this.deviceTokenHash = "hash-$clientId"
                        this.boundAt = now
                    }
                }.id.value
        }

    private fun seedBill(
        terminalId: Long,
        status: TseSigningStatus,
        tableNumber: String,
    ) {
        val session = TestData.createTableWithSession(tableNumber = tableNumber).session
        transaction {
            val now = currentTimeStamp()
            Bill.new {
                this.employeeId = session.employeeId
                this.tableSession = session
                this.billNumber = "CHR-BILL-$tableNumber"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.startTime = now
                this.endTime = now
                this.terminal = Terminal.findById(terminalId)!!
                this.tseSigningStatus = status
            }
        }
    }

    private fun seedOrder(
        terminalId: Long,
        status: TseSigningStatus,
        tableNumber: String,
    ) {
        val session = TestData.createTableWithSession(tableNumber = tableNumber).session
        transaction {
            val now = currentTimeStamp()
            Orders.new {
                this.orderNumber = "CHR-ORDER-$tableNumber"
                this.employee = session.employeeId
                this.tableSession = session
                this.orderTime = now
                this.orderTotal = BigDecimal("10.00")
                this.orderStatus = OrderStatus.CONFIRMED
                this.terminal = Terminal.findById(terminalId)!!
                this.tseSigningStatus = status
            }
        }
    }

    private fun clientIdOf(id: Long): String = transaction { Terminal.findById(id)!!.clientId }

    private fun isRegistered(id: Long): Boolean = transaction { Terminal.findById(id)!!.tseClientRegistered }

    private fun boundDevice(id: Long): String? = transaction { Terminal.findById(id)!!.deviceId }

    private fun storedCode(code: String): TerminalPairingCode = transaction { TerminalPairingCode.find { TerminalPairingCodeTable.code eq code }.single() }

    private fun issue(
        id: Long,
        registerTseClient: Boolean = true,
    ): String = runBlocking { service.generatePairingCode("emp", id, registerTseClient) }.code

    private fun pair(code: String) = runBlocking { service.pairDevice(TerminalDto.PairDeviceRequestDto(code = code, deviceId = "device-1")) }

    private fun refusal(block: suspend () -> Unit): PosException = assertFailsWith<PosException> { runBlocking { block() } }

    private fun assertRefusal(
        e: PosException,
        code: PosErrorCode,
        status: HttpStatusCode,
        message: String,
    ) {
        assertEquals(code, e.code, "code")
        assertEquals(status, e.httpStatus, "status")
        assertEquals(message, e.message, "message")
    }

    // ---- (a) pairing code: admin sitting and epoch binding --------------------------------------

    @Test
    fun `a registering code for an unlisted client is refused 423 while the sitting is locked`() {
        val id = terminal()
        val clientId = clientIdOf(id)

        val e = refusal { service.generatePairingCode("emp", id, true) }

        assertRefusal(
            e,
            PosErrorCode.TSE_ADMIN_LOCKED,
            HttpStatusCode.Locked,
            "Registering $clientId at the TSE while pairing needs the TSE admin sitting unlocked",
        )
        assertEquals(listOf("isInitialized", "listClients"), tseCalls, "the device is asked in this order, nothing else")
        assertEquals(0L, transaction { TerminalPairingCode.all().count() }, "a refused code is never minted")
    }

    @Test
    fun `a registering code is bound to the epoch of the sitting that issued it`() {
        TseAdminSession.unlock("123456")
        val epoch = TseAdminSession.currentEpochIfUnlocked()
        val id = terminal()

        val code = issue(id)

        assertNotNull(epoch)
        assertEquals(epoch, storedCode(code).adminSessionEpoch)
        assertTrue(storedCode(code).registerTseClient)
        assertEquals(listOf("isInitialized", "listClients"), tseCalls, "issuing a code registers nothing")
    }

    @Test
    fun `a new sitting ends a code bound to the previous one`() {
        TseAdminSession.unlock("123456")
        val id = terminal()
        val code = issue(id)
        TseAdminSession.unlock("123456") // a fresh sitting: the epoch moves on although still unlocked
        tseCalls.clear()

        val e = refusal { pair(code) }

        assertRefusal(e, PosErrorCode.PAIRING_CODE_EXPIRED, HttpStatusCode.BadRequest, "Pairing code's admin sitting ended")
        assertEquals(emptyList(), tseCalls, "the stale code is refused before the TSE is asked")
        assertNull(boundDevice(id))
        assertNull(storedCode(code).consumedAt)
    }

    @Test
    fun `a code for a client the TSE already lists needs no sitting and flags the terminal at issue`() {
        TseAdminSession.unlock("123456")
        val id = terminal()
        knownClients += clientIdOf(id)

        val code = issue(id)

        assertNull(storedCode(code).adminSessionEpoch)
        assertTrue(isRegistered(id), "the listed client is recorded when the code is issued")
    }

    @Test
    fun `a locked sitting with a recorded registration issues an unbound code that still pairs after the lock`() {
        val id = terminal(registered = true)

        val code = issue(id)
        tseCalls.clear()
        pair(code)

        assertNull(storedCode(code).adminSessionEpoch)
        assertEquals("device-1", boundDevice(id))
        assertEquals(listOf("isInitialized", "listClients"), tseCalls, "the unreadable list leaves the flag standing; no register")
    }

    // ---- (b) pairing: requireTseCanRegister and ensureTseClientForPairing -----------------------

    @Test
    fun `a non-fiscal terminal is refused before the TSE is asked`() {
        val id = terminal(fiscal = false)
        val clientId = clientIdOf(id)

        val e = refusal { service.generatePairingCode("emp", id, true) }

        assertRefusal(
            e,
            PosErrorCode.TERMINAL_NOT_FISCAL,
            HttpStatusCode.Conflict,
            "Terminal $clientId is not fiscal and has no TSE client to register",
        )
        assertEquals(emptyList(), tseCalls)
    }

    @Test
    fun `a disabled TSE is refused without asking the device`() {
        mode = TseMode.DISABLED
        val id = terminal()

        val e = refusal { service.generatePairingCode("emp", id, true) }

        assertRefusal(e, PosErrorCode.TSE_DISABLED, HttpStatusCode.Conflict, "TSE is disabled")
        assertEquals(emptyList(), tseCalls)
    }

    @Test
    fun `a TSE outside NORMAL mode is refused without asking the device`() {
        mode = TseMode.SIMULATION
        val id = terminal()

        val e = refusal { service.generatePairingCode("emp", id, true) }

        assertRefusal(
            e,
            PosErrorCode.TSE_NOT_OPERATIONAL,
            HttpStatusCode.Conflict,
            "The TSE is not in NORMAL mode and commissioned; pair without registering at the TSE",
        )
        assertEquals(emptyList(), tseCalls, "the mode check short-circuits isInitialized")
    }

    @Test
    fun `an uncommissioned TSE is refused after one isInitialized call`() {
        initialized = false
        val id = terminal()

        val e = refusal { service.generatePairingCode("emp", id, true) }

        assertRefusal(
            e,
            PosErrorCode.TSE_NOT_OPERATIONAL,
            HttpStatusCode.Conflict,
            "The TSE is not in NORMAL mode and commissioned; pair without registering at the TSE",
        )
        assertEquals(listOf("isInitialized"), tseCalls)
    }

    @Test
    fun `pairing an unlisted client registers it after checking and listing, in that order`() {
        TseAdminSession.unlock("123456")
        val id = terminal()
        val clientId = clientIdOf(id)
        val code = issue(id)
        tseCalls.clear()

        pair(code)

        assertEquals(listOf("isInitialized", "listClients", "registerClient($clientId)"), tseCalls)
        assertTrue(isRegistered(id))
        assertNotNull(storedCode(code).consumedAt)
    }

    @Test
    fun `pairing turns a thrown registration refusal into 424 carrying its message and cause`() {
        TseAdminSession.unlock("123456")
        val id = terminal()
        val clientId = clientIdOf(id)
        val code = issue(id)
        val cause = PosException(PosErrorCode.TSE_OPERATION_REJECTED, HttpStatusCode.Conflict, "card said no")
        registerFailure = cause

        val e = refusal { pair(code) }

        assertRefusal(
            e,
            PosErrorCode.PAIRING_TSE_REGISTRATION_FAILED,
            HttpStatusCode.FailedDependency,
            "Could not register $clientId at the TSE; the device was not paired and the code is still valid: card said no",
        )
        assertSame(cause, e.cause)
        assertNull(boundDevice(id))
    }

    @Test
    fun `pairing turns an unsuccessful registration result into 424 without a cause`() {
        TseAdminSession.unlock("123456")
        val id = terminal()
        val clientId = clientIdOf(id)
        val code = issue(id)
        registerResult = TseDto.ClientRegistrationResultDto(false, clientId, "client limit reached")

        val e = refusal { pair(code) }

        assertRefusal(
            e,
            PosErrorCode.PAIRING_TSE_REGISTRATION_FAILED,
            HttpStatusCode.FailedDependency,
            "Could not register $clientId at the TSE; the device was not paired and the code is still valid: client limit reached",
        )
        assertNull(e.cause)
        assertNull(boundDevice(id))
        assertFalse(isRegistered(id))
    }

    @Test
    fun `pairing wraps a TSE that left NORMAL mode since the code was issued`() {
        TseAdminSession.unlock("123456")
        val id = terminal()
        val clientId = clientIdOf(id)
        val code = issue(id)
        mode = TseMode.SIMULATION

        val e = refusal { pair(code) }

        assertRefusal(
            e,
            PosErrorCode.PAIRING_TSE_REGISTRATION_FAILED,
            HttpStatusCode.FailedDependency,
            "Could not register $clientId at the TSE; the device was not paired and the code is still valid: " +
                "The TSE is not in NORMAL mode and commissioned; pair without registering at the TSE",
        )
        assertEquals(PosErrorCode.TSE_NOT_OPERATIONAL, (e.cause as PosException).code)
    }

    @Test
    fun `the pairing status of a disabled TSE does not ask the device`() {
        mode = TseMode.DISABLED

        val status = runBlocking { service.getTsePairingStatus() }

        assertEquals(TseMode.DISABLED, status.mode)
        assertFalse(status.initialized)
        assertFalse(status.available)
        assertEquals(emptyList(), tseCalls)
    }

    @Test
    fun `the pairing status of a NORMAL TSE asks isInitialized once`() {
        val status = runBlocking { service.getTsePairingStatus() }

        assertTrue(status.initialized)
        assertTrue(status.available)
        assertEquals(listOf("isInitialized"), tseCalls)
    }

    // ---- (c) revoking a binding vs. ecr PENDING signatures -------------------------------------

    private val revokeRefusal = "has unfinished TSE transactions — finish them before revoking"

    @Test
    fun `revoking is refused while the terminal owns a PENDING bill`() {
        val id = rawTerminal("TER-REV1", active = true, boundTo = "rev-1", registered = true)
        seedBill(id, TseSigningStatus.PENDING, "CHR_B1")

        val e = refusal { service.revokeBinding("7", id) }

        assertRefusal(e, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.Conflict, "Terminal TER-REV1 $revokeRefusal")
        assertEquals("rev-1", boundDevice(id))
        assertEquals(emptyList(), tseCalls, "revoking never reaches the device")
    }

    @Test
    fun `revoking is refused while the terminal owns a PENDING order`() {
        val id = rawTerminal("TER-REV2", active = true, boundTo = "rev-2", registered = true)
        seedOrder(id, TseSigningStatus.PENDING, "CHR_O1")

        val e = refusal { service.revokeBinding("7", id) }

        assertRefusal(e, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.Conflict, "Terminal TER-REV2 $revokeRefusal")
        assertEquals("rev-2", boundDevice(id))
    }

    @Test
    fun `revoking is allowed when the terminal's bills and orders are signed`() {
        val id = rawTerminal("TER-REV3", active = true, boundTo = "rev-3", registered = true)
        seedBill(id, TseSigningStatus.SIGNED, "CHR_B2")
        seedOrder(id, TseSigningStatus.SIGNED, "CHR_O2")

        val summary = runBlocking { service.revokeBinding("7", id) }

        assertNull(boundDevice(id))
        assertEquals("TER-REV3", summary.terminalId)
    }

    @Test
    fun `a PENDING bill on another terminal does not block revoking this one`() {
        val other = rawTerminal("TER-REV4", active = true, boundTo = "rev-4", registered = true)
        val id = rawTerminal("TER-REV5", active = true, boundTo = "rev-5", registered = true)
        seedBill(other, TseSigningStatus.PENDING, "CHR_B3")

        runBlocking { service.revokeBinding("7", id) }

        assertNull(boundDevice(id))
        assertEquals("rev-4", boundDevice(other))
    }

    // ---- (d) deregistering a TSE client ---------------------------------------------------------

    @Test
    fun `deregistering a retired terminal with a PENDING bill is refused before the device is asked`() {
        TseAdminSession.unlock("123456")
        val id = rawTerminal("TER-004", active = false, boundTo = null, registered = true)
        seedBill(id, TseSigningStatus.PENDING, "CHR_B4")

        val e = refusal { service.deregisterTerminalTse("7", id) }

        assertRefusal(
            e,
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Conflict,
            "Terminal TER-004 has unfinished TSE transactions — finish them first",
        )
        assertEquals(emptyList(), tseCalls)
    }

    @Test
    fun `deregistering is refused while the TSE holds started transactions of the client`() {
        TseAdminSession.unlock("123456")
        knownClients += listOf("TER-001", "TER-004")
        startedTransactions = 2
        val id = rawTerminal("TER-004", active = false, boundTo = null, registered = true)

        val e = refusal { service.deregisterTerminalTse("7", id) }

        assertRefusal(
            e,
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Conflict,
            "The TSE still holds 2 unfinished transaction(s) of TER-004 — finish them first",
        )
        assertEquals(listOf("listClients", "listTransactions(TER-004)"), tseCalls)
        assertTrue(isRegistered(id))
    }

    @Test
    fun `deregistering a retired terminal asks list, transactions, deregister in that order`() {
        TseAdminSession.unlock("123456")
        knownClients += listOf("TER-001", "TER-004")
        val id = rawTerminal("TER-004", active = false, boundTo = null, registered = true)

        val summary = runBlocking { service.deregisterTerminalTse("7", id) }

        assertEquals(listOf("listClients", "listTransactions(TER-004)", "deregisterClient(TER-004)"), tseCalls)
        assertFalse(summary.tseClientRegistered)
        assertFalse(isRegistered(id))
    }

    @Test
    fun `an unsuccessful deregistration is a 500 naming the device's message and keeps the flag`() {
        TseAdminSession.unlock("123456")
        knownClients += listOf("TER-001", "TER-004")
        deregisterResult = TseDto.AdminResultDto(false, "card busy")
        val id = rawTerminal("TER-004", active = false, boundTo = null, registered = true)

        val e = refusal { service.deregisterTerminalTse("7", id) }

        assertRefusal(
            e,
            PosErrorCode.INTERNAL_ERROR,
            HttpStatusCode.InternalServerError,
            "TSE deregistration returned unsuccessful for TER-004: card busy",
        )
        assertTrue(isRegistered(id))
    }

    @Test
    fun `deregistering against a locked sitting keeps the provider's 423`() {
        knownClients += listOf("TER-001", "TER-004")
        val id = rawTerminal("TER-004", active = false, boundTo = null, registered = true)

        val e = refusal { service.deregisterTerminalTse("7", id) }

        assertRefusal(e, PosErrorCode.TSE_ADMIN_LOCKED, HttpStatusCode.Locked, "locked")
        assertEquals(listOf("listClients"), tseCalls)
    }

    // ---- (e) registerTerminalTse / syncTseRegistrationStatus / reactivate -----------------------

    @Test
    fun `registerTerminalTse registers an unflagged client and sets the flag`() {
        TseAdminSession.unlock("123456")
        val id = rawTerminal("TER-010", active = true, boundTo = null, registered = false)

        val summary = runBlocking { service.registerTerminalTse(id) }

        assertEquals(listOf("registerClient(TER-010)"), tseCalls, "an unflagged client is registered without listing")
        assertTrue(summary.tseClientRegistered)
        assertTrue(isRegistered(id))
    }

    @Test
    fun `registerTerminalTse turns an unsuccessful result into a 500 and leaves the flag clear`() {
        TseAdminSession.unlock("123456")
        registerResult = TseDto.ClientRegistrationResultDto(false, "TER-011", "full")
        val id = rawTerminal("TER-011", active = true, boundTo = null, registered = false)

        val e = refusal { service.registerTerminalTse(id) }

        assertRefusal(
            e,
            PosErrorCode.INTERNAL_ERROR,
            HttpStatusCode.InternalServerError,
            "TSE registration returned unsuccessful for TER-011: full",
        )
        assertFalse(isRegistered(id))
    }

    @Test
    fun `registerTerminalTse lets the provider's own exception through unchanged`() {
        val id = rawTerminal("TER-012", active = true, boundTo = null, registered = false)
        val providerRefusal = PosException(PosErrorCode.TSE_OPERATION_REJECTED, HttpStatusCode.Conflict, "card said no")
        TseAdminSession.unlock("123456")
        registerFailure = providerRefusal

        val e = refusal { service.registerTerminalTse(id) }

        assertSame(providerRefusal, e, "registerTerminalTse must not wrap the provider's PosException")
    }

    @Test
    fun `registerTerminalTse against a locked sitting answers the provider's 423`() {
        val id = rawTerminal("TER-013", active = true, boundTo = null, registered = false)

        val e = refusal { service.registerTerminalTse(id) }

        assertRefusal(e, PosErrorCode.TSE_ADMIN_LOCKED, HttpStatusCode.Locked, "locked")
    }

    @Test
    fun `registerTerminalTse refuses a client id a TSE 2_0 rejects before asking the device`() {
        val id = rawTerminal("TER:014", active = true, boundTo = null, registered = false)

        val e = refusal { service.registerTerminalTse(id) }

        assertRefusal(
            e,
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "Terminal clientId 'TER:014' contains characters a TSE 2.0 rejects",
        )
        assertEquals(emptyList(), tseCalls)
    }

    @Test
    fun `registerTerminalTse and sync refuse a disabled TSE without asking the device`() {
        mode = TseMode.DISABLED
        val id = rawTerminal("TER-015", active = true, boundTo = null, registered = false)

        assertRefusal(refusal { service.registerTerminalTse(id) }, PosErrorCode.TSE_DISABLED, HttpStatusCode.Conflict, "TSE is disabled")
        assertRefusal(refusal { service.syncTseRegistrationStatus() }, PosErrorCode.TSE_DISABLED, HttpStatusCode.Conflict, "TSE is disabled")
        assertEquals(emptyList(), tseCalls)
    }

    @Test
    fun `sync reconciles every terminal's flag against the device's list`() {
        TseAdminSession.unlock("123456")
        val listedButUnflagged = rawTerminal("TER-020", active = true, boundTo = null, registered = false)
        val flaggedButUnlisted = rawTerminal("TER-021", active = true, boundTo = null, registered = true)
        val consistent = rawTerminal("TER-022", active = true, boundTo = null, registered = true)
        knownClients += listOf("TER-020", "TER-022")

        val result = runBlocking { service.syncTseRegistrationStatus() }

        assertEquals(listOf("listClients"), tseCalls)
        assertTrue(isRegistered(listedButUnflagged))
        assertFalse(isRegistered(flaggedButUnlisted))
        assertTrue(isRegistered(consistent))
        assertEquals(3, result.size)
    }

    @Test
    fun `an unsuccessful client list is a 500 naming the device's message`() {
        TseAdminSession.unlock("123456")
        listResult = TseDto.ClientListResultDto(false, emptyList(), 0, "card unreadable")

        val e = refusal { service.syncTseRegistrationStatus() }

        assertRefusal(
            e,
            PosErrorCode.INTERNAL_ERROR,
            HttpStatusCode.InternalServerError,
            "TSE listClients returned unsuccessful: card unreadable",
        )
    }

    @Test
    fun `an unsuccessful client list during pairing falls back to the database flag`() {
        TseAdminSession.unlock("123456")
        listResult = TseDto.ClientListResultDto(false, emptyList(), 0, "card unreadable")
        val id = terminal(registered = true)

        val code = issue(id)

        assertNull(storedCode(code).adminSessionEpoch, "the unreadable list leaves the recorded registration standing")
    }

    @Test
    fun `reactivating reconciles the flag from the device, and a failing device leaves it as is`() {
        TseAdminSession.unlock("123456")
        val id = rawTerminal("TER-030", active = false, boundTo = null, registered = false)
        val actor =
            transaction {
                TestData
                    .createAdmin()
                    .id.value
                    .toString()
            }
        knownClients += "TER-030"

        runBlocking { service.reactivateTerminal(actor, id) }

        assertTrue(isRegistered(id), "a listed client is flagged on reactivation")
        assertEquals(listOf("listClients"), tseCalls)

        transaction { Terminal.findById(id)!!.isActive = false }
        TseAdminSession.clear()
        knownClients.clear()
        runBlocking { service.reactivateTerminal(actor, id) }

        assertTrue(isRegistered(id), "a locked device answers nothing, so the flag stands")
    }
}
