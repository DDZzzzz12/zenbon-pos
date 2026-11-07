package org.example.project.infrastructure.di

import org.example.project.database.LiveDataSourceProvider
import org.example.project.features.admin.data.service.PermissionDiagnosticsServiceImpl
import org.example.project.features.auth.domain.service.ApprovalDetailsResolver
import org.example.project.features.auth.domain.service.ApprovalRequestService
import org.example.project.features.backup.domain.service.BackupRestoreService
import org.example.project.features.backup.domain.service.PostRestoreHook
import org.example.project.features.employee.domain.service.EmployeeService
import org.example.project.features.employee.domain.service.StaffRosterListener
import org.example.project.features.maintenance.data.service.LogSearchService
import org.example.project.features.payment.domain.service.VatRateAdmission
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.service.ShopInfoServiceImpl
import org.example.project.features.setup.data.service.VatServiceImpl
import org.example.project.features.setup.domain.repository.ConfigurationContributor
import org.example.project.features.setup.domain.service.ShopIdentityChangeGuard
import org.example.project.features.terminal.data.service.TerminalServiceImpl
import org.example.project.features.terminal.domain.service.TerminalFiscalGateway
import org.example.project.features.terminal.domain.service.TerminalService
import org.example.project.features.tse.data.service.TseTerminalFiscalGateway
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.zBon.domain.FiscalShopIdentityGuard
import org.example.project.infrastructure.security.RouteRuleCatalog
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.injectedList
import org.example.project.testutils.injectedValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The ports platform code reads host wiring through (`RouteRuleCatalog`, `LiveDataSourceProvider`)
 * must be bound by the production modules: a missing binding fails here, not at startup.
 *
 * Resolution alone is not enough (spec §15 C(c)): `ServicesModule`/`ReposModule` could construct a
 * consumer with `emptyList()` — `ConfigurationRepository`'s contributors even default to it — and every
 * `get` here would still succeed. So each consumer's private constructor property is read back by
 * reflection and compared with what Koin holds for the port: the very instances, all of them, and at
 * least one.
 */
@DisplayName("Platform ports are bound by the production Koin modules")
class PlatformPortsBindingTest {
    private lateinit var koin: Koin

    @BeforeEach
    fun start() {
        TestDatabase.setup()
        koin = koinApplication(createEagerInstances = false) { modules(serializationModule, httpClientModule, reposModule, servicesModule) }.koin
    }

    @AfterEach
    fun stop() {
        koin.close()
        TestDatabase.teardown()
    }

    @Test
    fun `route rule catalog resolves and answers from the permission table`() {
        val catalog = koin.get<RouteRuleCatalog>()
        assertTrue(catalog.allRules().isNotEmpty())
        val (path, method, _) = catalog.registeredRules().first()
        assertNotNull(catalog.findRule(path, method))
    }

    @Test
    fun `live data source provider resolves`() {
        assertNotNull(koin.get<LiveDataSourceProvider>())
    }

    @Test
    fun `consumers of the ports resolve`() {
        assertNotNull(koin.get<PermissionDiagnosticsServiceImpl>())
        assertNotNull(koin.get<LogSearchService>())
        assertNotNull(koin.get<BackupRestoreService>())
    }

    /** [field] of [consumer] holds exactly Koin's bindings of the port: the same instances, non-empty. */
    private fun assertInjectsAll(
        consumer: Any,
        field: String,
        bound: List<Any>,
    ) {
        val injected = injectedList(consumer, field)
        assertTrue(bound.isNotEmpty(), "no binding of the port behind ${consumer.javaClass.simpleName}.$field")
        assertTrue(injected.isNotEmpty(), "${consumer.javaClass.simpleName}.$field was injected empty")
        assertEquals(bound.size, injected.size, "${consumer.javaClass.simpleName}.$field: $injected vs Koin's $bound")
        bound.zip(injected).forEach { (expected, actual) -> assertSame(expected, actual) }
    }

