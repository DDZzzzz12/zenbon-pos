package org.example.project.features.order

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Cancel items — a reason is required")
class CancelItemsReasonTest {
    private val deviceId = "cancel-reason-device"

    private fun json(body: String) = TextContent(body, ContentType.Application.Json)

    private fun setup() {
        TestData.createAdmin()
        TestData.setTseMode(TseMode.SIMULATION)
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-CIR")
    }

    @Test
    fun `cancelling without a reason is 400 and leaves the item open`() =
        testApp(setupData = { setup() }) { client ->
            val admin = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val tws = TestData.createTableWithSession(tableNumber = "CIR1")
            val item = TestData.createOrderItemInSession(tws.session, ItemStatus.OPEN)

            val response =
                client.post("/api/items/cancel/${tws.table.id.value}") {
                    bearerAuth(admin)
                    setBody(json("""{"itemIds":[${item.id.value}]}"""))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals(ItemStatus.OPEN, transaction { OrderItem.findById(item.id)!!.itemStatus })
        }

    @Test
    fun `cancelling with a whitespace-only reason is 400`() =
        testApp(setupData = { setup() }) { client ->
            val admin = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val tws = TestData.createTableWithSession(tableNumber = "CIR2")
            val item = TestData.createOrderItemInSession(tws.session, ItemStatus.OPEN)

            val response =
                client.post("/api/items/cancel/${tws.table.id.value}") {
                    bearerAuth(admin)
                    setBody(json("""{"itemIds":[${item.id.value}],"reason":"   "}"""))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }
}
