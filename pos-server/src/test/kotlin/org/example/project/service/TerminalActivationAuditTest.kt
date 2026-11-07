package org.example.project.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.terminal.data.repository.TerminalPairingCodeRepositoryImpl
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.data.service.BrowserStationPolicy
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.data.service.TerminalServiceImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseTerminalFiscalGateway
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real-TSE QA rt14 (d): deactivating and reactivating a terminal stamped `lastSeenAt`, so a till no
 * device had ever contacted read "seen today", and neither action wrote an audit row although each
 * decides which till may sign.
 */
@DisplayName("Terminal deactivate/reactivate — audited, and never mistaken for device contact")
class TerminalActivationAuditTest {
    /** A last-seen time far from now, so any re-stamp is visible. */
    private val longAgo = LocalDateTime(2020, 1, 2, 3, 4, 5)

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private val noDevice: TseAdminProvider =
        Proxy.newProxyInstance(
            TseAdminProvider::class.java.classLoader,
            arrayOf(TseAdminProvider::class.java),
        ) { _, method, _ -> throw UnsupportedOperationException("TseAdminProvider.${method.name} is not used here") } as TseAdminProvider

    private val disabledTse: IShopConfigurationManager =
        Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTseMode" -> TseMode.DISABLED
                "toString" -> "Config"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("IShopConfigurationManager.${method.name} is not used here")
            }
        } as IShopConfigurationManager

    private fun service(): TerminalServiceImpl {
        val repository = TerminalRepositoryImpl()
        return TerminalServiceImpl(
            repository = repository,
            pairingCodeRepository = TerminalPairingCodeRepositoryImpl(),
            fiscalGateway = TseTerminalFiscalGateway(noDevice),
            configManager = disabledTse,
            capabilityResolver = TerminalCapabilityResolver(repository),
            browserStationPolicy = BrowserStationPolicy(disabledTse),
        )
    }

    private fun terminal(active: Boolean): Long =
        transaction {
            Terminal
                .new {
                    deviceId = null
                    clientId = "TER-004"
                    registeredAt = longAgo
                    lastSeenAt = longAgo
                    isActive = active
                }.id.value
        }

    private fun actor(): String =
        transaction {
            TestData
                .createAdmin()
                .id.value
                .toString()
        }

    private fun rows(operation: String) = transaction { TransactionAuditLog.all().filter { it.operation == operation }.toList() }

    @Test
    fun `deactivate leaves lastSeenAt alone and writes one DEACTIVATE_TERMINAL row naming the terminal`() {
        val id = terminal(active = true)
        val actor = actor()

        runBlocking { service().deactivateTerminal(actor, id) }

        val stored = transaction { Terminal.findById(id)!! }
        assertFalse(transaction { stored.isActive })
        assertEquals(longAgo, transaction { stored.lastSeenAt }, "an operator's click is not device contact")
        val row = rows("DEACTIVATE_TERMINAL").single()
        assertEquals("TERMINAL", row.entityType)
        assertEquals(id.toString(), row.entityId)
        assertEquals("TER-004", row.entityName)
        assertEquals(actor, row.employeeId)
        assertTrue(row.changesSummary.orEmpty().startsWith("SUCCESS"), row.changesSummary)
    }

    @Test
    fun `reactivate leaves lastSeenAt alone and writes one REACTIVATE_TERMINAL row naming the terminal`() {
        val id = terminal(active = false)
        val actor = actor()

        runBlocking { service().reactivateTerminal(actor, id) }

        val stored = transaction { Terminal.findById(id)!! }
        assertTrue(transaction { stored.isActive })
        assertEquals(longAgo, transaction { stored.lastSeenAt }, "an operator's click is not device contact")
        val row = rows("REACTIVATE_TERMINAL").single()
        assertEquals(id.toString(), row.entityId)
        assertEquals("TER-004", row.entityName)
        assertTrue(row.changesSummary.orEmpty().startsWith("SUCCESS"), row.changesSummary)
    }

    @Test
    fun `a deactivate of an unknown terminal is audited as a failure naming the id asked for`() {
        val actor = actor()

        runCatching { runBlocking { service().deactivateTerminal(actor, 999L) } }

        val row = rows("DEACTIVATE_TERMINAL").single()
        assertEquals("999", row.entityId)
        assertTrue(row.changesSummary.orEmpty().startsWith("FAILED"), row.changesSummary)
    }
}
