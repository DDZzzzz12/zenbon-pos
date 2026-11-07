package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.terminal.data.repository.TerminalPairingCodeRepositoryImpl
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.data.service.BrowserStationPolicy
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.data.service.TerminalServiceImpl
import org.example.project.features.tse.data.service.TseTerminalFiscalGateway
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `POST /api/v1/terminals/sync-tse` against a TSE whose Admin sitting is locked must answer the
 * provider's own `423 TSE_ADMIN_LOCKED`, which the UI turns into a PIN prompt — not a 500 whose
 * body merely quotes the real code as text (E2E-2026-09-22 finding R3-6).
 *
 * `registerTerminalWithTse`, 65 lines above the offending catch in the same file, already carries
 * the rule: "let it propagate unchanged so the client can prompt for the Admin PIN. Do NOT wrap
 * into 500." This is its sibling finally following it.
 */
class TerminalTseAdminLockedTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    /** The locked TSE: `getAdminPin()` throws before any device I/O happens. */
    private fun lockedAdminProvider(): TseAdminProvider =
        Proxy.newProxyInstance(
            TseAdminProvider::class.java.classLoader,
            arrayOf(TseAdminProvider::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "listClients" ->
                    throw PosException(
                        code = PosErrorCode.TSE_ADMIN_LOCKED,
                        httpStatus = HttpStatusCode.Locked,
                        message = "TSE administration is locked. Enter the Admin PIN to continue.",
                    )
                "toString" -> "LockedTseAdminProvider"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("TseAdminProvider.${method.name} is not used here")
            }
        } as TseAdminProvider

    /** A shop whose TSE is in operation, so the sync reaches the provider at all. */
    private fun operationalTseConfig(): IShopConfigurationManager =
        Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTseMode" -> TseMode.NORMAL
                "toString" -> "OperationalTseConfig"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("IShopConfigurationManager.${method.name} is not used here")
            }
        } as IShopConfigurationManager

    private fun serviceWithLockedTse(): TerminalServiceImpl {
        val repository = TerminalRepositoryImpl()
        return TerminalServiceImpl(
            repository = repository,
            pairingCodeRepository = TerminalPairingCodeRepositoryImpl(),
            fiscalGateway = TseTerminalFiscalGateway(lockedAdminProvider()),
            configManager = operationalTseConfig(),
            capabilityResolver = TerminalCapabilityResolver(repository),
            browserStationPolicy = BrowserStationPolicy(operationalTseConfig()),
        )
    }

    @Test
    fun `syncing terminals against a locked TSE keeps the provider's 423`() {
        val ex = assertFailsWith<PosException> { runBlocking { serviceWithLockedTse().syncTseRegistrationStatus() } }

        assertEquals(PosErrorCode.TSE_ADMIN_LOCKED, ex.code, "the code the UI branches on must survive")
        assertEquals(HttpStatusCode.Locked, ex.httpStatus, "423 Locked, not 500 — see LocalTseAdminProvider.getAdminPin")
    }

    @Test
    fun `the operator is told to enter the PIN, not shown a wrapped internal error`() {
        val ex = assertFailsWith<PosException> { runBlocking { serviceWithLockedTse().syncTseRegistrationStatus() } }

        val message = ex.message.orEmpty()
        assertTrue(message.contains("Admin PIN"), "the message must name the remedy; got: $message")
        assertTrue(
            !message.contains("listClients failed"),
            "the wrapper that buried the real error must be gone; got: $message",
        )
    }
}
