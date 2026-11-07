package org.example.project.features.setup

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.payment.data.repository.PaymentTypeRepositoryImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.data.repository.VatRepositoryImpl
import org.example.project.features.setup.data.service.InitialSetupServiceImpl
import org.example.project.features.setup.mapper.toResponse
import org.example.project.features.zBon.domain.FiscalShopIdentityGuard
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
 * `ust_id_nr` is read on two fiscal paths — DSFinV-K `USTID` and the Z-Bon Stammdaten snapshot —
 * but until now no DTO carried it, so it was always null. These tests pin the write path.
 */
@DisplayName("ShopInfo — USt-IdNr is settable and round-trips")
class ShopInfoUstIdNrTest {
    private val repository = ShopInfoRepositoryImpl()

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun createRequest(ustIdNr: String?) =
        ShopInfoDto.CreateShopInfoRequestDto(
            shopName = "Cafe",
            shopAddress = "Main 1",
            shopCity = "Berlin",
            zipCode = 10115,
            shopCountry = "Germany",
            shopPhone = "030-1",
            shopTimezone = "Europe/Berlin",
            ustIdNr = ustIdNr,
        )

    @Test
    fun create_persistsUstIdNr() =
        runBlocking {
            val shop = transaction { repository.create(createRequest("DE123456789")) }
            val stored = transaction { repository.findById(shop.id.value) }
            assertEquals("DE123456789", stored?.ustIdNr)
        }

    @Test
    fun toResponse_carriesUstIdNr() =
        runBlocking {
            // Pins the ShopInfoMapper.toResponse() edit — without it, the DAO entity still holds
            // ustIdNr but the client-facing response DTO silently drops it.
            val shop = transaction { repository.create(createRequest("DE123456789")) }
            val response = transaction { repository.findById(shop.id.value)!!.toResponse() }
            assertEquals("DE123456789", response.ustIdNr)
        }

    @Test
    fun update_changesUstIdNr() =
        runBlocking {
            val shop = transaction { repository.create(createRequest("DE123456789")) }
            transaction {
                repository.update(shop.id.value, ShopInfoDto.UpdateShopInfoRequestDto(ustIdNr = "DE987654321"))
            }
            val stored = transaction { repository.findById(shop.id.value) }
            assertEquals("DE987654321", stored?.ustIdNr)
        }

    @Test
    fun update_withoutUstIdNr_preservesExistingValue() =
        runBlocking {
            // The settings screen sends ustIdNr = null on every save (ShopInfoFormState has no such
            // field), so null MUST mean "not changing" — otherwise each save blanks a fiscal field.
            val shop = transaction { repository.create(createRequest("DE123456789")) }
            transaction {
                repository.update(shop.id.value, ShopInfoDto.UpdateShopInfoRequestDto(shopCity = "Hamburg"))
            }
            val stored = transaction { repository.findById(shop.id.value) }
            assertEquals("DE123456789", stored?.ustIdNr)
            assertEquals("Hamburg", stored?.shopCity)
        }

    @Test
    fun completeSetup_persistsUstIdNr() =
        runBlocking {
            // Covers the InitialSetupServiceImpl pass-through, which the repository tests above skip.
            val service =
                InitialSetupServiceImpl(
                    EmployeeRepositoryImpl(),
                    EmployeeInfoRepositoryImpl(),
                    ShopInfoRepositoryImpl(),
                    ConfigurationRepository(),
                    PaymentTypeRepositoryImpl(),
                    VatRepositoryImpl(),
                    ShopConfigurationManager,
                )

            service.executeCompleteSetup(
                InitialSetupDto.CompleteSetupRequestDto(
                    owner =
                        InitialSetupDto.OwnerSetupRequestDto(
                            adminUsername = "owner",
                            adminPassword = "secret1",
                            adminEmail = "o@example.com",
                            adminFirstName = "O",
                            adminLastName = "Wner",
                            adminPin = "1234",
                        ),
                    shop =
                        InitialSetupDto.ShopSetupRequestDto(
                            shopName = "Cafe",
                            shopAddress = "Main 1",
                            shopCity = "Berlin",
                            zipCode = 10115,
                            shopPhone = "030-1",
                            ustIdNr = "DE123456789",
                        ),
                ),
            )

            val stored = transaction { repository.findAll().first() }
            assertEquals("DE123456789", stored.ustIdNr)
        }

    @Test
    fun changedUstIdNr_countsAsFiscalIdentityChange() =
        runBlocking {
            val shop = transaction { repository.create(createRequest("DE123456789")) }
            val service = FiscalShopIdentityGuard()

            val changed =
                transaction {
                    service.changesFiscalIdentity(
                        repository.findById(shop.id.value)!!,
                        ShopInfoDto.UpdateShopInfoRequestDto(ustIdNr = "DE987654321"),
                    )
                }

            assertTrue(changed, "changing the exported USt-IdNr must trigger the closing duty")
        }

    @Test
    fun unchangedUstIdNr_isNotAFiscalIdentityChange() =
        runBlocking {
            val shop = transaction { repository.create(createRequest("DE123456789")) }
            val service = FiscalShopIdentityGuard()

            val changed =
                transaction {
                    service.changesFiscalIdentity(
                        repository.findById(shop.id.value)!!,
                        ShopInfoDto.UpdateShopInfoRequestDto(ustIdNr = "DE123456789"),
                    )
                }

            assertFalse(changed, "resubmitting the current value changes nothing")
        }

    @Test
    fun firstTimeUstIdNr_countsAsFiscalIdentityChange() =
        runBlocking {
            // The realistic case: every existing installation has ust_id_nr = NULL, so the first edit
            // an operator makes is null -> a real value. That MUST trigger the closing duty, or the
            // new value is retroactively attributed to historical closings.
            val shop = transaction { repository.create(createRequest(null)) }
            val service = FiscalShopIdentityGuard()

            val changed =
                transaction {
                    service.changesFiscalIdentity(
                        repository.findById(shop.id.value)!!,
                        ShopInfoDto.UpdateShopInfoRequestDto(ustIdNr = "DE123456789"),
                    )
                }

            assertTrue(changed, "setting a VAT ID for the first time is a Stammdaten change")
        }
}
