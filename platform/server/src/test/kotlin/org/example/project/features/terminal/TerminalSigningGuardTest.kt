package org.example.project.features.terminal

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.ClientKind
import org.example.project.core.error.PosErrorCode
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.terminal.domain.requireSigningTerminal
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `mode` argument is irrelevant to every case here — all three reject before a terminal is ever
 * resolved, so `assertMaySign` is never reached. `TseMode.NORMAL` is passed as the strictest value
 * so that a future change which accidentally moved the assertion *earlier* would surface here.
 *
 * The assertion half of the unified guard (Stage 4b) is covered by `UnifiedSigningGuardTest` and by
 * `TransactionSigningGuard`'s own tests; constructing a `Terminal` Exposed entity outside a
 * transaction to re-test it here would test Exposed, not the guard.
 */
@DisplayName("requireSigningTerminal")
class TerminalSigningGuardTest {
    /**
     * Stub that fails loudly if the DB is touched. The guard must reject
     * non-terminal device ids WITHOUT calling [findByDeviceId], so those cases
     * never reach the repository; the 404 case configures a null return.
     */
    private class StubTerminalRepository(
        private val findResult: Terminal? = null,
        private val onFind: () -> Terminal? = { findResult },
    ) : TerminalRepository {
        override fun findByDeviceId(deviceId: String): Terminal? = onFind()

        override fun create(
            deviceId: String,
            model: String?,
            brand: String?,
            serialNr: String?,
            swVersion: String?,
        ) = throw NotImplementedError()

        override fun update(
            terminal: Terminal,
            model: String?,
            brand: String?,
            serialNr: String?,
            swVersion: String?,
        ) = throw NotImplementedError()

        override fun updateLastSeen(deviceId: String) = throw NotImplementedError()

        override fun getAll(): List<Terminal> = throw NotImplementedError()

        override fun getActive(): List<Terminal> = throw NotImplementedError()

        override fun deactivate(terminalId: Long) = throw NotImplementedError()

        override fun reactivate(terminalId: Long) = throw NotImplementedError()

        override fun getNextTerminalNumber(): Int = throw NotImplementedError()

        override fun createUnbound(
            name: String?,
            role: String,
            fiscal: Boolean,
        ): Terminal = throw NotImplementedError()

        override fun findById(id: Long): Terminal? = throw NotImplementedError()

        override fun updateDetails(
            terminal: Terminal,
            name: String?,
            location: String?,
            role: String?,
        ): Unit = throw NotImplementedError()

        override fun bindDevice(
            terminal: Terminal,
            deviceId: String,
            model: String?,
            brand: String?,
            serialNr: String?,
            swVersion: String?,
            deviceTokenHash: String,
            tokenExpiresAt: LocalDateTime,
            clientKind: ClientKind,
        ): Terminal = throw NotImplementedError()

        override fun findBoundBrowserTerminals(): List<Terminal> = throw NotImplementedError()

        override fun findByDeviceTokenHash(deviceTokenHash: String): Terminal? = throw NotImplementedError()

        override fun clearBinding(terminal: Terminal) = throw NotImplementedError()
    }

    @Test
    fun `rejects wasm admin device with 403 without hitting the database`() {
        val repo = StubTerminalRepository(onFind = { error("findByDeviceId must not be called") })
        val ex = assertFailsWith<PosException> { requireSigningTerminal(repo, "admin-web-abc123", TseMode.NORMAL) }
        assertEquals(PosErrorCode.AUTH_FORBIDDEN, ex.code)
        assertEquals(HttpStatusCode.Forbidden, ex.httpStatus)
    }

    @Test
    fun `defers the unknown login fallback to terminal lookup (404 when unregistered)`() {
        // "unknown" is the default when a client sends no device header; a terminal
        // may legitimately be registered under it, so the guard must NOT hard-reject.
        val repo = StubTerminalRepository(findResult = null)
        val ex = assertFailsWith<PosException> { requireSigningTerminal(repo, "unknown", TseMode.NORMAL) }
        assertEquals(PosErrorCode.CARD_TERMINAL_NOT_FOUND, ex.code)
    }

    @Test
    fun `returns 404 when a plausible device id has no registered terminal`() {
        val repo = StubTerminalRepository(findResult = null)
        val ex = assertFailsWith<PosException> { requireSigningTerminal(repo, "real-till-01", TseMode.NORMAL) }
        assertEquals(PosErrorCode.CARD_TERMINAL_NOT_FOUND, ex.code)
        assertEquals(HttpStatusCode.NotFound, ex.httpStatus)
    }

    @Test
    fun `the not-a-till refusal says so, for an unregistered device exactly as for admin-web`() {
        val repo = StubTerminalRepository(findResult = null)
        val unregistered = assertFailsWith<PosException> { requireSigningTerminal(repo, "qa-admin", TseMode.NORMAL) }
        val adminWeb = assertFailsWith<PosException> { requireSigningTerminal(repo, "admin-web-abc", TseMode.NORMAL) }
        assertEquals(
            "Client 'qa-admin' is not a registered POS terminal and cannot perform fiscal (TSE) operations",
            unregistered.message,
        )
        assertEquals(
            "Client 'admin-web-abc' is not a registered POS terminal and cannot perform fiscal (TSE) operations",
            adminWeb.message,
        )
    }
}
