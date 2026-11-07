package org.example.project.service

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.terminal.data.service.BrowserStationPolicy
import org.example.project.features.terminal.data.service.DeviceTokenGenerator
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.data.service.TerminalServiceImpl
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.repository.TerminalPairingCodeRepository
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.tse.data.service.TseTerminalFiscalGateway
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A4 Task 1: [TerminalCapabilityResolver] caches the device -> terminal-capability lookup and
 * only reflects a DB change once explicitly invalidated.
 */
@DisplayName("TerminalCapabilityResolver (A4 Task 1)")
class TerminalCapabilityResolverTest {
    @Test
    fun `byDeviceId caches until invalidate, unknown device returns null`() =
        testApp(
            setupData = {
                // Provision + "pair" a fiscal CASHIER terminal directly (deviceId="dev-1"), TSE-registered.
                transaction {
                    Terminal.new {
                        this.deviceId = "dev-1"
                        this.clientId = "TER-CAP1"
                        this.role = "CASHIER"
                        this.fiscal = true
                        this.tseClientRegistered = true
                        this.isActive = true
                        this.registeredAt = currentTimeStamp()
                        this.lastSeenAt = currentTimeStamp()
                    }
                }

                val resolver = GlobalContext.get().get<TerminalCapabilityResolver>()

                // 1) byDeviceId("dev-1") returns the expected capability.
                val initial = runBlocking { resolver.byDeviceId("dev-1") }
                assertTrue(initial != null, "expected a capability for dev-1")
                assertEquals("TER-CAP1", initial!!.clientId)
                assertEquals("dev-1", initial.deviceId)
                assertEquals("CASHIER", initial.role)
                assertTrue(initial.fiscal)
                assertTrue(initial.tseClientRegistered)
                assertTrue(initial.isActive)

                // 2) DB flag flips WITHOUT invalidation — resolver still returns the cached (stale) value.
                transaction {
                    Terminal.find { TerminalTable.deviceId eq "dev-1" }.first().tseClientRegistered = false
                }
                val stale = runBlocking { resolver.byDeviceId("dev-1") }
                assertTrue(stale!!.tseClientRegistered, "expected the cached (stale) value before invalidate")

                // 3) After invalidate("dev-1"), the resolver re-reads and returns the fresh value.
                resolver.invalidate("dev-1")
                val fresh = runBlocking { resolver.byDeviceId("dev-1") }
                assertFalse(fresh!!.tseClientRegistered, "expected the fresh value after invalidate")

                // 4) An unknown device resolves to null.
                val unknown = runBlocking { resolver.byDeviceId("nope") }
                assertNull(unknown)
            },
        ) { _ -> }

    // SECURITY BLOCKER regression test: a login-less station (ORDER_STATION/KITCHEN/etc.) is cached
    // EXCLUSIVELY via byTokenHash — the ceiling's resolveCeilingCapability path 1 and the
    // device-principal provider both call byTokenHash; byDevice is only ever populated by the JWT /
    // raw-X-Device-ID paths. Every OTHER test in this file primes via byDeviceId first, which masked
    // this bug (a prior version of invalidate() did `byDevice.remove(deviceId) ?: return`, so when
    // byDevice had no entry it early-returned WITHOUT ever touching byTokenHash). This test primes
    // via byTokenHash ONLY, proving invalidate(deviceId) evicts the token-hash entry unconditionally.
    @Test
    fun `invalidate evicts a token-only cached entry for a login-less station`() =
        testApp(
            setupData = {
                transaction {
                    Terminal.new {
                        this.deviceId = "dev-tokenonly1"
                        this.clientId = "TER-CAP-TOKONLY1"
                        this.role = "ORDER_STATION"
                        this.fiscal = true
                        this.tseClientRegistered = true
                        this.isActive = true
                        this.deviceTokenHash = "hash-tokenonly1"
                        this.registeredAt = currentTimeStamp()
                        this.lastSeenAt = currentTimeStamp()
                    }
                }
                val resolver = GlobalContext.get().get<TerminalCapabilityResolver>()

                // Prime the cache via byTokenHash ONLY — never call byDeviceId first.
                val primed = runBlocking { resolver.byTokenHash("hash-tokenonly1") }
                assertTrue(primed != null, "expected a capability for hash-tokenonly1")
                assertTrue(primed!!.isActive)

                // Revoke/deactivate at the DB layer WITHOUT invalidation — resolver still returns the
                // cached (stale) value, proving the cache is actually live.
                transaction {
                    Terminal.find { TerminalTable.deviceId eq "dev-tokenonly1" }.first().isActive = false
                }
                val stale = runBlocking { resolver.byTokenHash("hash-tokenonly1") }
                assertTrue(stale!!.isActive, "expected the cached (stale) value before invalidate")

                // invalidate("dev-tokenonly1") must evict the byTokenHash entry too — this deviceId was
                // NEVER looked up via byDeviceId, so byDevice has no entry for it. The pre-fix code's
                // `byDevice.remove(deviceId) ?: return` would early-return here and leave byTokenHash
                // stale FOREVER (no TTL) — a revoked login-less fiscal station would keep signing.
                resolver.invalidate("dev-tokenonly1")
                val fresh = runBlocking { resolver.byTokenHash("hash-tokenonly1") }
                assertFalse(fresh!!.isActive, "expected the fresh (revoked) value after invalidate")
            },
        ) { _ -> }

