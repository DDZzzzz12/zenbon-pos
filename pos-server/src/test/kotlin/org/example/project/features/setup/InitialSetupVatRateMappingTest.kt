package org.example.project.features.setup

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.enums.setup.SetupSteps
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.payment.data.repository.PaymentTypeRepositoryImpl
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.data.repository.VatRepositoryImpl
import org.example.project.features.setup.data.service.InitialSetupServiceImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-3 STAM-4 — primary rate-config path. The setup wizard's custom-rate step
 * ([InitialSetupServiceImpl.executeVatSetup]) creates rates DIRECTLY via the repository, bypassing
 * [org.example.project.features.setup.data.service.VatServiceImpl]. This test locks the same
 * mappability guard onto that path: an unmappable custom rate (10 %) is rejected with 422, while a
 * mapped custom rate (7.8 %) is accepted.
 */
@DisplayName("InitialSetup wizard — reject unmappable custom VAT rates (STAM-4)")
class InitialSetupVatRateMappingTest {
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
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun vatSetupRequest(vararg custom: InitialSetupDto.VatRateDto) =
        InitialSetupDto.InitialSetupRequestDto(
            setupStep = SetupSteps.VAT_SETUP,
            data =
                InitialSetupDto.VatSetupRequestDto(
                    createStandardGermanRates = false,
                    customRates = custom.toList(),
                ),
        )

    @Test
    fun `wizard rejects an unmapped 10 percent custom rate with 422`() {
        val ex =
            assertFailsWith<PosException> {
                runBlocking {
                    service.executeSetupStep(
                        vatSetupRequest(
                            InitialSetupDto.VatRateDto(vatClass = "X", vatRate = 0.10, description = "custom"),
                        ),
                    )
                }
            }
        assertEquals(HttpStatusCode.UnprocessableEntity, ex.httpStatus, "unmappable custom rate must be 422")
        assertTrue(
            ex.message?.contains("not a DSFinV-K-mappable rate") == true,
            "message must explain the rate is unmappable; got: ${ex.message}",
        )
        // No rate should have been persisted (the guard fires before create).
        assertEquals(0, transaction { Vat.all().count() }, "no VAT rate must be persisted on rejection")
    }

    @Test
    fun `wizard accepts a mapped 7,8 percent custom rate`() {
        runBlocking {
            service.executeSetupStep(
                vatSetupRequest(
                    InitialSetupDto.VatRateDto(vatClass = "AVG3", vatRate = 0.078, description = "avgthree"),
                ),
            )
        }
        assertEquals(1, transaction { Vat.all().count() }, "the mapped custom rate must be persisted")
    }

    @Test
    fun `wizard standard German rates path succeeds`() {
        runBlocking {
            service.executeSetupStep(
                InitialSetupDto.InitialSetupRequestDto(
                    setupStep = SetupSteps.VAT_SETUP,
                    data = InitialSetupDto.VatSetupRequestDto(createStandardGermanRates = true),
                ),
            )
        }
        // Standard German rates (19/7/0) are all mapped — normal setup is unaffected by the guard.
        assertTrue(transaction { Vat.all().count() } > 0, "standard German rates must be created")
    }
}
