package org.example.project.features.allocation

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.data.repository.AllocationQueueRepositoryImpl
import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationServiceImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.contract.data.repository.ContractRepositoryImpl
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.finance.data.service.BookkeepingStartServiceImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The target slice 3b refused on purpose. What matters is that an instalment's residual is
 * maintained by the same machinery as an invoice's — recomputed from the surviving rows, never
 * adjusted by a delta — so `ResidualInvariantTest`'s guarantee extends to it rather than being
 * duplicated beside it.
 *
 * Every reload reads in its own transaction: within one, Exposed's identity map can serve back the
 * instance just written rather than what the column actually stored.
 */
@DisplayName("Instalment allocation")
class InstalmentAllocationTest {
    private val service =
        AllocationServiceImpl(
            queueRepository = AllocationQueueRepositoryImpl(),
            writer = AllocationWriter(AllocationRepositoryImpl()),
            allocationRepository = AllocationRepositoryImpl(),
            contractRepository = ContractRepositoryImpl(),
            bookkeepingStart = BookkeepingStartServiceImpl(ConfigurationRepository()),
            configManager = ShopConfigurationManager,
        )

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    private fun request(
        transactionId: Long,
        instalmentId: Long?,
        amount: String,
    ) = AllocationDto.CreateRequestDto(
        sourceKind = "BANK_TRANSACTION",
        targetKind = "CONTRACT_INSTALMENT",
        amount = amount,
        bankTransactionId = transactionId,
        contractInstalmentId = instalmentId,
    )

    @Test
    fun `assigning part of a payment to an instalment leaves the rest open`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, instalment) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        ContractFixtures.newInstalment(expected = BigDecimal("100.0000")).id.value
                }

            val response = runBlocking { service.create(request(tx, instalment, "60.00"), "tester") }

            assertEquals(0, BigDecimal("40.0000").compareTo(BigDecimal(response.sourceOpenAmount!!)))
            assertEquals(0, BigDecimal("40.0000").compareTo(BigDecimal(response.targetOpenAmount!!)))
        }
    }

    @Test
    fun `assigning the full amount settles the instalment`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, instalment) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        ContractFixtures.newInstalment(expected = BigDecimal("100.0000")).id.value
                }

            val response = runBlocking { service.create(request(tx, instalment, "100.00"), "tester") }

            assertEquals(0, BigDecimal.ZERO.compareTo(BigDecimal(response.targetOpenAmount!!)))
        }
    }

    @Test
    fun `reversing restores the instalment's residual`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, instalment) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        ContractFixtures.newInstalment(expected = BigDecimal("100.0000")).id.value
                }
            val created = runBlocking { service.create(request(tx, instalment, "60.00"), "tester") }

            runBlocking { service.reverse(created.allocation.id, null) }

            transaction {
                val reloaded = ContractInstalment.findById(instalment)!!
                assertEquals(0, BigDecimal("100.0000").compareTo(reloaded.openAmount!!))
            }
        }
    }

    /**
     * An instalment is an expectation, not a debt: a variable Strom bill genuinely comes in above
     * what was expected, so the service does not guard the target side. The residual goes negative
     * rather than clamping, which is what keeps the overshoot visible.
     */
    @Test
    fun `paying more than expected is allowed and shows as a negative residual`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, instalment) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("200.0000")).id.value to
                        ContractFixtures.newInstalment(expected = BigDecimal("100.0000")).id.value
                }

            val response = runBlocking { service.create(request(tx, instalment, "150.00"), "tester") }

            assertEquals(0, BigDecimal("-50.0000").compareTo(BigDecimal(response.targetOpenAmount!!)))
        }
    }

    /**
     * With nothing expected there is no residual to state. Substituting zero would render the
     * instalment settled the moment a single cent was assigned to it.
     */
    @Test
    fun `an instalment with no expected amount keeps a null residual`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, instalment) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        ContractFixtures.newInstalment(expected = null).id.value
                }

            val response = runBlocking { service.create(request(tx, instalment, "60.00"), "tester") }

            assertNull(response.targetOpenAmount)
            transaction { assertNull(ContractInstalment.findById(instalment)!!.openAmount) }
        }
    }

    @Test
    fun `an unknown instalment id is a 404`() {
        testApp(setupData = { setup() }) { _ ->
            val tx =
                transaction { AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value }

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service.create(request(tx, 999_999, "10.00"), "tester") }
                }
            assertEquals(PosErrorCode.RESOURCE_NOT_FOUND, error.code)
        }
    }

    @Test
    fun `CONTRACT_INSTALMENT without an id is a 400`() {
        testApp(setupData = { setup() }) { _ ->
            val tx =
                transaction { AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value }

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service.create(request(tx, instalmentId = null, amount = "10.00"), "tester") }
                }
            assertEquals(PosErrorCode.INVALID_REQUEST, error.code)
        }
    }
}
