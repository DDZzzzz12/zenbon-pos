package org.example.project.features.terminal

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.PosErrorCode
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.TSE_DISABLED_CLIENT_ID
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.resolveSigningClientId
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `resolveSigningClientId` is `requireSigningTerminal(...).clientId` in NORMAL/SIMULATION, and in
 * DISABLED lets a device that is no registered till through with a placeholder (nothing is signed).
 */
@DisplayName("resolveSigningClientId")
class ResolveSigningClientIdTest {
    private lateinit var shop: ShopInfo
    private val repo = TerminalRepositoryImpl()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        shop = TestData.createShopInfo()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun till(
        active: Boolean = true,
        registered: Boolean = true,
    ) = transaction {
        val now = currentTimeStamp()
        Terminal.new {
            deviceId = "dev-1"
            clientId = "TER-001"
            name = "Kasse 1"
            isActive = active
            fiscal = true
            tseClientRegistered = registered
            role = TerminalRole.CASHIER.name
            shopInfo = shop
            registeredAt = now
            lastSeenAt = now
        }
    }

    private fun resolve(
        device: String,
        mode: TseMode,
    ) = transaction { resolveSigningClientId(repo, device, mode) }

    @Test
    fun `NORMAL and SIMULATION refuse admin-web and an unknown device exactly like requireSigningTerminal`() {
        listOf(TseMode.NORMAL, TseMode.SIMULATION).forEach { mode ->
            assertEquals(PosErrorCode.AUTH_FORBIDDEN, assertFailsWith<PosException> { resolve("admin-web-x", mode) }.code)
            assertEquals(PosErrorCode.CARD_TERMINAL_NOT_FOUND, assertFailsWith<PosException> { resolve("nope", mode) }.code)
        }
    }

    @Test
    fun `NORMAL refuses a TSE-unregistered till, DISABLED does not`() {
        till(registered = false)
        assertEquals(PosErrorCode.TERMINAL_NOT_TSE_REGISTERED, assertFailsWith<PosException> { resolve("dev-1", TseMode.NORMAL) }.code)
        assertEquals("TER-001", resolve("dev-1", TseMode.DISABLED))
    }

    @Test
    fun `a registered till keeps its own client id in every mode`() {
        till()
        TseMode.entries.forEach { assertEquals("TER-001", resolve("dev-1", it)) }
    }

    @Test
    fun `DISABLED lets admin-web and an unknown device through with the placeholder`() {
        assertEquals(TSE_DISABLED_CLIENT_ID, resolve("admin-web-x", TseMode.DISABLED))
        assertEquals(TSE_DISABLED_CLIENT_ID, resolve("unknown", TseMode.DISABLED))
    }

    @Test
    fun `a deactivated till is refused even when DISABLED`() {
        till(active = false)
        assertFailsWith<PosException> { resolve("dev-1", TseMode.DISABLED) }
        Unit
    }
}
