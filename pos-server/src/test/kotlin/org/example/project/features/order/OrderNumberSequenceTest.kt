package org.example.project.features.order

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.features.order.data.repository.OrderRepositoryImpl
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The counter behind `order_number` (duplicate `ORD-20261008-7` under parallel `POST /api/orders`).
 * Sequential semantics only — H2 runs one connection here and never contends, so the concurrency
 * half lives in `OrderNumberConcurrencyPostgresTest`.
 */
@DisplayName("Order numbers come from a locked counter")
class OrderNumberSequenceTest {
    private val repo = OrderRepositoryImpl()

    private val today: String
        get() = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

    private var sessionId = 0L

    @BeforeEach
    fun setUp() {
        TestDatabase.setup()
        sessionId =
            TestData
                .createTableWithSession("SEQ1")
                .session.id.value
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    private fun issue(): String =
        runBlocking {
            dbQuery {
                val session = TableSession.findById(sessionId)!!
                repo.create(session.employeeId, session).orderNumber
            }
        }

    @Test
    fun `numbers keep the visible format and count up by one`() {
        val first = issue()
        assertTrue(Regex("ORD-$today-\\d+").matches(first), "visible format is unchanged: $first")
        val n = first.substringAfterLast('-').toLong()
        assertEquals(listOf("ORD-$today-${n + 1}", "ORD-$today-${n + 2}"), List(2) { issue() })
    }

    @Test
    fun `an install without a counter row continues past the highest number already issued`() {
        // One order numbered 41 under an older date. max(id) + 1 would answer 2 — a number that
        // may already exist; the counter must start past every issued number.
        runBlocking {
            dbQuery {
                val session = TableSession.findById(sessionId)!!
                Orders.new {
                    this.employee = session.employeeId
                    this.orderNumber = "ORD-20200101-41"
                    this.tableSession = session
                    this.orderTime = currentTimeStamp()
                    this.orderTotal = BigDecimal.ZERO
                    this.orderStatus = OrderStatus.DRAFT
                }
            }
        }

        assertEquals("ORD-$today-42", issue())
        assertEquals("ORD-$today-43", issue())
    }

    @Test
    fun `a rolled-back issuance hands its number to the next order`() {
        val first = issue()
        val n = first.substringAfterLast('-').toLong()
        assertFailsWith<IllegalStateException> {
            runBlocking {
                dbQuery {
                    val session = TableSession.findById(sessionId)!!
                    repo.create(session.employeeId, session)
                    error("order write failed after the number was drawn")
                }
            }
        }
        assertEquals("ORD-$today-${n + 1}", issue(), "the failed order's number is reissued")
        Unit
    }
}
