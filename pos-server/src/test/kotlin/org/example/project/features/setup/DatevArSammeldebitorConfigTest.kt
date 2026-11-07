package org.example.project.features.setup

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `ConfigKey.DATEV_AR_SAMMELDEBITOR` holds the Sammeldebitor Personenkonto used by the AR
 * (Ausgangsrechnungen) DATEV export (slice 4d). It is a plain string setting — like
 * `CASH_BOOK_TRANSFER_TARGET` — not a field nested inside `DATEV_XML_CONFIG`, so the round trip
 * goes through `ShopConfigurationManager.getDatevArSammeldebitor()` directly against the stored
 * key, not through `getJsonObject(ConfigKey.DATEV_XML_CONFIG)`.
 */
@DisplayName("ShopConfigurationManager — DATEV_AR_SAMMELDEBITOR round-trips")
class DatevArSammeldebitorConfigTest {
    private val shopInfoRepository = ShopInfoRepositoryImpl()
    private val configRepository = ConfigurationRepository()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        // ShopConfigurationManager is a KoinComponent — it resolves ConfigurationRepository via
        // `by inject()`. TestDatabase.setup() stops Koin, so without this every read throws and is
        // swallowed, which would make this test green against a broken implementation.
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

    private fun createShop(): Long =
        transaction {
            shopInfoRepository
                .create(
                    ShopInfoDto.CreateShopInfoRequestDto(
                        shopName = "Testshop",
                        shopAddress = "Teststrasse 1",
                        shopCity = "Berlin",
                        zipCode = 10115,
                        shopCountry = "Germany",
                        shopPhone = "030-1",
                        shopTimezone = "Europe/Berlin",
                    ),
                ).id.value
        }

    @Test
    fun `an unconfigured Sammeldebitor reads back as null`() =
        runBlocking {
            createShop()

            assertNull(
                ShopConfigurationManager.getDatevArSammeldebitor(),
                "no Sammeldebitor was ever set, so the getter must not invent one",
            )
        }

    @Test
    fun `a configured Sammeldebitor round-trips through ShopConfigurationManager`() =
        runBlocking {
            val shopId = createShop()

            transaction {
                runBlocking {
                    configRepository.setSetting(
                        shopId = shopId,
                        configKey = ConfigKey.DATEV_AR_SAMMELDEBITOR,
                        value = "10000",
                    )
                }
            }
            clearShopConfigurationCache()

            assertEquals(
                "10000",
                ShopConfigurationManager.getDatevArSammeldebitor(),
                "the stored Personenkonto must be readable back unchanged",
            )
        }

    @Test
    fun `a blank stored Sammeldebitor reads back as null`() =
        runBlocking {
            val shopId = createShop()

            transaction {
                runBlocking {
                    configRepository.setSetting(
                        shopId = shopId,
                        configKey = ConfigKey.DATEV_AR_SAMMELDEBITOR,
                        value = "",
                    )
                }
            }
            clearShopConfigurationCache()

            assertNull(
                ShopConfigurationManager.getDatevArSammeldebitor(),
                "a blank stored value means unconfigured, matching consultantNumber/clientNumber semantics",
            )
        }
}
