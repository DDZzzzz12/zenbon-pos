package org.example.project.features.setup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TSE mode is owned by [ConfigKey.TSE_CONFIG] (as `TseConfigDto.TseConfig.mode`), NOT by
 * `shop_info`. The `shop_info.tse_mode` column existed only pre-Flyway — added in `186d11f82`,
 * deleted in `6a2b57d52` with a `TseMode.MOCK` default that no longer exists as an enum constant.
 * No Flyway migration ever created it.
 *
 * What outlived the column was `UpdateShopInfoRequestDto.tseMode` plus its `hasChanges()` term.
 * That combination made `PUT /api/shop-info/{id}` answer 200 to a TSE-mode-only body — the guard
 * saw a change, the repository had no `ShopInfo.tseMode` to assign, and the caller was told the
 * fiscal mode had been switched when nothing was written.
 *
 * These tests pin the removal, and pin that shop-info updates leave the real owner alone. Adding
 * the column back instead would give a safety-critical fiscal setting two competing sources of
 * truth, only one of which `TseServiceFactory` actually dispatches on.
 */
@DisplayName("ShopInfo — TSE mode is owned by TSE_CONFIG, not shop-info")
class ShopInfoTseModeOwnershipTest {
    private val repository = ShopInfoRepositoryImpl()
    private val configRepository = ConfigurationRepository()

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun createRequest() =
        ShopInfoDto.CreateShopInfoRequestDto(
            shopName = "Cafe",
            shopAddress = "Main 1",
            shopCity = "Berlin",
            zipCode = 10115,
            shopCountry = "Germany",
            shopPhone = "030-1",
            shopTimezone = "Europe/Berlin",
        )

    @Test
    fun everyUpdateFieldHasSomewhereToBeWritten() {
        // The general invariant the tseMode bug violated: a field is only meaningful on the update
        // DTO if `ShopInfoRepositoryImpl.update` has an entity property to assign it to. Anything
        // declared here but absent from the entity is silently dropped while still counting towards
        // hasChanges() — i.e. a phantom 200. Driven off the serial descriptor because that, not the
        // Kotlin class, is the wire contract clients actually send.
        val entityWriters =
            ShopInfo::class.java.methods
                .map { it.name }
                .toSet()

        val orphaned =
            ShopInfoDto.UpdateShopInfoRequestDto
                .serializer()
                .descriptor
                .elementNames
                .filterNot { "set${it.replaceFirstChar(Char::uppercaseChar)}" in entityWriters }

        assertTrue(
            orphaned.isEmpty(),
            "UpdateShopInfoRequestDto declares field(s) with no writable ShopInfo property: " +
                "$orphaned. Either wire them up in ShopInfoRepositoryImpl.update, or drop them " +
                "from the DTO and hasChanges() — do not leave them silently unwritten.",
        )
    }

    @Test
    fun tseModeIsNotPartOfTheShopInfoUpdateContract() {
        val fields =
            ShopInfoDto.UpdateShopInfoRequestDto
                .serializer()
                .descriptor.elementNames
        assertFalse(
            "tseMode" in fields,
            "TSE mode belongs to ConfigKey.TSE_CONFIG. Re-exposing it on shop-info creates a " +
                "second source of truth for a fiscal setting.",
        )
    }

    @Test
    fun aTseModeOnlyBodyIsNotAChange() {
        // The concrete regression. ContentNegotiation runs with ignoreUnknownKeys = true, so a
        // stale client may still send this shape; it must now fall into the "no changes" branch
        // rather than being reported as a successful TSE mode switch.
        val json = Json { ignoreUnknownKeys = true }

        val request =
            json.decodeFromString<ShopInfoDto.UpdateShopInfoRequestDto>(
                """{"tseMode":"NORMAL"}""",
            )

        assertFalse(
            request.hasChanges(),
            "a body that only carries tseMode changes nothing in shop_info and must not be " +
                "reported as a change",
        )
    }

    @Test
    fun shopInfoUpdateLeavesTheRealTseModeUntouched() =
        runBlocking {
            // Ownership, stated positively: TSE mode lives in the shop_configuration key-value store,
            // and writing shop-info neither reads nor disturbs it.
            val shop = transaction { repository.create(createRequest()) }
            val shopId = shop.id.value

            val storedConfig =
                TseConfigDto.TseConfig(
                    mode = TseMode.NORMAL,
                    timeAdminPin = "12345",
                )
            transaction {
                runBlocking {
                    configRepository.setSetting(
                        shopId = shopId,
                        configKey = ConfigKey.TSE_CONFIG,
                        value = Json.encodeToString(TseConfigDto.TseConfig.serializer(), storedConfig),
                    )
                }
            }

            transaction {
                repository.update(shopId, ShopInfoDto.UpdateShopInfoRequestDto(shopCity = "Hamburg"))
            }

            val modeAfter =
                transaction {
                    runBlocking { configRepository.getSetting(shopId, ConfigKey.TSE_CONFIG.key) }
                }?.jsonObject?.get("mode")?.jsonPrimitive?.content

            assertEquals(TseMode.NORMAL.name, modeAfter, "shop-info update must not touch TSE_CONFIG")
            assertEquals("Hamburg", transaction { repository.findById(shopId) }?.shopCity)
        }
}
