package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.Orders
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * QA finding NF-2 (rt42): a real-mode employee ordering onto a table whose open session is a
 * training session (or the other way round) answered 500 INTERNAL_ERROR — the repository's
 * `IllegalStateException` reached StatusPages. It is a 409 `TRAINING_MODE_MISMATCH`, like every other
 * place that refuses the mix, and nothing is booked.
 */
@DisplayName("Order onto a session of the other training mode")
class OrderTrainingSessionMismatchRouteTest {
    private suspend fun order(
        client: HttpClient,
        token: String,
        tableId: Long,
        productId: Long,
    ): HttpResponse =
        client.post("/api/orders") {
            bearerAuth(token)
            setBody(
                TextContent(
                    """{"tableInfoId": 0, "tableId": $tableId, "orderItems": [""" +
                        """{"productId": $productId, "unitPrice": "9.99", "quantity": 1}]}""",
                    ContentType.Application.Json,
                ),
            )
        }

    private fun productId(): Long {
        TestData.createDefaultVatRates()
        val category = TestData.createCategory(name = "TRN_Cat")
        return TestData.createProduct(categoryId = category.id.value, productName = "TRN_Prod", productNumber = "TRN_P").id.value
    }

    private val deviceId = "order-training-mismatch-device"

    private fun setup() {
        TestData.createAdmin()
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-ORDER-TRAINING-1")
        TestData.createCashier(employeeNumber = "TRN_REAL", username = "real_cashier")
        val trainee = TestData.createCashier(employeeNumber = "TRN_TRAINEE", username = "trainee")
        transaction { Employee.findById(trainee.id)!!.trainingMode = true }
    }

    @Test
    fun `a real order onto a training session is a 409 and books nothing`() =
        testApp(setupData = { setup() }) { client ->
            val product = productId()
            val table = TestData.createTable(tableNumber = "TRN_T1").id.value
            val trainee = TestAuthHelper.loginAs(client, "trainee", deviceId = deviceId)
            val real = TestAuthHelper.loginAs(client, "real_cashier", deviceId = deviceId)

            val opened = order(client, trainee, table, product)
            assertEquals(HttpStatusCode.Created, opened.status, "the trainee opens the table: ${opened.bodyAsText()}")
            val ordersBefore = transaction { Orders.all().count() }

            val refused = order(client, real, table, product)

            assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
            assertEquals(PosErrorCode.TRAINING_MODE_MISMATCH.name, testJson.decodeFromString<ProblemDetail>(refused.bodyAsText()).code)
            assertEquals(ordersBefore, transaction { Orders.all().count() }, "the refused order stored nothing")
        }

    @Test
    fun `a training order onto a real session is a 409 as well`() =
        testApp(setupData = { setup() }) { client ->
            val product = productId()
            val table = TestData.createTable(tableNumber = "TRN_T2").id.value
            val trainee = TestAuthHelper.loginAs(client, "trainee", deviceId = deviceId)
            val real = TestAuthHelper.loginAs(client, "real_cashier", deviceId = deviceId)

            val opened = order(client, real, table, product)
            assertEquals(HttpStatusCode.Created, opened.status, "the real cashier opens the table: ${opened.bodyAsText()}")

            val refused = order(client, trainee, table, product)

            assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
            assertEquals(PosErrorCode.TRAINING_MODE_MISMATCH.name, testJson.decodeFromString<ProblemDetail>(refused.bodyAsText()).code)
        }
}
