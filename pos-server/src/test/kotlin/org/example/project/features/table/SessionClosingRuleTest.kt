package org.example.project.features.table

import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.table.data.repository.TableSessionRepositoryImpl
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A session with no OPEN item carries no unpaid business and is ended — one rule for every path. */
class SessionClosingRuleTest {
    private val repo = TableSessionRepositoryImpl()

    @BeforeEach fun setup() = TestDatabase.setup()

    @AfterEach fun teardown() = TestDatabase.teardown()

    @Test
    fun `an item-less session is completed and its table freed`() {
        val tws = TestData.createTableWithSession("SCR1")

        val closed = transaction { repo.completeIfNoOpenItems(tws.session.id.value) }

        assertNotNull(closed)
        transaction {
            assertTrue(TableSession.findById(tws.session.id.value)!!.completed)
            assertEquals(TableStatus.FREE, RTable.findById(tws.table.id.value)!!.tableStatus)
        }
    }

    @Test
    fun `paid and cancelled lines do not keep a session open`() {
        val tws = TestData.createTableWithSession("SCR2")
        TestData.createOrderItemInSession(tws.session, ItemStatus.PAID)
        TestData.createOrderItemInSession(tws.session, ItemStatus.CANCELLED)

        assertNotNull(transaction { repo.completeIfNoOpenItems(tws.session.id.value) })
    }

    @Test
    fun `an OPEN item keeps the session open`() {
        val tws = TestData.createTableWithSession("SCR3")
        TestData.createOrderItemInSession(tws.session, ItemStatus.OPEN)

        assertNull(transaction { repo.completeIfNoOpenItems(tws.session.id.value) })
        transaction { assertFalse(TableSession.findById(tws.session.id.value)!!.completed) }
    }

    @Test
    fun `an already completed session is left alone`() {
        val tws = TestData.createTableWithSession("SCR4")
        transaction { repo.completeSession(tws.session.id.value) }

        assertNull(transaction { repo.completeIfNoOpenItems(tws.session.id.value) })
    }
}