    // A4 Task 2: TerminalServiceImpl invalidates the resolver's cache itself after each mutation —
    // these tests never call resolver.invalidate() manually; only the service path does.

    @Test
    fun `revokeBinding invalidates the terminal's cached capability`() =
        testApp(
            setupData = {
                val terminal =
                    transaction {
                        Terminal.new {
                            this.deviceId = "dev-revoke1"
                            this.clientId = "TER-CAP-REVOKE1"
                            this.role = "CASHIER"
                            this.fiscal = true
                            this.tseClientRegistered = true
                            this.isActive = true
                            this.registeredAt = currentTimeStamp()
                            this.lastSeenAt = currentTimeStamp()
                        }
                    }
                val resolver = GlobalContext.get().get<TerminalCapabilityResolver>()
                val service = testTerminalService()

                // Prime the cache.
                val primed = runBlocking { resolver.byDeviceId("dev-revoke1") }
                assertTrue(primed != null, "expected a capability for dev-revoke1")

                // No manual resolver.invalidate() call here — only revokeBinding itself.
                runBlocking { service.revokeBinding(employeeId = "", terminalDbId = terminal.id.value) }

                // A stale cache would still return the primed (non-null) capability keyed by
                // "dev-revoke1"; a correctly invalidated cache misses and re-reads the DB, where no
                // terminal now carries that deviceId (clearBinding nulled it out), so this is null.
                val afterRevoke = runBlocking { resolver.byDeviceId("dev-revoke1") }
                assertNull(afterRevoke, "expected revokeBinding to invalidate the cache without a manual call")
            },
        ) { _ -> }

    // SECURITY BLOCKER regression test, end-to-end through the real service path: a login-less
    // station's ONLY cache entry lives in byTokenHash (it never authenticates via byDeviceId), so
    // revokeBinding must evict that entry too, not just byDevice.
    @Test
    fun `revokeBinding evicts a login-less station's token-hash cache end-to-end`() =
        testApp(
            setupData = {
                val service = testTerminalService()

                // Provision + pair a login-less (ORDER_STATION) terminal through the real service
                // path — pairDevice is the ONLY path that issues a device token, so it's the only way
                // to populate byTokenHash for a station that never presents a JWT.
                val created =
                    runBlocking {
                        service.createTerminal(
                            employeeId = "",
                            request = TerminalDto.CreateTerminalRequestDto(name = "Order Station", role = "ORDER_STATION"),
                        )
                    }
                val pairingCode = runBlocking { service.generatePairingCode(employeeId = "", terminalDbId = created.id) }
                val paired =
                    runBlocking {
                        service.pairDevice(
                            TerminalDto.PairDeviceRequestDto(code = pairingCode.code, deviceId = "dev-revoke-tokenonly1"),
                        )
                    }
                val tokenHash = DeviceTokenGenerator.hash(paired.deviceToken)

                val resolver = GlobalContext.get().get<TerminalCapabilityResolver>()

                // Prime byTokenHash ONLY — the login-less station's only cache path.
                val primed = runBlocking { resolver.byTokenHash(tokenHash) }
                assertTrue(primed != null, "expected a capability for the paired station's token hash")

                // No manual resolver.invalidate() call here — only revokeBinding itself.
                runBlocking { service.revokeBinding(employeeId = "", terminalDbId = created.id) }

                // A stale cache would still return the primed (non-null) capability keyed by
                // tokenHash; a correctly invalidated cache misses and re-reads the DB, where the
                // binding (and its deviceTokenHash) has been cleared, so this is null.
                val afterRevoke = runBlocking { resolver.byTokenHash(tokenHash) }
                assertNull(afterRevoke, "expected revokeBinding to evict the token-hash cache too, not just byDevice")
            },
        ) { _ -> }

