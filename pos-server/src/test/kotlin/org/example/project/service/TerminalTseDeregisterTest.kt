package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.terminal.data.repository.TerminalPairingCodeRepositoryImpl
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.data.service.BrowserStationPolicy
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.data.service.TerminalServiceImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseTerminalFiscalGateway
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F68 / rt13 B2: a retired till kept its TSE client registered forever — nothing called
 * `deregisterClient`, and the TSE has a client limit. `tse-deregister` now does, behind guards that
 * keep a terminal that could still sign from losing its identity.
 */
@DisplayName("tse-deregister — only a retired terminal gives up its TSE client")
class TerminalTseDeregisterTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private val deregistered = mutableListOf<String>()

    private fun device(
        knownClients: List<String>,
        startedTransactions: Int = 0,
        locked: Boolean = false,
    ): TseAdminProvider =
        Proxy.newProxyInstance(
            TseAdminProvider::class.java.classLoader,
            arrayOf(TseAdminProvider::class.java),
        ) { _, method, args ->
            when (method.name) {
                "listClients" -> {
                    if (locked) throw PosException(PosErrorCode.TSE_ADMIN_LOCKED, HttpStatusCode.Locked, "locked")
                    TseDto.ClientListResultDto(true, knownClients, knownClients.size)
                }
                "listTransactions" ->
                    TseDto.TransactionListResultDto(true, List(startedTransactions) { it.toLong() }, startedTransactions)
                "deregisterClient" -> {
                    deregistered += args!![0] as String
                    TseDto.AdminResultDto(true, "deregistered")
                }
                "toString" -> "FakeDevice"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("TseAdminProvider.${method.name} is not used here")
            }
        } as TseAdminProvider

    private fun config(mode: TseMode): IShopConfigurationManager =
        Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTseMode" -> mode
                "toString" -> "Config"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("IShopConfigurationManager.${method.name} is not used here")
            }
        } as IShopConfigurationManager

    private fun service(
        device: TseAdminProvider,
        mode: TseMode = TseMode.NORMAL,
    ): TerminalServiceImpl {
        val repository = TerminalRepositoryImpl()
        return TerminalServiceImpl(
            repository = repository,
            pairingCodeRepository = TerminalPairingCodeRepositoryImpl(),
            fiscalGateway = TseTerminalFiscalGateway(device),
            configManager = config(mode),
            capabilityResolver = TerminalCapabilityResolver(repository),
            browserStationPolicy = BrowserStationPolicy(config(mode)),
        )
    }

    private fun terminal(
        active: Boolean = false,
        boundTo: String? = null,
        clientId: String = "TER-004",
    ): Long =
        transaction {
            val now = currentTimeStamp()
            Terminal
                .new {
                    deviceId = boundTo
                    this.clientId = clientId
                    registeredAt = now
                    lastSeenAt = now
                    isActive = active
                    tseClientRegistered = true
                }.id.value
        }

    private fun refusal(block: suspend () -> Unit): PosException = assertFailsWith<PosException> { runBlocking { block() } }

    private fun auditRows() = transaction { TransactionAuditLog.all().filter { it.operation == "DEREGISTER_TSE_CLIENT" }.toList() }

    @Test
    fun `a retired terminal is deregistered, its flag cleared and an audit row written`() {
        val id = terminal()
        // The audit writer resolves the actor against the employee table; an unknown id writes "null".
        val actor =
            transaction {
                TestData
                    .createAdmin()
                    .id.value
                    .toString()
            }

        val result = runBlocking { service(device(listOf("TER-001", "TER-004"))).deregisterTerminalTse(actor, id) }

        assertEquals(listOf("TER-004"), deregistered)
        assertFalse(result.tseClientRegistered)
        assertFalse(transaction { Terminal.findById(id)!!.tseClientRegistered })
        val row = auditRows().single()
        assertEquals("TERMINAL", row.entityType)
        assertEquals(id.toString(), row.entityId)
        assertEquals(actor, row.employeeId)
    }

    @Test
    fun `an active terminal is refused with 409 and the device is never asked`() {
        val id = terminal(active = true)

        val e = refusal { service(device(listOf("TER-001", "TER-004"))).deregisterTerminalTse("7", id) }

        assertEquals(HttpStatusCode.Conflict, e.httpStatus)
        assertTrue(e.message!!.contains("deactivate"), e.message)
        assertTrue(deregistered.isEmpty())
        // The refusal is audited too, naming the terminal.
        assertEquals(id.toString(), auditRows().single().entityId)
    }

    @Test
    fun `a terminal still bound to a device is refused with 409`() {
        val id = terminal(boundTo = "tablet-1")

        val e = refusal { service(device(listOf("TER-001", "TER-004"))).deregisterTerminalTse("7", id) }

        assertEquals(HttpStatusCode.Conflict, e.httpStatus)
        assertTrue(e.message!!.contains("revoke"), e.message)
        assertTrue(deregistered.isEmpty())
    }

    @Test
    fun `a locked Admin sitting surfaces 423 and deregisters nothing`() {
        val id = terminal()

        val e = refusal { service(device(listOf("TER-001", "TER-004"), locked = true)).deregisterTerminalTse("7", id) }

        assertEquals(HttpStatusCode.Locked, e.httpStatus)
        assertTrue(deregistered.isEmpty())
        assertTrue(transaction { Terminal.findById(id)!!.tseClientRegistered })
    }

    @Test
    fun `the last client of a NORMAL-mode TSE is kept`() {
        val id = terminal()

        val e = refusal { service(device(listOf("TER-004"))).deregisterTerminalTse("7", id) }

        assertEquals(HttpStatusCode.Conflict, e.httpStatus)
        assertTrue(e.message!!.contains("last registered client"), e.message)
        assertTrue(deregistered.isEmpty())
    }

    @Test
    fun `the last client may go when the TSE is not in NORMAL mode`() {
        val id = terminal()

        runBlocking { service(device(listOf("TER-004")), mode = TseMode.SIMULATION).deregisterTerminalTse("7", id) }

        assertEquals(listOf("TER-004"), deregistered)
    }

    @Test
    fun `started transactions on the device block the deregistration`() {
        val id = terminal()

        val e = refusal { service(device(listOf("TER-001", "TER-004"), startedTransactions = 2)).deregisterTerminalTse("7", id) }

        assertEquals(HttpStatusCode.Conflict, e.httpStatus)
        assertTrue(e.message!!.contains("unfinished"), e.message)
        assertTrue(deregistered.isEmpty())
    }

    @Test
    fun `a client the device does not list is refused rather than sent to the device`() {
        val id = terminal()

        val e = refusal { service(device(listOf("TER-001", "TER-002"))).deregisterTerminalTse("7", id) }

        assertEquals(HttpStatusCode.Conflict, e.httpStatus)
        assertTrue(deregistered.isEmpty())
    }

    @Test
    fun `a disabled TSE is refused with TSE_DISABLED`() {
        val id = terminal()

        val e = refusal { service(device(listOf("TER-001", "TER-004")), mode = TseMode.DISABLED).deregisterTerminalTse("7", id) }

        assertEquals(PosErrorCode.TSE_DISABLED, e.code)
        assertTrue(deregistered.isEmpty())
    }
}
