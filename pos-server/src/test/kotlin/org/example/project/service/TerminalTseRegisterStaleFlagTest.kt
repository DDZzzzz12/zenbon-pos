package org.example.project.service

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.terminal.data.repository.TerminalPairingCodeRepositoryImpl
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.data.service.BrowserStationPolicy
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.data.service.TerminalServiceImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseTerminalFiscalGateway
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E round 4, R4-6: after a restore or a TSE swap the database flags a terminal registered that the
 * attached device has never seen. `tse-register` answered from the flag — 200, "already registered" —
 * and registered nothing, so the station's queued Vorgänge kept failing with CLIENT_NOT_REGISTERED.
 */
@DisplayName("tse-register — the device, not the database flag, says whether a client is registered")
class TerminalTseRegisterStaleFlagTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private val registered = mutableListOf<String>()

    private fun device(knownClients: List<String>): TseAdminProvider =
        Proxy.newProxyInstance(
            TseAdminProvider::class.java.classLoader,
            arrayOf(TseAdminProvider::class.java),
        ) { _, method, args ->
            when (method.name) {
                "listClients" -> TseDto.ClientListResultDto(true, knownClients, knownClients.size)
                "registerClient" -> {
                    val clientId = args!![0] as String
                    registered += clientId
                    TseDto.ClientRegistrationResultDto(true, clientId)
                }
                "toString" -> "FakeDevice"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("TseAdminProvider.${method.name} is not used here")
            }
        } as TseAdminProvider

    private fun normalMode(): IShopConfigurationManager =
        Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTseMode" -> TseMode.NORMAL
                "toString" -> "NormalTseConfig"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("IShopConfigurationManager.${method.name} is not used here")
            }
        } as IShopConfigurationManager

    private fun service(knownClients: List<String>): TerminalServiceImpl {
        val repository = TerminalRepositoryImpl()
        return TerminalServiceImpl(
            repository = repository,
            pairingCodeRepository = TerminalPairingCodeRepositoryImpl(),
            fiscalGateway = TseTerminalFiscalGateway(device(knownClients)),
            configManager = normalMode(),
            capabilityResolver = TerminalCapabilityResolver(repository),
            browserStationPolicy = BrowserStationPolicy(normalMode()),
        )
    }

    /** A station the database believes is registered — the restored-archive shape. */
    private fun flaggedStation(): Long =
        transaction {
            val now = currentTimeStamp()
            Terminal
                .new {
                    deviceId = "station-device"
                    clientId = "TER-002"
                    registeredAt = now
                    lastSeenAt = now
                    tseClientRegistered = true
                }.id.value
        }

    @Test
    fun `a flagged client the device does not know is registered for real`() {
        val id = flaggedStation()

        val result = runBlocking { service(knownClients = listOf("TER-001")).registerTerminalTse(id) }

        assertEquals(listOf("TER-002"), registered, "the device must be asked to register the client")
        assertTrue(result.tseClientRegistered)
    }

    @Test
    fun `a flagged client the device lists is left alone`() {
        val id = flaggedStation()

        val result = runBlocking { service(knownClients = listOf("TER-001", "TER-002")).registerTerminalTse(id) }

        assertTrue(registered.isEmpty(), "a confirmed registration needs no second one")
        assertTrue(result.tseClientRegistered)
    }
}
