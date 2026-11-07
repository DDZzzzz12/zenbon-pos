package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server's ContentNegotiation Json is the ONE encoder every API response goes through.
 * Pretty-printing it inflates every payload by 15-30% for no consumer: all four client Json
 * instances (PosHttpClient plus the three HttpClientFactory actuals) are compact.
 */
@DisplayName("API responses are serialized compactly")
class JsonPayloadCompactnessTest {
    @Test
    fun `product list response contains no pretty-print whitespace`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                val cat = TestData.createCategory("Compact Cat")
                TestData.createProduct(
                    categoryId = cat.id.value,
                    productName = "Compact A",
                    productNumber = "CMP_A",
                )
                TestData.createProduct(
                    categoryId = cat.id.value,
                    productName = "Compact B",
                    productNumber = "CMP_B",
                )
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/products") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            assertTrue(
                body.contains("CMP_A") && body.contains("CMP_B"),
                "sanity: both seeded products must be in the body; got:\n$body",
            )
            assertFalse(
                body.contains('\n'),
                "a pretty-printed body puts every field on its own line; the server Json must set " +
                    "prettyPrint = false. Body was:\n$body",
            )
            assertFalse(
                body.contains("\": "),
                "compact JSON writes \"key\":value with no space after the colon; got:\n$body",
            )
        }
}
