package org.example.project.features.table

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@DisplayName("Delete table session — a reason is required and reaches the cancellations")
class DeleteSessionReasonTest {
    private val deviceId = "delete-session-reason-device"

    private fun setup() {
        TestData.createAdmin()
        TestData.setTseMode(TseMode.SIMULATION)
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-DSR")
    }

    @Test
    fun `deleting a session without a reason is 400 and keeps the session`() =
        testApp(setupData = { setup() }) { client ->
            val admin = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val tws = TestData.createTableWithSession(tableNumber = "DSR1")
            TestData.createOrderItemInSession(tws.session, ItemStatus.OPEN)

            val response = client.delete("/api/tables/${tws.table.id.value}/session") { bearerAuth(admin) }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertFalse(transaction { TableSession.findById(tws.session.id)!!.completed }, "the session must stay open")
        }

    @Test
    fun `deleting a session with a blank reason is 400`() =
        testApp(setupData = { setup() }) { client ->
            val admin = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val tws = TestData.createTableWithSession(tableNumber = "DSR2")
            TestData.createOrderItemInSession(tws.session, ItemStatus.OPEN)

            val response = client.delete("/api/tables/${tws.table.id.value}/session?reason=%20%20") { bearerAuth(admin) }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }

    @Test
    fun `the reason reaches the cancelled items and the fallback text is gone`() =
        testApp(setupData = { setup() }) { client ->
            val admin = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val tws = TestData.createTableWithSession(tableNumber = "DSR3")
            TestData.createOrderItemInSession(tws.session, ItemStatus.OPEN)

            val response =
                client.delete("/api/tables/${tws.table.id.value}/session?reason=Falscher%20Tisch") { bearerAuth(admin) }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val reasons = transaction { CancellationTable.selectAll().map { it[CancellationTable.reason] } }
            assertEquals(listOf<String?>("Falscher Tisch"), reasons)
        }
}
