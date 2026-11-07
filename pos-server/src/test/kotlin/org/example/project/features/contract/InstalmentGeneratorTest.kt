package org.example.project.features.contract

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.example.project.features.contract.data.repository.ContractRepositoryImpl
import org.example.project.features.contract.data.service.InstalmentGenerator
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.contract.domain.models.ContractInstalmentStatus
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generator persists what [org.example.project.features.contract.data.service.InstalmentSchedule]
 * returns, and nothing else. What is asserted here is therefore persistence behaviour rather than
 * arithmetic: that the rolling window lands, that a **second run creates nothing** (the job re-runs
 * after a restart), and that dormant contracts stay silent.
 *
 * Every assertion reads in its **own** transaction: within one, Exposed's identity map can serve
 * back the instance just written rather than what the column actually stored.
 */
@DisplayName("Instalment generator")
class InstalmentGeneratorTest {
    private val generator = InstalmentGenerator(ContractRepositoryImpl())

    /** March 10th — mid-month, so a window running to the end of April is not an artefact of the day. */
    private val today = LocalDate(2026, 3, 10)

    @Test
    fun `it materialises the current period and the next, as OPEN, at the contract's amount`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val contractId = transaction { newContract().id.value }

            val created = transaction { generator.generate(today) }

            assertEquals(2, created, "March and April fall inside the window; May does not")
            transaction {
                val instalments = ContractInstalment.all().sortedBy { it.dueDate }
                assertEquals(
                    listOf(LocalDateTime(2026, 3, 1, 0, 0), LocalDateTime(2026, 4, 1, 0, 0)),
                    instalments.map { it.dueDate },
                )
                assertTrue(instalments.all { it.contract.id.value == contractId })
                assertTrue(
                    instalments.all { it.status == ContractInstalmentStatus.OPEN.name },
                    "OVERDUE is derived at read time, so the generator writes only OPEN",
                )
                assertTrue(instalments.all { BigDecimal("1250.0000").compareTo(it.expectedAmount!!) == 0 })
            }
        }
    }

    /**
     * The idempotency that matters: the job may run twice after a restart, and a duplicated
     * instalment would double the expected outgoings. The unique index on `(contract_id, due_date)`
     * is the real guarantee; the repository's check is what turns the duplicate into a quiet no-op.
     */
    @Test
    fun `running it twice creates nothing the second time`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction { newContract() }
            transaction { generator.generate(today) }

            val createdAgain = transaction { generator.generate(today) }

            assertEquals(0, createdAgain, "a second run reports no new instalments")
            transaction {
                assertEquals(2, ContractInstalment.all().count(), "and leaves the row count untouched")
            }
        }
    }

    @Test
    fun `an inactive contract generates nothing`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction { newContract { isActive = false } }

            val created = transaction { generator.generate(today) }

            assertEquals(0, created)
            transaction { assertEquals(0, ContractInstalment.all().count()) }
        }
    }

    @Test
    fun `a contract whose end date has passed generates nothing`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction { newContract { endDate = LocalDateTime(2026, 1, 31, 0, 0) } }

            val created = transaction { generator.generate(today) }

            assertEquals(0, created, "the contract ended before the window opened")
            transaction { assertEquals(0, ContractInstalment.all().count()) }
        }
    }

    /** The same due date under a *different* contract is not a collision. */
    @Test
    fun `two contracts generate independently`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val rentId = transaction { newContract().id.value }
            val stromId =
                transaction {
                    newContract(name = "Strom") {
                        interval = ContractInterval.QUARTERLY.name
                        startDate = LocalDateTime(2026, 2, 1, 0, 0)
                    }.id.value
                }

            val created = transaction { generator.generate(today) }

            assertEquals(3, created, "two monthly instalments plus the quarterly May one")
            transaction {
                assertEquals(
                    listOf(LocalDateTime(2026, 3, 1, 0, 0), LocalDateTime(2026, 4, 1, 0, 0)),
                    instalmentDueDatesOf(rentId),
                )
                assertEquals(
                    listOf(LocalDateTime(2026, 5, 1, 0, 0)),
                    instalmentDueDatesOf(stromId),
                    "a quarterly contract starting in February falls due in May, and its window reaches there",
                )
            }
        }
    }

    private fun instalmentDueDatesOf(contractId: Long) =
        ContractInstalment
            .all()
            .filter { it.contract.id.value == contractId }
            .map { it.dueDate }
            .sorted()

    private fun newContract(
        name: String = "Miete Ladenlokal",
        configure: Contract.() -> Unit = {},
    ): Contract =
        Contract.new {
            shop = ShopInfo.all().first()
            this.name = name
            interval = ContractInterval.MONTHLY.name
            dueDay = 1
            startDate = LocalDateTime(2026, 1, 1, 0, 0)
            expectedAmount = BigDecimal("1250.0000")
            configure()
        }
}