    @Test
    fun `employee service is injected with every roster listener`() {
        assertInjectsAll(koin.get<EmployeeService>(), "listeners", koin.getAll<StaffRosterListener>())
    }

    @Test
    fun `approval request service is injected with every details resolver`() {
        assertInjectsAll(koin.get<ApprovalRequestService>(), "resolvers", koin.getAll<ApprovalDetailsResolver>())
    }

    @Test
    fun `restore service is injected with every post restore hook`() {
        assertInjectsAll(koin.get<BackupRestoreService>(), "postRestoreHooks", koin.getAll<PostRestoreHook>())
    }

    @Test
    fun `configuration repository is injected with every contributor`() {
        assertInjectsAll(koin.get<ConfigurationRepository>(), "contributors", koin.getAll<ConfigurationContributor>())
    }

    @Test
    fun `shop info service is injected with the bound identity guard`() {
        val guard = injectedValue(koin.get<ShopInfoServiceImpl>(), "identityGuard")
        assertNotNull(guard)
        assertSame(koin.get<ShopIdentityChangeGuard>(), guard)
    }

    @Test
    fun `roster listeners and approval resolvers are bound and injected`() {
        assertTrue(koin.getAll<StaffRosterListener>().isNotEmpty())
        assertTrue(koin.getAll<ApprovalDetailsResolver>().isNotEmpty())
        assertNotNull(koin.get<EmployeeService>())
        assertNotNull(koin.get<ApprovalRequestService>())
    }

    @Test
    fun `post restore hooks are bound and injected into the restore service`() {
        assertTrue(koin.getAll<PostRestoreHook>().isNotEmpty())
        assertNotNull(koin.get<BackupRestoreService>())
    }

    @Test
    fun `vat admission and configuration contributors are bound and injected`() {
        assertNotNull(koin.get<VatRateAdmission>())
        assertNotNull(koin.get<VatServiceImpl>())
        assertTrue(koin.getAll<ConfigurationContributor>().isNotEmpty())
        assertNotNull(koin.get<ConfigurationRepository>())
    }

    @Test
    fun `shop identity change guard is bound to the fiscal implementation and injected`() {
        assertTrue(koin.get<ShopIdentityChangeGuard>() is FiscalShopIdentityGuard)
        assertNotNull(koin.get<ShopInfoServiceImpl>())
    }

    /**
     * The production `TseAdminProvider` factory reads the global Koin context, which this isolated
     * application does not start; it is replaced by an inert stand-in so the test checks the
     * gateway's own binding and its injection into the terminal service, not the device wiring.
     */
    @Test
    fun `terminal fiscal gateway is bound to the TSE implementation and injected`() {
        val inertDevice =
            Proxy.newProxyInstance(TseAdminProvider::class.java.classLoader, arrayOf(TseAdminProvider::class.java)) { _, method, _ ->
                when (method.name) {
                    "toString" -> "InertTseAdminProvider"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> throw UnsupportedOperationException("TseAdminProvider.${method.name} is not used here")
                }
            } as TseAdminProvider
        val withDevice =
            koinApplication(createEagerInstances = false) {
                allowOverride(true)
                modules(serializationModule, httpClientModule, reposModule, servicesModule, module { single<TseAdminProvider> { inertDevice } })
            }.koin
        try {
            assertTrue(withDevice.get<TerminalFiscalGateway>() is TseTerminalFiscalGateway)
            assertNotNull(withDevice.get<TerminalServiceImpl>())
            assertTrue(withDevice.get<TerminalService>() is TerminalServiceImpl)
            val injected = injectedValue(withDevice.get<TerminalServiceImpl>(), "fiscalGateway")
            assertNotNull(injected)
            assertSame(withDevice.get<TerminalFiscalGateway>(), injected)
        } finally {
            withDevice.close()
        }
    }
}
