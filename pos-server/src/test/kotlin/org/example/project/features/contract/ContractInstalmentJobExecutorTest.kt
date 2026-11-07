package org.example.project.features.contract

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.features.contract.data.repository.ContractRepositoryImpl
import org.example.project.features.contract.data.service.InstalmentGenerator
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.scheduler.data.service.executors.ContractInstalmentJobExecutor
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
 * The executor is the only place a clock enters this feature — everything below it takes `today` as
 * an argument. What it owes the scheduler is a truthful count, including a zero on the re-run that
 * follows a restart.
 */
@DisplayName("Contract instalment job")
class ContractInstalmentJobExecutorTest {
    private val executor = ContractInstalmentJobExecutor(InstalmentGenerator(ContractRepositoryImpl()))

    @Test
    fun `it is the executor for the contract instalment job type`() {
        assertEquals(ScheduledJobType.CONTRACT_INSTALMENTS, executor.type)
    }

    @Test
    fun `it reports what it created, and reports zero on a second run`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction { newContract() }

            val first = runBlocking { executor.execute("{}") }
            val second = runBlocking { executor.execute("{}") }

            assertTrue(first.contains("2"), "the current period plus the next, reported: $first")
            assertTrue(second.contains("0"), "a re-run after a restart creates nothing: $second")
            transaction {
                assertEquals(2, ContractInstalment.all().count(), "and the row count is unchanged")
            }
        }
    }

    /** The executor opens its own transaction, so a bare call must not need one around it. */
    @Test
    fun `it opens its own transaction`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction { newContract() }

            runBlocking { executor.execute("{}") }

            transaction { assertTrue(ContractInstalment.all().count() > 0) }
        }
    }

    /**
     * The contract's start date is far in the past and it has no end, so whatever "today" the clock
     * reports, the rolling window is non-empty — the assertion above does not depend on the date the
     * suite happens to run on.
     */
    private fun newContract(): Contract =
        Contract.new {
            shop = ShopInfo.all().first()
            name = "Miete Ladenlokal"
            interval = ContractInterval.MONTHLY.name
            dueDay = 1
            startDate = LocalDateTime(2020, 1, 1, 0, 0)
            expectedAmount = BigDecimal("1250.0000")
        }
}
