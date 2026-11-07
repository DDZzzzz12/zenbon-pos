package org.example.project.features.table

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2026-09-18 e2e finding #3, plus the read-only GET (finding #4). */
class TableSessionLifecycleTest {
    private val deviceId = "table-lifecycle-device"
    private var tableId = 0L
    private var sessionId = 0L
    private var targetTableId = 0L
    private var virtualId = 0L

    private fun setupBase() {
        TestData.createShopInfo()
        // ShopConfigurationManager's cache is process-wide; rotation tests can leave round-robin ON for the next class.
        runBlocking { TestData.setRoundRobinAssignment(false) }
        TestData.createManager(employeeNumber = "TSL_MGR", username = "tsl_mgr")
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-TSL")
    }

    private fun setupEmptySession() {
        setupBase()
        val tws = TestData.createTableWithSession("TSL1")
        tableId = tws.table.id.value
        sessionId = tws.session.id.value
    }

    private fun setupPaidOnlySession() {
        setupEmptySession()
        TestData.createOrderItemInSession(transaction { TableSession.findById(sessionId)!! }, ItemStatus.PAID)
    }

    private fun setupTransferPair() {
        setupEmptySession()
        val source = transaction { TableSession.findById(sessionId)!! }
        TestData.createOrderItemInSession(source, ItemStatus.PAID)
        TestData.createOrderItemInSession(source, ItemStatus.OPEN)
        val target = TestData.createTableWithSession("TSL2")
        targetTableId = target.table.id.value
    }

    private fun setupFreeTable() {
        setupBase()
        tableId = TestData.createTable("TSL_FREE").id.value
        virtualId = TestData.createTable("TSL_QS", locationTypeId = WellKnownLocationTypes.QUICK_SALE).id.value
    }

    private suspend fun transferTable(
        client: HttpClient,
        token: String,
        from: Long,
        to: Long,
        toEmployee: String,
    ): HttpResponse =
        client.post("/api/tables/$from/transfer") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(RTableDto.TransferTableRequestDto(targetTableId = to, targetEmployeeNumber = toEmployee, reason = "Test"))
        }

    @Test
    fun `deleting an item-less session completes it and frees the table`() =
        testApp(setupData = { setupEmptySession() }) { client ->
            val token = TestAuthHelper.loginAs(client, "tsl_mgr", deviceId = deviceId)

            val response = client.delete("/api/tables/$tableId/session?reason=Test") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            transaction {
                val table = RTable.findById(tableId)!!
                assertEquals(TableStatus.FREE, table.tableStatus)
                assertNull(table.currentSession)
                assertTrue(TableSession.findById(sessionId)!!.completed)
            }
        }

    @Test
    fun `deleting a session whose lines are all paid closes it`() =
        testApp(setupData = { setupPaidOnlySession() }) { client ->
            val token = TestAuthHelper.loginAs(client, "tsl_mgr", deviceId = deviceId)

            val response = client.delete("/api/tables/$tableId/session?reason=Test") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            transaction { assertTrue(TableSession.findById(sessionId)!!.completed) }
        }

    @Test
    fun `deleting when the table has no session is a 409, not a silent 200`() =
        testApp(setupData = { setupEmptySession() }) { client ->
            val token = TestAuthHelper.loginAs(client, "tsl_mgr", deviceId = deviceId)
            client.delete("/api/tables/$tableId/session?reason=Test") { bearerAuth(token) }

            val second = client.delete("/api/tables/$tableId/session?reason=Test") { bearerAuth(token) }

            assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())
            assertTrue(second.bodyAsText().contains("TABLE_HAS_NO_SESSION"))
        }

    @Test
    fun `moving a table with a paid round onto an occupied table closes the source`() =
        testApp(setupData = { setupTransferPair() }) { client ->
            val token = TestAuthHelper.loginAs(client, "tsl_mgr", deviceId = deviceId)

            val response = transferTable(client, token, from = tableId, to = targetTableId, toEmployee = "TBL_TSL2")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            transaction {
                assertTrue(TableSession.findById(sessionId)!!.completed, "source kept a paid line and nothing open")
                assertEquals(TableStatus.FREE, RTable.findById(tableId)!!.tableStatus)
            }
        }

    @Test
    fun `GET currentSession from a non-terminal caller never creates a session`() =
        testApp(setupData = { setupFreeTable() }) { client ->
            val token = TestAuthHelper.loginAs(client, "tsl_mgr") // no deviceId → not a paired terminal

            val response = client.get("/api/tables/$tableId/currentSession") { bearerAuth(token) }

            assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("TABLE_HAS_NO_SESSION"))
            transaction { assertEquals(TableStatus.FREE, RTable.findById(tableId)!!.tableStatus) }
        }

    @Test
    fun `GET currentSession from a paired terminal still opens the table (one-release legacy)`() =
        testApp(setupData = { setupFreeTable() }) { client ->
            val token = TestAuthHelper.loginAs(client, "tsl_mgr", deviceId = deviceId)

            val response = client.get("/api/tables/$tableId/currentSession") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            transaction { assertEquals(TableStatus.OCCUPIED, RTable.findById(tableId)!!.tableStatus) }
        }

    @Test
    fun `the legacy GET never opens the QUICK_SALE virtual table`() =
        testApp(setupData = { setupFreeTable() }) { client ->
            val token = TestAuthHelper.loginAs(client, "tsl_mgr", deviceId = deviceId)

            val response = client.get("/api/tables/$virtualId/currentSession") { bearerAuth(token) }

            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("TABLE_VIRTUAL_NOT_ORDERABLE"))
            transaction { assertEquals(TableStatus.FREE, RTable.findById(virtualId)!!.tableStatus) }
        }
}
