package org.example.project.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.features.terminal.data.repository.TerminalPairingCodeRepositoryImpl
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.data.service.BrowserStationPolicy
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.data.service.TerminalServiceImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseTerminalFiscalGateway
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/**
 * The software version a device reports when it opens its notification stream.
 *
 * `terminal_sw_version` feeds DSFinV-K TERMINAL_SW_VERSION. It used to be written only at pairing
 * and at a till's login, so a login-less ORDER_STATION kept reporting the version it was paired
 * with forever. The closing-snapshot side of this lives in `DsfinvkKassenStammdatenSnapshotTest`.
 */
@DisplayName("TerminalService.recordReportedSwVersion")
class TerminalSwVersionReportTest {
    private val service = swVersionTestTerminalService()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private val lastSeen = LocalDateTime(2020, 1, 1, 0, 0)

    private fun seedTerminal(
        deviceId: String = "dev-till",
        swVersion: String? = "1.0.0",
    ): Long =
        transaction {
            Terminal
                .new {
                    this.deviceId = deviceId
                    this.clientId = "TER-001"
                    this.terminalSwBrand = "POS-KMP"
                    this.terminalSwVersion = swVersion
                    this.registeredAt = lastSeen
                    this.lastSeenAt = lastSeen
                }.id.value
        }

    private fun terminal(id: Long) = transaction { Terminal.findById(id)!!.let { it.terminalSwVersion to it.lastSeenAt } }

    private fun report(
        deviceId: String,
        version: String?,
    ) = runBlocking { service.recordReportedSwVersion(deviceId, version) }

    @Test
    fun `a changed version is written to the bound terminal, trimmed`() {
        val id = seedTerminal(swVersion = "1.0.0")

        report("dev-till", " 1.1.0 ")

        assertEquals("1.1.0", terminal(id).first)
    }

    @Test
    fun `a terminal paired without a version gets one`() {
        val id = seedTerminal(swVersion = null)

        report("dev-till", "1.1.0")

        assertEquals("1.1.0", terminal(id).first)
    }

    @Test
    fun `an unchanged version writes nothing`() {
        val id = seedTerminal(swVersion = "1.0.0")

        report("dev-till", "1.0.0")

        assertEquals("1.0.0" to lastSeen, terminal(id), "an unchanged version must not touch the row at all")
    }

    @Test
    fun `a missing, blank or over-long version is ignored`() {
        val id = seedTerminal(swVersion = "1.0.0")

        report("dev-till", null)
        report("dev-till", "   ")
        report("dev-till", "9".repeat(51))

        assertEquals("1.0.0" to lastSeen, terminal(id))
    }

    @Test
    fun `a version of exactly the column width is accepted`() {
        val id = seedTerminal(swVersion = "1.0.0")

        report("dev-till", "9".repeat(50))

        assertEquals("9".repeat(50), terminal(id).first)
    }

    @Test
    fun `a device bound to no terminal is a no-op and does not throw`() {
        val id = seedTerminal(swVersion = "1.0.0")

        // The wasm admin console and a login without X-Device-ID ("unknown") both open the stream.
        report("admin-web-abc123", "1.1.0")
        report("unknown", "1.1.0")

        assertEquals("1.0.0" to lastSeen, terminal(id))
    }
}

/**
 * A [TerminalServiceImpl] on the real H2-backed repositories, with no Koin. The TSE provider is never
 * reached by `recordReportedSwVersion`, so every call on it fails loudly.
 */
internal fun swVersionTestTerminalService(): TerminalServiceImpl {
    val unusedTse =
        Proxy.newProxyInstance(TseAdminProvider::class.java.classLoader, arrayOf(TseAdminProvider::class.java)) { _, method, _ ->
            when (method.name) {
                "toString" -> "UnusedTseAdminProvider"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("TseAdminProvider.${method.name} is not used here")
            }
        } as TseAdminProvider
    val repository = TerminalRepositoryImpl()
    return TerminalServiceImpl(
        repository = repository,
        pairingCodeRepository = TerminalPairingCodeRepositoryImpl(),
        fiscalGateway = TseTerminalFiscalGateway(unusedTse),
        configManager = ShopConfigurationManager,
        capabilityResolver = TerminalCapabilityResolver(repository),
        browserStationPolicy = BrowserStationPolicy(ShopConfigurationManager),
    )
}
