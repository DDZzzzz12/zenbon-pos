package org.example.project.features.setup

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.enums.setup.SetupSteps
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.payment.data.repository.PaymentTypeRepositoryImpl
import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.data.repository.VatRepositoryImpl
import org.example.project.features.setup.data.service.InitialSetupServiceImpl
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Slice 4c-3: the sample seed is the first place a tender is marked receivable. ÜBERWEISUNG means
 * "auf Rechnung" — settling with it opens a receivable rather than taking money — and by this slice
 * both ways to clear one (bank reconciliation, till clearing) already exist. Every other default
 * tender takes money and must stay non-receivable, or an ordinary card sale would be booked as an
 * open debt.
 */
@DisplayName("Sample seed — ÜBERWEISUNG is the only receivable default tender (4c-3)")
class SamplePaymentTypeReceivableTest {
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

    private val samplePaymentTypes =
        InitialSetupDto.InitialSetupRequestDto(
            setupStep = SetupSteps.PAYMENT_TYPES_SETUP,
            data = InitialSetupDto.PaymentTypesSetupRequestDto(useSamplePaymentTypes = true),
        )

    @Test
    fun `the seed marks only the bank transfer receivable`() =
        runBlocking {
            service.executeSetupStep(samplePaymentTypes)

            transaction {
                val transfer =
                    assertNotNull(
                        PaymentType.find { PaymentTypeTable.name eq "ÜBERWEISUNG" }.firstOrNull(),
                        "the sample seed must create ÜBERWEISUNG",
                    )
                assertTrue(transfer.createsReceivable, "a bank transfer is settled auf Rechnung")

                val takingTenders =
                    PaymentType
                        .find { PaymentTypeTable.name neq "ÜBERWEISUNG" }
                        .toList()
                assertTrue(takingTenders.isNotEmpty(), "the seed also creates cash and card tenders")
                assertTrue(
                    takingTenders.none { it.createsReceivable },
                    "every money-taking tender must stay non-receivable, got " +
                        takingTenders.filter { it.createsReceivable }.map { it.name },
                )
            }
        }
}
