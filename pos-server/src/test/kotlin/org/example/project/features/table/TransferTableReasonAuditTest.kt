package org.example.project.features.table

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Transfer table — the reason is written to the audit row in every case")
class TransferTableReasonAuditTest {
    private val deviceId = "transfer-table-audit-device"

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager(employeeNumber = "TTA_MGR", username = "tta_mgr")
        TestData.createCashier(employeeNumber = "TTA_C2", username = "tta_c2")
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-TTA")
    }

    private fun auditNames() = transaction { TransactionAuditLog.all().filter { it.operation == "TRANSFER_TABLE" }.map { it.entityName } }

    @Test
    fun `reassigning a session to another waiter records the reason`() =
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAs(client, "tta_mgr", deviceId = deviceId)
            val tws = TestData.createTableWithSession("TTA1")

            val response =
                client.post("/api/tables/${tws.table.id.value}/transfer") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(RTableDto.TransferTableRequestDto(tws.table.id.value, "TTA_C2", "Kellnerwechsel"))
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(auditNames().any { it?.contains("Kellnerwechsel") == true }, auditNames().toString())
        }

    @Test
    fun `moving a whole session to a free table records the reason`() =
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAs(client, "tta_mgr", deviceId = deviceId)
            val tws = TestData.createTableWithSession("TTA2")
            val free = TestData.createTable("TTA2_FREE")

            val response =
                client.post("/api/tables/${tws.table.id.value}/transfer") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(RTableDto.TransferTableRequestDto(free.id.value, "TTA_C2", "Gast hat Platz gewechselt"))
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(auditNames().any { it?.contains("Gast hat Platz gewechselt") == true }, auditNames().toString())
        }
}
