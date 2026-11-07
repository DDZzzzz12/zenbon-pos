package org.example.project.features.bill

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toJavaLocalDate
import org.example.project.features.bill.data.repository.BillRepositoryImpl
import org.example.project.features.bill.domain.models.Bill
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The month counter behind `bill_number` (real-TSE QA rt12, finding d). Sequential semantics only —
 * H2 runs one connection here and never contends, so the concurrency half lives in
 * `BillNumberConcurrencyPostgresTest`.
 */
@DisplayName("Bill numbers come from a gapless per-month counter")
class BillNumberSequenceTest {
    private val repo = BillRepositoryImpl()

    private val today: String
        get() = currentTimeStamp().date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("yyMMdd"))

    @BeforeEach
    fun setUp() = TestDatabase.setup()

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    private fun issue(): String = runBlocking { dbQuery { repo.generatedBillNumber() } }

    @Test
    fun `a month without bills starts at 0 and counts up by one`() {
        assertEquals(listOf("RE-$today-0", "RE-$today-1", "RE-$today-2"), List(3) { issue() })
    }

    @Test
    fun `an upgraded month continues past the highest number already issued, not past the count`() {
        // One bill in the month, but numbered 41: the old count(*) would have answered 1 — a
        // number that may already exist. The counter must start past every issued number.
        val session = TestData.createTableWithSession("SEQ1").session
        val sameMonthOtherDay = "RE-${today.substring(0, 4)}01-41"
        transaction {
            Bill.new {
                this.employeeId = session.employeeId
                this.tableSession = session
                this.billAmount = BigDecimal.ONE
                this.paidAmount = BigDecimal.ONE
                this.tips = BigDecimal.ZERO
                this.startTime = session.startTime
                this.endTime = currentTimeStamp()
                this.billNumber = sameMonthOtherDay
            }
        }

        assertEquals("RE-$today-42", issue())
        assertEquals("RE-$today-43", issue())
    }

    @Test
    fun `a rolled-back issuance hands its number to the next bill, leaving no gap`() {
        assertEquals("RE-$today-0", issue())
        assertFailsWith<IllegalStateException> {
            runBlocking {
                dbQuery {
                    repo.generatedBillNumber()
                    error("bill write failed after the number was drawn")
                }
            }
        }
        assertEquals("RE-$today-1", issue(), "the failed bill's number is reissued")
        Unit
    }
}
