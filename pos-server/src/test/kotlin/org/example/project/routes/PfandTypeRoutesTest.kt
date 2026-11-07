package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PfandTypeRoutesTest {
    @Test
    fun `create then list returns the type`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val create =
                client.post("/api/pfand-types") {
                    bearerAuth(token)
                    setBody(TextContent("""{"name":"Einweg 0,25","depositValue":"0.25","vatClass":"A"}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Created, create.status, "body: ${create.bodyAsText()}")

            val list = client.get("/api/pfand-types") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains("Einweg 0,25"))
        }

    @Test
    fun `create with unseeded vat class C is 400`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val res =
                client.post("/api/pfand-types") {
                    bearerAuth(token)
                    setBody(TextContent("""{"name":"X","depositValue":"0.25","vatClass":"C"}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.BadRequest, res.status)
        }
}
