package org.example.project.features.setup

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.enums.setup.SetupSteps
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.payment.data.repository.PaymentTypeRepositoryImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.data.repository.VatRepositoryImpl
import org.example.project.features.setup.data.service.InitialSetupServiceImpl
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

/**
 * The setup wizard asks for an ISO 4217 currency and used to discard it: [InitialSetupServiceImpl]
 * passed `currencyCode` into the shop-info create request, and [ShopInfoRepositoryImpl.create]
 * never assigned it — it sets 15 fields and `currencyCode` is not among them. There is no currency
 * column on the shop table, so a shop configured as CHF was EUR everywhere and
 * [ShopConfigurationManager.getDefaultCurrency] returned its `"EUR"` fallback forever.
 *
 * The wizard now writes the collected value to `ConfigKey.DEFAULT_CURRENCY`, which is what
 * `getDefaultCurrency()` actually reads, after `initializeDefaults` has seeded every key (which
 * would otherwise overwrite it with the seeded `{"currency": "EUR"}`).
 */
@DisplayName("InitialSetup — the chosen currency is persisted, not discarded")
class InitialSetupCurrencyTest {
    private val service =
        InitialSetupServiceImpl(
            EmployeeRepositoryImpl(),
            EmployeeInfoRepositoryImpl(),
            ShopInfoRepositoryImpl(),
            ConfigurationRepository(),
            PaymentTypeRepositoryImpl(),
            VatRepositoryImpl(),
            ShopConfigurationManager,
        )

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        // ShopConfigurationManager is a KoinComponent — it resolves ConfigurationRepository via
        // `by inject()`. TestDatabase.setup() stops Koin, so without this every read throws
        // "KoinApplication has not been started", is swallowed by its catch-all, and
        // getDefaultCurrency() returns its "EUR" fallback no matter what is in the database —
        // which would make this test green against a broken implementation.
        GlobalContext.getOrNull()?.let { GlobalContext.stopKoin() }
        startKoin { modules(serializationModule, reposModule) }
        clearShopConfigurationCache()
    }

    @AfterEach
    fun teardown() {
        // Process-global state: the manager caches for 30 minutes, so a config loaded from this
        // test's throwaway H2 database would otherwise stay visible to every later test class in
        // the same JVM.
        clearShopConfigurationCache()
        TestDatabase.teardown()
    }

    /** Nulls the manager's private 30-minute cache; it exposes no public invalidation. */
    @Suppress("UNCHECKED_CAST")
    private fun clearShopConfigurationCache() {
        val field = ShopConfigurationManager::class.java.getDeclaredField("cachedConfig")
        field.isAccessible = true
        (field.get(ShopConfigurationManager) as AtomicReference<Any?>).set(null)
    }

    private fun shopSetupRequest(currency: String) =
        InitialSetupDto.InitialSetupRequestDto(
            setupStep = SetupSteps.SHOP_SETUP,
            data =
                InitialSetupDto.ShopSetupRequestDto(
                    shopName = "Testshop",
                    shopAddress = "Teststrasse 1",
                    shopCity = "Berlin",
                    zipCode = 10115,
                    shopPhone = "+49 30 1234567",
                    currencyCode = currency,
                ),
        )

    @Test
    fun `the chosen currency is readable back from shop config`() =
        runBlocking {
            service.executeSetupStep(shopSetupRequest("CHF"))

            assertEquals(
                "CHF",
                ShopConfigurationManager.getDefaultCurrency(),
                "the wizard's currency must reach ConfigKey.DEFAULT_CURRENCY",
            )
        }

    @Test
    fun `a euro shop still reads back as EUR`() =
        runBlocking {
            service.executeSetupStep(shopSetupRequest("EUR"))

            assertEquals(
                "EUR",
                ShopConfigurationManager.getDefaultCurrency(),
                "the ordinary euro case must be unaffected",
            )
        }
}
