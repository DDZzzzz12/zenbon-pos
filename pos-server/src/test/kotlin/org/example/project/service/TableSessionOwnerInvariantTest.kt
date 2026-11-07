package org.example.project.service

import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.table.data.repository.TableSessionRepositoryImpl
import org.example.project.features.table.data.service.TableSessionServiceImpl
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spec 2026-09-14 §4.2: whatever path forgets TableSessionOwnerPolicy, starting a session for a terminal
 * account is refused; reusing an active session is not a start; the repair job leaves a terminal-owned
 * stranded session alone instead of rolling back.
 */
class TableSessionOwnerInvariantTest {
    private val repository = TableSessionRepositoryImpl()

    @Test
    fun `a new session is never started for a terminal account`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val terminal = TestData.createTerminalDeviceEmployee()
            val table = TestData.createTable(tableNumber = "INV_FREE")

            val thrown =
                runCatching {
                    transaction {
                        repository.getOrCreateSession(RTable.findById(table.id)!!, Employee.findById(terminal.id)!!)
                    }
                }.exceptionOrNull()

            assertIs<IllegalStateException>(thrown)
            val active =
                transaction {
                    TableSession
                        .find { (TableSessionTable.table eq table.id.value) and (TableSessionTable.completed eq false) }
                        .count()
                }
            assertEquals(0L, active, "a refused start must leave the table without a session")
        }

    @Test
    fun `an active session is reused for a terminal account`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val terminal = TestData.createTerminalDeviceEmployee()
            val tws = TestData.createTableWithSession(tableNumber = "INV_OPEN")

            val (created, sessionId) =
                transaction {
                    val result = repository.getOrCreateSession(RTable.findById(tws.table.id)!!, Employee.findById(terminal.id)!!)
                    result.first to result.second.id.value
                }

            assertFalse(created)
            assertEquals(tws.session.id.value, sessionId)
        }

    @Test
    fun `repair skips a stranded session owned by a terminal account and repairs the rest`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val terminal = TestData.createTerminalDeviceEmployee()
            val terminalTable = TestData.createTable(tableNumber = "INV_REP_T")
            val personTws = TestData.createTableWithSession(tableNumber = "INV_REP_P")
            val category = TestData.createCategory(name = "INV_Cat")
            val product = TestData.createProduct(categoryId = category.id.value, productName = "INV_Prod", productNumber = "INV_P")
            val vat = TestData.createDefaultVatRates().first()

            // Two corrupt tables of the kind the repair exists for: each still points at a COMPLETED session
            // holding an OPEN item. The terminal-owned one is built on the entity, because the invariant under
            // test stops getOrCreateSession from producing it at all.
            val (terminalSessionId, personSessionId) =
                transaction {
                    val terminalSession = RTable.findById(terminalTable.id)!!.startSession(Employee.findById(terminal.id)!!)
                    val personSession = TableSession.findById(personTws.session.id.value)!!
                    listOf(terminalSession, personSession).forEach { session ->
                        OrderItem.new {
                            this.session = session
                            this.product = Product.findById(product.id)!!
                            this.unitPrice = BigDecimal("9.99")
                            this.finalPrice = BigDecimal("9.99")
                            this.itemStatus = ItemStatus.OPEN
                            this.vat = Vat.findById(vat.id)!!
                            this.discount = BigDecimal.ONE
                            this.customPrice = false
                            this.takeAway = false
                            this.transferred = false
                            this.sideNotes = null
                        }
                        session.completed = true
                    }
                    terminalSession.id.value to personSession.id.value
                }

            val result = TableSessionServiceImpl(TableSessionRepositoryImpl(), SalesInvoiceBillInvoiceLink()).repairCompletedButCurrentSessions()

            assertEquals(1, result.reopenedSessions, "only the person-owned session is re-opened")
            assertEquals(1, result.movedOpenItems, "the one OPEN item on the person-owned session is moved")
            assertEquals(0, result.clearedDanglingPointers, "the terminal-owned table is skipped, not treated as an empty dangling pointer")
            transaction {
                val terminalCurrent = RTable.findById(terminalTable.id)!!.currentSession!!
                assertEquals(terminalSessionId, terminalCurrent.id.value, "the terminal-owned table is left as it was")
                assertTrue(terminalCurrent.completed)

                val personCurrent = RTable.findById(personTws.table.id)!!.currentSession!!
                assertNotEquals(personSessionId, personCurrent.id.value, "the person-owned table got a fresh session")
                assertFalse(personCurrent.completed)
            }
        }
}