    @Test
    fun `registerTerminalTse invalidates the terminal's cached capability`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                runBlocking {
                    ShopConfigurationManager.setTseConfig(
                        TseConfigDto.TseConfig(timeAdminPin = "12345", mode = TseMode.SIMULATION),
                    )
                }
                val terminal =
                    transaction {
                        Terminal.new {
                            this.deviceId = "dev-tse1"
                            this.clientId = "TER-CAP-TSE1"
                            this.role = "CASHIER"
                            this.fiscal = true
                            this.tseClientRegistered = false
                            this.isActive = true
                            this.registeredAt = currentTimeStamp()
                            this.lastSeenAt = currentTimeStamp()
                        }
                    }
                val resolver = GlobalContext.get().get<TerminalCapabilityResolver>()
                val service = testTerminalService(tseAdminClient = alwaysRegistersProvider())

                // Prime the cache with the pre-registration value.
                val primed = runBlocking { resolver.byDeviceId("dev-tse1") }
                assertTrue(primed != null && !primed.tseClientRegistered, "expected the pre-registration capability")

                // No manual resolver.invalidate() call here — only registerTerminalTse itself.
                runBlocking { service.registerTerminalTse(terminal.id.value) }

                val afterRegister = runBlocking { resolver.byDeviceId("dev-tse1") }
                assertTrue(
                    afterRegister!!.tseClientRegistered,
                    "expected registerTerminalTse to invalidate the cache without a manual call",
                )
            },
        ) { _ -> }

    @Test
    fun `syncTseRegistrationStatus invalidates the whole cache`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                runBlocking {
                    ShopConfigurationManager.setTseConfig(
                        TseConfigDto.TseConfig(timeAdminPin = "12345", mode = TseMode.SIMULATION),
                    )
                }
                transaction {
                    Terminal.new {
                        this.deviceId = "dev-sync1"
                        this.clientId = "TER-CAP-SYNC1"
                        this.role = "CASHIER"
                        this.fiscal = true
                        this.tseClientRegistered = true
                        this.isActive = true
                        this.registeredAt = currentTimeStamp()
                        this.lastSeenAt = currentTimeStamp()
                    }
                }
                val resolver = GlobalContext.get().get<TerminalCapabilityResolver>()
                // The fake TSE reports NO registered clients, so sync flips dev-sync1 to unregistered.
                val service = testTerminalService(tseAdminClient = reportsNoClientsProvider())

                val primed = runBlocking { resolver.byDeviceId("dev-sync1") }
                assertTrue(primed!!.tseClientRegistered, "expected the primed (pre-sync) value to be registered")

                // No manual resolver.invalidate()/invalidateAll() call here — only the service call.
                runBlocking { service.syncTseRegistrationStatus() }

                val afterSync = runBlocking { resolver.byDeviceId("dev-sync1") }
                assertFalse(
                    afterSync!!.tseClientRegistered,
                    "expected syncTseRegistrationStatus to invalidateAll() without a manual call",
                )
            },
        ) { _ -> }

    // F20: a terminal's role IS its capability, so moving it must drop the cached one. Without this,
    // RouteAuthorizationPlugin's ceiling kept enforcing the OLD role's limits — a terminal demoted for
    // a reason stayed exactly as privileged as it was until something unrelated evicted the cache.
    // updateTerminal is also the one mutation whose invalidate is CONDITIONAL, hence two tests.
    @Test
    fun `updateTerminal invalidates the cached capability when the role actually changes`() =
        testApp(
            setupData = {
                val adminId =
                    TestData
                        .createAdmin()
                        .id.value
                        .toString()
                val terminal =
                    transaction {
                        Terminal.new {
                            this.deviceId = "dev-role1"
                            this.clientId = "TER-CAP-ROLE1"
                            this.role = "CASHIER"
                            this.fiscal = true
                            this.tseClientRegistered = true
                            this.isActive = true
                            this.registeredAt = currentTimeStamp()
                            this.lastSeenAt = currentTimeStamp()
                        }
                    }
                val resolver = GlobalContext.get().get<TerminalCapabilityResolver>()
                val service = testTerminalService()

                // Prime the cache with the pre-change role.
                val primed = runBlocking { resolver.byDeviceId("dev-role1") }
                assertEquals("CASHIER", primed?.role, "expected the pre-change capability")

                // No manual resolver.invalidate() call here — only updateTerminal itself.
                runBlocking {
                    service.updateTerminal(
                        employeeId = adminId,
                        terminalId = terminal.id.value,
                        request = TerminalDto.UpdateTerminalRequestDto(role = "ORDER_STATION"),
                    )
                }

                val afterUpdate = runBlocking { resolver.byDeviceId("dev-role1") }
                assertEquals(
                    "ORDER_STATION",
                    afterUpdate?.role,
                    "expected updateTerminal to invalidate the cache without a manual call",
                )
            },
        ) { _ -> }

    @Test
    fun `updateTerminal leaves the cached capability alone when the role does not move`() =
        testApp(
            setupData = {
                val adminId =
                    TestData
                        .createAdmin()
                        .id.value
                        .toString()
                val terminal =
                    transaction {
                        Terminal.new {
                            this.deviceId = "dev-role2"
                            this.clientId = "TER-CAP-ROLE2"
                            this.role = "CASHIER"
                            this.fiscal = true
                            this.tseClientRegistered = true
                            this.isActive = true
                            this.registeredAt = currentTimeStamp()
                            this.lastSeenAt = currentTimeStamp()
                        }
                    }
                val resolver = GlobalContext.get().get<TerminalCapabilityResolver>()
                val service = testTerminalService()

                val primed = runBlocking { resolver.byDeviceId("dev-role2") }
                assertTrue(primed!!.tseClientRegistered, "expected the primed capability")

                // Flip a field out-of-band. The cache is the ONLY thing hiding this change, so if a
                // no-op edit wrongly evicted it, the assertion below would read the fresh value.
                transaction {
                    Terminal.find { TerminalTable.deviceId eq "dev-role2" }.first().tseClientRegistered = false
                }

                // Both shapes of no-op: a name-only edit, and re-saving the role it already carries.
                runBlocking {
                    service.updateTerminal(
                        employeeId = adminId,
                        terminalId = terminal.id.value,
                        request = TerminalDto.UpdateTerminalRequestDto(name = "Tresen"),
                    )
                    service.updateTerminal(
                        employeeId = adminId,
                        terminalId = terminal.id.value,
                        request = TerminalDto.UpdateTerminalRequestDto(name = "Tresen", role = "CASHIER"),
                    )
                }

                val afterNoOp = runBlocking { resolver.byDeviceId("dev-role2") }
                assertTrue(
                    afterNoOp!!.tseClientRegistered,
                    "a no-op edit must not evict the cache — the still-stale value is the proof nothing was invalidated",
                )
            },
        ) { _ -> }
}

