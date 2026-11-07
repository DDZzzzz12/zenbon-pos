package org.example.project.features.terminal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.database.secret.SealedSecret
import org.example.project.core.data.database.secret.SecretProtectingKeyValueStore
import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.terminal.ClientKind
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.PinnedRootTrust
import org.example.project.core.network.RootCaFetchException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.TerminalPairingKeys
import org.example.project.core.utils.RootFingerprint
import org.example.project.core.utils.sha256
import org.example.project.features.terminal.data.RootCaPinner
import org.example.project.features.terminal.presentation.viewmodels.DevicePairingViewModel
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeSecretCipher
import org.example.project.testutils.fakes.IoMarkingDispatcher
import org.example.project.testutils.fakes.SecretAccessRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DevicePairingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    /** Minimal shared-TerminalApiClient fake: only pairDevice is exercised. */
    private class FakePairingApiClient : TerminalApiClient {
        var pairResult: (suspend () -> TerminalDto.PairDeviceResponseDto)? = null

        var onPair: (TerminalDto.PairDeviceRequestDto) -> Unit = {}

        override suspend fun pairDevice(request: TerminalDto.PairDeviceRequestDto): TerminalDto.PairDeviceResponseDto {
            onPair(request)
            return pairResult!!.invoke()
        }

        override suspend fun updateTerminal(
            id: Long,
            request: TerminalDto.UpdateTerminalRequestDto,
        ): TerminalDto.TerminalResponseDto = TODO("not used by this test")

        override suspend fun registerTerminal(request: TerminalDto.RegisterTerminalRequestDto): TerminalDto.TerminalResponseDto = TODO()

        override suspend fun getCurrentTerminal(): TerminalDto.TerminalResponseDto = TODO()

        override suspend fun getCurrentTerminalByDevice(): TerminalDto.TerminalResponseDto = TODO()

        override suspend fun openStationSession(): TerminalDto.StationSessionResponseDto = TODO("not used by this test")

        override suspend fun getAllTerminals(): List<TerminalDto.TerminalSummaryDto> = TODO()

        override suspend fun deactivateTerminal(id: Long) = TODO()

        override suspend fun reactivateTerminal(id: Long): TerminalDto.TerminalSummaryDto = TODO()

        override suspend fun registerTerminalTse(id: Long): TerminalDto.TerminalSummaryDto = TODO()

        override suspend fun deregisterTerminalTse(id: Long): TerminalDto.TerminalSummaryDto = TODO()

        override suspend fun syncTseStatus(): List<TerminalDto.TerminalSummaryDto> = TODO()

        override suspend fun createTerminal(request: TerminalDto.CreateTerminalRequestDto): TerminalDto.TerminalResponseDto = TODO()

        override suspend fun generatePairingCode(
            id: Long,
            registerTseClient: Boolean,
        ): TerminalDto.PairingCodeResponseDto = TODO()

        override suspend fun getTsePairingStatus(): TerminalDto.TsePairingStatusDto = TODO()

        override suspend fun revokeBinding(id: Long): TerminalDto.TerminalSummaryDto = TODO()
    }

    private fun terminal() =
        TerminalDto.TerminalResponseDto(
            id = 7L,
            deviceId = "dev-1",
            terminalId = "TER-007",
            terminalBrand = "Android",
            terminalModel = "Tab",
            terminalSerialNr = null,
            terminalSwBrand = "POS",
            terminalSwVersion = "1.0",
            isActive = true,
            tseClientRegistered = false,
            registeredAt = "2026-07-26T10:00:00Z",
            lastSeenAt = "2026-07-26T10:00:00Z",
        )

    private fun pairingApi(deviceToken: String = "tok-xyz") =
        FakePairingApiClient().apply {
            pairResult = { TerminalDto.PairDeviceResponseDto(terminal = terminal(), deviceToken = deviceToken) }
        }

    private fun newViewModel(
        api: TerminalApiClient,
        localDb: LocalDatabaseManager,
        io: CoroutineDispatcher = testDispatcher,
    ) = DevicePairingViewModel(api, localDb, ioDispatcher = io)

    @BeforeEach fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach fun tearDown() {
        Dispatchers.resetMain()
        // Process-wide request-time holder: pairing sets it, so it must not leak into the next test.
        DeviceTokenProvider.reset()
    }

    @Test
    fun `successful pair stores token + terminal and invokes onPaired`() =
        runTest(testDispatcher) {
            val localDb = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://test-server/"))
            val vm = newViewModel(pairingApi(), localDb)
            var paired = false

            vm.pair("123456") { paired = true }
            advanceUntilIdle()

            assertTrue(paired)
            assertEquals("tok-xyz", localDb.getDeviceToken())
            assertEquals("TER-007", localDb.getTerminalId())
            assertEquals(false, vm.state.value.isPairing)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `pairing seals the device token on the IO dispatcher, never the UI thread`() =
        runTest(testDispatcher) {
            val io = IoMarkingDispatcher(testDispatcher)
            val recorder = SecretAccessRecorder(SecretProtectingKeyValueStore(FakeKeyValueStore(), FakeSecretCipher()), io)
            val localDb = LocalDatabaseManager(recorder)
            // Only the pairing's own accesses count.
            recorder.reset()
            val vm = newViewModel(pairingApi(), localDb, io)
            var paired = false

            vm.pair("123456") { paired = true }
            advanceUntilIdle()

            assertTrue(paired)
            assertEquals(listOf("set:device_token"), recorder.secretAccesses)
            assertEquals(emptyList(), recorder.offIo, "the device token was sealed on the UI thread")
        }

    /**
     * The pairing screen shows while an earlier pairing is stored exactly when that device token cannot be opened right
     * now — a locked keychain — and then sealing the new token fails too. The seal throws before anything is written, so
     * there is nothing to roll back: clearing the registration would destroy the pairing the store deliberately kept.
     */
    @Test
    fun `a device token that cannot be sealed keeps the earlier pairing the store kept`() =
        runTest(testDispatcher) {
            val cipher = FakeSecretCipher()
            val raw = FakeKeyValueStore()
            val localDb = LocalDatabaseManager(SecretProtectingKeyValueStore(raw, cipher))
            // The earlier pairing, stored while sealing still worked.
            localDb.setDeviceToken("tok-old")
            localDb.setTerminalRegistered("TER-OLD", 3L)
            val sealedOld = assertNotNull(raw.getString("device_token"), "precondition")
            assertTrue(SealedSecret.isSealed(sealedOld), "precondition")
            // The realistic case: that token could not be opened, so nothing holds it in memory.
            DeviceTokenProvider.token = null

            cipher.canSeal = false
            val vm = newViewModel(pairingApi(deviceToken = "tok-new"), localDb)
            var paired = false

            vm.pair("123456") { paired = true }
            advanceUntilIdle()

            assertFalse(paired)
            val error = assertIs<UiError.Unknown>(vm.state.value.error)
            assertEquals(TerminalPairingKeys.errorStorageFailed, error.messageKey)
            assertEquals(false, vm.state.value.isPairing)
            assertEquals(sealedOld, raw.getString("device_token"), "the kept pairing's sealed token must survive")
            assertEquals("TER-OLD", localDb.getTerminalId())
            assertEquals(3L, localDb.getTerminalDatabaseId())
            assertTrue(localDb.isTerminalRegistered())
            assertNull(DeviceTokenProvider.token, "a token that was never stored must not be sent")
            // Once the key store answers again, the kept pairing opens.
            assertEquals("tok-old", localDb.getDeviceToken())
        }

    /**
     * Once the new device token has landed the earlier pairing is gone either way. A registration that then fails to
     * store must not leave the till half-paired, sending a device token for a terminal it holds no record of.
     */
    @Test
    fun `a registration that cannot be stored after the new token landed rolls the pairing back`() =
        runTest(testDispatcher) {
            val raw = FakeKeyValueStore()
            var terminalIdWriteFails = true
            val failingOnce =
                object : KeyValueStore by raw {
                    override fun setString(
                        key: String,
                        value: String,
                    ) {
                        if (key == "terminal_id" && terminalIdWriteFails) {
                            terminalIdWriteFails = false
                            throw IllegalStateException("disk full")
                        }
                        raw.setString(key, value)
                    }
                }
            val localDb = LocalDatabaseManager(SecretProtectingKeyValueStore(failingOnce, FakeSecretCipher()))
            val vm = newViewModel(pairingApi(deviceToken = "tok-new"), localDb)
            var paired = false

            vm.pair("123456") { paired = true }
            advanceUntilIdle()

            assertFalse(terminalIdWriteFails, "precondition: the registration write failed")
            assertFalse(paired)
            val error = assertIs<UiError.Unknown>(vm.state.value.error)
            assertEquals(TerminalPairingKeys.errorStorageFailed, error.messageKey)
            assertEquals(false, vm.state.value.isPairing)
            assertNull(localDb.getDeviceToken())
            assertFalse(localDb.isTerminalRegistered())
            assertNull(localDb.getTerminalId())
            assertNull(localDb.getTerminalDatabaseId())
            assertNull(DeviceTokenProvider.token, "a rolled-back pairing must not keep sending its device token")
        }

    @Test
    fun `expired code maps to the friendly expired message`() =
        runTest(testDispatcher) {
            val api =
                FakePairingApiClient().apply {
                    pairResult = { throw PosApiException(PosErrorCode.PAIRING_CODE_EXPIRED, 410, "expired", null) }
                }
            val vm = newViewModel(api, FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://test-server/")))

            vm.pair("123456") { }
            advanceUntilIdle()

            assertEquals(
                TerminalPairingKeys.errorCodeExpired,
                vm.state.value.error
                    ?.messageKey,
            )
            assertEquals(false, vm.state.value.isPairing)
        }

    @Test
    fun `a failed TSE registration maps to the ask-the-admin message`() =
        runTest(testDispatcher) {
            val api =
                FakePairingApiClient().apply {
                    pairResult = { throw PosApiException(PosErrorCode.PAIRING_TSE_REGISTRATION_FAILED, 424, "tse", null) }
                }
            val vm = newViewModel(api, FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://test-server/")))

            vm.pair("123456") { }
            advanceUntilIdle()

            assertEquals(
                TerminalPairingKeys.errorTseRegistrationFailed,
                vm.state.value.error
                    ?.messageKey,
            )
            assertEquals(false, vm.state.value.isPairing)
        }

    @Test
    fun `a disabled browser station maps to the browser-stations-disabled message`() =
        runTest(testDispatcher) {
            val api =
                FakePairingApiClient().apply {
                    pairResult = { throw PosApiException(PosErrorCode.BROWSER_STATION_DISABLED, 403, "disabled", null) }
                }
            val vm = newViewModel(api, FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://test-server/")))

            vm.pair("123456") { }
            advanceUntilIdle()

            assertEquals(
                TerminalPairingKeys.browserStationDisabled,
                vm.state.value.error
                    ?.messageKey,
            )
            assertEquals(false, vm.state.value.isPairing)
        }

    @Test
    fun `a browser preview device id pairs as BROWSER and a native id as NATIVE`() {
        assertEquals("BROWSER", DevicePairingViewModel.buildPairRequest("123456", "pos-preview-abc").clientKind)
        assertEquals("NATIVE", DevicePairingViewModel.buildPairRequest("123456", "android-1").clientKind)
    }

    @Test
    fun `the pair call carries the effective client kind`() =
        runTest(testDispatcher) {
            var seen: TerminalDto.PairDeviceRequestDto? = null
            val api =
                FakePairingApiClient().apply {
                    pairResult = { throw PosApiException(PosErrorCode.PAIRING_CODE_INVALID, 400, "x", null) }
                    onPair = { seen = it }
                }
            val vm = newViewModel(api, FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://test-server/")))

            vm.pair("123456") { }
            advanceUntilIdle()

            val request = checkNotNull(seen)
            assertEquals(ClientKind.effective(null, request.deviceId).name, request.clientKind)
        }

    @Test
    fun `blank code sets the empty-code error without calling the api`() =
        runTest(testDispatcher) {
            val vm = newViewModel(FakePairingApiClient(), FakeLocalDatabaseManager.create())

            vm.pair("   ") { }
            advanceUntilIdle()

            assertEquals(
                TerminalPairingKeys.errorEmptyCode,
                vm.state.value.error
                    ?.messageKey,
            )
        }

    @Test
    fun `a consumed code does not reappear when the pairing screen is re-entered`() =
        runTest(testDispatcher) {
            val api =
                FakePairingApiClient().apply {
                    pairResult = { TerminalDto.PairDeviceResponseDto(terminal = terminal(), deviceToken = "tok-xyz") }
                }
            val vm = newViewModel(api, FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://test-server/")))

            vm.onScreenEntered()
            vm.updateCode("123456")
            vm.pair(vm.state.value.code) { }
            advanceUntilIdle()
            assertEquals("", vm.state.value.code)

            // Binding revoked in the back office -> the retained VM's screen is shown again.
            vm.onScreenEntered()

            assertEquals("", vm.state.value.code)
            assertNull(vm.state.value.error)
            assertEquals(false, vm.state.value.isPairing)
        }

    @Test
    fun `entering the screen with a link code pre-fills the field without pairing`() =
        runTest(testDispatcher) {
            var calls = 0
            val api = FakePairingApiClient().apply { onPair = { calls++ } }
            val vm = newViewModel(api, FakeLocalDatabaseManager.create())

            vm.onScreenEntered("123456")
            advanceUntilIdle()

            assertEquals("123456", vm.state.value.code)
            assertEquals(false, vm.state.value.isPairing)
            assertEquals(0, calls)
        }

    @Test
    fun `re-entering the screen clears a code left over from a previous visit`() =
        runTest(testDispatcher) {
            val vm = newViewModel(FakePairingApiClient(), FakeLocalDatabaseManager.create())

            vm.updateCode("654321")
            vm.onScreenEntered()

            assertEquals("", vm.state.value.code)
        }

    @Test
    fun `an invalid code stays in the field so the operator can correct it`() =
        runTest(testDispatcher) {
            val api =
                FakePairingApiClient().apply {
                    pairResult = { throw PosApiException(PosErrorCode.PAIRING_CODE_INVALID, 400, "invalid", null) }
                }
            val vm = newViewModel(api, FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://test-server/")))

            vm.updateCode("123456")
            vm.pair(vm.state.value.code) { }
            advanceUntilIdle()

            assertEquals("123456", vm.state.value.code)
            assertEquals(
                TerminalPairingKeys.errorCodeInvalid,
                vm.state.value.error
                    ?.messageKey,
            )
        }

    // --- certificate pin (check code from the pairing QR or typed) ---

    private fun pem(name: String) = checkNotNull(javaClass.getResource("/pairing-pin/$name")) { "missing $name" }.readText()

    private fun checkCodeOf(name: String): String {
        val der =
            java.security.cert.CertificateFactory
                .getInstance("X.509")
                .generateCertificate(pem(name).byteInputStream())
                .encoded
        return RootFingerprint.fromDigest(sha256(der))
    }

    private fun pairingRig(fetched: suspend (String) -> String): Triple<DevicePairingViewModel, LocalDatabaseManager, MutableList<String>> {
        val requested = mutableListOf<String>()
        val api =
            FakePairingApiClient().apply {
                onPair = { requested += it.code }
                pairResult = { TerminalDto.PairDeviceResponseDto(terminal = terminal(), deviceToken = "tok-xyz") }
            }
        val localDb = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "https://192.168.1.10/"))
        return Triple(DevicePairingViewModel(api, localDb, RootCaPinner(localDb, fetched), testDispatcher), localDb, requested)
    }

    @AfterEach fun clearPin() = PinnedRootTrust.set(null)

    @Test
    fun `a matching check code pins the root and then pairs`() =
        runTest(testDispatcher) {
            val (vm, localDb, sent) = pairingRig { pem("root.pem") }
            var paired = false

            vm.pair("123456", checkCodeOf("root.pem")) { paired = true }
            advanceUntilIdle()

            assertTrue(paired)
            assertEquals(listOf("123456"), sent)
            assertEquals(PinnedRootTrust.pem, localDb.getPinnedRootPem())
            assertTrue(PinnedRootTrust.pem!!.contains("BEGIN CERTIFICATE"))
        }

    @Test
    fun `a root that does not match the check code stops before the pairing code is sent`() =
        runTest(testDispatcher) {
            val (vm, localDb, sent) = pairingRig { pem("otherroot.pem") }
            var paired = false

            vm.pair("123456", checkCodeOf("root.pem")) { paired = true }
            advanceUntilIdle()

            assertEquals(false, paired)
            assertTrue(sent.isEmpty(), "the pairing code must not travel to an unauthenticated server")
            assertNull(localDb.getPinnedRootPem())
            assertNull(PinnedRootTrust.pem)
            assertEquals(
                TerminalPairingKeys.errorRootMismatch,
                vm.state.value.error
                    ?.messageKey,
            )
            assertEquals(false, vm.state.value.isPairing)
        }

    @Test
    fun `a malformed check code is refused without any request`() =
        runTest(testDispatcher) {
            var fetches = 0
            val (vm, _, sent) =
                pairingRig {
                    fetches++
                    pem("root.pem")
                }

            vm.pair("123456", "not-a-code") {}
            advanceUntilIdle()

            assertEquals(0, fetches)
            assertTrue(sent.isEmpty())
            assertEquals(
                TerminalPairingKeys.errorRootCodeInvalid,
                vm.state.value.error
                    ?.messageKey,
            )
        }

    @Test
    fun `an unreachable server for the root download reports it and sends nothing`() =
        runTest(testDispatcher) {
            val (vm, _, sent) = pairingRig { throw RootCaFetchException("down") }

            vm.pair("123456", checkCodeOf("root.pem")) {}
            advanceUntilIdle()

            assertTrue(sent.isEmpty())
            assertEquals(
                TerminalPairingKeys.errorRootFetchFailed,
                vm.state.value.error
                    ?.messageKey,
            )
        }

    @Test
    fun `without a check code nothing is downloaded and pairing is unchanged`() =
        runTest(testDispatcher) {
            var fetches = 0
            val (vm, localDb, sent) =
                pairingRig {
                    fetches++
                    pem("root.pem")
                }
            var paired = false

            vm.pair("123456", "  ") { paired = true }
            advanceUntilIdle()

            assertTrue(paired)
            assertEquals(0, fetches)
            assertEquals(listOf("123456"), sent)
            assertNull(localDb.getPinnedRootPem())
        }

    @Test
    fun `a new pairing replaces the earlier pin`() =
        runTest(testDispatcher) {
            val (vm, localDb, _) = pairingRig { pem("otherroot.pem") }
            localDb.setPinnedRootPem(pem("root.pem"))

            vm.pair("123456", checkCodeOf("otherroot.pem")) {}
            advanceUntilIdle()

            assertEquals(pem("otherroot.pem").filterNot(Char::isWhitespace), localDb.getPinnedRootPem()?.filterNot(Char::isWhitespace))
        }
}
