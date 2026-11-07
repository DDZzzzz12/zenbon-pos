package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An unreadable product or extra price is refused with the dedicated `PRICE_INVALID` code, which the
 * client maps to a localized sentence (QA admin21 NOTE-d21: the raw English "Price must be a valid
 * decimal number" reached the German product form under the generic VALIDATION_FAILED).
 */
@DisplayName("Product/extra price validation")
class ProductPriceValidationTest {
    private var categoryId = 0

    private fun productBody(price: String) =
        """{"productNumber":"QA1","name":"Testgericht","description":null,"price":"$price",""" +
            """"categoryId":$categoryId,"productType":"FOOD","isActive":true}"""

    @Test
    fun `a comma price on product create answers 400 PRICE_INVALID`() =
        testApp(setupData = {
            TestData.createManager()
            categoryId = TestData.createCategory().id.value
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/products") {
                    bearerAuth(token)
                    setBody(TextContent(productBody("12,90"), ContentType.Application.Json))
                }
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertTrue("PRICE_INVALID" in body, body)
        }

    @Test
    fun `a negative price on product update answers 400 PRICE_INVALID`() =
        testApp(setupData = {
            TestData.createManager()
            categoryId = TestData.createCategory().id.value
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val created =
                client.post("/api/products") {
                    bearerAuth(token)
                    setBody(TextContent(productBody("12.90"), ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Created.value / 100, created.status.value / 100, created.bodyAsText())
            val id = Regex("\"id\":(\\d+)").find(created.bodyAsText())!!.groupValues[1]
            val response =
                client.put("/api/products/$id") {
                    bearerAuth(token)
                    setBody(TextContent(productBody("-1.00"), ContentType.Application.Json))
                }
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertTrue("PRICE_INVALID" in body, body)
        }

    @Test
    fun `an unreadable extra price answers 400 PRICE_INVALID`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/product-extras") {
                    bearerAuth(token)
                    setBody(TextContent("""{"name":"Käse","price":"1,5x","isActive":true}""", ContentType.Application.Json))
                }
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertTrue("PRICE_INVALID" in body, body)
        }
}