/**
 * Builds a [TerminalServiceImpl] reusing the real (H2-backed) repositories, config manager, and
 * — crucially — the SAME [TerminalCapabilityResolver] singleton the test reads from, so a real
 * service call's invalidation is observable from the test's resolver reference. Only the TSE
 * provider is swapped for a deterministic fake — real TSE hardware/admin-session flows are out of
 * scope for this cache-invalidation test.
 */
private fun testTerminalService(tseAdminClient: TseAdminProvider = unusedTseAdminProvider()): TerminalServiceImpl {
    val koin = GlobalContext.get()
    return TerminalServiceImpl(
        repository = koin.get<TerminalRepository>(),
        pairingCodeRepository = koin.get<TerminalPairingCodeRepository>(),
        fiscalGateway = TseTerminalFiscalGateway(tseAdminClient),
        configManager = koin.get<IShopConfigurationManager>(),
        capabilityResolver = koin.get<TerminalCapabilityResolver>(),
        browserStationPolicy = BrowserStationPolicy(koin.get<IShopConfigurationManager>()),
    )
}

private fun alwaysRegistersProvider(): TseAdminProvider =
    object : TseAdminProvider by unusedTseAdminProvider() {
        override suspend fun registerClient(clientId: String): TseDto.ClientRegistrationResultDto =
            TseDto.ClientRegistrationResultDto(success = true, clientId = clientId)
    }

