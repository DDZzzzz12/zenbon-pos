package org.example.project.features.setup

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.features.setup.data.repository.VatRepositoryImpl
import org.example.project.features.setup.data.service.VatRateGuard
import org.example.project.features.setup.data.service.VatServiceImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
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
 * Round-3 STAM-4: VAT rates are free-form master data with no rate whitelist. A sale at a rate
 * outside the DSFinV-K-mapped set (e.g. 10 %) exports rows under UST_SCHLUESSEL 7 (NICHT_ERMITTELBAR)
 * carrying a NON-ZERO computed USt, while vat.csv declares key 7 = 0,00 % — an arithmetic
 * contradiction. The guard rejects an unmappable rate at VAT create/update; a mapped rate succeeds.
 */
@DisplayName("VatService — reject VAT rates outside the DSFinV-K mapping (STAM-4)")
class VatServiceRateMappingTest {
    private val service = VatServiceImpl(VatRepositoryImpl(), VatRateGuard)
    private lateinit var adminId: String

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        adminId =
            transaction {
                TestData
                    .createAdmin()
                    .id.value
                    .toString()
            }
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `createVatRate rejects an unmapped 10 percent rate with 422`() {
        val ex =
            assertFailsWith<PosException> {
                runBlocking {
                    service.createVatRate(
                        adminId,
                        VatDto.CreateVatRequestDto(vatId = "V_UNMAPPED", vatClass = "SPECIAL", vatRate = 0.10),
                    )
                }
            }
        assertEquals(HttpStatusCode.UnprocessableEntity, ex.httpStatus, "unmappable rate must be 422")
        assertTrue(
            ex.message?.contains("not a DSFinV-K-mappable rate") == true,
            "message must explain the rate is unmappable; got: ${ex.message}",
        )
        assertTrue(
            ex.message?.contains("19%") == true && ex.message?.contains("7.8%") == true,
            "message must list the allowed rates; got: ${ex.message}",
        )
    }

    @Test
    fun `createVatRate accepts the mapped 7,8 percent rate`() {
        val created =
            runBlocking {
                service.createVatRate(
                    adminId,
                    VatDto.CreateVatRequestDto(vatId = "V_AVG3", vatClass = "AVGTHREE", vatRate = 0.078),
                )
            }
        assertEquals("AVGTHREE", created.vatClass)
    }

    @Test
    fun `createVatRate accepts a standard 19 percent rate`() {
        val created =
            runBlocking {
                service.createVatRate(
                    adminId,
                    VatDto.CreateVatRequestDto(vatId = "V_STD", vatClass = "STANDARD", vatRate = 0.19),
                )
            }
        assertEquals("STANDARD", created.vatClass)
    }

    @Test
    fun `updateVatRate rejects an unmapped 10 percent rate with 422`() {
        // Seed a valid rate first, then try to update it to an unmappable one.
        runBlocking {
            service.createVatRate(
                adminId,
                VatDto.CreateVatRequestDto(vatId = "V_UPD", vatClass = "UPDTARGET", vatRate = 0.19),
            )
        }

        val ex =
            assertFailsWith<PosException> {
                runBlocking {
                    service.updateVatRate(
                        adminId,
                        "V_UPD",
                        VatDto.UpdateVatRequestDto(vatRate = 0.10),
                    )
                }
            }
        assertEquals(HttpStatusCode.UnprocessableEntity, ex.httpStatus, "unmappable rate must be 422")
        assertTrue(
            ex.message?.contains("not a DSFinV-K-mappable rate") == true,
            "message must explain the rate is unmappable; got: ${ex.message}",
        )
    }

    @Test
    fun `updateVatRate accepts a mapped 7 percent rate`() {
        runBlocking {
            service.createVatRate(
                adminId,
                VatDto.CreateVatRequestDto(vatId = "V_UPD2", vatClass = "UPDTARGETTWO", vatRate = 0.19),
            )
        }

        val updated =
            runBlocking {
                service.updateVatRate(
                    adminId,
                    "V_UPD2",
                    VatDto.UpdateVatRequestDto(vatRate = 0.07),
                )
            }
        assertEquals(0.07, updated.vatRate)
    }

    @Test
    fun `test_vat_rate_not_mappable_is_rejected_via_admission`() {
        val seen = mutableListOf<Double>()
        val rejecting =
            VatServiceImpl(VatRepositoryImpl()) { rate ->
                seen += rate
                VatRateGuard.requireMappable(rate)
            }
        val ex =
            assertFailsWith<PosException> {
                runBlocking {
                    rejecting.createVatRate(
                        adminId,
                        VatDto.CreateVatRequestDto(vatId = "V_VIA_PORT", vatClass = "SPECIAL", vatRate = 0.10),
                    )
                }
            }
        assertEquals(HttpStatusCode.UnprocessableEntity, ex.httpStatus)
        assertEquals(listOf(0.10), seen, "the service must ask the admission port, exactly once")
    }
}
