package org.example.project.features.launch.lifecycle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.launch.presentation.TerminalColdResetReason
import org.example.project.features.launch.presentation.TerminalLifecycleHandler
import org.example.project.features.launch.presentation.TerminalResetKind
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeNotificationConnection
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.ui.auth.SessionState
import org.example.project.ui.auth.StartupPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How far a server-pushed `terminal_session_terminated` tears the device down.
 *
 * The distinction is `requiresRepairing`: a revoked or deactivated terminal loses its pairing, a
 * re-roled one keeps it. Before the flag was honoured every termination un-paired the device, so
 * switching a till to an order station — an operator action, not a fault — cost a technician a
 * walk to the device and a hand-typed pairing code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalLifecycleHandlerTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The handler over in-memory fakes; the token store, permissions and session are real classes. */
    private class Rig {
        val db: LocalDatabaseManager = FakeLocalDatabaseManager.create()
        val tokenStore: TokenStore = FakeTokenStore.create()
        val permissionManager = PermissionManager(db, tokenStore)
        val session = SessionState(tokenStore, StartupPolicy.WIPE)
        val connection = FakeNotificationConnection()

        /** Stands in for the `BearerAuthProvider` sweep `viewModelModule` wires in production. */
        var cachedAuthClears = 0
            private set

        val handler =
            TerminalLifecycleHandler(connection, tokenStore, db, permissionManager, session) { cachedAuthClears++ }

        var coldResets = 0
            private set
        var sessionResets = 0
            private set

        init {
            handler.onColdReset = { coldResets++ }
            handler.onSessionReset = { sessionResets++ }
        }

        /**
         * A paired terminal with a person signed in on it — the exact state the live defect was
         * observed in: an admin logged in while the device was a CASHIER, then re-roled.
         *
         * Seeded after the rig is built, because `StartupPolicy.WIPE` clears the token store as
         * `SessionState` is constructed.
         */
        fun pairedWithAnAdminSignedIn() {
            db.setDeviceToken("device-token")
            db.setTerminalRegistered("TER-007", 7L)
            db.setLoginUserData(username = "anna", password = "pw", rememberMe = true)
            db.setUserInfo(
                EmployeeInfoDto.EmployeeInfo(
                    id = "emp-1",
                    username = "anna",
                    employeeNumber = "42",
                    role = EmployeeRole.ADMIN.name,
                    trainingMode = false,
                    capabilities = setOf("BILL_READ"),
                ),
            )
            tokenStore.accessToken = PERSON_JWT
            tokenStore.refreshToken = "refresh"
            permissionManager.readRoleSnapshot()?.let(permissionManager::applyRoleSnapshot)
            session.markLoggedIn()
        }
    }

    private fun terminated(requiresRepairing: Boolean) =
        NotificationDto.TerminalSessionTerminatedDto(
            timestamp = "2026-09-17T10:00:00Z",
            terminalId = "TER-007",
            reason = if (requiresRepairing) "REVOKED" else "ROLE_CHANGED",
            requiresRepairing = requiresRepairing,
        )

    /**
     * Starts the handler, waits until it is really subscribed, and only then pushes [message].
     *
     * **`runCurrent()`, never `advanceUntilIdle()`.** The handler collects in [backgroundScope],
     * and `advanceUntilIdle` drains only FOREGROUND tasks — with nothing else queued it returns
     * having run nothing at all, so `start` had not subscribed yet. `FakeNotificationConnection`'s
     * `messages` is a `MutableSharedFlow(replay = 0)`, which drops a value emitted while there is
     * no subscriber and never redelivers it, so every test silently observed no message and
     * asserted against a handler that was never called. `runCurrent` runs everything queued at the
     * current virtual time, background included — which is both halves of what this needs: the
     * subscribe before the emit, and the whole `collectLatest` delivery chain after it.
     *
     * Kept as one helper rather than repeated in each test so the ordering cannot be got wrong
     * again, and so the trap is written down once.
     */
    private suspend fun TestScope.startAndDeliver(
        rig: Rig,
        message: NotificationDto.NotificationMessage,
    ) {
        rig.handler.start(backgroundScope)
        runCurrent()
        rig.connection.emitMessage(message)
        runCurrent()
    }

    /**
     * The role-change path. The session goes — the JWT was minted for the old role — but the
     * pairing survives, so the launch flow can be re-run against the same device token and an
     * order station can trade it for a station JWT with nobody touching the device.
     */
    @Test
    fun a_role_change_drops_the_session_and_keeps_the_pairing() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.pairedWithAnAdminSignedIn()
            assertEquals(SessionStatus.AUTHENTICATED, rig.session.status, "precondition")
            assertEquals(EmployeeRole.ADMIN, rig.permissionManager.currentRole.value, "precondition")

            startAndDeliver(rig, terminated(requiresRepairing = false))

            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)
            assertNull(rig.tokenStore.accessToken, "the old role's token must not outlive the role")
            assertNull(rig.tokenStore.refreshToken)
            assertNull(rig.permissionManager.currentRole.value)
            assertTrue(rig.db.getUserId().isNullOrEmpty(), "the previous principal must not survive")
            assertTrue(rig.db.getLoginUserData().isNullOrEmpty())

            assertEquals("device-token", rig.db.getDeviceToken(), "a role change must not un-pair the device")
            assertTrue(rig.db.isTerminalRegistered())
            assertEquals("TER-007", rig.db.getTerminalId())

            assertEquals(1, rig.sessionResets, "the app root must re-run the launch, not fall through to pairing")
            assertEquals(0, rig.coldResets)
        }

    /**
     * The API-failure path, which is what the HTTP client's `onUnauthorized` reaches: a session the
     * server rejected ends the login, not the pairing. Unpairing there sent a technician out with a
     * pairing code for what a login would have fixed, and it emptied both tills in the 2026-09-19
     * e2e run when pos-server was restarted under them.
     */
    @Test
    fun a_rejected_session_returns_to_login_with_the_pairing_intact() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.pairedWithAnAdminSignedIn()

            rig.handler.sessionReset()

            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)
            assertNull(rig.tokenStore.accessToken)
            assertNull(rig.tokenStore.refreshToken)

            assertEquals("device-token", rig.db.getDeviceToken(), "an expired session must not un-pair the device")
            assertTrue(rig.db.isTerminalRegistered())
            assertEquals("TER-007", rig.db.getTerminalId())

            assertEquals(1, rig.sessionResets)
            assertEquals(0, rig.coldResets, "nobody should have to re-pair this device")
        }

    /**
     * Every unpair records what licensed it, before the wipe removes the evidence. On 2026-09-19
     * two tills asked for a pairing code and nothing on either of them, or on the server, said why.
     */
    @Test
    fun an_unpair_records_the_reason_it_was_allowed() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.pairedWithAnAdminSignedIn()

            rig.handler.coldReset(TerminalColdResetReason.DEVICE_TOKEN_REJECTED)

            assertEquals(
                TerminalColdResetReason.DEVICE_TOKEN_REJECTED.diagnostic,
                rig.db.getPreference(TerminalLifecycleHandler.LAST_COLD_RESET_REASON),
                "support has to be able to ask the device why it wants a pairing code",
            )
        }

    /**
     * The structural half of the same rule: a caller cannot ASK for an unpair without naming a
     * cause the server gave. `TerminalResetKind` is the only route into the handler from the app
     * (the HTTP client, the nav footer), and its COLD values each carry a reason; SESSION carries
     * none and therefore cannot reach the cold path at all.
     */
    @Test
    fun the_reset_bus_offers_no_unpair_for_a_locally_inferred_error() =
        runTest(dispatcher) {
            assertNull(
                TerminalResetKind.SESSION.coldResetReason,
                "an expired session must not be able to request an unpair",
            )
            assertEquals(
                setOf(TerminalColdResetReason.DEVICE_TOKEN_REJECTED, TerminalColdResetReason.OPERATOR_REPAIR),
                TerminalResetKind.entries.mapNotNull { it.coldResetReason }.toSet(),
                "the only requestable unpairs are a server refusal and an operator asking for one",
            )
        }

    /** A device-token refusal (409/403 on the station exchange) still unpairs: that pairing is dead. */
    @Test
    fun a_rejected_device_token_still_cold_resets() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.pairedWithAnAdminSignedIn()

            rig.handler.coldReset(TerminalColdResetReason.DEVICE_TOKEN_REJECTED)

            assertTrue(rig.db.getDeviceToken().isNullOrBlank())
            assertTrue(rig.db.getTerminalId().isNullOrBlank())
            assertEquals(1, rig.coldResets)
            assertEquals(0, rig.sessionResets)
        }

    /** A revoked or deactivated terminal is the case the pairing itself is wrong for. */
    @Test
    fun a_revoked_terminal_still_gets_the_full_cold_reset() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.pairedWithAnAdminSignedIn()

            startAndDeliver(rig, terminated(requiresRepairing = true))

            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)
            assertNull(rig.tokenStore.accessToken)
            assertTrue(rig.db.getDeviceToken().isNullOrBlank(), "a revoked pairing must go")
            assertTrue(rig.db.getTerminalId().isNullOrBlank())

            assertEquals(1, rig.coldResets)
            assertEquals(0, rig.sessionResets, "an un-paired device has no launch flow to re-run")
        }

    /**
     * `requiresRepairing` defaults to true on the wire, so a server that predates the flag — or any
     * future message that omits it — keeps today's behaviour rather than silently leaving a revoked
     * terminal paired.
     */
    @Test
    fun a_message_without_the_flag_cold_resets() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.pairedWithAnAdminSignedIn()

            startAndDeliver(
                rig,
                NotificationDto.TerminalSessionTerminatedDto(
                    timestamp = "2026-09-17T10:00:00Z",
                    terminalId = "TER-007",
                    reason = "DEACTIVATED",
                ),
            )

            assertEquals(1, rig.coldResets)
            assertEquals(0, rig.sessionResets)
            assertTrue(rig.db.getDeviceToken().isNullOrBlank())
        }

    /**
     * Clearing [TokenStore] is only half of dropping the credential.
     *
     * Ktor's `BearerAuthProvider` caches whatever `loadTokens` last returned and reloads it only on
     * a 401, so a reset that empties the store alone leaves the client sending the PREVIOUS person's
     * JWT. Measured on the server: a terminal re-roled to ORDER_STATION opened its station session
     * correctly ("Station session opened for terminal TER-002") and the SSE stream that followed was
     * `employee=31d368e0… station=false` — the admin's identity, not the shared terminal-device
     * account. The device then ran, and mounted the till UI, as that admin.
     *
     * Asserted on all three entry points because the sweep lives in the shared `clearSession()`: the
     * two SSE paths and the public [TerminalLifecycleHandler.coldReset] the 409/401/403 API fallback
     * (and `LaunchCoordinator.refuse`) calls. If it ever moves into one of the callers, the other
     * two stop clearing and this fails.
     */
    @Test
    fun every_reset_path_drops_the_http_client_s_cached_bearer_token() =
        runTest(dispatcher) {
            val roleChange = Rig().also { it.pairedWithAnAdminSignedIn() }
            startAndDeliver(roleChange, terminated(requiresRepairing = false))
            assertEquals(1, roleChange.cachedAuthClears, "a session reset must not leave the old JWT cached")
            assertEquals(1, roleChange.sessionResets, "precondition: the reset really ran")

            val revoked = Rig().also { it.pairedWithAnAdminSignedIn() }
            startAndDeliver(revoked, terminated(requiresRepairing = true))
            assertEquals(1, revoked.cachedAuthClears, "a cold reset shares clearSession(), so it gets it too")
            assertEquals(1, revoked.coldResets, "precondition: the reset really ran")

            val apiFailure = Rig().also { it.pairedWithAnAdminSignedIn() }
            apiFailure.handler.coldReset(TerminalColdResetReason.DEVICE_TOKEN_REJECTED)
            assertEquals(1, apiFailure.cachedAuthClears, "the API-failure fallback runs the same teardown")
        }

    companion object {
        /** An unsigned but realistically shaped token, so the role snapshot's decode really runs. */
        @OptIn(ExperimentalEncodingApi::class)
        private val PERSON_JWT: String =
            run {
                val payload = """{"sub":"emp-1","employeeNumber":"42","role":"ADMIN"}"""
                val encoded = Base64.UrlSafe.encode(payload.encodeToByteArray()).trimEnd('=')
                "eyJhbGciOiJub25lIn0.$encoded.sig"
            }
    }
}