private fun reportsNoClientsProvider(): TseAdminProvider =
    object : TseAdminProvider by unusedTseAdminProvider() {
        override suspend fun listClients(): TseDto.ClientListResultDto = TseDto.ClientListResultDto(success = true, clients = emptyList(), count = 0)
    }

/** A [TseAdminProvider] whose every method throws — the base for `by` delegation above. */
private fun unusedTseAdminProvider(): TseAdminProvider =
    object : TseAdminProvider {
        private fun no(): Nothing = throw NotImplementedError("unused in this test")

        override suspend fun getStatus(): TseDto.StatusInfoDto = no()

        override suspend fun healthCheck(): TseDto.HealthCheckResultDto = no()

        override suspend fun ping(): Boolean = no()

        override suspend fun status(): TseDto.StatusInfoDto = no()

        override suspend fun getInfo(): TseDto.TseInfoDto = no()

        override suspend fun flashHealth(): TseDto.FlashHealthResultDto = no()

        override suspend fun setup(
            deviceId: String,
            credentials: TseDto.SetupCredentialsRequestDto,
        ): TseDto.AdminResultDto = no()

        override suspend fun factoryReset(): TseDto.AdminResultDto = no()

        override suspend fun selfTest(clientId: String?): TseDto.SelfTestResultDto = no()

        override suspend fun forceSelfTest(clientId: String?): TseDto.SelfTestResultDto = no()

        override suspend fun updateTime(): TseDto.TimeUpdateResultDto = no()

        override suspend fun registerClient(clientId: String): TseDto.ClientRegistrationResultDto = no()

        override suspend fun deregisterClient(clientId: String): TseDto.AdminResultDto = no()

        override suspend fun listClients(): TseDto.ClientListResultDto = no()

        override suspend fun listTransactions(clientId: String?): TseDto.TransactionListResultDto = no()

        override suspend fun lastTransaction(clientId: String?): TseDto.LastTransactionResultDto = no()

        override suspend fun exportLocal(outputPath: String): TseDto.LocalExportResultDto = no()

        override suspend fun exportFull(outputPath: String): TseDto.ExportResultDto = no()

        override suspend fun exportIncremental(
            outputPath: String,
            lastState: String?,
            maxExportSize: Long,
        ): TseDto.ExportResultDto = no()

        override suspend fun exportTimeFiltered(
            outputPath: String,
            startDate: Long,
            endDate: Long,
            clientId: String?,
        ): TseDto.ExportResultDto = no()

        override suspend fun exportTransactionFiltered(
            outputPath: String,
            startTransaction: Long,
            endTransaction: Long,
            clientId: String?,
        ): TseDto.ExportResultDto = no()

        override suspend fun getExportSize(lastState: String?): TseDto.ExportSizeDto = no()

        override suspend fun deleteExportedData(outputPath: String): TseDto.ExportAndDeleteResultDto = no()

        override suspend fun unblockUser(
            puk: String,
            userId: String?,
            newPin: String?,
        ): TseDto.AdminResultDto = no()

        override suspend fun changePin(
            currentPin: String,
            newPin: String,
            userId: String?,
        ): TseDto.AdminResultDto = no()

        override suspend fun enableCtss(): TseDto.AdminResultDto = no()

        override suspend fun disableCtss(): TseDto.AdminResultDto = no()

        override suspend fun checkFirmwareUpdate(): TseDto.FirmwareUpdateCheckResultDto = no()

        override suspend fun applyBundledFirmwareUpdate(): TseDto.FirmwareApplyResultDto = no()

        override suspend fun decommission(): TseDto.AdminResultDto = no()

        override suspend fun disableExportIfCspTestFails(): TseDto.AdminResultDto = no()

        override suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto): TseDto.LogEntryBrowseResultDto = no()

        override suspend fun exportLcm(): TseDto.LcmExportResultDto = no()

        override suspend fun restartServer(): TseDto.AdminResultDto = no()

        override suspend fun logoutAdmin(): TseDto.AdminResultDto = no()

        override suspend fun refreshConfig() = no()

        override suspend fun setMode(
            mode: String,
            clientId: String,
        ): Boolean = no()

        override suspend fun isInitialized(): Boolean = no()
    }
