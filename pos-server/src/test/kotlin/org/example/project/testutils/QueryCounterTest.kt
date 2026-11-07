package org.example.project.testutils

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.TableSession
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the harness the rest of the performance plan asserts on actually observes what Exposed
 * executes. Without this, an N+1 regression test could pass because the counter silently saw
 * nothing.
 */
@DisplayName("QueryCounter observes the SQL Exposed executes")
class QueryCounterTest {
    @Test
    fun `captures one statement per executed query and nothing outside the recorded block`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin(employeeNumber = "QC_ADM", username = "qc_adm")
                val vats = TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession("QC1")
                val category = TestData.createCategory("QC Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "QC Product",
                        productNumber = "QC_P",
                        price = BigDecimal("5.0000"),
                    )
                val sessionId = tws.session.id.value

                transaction {
                    val session = TableSession.findById(sessionId)!!
                    session.employeeId = admin
                    OrderItem.new {
                        this.session = session
                        this.product = product
                        this.unitPrice = BigDecimal("5.00")
                        this.finalPrice = BigDecimal("5.00")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vats.first()
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }
                }

                val (_, oneFind) =
                    QueryCounter.record {
                        runBlocking {
                            dbQuery { OrderItem.find { OrderItemTable.sessionId eq sessionId }.toList() }
                        }
                    }
                assertEquals(
                    1,
                    oneFind.count { it.contains("t_order_item", ignoreCase = true) },
                    "one OrderItem.find must be observed as exactly one SELECT over t_order_item; got $oneFind",
                )

                val (_, twoFinds) =
                    QueryCounter.record {
                        runBlocking {
                            dbQuery {
                                OrderItem.find { OrderItemTable.sessionId eq sessionId }.toList()
                                OrderItem.find { OrderItemTable.itemStatus eq ItemStatus.OPEN }.toList()
                            }
                        }
                    }
                assertEquals(
                    2,
                    twoFinds.count { it.contains("t_order_item", ignoreCase = true) },
                    "two distinct finds must be observed as two SELECTs over t_order_item; got $twoFinds",
                )

                assertTrue(
                    QueryCounter.record { }.second.isEmpty(),
                    "recording an empty block must capture nothing",
                )

                // Once record() returns, the interceptor must be off Exposed's global list again —
                // otherwise it would leak across the 5 test classes that share one forked JVM.
                // This asserts the registration state DIRECTLY, and deliberately so: capture is gated
                // on the recording flag and record() clears its buffer on entry, so a leaked
                // registration is invisible to any check that only looks at what was captured.
                assertFalse(
                    JdbcTransaction.globalInterceptors.contains(QueryCounter),
                    "record() must unregister the interceptor when the block ends",
                )

                // A rejected nested record() must not disturb the outer recording. If the entry-clear
                // ran before the re-entrancy guard, the inner call would wipe the outer capture on its
                // way to throwing, and an outer exact-count assertion could then pass on truncated
                // data — the silent-failure mode this harness exists to prevent.
                val (_, outerSurvived) =
                    QueryCounter.record {
                        runBlocking {
                            dbQuery { OrderItem.find { OrderItemTable.sessionId eq sessionId }.toList() }
                        }
                        assertFailsWith<IllegalStateException> { QueryCounter.record { } }
                    }
                assertEquals(
                    1,
                    outerSurvived.count { it.contains("t_order_item", ignoreCase = true) },
                    "a rejected nested record() must leave the outer capture intact; got $outerSurvived",
                )

                // A throwing block must still release the flag and the registration. Otherwise the
                // first perf test whose service call throws strands QueryCounter registered with
                // recording == true, and every later record() in the forked JVM dies on "not
                // re-entrant". Asserting the message distinguishes the block's own failure from the
                // re-entrancy guard's — both are IllegalStateException.
                val thrown = assertFailsWith<IllegalStateException> { QueryCounter.record { error("boom") } }
                assertEquals(
                    "boom",
                    thrown.message,
                    "record() must propagate the block's exception, not the re-entrancy guard's",
                )
                assertFalse(
                    JdbcTransaction.globalInterceptors.contains(QueryCounter),
                    "record() must unregister even when the block throws",
                )
                assertTrue(
                    QueryCounter.record { }.second.isEmpty(),
                    "record() must still be usable after a block threw",
                )
            },
        ) { }
}
