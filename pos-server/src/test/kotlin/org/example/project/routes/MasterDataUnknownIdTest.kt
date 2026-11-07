package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Deleting (or toggling) a master-data row that does not exist answers 404 (QA admin19 NOTE-b).
 *
 * Each of these reached the repository, whose `NoSuchElementException` (or, for printers,
 * `IllegalArgumentException`) StatusPages turned into a 500 (or a 400). The positive paths stay
 * covered by each feature's own route tests.
 */
@DisplayName("Master data — unknown id")
class MasterDataUnknownIdTest {
    private suspend fun assertNotFound(response: HttpResponse) {
        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    @Test
    fun `unknown ids answer 404 on delete and activate`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertNotFound(client.delete("/api/payment-types/999999") { bearerAuth(token) })
            assertNotFound(client.delete("/api/product-categories/999999") { bearerAuth(token) })
            assertNotFound(client.delete("/api/product-extras/999999") { bearerAuth(token) })
            assertNotFound(client.delete("/api/choice-groups/999999") { bearerAuth(token) })
            assertNotFound(client.delete("/api/printer-location/999999") { bearerAuth(token) })
            assertNotFound(client.patch("/api/printer-location/999999/activate") { bearerAuth(token) })
        }

    @Test
    fun `an existing payment type still deletes`() =
        testApp(setupData = {
            TestData.createManager()
            TestData.createPaymentType(name = "QA-Zahlart", isCash = false)
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val id =
                transaction {
                    PaymentType
                        .all()
                        .first { it.name == "QA-Zahlart" }
                        .id.value
                }
            val response = client.delete("/api/payment-types/$id") { bearerAuth(token) }
            assertEquals(true, response.status.isSuccess(), response.bodyAsText())
        }
}
