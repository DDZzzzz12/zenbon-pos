package org.example.project.features.launch.viewmodel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.SettingManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.database.secret.SecretProtectingKeyValueStore
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.RootCaFetchException
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.PosApiExceptionDecoder
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.LaunchKeys
import org.example.project.core.presentation.strings.keys.TerminalPairingKeys
import org.example.project.core.utils.RootFingerprint
import org.example.project.core.utils.sha256
import org.example.project.features.launch.presentation.TerminalColdResetReason
import org.example.project.features.launch.presentation.TerminalLifecycleHandler
import org.example.project.features.launch.presentation.nav.AppDestination
import org.example.project.features.launch.presentation.nav.StationBlock
import org.example.project.features.launch.presentation.viewmodels.LaunchCoordinator
import org.example.project.features.launch.presentation.viewmodels.SETUP_CHECK_TIMEOUT_MS
import org.example.project.features.launch.presentation.viewmodels.STATION_BLOCK_RECHECK_MS
import org.example.project.features.launch.presentation.viewmodels.StationRefusal
import org.example.project.features.terminal.data.RootCaPinner
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeNotificationConnection
import org.example.project.testutils.fakes.FakePlatformNotificationService
import org.example.project.testutils.fakes.FakeSecretCipher
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.testutils.fakes.IoMarkingDispatcher
import org.example.project.testutils.fakes.SecretAccessRecorder
import org.example.project.ui.auth.SessionState
import org.example.project.ui.auth.StartupPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.testutils.fakes.FakeLaunchSetupApiClient as FakeSetupApiClient
import org.example.project.testutils.fakes.FakeLaunchTerminalApiClient as FakeTerminalApiClient

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        // Process-wide request-time holder: setDeviceToken sets it, so it must not leak into the next test.
        DeviceTokenProvider.reset()
    }

    /**
     * Everything a coordinator needs, over in-memory fakes; the session pieces are real classes. Storage runs on
     * [ioDispatcher], or on the test's own dispatcher when that is null, so virtual time drives it.
     */
    private inner class Rig(
        val store: FakeKeyValueStore = FakeKeyValueStore(),
        val db: LocalDatabaseManager = LocalDatabaseManager(store),
        val setup: FakeSetupApiClient = FakeSetupApiClient(),
        val term: FakeTerminalApiClient = FakeTerminalApiClient(),
        val tokenStore: TokenStore = FakeTokenStore.create(),
        val ioDispatcher: CoroutineDispatcher? = null,
        val fetchedRootPem: String? = null,
    ) {
        /** Over [store], like the app's single KeyValueStore — so an address it saves is what [db] reads. */
        val settings = SettingManager(store)
        val permissionManager = PermissionManager(db, tokenStore)
        val session = SessionState(tokenStore, StartupPolicy.WIPE)
        val connection = FakeNotificationConnection()
        val notifications = FakePlatformNotificationService()

        /** How often the handler asked the HTTP client to forget its cached bearer token. */
        var cachedAuthClears = 0
            private set

        /** The real handler: a refusal must run the same teardown a server-pushed revoke does. */
        val lifecycle =
            TerminalLifecycleHandler(connection, tokenStore, db, permissionManager, session) { cachedAuthClears++ }

        /**
         * The automatic re-check of a blocked station is OFF by default here: it reschedules itself on
         * every block, so `advanceUntilIdle()` on a blocked station would never go idle. The tests for
         * it pass an interval.
         */
        fun coordinator(stationRecheckIntervalMs: Long? = null) =
            LaunchCoordinator(
                setup,
                term,
                db,
                tokenStore,
                permissionManager,
                session,
                lifecycle,
                settings,
                notifications,
                stationRecheckIntervalMs,
                ioDispatcher ?: dispatcher,
                RootCaPinner(db) { fetchedRootPem ?: throw RootCaFetchException("no root served") },
            )

        /** A principal already signed in on this device — what a refusal has to tear down. */
        fun signInAnAdmin() {
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
            tokenStore.accessToken = STATION_JWT
            permissionManager.applyRoleSnapshot(assertNotNull(permissionManager.readRoleSnapshot(), "precondition: the admin's role is readable"))
            session.markLoggedIn()
        }
    }

    private fun terminal(role: String) =
        TerminalDto.TerminalResponseDto(
            id = 7L,
            deviceId = "dev-1",
            terminalId = "TER-007",
            terminalBrand = "A",
            terminalModel = "Tab",
            terminalSerialNr = null,
            terminalSwBrand = "POS",
            terminalSwVersion = "1.0",
            isActive = true,
            tseClientRegistered = false,
            registeredAt = "t",
            lastSeenAt = "t",
            role = role,
            fiscal = true,
        )

    private fun stationSession(accessToken: String = STATION_JWT) =
        TerminalDto.StationSessionResponseDto(
            accessToken = accessToken,
            expiresAt = "2030-01-01T00:00:00Z",
            terminalId = "TER-007",
            role = "ORDER_STATION",
            capabilities = setOf("ORDER_READ", "ORDER_WRITE", "RESERVATION_READ"),
        )

    /**
     * Records every refusal the coordinator raises. The conflated channel behind
     * [LaunchCoordinator.refusals] buffers, so a refusal raised before this collector attaches is
     * still delivered on the next [advanceUntilIdle] — which is exactly the production guarantee
     * being relied on.
     */
    private fun TestScope.refusalsOf(c: LaunchCoordinator): List<StationRefusal> {
        val seen = mutableListOf<StationRefusal>()
        backgroundScope.launch { c.refusals.collect { seen += it } }
        return seen
    }

    @Test
    fun no_token_resolves_to_pairing() =
        runTest(dispatcher) {
            val c = Rig().coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Pairing, c.destination.value)
        }

    @Test
    fun cashier_token_is_login_then_main_after_session_flips() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("CASHIER") }
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Login, c.destination.value)
            c.setSessionStatus(SessionStatus.AUTHENTICATED)
            advanceUntilIdle()
            assertEquals(AppDestination.Main, c.destination.value)
        }

    @Test
    fun invalid_credentials_clears_token_and_pairs() =
        runTest(dispatcher) {
            val rig =
                Rig(
                    db =
                        FakeLocalDatabaseManager.create().apply {
                            setDeviceToken("stale")
                            setTerminalRegistered("TER-OLD", 1L)
                        },
                )
            rig.term.terminalByDeviceResult = { throw PosApiException(PosErrorCode.AUTH_INVALID_CREDENTIALS, 401, "x", null) }
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Pairing, c.destination.value)
            assertTrue(rig.db.getDeviceToken().isNullOrBlank())
        }

    @Test
    fun setup_incomplete_resolves_to_setup() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.setup.status = InitialSetupDto.InitialSetupStatusDto(isInitialized = false, requiresSetup = true)
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Setup(canChangeServerAddress = true), c.destination.value)
        }

    /**
     * The setup-required screen used to store a typed server address with no check at all. A paired
     * till reaches it when its own server answers that it has not been set up (a reset or reinstalled
     * server), so it must be neither offered the change nor able to make it.
     */
    @Test
    fun a_paired_device_on_the_setup_screen_is_not_offered_and_cannot_change_the_server_address() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.db.setDeviceToken("tok")
            rig.setup.status = InitialSetupDto.InitialSetupStatusDto(isInitialized = false, requiresSetup = true)
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Setup(canChangeServerAddress = false), c.destination.value)
            val before = rig.db.getApiBaseUrl()

            assertFalse(c.changeServerAddress("https://192.168.1.99:8080"))
            advanceUntilIdle()

            assertEquals(before, rig.db.getApiBaseUrl(), "nothing may be stored for a paired device")
            assertEquals("tok", rig.db.getDeviceToken(), "refusing must not un-pair the device")
            assertEquals(LaunchKeys.serverAddressLockedWhilePaired, c.serverAddressError.value?.messageKey)
            assertEquals(AppDestination.Setup(canChangeServerAddress = false), c.destination.value)
        }

    @Test
    fun an_unpaired_device_whose_server_certificate_is_untrusted_lands_on_pairing_not_offline() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.setup.failure = NetworkApiException(javax.net.ssl.SSLHandshakeException("Trust anchor for certification path not found."))
            val c = rig.coordinator()
            advanceUntilIdle()

            assertEquals(AppDestination.Pairing, c.destination.value)
        }

    @Test
    fun pairing_through_an_untrusted_certificate_re_runs_the_launch_once_the_certificate_is_pinned() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.term.terminalByDeviceResult = { terminal("CASHIER") }
            rig.setup.failure = NetworkApiException(javax.net.ssl.SSLHandshakeException("Trust anchor for certification path not found."))
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Pairing, c.destination.value)

            // The pairing screen pinned the root and paired: the server answers now.
            rig.setup.failure = null
            rig.db.setDeviceToken("tok")
            c.onPaired(terminal("CASHIER"))
            advanceUntilIdle()

            assertEquals(AppDestination.Login, c.destination.value)
        }

    @Test
    fun a_server_that_never_answers_ends_the_launch_in_the_offline_screen_after_the_setup_timeout() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.setup.hang = true
            val c = rig.coordinator()
            advanceTimeBy(SETUP_CHECK_TIMEOUT_MS - 1)
            assertEquals(AppDestination.Resolving, c.destination.value)

            advanceTimeBy(2)
            advanceUntilIdle()
            assertEquals(AppDestination.Offline(canChangeServerAddress = true), c.destination.value)
        }

    private fun rootPem() = checkNotNull(javaClass.getResource("/pairing-pin/root.pem")).readText()

    private fun rootCheckCode(): String {
        val der =
            java.security.cert.CertificateFactory
                .getInstance("X.509")
                .generateCertificate(rootPem().byteInputStream())
                .encoded
        return RootFingerprint.fromDigest(sha256(der))
    }

    private fun untrusted() = NetworkApiException(javax.net.ssl.SSLHandshakeException("Trust anchor for certification path not found."))

    @Test
    fun a_paired_till_behind_an_untrusted_certificate_is_offered_the_check_code_and_pins_without_unpairing() =
        runTest(dispatcher) {
            val rig = Rig(fetchedRootPem = rootPem())
            rig.db.setDeviceToken("tok")
            rig.setup.failure = untrusted()
            rig.term.terminalByDeviceResult = { terminal("CASHIER") }
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Offline(canChangeServerAddress = false, certificateUntrusted = true), c.destination.value)

            rig.setup.failure = null // the server is trusted once the root is pinned
            c.pinServerCertificate(rootCheckCode())
            advanceUntilIdle()

            assertEquals("tok", rig.db.getDeviceToken(), "the pairing must survive")
            assertTrue(rig.db.getPinnedRootPem()!!.contains("BEGIN CERTIFICATE"))
            assertNull(c.serverAddressError.value)
            assertEquals(AppDestination.Login, c.destination.value)
        }

    @Test
    fun a_wrong_check_code_pins_nothing_and_leaves_the_till_offline() =
        runTest(dispatcher) {
            val rig = Rig(fetchedRootPem = rootPem())
            rig.db.setDeviceToken("tok")
            rig.setup.failure = untrusted()
            val c = rig.coordinator()
            advanceUntilIdle()

            c.pinServerCertificate("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZW")
            advanceUntilIdle()

            assertNull(rig.db.getPinnedRootPem())
            assertEquals(TerminalPairingKeys.errorRootMismatch, c.serverAddressError.value?.messageKey)
            assertEquals(AppDestination.Offline(canChangeServerAddress = false, certificateUntrusted = true), c.destination.value)
        }

    @Test
    fun saving_the_https_address_with_a_check_code_pins_first_and_stores_nothing_when_that_fails() =
        runTest(dispatcher) {
            val rig = Rig(fetchedRootPem = rootPem())
            rig.db.setDeviceToken("tok")
            rig.db.setApiBaseUrl("http://192.168.1.10:8080/")
            rig.term.terminalByDeviceResult = { terminal("CASHIER") }
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.InsecureServerUrl, c.destination.value)

            c.saveHttpsAddress("https://192.168.1.10/", "ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZW")
            advanceUntilIdle()
            assertEquals("http://192.168.1.10:8080/", rig.db.getApiBaseUrl(), "a failed pin must not move the address")
            assertNull(rig.db.getPinnedRootPem())

            c.saveHttpsAddress("https://192.168.1.10/", rootCheckCode())
            advanceUntilIdle()
            assertEquals("https://192.168.1.10/", rig.db.getApiBaseUrl())
            assertTrue(rig.db.getPinnedRootPem()!!.contains("BEGIN CERTIFICATE"))
        }

    @Test
    fun saving_a_plain_http_address_from_the_insecure_screen_is_refused() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.db.setApiBaseUrl("http://192.168.1.10:8080/")
            val c = rig.coordinator()
            advanceUntilIdle()

            c.saveHttpsAddress("http://192.168.1.10:8080/", null)
            advanceUntilIdle()

            assertEquals(LaunchKeys.serverAddressInvalid, c.serverAddressError.value?.messageKey)
            assertEquals("http://192.168.1.10:8080/", rig.db.getApiBaseUrl())
        }

    @Test
    fun a_paired_device_whose_server_certificate_is_untrusted_stays_offline() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.db.setDeviceToken("tok")
            rig.setup.failure = NetworkApiException(javax.net.ssl.SSLHandshakeException("Trust anchor for certification path not found."))
            val c = rig.coordinator()
            advanceUntilIdle()

            assertEquals(AppDestination.Offline(canChangeServerAddress = false, certificateUntrusted = true), c.destination.value)
        }

    @Test
    fun an_unpaired_device_on_the_setup_screen_changes_the_server_address_and_re_runs_the_launch() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.setup.status = InitialSetupDto.InitialSetupStatusDto(isInitialized = false, requiresSetup = true)
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Setup(canChangeServerAddress = true), c.destination.value, "precondition")

            // The typed address is a server that has been set up.
            rig.setup.status = InitialSetupDto.InitialSetupStatusDto(isInitialized = true, requiresSetup = false)
            assertTrue(c.changeServerAddress("192.168.1.20:8080"))
            advanceUntilIdle()

            assertEquals("https://192.168.1.20:8080/", rig.db.getApiBaseUrl())
            assertNull(c.serverAddressError.value)
            assertEquals(AppDestination.Pairing, c.destination.value)
        }

    @Test
    fun network_failure_resolves_to_offline() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.setup.throwNetwork = true
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Offline(canChangeServerAddress = true), c.destination.value)
        }

    // --- Server address on the offline launch screen (2026-09-13) ---

    /**
     * A paired till that cannot reach its server is not offered a new address: the setup check fails
     * before the device check runs, so the locally stored token is what decides.
     */
    @Test
    fun a_paired_device_offline_at_launch_is_not_offered_the_server_address_change() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.db.setDeviceToken("tok")
            rig.setup.throwNetwork = true
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Offline(canChangeServerAddress = false), c.destination.value)
        }

    /**
     * The fresh-install dead end this exists for: the default address is `http://localhost:8080/`,
     * which on a tablet or emulator is the device itself, and the offline screen had only a retry.
     * The server here answers at the typed address only, so reaching Pairing proves the launch was
     * re-run AFTER the address was stored, not merely that a retry happened.
     */
    @Test
    fun changing_the_server_address_stores_it_and_re_runs_the_launch_against_it() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.setup.reachable = { rig.db.getApiBaseUrl() == "https://192.168.1.20:8080/" }
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Offline(canChangeServerAddress = true), c.destination.value, "precondition")

            assertTrue(c.changeServerAddress(" 192.168.1.20:8080 "))
            advanceUntilIdle()

            assertEquals("https://192.168.1.20:8080/", rig.db.getApiBaseUrl(), "the HTTP client resolves its base URL from here")
            assertEquals("https://192.168.1.20:8080/", rig.settings.url.value, "the pairing screen shows the address from here")
            assertNull(c.serverAddressError.value)
            assertEquals(AppDestination.Pairing, c.destination.value)
        }

    /**
     * The logic half of the guard — the offline screen hides the action for a paired device, and this
     * refuses it anyway. The token would otherwise ride on every request to whatever address was
     * typed, and the terminal's pairing, TSE client and journal belong to the server that issued it.
     */
    @Test
    fun a_paired_device_refuses_to_change_its_server_address() =
        runTest(dispatcher) {
            TranslationRegistry.initialize(defaultTranslations())
            val rig = Rig()
            rig.db.setDeviceToken("tok")
            rig.setup.throwNetwork = true
            val c = rig.coordinator()
            advanceUntilIdle()
            val before = rig.db.getApiBaseUrl()

            assertFalse(c.changeServerAddress("https://192.168.1.99:8080"))
            advanceUntilIdle()

            assertEquals(before, rig.db.getApiBaseUrl(), "nothing may be stored for a paired device")
            assertEquals("tok", rig.db.getDeviceToken(), "refusing must not un-pair the device")
            assertEquals(AppDestination.Offline(canChangeServerAddress = false), c.destination.value)
            val error = c.serverAddressError.value
            assertEquals(LaunchKeys.serverAddressLockedWhilePaired, error?.messageKey)
            for (language in Language.entries) {
                val shown = getString(LaunchKeys.serverAddressLockedWhilePaired, language)
                assertNotEquals(LaunchKeys.serverAddressLockedWhilePaired.id, shown, "untranslated in $language")
            }
        }

    @Test
    fun an_invalid_server_address_is_rejected_and_not_stored() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.setup.throwNetwork = true
            val c = rig.coordinator()
            advanceUntilIdle()
            val before = rig.db.getApiBaseUrl()

            assertFalse(c.changeServerAddress("ftp://192.168.1.20"))
            advanceUntilIdle()

            assertEquals(before, rig.db.getApiBaseUrl())
            assertEquals(LaunchKeys.serverAddressInvalid, c.serverAddressError.value?.messageKey)
            assertEquals(AppDestination.Offline(canChangeServerAddress = true), c.destination.value)
            c.clearServerAddressError()
            assertNull(c.serverAddressError.value)
        }

    @Test
    fun on_paired_cashier_resolves_to_login() =
        runTest(dispatcher) {
            val c = Rig().coordinator()
            advanceUntilIdle()
            c.onPaired(terminal("CASHIER"))
            advanceUntilIdle()
            assertEquals(AppDestination.Login, c.destination.value)
        }

    /**
     * Pairing a till opens no session — the person still signs in, and that login starts the service. Starting it
     * here would stream for nobody (the service stops without an AUTHENTICATED session).
     */
    @Test
    fun pairing_a_cashier_starts_no_notification_service() =
        runTest(dispatcher) {
            val rig = Rig()
            val c = rig.coordinator()
            advanceUntilIdle()

            c.onPaired(terminal("CASHIER"))
            advanceUntilIdle()

            assertEquals(AppDestination.Login, c.destination.value)
            assertEquals(0, rig.notifications.startServiceCallCount)
        }

    /**
     * A freshly paired order station never logs in — the exchange is its login — so the exchange has to start the
     * notification service a login would. On Android nothing else opens the stream, and the station received no
     * push at all. The session must already be AUTHENTICATED when the service starts, or the service reads a
     * logged-out session and stops (NOTE-8).
     */
    @Test
    fun pairing_a_station_starts_the_notification_service_with_a_live_session() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.term.stationSessionResult = { stationSession() }
            var statusAtStart: SessionStatus? = null
            rig.notifications.onStartService = { statusAtStart = rig.session.status }
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.Pairing, c.destination.value)
            assertEquals(0, rig.notifications.startServiceCallCount)

            c.onPaired(terminal("ORDER_STATION"))
            advanceUntilIdle()

            assertEquals(1, rig.notifications.startServiceCallCount)
            assertEquals(SessionStatus.AUTHENTICATED, statusAtStart)
        }

    /** A refused exchange opens no session, so it must not start a service either. */
    @Test
    fun a_refused_station_exchange_starts_no_notification_service() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { throw PosApiException(PosErrorCode.INTERNAL_ERROR, 409, "Device not paired", null) }
            rig.coordinator()
            advanceUntilIdle()

            assertEquals(0, rig.notifications.startServiceCallCount)
        }

    @Test
    fun cold_reset_resolves_to_pairing() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("CASHIER") }
            val c = rig.coordinator()
            advanceUntilIdle()
            c.setSessionStatus(SessionStatus.AUTHENTICATED)
            advanceUntilIdle()
            c.onColdReset()
            advanceUntilIdle()
            assertEquals(AppDestination.Pairing, c.destination.value)
        }

    /**
     * The gate reads the device token straight from storage, and hands what it read to the request-time holder. A setup
     * check that caught the key store not ready leaves the holder inside its no-retry window; without the hand-over the
     * device check went out with no X-Device-Token, and the server's 401 read as the offline screen.
     */
    @Test
    fun the_device_token_the_gate_reads_seeds_the_request_time_holder() =
        runTest(dispatcher) {
            // Persisted directly, not through setDeviceToken, which would set the holder itself.
            val rig = Rig(db = FakeLocalDatabaseManager.create(mapOf("device_token" to "tok")))
            var heldDuringDeviceCheck: String? = null
            rig.term.terminalByDeviceResult = {
                heldDuringDeviceCheck = DeviceTokenProvider.token
                terminal("CASHIER")
            }
            rig.coordinator()
            advanceUntilIdle()

            assertEquals("tok", heldDuringDeviceCheck, "the device check must go out with the token the gate just read")
            assertEquals("tok", DeviceTokenProvider.token)
        }

    /**
     * A cold reset — a server-pushed revoke, say — that clears the device token while the gate is still reading it wins.
     * The gate used to hand the token it had read before the reset to the request-time holder, putting a revoked token
     * back into memory for every request that followed. Once it stopped doing that, it still ran the device check with
     * the token just cleared: the request went out without X-Device-Token, the server answered 401, and the gate read that
     * as a network fault — the offline screen, instead of pairing.
     */
    @Test
    fun a_cold_reset_during_the_gates_device_token_read_resolves_to_pairing_without_a_device_check() =
        runTest(dispatcher) {
            // Persisted directly, not through setDeviceToken, which would set the holder itself.
            val store = DeviceTokenReadHook().apply { setString("device_token", "tok") }
            val rig = Rig(db = LocalDatabaseManager(store))
            val tokensSentToDeviceCheck = mutableListOf<String?>()
            rig.term.terminalByDeviceResult = {
                tokensSentToDeviceCheck += DeviceTokenProvider.token
                // What the server answers a device check that carries no device token.
                if (DeviceTokenProvider.token == null) throw PosApiException(PosErrorCode.AUTH_SESSION_EXPIRED, 401, null, null)
                terminal("CASHIER")
            }
            // The gate has read "tok"; the reset lands before the read returns to it. The reset's navigation callback is
            // left unwired, so pairing has to come from the gate's own decision.
            store.onDeviceTokenRead = { rig.lifecycle.coldReset(TerminalColdResetReason.DEVICE_TOKEN_REJECTED) }
            val c = rig.coordinator()
            advanceUntilIdle()

            assertNull(DeviceTokenProvider.token, "the reset cleared the device token; the gate's older read must not put it back")
            assertEquals(emptyList(), tokensSentToDeviceCheck, "no device check may go out once the token it carries was cleared")
            assertEquals(AppDestination.Pairing, c.destination.value)
        }

    /**
     * The holder can change during the read without being cleared: a request that loaded the token itself installs its own
     * copy. The gate then seeds nothing, but the device check still goes out, with the token the holder carries.
     */
    @Test
    fun a_device_token_the_holder_gained_during_the_gates_read_still_runs_the_device_check() =
        runTest(dispatcher) {
            val store = DeviceTokenReadHook().apply { setString("device_token", "tok") }
            val rig = Rig(db = LocalDatabaseManager(store))
            var heldDuringDeviceCheck: String? = "not checked"
            rig.term.terminalByDeviceResult = {
                heldDuringDeviceCheck = DeviceTokenProvider.token
                terminal("CASHIER")
            }
            store.onDeviceTokenRead = { DeviceTokenProvider.token = "tok" }
            val c = rig.coordinator()
            advanceUntilIdle()

            assertEquals("tok", heldDuringDeviceCheck)
            assertEquals(AppDestination.Login, c.destination.value)
        }

    /** Storage whose next device-token read runs [onDeviceTokenRead] once — after reading the value, before returning it. */
    private class DeviceTokenReadHook(
        private val raw: FakeKeyValueStore = FakeKeyValueStore(),
    ) : KeyValueStore by raw {
        var onDeviceTokenRead: () -> Unit = {}

        override fun getString(key: String): String? {
            val stored = raw.getString(key)
            if (key == "device_token") {
                val hook = onDeviceTokenRead
                onDeviceTokenRead = {}
                hook()
            }
            return stored
        }
    }

    // --- Order station (2026-09-06) ---

    @Test
    fun station_exchange_seeds_the_session_and_resolves_to_main() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { stationSession() }
            val c = rig.coordinator()
            advanceUntilIdle()

            // The exchange is the station's login.
            assertEquals(SessionStatus.AUTHENTICATED, rig.session.status)
            assertEquals(STATION_JWT, rig.tokenStore.accessToken)
            assertNull(rig.tokenStore.refreshToken)
            assertEquals("ORDER_STATION", rig.tokenStore.stationRole)
            assertEquals(EmployeeRole.TERMINAL_DEVICE, rig.db.getRole())
            assertEquals("TER-007", rig.db.getEmployeeNumber())
            // The station's employee id comes out of the token itself, not a placeholder.
            assertEquals(STATION_EMPLOYEE_ID, rig.db.getUserId())
            assertTrue(
                rig.permissionManager.accessPolicy.value
                    .allows(Capability.ORDER_WRITE),
            )
            assertFalse(
                rig.permissionManager.accessPolicy.value
                    .allows(Capability.BILL_READ),
            )

            // AppNavigation bridges SessionState.status into the coordinator; do the same here.
            c.setSessionStatus(rig.session.status)
            advanceUntilIdle()
            assertEquals(AppDestination.Main, c.destination.value)
        }

    /**
     * A device someone signed into as a person while it was still a CASHIER, whose terminal role
     * is now ORDER_STATION — the shape of the live defect.
     *
     * AUTHENTICATED alone resolved it to Main, and Main is the one destination the exchange is NOT
     * triggered from, so the device never obtained a station JWT: it kept presenting the admin's
     * token on every request, including the order-station SSE stream, for as long as it ran.
     * Marking the session authenticated is not enough — it has to have been marked so BY the
     * exchange.
     */
    @Test
    fun a_person_session_on_an_order_station_still_performs_the_exchange() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.signInAnAdmin()
            assertEquals(SessionStatus.AUTHENTICATED, rig.session.status, "precondition")
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            var exchanges = 0
            rig.term.stationSessionResult = {
                exchanges++
                stationSession()
            }
            val c = rig.coordinator()
            // The app root bridges the live — already authenticated — session in, as it would here.
            c.setSessionStatus(rig.session.status)
            advanceUntilIdle()

            assertEquals(1, exchanges, "a person's session on a station is not a station session")
            assertEquals("ORDER_STATION", rig.tokenStore.stationRole)
            assertEquals(EmployeeRole.TERMINAL_DEVICE, rig.db.getRole(), "the station identity must replace the person's")
            assertEquals(STATION_EMPLOYEE_ID, rig.db.getUserId())
            assertEquals(AppDestination.Main, c.destination.value)
        }

    /**
     * A station's session ending mid-shift is not a dead end: it has no login screen, so the
     * exchange is its only way back into a session. Before this, the exchange ran once from
     * `resolveDevice`; when the session later ended (an expired token, a logout triggered by the
     * notification stream) the gate resolved to StationSession — the launch screen — and nothing
     * ever re-ran the exchange, so the station sat on "Starten…" until someone restarted it.
     */
    @Test
    fun station_session_ending_re_runs_the_exchange_instead_of_hanging_on_the_launch_screen() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            var exchanges = 0
            rig.term.stationSessionResult = {
                exchanges++
                stationSession()
            }
            val c = rig.coordinator()
            advanceUntilIdle()
            c.setSessionStatus(rig.session.status)
            advanceUntilIdle()
            assertEquals(AppDestination.Main, c.destination.value)
            assertEquals(1, exchanges)

            // The session ends while the device stays paired — what a logout does.
            rig.session.logout()
            c.setSessionStatus(rig.session.status)
            advanceUntilIdle()

            assertEquals(2, exchanges, "a station cannot log in: re-exchanging is its only way back")
            c.setSessionStatus(rig.session.status)
            advanceUntilIdle()
            assertEquals(AppDestination.Main, c.destination.value)
        }

    @Test
    fun station_exchange_refused_clears_the_token_and_pairs() =
        runTest(dispatcher) {
            val rig =
                Rig(
                    db =
                        FakeLocalDatabaseManager.create().apply {
                            setDeviceToken("tok")
                            setTerminalRegistered("TER-007", 7L)
                        },
                )
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { throw PosApiException(PosErrorCode.INTERNAL_ERROR, 409, "Device not paired", null) }
            val c = rig.coordinator()
            val refusals = refusalsOf(c)
            advanceUntilIdle()

            assertEquals(AppDestination.Pairing, c.destination.value)
            assertTrue(rig.db.getDeviceToken().isNullOrBlank())
            assertEquals("Device not paired", refusals.single().serverDetail)
            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)
        }

    /**
     * The refusal must run the whole cold reset, not a hand-rolled subset of it.
     *
     * The session is deliberately marked in FIRST: asserting UNAUTHENTICATED on a session that was
     * never marked in passes vacuously, and the surviving AUTHENTICATED state is precisely the bug
     * — a device re-paired as CASHIER walks straight into Main past the login screen.
     */
    @Test
    fun station_refusal_cold_resets_a_session_that_was_already_signed_in() =
        runTest(dispatcher) {
            val rig =
                Rig(
                    db =
                        FakeLocalDatabaseManager.create().apply {
                            setDeviceToken("tok")
                            setTerminalRegistered("TER-007", 7L)
                        },
                )
            rig.signInAnAdmin()
            assertEquals(SessionStatus.AUTHENTICATED, rig.session.status, "precondition")
            assertEquals(EmployeeRole.ADMIN, rig.permissionManager.currentRole.value, "precondition")

            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { throw PosApiException(PosErrorCode.INTERNAL_ERROR, 403, "Terminal deactivated", null) }
            val c = rig.coordinator()
            advanceUntilIdle()

            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)
            assertNull(rig.permissionManager.currentRole.value)
            assertTrue(rig.db.getUserId().isNullOrEmpty(), "the previous principal's user id must not survive")
            assertNull(rig.tokenStore.accessToken)
            assertEquals(AppDestination.Pairing, c.destination.value)
        }

    /**
     * The two cases the feature exists for — a revoked (409) and a deactivated (403) terminal —
     * answer with a plain `{"error":"..."}` map, not RFC 7807. `ProblemDetail`'s fields are all
     * optional and the decoder's Json ignores unknown keys, so that body parses into an all-null
     * record and the decoded message is null. Rendering `rawDetail` alone therefore showed the
     * operator nothing at all. Driven through the real decoder, not a hand-built exception.
     */
    @Test
    fun a_refusal_with_a_plain_error_body_still_explains_itself() =
        runTest(dispatcher) {
            TranslationRegistry.initialize(defaultTranslations())
            val decoded = decodeCeilingRefusal(HttpStatusCode.Conflict, """{"error":"Device not paired"}""")
            assertNull(decoded.message, "the ceiling's body is not RFC 7807; this is the condition under test")

            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { throw decoded }
            val c = rig.coordinator()
            val refusals = refusalsOf(c)
            advanceUntilIdle()

            val refusal = refusals.single()
            assertNull(refusal.serverDetail, "no server sentence to show")
            assertEquals(LaunchKeys.stationRefusedMessage, refusal.error.messageKey)
            // What the user actually sees, resolved exactly as AppNavigation resolves it.
            val shown = refusal.serverDetail ?: getString(refusal.error.messageKey, Language.ENGLISH)
            assertTrue(shown.isNotBlank(), "a refusal must never be silent")
            assertNotEquals(refusal.error.messageKey.id, shown, "the key must resolve to a real translation")
            assertEquals(AppDestination.Pairing, c.destination.value)
        }

    /**
     * 401 is the route plugin's and the device-principal challenge's answer to an expired device
     * token. Treated as a network blip it looped on the offline screen forever.
     */
    @Test
    fun station_exchange_401_is_a_refusal_not_a_blip() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { throw PosApiException(PosErrorCode.AUTH_INVALID_CREDENTIALS, 401, null, null) }
            val c = rig.coordinator()
            val refusals = refusalsOf(c)
            advanceUntilIdle()

            assertEquals(AppDestination.Pairing, c.destination.value)
            assertEquals(1, refusals.size)
            assertTrue(rig.db.getDeviceToken().isNullOrBlank())
            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)
        }

    /**
     * 424 is the terminal ceiling's answer when the server signs to hardware and this station has
     * no registered TSE client. Read as a network blip it showed "server unreachable" with a retry
     * that could never succeed, sending a technician after a fault that does not exist. It is also
     * not a refusal: the pairing is intact and must survive, or the fix costs a re-pairing too.
     *
     * Driven through the real decoder over the ceiling's real `{"error":...}` body.
     */
    @Test
    fun station_exchange_424_names_the_unregistered_tse_client_and_keeps_the_pairing() =
        runTest(dispatcher) {
            TranslationRegistry.initialize(defaultTranslations())
            val decoded =
                decodeCeilingRefusal(HttpStatusCode.FailedDependency, """{"error":"Terminal not TSE-registered"}""")
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { throw decoded }
            val c = rig.coordinator()
            val refusals = refusalsOf(c)
            advanceUntilIdle()

            assertEquals(
                AppDestination.StationBlocked(StationBlock.TSE_CLIENT_NOT_REGISTERED),
                c.destination.value,
                "424 must not resolve to the offline screen",
            )
            assertEquals("tok", rig.db.getDeviceToken(), "a server-side fault must not un-pair the device")
            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)

            val warning = refusals.single()
            assertEquals(LaunchKeys.stationTseNotRegistered, warning.error.messageKey)
            val shown = warning.serverDetail ?: getString(warning.error.messageKey, Language.ENGLISH)
            assertNotEquals(warning.error.messageKey.id, shown, "the key must resolve to a real translation")
            assertFalse(shown.contains("424"), "an HTTP status is not an explanation for staff")
        }

    /**
     * 403 BROWSER_STATION_DISABLED is the shop's setting, not a verdict on this device: the pairing
     * must survive so that re-enabling works without re-pairing. It must never reach [refuse], which
     * cold-resets and un-pairs on every other 403.
     */
    @Test
    fun station_exchange_browser_station_disabled_blocks_without_unpairing() =
        runTest(dispatcher) {
            TranslationRegistry.initialize(defaultTranslations())
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = {
                throw PosApiException(PosErrorCode.BROWSER_STATION_DISABLED, 403, "disabled", null)
            }
            val c = rig.coordinator()
            val refusals = refusalsOf(c)
            advanceUntilIdle()

            assertEquals(
                AppDestination.StationBlocked(StationBlock.BROWSER_STATIONS_DISABLED),
                c.destination.value,
            )
            assertEquals("tok", rig.db.getDeviceToken(), "a disabled shop setting must not un-pair the device")
            assertEquals(TerminalPairingKeys.browserStationDisabled, refusals.single().error.messageKey)
        }

    /**
     * 500 is the answer when the shared terminal-device employee is not provisioned — a broken
     * install, not a broken network and not a broken pairing. The server's own detail names server
     * internals, so it is dropped in favour of the localized sentence.
     */
    @Test
    fun station_exchange_500_names_the_incomplete_install_and_keeps_the_pairing() =
        runTest(dispatcher) {
            TranslationRegistry.initialize(defaultTranslations())
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = {
                throw PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "terminal-device employee missing", null)
            }
            val c = rig.coordinator()
            val refusals = refusalsOf(c)
            advanceUntilIdle()

            assertEquals(
                AppDestination.StationBlocked(StationBlock.SERVER_INSTALLATION_INCOMPLETE),
                c.destination.value,
                "500 must not resolve to the offline screen",
            )
            assertEquals("tok", rig.db.getDeviceToken())
            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)

            val warning = refusals.single()
            assertEquals(LaunchKeys.stationServerIncomplete, warning.error.messageKey)
            assertNull(warning.serverDetail, "the server's internal sentence is not shown to staff")
            val shown = getString(warning.error.messageKey, Language.ENGLISH)
            assertNotEquals(warning.error.messageKey.id, shown, "the key must resolve to a real translation")
        }

    /** The whole point of not cold-resetting: the retry works once the server side is repaired. */
    @Test
    fun a_blocked_station_starts_on_retry_once_the_server_is_fixed() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { throw PosApiException(PosErrorCode.INTERNAL_ERROR, 424, null, null) }
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(
                AppDestination.StationBlocked(StationBlock.TSE_CLIENT_NOT_REGISTERED),
                c.destination.value,
                "precondition",
            )

            rig.term.stationSessionResult = { stationSession() }
            c.refresh()
            advanceUntilIdle()

            assertEquals(SessionStatus.AUTHENTICATED, rig.session.status)
            // AppNavigation bridges SessionState.status into the coordinator; do the same here.
            c.setSessionStatus(rig.session.status)
            advanceUntilIdle()
            assertEquals(AppDestination.Main, c.destination.value)
        }

    /** A block belongs to the pairing it was raised for: a new pairing runs its own exchange instead of showing the old block. */
    @Test
    fun re_pairing_clears_a_stale_station_block() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { throw PosApiException(PosErrorCode.INTERNAL_ERROR, 424, null, null) }
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(
                AppDestination.StationBlocked(StationBlock.TSE_CLIENT_NOT_REGISTERED),
                c.destination.value,
                "precondition",
            )
            c.pairDifferentDevice()
            advanceUntilIdle()
            assertEquals(AppDestination.Pairing, c.destination.value, "precondition")

            rig.term.stationSessionResult = { stationSession() }
            c.onPaired(terminal("ORDER_STATION"))
            advanceUntilIdle()

            assertEquals(SessionStatus.AUTHENTICATED, rig.session.status, "the new pairing opens its own station session")
            c.setSessionStatus(rig.session.status)
            advanceUntilIdle()
            assertEquals(AppDestination.Main, c.destination.value)
        }

    /**
     * E2E-2026-09-18 #16: a blocked station has no JWT and no event stream, so a role change never
     * reaches it. It must re-check on its own and follow the new role — without the pairing path.
     */
    @Test
    fun a_blocked_station_rechecks_on_its_own_and_follows_a_role_change() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { throw PosApiException(PosErrorCode.INTERNAL_ERROR, 424, null, null) }
            val c = rig.coordinator(stationRecheckIntervalMs = STATION_BLOCK_RECHECK_MS)
            runCurrent()
            assertEquals(AppDestination.StationBlocked(StationBlock.TSE_CLIENT_NOT_REGISTERED), c.destination.value, "precondition")

            // The admin switches the terminal back to CASHIER; nobody touches the device. A CASHIER
            // is never blocked, so no further re-check is scheduled and the scheduler goes idle.
            rig.term.terminalByDeviceResult = { terminal("CASHIER") }
            advanceTimeBy(STATION_BLOCK_RECHECK_MS + 1)
            advanceUntilIdle()

            assertEquals(AppDestination.Login, c.destination.value, "it followed the role change without a tap")
            assertEquals("tok", rig.db.getDeviceToken(), "still paired: the pairing path was never taken")
        }

    @Test
    fun a_blocked_station_does_not_recheck_before_the_interval() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { throw PosApiException(PosErrorCode.INTERNAL_ERROR, 424, null, null) }
            val c = rig.coordinator(stationRecheckIntervalMs = STATION_BLOCK_RECHECK_MS)
            runCurrent()
            rig.term.terminalByDeviceResult = { terminal("CASHIER") }

            advanceTimeBy(STATION_BLOCK_RECHECK_MS - 1)
            runCurrent()

            assertEquals(AppDestination.StationBlocked(StationBlock.TSE_CLIENT_NOT_REGISTERED), c.destination.value)
        }

    /**
     * An undecodable station token is a broken build or a spoofed response. Storing an empty
     * employee id half-initialized the shell — and the same null decode makes
     * `PermissionManager.readRoleSnapshot` return no role at all, which the client nav gate reads
     * as "unknown" rather than "denied".
     */
    @Test
    fun an_undecodable_station_token_is_refused_not_adopted() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { stationSession(accessToken = "not-a-jwt") }
            val c = rig.coordinator()
            val refusals = refusalsOf(c)
            advanceUntilIdle()

            assertEquals(AppDestination.Pairing, c.destination.value)
            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)
            assertNull(rig.tokenStore.accessToken, "a token that cannot be read is never stored")
            assertTrue(rig.db.getUserId().isNullOrEmpty())
            assertEquals(LaunchKeys.stationTokenUnreadable, refusals.single().error.messageKey)
        }

    @Test
    fun station_exchange_network_failure_is_offline_with_retry() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { throw NetworkApiException(RuntimeException("offline")) }
            val c = rig.coordinator()
            advanceUntilIdle()

            assertEquals(AppDestination.Offline(canChangeServerAddress = false), c.destination.value)
            assertEquals("tok", rig.db.getDeviceToken(), "a blip must not throw the station back to pairing")
        }

    // --- HTTPS-only server address (2026-09-12) ---

    /** A paired till whose stored server address is the pre-Caddy plain-HTTP one. */
    private fun rigOnAnInsecureUrl(onDeviceLookup: () -> Unit): Rig {
        val rig =
            Rig(
                db =
                    FakeLocalDatabaseManager.create().apply {
                        setDeviceToken("tok")
                        setApiBaseUrl("http://192.168.1.10:8080/")
                    },
            )
        rig.term.terminalByDeviceResult = {
            onDeviceLookup()
            terminal("CASHIER")
        }
        return rig
    }

    /**
     * A shop that auto-updated the server jar without re-running the installer still serves plain
     * HTTP on :8080. The setup probe and the device lookup both carry the device token, so the gate
     * must show the recovery screen without making either call.
     */
    @Test
    fun an_insecure_stored_url_shows_the_recovery_screen_without_calling_the_server() =
        runTest(dispatcher) {
            var deviceLookups = 0
            val rig = rigOnAnInsecureUrl { deviceLookups++ }
            val c = rig.coordinator()
            c.refresh()
            advanceUntilIdle()

            assertEquals(AppDestination.InsecureServerUrl, c.destination.value)
            assertEquals(0, rig.setup.checkSetupStatusCalls, "the setup probe would send the device token over http")
            assertEquals(0, deviceLookups)
        }

    @Test
    fun saving_an_https_url_leaves_the_recovery_screen_and_resumes_the_launch_checks() =
        runTest(dispatcher) {
            var deviceLookups = 0
            val rig = rigOnAnInsecureUrl { deviceLookups++ }
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.InsecureServerUrl, c.destination.value, "precondition")

            // What the recovery screen's "Save and retry" does.
            rig.db.setApiBaseUrl("https://kasse.example.de/")
            c.refresh()
            advanceUntilIdle()

            assertNotEquals(AppDestination.InsecureServerUrl, c.destination.value)
            assertEquals(AppDestination.Login, c.destination.value)
            assertEquals(1, rig.setup.checkSetupStatusCalls)
            assertEquals(1, deviceLookups)
        }

    /**
     * The exchange answered, but the station token could not be sealed: Keystore or keychain unusable right now. The
     * seal failure used to escape the destination collector and crash the app; after that it was a refusal, whose cold
     * reset deleted the device token — for good when that token was legacy plaintext the store could not re-seal.
     * A transient key-store failure must never destroy a pairing: the station blocks with a retry instead, and no part
     * of the session is left behind.
     *
     * Device token and session share one sealing store, as on the till, so the pairing that must survive is sealed too.
     */
    @Test
    fun a_station_session_that_cannot_be_stored_blocks_with_a_retry_and_keeps_the_pairing() =
        runTest(dispatcher) {
            TranslationRegistry.initialize(defaultTranslations())
            val cipher = FakeSecretCipher()
            val store = SecretProtectingKeyValueStore(FakeKeyValueStore(), cipher)
            val rig =
                Rig(
                    db =
                        LocalDatabaseManager(store).apply {
                            setDeviceToken("tok")
                            setTerminalRegistered("TER-007", 7L)
                        },
                    tokenStore = TokenStore(store),
                )
            cipher.canSeal = false
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { stationSession() }
            val c = rig.coordinator()
            val refusals = refusalsOf(c)
            advanceUntilIdle()

            assertEquals(AppDestination.StationBlocked(StationBlock.LOCAL_STORAGE_UNAVAILABLE), c.destination.value)
            val error = assertIs<UiError.Unknown>(refusals.single().error)
            assertEquals(LaunchKeys.stationBlockedTitle, error.titleKey)
            assertEquals(LaunchKeys.stationStorageUnavailable, error.messageKey)
            assertIs<IllegalStateException>(error.cause)
            assertNotEquals(error.messageKey.id, getString(error.messageKey, Language.ENGLISH), "the key must resolve to a real translation")

            assertEquals("tok", rig.db.getDeviceToken(), "a key store that cannot seal right now must not un-pair the device")
            assertTrue(rig.db.isTerminalRegistered())
            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)
            assertNull(rig.tokenStore.accessToken)
            assertNull(rig.tokenStore.stationRole, "a half-stored station marker must not survive")
            assertNull(rig.tokenStore.role)
            assertTrue(rig.db.getUserId().isNullOrEmpty())
            assertNull(rig.permissionManager.currentRole.value)

            // The key store answers again: the retry on the blocked screen starts the station on the same pairing.
            cipher.canSeal = true
            c.refresh()
            advanceUntilIdle()

            assertEquals(SessionStatus.AUTHENTICATED, rig.session.status)
            // AppNavigation bridges SessionState.status into the coordinator; do the same here.
            c.setSessionStatus(rig.session.status)
            advanceUntilIdle()
            assertEquals(AppDestination.Main, c.destination.value)
            assertEquals("tok", rig.db.getDeviceToken())
        }

    /**
     * A cold reset — a server-pushed revoke, say — that lands while the station session is being stored wins. The store
     * finishing afterwards used to mark the session logged in on a device already reset to pairing, with the station
     * token written back after the reset had cleared it.
     */
    @Test
    fun a_cold_reset_during_the_station_session_store_is_not_undone_when_the_store_finishes() =
        runTest(dispatcher) {
            val raw = FakeKeyValueStore()
            var onAccessTokenWrite: () -> Unit = {}
            val hooked =
                object : KeyValueStore by raw {
                    override fun setString(
                        key: String,
                        value: String,
                    ) {
                        if (key == "jwt_access_token") {
                            val hook = onAccessTokenWrite
                            onAccessTokenWrite = {}
                            hook()
                        }
                        raw.setString(key, value)
                    }
                }
            val rig =
                Rig(
                    db =
                        FakeLocalDatabaseManager.create().apply {
                            setDeviceToken("tok")
                            setTerminalRegistered("TER-007", 7L)
                        },
                    tokenStore = TokenStore(hooked),
                )
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { stationSession() }
            val c = rig.coordinator()
            // Wired as AppNavigation wires it.
            rig.lifecycle.onColdReset = { c.onColdReset() }
            onAccessTokenWrite = { rig.lifecycle.coldReset(TerminalColdResetReason.DEVICE_TOKEN_REJECTED) }
            advanceUntilIdle()

            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status, "a reset device must not be logged in")
            assertNull(rig.tokenStore.accessToken, "the station token written after the reset must be discarded")
            assertNull(rig.tokenStore.stationRole)
            assertTrue(rig.db.getUserId().isNullOrEmpty())
            assertNull(rig.permissionManager.currentRole.value)
            assertEquals(AppDestination.Pairing, c.destination.value)
        }

    @Test
    fun the_device_token_read_and_the_station_session_seal_run_on_the_io_dispatcher() =
        runTest(dispatcher) {
            val io = IoMarkingDispatcher(dispatcher)
            val recorder = SecretAccessRecorder(SecretProtectingKeyValueStore(FakeKeyValueStore(), FakeSecretCipher()), io)
            val rig =
                Rig(
                    db = LocalDatabaseManager(recorder).apply { setDeviceToken("tok") },
                    tokenStore = TokenStore(recorder),
                    ioDispatcher = io,
                )
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            rig.term.stationSessionResult = { stationSession() }
            // Setting up the rig read and wrote the device token itself; only the coordinator's accesses count.
            recorder.reset()
            rig.coordinator()
            advanceUntilIdle()

            assertEquals(SessionStatus.AUTHENTICATED, rig.session.status)
            assertTrue(
                recorder.secretAccesses.containsAll(listOf("get:device_token", "set:jwt_access_token")),
                "expected the device token read and the station token seal, got ${recorder.secretAccesses}",
            )
            assertEquals(emptyList(), recorder.offIo, "a secret was opened or sealed on the UI thread")
        }

    @Test
    fun kitchen_is_still_a_placeholder() =
        runTest(dispatcher) {
            val rig = Rig(db = FakeLocalDatabaseManager.create().apply { setDeviceToken("tok") })
            rig.term.terminalByDeviceResult = { terminal("KITCHEN") }
            val c = rig.coordinator()
            advanceUntilIdle()
            assertEquals(AppDestination.RolePlaceholder("TER-007", "KITCHEN"), c.destination.value)
        }

    // --- A terminal role change (2026-09-17) ---

    /**
     * Wires the app root's two lifecycle callbacks onto the rig's REAL handler and delivers a
     * `ROLE_CHANGED` termination — the whole production chain, including the `clearSession()` that
     * empties the token store before the coordinator is called back.
     *
     * **`runCurrent()`, never `advanceUntilIdle()`.** The handler collects in [backgroundScope],
     * which `advanceUntilIdle` does not drain, so it would not have subscribed before the emit — and
     * `FakeNotificationConnection.messages` is a `replay = 0` shared flow, which drops a value with
     * no subscriber and never redelivers it. Same trap, same fix, as
     * `TerminalLifecycleHandlerTest.startAndDeliver`.
     */
    private suspend fun TestScope.deliverRoleChange(
        rig: Rig,
        c: LaunchCoordinator,
    ) {
        rig.lifecycle.onColdReset = { c.onColdReset() }
        rig.lifecycle.onSessionReset = { c.onTerminalRoleChanged() }
        rig.lifecycle.start(backgroundScope)
        runCurrent()
        rig.connection.emitMessage(
            NotificationDto.TerminalSessionTerminatedDto(
                timestamp = "2026-09-17T13:25:13Z",
                terminalId = "TER-007",
                reason = "ROLE_CHANGED",
                requiresRepairing = false,
            ),
        )
        runCurrent()
    }

    /**
     * ORDER_STATION → CASHIER, and the reason the reset has to be ONE input mutation.
     *
     * `setSessionStatus(UNAUTHENTICATED)` followed by `refresh()` recomputes the destination on the
     * FIRST of the two, while `LaunchInputs.device` still holds `Paired(ORDER_STATION)`. That
     * intermediate — UNAUTHENTICATED plus a stale station role — resolves to `StationSession`, the
     * one trigger for the exchange, so the device fired it against a server already on the new role
     * and was answered 403 ("no shell for role CASHIER"); the refusal branch then cold-reset and
     * UN-PAIRED it. Captured on the device at 13:25:13, one tenth of a second after the termination
     * arrived with `requiresRepairing = false` — the flag whose entire purpose is to keep the
     * pairing.
     *
     * **Main is unconfined here on purpose, and only here.** `viewModelScope` runs on
     * `Dispatchers.Main.immediate`, which resumes the `stateIn` collector INLINE inside the first
     * `inputs.update`, so it observes that intermediate. A queuing dispatcher — `StandardTestDispatcher`,
     * as everywhere else in this file — runs the collector only after both mutations and reads the
     * conflated final value, which hides the defect completely: the old two-call shape passes under
     * it. This is the one test in the file whose failure mode depends on WHEN the collector runs, so
     * it is the one test that models the real dispatcher.
     */
    @Test
    fun a_role_change_to_cashier_attempts_no_station_exchange_and_keeps_the_pairing() =
        runTest(dispatcher) {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val rig =
                Rig(
                    db =
                        FakeLocalDatabaseManager.create().apply {
                            setDeviceToken("tok")
                            setTerminalRegistered("TER-007", 7L)
                        },
                )
            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            var exchanges = 0
            rig.term.stationSessionResult = {
                exchanges++
                stationSession()
            }
            val c = rig.coordinator()
            advanceUntilIdle()
            c.setSessionStatus(rig.session.status)
            advanceUntilIdle()
            assertEquals(AppDestination.Main, c.destination.value, "precondition: a running order station")
            assertEquals(1, exchanges, "precondition: it obtained a station session")

            // The operator re-roles the terminal. From here the server answers with the NEW role,
            // and refuses a station exchange exactly as the live one did.
            rig.term.terminalByDeviceResult = { terminal("CASHIER") }
            rig.term.stationSessionResult = {
                exchanges++
                throw PosApiException(PosErrorCode.AUTH_FORBIDDEN, 403, "no shell for role CASHIER", null)
            }
            deliverRoleChange(rig, c)
            advanceUntilIdle()

            assertEquals(1, exchanges, "the exchange must never be attempted against the role that just went away")
            assertEquals("tok", rig.db.getDeviceToken(), "a role change must not un-pair the device")
            assertTrue(rig.db.isTerminalRegistered())
            assertEquals("TER-007", rig.db.getTerminalId())
            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)
            assertNull(rig.tokenStore.accessToken, "the station JWT was minted for the role that just went away")
            assertEquals(1, rig.cachedAuthClears, "and the HTTP client must not keep serving it from its own cache")
            assertEquals(AppDestination.Login, c.destination.value, "a cashier with no session signs in")
        }

    /**
     * The mirror: CASHIER → ORDER_STATION. Keeping the pairing is only half of what the reset owes —
     * the launch flow has to be RE-RUN, so the surviving device token is traded for a station JWT
     * exactly once and the station identity replaces the person's. A station that never exchanges
     * goes on presenting the signed-in admin's token, which is the defect the role-change path was
     * built to close.
     */
    @Test
    fun a_role_change_to_order_station_performs_the_exchange_exactly_once() =
        runTest(dispatcher) {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val rig =
                Rig(
                    db =
                        FakeLocalDatabaseManager.create().apply {
                            setDeviceToken("tok")
                            setTerminalRegistered("TER-007", 7L)
                        },
                )
            rig.term.terminalByDeviceResult = { terminal("CASHIER") }
            var exchanges = 0
            rig.term.stationSessionResult = {
                exchanges++
                stationSession()
            }
            val c = rig.coordinator()
            advanceUntilIdle()
            rig.signInAnAdmin()
            c.setSessionStatus(rig.session.status)
            advanceUntilIdle()
            assertEquals(AppDestination.Main, c.destination.value, "precondition: a till with a person signed in")
            assertEquals(0, exchanges, "precondition: a cashier never exchanges")

            rig.term.terminalByDeviceResult = { terminal("ORDER_STATION") }
            deliverRoleChange(rig, c)
            advanceUntilIdle()

            assertEquals(1, exchanges, "the station's only way into a session")
            assertEquals("ORDER_STATION", rig.tokenStore.stationRole)
            assertEquals(EmployeeRole.TERMINAL_DEVICE, rig.db.getRole(), "the person's identity must not survive the re-role")
            assertEquals(STATION_EMPLOYEE_ID, rig.db.getUserId())
            assertEquals("tok", rig.db.getDeviceToken(), "a role change must not un-pair the device")

            // AppNavigation bridges SessionState.status into the coordinator; do the same here.
            c.setSessionStatus(rig.session.status)
            advanceUntilIdle()
            assertEquals(AppDestination.Main, c.destination.value)
        }

    /** The real decoder over a real response, so the null-message condition is not simulated. */
    private suspend fun decodeCeilingRefusal(
        status: HttpStatusCode,
        body: String,
    ): PosApiException {
        val client =
            HttpClient(
                MockEngine { _ ->
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf("Content-Type", listOf("application/json")),
                    )
                },
            )
        return PosApiExceptionDecoder.decode(client.get("http://test/"))
    }

    companion object {
        private const val STATION_EMPLOYEE_ID = "emp-station-1"

        /** An unsigned but realistically shaped token, so the decode branch is actually exercised. */
        @OptIn(ExperimentalEncodingApi::class)
        private val STATION_JWT: String =
            run {
                val payload = """{"sub":"$STATION_EMPLOYEE_ID","employeeNumber":"TER-007","role":"TERMINAL_DEVICE"}"""
                val encoded = Base64.UrlSafe.encode(payload.encodeToByteArray()).trimEnd('=')
                "eyJhbGciOiJub25lIn0.$encoded.sig"
            }
    }
}
