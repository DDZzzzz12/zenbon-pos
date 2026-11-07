package org.example.project.features.bill

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.features.bill.domain.models.Bill
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * emu20 NOTE-t: the Z-Bon receipt list (`/bills/search` by window) omitted the closing's training
 * receipts while `bill.zbon_id` and DSFinV-K carried them. A receipt LIST shows every signed Beleg,
 * so the list asks with `includeTraining=true` and gets them flagged; every other caller keeps the
 * default and still never sees a Schulungsbeleg.
 */
@DisplayName("Bill search: training receipts only on request, and flagged")
class BillSearchTrainingTest {
    private val closedAt = LocalDate(2026, 10, 3).atTime(12, 0, 0)

    private fun seed() {
        TestData.createAdmin()
        val tws = TestData.createTableWithSession("TRN")
        transaction {
            listOf("REAL-1" to false, "TRAIN-1" to true).forEach { (number, training) ->
                Bill.new {
                    this.employeeId = tws.session.employeeId
                    this.tableSession = tws.session
                    this.billNumber = number
                    this.billAmount = BigDecimal("7.4000")
                    this.paidAmount = BigDecimal("7.4000")
                    this.startTime = closedAt
                    this.endTime = closedAt
                    this.isTraining = training
                }
            }
        }
    }

    private fun numbersAndFlags(body: String): Map<String, Boolean> =
        Json.parseToJsonElement(body).jsonArray.associate {
            val bill = it.jsonObject
            bill.getValue("billNumber").jsonPrimitive.content to (bill["isTraining"]?.jsonPrimitive?.boolean ?: false)
        }

    @Test
    fun `by default a training receipt is not listed`() =
        testApp(setupData = { seed() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/v1/bills/search?includeDeleted=true") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(mapOf("REAL-1" to false), numbersAndFlags(response.bodyAsText()))
        }

    @Test
    fun `with includeTraining the training receipt is listed and flagged`() =
        testApp(setupData = { seed() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/v1/bills/search?includeDeleted=true&includeTraining=true") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(mapOf("REAL-1" to false, "TRAIN-1" to true), numbersAndFlags(response.bodyAsText()))
        }
}
